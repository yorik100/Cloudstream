package com.flemmix.cloudstream

import java.net.URLDecoder
import java.net.URLEncoder

internal data class FlemmixPlaybackRequest(
    val type: String,
    val path: String,
    val episode: Int? = null,
) {
    fun encode(): String {
        val values = linkedMapOf(
            "type" to type,
            "path" to path,
        )
        episode?.let { values["episode"] = it.toString() }

        return values.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
    }

    companion object {
        fun decode(data: String): FlemmixPlaybackRequest? {
            val values = data.split("&")
                .mapNotNull { part ->
                    val separator = part.indexOf('=')
                    if (separator <= 0) return@mapNotNull null
                    urlDecode(part.substring(0, separator)) to
                        urlDecode(part.substring(separator + 1))
                }
                .toMap()

            val type = values["type"]
                ?.takeIf { it == "movie" || it == "tv" }
                ?: return null
            val path = values["path"]
                ?.takeIf { it.startsWith('/') && !it.startsWith("//") }
                ?: return null
            val episode = values["episode"]?.toIntOrNull()
            if (type == "tv" && (episode == null || episode <= 0)) return null

            return FlemmixPlaybackRequest(type, path, episode)
        }

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, "UTF-8")

        private fun urlDecode(value: String): String =
            URLDecoder.decode(value, "UTF-8")
    }
}

internal data class FlemmixServer(
    val label: String,
    val url: String,
)
