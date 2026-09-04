package com.frembed.cloudstream

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
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class FrembedProvider : MainAPI() {
    override var mainUrl = FrembedDomainResolver.DISCOVERY_ORIGIN
    override var name = "Frembed"
    override var lang = "fr"

    override val hasMainPage = true
    override val usesWebView = false
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbImages = "https://image.tmdb.org/t/p"

    // Same public TMDB frontend key already used by the working AfterDark module.
    private val tmdbApiKey = "f3d757824f08ea2cff45eb8f47ca3a1e"

    private val browserHeaders = mapOf(
        "User-Agent" to (
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/149.0 Mobile Safari/537.36"
            ),
        "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language" to "fr-FR,fr;q=0.9,en;q=0.7",
    )

    // Exact request shape observed when Frembed successfully opens /api/stream
    // inside its player iframe. Do not add browser cookies here: they are
    // session-specific and must never be hard-coded in the extension.
    private val streamNavigationHeaders = mapOf(
        "User-Agent" to (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/151.0.0.0 Safari/537.36"
            ),
        "Accept" to (
            "text/html,application/xhtml+xml,application/xml;q=0.9," +
                "image/avif,image/webp,image/apng,*/*;q=0.8," +
                "application/signed-exchange;v=b3;q=0.7"
            ),
        "Accept-Language" to "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7,ja;q=0.6",
        "Sec-Fetch-Dest" to "iframe",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
    )

    private val domainResolver = FrembedDomainResolver(
        requestHeaders = browserHeaders,
        streamHeaders = streamNavigationHeaders,
    )

    private suspend fun ensureFrembedDomain(): String {
        val resolved = domainResolver.resolve()
        mainUrl = resolved
        return resolved
    }

    internal suspend fun prepareDomain() {
        ensureFrembedDomain()
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

    private fun catalogUrl(type: String, id: Int): String =
        "$mainUrl/cloudstream/$type/$id"

    private fun JSONObject.stringOrNull(key: String): String? =
        optString(key, "").trim().takeIf { it.isNotEmpty() }

    private fun JSONArray.objects(): Sequence<JSONObject> = sequence {
        for (index in 0 until length()) {
            optJSONObject(index)?.let { yield(it) }
        }
    }

    private fun todayUtc(): String =
        SimpleDateFormat(
            "yyyy-MM-dd",
            Locale.US,
        ).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    private fun isFutureEpisode(
        airDate: String?,
        today: String,
    ): Boolean =
        airDate != null && airDate > today

    private suspend fun tmdbGet(
        path: String,
        params: Map<String, String?> = emptyMap(),
    ): JSONObject {
        val response = app.get(tmdbUrl(path, params), cacheTime = 0)
        return runCatching { JSONObject(response.text) }
            .getOrElse { throw ErrorLoadingException("Réponse TMDB invalide") }
    }

    private fun JSONObject.toSearchResponse(
        forcedType: String? = null,
    ): SearchResponse? {
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
                url = catalogUrl(mediaType, id),
                type = TvType.Movie,
            ) {
                posterUrl = itemPoster
                year = itemYear
                score = itemScore
            }
        } else {
            newTvSeriesSearchResponse(
                name = itemName,
                url = catalogUrl(mediaType, id),
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
        ensureFrembedDomain()

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

    private suspend fun isAvailableOnFrembed(item: JSONObject): Boolean {
        val mediaType = item.stringOrNull("media_type") ?: return false
        val tmdbId = item.optInt("id", 0).takeIf { it > 0 } ?: return false

        return when (mediaType) {
            "movie" -> fetchMovieServers(tmdbId).isNotEmpty()

            "tv" -> {
                // Search results do not include a selected episode. Checking
                // S01E01 prevents Frembed from advertising unrelated TMDB
                // titles while keeping search reasonably lightweight.
                val request = FrembedPlaybackRequest(
                    tmdbId = tmdbId,
                    type = "tv",
                    season = 1,
                    episode = 1,
                )
                fetchSeriesServers(request).isNotEmpty()
            }

            else -> false
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        ensureFrembedDomain()

        val root = tmdbGet(
            "/search/multi",
            mapOf(
                "query" to query,
                "page" to "1",
            ),
        )
        val results = root.optJSONArray("results") ?: JSONArray()
        val output = ArrayList<SearchResponse>()

        for (item in results.objects()) {
            if (!runCatching { isAvailableOnFrembed(item) }.getOrDefault(false)) {
                continue
            }

            item.toSearchResponse()?.let { output += it }
        }

        return output
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        ensureFrembedDomain()

        val match = Regex("""/cloudstream/(movie|tv)/(\d+)""").find(url)
            ?: throw ErrorLoadingException("URL Frembed invalide")

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
            val playback = FrembedPlaybackRequest(
                tmdbId = tmdbId,
                type = "movie",
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

            val today = todayUtc()
            val episodes = ArrayList<com.lagradost.cloudstream3.Episode>()
            var hasAvailableEpisode = false

            for (season in seasons) {
                val seasonNumber = season.optInt("season_number", 0)
                val episodeCount = season.optInt("episode_count", 0)
                val seasonPoster = poster(season.stringOrNull("poster_path"))

                val seasonDetails = runCatching {
                    tmdbGet("/tv/$tmdbId/season/$seasonNumber")
                }.getOrNull()

                val metadataByEpisode =
                    (seasonDetails?.optJSONArray("episodes") ?: JSONArray())
                        .objects()
                        .associateBy { it.optInt("episode_number", 0) }

                for (episodeNumber in 1..episodeCount) {
                    val metadata = metadataByEpisode[episodeNumber]
                    val airDate = metadata?.stringOrNull("air_date")
                    val future = isFutureEpisode(airDate, today)

                    val playback = FrembedPlaybackRequest(
                        tmdbId = tmdbId,
                        type = "tv",
                        season = seasonNumber,
                        episode = episodeNumber,
                    )

                    // Frembed itself decides whether this exact episode exists.
                    // A future TMDB date never blocks an early Frembed release.
                    val availableOnFrembed = runCatching {
                        fetchSeriesServers(playback).isNotEmpty()
                    }.getOrDefault(false)

                    if (availableOnFrembed) {
                        hasAvailableEpisode = true
                    } else if (!future) {
                        // An already-aired episode with no Frembed server does
                        // not belong in the Frembed provider.
                        continue
                    }

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

                    episodes += newEpisode(
                        url = playback.encode(),
                        initializer = {
                            name = if (availableOnFrembed) {
                                episodeTitle
                            } else {
                                val dateLabel = airDate?.let { " · $it" }.orEmpty()
                                "⏳ À venir · $episodeTitle$dateLabel"
                            }
                            this.season = seasonNumber
                            episode = episodeNumber
                            posterUrl = episodePoster
                            description = if (availableOnFrembed) {
                                episodeOverview
                            } else {
                                listOfNotNull(
                                    airDate?.let { "Prévu le $it" },
                                    episodeOverview,
                                ).joinToString("\n\n").ifBlank {
                                    "Épisode à venir"
                                }
                            }
                        },
                        fix = false,
                    )
                }
            }

            // A direct/catalog URL must not expose a TMDB-only series as a
            // Frembed title. Future placeholders are shown only once Frembed
            // already has at least one real episode for the series.
            if (!hasAvailableEpisode) {
                throw ErrorLoadingException("Série indisponible sur Frembed")
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

    private fun moviePageUrl(tmdbId: Int): String =
        "$mainUrl/films?id=$tmdbId"

    private fun movieInfoUrl(tmdbId: Int): String =
        "$mainUrl/api/films?id=$tmdbId&idType=tmdb"

    private fun seriesApiUrl(request: FrembedPlaybackRequest): String? {
        val season = request.season ?: return null
        val episode = request.episode ?: return null

        return "$mainUrl/api/public/v1/tv/${request.tmdbId}" +
            "?sa=$season&epi=$episode"
    }

    private fun seriesInfoUrl(request: FrembedPlaybackRequest): String? {
        val season = request.season ?: return null
        val episode = request.episode ?: return null

        return "$mainUrl/api/series?id=${request.tmdbId}" +
            "&sa=$season&epi=$episode&idType=tmdb"
    }

    private fun seriesPageUrl(request: FrembedPlaybackRequest): String? {
        val season = request.season ?: return null
        val episode = request.episode ?: return null

        return "$mainUrl/series?id=${request.tmdbId}&sa=$season&epi=$episode"
    }

    private fun seriesEmbedUrl(request: FrembedPlaybackRequest): String? {
        val season = request.season ?: return null
        val episode = request.episode ?: return null

        return "$mainUrl/embed/serie/${request.tmdbId}?sa=$season&epi=$episode"
    }

    private fun streamUrl(rawUrl: String): String? =
        resolveUrl("$mainUrl/", rawUrl)

    private data class FrembedServer(
        val label: String,
        val slug: String,
        val lang: String,
        val url: String,
    )

    private suspend fun fetchMovieServers(tmdbId: Int): List<FrembedServer> {
        val response = runCatching {
            app.get(
                url = movieInfoUrl(tmdbId),
                headers = browserHeaders,
                referer = moviePageUrl(tmdbId),
                cacheTime = 0,
            )
        }.getOrNull() ?: return emptyList()

        if (response.okhttpResponse.code !in 200..299) return emptyList()

        val root = runCatching { JSONObject(response.text) }.getOrNull()
            ?: return emptyList()

        val result = LinkedHashMap<String, FrembedServer>()

        // Current Frembed API: links[] is the authoritative server list.
        val links = root.optJSONArray("links") ?: JSONArray()
        for (index in 0 until links.length()) {
            val item = links.optJSONObject(index) ?: continue
            val rawUrl = item.stringOrNull("url") ?: continue
            val absoluteUrl = streamUrl(rawUrl) ?: continue

            val host = item.optJSONObject("host")
            val label = item.stringOrNull("label")
                ?: host?.stringOrNull("name")
                ?: host?.stringOrNull("slug")
                ?: "Serveur ${index + 1}"

            val lang = item.stringOrNull("lang")
                ?.uppercase()
                ?: "VF"

            val slug = host?.stringOrNull("slug")
                ?: label.lowercase()

            result[absoluteUrl] = FrembedServer(
                label = label,
                slug = slug,
                lang = lang,
                url = absoluteUrl,
            )
        }

        // Compatibility fallback for API responses that only expose link1/link2...
        if (result.isEmpty()) {
            val suffixes = listOf(
                "" to "VF",
                "vostfr" to "VOSTFR",
                "vo" to "VO",
            )

            for ((suffix, lang) in suffixes) {
                for (serverIndex in 1..7) {
                    val key = "link$serverIndex$suffix"
                    val rawUrl = root.stringOrNull(key) ?: continue
                    val absoluteUrl = streamUrl(rawUrl) ?: continue

                    result[absoluteUrl] = FrembedServer(
                        label = "Serveur $serverIndex",
                        slug = "",
                        lang = lang,
                        url = absoluteUrl,
                    )
                }
            }
        }

        return result.values.toList()
    }

    private suspend fun fetchSeriesServers(
        request: FrembedPlaybackRequest,
    ): List<FrembedServer> {
        val infoUrl = seriesInfoUrl(request) ?: return emptyList()
        val pageUrl = seriesPageUrl(request) ?: return emptyList()

        val response = runCatching {
            app.get(
                url = infoUrl,
                headers = browserHeaders,
                referer = pageUrl,
                cacheTime = 0,
            )
        }.getOrNull() ?: return emptyList()

        if (response.okhttpResponse.code !in 200..299) return emptyList()

        val root = runCatching { JSONObject(response.text) }.getOrNull()
            ?: return emptyList()

        val result = LinkedHashMap<String, FrembedServer>()

        // Current Frembed series API: links[] is the authoritative list.
        val links = root.optJSONArray("links") ?: JSONArray()
        for (index in 0 until links.length()) {
            val item = links.optJSONObject(index) ?: continue
            val rawUrl = item.stringOrNull("url") ?: continue
            val absoluteUrl = streamUrl(rawUrl) ?: continue

            val host = item.optJSONObject("host")
            val label = item.stringOrNull("label")
                ?: host?.stringOrNull("name")
                ?: host?.stringOrNull("slug")
                ?: "Serveur ${index + 1}"

            val slug = host?.stringOrNull("slug")
                ?: label.lowercase()

            val lang = item.stringOrNull("lang")
                ?.uppercase()
                ?: "VF"

            result[absoluteUrl] = FrembedServer(
                label = label,
                slug = slug,
                lang = lang,
                url = absoluteUrl,
            )
        }

        // Compatibility fallback for older API responses.
        if (result.isEmpty()) {
            val suffixes = listOf(
                "" to "VF",
                "vostfr" to "VOSTFR",
                "vo" to "VO",
            )

            for ((suffix, lang) in suffixes) {
                for (serverIndex in 1..7) {
                    val key = "link$serverIndex$suffix"
                    val rawUrl = root.stringOrNull(key) ?: continue
                    val absoluteUrl = streamUrl(rawUrl) ?: continue

                    result[absoluteUrl] = FrembedServer(
                        label = "Serveur $serverIndex",
                        slug = "",
                        lang = lang,
                        url = absoluteUrl,
                    )
                }
            }

            root.stringOrNull("link")?.let { rawUrl ->
                streamUrl(rawUrl)?.let { absoluteUrl ->
                    result.putIfAbsent(
                        absoluteUrl,
                        FrembedServer(
                            label = "Serveur",
                            slug = "",
                            lang = root.stringOrNull("version") ?: "VF",
                            url = absoluteUrl,
                        ),
                    )
                }
            }
        }

        return result.values.toList()
    }

    private fun normalizeEscapes(value: String): String =
        value
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003d", "=", ignoreCase = true)
            .replace("\\u002f", "/", ignoreCase = true)
            .replace("\\x26", "&", ignoreCase = true)
            .replace("\\x3d", "=", ignoreCase = true)
            .replace("&amp;", "&")
            .replace("&#38;", "&")
            .replace("&#x26;", "&", ignoreCase = true)
            .trim()

    private fun resolveUrl(baseUrl: String, rawValue: String): String? {
        val value = normalizeEscapes(rawValue)
            .trim('"', '\'', ' ', '\t', '\r', '\n')
        if (value.isBlank()) return null

        val lower = value.lowercase()
        if (
            lower.startsWith("javascript:") ||
            lower.startsWith("data:") ||
            lower.startsWith("mailto:") ||
            lower.startsWith("#")
        ) return null

        return runCatching {
            val absolute = when {
                value.startsWith("//") -> "https:$value"
                value.startsWith("http://") || value.startsWith("https://") -> value
                else -> URI(baseUrl).resolve(value).toString()
            }

            val uri = URI(absolute)
            if (uri.scheme != "http" && uri.scheme != "https") null else absolute
        }.getOrNull()
    }

    private fun isIgnoredAsset(url: String): Boolean {
        val path = runCatching { URI(url).path ?: "" }.getOrDefault("")
            .substringBefore("?")
            .lowercase()

        return listOf(
            ".css", ".js", ".mjs", ".png", ".jpg", ".jpeg", ".webp",
            ".gif", ".svg", ".ico", ".woff", ".woff2", ".ttf", ".map",
        ).any { path.endsWith(it) }
    }

    private fun directType(url: String): ExtractorLinkType? {
        val clean = url.substringBefore("?").substringBefore("#").lowercase()
        return when {
            clean.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            clean.endsWith(".mpd") -> ExtractorLinkType.DASH
            clean.endsWith(".mp4") ||
                clean.endsWith(".mkv") ||
                clean.endsWith(".webm") -> ExtractorLinkType.VIDEO
            else -> null
        }
    }

    private fun isSubtitle(url: String): Boolean {
        val clean = url.substringBefore("?").substringBefore("#").lowercase()
        return clean.endsWith(".vtt") ||
            clean.endsWith(".srt") ||
            clean.endsWith(".ass") ||
            clean.endsWith(".ssa")
    }

    private fun isFrembedTestVideoUrl(url: String): Boolean {
        val value = url.lowercase()
        return "frembed test-video" in value ||
            "frembed-test-video" in value ||
            "test-video" in value ||
            "test_video" in value
    }

    private fun hostName(url: String): String =
        runCatching {
            URI(url).host
                ?.removePrefix("www.")
                ?.substringBefore(".")
                ?.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase() else it.toString()
                }
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "Frembed"

    private fun isInterestingCandidate(url: String): Boolean {
        if (directType(url) != null || isSubtitle(url)) return true

        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host ?: return false
        val frembedHost = runCatching { URI(mainUrl).host }.getOrNull()

        // Third-party hosts are useful because they are normally video servers.
        if (frembedHost != null && !host.equals(frembedHost, ignoreCase = true)) {
            return true
        }

        // On Frembed itself, only follow player/API-style routes. Never crawl
        // movie pages, navigation links, images, account pages, etc.
        val path = (uri.path ?: "").lowercase()
        return path.contains("/embed/") ||
            path.contains("/api/") ||
            path.contains("/player") ||
            path.contains("/stream") ||
            path.contains("/video")
    }

    private fun extractCandidateUrls(
        baseUrl: String,
        body: String,
    ): List<String> {
        val normalizedBody = normalizeEscapes(body)
        val rawValues = LinkedHashSet<String>()

        val patterns = listOf(
            Regex(
                """(?is)(?:src|data-src|data-url|data-link|data-player|data-embed)\s*=\s*["']([^"'<>]+)["']"""
            ),
            Regex(
                """(?is)["'](?:file|url|src|link|embed|player|stream|playlist)["']\s*:\s*["']([^"'<>]+)["']"""
            ),
            Regex(
                """(?is)(?:file|url|src|link|embed|player|stream|playlist)\s*[:=]\s*["']([^"'<>]+)["']"""
            ),
            Regex(
                """(?is)(?:fetch|axios\.get)\(\s*["']([^"'<>]+)["']"""
            ),
        )

        patterns.forEach { regex ->
            regex.findAll(normalizedBody).forEach { match ->
                match.groupValues.getOrNull(1)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { rawValues += it }
            }
        }

        Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
            .findAll(normalizedBody)
            .forEach { rawValues += it.value }

        return rawValues
            .mapNotNull { resolveUrl(baseUrl, it) }
            .filterNot(::isIgnoredAsset)
            .filterNot(::isFrembedTestVideoUrl)
            .filter(::isInterestingCandidate)
            .distinct()
            .take(40)
    }

    private fun extractSeriesStreamUrls(
        baseUrl: String,
        body: String,
        request: FrembedPlaybackRequest,
    ): List<String> {
        val normalized = normalizeEscapes(body)
        val expectedTmdb = request.tmdbId.toString()
        val expectedSeason = request.season?.toString() ?: return emptyList()
        val expectedEpisode = request.episode?.toString() ?: return emptyList()

        val result = LinkedHashSet<String>()

        // Current Frembed series player route observed in the browser:
        // /api/stream?type=serie&tmdb=1396&sa=1&epi=1&server=id:898
        Regex(
            """(?i)(?:https?://[^"'<>\\\s]+)?/api/stream\?[^"'<>\\\s]+"""
        ).findAll(normalized).forEach { match ->
            val absolute = resolveUrl(baseUrl, match.value) ?: return@forEach
            val uri = runCatching { URI(absolute) }.getOrNull() ?: return@forEach
            val query = uri.rawQuery ?: return@forEach

            val params = query.split("&")
                .mapNotNull { part ->
                    val pieces = part.split("=", limit = 2)
                    if (pieces.size != 2) null
                    else pieces[0].lowercase() to pieces[1]
                }
                .toMap()

            if (
                params["type"]?.equals("serie", ignoreCase = true) == true &&
                params["tmdb"] == expectedTmdb &&
                params["sa"] == expectedSeason &&
                params["epi"] == expectedEpisode &&
                params["server"]?.startsWith("id:") == true
            ) {
                result += absolute
            }
        }

        return result.toList()
    }

    private suspend fun fetchSeriesStreamUrls(
        request: FrembedPlaybackRequest,
    ): List<String> {
        val pageUrl = seriesPageUrl(request) ?: return emptyList()
        val embedUrl = seriesEmbedUrl(request) ?: return emptyList()

        // Prime the same page that Chrome uses as Referer for /api/stream.
        val pageResponse = runCatching {
            app.get(
                url = pageUrl,
                headers = browserHeaders,
                referer = "$mainUrl/",
                cacheTime = 0,
            )
        }.getOrNull()

        val result = LinkedHashSet<String>()

        if (pageResponse != null && pageResponse.okhttpResponse.code in 200..299) {
            result += extractSeriesStreamUrls(pageUrl, pageResponse.text, request)
        }

        // Some Frembed builds serialize the server ids in the embed route
        // rather than the /series page, so inspect both official player pages.
        val embedResponse = runCatching {
            app.get(
                url = embedUrl,
                headers = browserHeaders,
                referer = pageUrl,
                cacheTime = 0,
            )
        }.getOrNull()

        if (embedResponse != null && embedResponse.okhttpResponse.code in 200..299) {
            result += extractSeriesStreamUrls(embedUrl, embedResponse.text, request)
        }

        return result.toList()
    }

    private suspend fun resolveSeriesServerRedirect(
        serverUrl: String,
        request: FrembedPlaybackRequest,
    ): String? {
        val pageUrl = seriesPageUrl(request) ?: return null

        // Exact successful browser context supplied from DevTools:
        // Referer: /series?id=<tmdb>&sa=<season>&epi=<episode>
        // Sec-Fetch-Dest: iframe
        // Sec-Fetch-Mode: navigate
        // Sec-Fetch-Site: same-origin
        val response = runCatching {
            app.get(
                url = serverUrl,
                headers = streamNavigationHeaders,
                referer = pageUrl,
                allowRedirects = false,
                cacheTime = 0,
            )
        }.getOrNull() ?: return null

        if (response.okhttpResponse.code !in 300..399) return null

        return resolveLocation(
            requestUrl = serverUrl,
            location = response.headers["Location"],
        )
    }

    private suspend fun emitSeriesServer(
        server: FrembedServer,
        request: FrembedPlaybackRequest,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val target = resolveSeriesServerRedirect(server.url, request)
            ?: return false

        if (isFrembedTestVideoUrl(target)) return false

        if (isSubtitle(target)) {
            subtitleCallback(newSubtitleFile("Frembed", target))
            return true
        }

        if (emitDirect(target, "", callback)) return true

        // The series API already tells us the authoritative host.slug.
        if (
            tryNamedHostExtractors(
                server = server,
                url = target,
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
        ) {
            return true
        }

        if (tryKnownHostExtractorByUrl(target, subtitleCallback, callback)) {
            return true
        }

        if (tryCloudStreamExtractor(target, null, subtitleCallback, callback)) {
            return true
        }

        // Unknown hosts (the captured example also showed fanstream.buzz)
        // can still expose an iframe/direct media URL, so probe only that
        // resolved third-party target as a last fallback.
        return probeUrl(
            url = target,
            referer = "$mainUrl/",
            depth = 0,
            visited = LinkedHashSet(),
            subtitleCallback = subtitleCallback,
            callback = callback,
        )
    }

    private fun resolveLocation(
        requestUrl: String,
        location: String?,
    ): String? =
        location?.let { resolveUrl(requestUrl, it) }

    private suspend fun resolveMovieServerRedirect(
        serverUrl: String,
        tmdbId: Int,
    ): String? {
        val filmPage = moviePageUrl(tmdbId)

        // Prime the Frembed session first. In a browser this page is loaded
        // before the iframe navigation and may establish harmless session
        // cookies used by Next/Frembed. The shared CloudStream HTTP client can
        // retain any cookies set by the site; none are hard-coded.
        runCatching {
            app.get(
                url = filmPage,
                headers = browserHeaders,
                referer = "$mainUrl/",
                cacheTime = 0,
            )
        }

        val response = runCatching {
            app.get(
                url = serverUrl,
                headers = streamNavigationHeaders,
                referer = filmPage,
                allowRedirects = false,
                cacheTime = 0,
            )
        }.getOrNull() ?: return null

        if (response.okhttpResponse.code !in 300..399) return null

        return resolveLocation(
            requestUrl = serverUrl,
            location = response.headers["Location"],
        )
    }

    private suspend fun emitMovieServer(
        server: FrembedServer,
        tmdbId: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // Current Frembed movie flow:
        // film page -> iframe /api/stream?... -> HTTP 302 -> host embed.
        val filmPage = moviePageUrl(tmdbId)
        val target = resolveMovieServerRedirect(server.url, tmdbId)
            ?: return false

        if (isSubtitle(target)) {
            subtitleCallback(newSubtitleFile("${server.lang} · ${server.label}", target))
            return true
        }

        // Frembed answers /api/stream with Referrer-Policy: same-origin.
        // The 302 target is cross-origin (Uqload/Voe/Dood/etc.), so the browser
        // does NOT forward the Frembed film page as Referer to that host.
        if (emitDirect(target, "", callback)) return true

        // Frembed gives us the host slug in links[] (uqload/voe/dood/...).
        // Dispatch by that host name first, bypassing CloudStream's domain-based
        // selector which can miss current mirror domains.
        if (
            tryNamedHostExtractors(
                server = server,
                url = target,
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
        ) return true

        // Unknown/new hosts still get CloudStream's normal domain-based fallback.
        return tryCloudStreamExtractor(
            url = target,
            referer = null,
            subtitleCallback = subtitleCallback,
            callback = callback,
        )
    }

    private fun qualityName(url: String): String? =
        Regex("""(?i)(2160|1440|1080|720|480|360)p?""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)

    private suspend fun emitDirect(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (isFrembedTestVideoUrl(url)) return false
        val type = directType(url) ?: return false

        callback(
            newExtractorLink(
                source = "Frembed",
                name = "Frembed · ${hostName(url)}",
                url = url,
                type = type,
            ) {
                this.referer = referer
                quality = getQualityFromName(qualityName(url))
                headers = browserHeaders
            },
        )
        return true
    }

    private fun isFrembedTestVideo(link: ExtractorLink): Boolean {
        val value = "${link.source} ${link.name} ${link.url}".lowercase()
        return "frembed test-video" in value ||
            "frembed test video" in value ||
            "test-video" in value
    }

    private fun normalizeExtractorName(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    private fun knownHostSlug(url: String): String? {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
            ?: return null

        return when {
            "uqload" in host -> "uqload"
            host == "voe.sx" || host.startsWith("voe.") || ".voe." in host -> "voe"
            "dood" in host -> "dood"
            "streamtape" in host -> "streamtape"
            "vidmoly" in host -> "vidmoly"
            "filemoon" in host -> "filemoon"
            else -> null
        }
    }

    private suspend fun tryKnownHostExtractorByUrl(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val slug = knownHostSlug(url) ?: return false

        return tryNamedHostExtractors(
            server = FrembedServer(
                label = slug,
                slug = slug,
                lang = "",
                url = url,
            ),
            url = url,
            subtitleCallback = subtitleCallback,
            callback = callback,
        )
    }

    private suspend fun tryNamedHostExtractors(
        server: FrembedServer,
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val hostKey = normalizeExtractorName(server.slug)
            .ifBlank { normalizeExtractorName(server.label) }

        if (hostKey.isBlank()) return false

        // CloudStream's normal loadExtractor() first matches by extractor.mainUrl.
        // Frembed already tells us the actual host (uqload/voe/dood/...), so call
        // matching extractors directly and bypass stale/missing domain aliases.
        for (index in extractorApis.lastIndex downTo 0) {
            val extractor = extractorApis[index]
            val extractorKey = normalizeExtractorName(extractor.name)

            val matchesHost =
                extractorKey == hostKey ||
                    extractorKey.contains(hostKey) ||
                    hostKey.contains(extractorKey)

            if (!matchesHost) continue

            var emitted = false

            runCatching {
                extractor.getUrl(
                    url = url,
                    referer = null,
                    subtitleCallback = subtitleCallback,
                    callback = { link ->
                        if (!isFrembedTestVideo(link)) {
                            emitted = true
                            callback(link)
                        }
                    },
                )
            }

            if (emitted) return true
        }

        return false
    }

    private suspend fun tryCloudStreamExtractor(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var emitted = false

        runCatching {
            loadExtractor(
                url = url,
                referer = referer,
                subtitleCallback = subtitleCallback,
                callback = { link ->
                    // Some player pages expose a short placeholder/test asset.
                    // Never forward that asset to CloudStream as a real source.
                    if (!isFrembedTestVideo(link)) {
                        emitted = true
                        callback(link)
                    }
                },
            )
        }

        return emitted
    }

    private suspend fun probeUrl(
        url: String,
        referer: String,
        depth: Int,
        visited: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (depth > 3) return false
        if (!visited.add(url)) return false

        if (isSubtitle(url)) {
            subtitleCallback(newSubtitleFile("Frembed", url))
            return true
        }

        if (emitDirect(url, referer, callback)) return true

        val host = runCatching { URI(url).host }.getOrNull()
        val frembedHost = runCatching { URI(mainUrl).host }.getOrNull()

        if (
            host != null &&
            frembedHost != null &&
            !host.equals(frembedHost, ignoreCase = true)
        ) {
            if (tryKnownHostExtractorByUrl(url, subtitleCallback, callback)) {
                return true
            }

            if (tryCloudStreamExtractor(url, referer, subtitleCallback, callback)) {
                return true
            }
        }

        val response = runCatching {
            app.get(
                url = url,
                headers = browserHeaders,
                referer = referer,
                allowRedirects = false,
                cacheTime = 0,
            )
        }.getOrNull() ?: return false

        val code = response.okhttpResponse.code

        if (code in 300..399) {
            val redirected = resolveLocation(url, response.headers["Location"])
                ?: return false

            if (emitDirect(redirected, url, callback)) return true

            if (
                tryKnownHostExtractorByUrl(
                    redirected,
                    subtitleCallback,
                    callback,
                )
            ) return true

            if (
                tryCloudStreamExtractor(
                    redirected,
                    url,
                    subtitleCallback,
                    callback,
                )
            ) return true

            return probeUrl(
                url = redirected,
                referer = url,
                depth = depth + 1,
                visited = visited,
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
        }

        if (code !in 200..299) return false

        val contentType = response.headers["Content-Type"]
            ?.substringBefore(";")
            ?.trim()
            ?.lowercase()

        val responseType = when (contentType) {
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "audio/mpegurl",
            "audio/x-mpegurl" -> ExtractorLinkType.M3U8

            "application/dash+xml" -> ExtractorLinkType.DASH

            "video/mp4",
            "video/webm",
            "video/x-matroska" -> ExtractorLinkType.VIDEO

            else -> null
        }

        if (responseType != null) {
            callback(
                newExtractorLink(
                    source = "Frembed",
                    name = "Frembed · ${hostName(url)}",
                    url = url,
                    type = responseType,
                ) {
                    this.referer = referer
                    quality = getQualityFromName(qualityName(url))
                    headers = browserHeaders
                },
            )
            return true
        }

        val body = runCatching { response.text }.getOrDefault("")
        if (body.isBlank()) return false

        val candidates = extractCandidateUrls(url, body)
        if (candidates.isEmpty()) return false

        var emitted = false

        for (candidate in candidates) {
            if (candidate == url) continue

            if (isSubtitle(candidate)) {
                subtitleCallback(newSubtitleFile("Frembed", candidate))
                emitted = true
                continue
            }

            if (emitDirect(candidate, url, callback)) {
                emitted = true
                continue
            }

            if (
                tryKnownHostExtractorByUrl(
                    candidate,
                    subtitleCallback,
                    callback,
                )
            ) {
                emitted = true
                continue
            }

            if (
                tryCloudStreamExtractor(
                    candidate,
                    url,
                    subtitleCallback,
                    callback,
                )
            ) {
                emitted = true
                continue
            }

            if (depth < 3) {
                val nested = probeUrl(
                    url = candidate,
                    referer = url,
                    depth = depth + 1,
                    visited = visited,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )
                emitted = nested || emitted
            }
        }

        return emitted
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        ensureFrembedDomain()

        val request = FrembedPlaybackRequest.decode(data)
            ?: throw ErrorLoadingException("Données Frembed invalides")

        if (request.type == "movie") {
            val servers = fetchMovieServers(request.tmdbId)
            if (servers.isEmpty()) return false

            var found = false

            for (server in servers) {
                val emitted = emitMovieServer(
                    server = server,
                    tmdbId = request.tmdbId,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )

                found = emitted || found
            }

            return found
        }

        val servers = fetchSeriesServers(request)
        if (servers.isEmpty()) return false

        var found = false

        for (server in servers) {
            val emitted = emitSeriesServer(
                server = server,
                request = request,
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
            found = emitted || found
        }

        return found
    }
}
