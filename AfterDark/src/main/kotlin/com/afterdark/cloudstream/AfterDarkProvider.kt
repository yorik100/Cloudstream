package com.afterdark.cloudstream

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class AfterDarkProvider : MainAPI() {
    override var mainUrl = "https://afterdark06.mom"
    override var name = "AfterDark"
    override var lang = "fr"

    override val hasMainPage = true
    override val usesWebView = true
    override val hasDownloadSupport = false
    override val hasChromecastSupport = false
    override val loadLinksTimeoutMs: Long? = 210_000L
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbImages = "https://image.tmdb.org/t/p"

    // Public frontend key present in AfterDark's current browser bundle.
    private val tmdbApiKey = "f3d757824f08ea2cff45eb8f47ca3a1e"

    private val proofCache = ConcurrentHashMap<String, ProofSession>()

    override val mainPage = mainPageOf(
        "movie" to "Films populaires",
        "tv" to "Séries populaires",
    )

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun tmdbUrl(
        path: String,
        params: Map<String, String?> = emptyMap(),
    ): String {
        val query = linkedMapOf(
            "api_key" to tmdbApiKey,
            "language" to "fr-FR",
            "include_adult" to "false",
        )

        params.forEach { (key, value) ->
            if (value != null) query[key] = value
        }

        return "$tmdbApi$path?" + query.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
    }

    private fun poster(path: String?, size: String = "w500"): String? =
        path?.takeIf { it.isNotBlank() }?.let { "$tmdbImages/$size$it" }

    private fun year(date: String?): Int? =
        date?.take(4)?.toIntOrNull()

    private fun watchUrl(type: String, id: Int): String =
        "$mainUrl/watch/$type-$id"

    private fun JSONObject.stringOrNull(key: String): String? =
        optString(key, "").trim().takeIf { it.isNotEmpty() }

    private fun JSONObject.stringMap(key: String): Map<String, String> {
        val obj = optJSONObject(key) ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        val keys = obj.keys()

        while (keys.hasNext()) {
            val name = keys.next()
            val value = obj.optString(name, "")
            if (value.isNotBlank()) result[name] = value
        }

        return result
    }

    private fun JSONArray.objects(): Sequence<JSONObject> = sequence {
        for (index in 0 until length()) {
            optJSONObject(index)?.let { yield(it) }
        }
    }

    private suspend fun tmdbGet(path: String, params: Map<String, String?> = emptyMap()): JSONObject {
        val response = app.get(tmdbUrl(path, params), cacheTime = 0)
        return runCatching { JSONObject(response.text) }
            .getOrElse { throw ErrorLoadingException("Réponse TMDB invalide") }
    }

    private fun JSONObject.toSearchResponse(forcedType: String? = null): SearchResponse? {
        val mediaType = forcedType ?: stringOrNull("media_type") ?: return null
        if (mediaType != "movie" && mediaType != "tv") return null

        val id = optInt("id", 0).takeIf { it > 0 } ?: return null
        val itemName = if (mediaType == "movie") {
            stringOrNull("title")
        } else {
            stringOrNull("name")
        } ?: return null

        val itemYear = year(
            if (mediaType == "movie") stringOrNull("release_date")
            else stringOrNull("first_air_date")
        )

        val itemPoster = poster(stringOrNull("poster_path"))
        val itemScore = Score.from10(
            if (has("vote_average")) optDouble("vote_average") else null
        )

        return if (mediaType == "movie") {
            newMovieSearchResponse(
                name = itemName,
                url = watchUrl(mediaType, id),
                type = TvType.Movie,
            ) {
                posterUrl = itemPoster
                year = itemYear
                score = itemScore
            }
        } else {
            newTvSeriesSearchResponse(
                name = itemName,
                url = watchUrl(mediaType, id),
                type = TvType.TvSeries,
            ) {
                posterUrl = itemPoster
                year = itemYear
                score = itemScore
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val type = if (request.data == "tv") "tv" else "movie"
        val endpoint = if (type == "tv") "/tv/popular" else "/movie/popular"
        val root = tmdbGet(endpoint, mapOf("page" to page.toString()))
        val results = root.optJSONArray("results") ?: JSONArray()

        val items = results.objects()
            .mapNotNull { it.toSearchResponse(type) }
            .toList()

        val totalPages = root.optInt("total_pages", page)
        return newHomePageResponse(request, items, page < totalPages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val root = tmdbGet(
            "/search/multi",
            mapOf(
                "query" to query,
                "page" to "1",
            ),
        )
        val results = root.optJSONArray("results") ?: JSONArray()

        return results.objects()
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
            .toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val match = Regex("""/watch/(movie|tv)-(\d+)""").find(url)
            ?: throw ErrorLoadingException("URL AfterDark invalide")

        val type = match.groupValues[1]
        val tmdbId = match.groupValues[2].toIntOrNull()
            ?: throw ErrorLoadingException("TMDB ID invalide")

        val details = tmdbGet("/$type/$tmdbId")

        val title = if (type == "movie") {
            details.stringOrNull("title")
        } else {
            details.stringOrNull("name")
        } ?: throw ErrorLoadingException("Titre introuvable")

        val releaseYear = year(
            if (type == "movie") details.stringOrNull("release_date")
            else details.stringOrNull("first_air_date")
        )

        val commonPoster = poster(details.stringOrNull("poster_path"))
        val backdrop = poster(details.stringOrNull("backdrop_path"), "w1280")
        val plotText = details.stringOrNull("overview")
        val scoreValue = Score.from10(
            if (details.has("vote_average")) details.optDouble("vote_average") else null
        )
        val tags = (details.optJSONArray("genres") ?: JSONArray())
            .objects()
            .mapNotNull { it.stringOrNull("name") }
            .toList()

        return if (type == "movie") {
            val playback = PlaybackRequest(
                tmdbId = tmdbId,
                type = "movie",
                title = title,
                releaseYear = releaseYear,
            )

            newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = playback.encode(),
            ) {
                posterUrl = commonPoster
                backgroundPosterUrl = backdrop
                year = releaseYear
                plot = plotText
                this.tags = tags
                duration = details.optInt("runtime", 0).takeIf { it > 0 }
                score = scoreValue
            }
        } else {
            val seasons = details.optJSONArray("seasons") ?: JSONArray()
            val episodes = seasons.objects()
                .filter { it.optInt("episode_count", 0) > 0 }
                .sortedBy { it.optInt("season_number", 0) }
                .flatMap { season ->
                    val seasonNumber = season.optInt("season_number", 0)
                    val episodeCount = season.optInt("episode_count", 0)
                    val seasonPoster = poster(season.stringOrNull("poster_path"))

                    (1..episodeCount).asSequence().map { episodeNumber ->
                        val playback = PlaybackRequest(
                            tmdbId = tmdbId,
                            type = "tv",
                            title = title,
                            releaseYear = releaseYear,
                            season = seasonNumber,
                            episode = episodeNumber,
                        )

                        newEpisode(
                            url = playback.encode(),
                            initializer = {
                                name = "Épisode $episodeNumber"
                                this.season = seasonNumber
                                episode = episodeNumber
                                posterUrl = seasonPoster
                            },
                        )
                    }
                }
                .toList()

            newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = episodes,
            ) {
                posterUrl = commonPoster
                backgroundPosterUrl = backdrop
                year = releaseYear
                plot = plotText
                this.tags = tags
                score = scoreValue
            }
        }
    }

    private fun sourcesApiUrl(request: PlaybackRequest): String {
        val params = linkedMapOf(
            "tmdbId" to request.tmdbId.toString(),
            "type" to request.type,
            "title" to request.title,
        )

        request.releaseYear?.let { params["releaseYear"] = it.toString() }
        request.season?.let { params["season"] = it.toString() }
        request.episode?.let { params["episode"] = it.toString() }

        return "$mainUrl/api/sources?" + params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
    }

    private suspend fun fetchSources(
        request: PlaybackRequest,
        session: ProofSession,
    ): Pair<Int, String> {
        val referer = request.watchUrl(mainUrl)
        val headers = linkedMapOf(
            "Accept" to "application/x-ndjson",
            "x-nabi-proof" to session.proof,
            "User-Agent" to session.userAgent,
        )

        session.cookie?.takeIf { it.isNotBlank() }?.let {
            headers["Cookie"] = it
        }

        val response = app.get(
            url = sourcesApiUrl(request),
            headers = headers,
            referer = referer,
            cacheTime = 0,
        )

        return response.okhttpResponse.code to response.text
    }

    private suspend fun obtainSession(request: PlaybackRequest): ProofSession? {
        proofCache[request.titleKey]?.let { return it }

        val session = try {
            AfterDarkProofWebView.acquire(request, mainUrl)
        } catch (_: Exception) {
            null
        } ?: return null

        proofCache[request.titleKey] = session
        return session
    }

    private fun parseSubtitle(node: JSONObject): ParsedSubtitle? {
        val url = sequenceOf("url", "file", "src")
            .mapNotNull { node.stringOrNull(it) }
            .firstOrNull()
            ?: return null

        val language = sequenceOf("language", "lang", "label", "name")
            .mapNotNull { node.stringOrNull(it) }
            .firstOrNull()
            ?: "Sous-titre"

        return ParsedSubtitle(
            language = language,
            url = url,
            headers = node.stringMap("headers"),
        )
    }

    private fun parseSubtitles(array: JSONArray?): List<ParsedSubtitle> {
        if (array == null) return emptyList()

        val result = ArrayList<ParsedSubtitle>()

        for (index in 0 until array.length()) {
            val raw = array.opt(index)
            when (raw) {
                is String -> if (raw.startsWith("http")) {
                    result += ParsedSubtitle("Sous-titre", raw)
                }
                is JSONObject -> parseSubtitle(raw)?.let { result += it }
            }
        }

        return result
    }

    private fun parseNdjson(body: String): List<ParsedSource> {
        val sources = ArrayList<ParsedSource>()

        body.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            val group = runCatching { JSONObject(line) }.getOrNull()
                ?: return@forEach
            val items = group.optJSONArray("items")
                ?: return@forEach
            if (items.length() == 0) return@forEach

            val groupName = group.stringOrNull("provider")
                ?: group.stringOrNull("id")
                ?: "AfterDark"

            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val sourceUrl = item.stringOrNull("url") ?: continue

                sources += ParsedSource(
                    group = groupName,
                    service = item.stringOrNull("service") ?: "AfterDark",
                    provider = item.stringOrNull("provider") ?: groupName,
                    url = sourceUrl,
                    quality = item.stringOrNull("quality"),
                    language = item.stringOrNull("language"),
                    type = item.stringOrNull("type"),
                    proxied = item.optBoolean("proxied", false),
                    referer = item.stringOrNull("referer"),
                    headers = item.stringMap("headers"),
                    subtitles = parseSubtitles(item.optJSONArray("subtitles")),
                )
            }
        }

        return sources.distinctBy { "${it.service}|${it.url}" }
    }

    private fun directType(source: ParsedSource): ExtractorLinkType? {
        val declared = source.type?.lowercase()

        return when (declared) {
            "hls", "m3u8" -> ExtractorLinkType.M3U8
            "dash", "mpd" -> ExtractorLinkType.DASH
            "mp4", "video", "direct" -> ExtractorLinkType.VIDEO
            else -> {
                val path = source.url.substringBefore("?")
                when {
                    path.endsWith(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                    path.endsWith(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                    Regex(""".*\.(mp4|mkv|webm)$""", RegexOption.IGNORE_CASE).matches(path) ->
                        ExtractorLinkType.VIDEO
                    else -> null
                }
            }
        }
    }

    private fun displayName(source: ParsedSource): String =
        listOfNotNull(
            source.provider.takeIf { it.isNotBlank() && it != "AfterDark" },
            source.service.takeIf { it.isNotBlank() },
            source.language,
            source.quality,
        )
            .distinct()
            .joinToString(" · ")
            .ifBlank { source.group.ifBlank { "AfterDark" } }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val request = PlaybackRequest.decode(data)
            ?: throw ErrorLoadingException("Données AfterDark invalides")

        var session = obtainSession(request) ?: return false
        var response = fetchSources(request, session)

        // Proofs are temporary. Retry once through the official WebView flow.
        if (response.first == 403) {
            proofCache.remove(request.titleKey)
            session = obtainSession(request) ?: return false
            response = fetchSources(request, session)
        }

        if (response.first !in 200..299) return false

        val sources = parseNdjson(response.second)
        if (sources.isEmpty()) return false

        var emitted = false
        val seen = HashSet<String>()

        for (source in sources) {
            for (subtitle in source.subtitles) {
                subtitleCallback(
                    newSubtitleFile(subtitle.language, subtitle.url) {
                        if (subtitle.headers.isNotEmpty()) {
                            headers = subtitle.headers
                        }
                    },
                )
            }

            if (!seen.add(source.url)) continue

            val type = directType(source)
            val isEmbed = source.type.equals("embed", ignoreCase = true)
            val sourceReferer = source.referer ?: "$mainUrl/"
            val sourceHeaders = LinkedHashMap<String, String>(source.headers)
            if (sourceHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                sourceHeaders["User-Agent"] = session.userAgent
            }

            if (type != null || (source.proxied && !isEmbed)) {
                callback(
                    newExtractorLink(
                        source = source.service,
                        name = displayName(source),
                        url = source.url,
                        type = type,
                    ) {
                        referer = sourceReferer
                        quality = getQualityFromName(source.quality)
                        headers = sourceHeaders
                    },
                )
                emitted = true
                continue
            }

            val extractorLoaded = runCatching {
                loadExtractor(
                    url = source.url,
                    referer = sourceReferer,
                    subtitleCallback = subtitleCallback,
                    callback = {
                        emitted = true
                        callback(it)
                    },
                )
            }.getOrDefault(false)

            // Unknown embeds must not be falsely exposed as direct media.
            if (!extractorLoaded && !isEmbed) {
                callback(
                    newExtractorLink(
                        source = source.service,
                        name = displayName(source),
                        url = source.url,
                        type = null,
                    ) {
                        referer = sourceReferer
                        quality = getQualityFromName(source.quality)
                        headers = sourceHeaders
                    },
                )
                emitted = true
            }
        }

        return emitted
    }
}
