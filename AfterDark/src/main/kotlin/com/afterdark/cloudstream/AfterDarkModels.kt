package com.afterdark.cloudstream

data class PlaybackRequest(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val releaseYear: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val titleKey: String
        get() = "$type-$tmdbId"

    fun watchUrl(mainUrl: String): String {
        val base = "$mainUrl/watch/$type-$tmdbId"
        return if (type == "tv" && season != null && episode != null) {
            "$base?s=$season&e=$episode"
        } else {
            base
        }
    }
}

data class ProofSession(
    val proof: String,
    val cookie: String?,
    val userAgent: String,
)

data class TmdbPage(
    val page: Int = 1,
    val results: List<TmdbItem> = emptyList(),
    val total_pages: Int = 1,
)

data class TmdbItem(
    val id: Int = 0,
    val media_type: String? = null,
    val title: String? = null,
    val name: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val vote_average: Double? = null,
)

data class TmdbDetails(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    val original_title: String? = null,
    val original_name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val runtime: Int? = null,
    val vote_average: Double? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val seasons: List<TmdbSeasonSummary> = emptyList(),
)

data class TmdbGenre(
    val id: Int = 0,
    val name: String = "",
)

data class TmdbSeasonSummary(
    val id: Int = 0,
    val name: String? = null,
    val season_number: Int = 0,
    val episode_count: Int = 0,
    val poster_path: String? = null,
    val air_date: String? = null,
)

data class ParsedSubtitle(
    val language: String,
    val url: String,
)

data class ParsedSource(
    val group: String,
    val service: String,
    val provider: String,
    val url: String,
    val quality: String?,
    val language: String?,
    val type: String?,
    val proxied: Boolean,
    val subtitles: List<ParsedSubtitle>,
)
