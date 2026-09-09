package com.xalaflix.cloudstream

import android.util.Base64
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

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

    /** data pour loadLinks = chemin /movie/... | /tv-show/... | /episode/... */
    private data class Playback(val path: String) {
        fun encode(): String = path
        companion object {
            fun decode(raw: String): Playback? =
                raw.trim().takeIf { it.startsWith('/') }?.let { Playback(it) }
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
        val path = contentPath(url) ?: throw ErrorLoadingException("URL Xalaflix invalide")
        val loaded = fetchWithRefresh(path) ?: throw ErrorLoadingException("Fiche Xalaflix inaccessible")
        val doc = loaded.document
        val origin = loaded.origin
        val type = when {
            path.startsWith("/tv-show/") || path.startsWith("/episode/") -> TvType.TvSeries
            else -> TvType.Movie
        }
        val title = doc.selectFirst("h1, .detail_page-infor h2, .heading-name")?.text()?.trim()
            ?: doc.title().substringBefore(" Streaming").trim().takeIf(String::isNotBlank)
            ?: throw ErrorLoadingException("Titre Xalaflix introuvable")
        val poster = absolute(
            doc.selectFirst(".detail_page-infor img, .film-poster-img, .movie-poster img, img[src*=image.tmdb]")?.absUrl("src"),
            origin,
        )
        val plot = doc.selectFirst(
            "h1 ~ p.text-gray-400.mt-3, .flex-1 > p.text-gray-400.mt-3, " +
                ".description, .detail_page-infor .description, .film-description, [class*=overview]",
        )?.text()?.trim()?.takeIf(String::isNotBlank)
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

        val episodes = loadAllSeriesEpisodes(doc, origin, path)
        if (episodes.isEmpty()) throw ErrorLoadingException("Aucun épisode Xalaflix disponible")
        Log.i(LOG_TAG, "Épisodes chargés=${episodes.size} saisons=${episodes.mapNotNull { it.season }.distinct().sorted()}")

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
        val loaded = fetchWithRefresh(playback.path) ?: return false
        val referer = "${loaded.origin}${playback.path}"
        val html = loaded.document.html()
        Log.i(LOG_TAG, "Lecture path=${playback.path} html=${html.length}")

        val urls = LinkedHashMap<String, Pair<String, String>>()
        extractVideosFromWatchSnapshot(html).forEach { (label, link) -> urls[link] = label to link }
        // Repli regex global "link":"https://..."
        LINK_JSON.findAll(html).forEach { m ->
            val link = cleanUrl(m.groupValues[1])
            if (link.startsWith("http") && contentPath(link) == null) {
                urls.putIfAbsent(link, "Lecteur" to link)
            }
        }
        extractPlayers(loaded.document, loaded.origin).forEach { (label, link) ->
            if (link.startsWith("http") && !link.contains("iframeSrc", true)) {
                urls.putIfAbsent(link, label to link)
            }
        }
        Log.i(LOG_TAG, "Sources brutes=${urls.size} : ${urls.keys.take(8).joinToString()}")

        var emitted = false
        for ((label, playerUrl) in urls.values) {
            try {
                when {
                    DIRECT_MEDIA.containsMatchIn(playerUrl) -> {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "Xalaflix · $label",
                                url = playerUrl,
                                type = if (playerUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                            ) {
                                this.referer = referer
                                quality = getQualityFromName(label)
                                headers = this@XalaflixProvider.headers
                            },
                        )
                        emitted = true
                    }
                    isVidzyEmbed(playerUrl) -> {
                        val mediaUrl = resolveVidzy(playerUrl, referer)
                        if (mediaUrl != null) {
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = "Xalaflix · $label",
                                    url = mediaUrl,
                                    type = if (mediaUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                ) {
                                    this.referer = playerUrl
                                    quality = getQualityFromName(label)
                                    headers = this@XalaflixProvider.headers
                                },
                            )
                            emitted = true
                        } else {
                            loadExtractor(playerUrl, referer, subtitleCallback) { emitted = true; callback(it) }
                        }
                    }
                    else -> {
                        loadExtractor(playerUrl, referer, subtitleCallback) { emitted = true; callback(it) }
                    }
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Source échouée $label $playerUrl", e)
            }
        }
        Log.i(LOG_TAG, "Sources émises=$emitted")
        return emitted
    }

    // -------------------------------------------------------------------------
    // Saisons / épisodes (Livewire updateSeason)
    // -------------------------------------------------------------------------

    private data class SeasonEntry(val id: String, val number: Int)
    private data class SiteEpisode(
        val path: String,
        val season: Int,
        val episode: Int,
        val title: String,
        val poster: String? = null,
    )

    private suspend fun loadAllSeriesEpisodes(
        doc: Document,
        origin: String,
        seriesPath: String,
    ): List<com.lagradost.cloudstream3.Episode> {
        val seasons = parseSeasonEntries(doc.html())
        Log.i(LOG_TAG, "Saisons=${seasons.joinToString { "${it.number}:${it.id}" }}")

        val byKey = LinkedHashMap<String, SiteEpisode>()
        parseSiteEpisodes(doc, origin).forEach { byKey[it.path] = it }

        val csrf = CSRF_META.find(doc.html())?.groupValues?.getOrNull(1)
            ?: doc.selectFirst("meta[name=csrf-token]")?.attr("content")?.takeIf(String::isNotBlank)
        val seasonSnapshot = extractWireSnapshot(doc.html(), "season-component")
        val loadedSeasons = byKey.values.map { it.season }.toSet()

        if (csrf != null && seasonSnapshot != null && seasons.isNotEmpty()) {
            val missing = seasons.filter { it.number !in loadedSeasons }
            Log.i(LOG_TAG, "Saisons à charger via Livewire=${missing.map { it.number }}")
            coroutineScope {
                missing.map { season ->
                    async {
                        val html = livewireCall(
                            origin = origin,
                            referer = "$origin$seriesPath",
                            csrf = csrf,
                            snapshot = seasonSnapshot,
                            method = "updateSeason",
                            params = listOf(season.id),
                        )
                        season to html
                    }
                }.awaitAll()
            }.forEach { (season, html) ->
                if (html.isNullOrBlank()) {
                    Log.w(LOG_TAG, "Livewire saison ${season.number} vide")
                    return@forEach
                }
                val fragment = Jsoup.parseBodyFragment(html, "$origin/")
                parseSiteEpisodes(fragment, origin).forEach { item ->
                    val fixed = item.copy(season = if (item.season > 0) item.season else season.number)
                    byKey.putIfAbsent(fixed.path, fixed)
                }
            }
        } else {
            Log.w(LOG_TAG, "Livewire skip csrf=${csrf != null} snap=${seasonSnapshot != null}")
        }

        return byKey.values
            .sortedWith(compareBy({ it.season }, { it.episode }))
            .map { item ->
                newEpisode(Playback(item.path).encode()) {
                    name = item.title
                    season = item.season
                    episode = item.episode
                    posterUrl = item.poster
                }
            }
    }

    private fun parseSeasonEntries(html: String): List<SeasonEntry> {
        val result = LinkedHashMap<String, SeasonEntry>()
        // wire:click="updateSeason('1251')" ... Saison 2
        SEASON_CLICK_BLOCK.findAll(html).forEach { m ->
            val id = m.groupValues[1]
            val label = m.groupValues[2].replace(Regex("\\s+"), " ").trim()
            val number = SEASON.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@forEach
            result[id] = SeasonEntry(id, number)
        }
        // ids seuls si le libellé n'a pas matché
        SEASON_CLICK.findAll(html).forEach { m ->
            val id = m.groupValues[1]
            if (id !in result) {
                // numéro inconnu : on ignore plutôt que d'inventer
            }
        }
        // snapshot season-component (saison courante)
        extractWireSnapshot(html, "season-component")?.let { snap ->
            runCatching {
                val data = JSONObject(snap).optJSONObject("data") ?: return@runCatching
                val sid = data.opt("seasonId")?.toString()?.takeIf { it.matches(NUMERIC_ID) } ?: return@runCatching
                val snum = data.optString("season_number").toIntOrNull()
                    ?: data.optInt("season_number", -1).takeIf { it > 0 }
                    ?: return@runCatching
                result.putIfAbsent(sid, SeasonEntry(sid, snum))
            }
        }
        return result.values.sortedBy { it.number }
    }

    private fun parseSiteEpisodes(document: Document, origin: String): List<SiteEpisode> {
        val result = LinkedHashMap<String, SiteEpisode>()
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
                ?: container?.selectFirst("img")?.let { imageUrl(it, origin) }
            result[path] = SiteEpisode(path, season, episode, title, poster)
        }
        return result.values.toList()
    }

    /**
     * Extrait le JSON wire:snapshot d'un composant Livewire.
     * On parse le HTML brut (regex) car les attributs `wire:*` sont capricieux avec Jsoup.
     */
    private fun extractWireSnapshot(html: String, componentName: String): String? {
        for (m in WIRE_SNAPSHOT.findAll(html)) {
            val raw = m.groupValues[1]
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&amp;", "&")
                .replace("&#34;", "\"")
            if (raw.contains("\"name\":\"$componentName\"") || raw.contains("\\\"name\\\":\\\"$componentName\\\"")) {
                // parfois double-échappé
                val normalized = if (raw.contains("\\\"name\\\"")) {
                    raw.replace("\\\"", "\"")
                } else raw
                if (normalized.contains("\"name\":\"$componentName\"")) return normalized
                return raw
            }
        }
        return null
    }

    private suspend fun livewireCall(
        origin: String,
        referer: String,
        csrf: String,
        snapshot: String,
        method: String,
        params: List<String>,
    ): String? {
        val payload = mapOf(
            "_token" to csrf,
            "components" to listOf(
                mapOf(
                    "snapshot" to snapshot,
                    "updates" to emptyMap<String, Any>(),
                    "calls" to listOf(
                        mapOf(
                            "path" to "",
                            "method" to method,
                            "params" to params,
                        ),
                    ),
                ),
            ),
        )

        val response = runCatching {
            app.post(
                url = "$origin/livewire/update",
                headers = headers + mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "*/*",
                    "X-Livewire" to "",
                    "X-CSRF-TOKEN" to csrf,
                    "Origin" to origin,
                ),
                referer = referer,
                json = payload,
                cacheTime = 0,
                timeout = 25L,
            )
        }.onFailure { Log.w(LOG_TAG, "Livewire $method erreur réseau", it) }.getOrNull()

        if (response == null) return null
        Log.i(LOG_TAG, "Livewire $method HTTP=${response.okhttpResponse.code} len=${response.text.length}")
        if (response.okhttpResponse.code !in 200..299) {
            Log.w(LOG_TAG, "Livewire body=${response.text.take(300)}")
            return null
        }
        return runCatching {
            val root = JSONObject(response.text)
            val components = root.optJSONArray("components") ?: return@runCatching null
            if (components.length() == 0) return@runCatching null
            components.getJSONObject(0).optJSONObject("effects")?.optString("html")
                ?.takeIf(String::isNotBlank)
        }.onFailure { Log.w(LOG_TAG, "Livewire parse réponse", it) }.getOrNull()
    }

    // -------------------------------------------------------------------------
    // Lecteurs (snapshot watch-component)
    // -------------------------------------------------------------------------

    private fun extractVideosFromWatchSnapshot(html: String): List<Pair<String, String>> {
        val snapshot = extractWireSnapshot(html, "watch-component") ?: run {
            Log.w(LOG_TAG, "watch-component snapshot introuvable")
            return emptyList()
        }
        val result = LinkedHashMap<String, Pair<String, String>>()
        runCatching {
            val data = JSONObject(snapshot).optJSONObject("data") ?: return@runCatching
            fun add(obj: JSONObject) {
                val link = obj.optString("link").takeIf(String::isNotBlank) ?: return
                val server = obj.optString("server_name").ifBlank { "Server" }
                val label = obj.optString("label").ifBlank { "HD" }
                val version = obj.optString("version").ifBlank { "" }
                val name = listOf(server, label, version).filter(String::isNotBlank).joinToString(" · ")
                result.putIfAbsent(link, name to link)
            }
            collectVideoObjects(data.opt("videos")).forEach(::add)
            when (val byVersion = data.opt("videosByVersion")) {
                is JSONObject -> {
                    val keys = byVersion.keys()
                    while (keys.hasNext()) {
                        val ver = keys.next()
                        collectVideoObjects(byVersion.opt(ver)).forEach { obj ->
                            val link = obj.optString("link").takeIf(String::isNotBlank) ?: return@forEach
                            val server = obj.optString("server_name").ifBlank { "Server" }
                            val label = obj.optString("label").ifBlank { "HD" }
                            result.putIfAbsent(link, listOf(server, label, ver).filter(String::isNotBlank).joinToString(" · ") to link)
                        }
                    }
                }
                is JSONArray -> collectVideoObjects(byVersion).forEach(::add)
            }
        }.onFailure { Log.w(LOG_TAG, "Parse watch snapshot", it) }
        Log.i(LOG_TAG, "watch snapshot sources=${result.size}")
        return result.values.toList()
    }

    private fun collectVideoObjects(node: Any?): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        when (node) {
            is JSONObject -> {
                if (node.has("link") && node.optString("link").isNotBlank()) out += node
                else {
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k == "s") continue
                        out += collectVideoObjects(node.opt(k))
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) out += collectVideoObjects(node.opt(i))
        }
        return out
    }

    private suspend fun resolveVidzy(embedUrl: String, referer: String): String? {
        val response = runCatching {
            app.get(embedUrl, headers = headers, referer = referer, cacheTime = 0, timeout = 15L)
        }.getOrNull() ?: return null
        if (response.okhttpResponse.code !in 200..299) return null
        val host = runCatching { URI(embedUrl).host.orEmpty() }.getOrDefault("")
        return decodeVidzySource(response.text, host)
    }

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

    // -------------------------------------------------------------------------
    // HTTP / parsing générique
    // -------------------------------------------------------------------------

    private data class Page(val document: Document, val origin: String)

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
        return doc.takeIf {
            it.select("a[href*=/movie/], a[href*=/tv-show/], a[href*=/episode/], h1").isNotEmpty()
                || it.html().contains("wire:snapshot")
        }
    }

    private fun parseCards(doc: Document, origin: String): List<SearchResponse> {
        val results = LinkedHashMap<String, SearchResponse>()
        doc.select("a[href*=/movie/], a[href*=/tv-show/]").forEach { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            val path = contentPath(href) ?: return@forEach
            if (path.startsWith("/episode/")) return@forEach
            val container = anchor.closest(".flw-item, .film_list-wrap, .item, article, li, div.relative") ?: anchor
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
            } else {
                newMovieSearchResponse(title, itemUrl, TvType.Movie) { posterUrl = poster }
            }
            results.putIfAbsent(path, response)
        }
        return results.values.toList()
    }

    private fun extractPlayers(doc: Document, origin: String): List<Pair<String, String>> {
        val result = LinkedHashMap<String, Pair<String, String>>()
        doc.select("iframe[src], video[src], source[src], [data-src], [data-url], [data-link], [data-embed]").forEach { element ->
            val raw = listOf("src", "data-src", "data-url", "data-link", "data-embed")
                .map { element.attr(it) }.firstOrNull(String::isNotBlank) ?: return@forEach
            if (raw.equals("iframeSrc", true)) return@forEach
            val url = absolute(raw, origin) ?: return@forEach
            if (!url.startsWith("http") || contentPath(url) != null) return@forEach
            val label = element.attr("title").ifBlank { URI(url).host ?: "Lecteur" }
            result[url] = label to url
        }
        return result.values.toList()
    }

    private fun isVidzyEmbed(raw: String): Boolean = runCatching {
        val host = URI(raw).host.orEmpty().lowercase()
        (host.contains("vidzy") || host.contains("vidz")) &&
            URI(raw).path.orEmpty().contains("/embed-", true)
    }.getOrDefault(false)

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

    private fun contentPath(url: String): String? {
        val path = runCatching { URI(url).path }.getOrNull() ?: url.substringBefore('?').substringBefore('#')
        return path.takeIf { CONTENT_PATH.matches(it) }
    }

    private fun absolute(raw: String?, origin: String): String? {
        if (raw.isNullOrBlank() || raw.startsWith("data:") || raw.startsWith("javascript:")) return null
        return runCatching { URI("$origin/").resolve(raw).toString() }.getOrNull()
    }

    companion object {
        private const val LOG_TAG = "XalaflixDebug"
        private val CONTENT_PATH = Regex("^/(movie|tv-show|episode)/[^/?#]+(?:/\\d+-\\d+)?/?$", RegexOption.IGNORE_CASE)
        private val NUMERIC_ID = Regex("\\d+")
        private val YEAR = Regex("\\b(?:19|20)\\d{2}\\b")
        private val SEASON = Regex("(?i)(?:saison|season)\\s*(\\d+)")
        private val SEASON_CLICK = Regex("""updateSeason\(\s*['"](\d+)['"]\s*\)""")
        private val SEASON_CLICK_BLOCK = Regex(
            """wire:click="updateSeason\(\s*'(\d+)'\s*\)"[^>]*>\s*([^<]+)\s*<""",
            RegexOption.IGNORE_CASE,
        )
        private val EPISODE_PATH = Regex("(?i)^/episode/[^/]+/(\\d+)-(\\d+)/?$")
        private val DIRECT_MEDIA = Regex("(?i)\\.(?:m3u8|mp4|mpd)(?:[?#]|$)")
        private val LONG_BASE64 = Regex("[A-Za-z0-9+/]{80,}={0,2}")
        private val WIRE_SNAPSHOT = Regex("""wire:snapshot="([^"]+)"""")
        private val CSRF_META = Regex("""name="csrf-token"\s+content="([^"]+)"""")
        private val LINK_JSON = Regex(""""link"\s*:\s*"(https?://[^"]+)"""")
    }
}
