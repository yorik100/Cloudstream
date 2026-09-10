package com.xalaflix.cloudstream

import android.util.Log
import android.util.Base64
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
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.Session
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

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
    private val siteSession = Session(app.baseClient)

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
                val first = parts.firstOrNull()?.trim().orEmpty()
                val path = first.takeIf { it.startsWith('/') }
                    ?: runCatching { URI(first).path }.getOrNull()?.takeIf { it.startsWith('/') }
                    ?: return null
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
        val route = "/search/${encodePathSegment(query.trim())}"
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
        val plot = doc.selectFirst(
            "h1 ~ p.text-gray-400.mt-3, .flex-1 > p.text-gray-400.mt-3, " +
                ".description, .detail_page-infor .description, .film-description, [class*=overview]",
        )?.text()?.trim()?.takeIf(String::isNotBlank)
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

        val initiallyVisibleEpisodes = parseSiteEpisodes(doc, loaded.origin)
        val siteEpisodes = loadAllSeasons(doc, loaded.origin, "${loaded.origin}$path")
            .ifEmpty { initiallyVisibleEpisodes }
        val enrichedEpisodes = enrichEpisodesFromTmdb(title, year, siteEpisodes)
            ?: coroutineScope {
                // Compatibility fallback: if TMDB is unavailable, retain the
                // previous exact-site enrichment for the initially visible
                // season without requesting every episode of a large series.
                siteEpisodes.map { item ->
                    async {
                        val visible = initiallyVisibleEpisodes.any { it.path == item.path }
                        if (visible) enrichEpisode(item, loaded.origin) else item
                    }
                }.awaitAll()
            }
        Log.i(LOG_TAG, "Série saisons=${siteEpisodes.map { it.season }.distinct().size} épisodes=${siteEpisodes.size}")
        val episodes = enrichedEpisodes.map { item ->
            newEpisode(item.path) {
                name = item.title
                season = item.season
                episode = item.episode
                posterUrl = item.poster
                description = item.description
            }
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("Aucun épisode Xalaflix disponible")

        return newTvSeriesLoadResponse(title, url, type, episodes) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            // L'ancienne fiche ne contenait que la saison 9 et CloudStream a
            // mémorisé ce choix. Cette nouvelle clé réinitialise cette préférence
            // une seule fois ; CloudStream choisit alors la saison la plus proche
            // de 1, donc la première réellement disponible.
            uniqueUrl = "${loaded.origin}$path#xalaflix-complete-seasons"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val playback = Playback.decode(data)
        if (playback == null) {
            Log.w(LOG_TAG, "Lecture refusée : données d'épisode invalides taille=${data.length}")
            return false
        }
        Log.i(LOG_TAG, "Lecture demandée path=${playback.path}")
        // Même garde que load(): résolution obligatoire avant toute lecture.
        val loaded = fetchWithRefresh(playback.path) ?: return false
        val referer = "${loaded.origin}${playback.path}"
        val mediaId = playback.mediaId ?: findMediaId(loaded.document, referer)
        if (mediaId == null) {
            Log.i(LOG_TAG, "Lecture sans mediaId : extraction directe de la fiche ${playback.path}")
            return extractAndEmit(loaded.document, loaded.origin, referer, subtitleCallback, callback)
        }
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
        val players = LinkedHashMap<String, Pair<String, String>>()
        val pending = ArrayList<Pair<String, String>>()
        val visited = HashSet<String>()
        val playerReferers = HashMap<String, String>()
        val mediaReferers = HashMap<String, String>()

        fun enqueue(items: List<Pair<String, String>>, sourceReferer: String) {
            items.forEach { item ->
                if (players.putIfAbsent(item.second, item) == null) {
                    pending += item
                    playerReferers[item.second] = sourceReferer
                }
            }
        }

        enqueue(extractPlayers(document, origin), referer)
        var cursor = 0
        while (cursor < pending.size) {
            val (_, url) = pending[cursor++]
            if (!visited.add(url)) continue
            val sourceReferer = playerReferers[url] ?: referer
            if (isInternalEmbed(url, origin)) {
                val response = runCatching {
                    app.get(url, headers = headers, referer = sourceReferer, cacheTime = 0, timeout = 15L)
                }.onFailure { Log.w(LOG_TAG, "Lecteur interne inaccessible ${safeRoute(url)}", it) }.getOrNull()
                if (response != null && response.okhttpResponse.code in 200..299) {
                    val finalUrl = response.okhttpResponse.request.url.toString()
                    val embedDocument = Jsoup.parse(response.text, finalUrl)
                    val nested = extractPlayers(embedDocument, origin)
                    Log.i(LOG_TAG, "Lecteur interne ${safeRoute(url)} HTTP=${response.okhttpResponse.code} sources=${nested.size}")
                    enqueue(nested, finalUrl)
                }
            }
            if (isVidzyEmbed(url)) {
                val response = runCatching {
                    app.get(url, headers = headers, referer = sourceReferer, cacheTime = 0, timeout = 15L)
                }.onFailure { Log.w(LOG_TAG, "Lecteur Vidzy inaccessible ${safeRoute(url)}", it) }.getOrNull()
                if (response != null && response.okhttpResponse.code in 200..299) {
                    val finalUrl = response.okhttpResponse.request.url.toString()
                    val finalHost = response.okhttpResponse.request.url.host
                    val mediaUrl = decodeVidzySource(response.text, finalHost)
                    Log.i(LOG_TAG, "Lecteur Vidzy ${safeRoute(finalUrl)} HTTP=${response.okhttpResponse.code} HLS=${mediaUrl != null}")
                    if (mediaUrl != null) {
                        players[mediaUrl] = "Vidzy" to mediaUrl
                        mediaReferers[mediaUrl] = finalUrl
                    }
                }
            }
        }
        for ((label, url) in players.values) {
            if (DIRECT_MEDIA.containsMatchIn(url)) {
                callback(newExtractorLink(
                    source = name,
                    name = "Xalaflix · $label",
                    url = url,
                    type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = mediaReferers[url] ?: referer
                    quality = getQualityFromName(label)
                    headers = this@XalaflixProvider.headers
                })
                emitted = true
                continue
            }
            if (isInternalEmbed(url, origin)) continue
            var sourceEmitted = false
            runCatching {
                loadExtractor(url, playerReferers[url] ?: referer, subtitleCallback) {
                    sourceEmitted = true
                    emitted = true
                    callback(it)
                }
            }
            if (!sourceEmitted) {
                sourceEmitted = extractUqloadDirect(url, playerReferers[url] ?: referer, callback)
            }
            if (!sourceEmitted) {
                sourceEmitted = loadNamedHostExtractor(
                    url = url,
                    referer = playerReferers[url] ?: referer,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )
                if (sourceEmitted) emitted = true
            }
            Log.i(LOG_TAG, "Source ${safeRoute(url)} valide=$sourceEmitted")
        }
        return emitted
    }

    private suspend fun extractUqloadDirect(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (knownHostName(url) != "uqload") return false
        val response = runCatching {
            app.get(url, headers = headers, referer = referer, cacheTime = 0, timeout = 20L)
        }.onFailure { Log.w(LOG_TAG, "Page Uqload inaccessible ${safeRoute(url)}", it) }
            .getOrNull() ?: return false
        if (response.okhttpResponse.code !in 200..299) return false

        val unpacked = runCatching { getAndUnpack(response.text) }
            .onFailure { Log.w(LOG_TAG, "Script Uqload indécompactable ${safeRoute(url)}", it) }
            .getOrNull().orEmpty()
        val mediaUrl = sequenceOf(unpacked, response.text)
            .flatMap { html -> URL_IN_SCRIPT.findAll(html.replace("\\/", "/")).map { cleanUrl(it.value) } }
            .firstOrNull { DIRECT_MEDIA.containsMatchIn(it) }
            ?: return false
        val playerOrigin = runCatching {
            val uri = URI(response.okhttpResponse.request.url.toString())
            "${uri.scheme}://${uri.host}/"
        }.getOrDefault(referer)
        callback(newExtractorLink(
            source = name,
            name = "Xalaflix · Uqload",
            url = mediaUrl,
            type = if (mediaUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
        ) {
            this.referer = playerOrigin
            quality = getQualityFromName("HD")
            headers = this@XalaflixProvider.headers
        })
        return true
    }

    private fun normalizeExtractorName(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun knownHostName(url: String): String? {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return when {
            "uqload" in host -> "uqload"
            "vidzy" in host -> "vidzy"
            host == "voe.sx" || host.startsWith("voe.") || ".voe." in host -> "voe"
            "dood" in host -> "dood"
            "streamtape" in host -> "streamtape"
            "vidmoly" in host -> "vidmoly"
            "filemoon" in host -> "filemoon"
            "streamwish" in host || "wish" in host -> "streamwish"
            "mixdrop" in host -> "mixdrop"
            else -> null
        }
    }

    private suspend fun loadNamedHostExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val hostName = knownHostName(url) ?: return false
        for (index in extractorApis.lastIndex downTo 0) {
            val extractor = extractorApis[index]
            val extractorName = normalizeExtractorName(extractor.name)
            if (extractorName.isBlank()) continue
            if (
                extractorName != hostName &&
                !extractorName.contains(hostName) &&
                !hostName.contains(extractorName)
            ) continue

            var emitted = false
            runCatching {
                extractor.getUrl(
                    url = url,
                    referer = referer,
                    subtitleCallback = subtitleCallback,
                    callback = {
                        emitted = true
                        callback(it)
                    },
                )
            }.onFailure {
                Log.w(LOG_TAG, "Extracteur nommé $hostName impossible pour ${safeRoute(url)}", it)
            }
            if (emitted) return true
        }
        return false
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
            siteSession.get("$origin$path", headers = headers, referer = "$origin/", cacheTime = 0, timeout = 15L)
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

    private data class SiteEpisode(
        val path: String,
        val season: Int,
        val episode: Int,
        val title: String,
        val poster: String?,
        val description: String? = null,
        val posterIsDefault: Boolean = false,
    )

    private fun parseSiteEpisodes(document: Document, origin: String): List<SiteEpisode> {
        val result = LinkedHashMap<String, SiteEpisode>()
        val seasonPosters = parseSeasonPosters(document, origin)
        document.select("a[href*=/episode/]").forEach { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            val path = runCatching { URI(href).path }.getOrNull() ?: return@forEach
            val numbers = EPISODE_PATH.matchEntire(path) ?: return@forEach
            val season = numbers.groupValues[1].toIntOrNull() ?: return@forEach
            val episode = numbers.groupValues[2].toIntOrNull() ?: return@forEach
            val container = anchor.closest("div.relative.group") ?: anchor.parent()
            val title = container?.selectFirst("h3")?.text()?.trim()?.takeIf(String::isNotBlank)
                ?: "Épisode $episode"
            val poster = anchor.selectFirst("img")?.let { imageUrl(it, origin) }
            val posterIsDefault = poster != null && (
                seasonPosters[season]
                    ?.let { seasonPoster -> sameImageAsset(poster, seasonPoster) }
                    ?: false
                )
            result[path] = SiteEpisode(
                path = path,
                season = season,
                episode = episode,
                title = title,
                poster = poster,
                posterIsDefault = posterIsDefault,
            )
        }
        return result.values.sortedWith(compareBy<SiteEpisode> { it.season }.thenBy { it.episode })
    }

    private fun parseSeasonPosters(document: Document, origin: String): Map<Int, String> {
        val result = LinkedHashMap<Int, String>()
        document.getAllElements().forEach { element ->
            val season = SEASON.find(element.text())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: return@forEach
            val script = sequenceOf("@click", "x-on:click", "onclick")
                .map { element.attr(it) }
                .firstOrNull(String::isNotBlank)
                ?: return@forEach
            val rawPoster = UPDATE_POSTER.find(script)?.groupValues?.getOrNull(1)
                ?: return@forEach
            absolute(rawPoster, origin)?.let { result[season] = it }
        }
        return result
    }

    private fun sameImageAsset(first: String, second: String): Boolean {
        fun identity(url: String): String? = runCatching {
            URI(url).path.substringAfterLast('/').lowercase(Locale.ROOT)
        }.getOrNull()?.takeIf(String::isNotBlank)

        val firstIdentity = identity(first) ?: return false
        return firstIdentity == identity(second)
    }

    private suspend fun loadAllSeasons(document: Document, origin: String, referer: String): List<SiteEpisode> {
        val snapshotNode = document.getAllElements().firstOrNull {
            it.hasAttr("wire:snapshot") && it.attr("wire:snapshot").contains("season-component")
        } ?: return parseSiteEpisodes(document, origin)
        val snapshot = snapshotNode.attr("wire:snapshot")
        val csrf = document.selectFirst("meta[name=csrf-token]")?.attr("content")?.takeIf(String::isNotBlank)
            ?: return parseSiteEpisodes(document, origin)
        val snapshotData = runCatching { JSONObject(snapshot).optJSONObject("data") }.getOrNull()
        val currentSeasonId = livewireScalar(snapshotData?.opt("seasonId"))
        val currentSeasonNumber = livewireScalar(snapshotData?.opt("season_number"))?.toIntOrNull()
        val options = LinkedHashMap<String, Int>()
        document.getAllElements().forEach { element ->
            val action = element.attr("wire:click")
            val id = UPDATE_SEASON.find(action)?.groupValues?.getOrNull(1) ?: return@forEach
            val number = SEASON.find(element.text())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@forEach
            options[id] = number
        }
        if (currentSeasonId != null && currentSeasonNumber != null) options[currentSeasonId] = currentSeasonNumber

        val pages = ArrayList<Pair<Int, Document>>()
        val requested = options
            .filterKeys { it != currentSeasonId }
            .toList()

        // Livewire accepts several independent copies of the component in one
        // update. This returns every requested season in a single HTTP round
        // trip instead of waiting for one request per season.
        val batched = loadSeasonPagesBatch(
            origin = origin,
            referer = referer,
            csrf = csrf,
            snapshot = snapshot,
            seasons = requested,
        )
        pages += batched

        // Keep the proven chained mutation path for only the missing seasons
        // when a proxy or a future Livewire version truncates a large batch.
        val loadedSeasonNumbers = batched.mapTo(HashSet<Int>()) { it.first }
        var activeSnapshot = snapshot
        for ((seasonId, seasonNumber) in requested) {
            if (seasonNumber in loadedSeasonNumbers) continue
            val page = loadSeasonPage(origin, referer, csrf, activeSnapshot, seasonId) ?: continue
            activeSnapshot = page.snapshot
            val episodeCount = parseSiteEpisodes(page.document, origin).size
            Log.i(LOG_TAG, "Saison Livewire secours id=$seasonId saison=$seasonNumber épisodes=$episodeCount")
            pages += seasonNumber to page.document
        }
        Log.i(LOG_TAG, "Livewire saisons demandées=${options.size} chargées=${pages.size + 1}")
        val merged = LinkedHashMap<String, SiteEpisode>()
        parseSiteEpisodes(document, origin).forEach { merged[it.path] = it }
        pages.forEach { (_, seasonDocument) ->
            parseSiteEpisodes(seasonDocument, origin).forEach { merged[it.path] = it }
        }
        return merged.values.sortedWith(compareBy<SiteEpisode> { it.season }.thenBy { it.episode })
    }

    private data class LivewireSeasonPage(val document: Document, val snapshot: String)

    private suspend fun loadSeasonPagesBatch(
        origin: String,
        referer: String,
        csrf: String,
        snapshot: String,
        seasons: List<Pair<String, Int>>,
    ): List<Pair<Int, Document>> {
        if (seasons.isEmpty()) return emptyList()

        val components = JSONArray()
        seasons.forEach { (seasonId, _) ->
            val call = JSONObject()
                .put("path", "")
                .put("method", "updateSeason")
                .put("params", JSONArray().put(seasonId))
            components.put(
                JSONObject()
                    .put("snapshot", snapshot)
                    .put("updates", JSONObject())
                    .put("calls", JSONArray().put(call)),
            )
        }
        val payload = JSONObject()
            .put("_token", csrf)
            .put("components", components)
        val response = runCatching {
            siteSession.post(
                "$origin/livewire/update",
                json = payload,
                headers = headers + mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "X-CSRF-TOKEN" to csrf,
                    "X-Livewire" to "true",
                    "Origin" to origin,
                ),
                referer = referer,
                cacheTime = 0,
                timeout = 45L,
            )
        }.onFailure { Log.w(LOG_TAG, "Lot Livewire impossible saisons=${seasons.size}", it) }
            .getOrNull() ?: return emptyList()

        if (response.okhttpResponse.code !in 200..299) {
            Log.w(LOG_TAG, "Lot Livewire HTTP=${response.okhttpResponse.code} saisons=${seasons.size}")
            return emptyList()
        }

        val returned = runCatching {
            JSONObject(response.text).optJSONArray("components")
        }.onFailure {
            Log.w(LOG_TAG, "Réponse du lot Livewire illisible aperçu='${preview(response.text)}'", it)
        }.getOrNull() ?: return emptyList()

        val pages = ArrayList<Pair<Int, Document>>()
        for (index in 0 until minOf(returned.length(), seasons.size)) {
            val component = returned.optJSONObject(index) ?: continue
            val html = component.optJSONObject("effects")
                ?.optString("html")
                ?.takeIf(String::isNotBlank)
                ?: continue
            val seasonNumber = seasons[index].second
            val document = Jsoup.parseBodyFragment(html, "$origin/")
            val episodeCount = parseSiteEpisodes(document, origin).size
            if (episodeCount == 0) continue
            Log.i(LOG_TAG, "Saison Livewire lot saison=$seasonNumber épisodes=$episodeCount")
            pages += seasonNumber to document
        }
        Log.i(LOG_TAG, "Lot Livewire demandées=${seasons.size} reçues=${pages.size}")
        return pages
    }

    private suspend fun loadSeasonPage(
        origin: String,
        referer: String,
        csrf: String,
        snapshot: String,
        seasonId: String,
    ): LivewireSeasonPage? {
        // Session(app.baseClient) ne conserve pas le ResponseParser de CloudStream.
        // Une Map serait donc envoyée via Map.toString() et non en JSON. JSONObject
        // force NiceHttp à produire un véritable corps application/json.
        val call = JSONObject()
            .put("path", "")
            .put("method", "updateSeason")
            .put("params", JSONArray().put(seasonId))
        val component = JSONObject()
            .put("snapshot", snapshot)
            .put("updates", JSONObject())
            .put("calls", JSONArray().put(call))
        val payload = JSONObject()
            .put("_token", csrf)
            .put("components", JSONArray().put(component))
        val response = runCatching {
            siteSession.post(
                "$origin/livewire/update",
                json = payload,
                headers = headers + mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "X-CSRF-TOKEN" to csrf,
                    "X-Livewire" to "true",
                    "Origin" to origin,
                ),
                referer = referer,
                cacheTime = 0,
                timeout = 45L,
            )
        }.onFailure { Log.w(LOG_TAG, "Saison Livewire impossible id=$seasonId", it) }
            .getOrNull() ?: return null
        if (response.okhttpResponse.code !in 200..299) {
            Log.w(LOG_TAG, "Saison Livewire id=$seasonId HTTP=${response.okhttpResponse.code}")
            return null
        }
        val responseComponents = runCatching { JSONObject(response.text).optJSONArray("components") }
            .onFailure { Log.w(LOG_TAG, "Réponse Livewire illisible id=$seasonId aperçu='${preview(response.text)}'", it) }
            .getOrNull()
        if (responseComponents == null || responseComponents.length() == 0) {
            Log.w(LOG_TAG, "Réponse Livewire vide id=$seasonId aperçu='${preview(response.text)}'")
            return null
        }
        val returnedComponent = responseComponents.optJSONObject(0) ?: return null
        val returnedSnapshot = returnedComponent.optString("snapshot").takeIf(String::isNotBlank) ?: return null
        val html = returnedComponent.optJSONObject("effects")?.optString("html")
            ?.takeIf(String::isNotBlank) ?: return null
        return LivewireSeasonPage(Jsoup.parseBodyFragment(html, "$origin/"), returnedSnapshot)
    }

    private fun livewireScalar(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is org.json.JSONArray -> livewireScalar(value.opt(0))
        else -> value.toString().takeIf { it.isNotBlank() && it != "null" }
    }

    private suspend fun enrichEpisode(item: SiteEpisode, origin: String): SiteEpisode {
        val document = fetch(origin, item.path) ?: return item
        val description = document.selectFirst(
            "h1 ~ p.text-gray-400.mt-3, .flex-1 > p.text-gray-400.mt-3, " +
                "p.text-x.text-gray-400.mt-3, .description, [class*=overview]",
        )?.text()?.trim()?.takeIf(String::isNotBlank)
        return item.copy(description = description)
    }

    private data class TmdbEpisodeMetadata(
        val title: String?,
        val description: String?,
        val poster: String?,
    )

    private suspend fun enrichEpisodesFromTmdb(
        title: String,
        releaseYear: Int?,
        episodes: List<SiteEpisode>,
    ): List<SiteEpisode>? {
        val tmdbId = findTmdbSeriesId(title, releaseYear) ?: run {
            Log.w(LOG_TAG, "Descriptions TMDB : série introuvable titre='$title'")
            return null
        }
        val metadata = LinkedHashMap<Pair<Int, Int>, TmdbEpisodeMetadata>()
        val seasons = episodes.map { it.season }.distinct().sorted()

        for (batch in seasons.chunked(TMDB_SEASON_BATCH_SIZE)) {
            val results = coroutineScope {
                batch.map { seasonNumber ->
                    async {
                        seasonNumber to runCatching {
                            tmdbGet("/tv/$tmdbId/season/$seasonNumber")
                        }.getOrNull()
                    }
                }.awaitAll()
            }
            results.forEach { (seasonNumber, seasonDetails) ->
                val seasonPoster = tmdbPoster(seasonDetails?.optString("poster_path"))
                val episodeArray = seasonDetails?.optJSONArray("episodes") ?: JSONArray()
                for (index in 0 until episodeArray.length()) {
                    val item = episodeArray.optJSONObject(index) ?: continue
                    val episodeNumber = item.optInt("episode_number", 0).takeIf { it > 0 }
                        ?: continue
                    metadata[seasonNumber to episodeNumber] = TmdbEpisodeMetadata(
                        title = item.optString("name").trim().takeIf(String::isNotBlank),
                        description = item.optString("overview").trim().takeIf(String::isNotBlank),
                        poster = tmdbPoster(item.optString("still_path")) ?: seasonPoster,
                    )
                }
            }
        }

        val missingSeasons = seasons.filter { seasonNumber ->
            episodes.any { it.season == seasonNumber } &&
                metadata.keys.none { it.first == seasonNumber }
        }.toSet()
        if (missingSeasons.isNotEmpty()) {
            metadata.putAll(
                loadAlternativeTmdbEpisodeMetadata(tmdbId, missingSeasons),
            )
        }

        if (metadata.isEmpty()) return null
        var replacedTitles = 0
        var replacedPosters = 0
        val enriched = episodes.map { episode ->
            val item = metadata[episode.season to episode.episode] ?: return@map episode
            val genericTitle = isGenericEpisodeTitle(episode.title, episode.episode)
            val tmdbPoster = item.poster
            if (genericTitle && item.title != null) replacedTitles++
            if ((episode.poster == null || episode.posterIsDefault) && tmdbPoster != null) {
                replacedPosters++
            }
            episode.copy(
                title = if (genericTitle) item.title ?: episode.title else episode.title,
                poster = if (episode.poster == null || episode.posterIsDefault) {
                    tmdbPoster ?: episode.poster
                } else {
                    episode.poster
                },
                description = item.description ?: episode.description,
                posterIsDefault = episode.posterIsDefault && tmdbPoster == null,
            )
        }
        Log.i(
            LOG_TAG,
            "Descriptions TMDB tmdbId=$tmdbId saisons=${seasons.size} épisodes=${metadata.size} " +
                "titresRemplacés=$replacedTitles imagesRemplacées=$replacedPosters",
        )
        return enriched
    }

    private suspend fun loadAlternativeTmdbEpisodeMetadata(
        tmdbId: Int,
        wantedSeasons: Set<Int>,
    ): Map<Pair<Int, Int>, TmdbEpisodeMetadata> {
        val groupIndex = runCatching {
            tmdbGet("/tv/$tmdbId/episode_groups")
        }.getOrNull() ?: return emptyMap()
        val candidates = (groupIndex.optJSONArray("results") ?: JSONArray())
            .let { array -> (0 until array.length()).mapNotNull(array::optJSONObject) }
            // Digital order generally matches the extra season numbers used by
            // Xalaflix; DVD order is the next most useful fallback.
            .sortedBy { item ->
                when (item.optInt("type", 0)) {
                    4 -> 0
                    3 -> 1
                    else -> 2
                }
            }
        val result = LinkedHashMap<Pair<Int, Int>, TmdbEpisodeMetadata>()
        val resolvedSeasons = HashSet<Int>()

        for (candidate in candidates) {
            val groupId = candidate.optString("id").takeIf(String::isNotBlank) ?: continue
            val details = runCatching {
                tmdbGet("/tv/episode_group/$groupId")
            }.getOrNull() ?: continue
            val groups = details.optJSONArray("groups") ?: JSONArray()
            for (groupIndexPosition in 0 until groups.length()) {
                val group = groups.optJSONObject(groupIndexPosition) ?: continue
                val seasonNumber = SEASON.find(group.optString("name"))
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.takeIf { it in wantedSeasons && it !in resolvedSeasons }
                    ?: continue
                val groupEpisodes = group.optJSONArray("episodes") ?: JSONArray()
                for (episodeIndex in 0 until groupEpisodes.length()) {
                    val item = groupEpisodes.optJSONObject(episodeIndex) ?: continue
                    val episodeNumber = item.optInt("order", episodeIndex) + 1
                    result[seasonNumber to episodeNumber] = TmdbEpisodeMetadata(
                        title = item.optString("name").trim().takeIf(String::isNotBlank),
                        description = item.optString("overview").trim().takeIf(String::isNotBlank),
                        poster = tmdbPoster(item.optString("still_path")),
                    )
                }
                if (groupEpisodes.length() > 0) resolvedSeasons += seasonNumber
            }
            if (resolvedSeasons.containsAll(wantedSeasons)) break
        }

        if (resolvedSeasons.isNotEmpty()) {
            Log.i(
                LOG_TAG,
                "Ordre alternatif TMDB saisons=${resolvedSeasons.sorted().joinToString()}",
            )
        }
        return result
    }

    private fun isGenericEpisodeTitle(title: String, episodeNumber: Int): Boolean =
        GENERIC_EPISODE_TITLE.matches(title.trim()) &&
            GENERIC_EPISODE_NUMBER.find(title)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull() == episodeNumber

    private suspend fun findTmdbSeriesId(title: String, releaseYear: Int?): Int? {
        val root = runCatching {
            tmdbGet(
                "/search/tv",
                mapOf(
                    "query" to title,
                    "page" to "1",
                ),
            )
        }.getOrNull() ?: return null
        val wanted = normalizeTitle(title)
        val candidates = root.optJSONArray("results") ?: JSONArray()
        return (0 until candidates.length())
            .mapNotNull { candidates.optJSONObject(it) }
            .map { candidate ->
                val names = listOf(
                    candidate.optString("name"),
                    candidate.optString("original_name"),
                ).map(::normalizeTitle)
                var score = names.maxOfOrNull { name ->
                    when {
                        name == wanted -> 100
                        name.contains(wanted) || wanted.contains(name) -> 60
                        else -> 0
                    }
                } ?: 0
                val candidateYear = candidate.optString("first_air_date")
                    .take(4)
                    .toIntOrNull()
                if (releaseYear != null && candidateYear != null) {
                    score += when (kotlin.math.abs(releaseYear - candidateYear)) {
                        0 -> 25
                        1 -> 5
                        else -> -25
                    }
                }
                candidate to score
            }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= 70 }
            ?.first
            ?.optInt("id", 0)
            ?.takeIf { it > 0 }
    }

    private suspend fun tmdbGet(
        path: String,
        params: Map<String, String> = emptyMap(),
    ): JSONObject {
        val query = linkedMapOf(
            "api_key" to TMDB_API_KEY,
            "language" to "fr-FR",
        ).apply { putAll(params) }
        val url = "$TMDB_API$path?" + query.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val response = app.get(url, cacheTime = TMDB_CACHE_SECONDS)
        if (response.okhttpResponse.code !in 200..299) {
            throw ErrorLoadingException("TMDB HTTP ${response.okhttpResponse.code}")
        }
        return JSONObject(response.text)
    }

    private fun tmdbPoster(path: String?, size: String = "w500"): String? =
        path?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?.let { "$TMDB_IMAGES/$size$it" }

    private fun normalizeTitle(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

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
        val snapshotPlayers = extractSnapshotPlayers(doc, origin)
        if (snapshotPlayers.isNotEmpty()) {
            Log.i(LOG_TAG, "Sources du watch-component=${snapshotPlayers.size}")
            Log.i(LOG_TAG, "Candidats=${snapshotPlayers.mapNotNull { safeRoute(it.second) }.joinToString(" | ")}")
            return snapshotPlayers
        }
        doc.select("iframe[src], video[src], source[src], [data-src]:not(img), [data-url], [data-link], [data-embed]").forEach { element ->
            val raw = listOf("src", "data-src", "data-url", "data-link", "data-embed", "href")
                .map { element.attr(it) }.firstOrNull(String::isNotBlank) ?: return@forEach
            val url = absolute(raw, origin) ?: return@forEach
            if (!url.startsWith("http") || detailPath(url) != null || isExcludedPlaybackUrl(url)) return@forEach
            val label = element.attr("title").ifBlank { element.text() }.ifBlank { URI(url).host ?: "Lecteur" }
            result[url] = label to url
        }
        val normalizedHtml = doc.html().replace("\\/", "/")
        URL_IN_SCRIPT.findAll(normalizedHtml).forEach { match ->
            val url = cleanUrl(match.value)
            if (
                !isExcludedPlaybackUrl(url) &&
                (DIRECT_MEDIA.containsMatchIn(url) || PLAYER_HOST.containsMatchIn(url) || isInternalEmbed(url, origin))
            ) {
                result.putIfAbsent(url, "Lecteur" to url)
            }
        }
        SCRIPT_LINK.findAll(normalizedHtml).forEach { match ->
            absolute(cleanUrl(match.groupValues[1]), origin)
                ?.takeUnless(::isExcludedPlaybackUrl)
                ?.let { result.putIfAbsent(it, "Lecteur" to it) }
        }
        RELATIVE_EMBED.findAll(normalizedHtml).forEach { match ->
            absolute(cleanUrl(match.value), origin)?.let { result[it] = "Lecteur interne" to it }
        }
        Log.i(LOG_TAG, "Lecteurs intégrés trouvés=${result.size}")
        Log.i(LOG_TAG, "Candidats=${result.keys.mapNotNull(::safeRoute).distinct().take(50).joinToString(" | ")}")
        val dynamicRoutes = DYNAMIC_ROUTE.findAll(doc.html())
            .map { it.value.replace("\\/", "/").replace("&amp;", "&").substringBefore('?') }
            .distinct().take(50).toList()
        Log.i(LOG_TAG, "Routes dynamiques=${dynamicRoutes.joinToString(" | ")}")
        return result.values.toList()
    }

    private fun isExcludedPlaybackUrl(raw: String): Boolean = runCatching {
        val host = URI(raw).host.orEmpty().lowercase().removePrefix("www.")
        host == "youtu.be" ||
            host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
    }.getOrDefault(false)

    private fun extractSnapshotPlayers(doc: Document, origin: String): List<Pair<String, String>> {
        val result = LinkedHashMap<String, Pair<String, String>>()
        doc.getAllElements().asSequence()
            .map { it.attr("wire:snapshot") }
            .filter { it.contains("watch-component") }
            .forEach { snapshot ->
                val data = runCatching { JSONObject(snapshot).optJSONObject("data") }.getOrNull() ?: return@forEach
                collectSnapshotPlayers(data, origin, result)
            }
        Log.i(LOG_TAG, "Lecteurs snapshot trouvés=${result.size}")
        return result.values.toList()
    }

    private fun collectSnapshotPlayers(
        value: Any?,
        origin: String,
        result: LinkedHashMap<String, Pair<String, String>>,
    ) {
        when (value) {
            is JSONObject -> {
                val rawLink = value.optString("link").takeIf(String::isNotBlank)
                val url = rawLink?.let { absolute(cleanUrl(it), origin) }
                if (url != null && url.startsWith("http") && detailPath(url) == null) {
                    val label = listOf(
                        value.optString("server_name"),
                        value.optString("label"),
                        value.optString("version"),
                    ).filter(String::isNotBlank).distinct().joinToString(" · ").ifBlank { "Lecteur" }
                    result[url] = label to url
                }
                value.keys().forEach { key -> collectSnapshotPlayers(value.opt(key), origin, result) }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                collectSnapshotPlayers(value.opt(index), origin, result)
            }
        }
    }

    private fun safeRoute(raw: String): String? = runCatching {
        val uri = URI(raw)
        val host = uri.host ?: return@runCatching null
        "$host${uri.path.orEmpty()}"
    }.getOrNull()

    private fun isInternalEmbed(raw: String, origin: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.host.equals(URI(origin).host, true) && uri.path.orEmpty().contains("/embed-", true)
    }.getOrDefault(false)

    private fun isVidzyEmbed(raw: String): Boolean = runCatching {
        URI(raw).host.orEmpty().contains("vidzy.", true) && URI(raw).path.orEmpty().contains("/embed-", true)
    }.getOrDefault(false)

    private fun decodeVidzySource(html: String, hostname: String): String? {
        val hostKey = hostname.sumOf { it.code } and 255
        for (match in LONG_BASE64.findAll(html)) {
            val bytes = runCatching { Base64.decode(match.value, Base64.DEFAULT) }.getOrNull() ?: continue
            val reversed = bytes.reversedArray()
            val decoded = CharArray(reversed.size) { index ->
                ((reversed[index].toInt() and 255) xor ((0x3d + index * 89 + hostKey) and 255)).toChar()
            }.concatToString()
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) return decoded
        }
        return null
    }

    private fun cleanUrl(raw: String): String = raw
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .substringBefore("&quot;")
        .substringBefore("&#")
        .trim('"', '\'', ' ')

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

    private fun encodePathSegment(value: String): String =
        encode(value).replace("+", "%20")

    private fun preview(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .replace(Regex("https?://[^\\s\\\"']+"), "<url>")
        .take(180)

    companion object {
        private const val LOG_TAG = "XalaflixDebug"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_IMAGES = "https://image.tmdb.org/t/p"
        private const val TMDB_API_KEY = "f3d757824f08ea2cff45eb8f47ca3a1e"
        private const val TMDB_CACHE_SECONDS = 300
        private const val TMDB_SEASON_BATCH_SIZE = 6
        private val DETAIL = Regex("^/(movie|tv-show)/[^/?#]+/?$", RegexOption.IGNORE_CASE)
        private val NUMERIC_ID = Regex("\\d+")
        private val MEDIA_ID_IN_SOURCE = Regex(
            "(?i)(?:movie_id|film_id|media_id|data-id)[\\s\\\"':=]+(?:\\\"|')?(\\d+)",
        )
        private val YEAR = Regex("\\b(?:19|20)\\d{2}\\b")
        private val SEASON = Regex("(?i)(?:saison|season|s)[ ._-]*(\\d+)")
        private val EPISODE_NUMBER = Regex("(?i)(?:episode|épisode|ep|e)[ ._-]*(\\d+)")
        private val EPISODE_PATH = Regex("(?i)^/episode/[^/]+/(\\d+)-(\\d+)/?$")
        private val UPDATE_SEASON = Regex("(?i)updateSeason\\(['\"]?(\\d+)")
        private val UPDATE_POSTER = Regex("(?i)updatePoster\\(['\"]([^'\"]+)")
        private val GENERIC_EPISODE_TITLE = Regex(
            "(?i)^(?:episode|épisode|ep\\.?)[ ._-]*0*\\d+$",
        )
        private val GENERIC_EPISODE_NUMBER = Regex("(\\d+)$")
        private val DIRECT_MEDIA = Regex("(?i)\\.(?:m3u8|mp4|mpd)(?:[?#]|$)")
        private val PLAYER_HOST = Regex("(?i)(?:embed|player|stream|vid|filemoon|uqload|voe|dood|wish|sibnet)")
        private val URL_IN_SCRIPT = Regex("https?://[^\\s\\\"'<>]+")
        private val SCRIPT_LINK = Regex("(?i)\\\"link\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        private val LONG_BASE64 = Regex("[A-Za-z0-9+/]{80,}={0,2}")
        private val RELATIVE_EMBED = Regex("(?i)/embed-[a-z0-9_-]+\\.html")
        private val DYNAMIC_ROUTE = Regex(
            "(?i)(?:https?://[^\\s\\\"'<>]+|/[a-z0-9_./-]*(?:ajax|api|embed|episode|server|source|watch|player)[a-z0-9_./?=&-]*)",
        )
    }
}
