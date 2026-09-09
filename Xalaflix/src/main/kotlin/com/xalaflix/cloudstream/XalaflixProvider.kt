package com.xalaflix.cloudstream

import android.util.Log
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
import org.json.JSONObject
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
    private data class Playback(val path: String, val mediaId: String?, val episodeId: String? = null) {
        fun encode(): String = listOf(path, mediaId.orEmpty(), episodeId.orEmpty()).joinToString("\n")
        companion object {
            fun decode(raw: String): Playback? {
                val parts = raw.split('\n', limit = 3)
                val path = parts.firstOrNull()?.takeIf { it.startsWith('/') } ?: return null
                val mediaId = parts.getOrNull(1)?.takeIf(String::isNotBlank)
                return Playback(path, mediaId, parts.getOrNull(2)?.takeIf(String::isNotBlank))
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
        val route = "/search/${query.trim().replace(Regex("\\s+"), "-")}"
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
        val mediaId = findMediaId(doc, url)
        Log.i(LOG_TAG, "Fiche type=$type path=$path mediaId=${mediaId ?: "absent"}")

        if (type == TvType.Movie) {
            return newMovieLoadResponse(title, url, type, Playback(path, mediaId).encode()) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        val episodeLinks = mediaId?.let {
            loadSeriesEpisodes(loaded.origin, it, "${loaded.origin}$path")
        }.orEmpty().ifEmpty { parseInlineEpisodes(doc) }
        val episodes = episodeLinks.map { episode ->
            newEpisode(Playback(path, mediaId, episode.first).encode()) {
                name = episode.fourth ?: "Épisode ${episode.third}"
                season = episode.second
                this.episode = episode.third
            }
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("Aucun épisode Xalaflix disponible")

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
        val loaded = fetchWithRefresh(playback.path) ?: return false
        val referer = "${loaded.origin}${playback.path}"
        val mediaId = playback.mediaId ?: findMediaId(loaded.document, referer) ?: return false
        val playableId = playback.episodeId
            ?: loadMovieEpisodeId(loaded.origin, mediaId, referer)
            ?: return extractAndEmit(loaded.document, loaded.origin, referer, subtitleCallback, callback)
        Log.i(LOG_TAG, "Lecture path=${playback.path} mediaId=$mediaId playableId=$playableId")
        val urls = loadServerSources(loaded.origin, playableId, referer).ifEmpty {
            extractPlayers(loaded.document, loaded.origin)
        }
        Log.i(LOG_TAG, "Sources trouvées=${urls.size}")
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

    private suspend fun extractAndEmit(
        document: Document,
        origin: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var emitted = false
        for ((_, url) in extractPlayers(document, origin)) {
            runCatching {
                loadExtractor(url, referer, subtitleCallback) { emitted = true; callback(it) }
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

    private suspend fun ajaxDocument(origin: String, paths: List<String>, referer: String): Document? {
        for (path in paths) {
            val response = runCatching {
                app.get(
                    "$origin$path",
                    headers = headers + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "*/*",
                    ),
                    referer = referer,
                    cacheTime = 0,
                    timeout = 15L,
                )
            }.onFailure { Log.w(LOG_TAG, "AJAX impossible path=$path", it) }.getOrNull() ?: continue
            Log.i(
                LOG_TAG,
                "AJAX path=$path HTTP=${response.okhttpResponse.code} taille=${response.text.length} aperçu='${preview(response.text)}'",
            )
            if (response.okhttpResponse.code !in 200..299) continue
            val html = runCatching {
                val json = JSONObject(response.text)
                listOf("html", "result", "data").firstNotNullOfOrNull { key -> json.optString(key).takeIf(String::isNotBlank) }
            }.getOrNull() ?: response.text
            val document = Jsoup.parseBodyFragment(html, "$origin/")
            if (document.select("[data-id], a[href], iframe[src]").isNotEmpty()) return document
        }
        return null
    }

    private suspend fun loadMovieEpisodeId(origin: String, mediaId: String, referer: String): String? {
        val document = ajaxDocument(origin, listOf(
            "/ajax/movie/episodes/$mediaId",
            "/ajax/v2/movie/episodes/$mediaId",
        ), referer) ?: return null
        return document.selectFirst("[data-id]")?.attr("data-id")?.takeIf(String::isNotBlank)
    }

    private suspend fun loadSeriesEpisodes(origin: String, mediaId: String, referer: String): List<Quad> {
        val seasons = ajaxDocument(origin, listOf(
            "/ajax/v2/tv/seasons/$mediaId",
            "/ajax/tv/seasons/$mediaId",
        ), referer) ?: return emptyList()
        val result = ArrayList<Quad>()
        for ((seasonIndex, seasonNode) in seasons.select("[data-id]").withIndex()) {
            val seasonId = seasonNode.attr("data-id").takeIf(String::isNotBlank) ?: continue
            val seasonNumber = SEASON.find(seasonNode.text())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: seasonIndex + 1
            val episodes = ajaxDocument(origin, listOf(
                "/ajax/v2/season/episodes/$seasonId",
                "/ajax/season/episodes/$seasonId",
            ), referer) ?: continue
            episodes.select("[data-id]").forEachIndexed { index, node ->
                val episodeId = node.attr("data-id").takeIf(String::isNotBlank) ?: return@forEachIndexed
                val text = node.text().trim()
                val number = EPISODE_NUMBER.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: index + 1
                result += Quad(episodeId, seasonNumber, number, text.takeIf(String::isNotBlank))
            }
        }
        return result.distinctBy { it.first }.sortedWith(compareBy<Quad> { it.second }.thenBy { it.third })
    }

    private suspend fun loadServerSources(origin: String, episodeId: String, referer: String): List<Pair<String, String>> {
        val servers = ajaxDocument(origin, listOf(
            "/ajax/v2/episode/servers/$episodeId",
            "/ajax/episode/servers/$episodeId",
        ), referer) ?: return emptyList()
        val result = LinkedHashMap<String, Pair<String, String>>()
        for (server in servers.select("[data-id]")) {
            val serverId = server.attr("data-id").takeIf(String::isNotBlank) ?: continue
            var url: String? = null
            for (sourcePath in listOf(
                "/ajax/v2/episode/sources/$serverId",
                "/ajax/episode/sources/$serverId",
            )) {
                val response = runCatching {
                    app.get(
                        "$origin$sourcePath",
                        headers = headers + mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "application/json, text/javascript, */*; q=0.01",
                        ),
                        referer = referer,
                        cacheTime = 0,
                        timeout = 15L,
                    )
                }.onFailure { Log.w(LOG_TAG, "Source impossible path=$sourcePath", it) }.getOrNull() ?: continue
                Log.i(
                    LOG_TAG,
                    "Source path=$sourcePath HTTP=${response.okhttpResponse.code} taille=${response.text.length} aperçu='${preview(response.text)}'",
                )
                if (response.okhttpResponse.code !in 200..299) continue
                val json = runCatching { JSONObject(response.text) }.getOrNull() ?: continue
                url = listOf("link", "url", "file").firstNotNullOfOrNull { key ->
                    json.optString(key).takeIf(String::isNotBlank)
                }
                if (url != null) break
            }
            val playerUrl = url ?: continue
            val label = server.text().trim().ifBlank { "Lecteur" }
            result[playerUrl] = label to playerUrl
        }
        return result.values.toList()
    }

    private fun parseInlineEpisodes(document: Document): List<Quad> {
        val result = ArrayList<Quad>()
        document.select("[data-id]").forEachIndexed { index, node ->
            val text = node.text().trim()
            val episode = EPISODE_NUMBER.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@forEachIndexed
            val id = node.attr("data-id").takeIf(String::isNotBlank) ?: return@forEachIndexed
            val season = SEASON.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            result += Quad(id, season, episode, text.ifBlank { "Épisode $episode" })
        }
        return result.distinctBy { it.first }.sortedWith(compareBy<Quad> { it.second }.thenBy { it.third })
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
            val cardId = sequenceOf(anchor, container)
                .map { it.attr("data-id") }
                .firstOrNull { it.matches(NUMERIC_ID) }
            val itemUrl = "$origin$path" + (cardId?.let { "#csid=$it" } ?: "")
            val response = if (path.startsWith("/tv-show/")) {
                newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) { posterUrl = poster }
            } else newMovieSearchResponse(title, itemUrl, TvType.Movie) { posterUrl = poster }
            results.putIfAbsent(path, response)
        }
        return results.values.toList()
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

    private fun findMediaId(doc: Document, sourceUrl: String): String? {
        Regex("(?:#|&)csid=(\\d+)").find(sourceUrl)
            ?.groupValues?.getOrNull(1)?.let { return it }

        val selectors = listOf(
            ".detail_page-infor[data-id]",
            ".watch_block[data-id]",
            "#watch-block[data-id]",
            "[data-type][data-id]",
            ".film-buttons [data-id]",
            "[data-id]",
        )
        for (selector in selectors) {
            doc.select(selector).firstOrNull { it.attr("data-id").matches(NUMERIC_ID) }
                ?.attr("data-id")?.let { return it }
        }

        return MEDIA_ID_IN_SOURCE.find(doc.html())?.groupValues?.getOrNull(1)
    }

    private fun absolute(raw: String?, origin: String): String? {
        if (raw.isNullOrBlank() || raw.startsWith("data:") || raw.startsWith("javascript:")) return null
        return runCatching { URI("$origin/").resolve(raw).toString() }.getOrNull()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun preview(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .replace(Regex("https?://[^\\s\\\"']+"), "<url>")
        .take(180)

    companion object {
        private const val LOG_TAG = "XalaflixDebug"
        private val DETAIL = Regex("^/(movie|tv-show)/[^/?#]+/?$", RegexOption.IGNORE_CASE)
        private val NUMERIC_ID = Regex("\\d+")
        private val MEDIA_ID_IN_SOURCE = Regex(
            "(?i)(?:movie_id|film_id|media_id|data-id)[\\s\\\"':=]+(?:\\\"|')?(\\d+)",
        )
        private val YEAR = Regex("\\b(?:19|20)\\d{2}\\b")
        private val SEASON = Regex("(?i)(?:saison|season|s)[ ._-]*(\\d+)")
        private val EPISODE_NUMBER = Regex("(?i)(?:episode|épisode|ep|e)[ ._-]*(\\d+)")
        private val DIRECT_MEDIA = Regex("(?i)\\.(?:m3u8|mp4|mpd)(?:[?#]|$)")
        private val URL_IN_SCRIPT = Regex("https?://[^\\s\\\"'<>]+")
    }
}
