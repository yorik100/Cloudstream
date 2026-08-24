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
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

class FrembedProvider : MainAPI() {
    override var mainUrl = "https://frembed.casa"
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
            val seasons = details.optJSONArray("seasons") ?: JSONArray()

            val episodes = seasons.objects()
                .filter { it.optInt("episode_count", 0) > 0 }
                .sortedBy { it.optInt("season_number", 0) }
                .flatMap { season ->
                    val seasonNumber = season.optInt("season_number", 0)
                    val episodeCount = season.optInt("episode_count", 0)
                    val seasonPoster = poster(season.stringOrNull("poster_path"))

                    (1..episodeCount).asSequence().map { episodeNumber ->
                        val playback = FrembedPlaybackRequest(
                            tmdbId = tmdbId,
                            type = "tv",
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

    private fun apiUrl(request: FrembedPlaybackRequest): String =
        if (request.type == "movie") {
            "$mainUrl/api/film.php?id=${request.tmdbId}"
        } else {
            "$mainUrl/api/serie.php?id=${request.tmdbId}" +
                "&sa=${request.season}&epi=${request.episode}"
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
            .filter(::isInterestingCandidate)
            .distinct()
            .take(40)
    }

    private fun resolveLocation(
        requestUrl: String,
        location: String?,
    ): String? =
        location?.let { resolveUrl(requestUrl, it) }

    private fun qualityName(url: String): String? =
        Regex("""(?i)(2160|1440|1080|720|480|360)p?""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)

    private fun emitDirect(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
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

    private suspend fun tryCloudStreamExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean =
        runCatching {
            loadExtractor(
                url = url,
                referer = referer,
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
        }.getOrDefault(false)

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
        val request = FrembedPlaybackRequest.decode(data)
            ?: throw ErrorLoadingException("Données Frembed invalides")

        val visited = LinkedHashSet<String>()

        return probeUrl(
            url = apiUrl(request),
            referer = "$mainUrl/",
            depth = 0,
            visited = visited,
            subtitleCallback = subtitleCallback,
            callback = callback,
        )
    }
}
