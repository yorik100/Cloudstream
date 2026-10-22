package com.afterdark.cloudstream

import android.util.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.addDate
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
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

class AfterDarkProvider : MainAPI() {
    override var mainUrl = AfterDarkDomainResolver.SOURCE_ORIGIN
    override var name = "AfterDark"
    override var lang = "fr"

    override val hasMainPage = true
    // Verification and fallback players open their own WebViews explicitly.
    // Keep CloudStream from treating the unresolved registry URL as a page to
    // open automatically when link loading fails.
    override val usesWebView = false
    override val hasDownloadSupport = false
    override val hasChromecastSupport = false
    override val loadLinksTimeoutMs: Long? = 210_000L
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbImages = "https://image.tmdb.org/t/p"

    // Public frontend key present in AfterDark's current browser bundle.
    private val tmdbApiKey = "f3d757824f08ea2cff45eb8f47ca3a1e"

    private val proofCache = ConcurrentHashMap<String, ProofSession>()
    private val domainResolver = AfterDarkDomainResolver()

    private suspend fun ensureAfterDarkDomain(): String? {
        val resolved = domainResolver.resolve() ?: return null
        mainUrl = resolved
        return resolved
    }

    internal suspend fun prepareDomainInBackground() {
        Log.i(PROVIDER_TAG, "Résolution AfterDark en arrière-plan démarrée")
        domainResolver.resolve()?.let { resolved ->
            mainUrl = resolved
            Log.i(PROVIDER_TAG, "Résolution AfterDark en arrière-plan réussie : $resolved")
        } ?: run {
            Log.w(PROVIDER_TAG, "Résolution AfterDark en arrière-plan sans résultat")
        }
    }

    private suspend fun requireAfterDarkDomainForPlayback() {
        Log.i(PROVIDER_TAG, "Résolution AfterDark demandée par le lancement vidéo")

        val resolved = ensureAfterDarkDomain()
        if (resolved == null) {
            Log.w(PROVIDER_TAG, "Lancement vidéo annulé : aucun lien AfterDark trouvé")
            throw ErrorLoadingException("Le lien AfterDark n'a pas été trouvé")
        }

        Log.i(PROVIDER_TAG, "Résolution AfterDark pour la vidéo réussie : $resolved")
    }

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
        if (ensureAfterDarkDomain() == null) {
            return newHomePageResponse(request, emptyList<SearchResponse>(), false)
        }

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
        if (ensureAfterDarkDomain() == null) return emptyList()

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
        if (ensureAfterDarkDomain() == null) {
            throw ErrorLoadingException("AfterDark est temporairement inaccessible")
        }

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
            val seasons = (details.optJSONArray("seasons") ?: JSONArray())
                .objects()
                .filter { it.optInt("episode_count", 0) > 0 }
                .sortedBy { it.optInt("season_number", 0) }
                .toList()

            // Match AfterDark's title page exactly:
            // an episode is considered aired when air_date is missing or when
            // its ISO date is <= today's UTC ISO date.
            val todayUtc = afterDarkTodayUtc()
            val episodes = mutableListOf<Episode>()

            for (season in seasons) {
                val seasonNumber = season.optInt("season_number", 0)
                if (seasonNumber < 0) continue

                val episodeCount = season.optInt("episode_count", 0)
                val seasonPoster = poster(season.stringOrNull("poster_path"))

                val seasonDetails = runCatching {
                    tmdbGet("/tv/$tmdbId/season/$seasonNumber")
                }.getOrNull()

                val episodeMetadata = (seasonDetails?.optJSONArray("episodes") ?: JSONArray())
                    .objects()
                    .associateBy { it.optInt("episode_number", 0) }

                for (episodeNumber in 1..episodeCount) {
                    val metadata = episodeMetadata[episodeNumber]
                    val airDate = metadata?.stringOrNull("air_date")
                    val aired = isAiredOnAfterDark(airDate, todayUtc)

                    val episodeTitle = metadata
                        ?.stringOrNull("name")
                        ?.takeIf { it.isNotBlank() }
                        ?: "Épisode $episodeNumber"

                    val episodePoster = poster(
                        metadata?.stringOrNull("still_path"),
                        "w500",
                    ) ?: seasonPoster

                    val episodeOverview = metadata
                        ?.stringOrNull("overview")
                        ?.takeIf { it.isNotBlank() }

                    val runtime = metadata
                        ?.optInt("runtime", 0)
                        ?.takeIf { it > 0 }

                    val playback = PlaybackRequest(
                        tmdbId = tmdbId,
                        type = "tv",
                        title = title,
                        releaseYear = releaseYear,
                        season = seasonNumber,
                        episode = episodeNumber,
                    )

                    episodes += newEpisode(
                        url = playback.encode(),
                        initializer = {
                            name = if (aired) {
                                episodeTitle
                            } else {
                                "⏳ À venir · $episodeTitle"
                            }
                            this.season = seasonNumber
                            episode = episodeNumber
                            posterUrl = episodePoster
                            description = if (aired) {
                                episodeOverview
                            } else {
                                listOfNotNull(
                                    airDate?.let { "Prévu le $it" },
                                    episodeOverview,
                                ).joinToString("\n\n").ifBlank {
                                    "Épisode à venir"
                                }
                            }
                            runTime = runtime
                            addDate(airDate)
                        },
                        fix = false,
                    )
                }
            }

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

    private suspend fun obtainSession(
        request: PlaybackRequest,
        backgroundPreload: Boolean,
    ): ProofSession? {
        proofCache[request.sessionKey]?.let { return it }

        val session = try {
            AfterDarkProofWebView.acquire(
                request = request,
                mainUrl = mainUrl,
                backgroundPreload = backgroundPreload,
            )
        } catch (_: Exception) {
            null
        } ?: return null

        if (session.resolvedMedia == null) {
            proofCache[request.sessionKey] = session
        }
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

        return sources.distinctBy {
            listOf(
                it.group,
                it.provider,
                it.service,
                it.language.orEmpty(),
                it.quality.orEmpty(),
                it.type.orEmpty(),
                it.url,
            ).joinToString("|")
        }
    }

    private fun fallbackSources(request: PlaybackRequest): List<ParsedSource> {
        val season = request.season ?: 1
        val episode = request.episode ?: 1

        // The verification WebView is destroyed as soon as /api/sources is
        // captured and is never allowed to mount AfterDark's official player.
        // Therefore an empty official response reaches this native fallback
        // path instead of staying inside the /watch page.
        //
        // Peachify remains the existing defensive fallback here.
        val peachify = if (request.type == "tv") {
            "https://peachify.top/embed/tv/${request.tmdbId}/$season/$episode" +
                "?dub=French&sub=French&autoNext=30"
        } else {
            "https://peachify.top/embed/movie/${request.tmdbId}" +
                "?dub=French&sub=French"
        }

        return listOf(
            ParsedSource(
                group = "Secours",
                service = "peachify",
                provider = "AfterDark",
                url = peachify,
                quality = null,
                language = null,
                type = "embed",
                proxied = false,
                referer = "$mainUrl/",
                headers = emptyMap(),
                subtitles = emptyList(),
            ),
        )
    }

    private fun directType(source: ParsedSource): ExtractorLinkType? {
        val declared = source.type?.lowercase()

        return when (declared) {
            "hls", "m3u8" -> ExtractorLinkType.M3U8
            "dash", "mpd" -> ExtractorLinkType.DASH
            "mp4", "video" -> ExtractorLinkType.VIDEO
            // "direct" only describes the delivery path. AfterDark can use
            // extensionless HLS proxy URLs for direct providers, so never
            // force those URLs to VIDEO before MIME/type classification.
            "direct" -> null
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
            source.group.takeIf {
                it.isNotBlank() &&
                    !it.equals("AfterDark", ignoreCase = true)
            },
            source.provider.takeIf {
                it.isNotBlank() &&
                    !it.equals("AfterDark", ignoreCase = true) &&
                    !it.equals(source.group, ignoreCase = true)
            },
            source.service.takeIf { it.isNotBlank() },
            source.language,
            source.quality,
        )
            .distinctBy { it.lowercase() }
            .joinToString(" · ")
            .ifBlank { source.group.ifBlank { "AfterDark" } }

    private fun afterDarkTodayUtc(): String =
        SimpleDateFormat(
            "yyyy-MM-dd",
            Locale.US,
        ).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    private fun isAiredOnAfterDark(
        airDate: String?,
        todayUtc: String,
    ): Boolean =
        airDate == null || airDate <= todayUtc

    private suspend fun validateResolvedMedia(
        resolved: ResolvedWebMedia,
    ): ResolvedWebMedia? = AfterDarkMediaProbe.validate(resolved)

    private fun extractorType(resolved: ResolvedWebMedia): ExtractorLinkType =
        when (resolved.type) {
            "m3u8" -> ExtractorLinkType.M3U8
            "mpd" -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

    private fun mediaHint(type: ExtractorLinkType?): String? =
        when (type) {
            ExtractorLinkType.M3U8 -> "m3u8"
            ExtractorLinkType.DASH -> "mpd"
            ExtractorLinkType.VIDEO -> "video"
            else -> null
        }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // Keep CloudStream's player in its normal loading state while the same
        // HTTP-only resolver used at extension startup runs. Throwing here on
        // failure prevents CloudStream from opening mainUrl (the registry) as
        // a fallback WebView.
        requireAfterDarkDomainForPlayback()

        val request = PlaybackRequest.decode(data)
            ?: throw ErrorLoadingException("Données AfterDark invalides")

        val playerRole = AfterDarkRuntime.playerRequestRole(data)
        val backgroundPreload =
            request.type == "tv" &&
                playerRole == AfterDarkPlayerRequestRole.NEXT_PRELOAD

        Log.i(
            PROVIDER_TAG,
            "loadLinks ${request.sessionKey}: role=$playerRole " +
                "backgroundPreload=$backgroundPreload",
        )

        var session = obtainSession(
            request = request,
            backgroundPreload = backgroundPreload,
        ) ?: return false

        session.resolvedMedia?.let { resolved ->
            val checked = validateResolvedMedia(resolved)
            if (checked != null) {
                val service = session.resolvedService
                    ?.takeIf { it.isNotBlank() }
                    ?: "AfterDark"

                callback(
                    newExtractorLink(
                        source = service,
                        name = "AfterDark · $service",
                        url = checked.url,
                        type = extractorType(checked),
                    ) {
                        referer = checked.referer.orEmpty()
                        headers = checked.headers
                    },
                )

                return true
            }

            Log.w(
                PROVIDER_TAG,
                "Média capturé rejeté par le probe final service=${session.resolvedService.orEmpty()}",
            )
        }

        var response = fetchSources(request, session)

        // Proofs are temporary. A real playback request may retry once through
        // the official WebView flow. Background preload is strictly one-shot:
        // if it fails, CloudStream will retry normally when the episode is
        // actually opened.
        if (response.first == 403) {
            proofCache.remove(request.sessionKey)
            if (backgroundPreload) {
                Log.w(
                    PROVIDER_TAG,
                    "Préchargement ${request.sessionKey}: 403, aucun restart en arrière-plan",
                )
                return false
            }
            session = obtainSession(
                request = request,
                backgroundPreload = false,
            ) ?: return false
            response = fetchSources(request, session)
        }

        if (response.first !in 200..299) return false

        val parsedSources = parseNdjson(response.second)

        Log.i(
            PROVIDER_TAG,
            "Sources officielles reçues ${request.sessionKey}: " +
                if (parsedSources.isEmpty()) {
                    "aucune"
                } else {
                    parsedSources.joinToString(", ") { parsed ->
                        val host = AfterDarkMediaProbe.safeHost(parsed.url)
                            .ifBlank { "sans-host" }
                        "${parsed.service}@${host}:type=${parsed.type.orEmpty()}"
                    }
                },
        )

        // The verification-only WebView always returns control after capturing
        // /api/sources, including when the official response has no items.
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

            val sourceIdentity = listOf(
                source.group,
                source.service,
                source.language.orEmpty(),
                source.quality.orEmpty(),
                source.type.orEmpty(),
                source.url,
            ).joinToString("|")
            if (!seen.add(sourceIdentity)) continue

            Log.i(
                PROVIDER_TAG,
                "Source reçue service=${source.service} " +
                    "host=${AfterDarkMediaProbe.safeHost(source.url)} " +
                    "type=${source.type.orEmpty()} proxied=${source.proxied}",
            )

            val type = directType(source)
            val declaredType = source.type.orEmpty().lowercase()
            val sourceHost = runCatching {
                java.net.URI(source.url).host.orEmpty().lowercase()
            }.getOrDefault("")
            val sourceService = source.service.lowercase()

            // The official selector exposes direct provider groups (for
            // example Canberra/Paris) separately from the "Embeds" group.
            // A direct Paris source can legitimately have service=uqload;
            // the service label alone must therefore never turn it into an
            // Uqload iframe/embed.
            val explicitEmbed =
                declaredType == "embed" ||
                    declaredType == "iframe" ||
                    declaredType == "player"
            val groupEmbed = source.group.equals("Embeds", ignoreCase = true)
            val officialDirectGroup =
                source.group.isNotBlank() &&
                    !groupEmbed &&
                    !source.group.equals("Secours", ignoreCase = true)
            val hostLooksNativeEmbed =
                "uqload" in sourceHost ||
                    "vidzy" in sourceHost
            val serviceLooksNativeEmbed =
                "uqload" in sourceService ||
                    "vidzy" in sourceService
            val isKnownNativeEmbed =
                hostLooksNativeEmbed ||
                    (serviceLooksNativeEmbed && (explicitEmbed || groupEmbed || !officialDirectGroup))
            val isEmbed =
                explicitEmbed ||
                    groupEmbed ||
                    (isKnownNativeEmbed && !officialDirectGroup)
            val sourceReferer = source.referer ?: "$mainUrl/"
            val sourceHeaders = LinkedHashMap<String, String>(source.headers)
            if (sourceHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                sourceHeaders["User-Agent"] = session.userAgent
            }

            if (!isEmbed && (type != null || source.proxied || officialDirectGroup)) {
                // Official direct sources can be short-lived signed URLs.
                // Do not consume them with a GET probe before ExoPlayer. Use
                // declared/path information, then HEAD only when the type is
                // still unknown (notably extensionless direct HLS proxies).
                val checked = AfterDarkMediaProbe.classifyWithoutBody(
                    ResolvedWebMedia(
                        url = source.url,
                        referer = sourceReferer,
                        headers = sourceHeaders,
                        type = mediaHint(type),
                    ),
                )

                if (checked != null) {
                    callback(
                        newExtractorLink(
                            source = source.service,
                            name = displayName(source),
                            url = checked.url,
                            type = extractorType(checked),
                        ) {
                            referer = checked.referer.orEmpty()
                            quality = getQualityFromName(source.quality)
                            headers = checked.headers
                        },
                    )
                    emitted = true
                    Log.i(
                        PROVIDER_TAG,
                        "Source émise service=${source.service} " +
                            "host=${AfterDarkMediaProbe.safeHost(checked.url)} " +
                            "type=${checked.type.orEmpty()}",
                    )
                } else {
                    Log.w(
                        PROVIDER_TAG,
                        "Source directe rejetée par le probe service=${source.service} " +
                            "host=${AfterDarkMediaProbe.safeHost(source.url)}",
                    )
                }
                continue
            }

            val nativePriorityEmbed = isKnownNativeEmbed

            if (isEmbed) {
                val nativeResolved = runCatching {
                    AfterDarkNativeEmbedResolver.resolve(
                        source = source,
                        referer = sourceReferer,
                        userAgent = session.userAgent,
                    )
                }.onFailure {
                    Log.w(
                        PROVIDER_TAG,
                        "Résolution embed native impossible service=${source.service} host=$sourceHost",
                        it,
                    )
                }.getOrNull()

                if (nativeResolved != null) {
                    callback(
                        newExtractorLink(
                            source = source.service,
                            name = displayName(source),
                            url = nativeResolved.url,
                            type = extractorType(nativeResolved),
                        ) {
                            referer = nativeResolved.referer.orEmpty()
                            quality = getQualityFromName(source.quality)
                            headers = nativeResolved.headers
                        },
                    )
                    emitted = true
                    Log.i(
                        PROVIDER_TAG,
                        "Source native émise service=${source.service} " +
                            "host=${AfterDarkMediaProbe.safeHost(nativeResolved.url)} " +
                            "type=${nativeResolved.type.orEmpty()}",
                    )
                    if (source.group == "Secours") return true
                    continue
                }

                // Uqload's built-in CloudStream extractor can currently emit a
                // malformed pseudo-manifest. Vidzy also needs its current
                // decoder. For those two families, never fall back to the
                // generic built-in extractor after native validation failed.
                if (nativePriorityEmbed) {
                    Log.w(
                        PROVIDER_TAG,
                        "Embed ${source.service} rejeté après validation native host=$sourceHost",
                    )
                    continue
                }
            }

            val extractedLinks = ArrayList<ExtractorLink>()

            runCatching {
                loadExtractor(
                    url = source.url,
                    referer = sourceReferer,
                    subtitleCallback = subtitleCallback,
                    callback = { extractedLinks.add(it) },
                )
            }

            var extractorEmitted = false
            for (link in extractedLinks) {
                if (link.url.isBlank()) {
                    // Only the dedicated playlist link type is allowed to have
                    // no single URL. Reject any other blank extractor result.
                    if (link is ExtractorLinkPlayList) {
                        callback(link)
                        emitted = true
                        extractorEmitted = true
                    } else {
                        Log.w(PROVIDER_TAG, "Lien extracteur vide rejeté service=${source.service}")
                    }
                    continue
                }

                val checked = validateResolvedMedia(
                    ResolvedWebMedia(
                        url = link.url,
                        referer = link.referer,
                        headers = link.headers,
                        type = mediaHint(link.type),
                    ),
                )

                if (checked == null) {
                    Log.w(
                        PROVIDER_TAG,
                        "Lien extracteur rejeté service=${source.service} " +
                            "host=${AfterDarkMediaProbe.safeHost(link.url)} type=${link.type}",
                    )
                    continue
                }

                link.type = extractorType(checked)
                link.referer = checked.referer.orEmpty()
                link.headers = checked.headers
                callback(link)
                emitted = true
                extractorEmitted = true
            }

            if (extractorEmitted) {
                if (source.group == "Secours") return true
                continue
            }

            // Verification-only policy: never launch an embed/player WebView
            // as a fallback. The native resolver above gets first chance for
            // every embed (including unknown/new services such as "Thais").
            if (isEmbed) {
                Log.w(
                    PROVIDER_TAG,
                    "Embed non résolu nativement, WebView lecteur interdite: " +
                        "service=${source.service} host=$sourceHost",
                )
                continue
            }

            // Unknown non-embed URLs only reach the player after the same
            // final content probe. Never expose an arbitrary HTTP page as VIDEO.
            if (!isEmbed) {
                val checked = validateResolvedMedia(
                    ResolvedWebMedia(
                        url = source.url,
                        referer = sourceReferer,
                        headers = sourceHeaders,
                        type = null,
                    ),
                )
                if (checked != null) {
                    callback(
                        newExtractorLink(
                            source = source.service,
                            name = displayName(source),
                            url = checked.url,
                            type = extractorType(checked),
                        ) {
                            referer = checked.referer.orEmpty()
                            quality = getQualityFromName(source.quality)
                            headers = checked.headers
                        },
                    )
                    emitted = true
                } else {
                    Log.w(
                        PROVIDER_TAG,
                        "URL non-embed rejetée par le probe service=${source.service} " +
                            "host=${AfterDarkMediaProbe.safeHost(source.url)}",
                    )
                }
            }
        }

        return emitted
    }

    private companion object {
        const val PROVIDER_TAG = "AfterDarkProvider"
    }
}
