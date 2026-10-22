package com.afterdark.cloudstream

import com.lagradost.cloudstream3.app
import java.net.URI

/**
 * Final media gate used before any URL reaches CloudStream/ExoPlayer.
 *
 * A URL, extension or Content-Type alone is not enough: error pages from
 * players/CDNs can otherwise be handed to ExoPlayer and surface as 3002/3003.
 * The probe reads only the first bytes of the response and classifies the real
 * payload as HLS, DASH or a supported direct-video container.
 */
internal object AfterDarkMediaProbe {
    private const val TIMEOUT_SECONDS = 12L
    private const val PREFIX_LIMIT = 8192

    private fun contentType(response: com.lagradost.nicehttp.NiceResponse): String =
        response.headers["Content-Type"]
            ?.substringBefore(";")
            ?.trim()
            ?.lowercase()
            .orEmpty()

    private fun host(url: String): String = runCatching {
        URI(url).host.orEmpty().lowercase()
    }.getOrDefault("")

    private fun readPrefix(response: com.lagradost.nicehttp.NiceResponse): ByteArray {
        // NiceHttp exposes ResponseBody.bytes() in both the legacy OkHttp-backed
        // API and the current compatibility layer. Avoid materializing a large
        // video when a server ignores Range and returns the whole file.
        val declaredSize = response.size
        if (response.code == 200 && declaredSize != null && declaredSize > 1_000_000L) {
            return ByteArray(0)
        }

        val bytes = runCatching { response.body?.bytes() ?: ByteArray(0) }
            .getOrDefault(ByteArray(0))
        return if (bytes.size <= PREFIX_LIMIT) bytes else bytes.copyOf(PREFIX_LIMIT)
    }

    private fun startsWith(bytes: ByteArray, vararg expected: Int): Boolean {
        if (bytes.size < expected.size) return false
        return expected.indices.all { index ->
            (bytes[index].toInt() and 0xFF) == expected[index]
        }
    }

    private fun asciiAt(bytes: ByteArray, offset: Int, value: String): Boolean {
        if (offset < 0 || bytes.size < offset + value.length) return false
        return value.indices.all { index ->
            (bytes[offset + index].toInt() and 0xFF) == value[index].code
        }
    }

    private fun supportedVideoMagic(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false

        // ISO-BMFF / MP4. Fragmented files can begin with styp instead of ftyp.
        if (asciiAt(bytes, 4, "ftyp") || asciiAt(bytes, 4, "styp")) return true

        // Matroska / WebM (EBML).
        if (startsWith(bytes, 0x1A, 0x45, 0xDF, 0xA3)) return true

        // MPEG transport stream. Check a second packet when enough bytes exist.
        if ((bytes[0].toInt() and 0xFF) == 0x47) {
            if (bytes.size <= 188 || (bytes[188].toInt() and 0xFF) == 0x47) return true
        }

        // MPEG program stream.
        if (startsWith(bytes, 0x00, 0x00, 0x01, 0xBA)) return true

        // FLV.
        if (asciiAt(bytes, 0, "FLV")) return true

        // AVI.
        if (asciiAt(bytes, 0, "RIFF") && asciiAt(bytes, 8, "AVI ")) return true

        // Ogg container.
        if (asciiAt(bytes, 0, "OggS")) return true

        return false
    }

    private fun textPrefix(bytes: ByteArray): String =
        bytes.toString(Charsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')

    private fun clearlyErrorText(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.take(1024).lowercase()
        return lower.startsWith("<!doctype html") ||
            lower.startsWith("<html") ||
            lower.startsWith("{") ||
            lower.startsWith("[") ||
            lower.startsWith("error_") ||
            lower == "error" ||
            "error_nofile" in lower ||
            "file not found" in lower ||
            "access denied" in lower ||
            "forbidden" in lower
    }

    private suspend fun requestPrefix(
        media: ResolvedWebMedia,
        withRange: Boolean,
    ): Pair<com.lagradost.nicehttp.NiceResponse, ByteArray>? {
        val headers = LinkedHashMap(media.headers)
        headers.keys.firstOrNull { it.equals("Range", ignoreCase = true) }?.let(headers::remove)
        if (withRange) headers["Range"] = "bytes=0-${PREFIX_LIMIT - 1}"

        val response = runCatching {
            app.get(
                url = media.url,
                headers = headers,
                referer = media.referer.orEmpty(),
                cacheTime = 0,
                timeout = TIMEOUT_SECONDS,
            )
        }.getOrNull() ?: return null

        val code = response.code
        if (code !in 200..299) return null

        return response to readPrefix(response)
    }

    private fun normalizedHint(media: ResolvedWebMedia): String? {
        when (media.type?.lowercase()) {
            "m3u8", "hls" -> return "m3u8"
            "mpd", "dash" -> return "mpd"
            "video", "mp4" -> return "video"
        }

        val path = runCatching { URI(media.url).path.orEmpty().lowercase() }.getOrDefault("")
        return when {
            path.endsWith(".m3u8") -> "m3u8"
            path.endsWith(".mpd") -> "mpd"
            path.endsWith(".mp4") || path.endsWith(".m4v") ||
                path.endsWith(".webm") || path.endsWith(".mkv") -> "video"
            else -> null
        }
    }

    /**
     * Classifies an official direct source without performing a GET. Signed
     * media URLs can be one-shot/short-lived; probing their body before the
     * player uses them can turn a valid source into a later HTTP 4xx (2004).
     *
     * If AfterDark already declared the media type, trust that declaration.
     * Otherwise use HEAD only to inspect Content-Type.
     */
    suspend fun classifyWithoutBody(media: ResolvedWebMedia): ResolvedWebMedia? {
        if (!media.url.startsWith("http://", true) && !media.url.startsWith("https://", true)) {
            return null
        }

        normalizedHint(media)?.let { return media.copy(type = it) }

        val response = runCatching {
            app.head(
                url = media.url,
                headers = media.headers,
                referer = media.referer.orEmpty(),
                timeout = TIMEOUT_SECONDS,
            )
        }.getOrNull() ?: return null

        if (response.code !in 200..299) return null

        val type = contentType(response)
        val resolvedType = when {
            "mpegurl" in type || "m3u8" in type -> "m3u8"
            "dash+xml" in type || "mpd" in type -> "mpd"
            type.startsWith("video/") -> "video"
            else -> null
        } ?: return null

        return media.copy(type = resolvedType)
    }

    suspend fun validate(media: ResolvedWebMedia): ResolvedWebMedia? {
        if (!media.url.startsWith("http://", true) && !media.url.startsWith("https://", true)) {
            return null
        }

        var probed = requestPrefix(media, withRange = true)
        if (probed == null) {
            // Some CDNs reject Range on manifests/small resources.
            probed = requestPrefix(media, withRange = false)
        }
        val (response, prefix) = probed ?: return null

        val type = contentType(response)
        val text = textPrefix(prefix)

        if (text.startsWith("#EXTM3U")) {
            return media.copy(type = "m3u8")
        }

        if (text.contains("<MPD", ignoreCase = true)) {
            return media.copy(type = "mpd")
        }

        if (
            type.startsWith("text/html") ||
            type.startsWith("application/json") ||
            type.startsWith("text/plain") ||
            clearlyErrorText(text)
        ) {
            return null
        }

        val magic = supportedVideoMagic(prefix)
        if (magic || (type.startsWith("video/") && (prefix.isNotEmpty() || response.size != null))) {
            return media.copy(type = "video")
        }

        // If Range was ignored for a large, extension-declared video, avoid
        // downloading the complete file only to inspect its first bytes.
        val path = runCatching { URI(media.url).path.orEmpty().lowercase() }.getOrDefault("")
        val declaredVideoPath = path.endsWith(".mp4") ||
            path.endsWith(".m4v") ||
            path.endsWith(".webm") ||
            path.endsWith(".mkv")
        val responseSize = response.size
        if (
            prefix.isEmpty() &&
            responseSize != null &&
            responseSize > 1_000_000L &&
            declaredVideoPath &&
            type == "application/octet-stream"
        ) {
            return media.copy(type = "video")
        }

        // application/octet-stream alone is intentionally NOT enough. Hosts
        // often return textual error payloads with this MIME type; require a
        // recognized container unless the large-file case above applies.
        return null
    }

    fun safeHost(url: String): String = host(url)
}
