package com.afterdark.cloudstream

import java.net.URLDecoder
import java.net.URLEncoder

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

    fun encode(): String {
        val values = linkedMapOf(
            "tmdbId" to tmdbId.toString(),
            "type" to type,
            "title" to title,
        )
        releaseYear?.let { values["releaseYear"] = it.toString() }
        season?.let { values["season"] = it.toString() }
        episode?.let { values["episode"] = it.toString() }

        return values.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
    }

    companion object {
        fun decode(data: String): PlaybackRequest? {
            val values = data.split("&")
                .mapNotNull { part ->
                    val index = part.indexOf('=')
                    if (index <= 0) return@mapNotNull null
                    urlDecode(part.substring(0, index)) to urlDecode(part.substring(index + 1))
                }
                .toMap()

            val tmdbId = values["tmdbId"]?.toIntOrNull() ?: return null
            val type = values["type"]?.takeIf { it == "movie" || it == "tv" } ?: return null
            val title = values["title"]?.takeIf { it.isNotBlank() } ?: return null

            return PlaybackRequest(
                tmdbId = tmdbId,
                type = type,
                title = title,
                releaseYear = values["releaseYear"]?.toIntOrNull(),
                season = values["season"]?.toIntOrNull(),
                episode = values["episode"]?.toIntOrNull(),
            )
        }

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, "UTF-8")

        private fun urlDecode(value: String): String =
            URLDecoder.decode(value, "UTF-8")
    }
}

data class ProofSession(
    val proof: String,
    val cookie: String?,
    val userAgent: String,
)

data class ParsedSubtitle(
    val language: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
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
    val referer: String?,
    val headers: Map<String, String>,
    val subtitles: List<ParsedSubtitle>,
)
