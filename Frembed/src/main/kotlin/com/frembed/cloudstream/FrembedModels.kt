package com.frembed.cloudstream

import java.net.URLDecoder
import java.net.URLEncoder

data class FrembedPlaybackRequest(
    val tmdbId: Int,
    val type: String,
    val season: Int? = null,
    val episode: Int? = null,
) {
    fun encode(): String {
        val values = linkedMapOf(
            "tmdbId" to tmdbId.toString(),
            "type" to type,
        )
        season?.let { values["season"] = it.toString() }
        episode?.let { values["episode"] = it.toString() }

        return values.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
    }

    companion object {
        fun decode(data: String): FrembedPlaybackRequest? {
            val values = data.split("&")
                .mapNotNull { part ->
                    val index = part.indexOf('=')
                    if (index <= 0) return@mapNotNull null
                    urlDecode(part.substring(0, index)) to
                        urlDecode(part.substring(index + 1))
                }
                .toMap()

            val tmdbId = values["tmdbId"]?.toIntOrNull() ?: return null
            val type = values["type"]?.takeIf { it == "movie" || it == "tv" }
                ?: return null

            val season = values["season"]?.toIntOrNull()
            val episode = values["episode"]?.toIntOrNull()

            if (type == "tv" && (season == null || episode == null)) return null

            return FrembedPlaybackRequest(
                tmdbId = tmdbId,
                type = type,
                season = season,
                episode = episode,
            )
        }

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, "UTF-8")

        private fun urlDecode(value: String): String =
            URLDecoder.decode(value, "UTF-8")
    }
}
