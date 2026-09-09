package com.flemmix.cloudstream

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

class FlemmixProvider : MainAPI() {
    override var mainUrl = FlemmixDomainResolver.REGISTRY_ORIGIN
    override var name = "Flemmix/Wiflix"
    override var lang = "fr"

    override val hasMainPage = true
    override val usesWebView = false
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "movie" to "Derniers films Flemmix/Wiflix",
        "tv" to "Dernières séries Flemmix/Wiflix",
    )

    private val browserHeaders = mapOf(
        "User-Agent" to (
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/149.0 Mobile Safari/537.36"
            ),
        "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language" to "fr-FR,fr;q=0.9,en;q=0.7",
    )

    private val domainResolver = FlemmixDomainResolver(browserHeaders)
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbImages = "https://image.tmdb.org/t/p"
    private val tmdbApiKey = "f3d757824f08ea2cff45eb8f47ca3a1e"

    private data class CataloguePage(
        val items: List<SearchResponse>,
        val hasNext: Boolean,
    )

    private data class ParsedItem(
        val response: SearchResponse,
        val originalTitle: String?,
    )

    private data class SourceDetails(
        val type: String,
        val path: String,
        val title: String,
        val originalTitle: String?,
        val year: Int?,
        val posterUrl: String?,
        val plot: String?,
        val tags: List<String>,
        val durationMinutes: Int?,
        val season: Int?,
    )

    private suspend fun ensureDomain(): String {
        val resolved = domainResolver.resolve()
        mainUrl = resolved
        return resolved
    }

    internal suspend fun prepareDomain() {
        ensureDomain()
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val type = if (request.data == "tv") "tv" else "movie"
        val catalogue = loadCatalogue(type, page)
        return newHomePageResponse(request, catalogue.items, catalogue.hasNext)
    }

    private suspend fun loadCatalogue(type: String, page: Int): CataloguePage {
        val firstOrigin = ensureDomain()
        fetchCatalogue(firstOrigin, type, page)?.let { return it }

        domainResolver.invalidate(firstOrigin)
        val refreshedOrigin = ensureDomain()
        return fetchCatalogue(refreshedOrigin, type, page)
            ?: throw ErrorLoadingException("Catalogue Flemmix/Wiflix inaccessible")
    }

    private suspend fun fetchCatalogue(
        origin: String,
        type: String,
        page: Int,
    ): CataloguePage? {
        val route = if (type == "tv") "/serie-en-streaming/" else "/film-en-streaming/"
        val pageUrl = origin + route + if (page > 1) "page/$page/" else ""
        val response = runCatching {
            app.get(
                url = pageUrl,
                headers = browserHeaders,
                referer = "$origin/",
                cacheTime = CATALOGUE_CACHE_SECONDS,
                timeout = PAGE_TIMEOUT_SECONDS,
            )
        }.getOrNull() ?: return null

        if (response.okhttpResponse.code !in 200..299) return null
        val items = parseItems(response.text, origin, type).map { it.response }
        if (items.isEmpty()) return null

        val totalPages = PAGE_LINK_REGEX.findAll(response.text)
            .mapNotNull { it.groupValues[2].toIntOrNull() }
            .maxOrNull()

        return CataloguePage(
            items = items,
            hasNext = totalPages?.let { page < it } ?: (items.size >= 10),
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val firstOrigin = ensureDomain()
        searchAtOrigin(firstOrigin, query)?.let { return it }

        domainResolver.invalidate(firstOrigin)
        val refreshedOrigin = ensureDomain()
        return searchAtOrigin(refreshedOrigin, query).orEmpty()
    }

    private suspend fun searchAtOrigin(
        origin: String,
        query: String,
    ): List<SearchResponse>? {
        val response = runCatching {
            app.post(
                url = "$origin/",
                data = mapOf(
                    "do" to "search",
                    "subaction" to "search",
                    "story" to query,
                ),
                headers = browserHeaders,
                referer = "$origin/",
                cacheTime = 0,
                timeout = PAGE_TIMEOUT_SECONDS,
            )
        }.getOrNull() ?: return null

        if (response.okhttpResponse.code !in 200..299) return null

        // Flemmix injecte les cartes vedettes de l'accueil avant les vrais
        // résultats. Le formulaire fullsearch marque le début de la section
        // de recherche renvoyée par DataLife Engine.
        val searchStart = SEARCH_RESULTS_START_REGEX.find(response.text)
            ?.range
            ?.first
            ?: return null
        val searchHtml = response.text.substring(searchStart)
        val normalizedQuery = normalizeForMatch(query)

        return parseItems(searchHtml, origin, null)
            .filter { item ->
                sequenceOf(item.response.name, item.originalTitle)
                    .filterNotNull()
                    .map(::normalizeForMatch)
                    .any { title ->
                        title.contains(normalizedQuery) ||
                            normalizedQuery.contains(title)
                    }
            }
            .map { it.response }
            .distinctBy { it.url }
            .take(MAX_SEARCH_RESULTS)
    }

    private fun parseItems(
        html: String,
        origin: String,
        forcedType: String?,
    ): List<ParsedItem> {
        val results = LinkedHashMap<String, ParsedItem>()

        for (anchor in ANCHOR_REGEX.findAll(html)) {
            val openingTag = anchor.value.substringBefore('>')
            if (!MOV_TITLE_CLASS_REGEX.containsMatchIn(openingTag)) continue

            val rawHref = decodeHtml(anchor.groupValues[1]).trim()
            val absolute = resolveUrl("$origin/", rawHref) ?: continue
            val uri = runCatching { URI(absolute) }.getOrNull() ?: continue
            val path = uri.path ?: continue
            val pathMatch = DETAIL_PATH_REGEX.matchEntire(path) ?: continue
            val type = if (pathMatch.groupValues[1].equals("film", true)) "movie" else "tv"
            if (forcedType != null && forcedType != type) continue

            val body = anchor.groupValues[2]
            val contextStart = max(0, anchor.range.first - ITEM_CONTEXT_SIZE)
            val context = html.substring(contextStart, anchor.range.last + 1)
            val title = plainText(body).takeIf(String::isNotBlank)
                ?: continue
            val originalTitle = TITLE0_REGEX.find(body)?.groupValues?.getOrNull(1)
                ?.let(::plainText)
                ?.takeIf(String::isNotBlank)

            val imageTag = IMAGE_REGEX.findAll(body).lastOrNull()?.value
                ?: IMAGE_REGEX.findAll(context).lastOrNull()?.value
            val posterUrl = imageTag
                ?.let { image ->
                    sequenceOf("src", "data-src", "data-lazy-src")
                        .mapNotNull { attribute(image, it) }
                        .firstOrNull(String::isNotBlank)
                }
                ?.let { resolveUrl("$origin/", decodeHtml(it)) }

            val itemUrl = "$origin$path"
            val response = if (type == "movie") {
                newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            } else {
                newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            }

            results.putIfAbsent(
                "$type:$path",
                ParsedItem(response, originalTitle),
            )
        }

        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val requestedUri = runCatching { URI(url) }.getOrNull()
            ?: throw ErrorLoadingException("URL Flemmix/Wiflix invalide")
        val path = requestedUri.path
            ?.takeIf { DETAIL_PATH_REGEX.matches(it) }
            ?: throw ErrorLoadingException("Chemin Flemmix/Wiflix invalide")

        val page = fetchDetailWithRefresh(path)
            ?: throw ErrorLoadingException("Fiche Flemmix/Wiflix inaccessible")
        val details = parseDetails(page.html, page.origin, path)
            ?: throw ErrorLoadingException("Fiche Flemmix/Wiflix invalide")
        val tmdb = findTmdbMatch(details)

        return if (details.type == "movie") {
            buildMovieResponse(url, page.html, page.origin, details, tmdb)
        } else {
            buildSeriesResponse(url, page.html, page.origin, details, tmdb)
        }
    }

    private data class DetailPage(
        val html: String,
        val origin: String,
    )

    private suspend fun fetchDetailWithRefresh(path: String): DetailPage? {
        val firstOrigin = ensureDomain()
        fetchDetail(firstOrigin, path)?.let { return DetailPage(it, firstOrigin) }

        domainResolver.invalidate(firstOrigin)
        val refreshedOrigin = ensureDomain()
        return fetchDetail(refreshedOrigin, path)?.let { DetailPage(it, refreshedOrigin) }
    }

    private suspend fun fetchDetail(origin: String, path: String): String? {
        val response = runCatching {
            app.get(
                url = "$origin$path",
                headers = browserHeaders,
                referer = "$origin/",
                cacheTime = DETAIL_CACHE_SECONDS,
                timeout = PAGE_TIMEOUT_SECONDS,
            )
        }.getOrNull() ?: return null

        if (response.okhttpResponse.code !in 200..299) return null
        if (!DETAIL_TITLE_REGEX.containsMatchIn(response.text)) return null
        return response.text
    }

    private fun parseDetails(
        html: String,
        origin: String,
        path: String,
    ): SourceDetails? {
        val match = DETAIL_PATH_REGEX.matchEntire(path) ?: return null
        val type = if (match.groupValues[1].equals("film", true)) "movie" else "tv"
        val title = DETAIL_TITLE_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?.let(::plainText)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val sourceText = plainText(html)
        val originalTitle = labeledValue(html, "titre original")
        val year = sequenceOf("date de sortie", "année")
            .mapNotNull { labeledValue(html, it) }
            .mapNotNull { YEAR_REGEX.find(it) }
            .mapNotNull { it.value.toIntOrNull() }
            .firstOrNull()
            ?: YEAR_REGEX.find(sourceText)?.value?.toIntOrNull()
        val rawPoster = POSTER_REGEX.find(html)?.groupValues?.getOrNull(1)
        val posterUrl = rawPoster?.let { resolveUrl("$origin/", decodeHtml(it)) }
        val plot = DESCRIPTION_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?.let(::plainText)
            ?.takeIf(String::isNotBlank)
        val tags = GENRE_REGEX.findAll(html)
            .map { plainText(it.groupValues[1]) }
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        val duration = labeledValue(html, "durée")
            ?.let { DURATION_REGEX.find(it)?.groupValues?.getOrNull(1) }
            ?.toIntOrNull()
        val season = SEASON_IN_TITLE_REGEX.find(title)?.groupValues?.getOrNull(1)
            ?.toIntOrNull()

        return SourceDetails(
            type = type,
            path = path,
            title = title,
            originalTitle = originalTitle,
            year = year,
            posterUrl = posterUrl,
            plot = plot,
            tags = tags,
            durationMinutes = duration,
            season = season,
        )
    }

    private suspend fun buildMovieResponse(
        requestedUrl: String,
        html: String,
        origin: String,
        source: SourceDetails,
        tmdb: JSONObject?,
    ): LoadResponse {
        val servers = parseMovieServers(html, origin)
        if (servers.isEmpty()) {
            throw ErrorLoadingException("Film pas disponible sur Flemmix/Wiflix")
        }

        val title = tmdb?.stringOrNull("title") ?: source.title
        val releaseYear = year(tmdb?.stringOrNull("release_date")) ?: source.year
        val poster = tmdbPoster(tmdb?.stringOrNull("poster_path")) ?: source.posterUrl
        val playback = FlemmixPlaybackRequest(type = "movie", path = source.path)

        return newMovieLoadResponse(title, requestedUrl, TvType.Movie, playback.encode()) {
            posterUrl = poster
            backgroundPosterUrl = tmdbPoster(tmdb?.stringOrNull("backdrop_path"), "w1280")
            year = releaseYear
            plot = tmdb?.stringOrNull("overview")?.takeIf(String::isNotBlank) ?: source.plot
            tags = tmdbGenres(tmdb).ifEmpty { source.tags }
            duration = tmdb?.optInt("runtime", 0)?.takeIf { it > 0 } ?: source.durationMinutes
            score = tmdbScore(tmdb)
        }
    }

    private suspend fun buildSeriesResponse(
        requestedUrl: String,
        html: String,
        origin: String,
        source: SourceDetails,
        tmdb: JSONObject?,
    ): LoadResponse {
        val availableServers = parseSeriesServers(html, origin)
        if (availableServers.isEmpty()) {
            throw ErrorLoadingException("Série pas disponible sur Flemmix/Wiflix")
        }

        val seasonNumber = source.season ?: 1
        val tmdbId = tmdb?.optInt("id", 0)?.takeIf { it > 0 }
        val seasonDetails = tmdbId?.let {
            runCatching { tmdbGet("/tv/$it/season/$seasonNumber") }.getOrNull()
        }
        val metadataByEpisode = (seasonDetails?.optJSONArray("episodes") ?: JSONArray())
            .objects()
            .associateBy { it.optInt("episode_number", 0) }
        val tmdbCount = seasonDetails?.optJSONArray("episodes")?.length() ?: 0
        val sourceCount = availableServers.keys.maxOrNull() ?: 0
        val episodeCount = max(tmdbCount, sourceCount)
        val today = todayUtc()
        val sourcePoster = source.posterUrl

        val episodes = (1..episodeCount).map { episodeNumber ->
            val metadata = metadataByEpisode[episodeNumber]
            val airDate = metadata?.stringOrNull("air_date")
            val future = isFutureEpisode(airDate, today)
            val available = availableServers[episodeNumber].orEmpty().isNotEmpty()
            val episodeTitle = metadata?.stringOrNull("name")
                ?.takeIf(String::isNotBlank)
                ?: "Épisode $episodeNumber"
            val label = when {
                available -> episodeTitle
                future -> "⏳ À venir · $episodeTitle${airDate?.let { " · $it" }.orEmpty()}"
                else -> "⛔ Pas disponible · $episodeTitle"
            }
            val overview = metadata?.stringOrNull("overview")?.takeIf(String::isNotBlank)
            val description = when {
                available -> overview
                future -> listOfNotNull(airDate?.let { "Prévu le $it" }, overview)
                    .joinToString("\n\n").ifBlank { "Épisode à venir" }
                else -> listOfNotNull("Pas disponible sur Flemmix/Wiflix", overview)
                    .joinToString("\n\n")
            }

            newEpisode(
                url = FlemmixPlaybackRequest(
                    type = "tv",
                    path = source.path,
                    episode = episodeNumber,
                ).encode(),
                initializer = {
                    name = label
                    this.season = seasonNumber
                    episode = episodeNumber
                    posterUrl = tmdbPoster(metadata?.stringOrNull("still_path"), "w500")
                        ?: tmdbPoster(seasonDetails?.stringOrNull("poster_path"))
                        ?: sourcePoster
                    this.description = description
                },
                fix = false,
            )
        }

        val title = tmdb?.stringOrNull("name") ?: source.title
        return newTvSeriesLoadResponse(title, requestedUrl, TvType.TvSeries, episodes) {
            posterUrl = tmdbPoster(tmdb?.stringOrNull("poster_path")) ?: sourcePoster
            backgroundPosterUrl = tmdbPoster(tmdb?.stringOrNull("backdrop_path"), "w1280")
            year = year(tmdb?.stringOrNull("first_air_date")) ?: source.year
            plot = tmdb?.stringOrNull("overview")?.takeIf(String::isNotBlank) ?: source.plot
            tags = tmdbGenres(tmdb).ifEmpty { source.tags }
            score = tmdbScore(tmdb)
        }
    }

    private fun parseMovieServers(html: String, origin: String): List<FlemmixServer> =
        parseServers(html, origin, null)

    private fun parseSeriesServers(
        html: String,
        origin: String,
    ): Map<Int, List<FlemmixServer>> {
        val result = LinkedHashMap<Int, MutableList<FlemmixServer>>()

        for (block in EPISODE_BLOCK_REGEX.findAll(html)) {
            val episode = block.groupValues[1].toIntOrNull() ?: continue
            val language = if (block.groupValues[2].equals("vs", true)) "VOSTFR" else "VF"
            val servers = parseServers(block.groupValues[3], origin, language)
            if (servers.isNotEmpty()) result.getOrPut(episode) { ArrayList() }.addAll(servers)
        }

        return result.mapValues { (_, servers) -> servers.distinctBy(FlemmixServer::url) }
    }

    private fun parseServers(
        html: String,
        origin: String,
        language: String?,
    ): List<FlemmixServer> {
        val results = LinkedHashMap<String, FlemmixServer>()

        for (match in VIDEO_ANCHOR_REGEX.findAll(html)) {
            val rawUrl = decodeHtml(match.groupValues[1]).replace("\\/", "/")
            val url = resolveUrl("$origin/", rawUrl) ?: continue
            if (url.startsWith("javascript:", true)) continue
            val rawLabel = plainText(match.groupValues[2]).ifBlank { "Lecteur" }
            val label = listOfNotNull(language, rawLabel).joinToString(" · ")
            results.putIfAbsent(url, FlemmixServer(label, url))
        }

        return results.values.toList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val request = FlemmixPlaybackRequest.decode(data) ?: return false
        val page = fetchDetailWithRefresh(request.path) ?: return false
        val servers = if (request.type == "movie") {
            parseMovieServers(page.html, page.origin)
        } else {
            request.episode?.let { parseSeriesServers(page.html, page.origin)[it] }
                .orEmpty()
        }
        if (servers.isEmpty()) return false

        var emitted = false
        val referer = "${page.origin}${request.path}"

        for (server in servers.distinctBy(FlemmixServer::url)) {
            if (DIRECT_MEDIA_REGEX.containsMatchIn(server.url)) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Flemmix/Wiflix · ${server.label}",
                        url = server.url,
                        type = when {
                            server.url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                            server.url.contains(".mpd", true) -> ExtractorLinkType.DASH
                            else -> ExtractorLinkType.VIDEO
                        },
                    ) {
                        this.referer = referer
                        quality = getQualityFromName(server.label)
                        headers = browserHeaders
                    },
                )
                emitted = true
                continue
            }

            runCatching {
                loadExtractor(
                    url = server.url,
                    referer = referer,
                    subtitleCallback = subtitleCallback,
                    callback = {
                        emitted = true
                        callback(it)
                    },
                )
            }
        }

        return emitted
    }

    private suspend fun findTmdbMatch(source: SourceDetails): JSONObject? {
        val endpoint = if (source.type == "movie") "/search/movie" else "/search/tv"
        val candidates = sequenceOf(source.originalTitle, stripSeason(source.title), source.title)
            .filterNotNull()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(::normalizeForMatch)

        for (query in candidates) {
            val root = runCatching {
                tmdbGet(endpoint, mapOf("query" to query, "page" to "1"))
            }.getOrNull() ?: continue
            val result = (root.optJSONArray("results") ?: JSONArray())
                .objects()
                .maxByOrNull { tmdbMatchScore(it, source) }
                ?.takeIf { tmdbMatchScore(it, source) >= MINIMUM_TMDB_SCORE }
                ?: continue
            val id = result.optInt("id", 0).takeIf { it > 0 } ?: continue
            val detailPath = if (source.type == "movie") "/movie/$id" else "/tv/$id"
            return runCatching { tmdbGet(detailPath) }
                .getOrNull()
        }
        return null
    }

    private fun tmdbMatchScore(candidate: JSONObject, source: SourceDetails): Int {
        val candidateTitles = if (source.type == "movie") {
            listOf(candidate.stringOrNull("title"), candidate.stringOrNull("original_title"))
        } else {
            listOf(candidate.stringOrNull("name"), candidate.stringOrNull("original_name"))
        }.filterNotNull().map(::normalizeForMatch)
        val expected = listOfNotNull(source.originalTitle, stripSeason(source.title), source.title)
            .map(::normalizeForMatch)
        var score = candidateTitles.maxOfOrNull { title ->
            expected.maxOfOrNull { wanted ->
                when {
                    title == wanted -> 100
                    title.contains(wanted) || wanted.contains(title) -> 70
                    else -> 0
                }
            } ?: 0
        } ?: 0
        val candidateYear = year(
            if (source.type == "movie") candidate.stringOrNull("release_date")
            else candidate.stringOrNull("first_air_date"),
        )
        if (source.year != null && candidateYear != null) {
            score += when (kotlin.math.abs(source.year - candidateYear)) {
                0 -> 20
                1 -> 5
                else -> -20
            }
        }
        return score
    }

    private suspend fun tmdbGet(
        path: String,
        params: Map<String, String> = emptyMap(),
    ): JSONObject {
        val query = linkedMapOf(
            "api_key" to tmdbApiKey,
            "language" to "fr-FR",
        ).apply { putAll(params) }
        val url = "$tmdbApi$path?" + query.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val response = app.get(url, cacheTime = TMDB_CACHE_SECONDS)
        if (response.okhttpResponse.code !in 200..299) {
            throw ErrorLoadingException("TMDB HTTP ${response.okhttpResponse.code}")
        }
        return JSONObject(response.text)
    }

    private fun tmdbPoster(path: String?, size: String = "w500"): String? =
        path?.takeIf(String::isNotBlank)?.let { "$tmdbImages/$size$it" }

    private fun tmdbGenres(root: JSONObject?): List<String> =
        (root?.optJSONArray("genres") ?: JSONArray())
            .objects()
            .mapNotNull { it.stringOrNull("name") }
            .toList()

    private fun tmdbScore(root: JSONObject?): Score? =
        root?.takeIf { it.has("vote_average") }
            ?.optDouble("vote_average")
            ?.takeIf { it > 0.0 }
            ?.let { Score.from10(it) }

    private fun labeledValue(html: String, label: String): String? {
        val pattern = Regex(
            """<div\b[^>]*class=[\"'][^\"']*mov-label[^\"']*[\"'][^>]*>\s*${Regex.escape(label)}\s*:?\s*</div>\s*<div\b[^>]*class=[\"'][^\"']*mov-desc[^\"']*[\"'][^>]*>(.*?)</div>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return pattern.find(html)?.groupValues?.getOrNull(1)
            ?.let(::plainText)
            ?.takeIf(String::isNotBlank)
    }

    private fun resolveUrl(base: String, value: String): String? {
        if (value.isBlank() || value.startsWith("data:", true)) return null
        val uri = runCatching { URI(base).resolve(value.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        return uri.toString()
    }

    private fun attribute(tag: String, name: String): String? = Regex(
        """(?i)\b${Regex.escape(name)}\s*=\s*[\"']([^\"']*)[\"']""",
    ).find(tag)?.groupValues?.getOrNull(1)

    private fun plainText(value: String): String = decodeHtml(TAG_REGEX.replace(value, " "))
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun decodeHtml(value: String): String {
        var decoded = value
            .replace("&amp;", "&", true)
            .replace("&quot;", "\"", true)
            .replace("&#39;", "'", true)
            .replace("&apos;", "'", true)
            .replace("&lt;", "<", true)
            .replace("&gt;", ">", true)
        decoded = HEX_ENTITY_REGEX.replace(decoded) { match ->
            match.groupValues[1].toIntOrNull(16)
                ?.let { Character.toChars(it).concatToString() } ?: match.value
        }
        return DECIMAL_ENTITY_REGEX.replace(decoded) { match ->
            match.groupValues[1].toIntOrNull()
                ?.let { Character.toChars(it).concatToString() } ?: match.value
        }
    }

    private fun normalizeForMatch(value: String): String {
        val decomposed = Normalizer.normalize(decodeHtml(value), Normalizer.Form.NFKD)
        return decomposed
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            .lowercase(Locale.FRENCH)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private fun stripSeason(value: String): String =
        SEASON_IN_TITLE_REGEX.replace(value, "").trim(' ', '-', ':')

    private fun year(date: String?): Int? = date?.take(4)?.toIntOrNull()

    private fun todayUtc(): Date {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return formatter.parse(formatter.format(Date())) ?: Date()
    }

    private fun isFutureEpisode(date: String?, today: Date): Boolean {
        if (date.isNullOrBlank()) return false
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        return runCatching { formatter.parse(date)?.after(today) == true }.getOrDefault(false)
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        optString(key, "").takeIf(String::isNotBlank)

    private fun JSONArray.objects(): Sequence<JSONObject> = sequence {
        for (index in 0 until length()) optJSONObject(index)?.let { yield(it) }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val PAGE_TIMEOUT_SECONDS = 15L
        const val CATALOGUE_CACHE_SECONDS = 300
        const val DETAIL_CACHE_SECONDS = 120
        const val TMDB_CACHE_SECONDS = 3600
        const val ITEM_CONTEXT_SIZE = 2500
        const val MAX_SEARCH_RESULTS = 40
        const val MINIMUM_TMDB_SCORE = 70

        val DETAIL_PATH_REGEX = Regex(
            """/(film|serie)-en-streaming/[0-9]+-[^/?#]+\.html""",
            RegexOption.IGNORE_CASE,
        )
        val ANCHOR_REGEX = Regex(
            """<a\b[^>]*\bhref\s*=\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val IMAGE_REGEX = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
        val MOV_TITLE_CLASS_REGEX = Regex(
            """\bclass\s*=\s*[\"'][^\"']*\bmov-t\b[^\"']*[\"']""",
            RegexOption.IGNORE_CASE,
        )
        val TITLE0_REGEX = Regex(
            """<span\b[^>]*class=[\"'][^\"']*title0[^\"']*[\"'][^>]*>(.*?)</span>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val PAGE_LINK_REGEX = Regex(
            """href=[\"']([^\"']*/page/([0-9]+)/?)[\"']""",
            RegexOption.IGNORE_CASE,
        )
        val SEARCH_RESULTS_START_REGEX = Regex(
            """<form\b[^>]*(?:id|name)\s*=\s*[\"']fullsearch[\"'][^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        val DETAIL_TITLE_REGEX = Regex(
            """<h1\b[^>]*itemprop=[\"']name[\"'][^>]*>(.*?)</h1>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val POSTER_REGEX = Regex(
            """<img\b[^>]*\bid=[\"']posterimg[\"'][^>]*\bsrc=[\"']([^\"']+)[\"'][^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        val DESCRIPTION_REGEX = Regex(
            """<span\b[^>]*itemprop=[\"']description[\"'][^>]*>(.*?)</span>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val GENRE_REGEX = Regex(
            """<span\b[^>]*itemprop=[\"']genre[\"'][^>]*>(.*?)</span>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val VIDEO_ANCHOR_REGEX = Regex(
            """<a\b[^>]*\bonclick\s*=\s*[\"'][^\"']*loadVideo\(\s*['\"]([^'\"]+)['\"][^\"']*[\"'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val EPISODE_BLOCK_REGEX = Regex(
            """<div\b[^>]*class=[\"'][^\"']*\bep([0-9]+)(vf|vs)\b[^\"']*[\"'][^>]*>(.*?)</div>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val SEASON_IN_TITLE_REGEX = Regex(
            """\s*[-:]?\s*saison\s+([0-9]+)\b""",
            RegexOption.IGNORE_CASE,
        )
        val YEAR_REGEX = Regex("""\b(?:19|20)[0-9]{2}\b""")
        val DURATION_REGEX = Regex("""([0-9]{1,3})\s*(?:min|mn)""", RegexOption.IGNORE_CASE)
        val DIRECT_MEDIA_REGEX = Regex("""\.(?:m3u8|mpd|mp4|mkv)(?:[?#]|$)""", RegexOption.IGNORE_CASE)
        val TAG_REGEX = Regex("""<[^>]+>""")
        val HEX_ENTITY_REGEX = Regex("""&#x([0-9a-f]+);""", RegexOption.IGNORE_CASE)
        val DECIMAL_ENTITY_REGEX = Regex("""&#([0-9]+);""")
    }
}
