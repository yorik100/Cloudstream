package com.afterdark.cloudstream

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.getAndUnpack
import java.net.URI

/**
 * Native-only resolver for embed players returned by AfterDark.
 *
 * No WebView is used here. The page is fetched over HTTP and its HTML/scripts
 * are inspected for the final media URL. HLS candidates are content-validated
 * before they are returned so CloudStream never receives an HTML/error page as
 * an M3U8 manifest.
 */
internal object AfterDarkNativeEmbedResolver {
    private const val TAG = "AfterDarkNativeEmbed"
    private const val PAGE_TIMEOUT_SECONDS = 20L
    private const val MEDIA_TIMEOUT_SECONDS = 12L

    private val explicitMediaUrl = Regex(
        """https?://[^\s"'<>\\]+\.(?:m3u8|mp4|m4v|webm)(?:\?[^\s"'<>\\]*)?""",
        RegexOption.IGNORE_CASE,
    )

    private val assignedHttpUrl = Regex(
        """(?:file|src|source|url)\s*[:=]\s*["'](https?://[^"'<>\s]+)["']""",
        setOf(RegexOption.IGNORE_CASE),
    )

    private val anyHttpUrl = Regex(
        """https?://[^\s"'<>\\]+""",
        RegexOption.IGNORE_CASE,
    )

    private val vidzyPayload = Regex("""\}\)\("([A-Za-z0-9+/=]+)"\)""")
    private val vidzyCode = Regex(
        """(?:/d/|/download/|/file/|/f/|/v/|/embed-)([a-z0-9]+)""",
        RegexOption.IGNORE_CASE,
    )

    private fun origin(url: String): String? = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme ?: return@runCatching null
        val host = uri.host ?: return@runCatching null
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        "$scheme://$host$port"
    }.getOrNull()

    private fun host(url: String): String = runCatching {
        URI(url).host.orEmpty().lowercase()
    }.getOrDefault("")

    private fun cleanUrl(value: String): String = value
        .replace("\\/", "/")
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .trim()
        .trimEnd(')', ']', '}', ',', ';')

    private fun isObviouslyAsset(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return path.endsWith(".js") ||
            path.endsWith(".css") ||
            path.endsWith(".png") ||
            path.endsWith(".jpg") ||
            path.endsWith(".jpeg") ||
            path.endsWith(".gif") ||
            path.endsWith(".svg") ||
            path.endsWith(".ico") ||
            path.endsWith(".woff") ||
            path.endsWith(".woff2") ||
            path.endsWith(".ttf") ||
            path.endsWith(".vtt") ||
            path.endsWith(".srt")
    }

    private fun pageCandidates(html: String): List<String> {
        val result = LinkedHashSet<String>()

        explicitMediaUrl.findAll(html).forEach { result += cleanUrl(it.value) }
        assignedHttpUrl.findAll(html).forEach { result += cleanUrl(it.groupValues[1]) }

        val unpacked = runCatching { getAndUnpack(html) }.getOrNull().orEmpty()
        if (unpacked.isNotBlank() && unpacked != html) {
            explicitMediaUrl.findAll(unpacked).forEach { result += cleanUrl(it.value) }
            assignedHttpUrl.findAll(unpacked).forEach { result += cleanUrl(it.groupValues[1]) }
        }

        return result.toList()
    }

    private fun scriptCandidates(html: String): List<String> {
        val result = LinkedHashSet<String>()
        val scripts = org.jsoup.Jsoup.parse(html).select("script")

        scripts.forEach { script ->
            val raw = script.data().ifBlank { script.html() }
            if (raw.isBlank()) return@forEach

            val contents = ArrayList<String>()
            contents += raw
            JsUnpacker(raw).takeIf { it.detect() }?.unpack()?.let(contents::add)

            contents.forEach { content ->
                explicitMediaUrl.findAll(content).forEach { result += cleanUrl(it.value) }
                assignedHttpUrl.findAll(content).forEach { result += cleanUrl(it.groupValues[1]) }

                // Some players assign an extensionless proxy URL directly to
                // a video/source variable. Keep only a small, filtered set of
                // arbitrary HTTP candidates and validate their body later.
                if (
                    content.contains("sources", ignoreCase = true) ||
                    content.contains("video", ignoreCase = true) ||
                    content.contains("file", ignoreCase = true)
                ) {
                    anyHttpUrl.findAll(content)
                        .map { cleanUrl(it.value) }
                        .filterNot(::isObviouslyAsset)
                        .take(20)
                        .forEach(result::add)
                }
            }
        }

        return result.toList()
    }

    private fun vidzyEmbedUrl(url: String): String {
        val currentOrigin = origin(url) ?: return url
        val code = vidzyCode.find(url)?.groupValues?.getOrNull(1) ?: return url
        return "$currentOrigin/embed-$code.html"
    }

    private fun decodeVidzy(html: String, embedUrl: String): String? {
        val script = org.jsoup.Jsoup.parse(html)
            .select("script")
            .asSequence()
            .map { it.data().ifBlank { it.html() } }
            .firstOrNull {
                it.contains("sources:", ignoreCase = true) &&
                    it.contains("m3u8", ignoreCase = true)
            }
            ?: return null

        val encoded = vidzyPayload.find(script)?.groupValues?.getOrNull(1) ?: return null
        val hostname = host(embedUrl).takeIf(String::isNotBlank) ?: return null

        var hostSum = 0
        hostname.forEach { char -> hostSum = (hostSum + char.code) and 0xFF }

        val decoded = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
            ?: return null
        val reversed = decoded.reversedArray()
        val result = StringBuilder(reversed.size)

        reversed.indices.forEach { index ->
            val key = (0x3d + index * 89 + hostSum) and 0xFF
            result.append(((reversed[index].toInt() and 0xFF) xor key).toChar())
        }

        return result.toString().takeIf { it.startsWith("http", ignoreCase = true) }
    }

    private fun responseHeaders(
        source: ParsedSource,
        userAgent: String,
        pageUrl: String,
        cookies: Map<String, String>,
    ): Map<String, String> {
        val headers = LinkedHashMap<String, String>(source.headers)
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers["User-Agent"] = userAgent
        }

        origin(pageUrl)?.let { pageOrigin ->
            if (headers.keys.none { it.equals("Origin", ignoreCase = true) }) {
                headers["Origin"] = pageOrigin
            }
        }

        if (cookies.isNotEmpty() && headers.keys.none { it.equals("Cookie", ignoreCase = true) }) {
            headers["Cookie"] = cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }
        }

        return headers
    }

    private suspend fun validateCandidate(
        candidate: String,
        pageUrl: String,
        headers: Map<String, String>,
    ): ResolvedWebMedia? {
        if (!candidate.startsWith("http://", true) && !candidate.startsWith("https://", true)) {
            return null
        }
        if (isObviouslyAsset(candidate)) return null

        val hintedType = runCatching { URI(candidate).path.orEmpty().lowercase() }
            .getOrDefault("")
            .let { path ->
                when {
                    path.endsWith(".m3u8") -> "m3u8"
                    path.endsWith(".mpd") -> "mpd"
                    path.endsWith(".mp4") ||
                        path.endsWith(".m4v") ||
                        path.endsWith(".webm") ||
                        path.endsWith(".mkv") -> "video"
                    else -> null
                }
            }

        return AfterDarkMediaProbe.validate(
            ResolvedWebMedia(
                url = candidate,
                referer = pageUrl,
                headers = headers,
                type = hintedType,
            ),
        )
    }

    suspend fun resolve(
        source: ParsedSource,
        referer: String,
        userAgent: String,
    ): ResolvedWebMedia? {
        val sourceHost = host(source.url)
        val sourceService = source.service.lowercase()
        val isUqload = "uqload" in sourceHost || "uqload" in sourceService
        val isVidzy = "vidzy" in sourceHost || "vidzy" in sourceService

        val pageUrl = if (isVidzy) vidzyEmbedUrl(source.url) else source.url
        val requestHeaders = LinkedHashMap<String, String>(source.headers)
        if (requestHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            requestHeaders["User-Agent"] = userAgent
        }

        val page = runCatching {
            app.get(
                url = pageUrl,
                headers = requestHeaders,
                referer = referer,
                cacheTime = 0,
                timeout = PAGE_TIMEOUT_SECONDS,
            )
        }.onFailure {
            Log.w(TAG, "Page embed inaccessible service=${source.service} host=$sourceHost", it)
        }.getOrNull() ?: return null

        if (page.okhttpResponse.code !in 200..299) return null

        val finalPageUrl = page.okhttpResponse.request.url.toString()
        val headers = responseHeaders(
            source = source,
            userAgent = userAgent,
            pageUrl = finalPageUrl,
            cookies = page.cookies,
        )

        val candidates = LinkedHashSet<String>()

        if (isVidzy) {
            decodeVidzy(page.text, finalPageUrl)?.let(candidates::add)
        }

        // Server-rendered media nodes also cover extensionless HLS proxies.
        page.document.select("video[src], source[src]").forEach { node ->
            node.absUrl("src").ifBlank { node.attr("src") }
                .takeIf(String::isNotBlank)
                ?.let { candidates += cleanUrl(it) }
        }

        candidates += pageCandidates(page.text)
        candidates += scriptCandidates(page.text)

        Log.i(
            TAG,
            "Résolution native service=${source.service} host=$sourceHost " +
                "uqload=$isUqload vidzy=$isVidzy candidats=${candidates.size}",
        )

        for (candidate in candidates.take(30)) {
            val resolved = validateCandidate(
                candidate = candidate,
                pageUrl = finalPageUrl,
                headers = headers,
            )
            if (resolved != null) {
                Log.i(
                    TAG,
                    "Média natif validé service=${source.service} host=$sourceHost type=${resolved.type}",
                )
                return resolved
            }
        }

        Log.w(
            TAG,
            "Aucun média natif validé service=${source.service} host=$sourceHost " +
                "candidats=${candidates.size}",
        )
        return null
    }
}
