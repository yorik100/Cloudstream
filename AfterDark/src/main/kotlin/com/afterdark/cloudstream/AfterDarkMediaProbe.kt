package com.afterdark.cloudstream

import android.util.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
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
    private const val TAG = "AfterDarkMediaProbe"
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
        // Stream a bounded prefix even if Range is ignored or Content-Length
        // is absent. Always close the body; bytes() would download the movie.
        return response.body?.byteStream()?.use { input ->
            val buffer = ByteArray(PREFIX_LIMIT)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                if (read == 0) break
                count += read
            }
            buffer.copyOf(count)
        } ?: ByteArray(0)
    }

    private fun playbackContext(media: ResolvedWebMedia): ResolvedWebMedia {
        val headerReferer = media.headers.entries.firstOrNull {
            it.key.equals("Referer", true)
        }?.value
        val headers = media.headers.filterKeys { key ->
            key.lowercase() !in setOf("range", "host", "connection", "content-length", "accept-encoding", "referer")
        }
        return media.copy(referer = media.referer ?: headerReferer, headers = headers)
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
        stage: String,
    ): Pair<com.lagradost.nicehttp.NiceResponse, ByteArray>? {
        if (AfterDarkSourcePolicy.isDecoy(media.url)) {
            Log.w(TAG, "Probe rejeté stage=$stage host=${host(media.url)} raison=leurre")
            return null
        }
        val headers = LinkedHashMap(media.headers)
        headers.keys.firstOrNull { it.equals("Range", ignoreCase = true) }?.let(headers::remove)
        if (withRange) headers["Range"] = "bytes=0-${PREFIX_LIMIT - 1}"

        val response = try {
            app.get(
                url = media.url,
                headers = headers,
                referer = media.referer.orEmpty(),
                cacheTime = 0,
                timeout = TIMEOUT_SECONDS,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Probe échoué stage=$stage host=${host(media.url)} erreur=${error.javaClass.simpleName}")
            return null
        }

        val code = response.code
        if (code !in 200..299) {
            response.body?.close()
            Log.w(TAG, "Probe refusé stage=$stage host=${host(media.url)} status=$code range=$withRange")
            return null
        }
        if (AfterDarkSourcePolicy.isDecoy(response.url)) {
            response.body?.close()
            return null
        }
        val prefix = try {
            readPrefix(response)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        return response to prefix
    }

    private suspend fun probe(
        media: ResolvedWebMedia,
        stage: String,
    ): Pair<com.lagradost.nicehttp.NiceResponse, ByteArray>? =
        requestPrefix(media, withRange = true, stage = stage)
            ?: requestPrefix(media, withRange = false, stage = stage)

    private fun resolveUrl(base: String, value: String): String? = runCatching {
        URI(base).resolve(value.trim()).toString()
    }.getOrNull()?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }

    private fun childContext(parent: ResolvedWebMedia, url: String): ResolvedWebMedia = parent.copy(
        url = url,
        // An explicit Cookie header has no domain metadata. Do not send it to
        // unrelated segment/key hosts. NiceHttp's cookie jar keeps normal scope.
        headers = if (host(parent.url) == host(url)) parent.headers else parent.headers.filterKeys {
            !it.equals("Cookie", true) && !it.equals("Authorization", true)
        },
    )

    private suspend fun checkResource(media: ResolvedWebMedia, stage: String): Boolean {
        val (response, bytes) = probe(media, stage) ?: return false
        val text = textPrefix(bytes)
        val textual = bytes.take(64).all { (it.toInt() and 0xff) in 32..126 || it.toInt() in listOf(9, 10, 13) }
        val valid = bytes.isNotEmpty() && (!textual || !clearlyErrorText(text)) &&
            (stage != "clé" || bytes.size == 16) &&
            contentType(response) !in setOf("text/html", "application/json")
        if (!valid) Log.w(TAG, "Probe rejeté stage=$stage host=${host(media.url)} raison=contenu")
        return valid
    }

    private suspend fun checkHls(
        media: ResolvedWebMedia,
        text: String,
        depth: Int = 0,
    ): Boolean {
        if (depth > 2) return false
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        // A successful master playlist alone says nothing about the HTTP
        // status of its child playlist, encryption key, init or first segment.
        val variantIndex = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
        if (variantIndex >= 0) {
            val relative = lines.drop(variantIndex + 1).firstOrNull { !it.startsWith("#") }
                ?: return false
            val url = resolveUrl(media.url, relative) ?: return false
            val child = childContext(media, url)
            val (response, bytes) = probe(child, "playlist") ?: return false
            val childText = textPrefix(bytes)
            if (!childText.startsWith("#EXTM3U")) return false
            return checkHls(child.copy(url = response.url), childText, depth + 1)
        }

        // Only resources needed by the first segment belong to this sample.
        val firstSegment = lines.indexOfFirst { !it.startsWith("#") }
        if (firstSegment < 0) return false
        val beforeSegment = lines.take(firstSegment)
        val uriAttribute = Regex("""(?:^|[:,])URI="([^"]+)"""")
        val key = beforeSegment.lastOrNull { it.startsWith("#EXT-X-KEY:") }
            ?.takeIf { it.contains("METHOD=AES-128") }
        val init = beforeSegment.lastOrNull { it.startsWith("#EXT-X-MAP:") }
        for ((stage, line) in listOf("clé" to key, "init" to init)) {
            if (line == null) continue
            val relative = uriAttribute.find(line)?.groupValues?.get(1) ?: return false
            val url = resolveUrl(media.url, relative) ?: return false
            if (!checkResource(childContext(media, url), stage)) return false
        }
        val segmentUrl = resolveUrl(media.url, lines[firstSegment]) ?: return false
        return checkResource(childContext(media, segmentUrl), "segment")
    }

    suspend fun validate(media: ResolvedWebMedia): ResolvedWebMedia? =
        withTimeoutOrNull(25_000L) { validateInternal(playbackContext(media)) }

    private suspend fun validateInternal(media: ResolvedWebMedia): ResolvedWebMedia? {
        if (!media.url.startsWith("http://", true) && !media.url.startsWith("https://", true)) {
            return null
        }

        val (response, prefix) = probe(media, "média") ?: return null
        val checked = childContext(media, response.url)

        val type = contentType(response)
        val text = textPrefix(prefix)

        if (text.startsWith("#EXTM3U")) {
            if (!checkHls(checked, text)) {
                Log.w(TAG, "HLS rejeté après contrôle playlist/segment host=${host(media.url)}")
                return null
            }
            return checked.copy(type = "m3u8")
        }

        if (text.contains("<MPD", ignoreCase = true)) {
            return checked.copy(type = "mpd")
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
            return checked.copy(type = "video")
        }

        // application/octet-stream alone is intentionally NOT enough. Hosts
        // often return textual error payloads with this MIME type; require a
        // recognized container.
        return null
    }

    fun safeHost(url: String): String = host(url)
}
