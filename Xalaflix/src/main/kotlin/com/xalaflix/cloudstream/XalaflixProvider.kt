package com.xalaflix.cloudstream

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class XalaflixProvider : MainAPI() {
    override var mainUrl = XalaflixDomainResolver.REGISTRY_ORIGIN
    override var name = "Xalaflix"
    override var lang = "fr"
    override val hasMainPage = true
    override val usesWebView = false
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "movies" to "Derniers films Xalaflix",
        "tv-shows" to "Dernières séries Xalaflix",
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language" to "fr-FR,fr;q=0.9,en;q=0.7",
    )
    private val resolver = XalaflixDomainResolver(headers)

    internal suspend fun prepareDomain() { ensureDomain() }

    private suspend fun ensureDomain(): String {
        val origin = resolver.resolve()
        mainUrl = origin
        return origin
    }

    private data class Page(val document: Document, val origin: String)
    private data class Playback(val path: String, val episodePath: String? = null) {
        fun encode(): String = listOf(path, episodePath.orEmpty()).joinToString("\n")
        companion object {
            fun decode(raw: String): Playback? {
                val parts = raw.split('\n', limit = 2)
                val path = parts.firstOrNull()?.takeIf { it.startsWith('/') } ?: return null
                return Playback(path, parts.getOrNull(1)?.takeIf(String::isNotBlank))
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val route = "/${request.data}" + if (page > 1) "?page=$page" else ""
        val loaded = fetchWithRefresh(route) ?: throw ErrorLoadingException("Catalogue Xalaflix inaccessible")
        val items = parseCards(loaded.document, loaded.origin)
        if (items.isEmpty()) throw ErrorLoadingException("Catalogue Xalaflix vide")
        val hasNext = loaded.document.select("a[href*='page=${page + 1}'], a[rel=next]").isNotEmpty() || items.size >= 20
        return newHomePageResponse(request, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        // Aucun appel de recherche n'est effectué avant la résolution.
        val origin = resolver.resolvedOriginOrNull() ?: runCatching { ensureDomain() }.getOrNull() ?: return emptyList()
        mainUrl = origin
        val route = "/search?keyword=${encode(query)}"
        fetch(origin, route)?.let { page ->
            val results = parseCards(page, origin)
            if (results.isNotEmpty()) return results
        }
        resolver.invalidate(origin)
        val refreshed = runCatching { ensureDomain() }.getOrNull() ?: return emptyList()
        return fetch(refreshed, route)?.let { parseCards(it, refreshed) }.orEmpty()
    }

    override suspend fun load(url: String): LoadResponse {
        val path = detailPath(url) ?: throw ErrorLoadingException("URL Xalaflix invalide")
        // Une fiche ouverte depuis l'historique déclenche elle aussi la résolution.
        val loaded = fetchWithRefresh(path) ?: throw ErrorLoadingException("Fiche Xalaflix inaccessible")
        val doc = loaded.document
        val type = if (path.startsWith("/tv-show/")) TvType.TvSeries else TvType.Movie
        val title = doc.selectFirst("h1, .detail_page-infor h2, .heading-name")?.text()?.trim()
            ?: doc.title().substringBefore(" Streaming").trim().takeIf(String::isNotBlank)
            ?: throw ErrorLoadingException("Titre Xalaflix introuvable")
        val poster = absolute(doc.selectFirst(".detail_page-infor img, .film-poster-img, .movie-poster img, img[src*=image.tmdb]")?.absUrl("src"), loaded.origin)
        val plot = doc.selectFirst(".description, .detail_page-infor .description, .film-description, [class*=overview]")?.text()?.trim()
        val year = YEAR.find(doc.text())?.value?.toIntOrNull()
        val tags = doc.select("a[href*=/genre/]").map { it.text().trim() }.filter(String::isNotBlank).distinct()

        if (type == TvType.Movie) {
            return newMovieLoadResponse(title, url, type, Playback(path).encode()) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        val episodeLinks = parseEpisodes(doc, loaded.origin)
        val episodes = if (episodeLinks.isNotEmpty()) episodeLinks.map { episode ->
            newEpisode(Playback(path, episode.first).encode()) {
                name = episode.fourth ?: "Épisode ${episode.third}"
                season = episode.second
                this.episode = episode.third
            }
        } else listOf(newEpisode(Playback(path).encode()) { name = "Lecture"; season = 1; episode = 1 })

        return newTvSeriesLoadResponse(title, url, type, episodes) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val playback = Playback.decode(data) ?: return false
        // Même garde que load(): résolution obligatoire avant toute lecture.
        val route = playback.episodePath ?: playback.path
        val loaded = fetchWithRefresh(route) ?: return false
        val referer = "${loaded.origin}$route"
        val urls = extractPlayers(loaded.document, loaded.origin).toMutableList()
        // Certains boutons Xalaflix ouvrent d'abord une page lecteur interne.
        // On la déroule une seule fois avant de déléguer aux extracteurs.
        urls.filter { it.second.startsWith(loaded.origin) }.toList().forEach { (_, internalUrl) ->
            val internalPath = runCatching {
                val uri = URI(internalUrl)
                uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
            }.getOrNull() ?: return@forEach
            fetch(loaded.origin, internalPath)?.let { urls += extractPlayers(it, loaded.origin) }
        }
        var emitted = false
        for ((label, playerUrl) in urls.distinctBy { it.second }) {
            if (DIRECT_MEDIA.containsMatchIn(playerUrl)) {
                callback(newExtractorLink(
                    source = name,
                    name = "Xalaflix · $label",
                    url = playerUrl,
                    type = if (playerUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = referer
                    quality = getQualityFromName(label)
                    headers = this@XalaflixProvider.headers
                })
                emitted = true
            } else runCatching {
                loadExtractor(
                    url = playerUrl,
                    referer = referer,
                    subtitleCallback = subtitleCallback,
                    callback = { emitted = true; callback(it) },
                )
            }
        }
        return emitted
    }

    private suspend fun fetchWithRefresh(path: String): Page? {
        val first = ensureDomain()
        fetch(first, path)?.let { return Page(it, first) }
        resolver.invalidate(first)
        val refreshed = ensureDomain()
        return fetch(refreshed, path)?.let { Page(it, refreshed) }
    }

    private suspend fun fetch(origin: String, path: String): Document? {
        val response = runCatching {
            app.get("$origin$path", headers = headers, referer = "$origin/", cacheTime = 0, timeout = 15L)
        }.getOrNull() ?: return null
        if (response.okhttpResponse.code !in 200..299) return null
        val doc = Jsoup.parse(response.text, "$origin/")
        return doc.takeIf { it.select("a[href*=/movie/], a[href*=/tv-show/], iframe, video, source, h1").isNotEmpty() }
    }

    private fun parseCards(doc: Document, origin: String): List<SearchResponse> {
        val results = LinkedHashMap<String, SearchResponse>()
        doc.select("a[href*=/movie/], a[href*=/tv-show/]").forEach { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            val path = detailPath(href) ?: return@forEach
            val container = anchor.closest(".flw-item, .film_list-wrap, .item, article, li") ?: anchor
            val title = sequenceOf(
                anchor.attr("title"),
                container.selectFirst("h2, h3, .film-name, .title")?.text(),
                anchor.selectFirst("img")?.attr("alt"),
                anchor.text(),
            ).filterNotNull().map(String::trim).firstOrNull(String::isNotBlank) ?: return@forEach
            if (title.equals("View All", true)) return@forEach
            val poster = anchor.selectFirst("img")?.let { imageUrl(it, origin) }
                ?: container.selectFirst("img")?.let { imageUrl(it, origin) }
            val itemUrl = "$origin$path"
            val response = if (path.startsWith("/tv-show/")) {
                newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) { posterUrl = poster }
            } else newMovieSearchResponse(title, itemUrl, TvType.Movie) { posterUrl = poster }
            results.putIfAbsent(path, response)
        }
        return results.values.toList()
    }

    private fun parseEpisodes(doc: Document, origin: String): List<Quad> {
        val result = LinkedHashMap<String, Quad>()
        doc.select("a[href]").forEach { link ->
            val text = link.text().trim()
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            val match = EPISODE.find("$text $href") ?: return@forEach
            val episode = match.groupValues[2].toIntOrNull() ?: return@forEach
            val season = match.groupValues[1].toIntOrNull() ?: 1
            val uri = runCatching { URI(href) }.getOrNull()
            val path = uri?.rawPath?.plus(uri.rawQuery?.let { "?$it" }.orEmpty()) ?: href
            if (!path.startsWith('/')) return@forEach
            result.putIfAbsent(path, Quad(path, season, episode, text.takeIf(String::isNotBlank)))
        }
        return result.values.sortedWith(compareBy<Quad> { it.second }.thenBy { it.third })
    }

    private data class Quad(val first: String, val second: Int, val third: Int, val fourth: String?)

    private fun extractPlayers(doc: Document, origin: String): List<Pair<String, String>> {
        val result = LinkedHashMap<String, Pair<String, String>>()
        doc.select("iframe[src], video[src], source[src], [data-src], [data-url], a[href]").forEach { element ->
            val raw = listOf("src", "data-src", "data-url", "href").map { element.attr(it) }.firstOrNull(String::isNotBlank) ?: return@forEach
            val url = absolute(raw, origin) ?: return@forEach
            if (!url.startsWith("http") || detailPath(url) != null) return@forEach
            val label = element.attr("title").ifBlank { element.text() }.ifBlank { URI(url).host ?: "Lecteur" }
            result[url] = label to url
        }
        URL_IN_SCRIPT.findAll(doc.html()).forEach { match ->
            val url = match.value.replace("\\/", "/")
            if (DIRECT_MEDIA.containsMatchIn(url)) result[url] = "Direct" to url
        }
        return result.values.toList()
    }

    private fun imageUrl(image: Element, origin: String): String? = absolute(
        sequenceOf("data-src", "data-original", "data-lazy-src", "src").map { image.attr(it) }.firstOrNull(String::isNotBlank),
        origin,
    )

    private fun detailPath(url: String): String? {
        val path = runCatching { URI(url).path }.getOrNull() ?: url.substringBefore('?')
        return path.takeIf { DETAIL.matches(it) }
    }

    private fun absolute(raw: String?, origin: String): String? {
        if (raw.isNullOrBlank() || raw.startsWith("data:") || raw.startsWith("javascript:")) return null
        return runCatching { URI("$origin/").resolve(raw).toString() }.getOrNull()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private val DETAIL = Regex("^/(movie|tv-show)/[^/?#]+/?$", RegexOption.IGNORE_CASE)
        private val YEAR = Regex("\\b(?:19|20)\\d{2}\\b")
        private val EPISODE = Regex("(?i)(?:saison|season|s)[ ._-]*(\\d+).*?(?:episode|épisode|ep|e)[ ._-]*(\\d+)")
        private val DIRECT_MEDIA = Regex("(?i)\\.(?:m3u8|mp4|mpd)(?:[?#]|$)")
        private val URL_IN_SCRIPT = Regex("https?://[^\\s\\\"'<>]+")
    }
}
