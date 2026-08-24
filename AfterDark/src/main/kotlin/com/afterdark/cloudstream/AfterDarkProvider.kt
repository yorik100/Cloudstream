package com.afterdark.cloudstream

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
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
    override val loadLinksTimeoutMs = 210_000L

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbImages = "https://image.tmdb.org/t/p"

    /**
     * Clé présente dans le bundle frontend public d'AfterDark au moment de
     * l'analyse. Elle peut être changée/rotée par le site.
     */
    private val tmdbApiKey = "f3d757824f08ea2cff45eb8f47ca3a1e"

    private val proofCache = ConcurrentHashMap<String, ProofSession>()

    override val mainPage = mainPageOf(
        "movie" to "Films populaires",
        "tv" to "Séries populaires",
    )

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun tmdbUrl(
        path: String,
        params: Map<String, String?> = emptyMap()
    ): String {
        val query = linkedMapOf(
            "api_key" to tmdbApiKey,
            "language" to "fr-FR",
            "include_adult" to "false",
        )
        params.forEach { (key, value) ->
            if (value != null) query[key] = value
        }

        return "$tmdbApi$path?" + query.entries.joinToString("&") {
            "${enc(it.key)}=${enc(it.value)}"
        }
    }

    private fun poster(path: String?, size: String = "w500"): String? =
        path?.let { "$tmdbImages/$size$it" }

    private fun year(date: String?): Int? =
        date?.take(4)?.toIntOrNull()

    private fun watchUrl(type: String, id: Int): String =
        "$mainUrl/watch/$type-$id"

    private fun TmdbItem.toSearchResponse(forcedType: String? = null): SearchResponse? {
        val mediaType = forcedType ?: media_type ?: return null
        val itemName = when (mediaType) {
            "movie" -> title
            "tv" -> name
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return null

        val itemYear = year(
            if (mediaType == "movie") release_date else first_air_date
        )
        val url = watchUrl(mediaType, id)

        return when (mediaType) {
            "movie" -> newMovieSearchResponse(
                itemName,
                url,
                TvType.Movie
            ) {
                posterUrl = poster(poster_path)
                year = itemYear
                score = Score.from10(vote_average)
            }

            "tv" -> newTvSeriesSearchResponse(
                itemName,
                url,
                TvType.TvSeries
            ) {
                posterUrl = poster(poster_path)
                year = itemYear
                score = Score.from10(vote_average)
            }

            else -> null
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val type = request.data
        val endpoint = if (type == "tv") "/tv/popular" else "/movie/popular"

        val response = app.get(
            tmdbUrl(endpoint, mapOf("page" to page.toString()))
        ).parsed<TmdbPage>()

        val items = response.results.mapNotNull {
            it.toSearchResponse(type)
        }

        return newHomePageResponse(
            request,
            items,
            page < response.total_pages
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val response = app.get(
            tmdbUrl(
                "/search/multi",
                mapOf(
                    "query" to query,
                    "page" to "1",
                )
            )
        ).parsed<TmdbPage>()

        return response.results
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val match = Regex("""/watch/(movie|tv)-(\d+)""").find(url)
            ?: throw ErrorLoadingException("URL AfterDark invalide")

        val type = match.groupValues[1]
        val tmdbId = match.groupValues[2].toIntOrNull()
            ?: throw ErrorLoadingException("TMDB ID invalide")

        val details = app.get(
            tmdbUrl("/$type/$tmdbId")
        ).parsed<TmdbDetails>()

        val title = when (type) {
            "movie" -> details.title
            "tv" -> details.name
            else -> null
        }?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Titre introuvable")

        val releaseYear = year(
            if (type == "movie") details.release_date else details.first_air_date
        )

        val commonPoster = poster(details.poster_path)
        val backdrop = poster(details.backdrop_path, "w1280")
        val tags = details.genres.mapNotNull { it.name.takeIf(String::isNotBlank) }

        return if (type == "movie") {
            val data = PlaybackRequest(
                tmdbId = tmdbId,
                type = "movie",
                title = title,
                releaseYear = releaseYear,
            )

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                data
            ) {
                posterUrl = commonPoster
                backgroundPosterUrl = backdrop
                year = releaseYear
                plot = details.overview
                this.tags = tags
                duration = details.runtime
                score = Score.from10(details.vote_average)
            }
        } else {
            val episodes = details.seasons
                .filter { it.episode_count > 0 }
                .sortedBy { it.season_number }
                .flatMap { season ->
                    (1..season.episode_count).map { episodeNumber ->
                        newEpisode(
                            PlaybackRequest(
                                tmdbId = tmdbId,
                                type = "tv",
                                title = title,
                                releaseYear = releaseYear,
                                season = season.season_number,
                                episode = episodeNumber,
                            )
                        ) {
                            name = "Épisode $episodeNumber"
                            this.season = season.season_number
                            episode = episodeNumber
                            posterUrl = poster(season.poster_path)
                        }
                    }
                }

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = commonPoster
                backgroundPosterUrl = backdrop
                year = releaseYear
                plot = details.overview
                this.tags = tags
                score = Score.from10(details.vote_average)
            }
        }
    }

    private fun sourcesApiUrl(request: PlaybackRequest): String {
        val params = linkedMapOf<String, String>(
            "tmdbId" to request.tmdbId.toString(),
            "type" to request.type,
            "title" to request.title,
        )

        request.releaseYear?.let { params["releaseYear"] = it.toString() }
        request.season?.let { params["season"] = it.toString() }
        request.episode?.let { params["episode"] = it.toString() }

        return "$mainUrl/api/sources?" + params.entries.joinToString("&") {
            "${enc(it.key)}=${enc(it.value)}"
        }
    }

    private suspend fun fetchSources(
        request: PlaybackRequest,
        session: ProofSession,
    ): Pair<Int, String> {
        val watchUrl = request.watchUrl(mainUrl)
        val headers = linkedMapOf(
            "Accept" to "application/x-ndjson",
            "x-nabi-proof" to session.proof,
            "Referer" to watchUrl,
            "User-Agent" to session.userAgent,
        )

        session.cookie?.takeIf { it.isNotBlank() }?.let {
            headers["Cookie"] = it
        }

        val response = app.get(
            sourcesApiUrl(request),
            headers = headers,
            referer = watchUrl,
            cacheTime = 0
        )

        return response.okhttpResponse.code to response.text
    }

    private suspend fun obtainSession(request: PlaybackRequest): ProofSession? {
        proofCache[request.titleKey]?.let { return it }

        val acquired = AfterDarkProofWebView.acquire(request, mainUrl)
            ?: return null

        proofCache[request.titleKey] = acquired
        return acquired
    }

    private fun parseSubtitles(node: JsonNode?): List<ParsedSubtitle> {
        if (node == null || !node.isArray) return emptyList()

        return node.mapNotNull { sub ->
            if (sub.isTextual) {
                val url = sub.asText().takeIf { it.startsWith("http") }
                    ?: return@mapNotNull null
                ParsedSubtitle("Sous-titre", url)
            } else {
                val url = sequenceOf("url", "file", "src")
                    .mapNotNull { key -> sub.get(key)?.asText()?.takeIf(String::isNotBlank) }
                    .firstOrNull()
                    ?: return@mapNotNull null

                val language = sequenceOf("language", "lang", "label", "name")
                    .mapNotNull { key -> sub.get(key)?.asText()?.takeIf(String::isNotBlank) }
                    .firstOrNull()
                    ?: "Sous-titre"

                ParsedSubtitle(language, url)
            }
        }
    }

    private fun parseNdjson(body: String): List<ParsedSource> {
        val sources = mutableListOf<ParsedSource>()

        body.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEach

            val groupNode = runCatching { mapper.readTree(line) }.getOrNull()
                ?: return@forEach

            val items = groupNode.get("items")
            if (items == null || !items.isArray || items.size() == 0) return@forEach

            val group = groupNode.get("provider")?.asText()?.takeIf(String::isNotBlank)
                ?: groupNode.get("id")?.asText()?.takeIf(String::isNotBlank)
                ?: "AfterDark"

            items.forEach { item ->
                val url = item.get("url")?.asText()?.takeIf(String::isNotBlank)
                    ?: return@forEach

                sources += ParsedSource(
                    group = group,
                    service = item.get("service")?.asText()?.takeIf(String::isNotBlank)
                        ?: "AfterDark",
                    provider = item.get("provider")?.asText()?.takeIf(String::isNotBlank)
                        ?: group,
                    url = url,
                    quality = item.get("quality")?.asText()?.takeIf(String::isNotBlank),
                    language = item.get("language")?.asText()?.takeIf(String::isNotBlank),
                    type = item.get("type")?.asText()?.takeIf(String::isNotBlank),
                    proxied = item.get("proxied")?.asBoolean(false) ?: false,
                    subtitles = parseSubtitles(item.get("subtitles")),
                )
            }
        }

        return sources.distinctBy { "${it.service}|${it.url}" }
    }

    private fun linkType(source: ParsedSource): ExtractorLinkType? {
        return when (source.type?.lowercase()) {
            "hls", "m3u8" -> ExtractorLinkType.M3U8
            "mp4", "video" -> ExtractorLinkType.VIDEO
            "dash", "mpd" -> ExtractorLinkType.DASH
            else -> when {
                source.url.substringBefore("?").endsWith(".m3u8", true) ->
                    ExtractorLinkType.M3U8
                source.url.substringBefore("?").endsWith(".mpd", true) ->
                    ExtractorLinkType.DASH
                source.url.substringBefore("?").matches(
                    Regex(""".*\.(mp4|mkv|webm)$""", RegexOption.IGNORE_CASE)
                ) -> ExtractorLinkType.VIDEO
                else -> null
            }
        }
    }

    private fun displayName(source: ParsedSource): String {
        return listOfNotNull(
            source.provider.takeIf { it.isNotBlank() && it != "AfterDark" },
            source.service.takeIf { it.isNotBlank() },
            source.language,
            source.quality
        ).distinct().joinToString(" · ").ifBlank { "AfterDark" }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val request = tryParseJson<PlaybackRequest>(data)
            ?: throw ErrorLoadingException("Données AfterDark invalides")

        var session = obtainSession(request) ?: return false
        var (status, body) = fetchSources(request, session)

        // La proof est temporaire. En cas de 403, on l'efface et on laisse
        // l'utilisateur refaire la vérification officielle une seule fois.
        if (status == 403) {
            proofCache.remove(request.titleKey)
            session = obtainSession(request) ?: return false
            val retry = fetchSources(request, session)
            status = retry.first
            body = retry.second
        }

        if (status !in 200..299) return false

        val sources = parseNdjson(body)
        if (sources.isEmpty()) return false

        val emittedUrls = HashSet<String>()
        var emitted = false

        for (source in sources) {
            source.subtitles.forEach { sub ->
                subtitleCallback(
                    newSubtitleFile(sub.language, sub.url)
                )
            }

            if (!emittedUrls.add(source.url)) continue

            val directType = linkType(source)
            val isEmbed = source.type.equals("embed", ignoreCase = true)

            if (directType != null || (source.proxied && !isEmbed)) {
                callback(
                    newExtractorLink(
                        source = source.service,
                        name = displayName(source),
                        url = source.url,
                        type = directType ?: ExtractorLinkType.VIDEO,
                    ) {
                        referer = request.watchUrl(mainUrl)
                        quality = getQualityFromName(source.quality)
                        headers = mapOf(
                            "Referer" to request.watchUrl(mainUrl),
                            "User-Agent" to session.userAgent,
                        )
                    }
                )
                emitted = true
                continue
            }

            val extracted = runCatching {
                loadExtractor(
                    url = source.url,
                    referer = request.watchUrl(mainUrl),
                    subtitleCallback = subtitleCallback,
                    callback = {
                        emitted = true
                        callback(it)
                    }
                )
            }.getOrDefault(false)

            // Pour les "embed" inconnus, ne les présente pas comme MP4.
            // Une URL non-embed explicite reste utilisable en dernier recours.
            if (!extracted && !isEmbed) {
                callback(
                    newExtractorLink(
                        source = source.service,
                        name = displayName(source),
                        url = source.url,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        referer = request.watchUrl(mainUrl)
                        quality = getQualityFromName(source.quality)
                        headers = mapOf(
                            "Referer" to request.watchUrl(mainUrl),
                            "User-Agent" to session.userAgent,
                        )
                    }
                )
                emitted = true
            }
        }

        return emitted
    }
}
