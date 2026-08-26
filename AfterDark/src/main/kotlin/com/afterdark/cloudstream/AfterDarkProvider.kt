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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
                            fix = false,
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
        val capturedStatus = session.sourceResponseStatus
        val capturedBody = session.sourceResponseBody

        if (capturedStatus != null && capturedBody != null) {
            // Normal v11 flow: this is the exact response from the one official
            // /api/sources request intercepted during verification.
            return capturedStatus to capturedBody
        }

        // Defensive fallback for an older/incomplete in-memory session only.
        val headers = LinkedHashMap<String, String>()

        session.sourceRequestHeaders.forEach { (key, value) ->
            if (
                !key.equals("Host", ignoreCase = true) &&
                !key.equals("Connection", ignoreCase = true) &&
                !key.equals("Content-Length", ignoreCase = true) &&
                !key.equals("Cookie", ignoreCase = true) &&
                !key.equals("Referer", ignoreCase = true)
            ) {
                headers[key] = value
            }
        }

        headers["Accept"] = "application/x-ndjson"
        headers["x-nabi-proof"] = session.proof
        headers["User-Agent"] = session.userAgent

        session.cookie?.takeIf { it.isNotBlank() }?.let {
            headers["Cookie"] = it
        }

        // Replay the exact official GET /api/sources observed by the WebView
        // for this exact episode instead of rebuilding the URL after proof.
        val sourceUrl = session.sourceRequestUrl
            ?.takeIf { it.startsWith("$mainUrl/api/sources") }
            ?: sourcesApiUrl(request)

        val response = app.get(
            url = sourceUrl,
            headers = headers,
            referer = session.sourceReferer ?: request.watchUrl(mainUrl),
            cacheTime = 0,
        )

        return response.okhttpResponse.code to response.text
    }

    private suspend fun obtainSession(request: PlaybackRequest): ProofSession? {
        proofCache[request.sessionKey]?.let { return it }

        val session = try {
            AfterDarkProofWebView.acquire(request, mainUrl)
        } catch (_: Exception) {
            null
        } ?: return null

        proofCache[request.sessionKey] = session
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

    private fun fallbackSources(request: PlaybackRequest): List<ParsedSource> {
        val season = request.season ?: 1
        val episode = request.episode ?: 1

        val videasy = if (request.type == "tv") {
            "https://player.videasy.to/tv/${request.tmdbId}/$season/$episode" +
                "?overlay=true&color=8B5CF6" +
                "&nextEpisode=true&episodeSelector=true&autoplayNextEpisode=true"
        } else {
            "https://player.videasy.to/movie/${request.tmdbId}" +
                "?overlay=true&color=8B5CF6"
        }

        val peachify = if (request.type == "tv") {
            "https://peachify.top/embed/tv/${request.tmdbId}/$season/$episode" +
                "?dub=French&sub=French&autoNext=30"
        } else {
            "https://peachify.top/embed/movie/${request.tmdbId}" +
                "?dub=French&sub=French"
        }

        return listOf(
            "videasy" to videasy,
            "peachify" to peachify,
        ).map { (service, url) ->
            ParsedSource(
                group = "Secours",
                service = service,
                provider = "AfterDark",
                url = url,
                quality = null,
                language = null,
                type = "embed",
                proxied = false,
                referer = "$mainUrl/",
                headers = emptyMap(),
                subtitles = emptyList(),
            )
        }
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

    private suspend fun isEpisodeAlreadyAired(
        request: PlaybackRequest,
    ): Boolean {
        if (request.type != "tv") return true

        val season = request.season ?: return true
        val episode = request.episode ?: return true

        return runCatching {
            val details = tmdbGet(
                "/tv/${request.tmdbId}/season/$season/episode/$episode",
            )

            val airDate = details.stringOrNull("air_date")
                ?: return@runCatching true

            // ISO yyyy-MM-dd strings sort chronologically.
            val today = SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.US,
            ).format(Date())

            airDate <= today
        }.getOrDefault(true)
    }

    private fun responseContentType(headers: Map<String, String>): String =
        headers.entries
            .firstOrNull { (key, _) -> key.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.substringBefore(";")
            ?.trim()
            ?.lowercase()
            .orEmpty()

    private suspend fun validateResolvedMedia(
        resolved: ResolvedWebMedia,
    ): Boolean {
        val referer = resolved.referer.orEmpty()
        val headers = LinkedHashMap<String, String>(resolved.headers)

        // Never reuse a Range header captured from a browser media segment for
        // playlist validation.
        headers.keys
            .firstOrNull { it.equals("Range", ignoreCase = true) }
            ?.let { headers.remove(it) }

        return when (resolved.type) {
            "m3u8" -> runCatching {
                val response = app.get(
                    url = resolved.url,
                    headers = headers,
                    referer = referer,
                    cacheTime = 0,
                )

                if (response.okhttpResponse.code !in 200..299) {
                    return@runCatching false
                }

                val contentType = responseContentType(response.headers)
                val body = response.text.trimStart()

                // A real HLS manifest starts with #EXTM3U. Accept the MIME type
                // too because a few hosts return a minimal/variant body.
                body.startsWith("#EXTM3U") ||
                    "mpegurl" in contentType
            }.getOrDefault(false)

            "mpd" -> runCatching {
                val response = app.get(
                    url = resolved.url,
                    headers = headers,
                    referer = referer,
                    cacheTime = 0,
                )

                if (response.okhttpResponse.code !in 200..299) {
                    return@runCatching false
                }

                val contentType = responseContentType(response.headers)
                val body = response.text.trimStart()

                body.contains("<MPD", ignoreCase = true) ||
                    "dash+xml" in contentType
            }.getOrDefault(false)

            else -> {
                // For direct video, HEAD avoids downloading the media. Some
                // hosts reject HEAD, so only 404/410 are final failures; other
                // failures fall back to a one-byte Range probe.
                val head = runCatching {
                    app.head(
                        url = resolved.url,
                        headers = headers,
                        referer = referer,
                        timeout = 10L,
                    )
                }.getOrNull()

                if (head != null) {
                    val code = head.okhttpResponse.code
                    if (code == 404 || code == 410) {
                        return false
                    }

                    if (code in 200..299) {
                        val contentType = responseContentType(head.headers)
                        val contentLength = head.headers.entries
                            .firstOrNull { (key, _) ->
                                key.equals("Content-Length", ignoreCase = true)
                            }
                            ?.value
                            ?.toLongOrNull()

                        if (
                            contentType.startsWith("video/") ||
                            contentType == "application/octet-stream" ||
                            (contentLength != null && contentLength > 0)
                        ) {
                            return true
                        }
                    }
                }

                runCatching {
                    val probeHeaders = LinkedHashMap(headers)
                    probeHeaders["Range"] = "bytes=0-0"

                    val response = app.get(
                        url = resolved.url,
                        headers = probeHeaders,
                        referer = referer,
                        cacheTime = 0,
                    )

                    val code = response.okhttpResponse.code
                    if (code !in 200..299) {
                        return@runCatching false
                    }

                    val contentType = responseContentType(response.headers)
                    val contentRange = response.headers.entries
                        .firstOrNull { (key, _) ->
                            key.equals("Content-Range", ignoreCase = true)
                        }
                        ?.value

                    // Reject common "200 OK" HTML/JSON error pages.
                    val clearlyNotVideo =
                        contentType.startsWith("text/html") ||
                            contentType.startsWith("application/json") ||
                            contentType.startsWith("text/plain")

                    !clearlyNotVideo && (
                        code == 206 ||
                            !contentRange.isNullOrBlank() ||
                            contentType.startsWith("video/") ||
                            contentType == "application/octet-stream"
                    )
                }.getOrDefault(false)
            }
        }
    }

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
            proofCache.remove(request.sessionKey)
            session = obtainSession(request) ?: return false
            response = fetchSources(request, session)
        }

        if (response.first !in 200..299) return false

        val parsedSources = parseNdjson(response.second)

        // AfterDark's own frontend falls back to three embeds when the streamed
        // /api/sources response finishes without any item.
        val sources = if (parsedSources.isEmpty()) {
            fallbackSources(request)
        } else {
            parsedSources
        }

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

            var extractorEmitted = false

            runCatching {
                loadExtractor(
                    url = source.url,
                    referer = sourceReferer,
                    subtitleCallback = subtitleCallback,
                    callback = {
                        extractorEmitted = true
                        emitted = true
                        callback(it)
                    },
                )
            }

            if (extractorEmitted) {
                // "Secours" is a failover chain, not a list that should be
                // resolved all at once. Once Videasy (or a later fallback)
                // produced a real link, stop here so the next fallback does
                // not replace it while it is still loading.
                if (source.group == "Secours") return true
                continue
            }

            // AfterDark's official fallback embeds are browser players. When
            // CloudStream has no extractor for one of them, let the official
            // player execute normally in an internal WebView and capture the
            // real HLS/DASH/video request it produces.
            if (isEmbed && source.group == "Secours") {
                if (
                    source.service.equals("videasy", ignoreCase = true) &&
                    !isEpisodeAlreadyAired(request)
                ) {
                    // Do not wait on a Videasy player for an episode that has
                    // not aired yet. Continue directly to Peachify.
                    continue
                }

                val resolved = runCatching {
                    AfterDarkEmbedWebView.resolve(
                        embedUrl = source.url,
                        sourceName = source.service,
                        referer = sourceReferer,
                    )
                }.getOrNull()

                if (resolved != null && seen.add(resolved.url)) {
                    // Detection alone is not success: a player can briefly
                    // expose a stale/deleted media URL. Verify it before
                    // stopping the fallback chain.
                    val valid = validateResolvedMedia(resolved)

                    if (!valid) {
                        // Videasy dead/inaccessible => continue to Peachify.
                        continue
                    }

                    val resolvedType = when (resolved.type) {
                        "m3u8" -> ExtractorLinkType.M3U8
                        "mpd" -> ExtractorLinkType.DASH
                        else -> ExtractorLinkType.VIDEO
                    }

                    callback(
                        newExtractorLink(
                            source = source.service,
                            name = "AfterDark · ${source.service}",
                            url = resolved.url,
                            type = resolvedType,
                        ) {
                            referer = resolved.referer.orEmpty()
                            quality = getQualityFromName(source.quality)
                            headers = resolved.headers
                        },
                    )
                    emitted = true

                    // Only a verified fallback media URL stops the chain.
                    return true
                }

                continue
            }

            // Unknown non-embed URLs can still be handed to the player.
            // Unknown embeds are never falsely exposed as direct media.
            if (!isEmbed) {
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
