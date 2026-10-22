package com.afterdark.cloudstream

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.getAndUnpack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    private val vidzyLegacyPayload = Regex("""\}\)\("([A-Za-z0-9+/=]+)"\)""")
    private val vidzyFunctionPayload = Regex(
        """src\s*:\s*\(function\([a-zA-Z]+\).*?\}\)\(["']([^"']+)["']\)""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val vidzyParentWidth = Regex(
        """width\s*:\s*calc\(\s*1in\s*\+\s*(\d+)px\s*\)""",
        RegexOption.IGNORE_CASE,
    )
    private val vidzyChildWidth = Regex(
        """width\s*:\s*(?:calc\(\s*)?(\d+(?:\.\d+)?)%\s*(?:([+-])\s*(\d+)px)?""",
        RegexOption.IGNORE_CASE,
    )
    private val vidzyExpectedBc = Regex(
        """if\s*\(\s*BC\s*!==\s*(\d+)\s*\)""",
        RegexOption.IGNORE_CASE,
    )
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
        if (AfterDarkSourcePolicy.isDecoy(url)) return true
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
        val code = vidzyCode.find(url)?.groupValues?.getOrNull(1)
            ?: Regex("""^/([a-zA-Z0-9]{12})(?:\.html)?$""")
                .find(runCatching { URI(url).path }.getOrNull().orEmpty())
                ?.groupValues?.get(1)
            ?: return url
        return "$currentOrigin/embed-$code.html"
    }

    private fun decodeVidzyPayload(
        encoded: String,
        hostname: String,
        bc: Int,
    ): String? {
        val decoded = runCatching {
            Base64.decode(
                encoded.padEnd((encoded.length + 3) / 4 * 4, '='),
                Base64.DEFAULT,
            )
        }.getOrNull() ?: return null

        val reversed = decoded.reversedArray()
        var hostSum = 0
        hostname.forEach { char ->
            hostSum = (hostSum + char.code) and 0xFF
        }

        val result = StringBuilder(reversed.size)
        reversed.indices.forEach { index ->
            val key = (0x3d + index * 89 + hostSum + bc) and 0xFF
            result.append(
                ((reversed[index].toInt() and 0xFF) xor key).toChar(),
            )
        }

        return result.toString()
            .takeIf {
                it.startsWith("http://", ignoreCase = true) ||
                    it.startsWith("https://", ignoreCase = true)
            }
            ?.takeIf { "/troll/" !in it.lowercase() }
            ?.takeIf { value ->
                value.all { char -> char.code in 33..126 }
            }
    }

    private fun vidzyBcCandidates(
        html: String,
        functionText: String,
    ): List<Int> {
        val candidates = LinkedHashSet<Int>()

        // Current Vidzy format derives BC from DOM geometry. The parent is
        // calc(1in + Npx), with CSS 1in = 96 px. The child width may be
        // 50%, calc(100% - 11px), etc. Browser layout rounds to integer
        // pixels, so try both floor and ceil.
        val extraPx = vidzyParentWidth.find(functionText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: vidzyParentWidth.find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: 156

        val parentWidth = 96 + extraPx

        vidzyExpectedBc.find(functionText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let(candidates::add)

        vidzyChildWidth.findAll(functionText).forEach { match ->
            val percent = match.groupValues.getOrNull(1)
                ?.toDoubleOrNull()
                ?: return@forEach
            val delta = match.groupValues.getOrNull(3)
                ?.toIntOrNull()
                ?: 0

            val base = parentWidth * percent / 100.0
            val measured = when (match.groupValues.getOrNull(2)) {
                "-" -> base - delta
                "+" -> base + delta
                else -> base
            }

            candidates += kotlin.math.floor(measured).toInt()
            candidates += kotlin.math.ceil(measured).toInt()
        }

        // Legacy Vidzy layout used a 50% child.
        candidates += parentWidth / 2
        candidates += (parentWidth + 1) / 2

        // Old decoder had no BC term at all. Keep it as last-resort
        // compatibility, but never accept Vidzy's /troll/ decoy.
        candidates += 0

        return candidates.toList()
    }

    private fun decodeVidzy(html: String, embedUrl: String): String? {
        val document = org.jsoup.Jsoup.parse(html)
        val scripts = document
            .select("script")
            .asSequence()
            .map { it.data().ifBlank { it.html() } }
            .filter(String::isNotBlank)
            .toList()

        val hostname = host(embedUrl).takeIf(String::isNotBlank) ?: return null
        val hostCandidates = linkedSetOf(
            hostname,
            "vidzy.cc",
            "vidzy.live",
            "vidzy.to",
            "vidzy.org",
        )

        // Current format (October 2026): src:(function(s){...})("<base64>")
        // with an XOR key that includes both the hostname sum and BC, a value
        // derived from CSS layout geometry.
        for (script in scripts) {
            val functionMatch = vidzyFunctionPayload.find(script) ?: continue
            val encoded = functionMatch.groupValues.getOrNull(1) ?: continue
            val functionText = functionMatch.value
            val bcCandidates = vidzyBcCandidates(html, functionText)

            for (hostCandidate in hostCandidates) {
                for (bc in bcCandidates) {
                    decodeVidzyPayload(
                        encoded = encoded,
                        hostname = hostCandidate,
                        bc = bc,
                    )?.let { decoded ->
                        Log.i(
                            TAG,
                            "Vidzy déchiffré format courant host=$hostCandidate bc=$bc",
                        )
                        return decoded
                    }
                }
            }

            Log.w(
                TAG,
                "Payload Vidzy courant trouvé mais aucune clé BC valide",
            )
        }

        // Compatibility with the previous Vidzy variant.
        val legacyScript = scripts.firstOrNull {
            it.contains("sources:", ignoreCase = true)
        }
        val legacyEncoded = legacyScript
            ?.let { vidzyLegacyPayload.find(it) }
            ?.groupValues
            ?.getOrNull(1)

        if (legacyEncoded != null) {
            for (hostCandidate in hostCandidates) {
                decodeVidzyPayload(
                    encoded = legacyEncoded,
                    hostname = hostCandidate,
                    bc = 0,
                )?.let { decoded ->
                    Log.i(
                        TAG,
                        "Vidzy déchiffré format legacy host=$hostCandidate",
                    )
                    return decoded
                }
            }
        }

        return null
    }

    private fun responseHeaders(
        source: ParsedSource,
        userAgent: String,
        pageUrl: String,
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

        return headers
    }

    private suspend fun validateCandidate(
        candidate: String,
        pageUrl: String,
        headers: Map<String, String>,
        cookies: List<Cookie>,
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

        val mediaHeaders = LinkedHashMap(headers)
        // Embed cookies must obey Domain/Path when passed to another CDN.
        // Do not copy a bare Cookie header from the page to an unrelated host.
        if (host(candidate) != host(pageUrl)) {
            mediaHeaders.keys.filter { it.equals("Cookie", true) || it.equals("Authorization", true) }
                .forEach(mediaHeaders::remove)
        }
        candidate.toHttpUrlOrNull()?.let { url ->
            val applicable = cookies.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
            if (applicable.isNotEmpty()) {
                val key = mediaHeaders.keys.firstOrNull { it.equals("Cookie", true) }
                val explicit = key?.let { mediaHeaders.remove(it) }.orEmpty()
                mediaHeaders["Cookie"] = listOf(explicit, applicable.joinToString("; ") { "${it.name}=${it.value}" })
                    .filter(String::isNotBlank).joinToString("; ")
            }
        }
        return AfterDarkMediaProbe.validate(
            ResolvedWebMedia(
                url = candidate,
                referer = pageUrl,
                headers = mediaHeaders,
                type = hintedType,
            ),
        )
    }

    private suspend fun fetchEmbedPage(
        url: String,
        referer: String,
        headers: Map<String, String>,
        retryForVidzy: Boolean,
    ): com.lagradost.nicehttp.NiceResponse? {
        var page = runCatching {
            app.get(
                url = url,
                headers = headers,
                referer = referer,
                cacheTime = 0,
                timeout = PAGE_TIMEOUT_SECONDS,
            )
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "Requête embed échouée host=${host(url)} erreur=${it.javaClass.simpleName}")
        }.getOrNull() ?: return null

        if (!retryForVidzy || page.okhttpResponse.code !in 200..299) {
            return page
        }

        fun looksUseful(html: String): Boolean =
            vidzyFunctionPayload.containsMatchIn(html) ||
                vidzyLegacyPayload.containsMatchIn(html) ||
                html.contains(".m3u8", ignoreCase = true) ||
                html.contains("sources:", ignoreCase = true)

        if (looksUseful(page.text)) return page

        // Vidzy can serve a cookie-setting landing page first. Retry once
        // with the cookies set by that response and a self referer.
        val retryHeaders = LinkedHashMap(headers)
        if (
            page.cookies.isNotEmpty() &&
            retryHeaders.keys.none { it.equals("Cookie", ignoreCase = true) }
        ) {
            retryHeaders["Cookie"] = page.cookies.entries.joinToString("; ") {
                (key, value) -> "$key=$value"
            }
        }

        page = runCatching {
            app.get(
                url = url,
                headers = retryHeaders,
                referer = url,
                cacheTime = 0,
                timeout = PAGE_TIMEOUT_SECONDS,
            )
        }.onFailure { if (it is CancellationException) throw it }.getOrDefault(page)

        return page
    }

    suspend fun resolve(
        source: ParsedSource,
        referer: String,
        userAgent: String,
    ): ResolvedWebMedia? = withTimeoutOrNull(30_000L) {
        resolveInternal(source, referer, userAgent)
    }

    private suspend fun resolveInternal(
        source: ParsedSource,
        referer: String,
        userAgent: String,
    ): ResolvedWebMedia? {
        val sourceHost = host(source.url)
        val sourceService = source.service.lowercase()
        val isUqload = "uqload" in sourceHost || "uqload" in sourceService
        val isVidzy = "vidzy" in sourceHost || "vidzy" in sourceService

        val pageUrl = if (isVidzy || isUqload) vidzyEmbedUrl(source.url) else source.url
        val requestHeaders = LinkedHashMap<String, String>(source.headers)
        if (requestHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            requestHeaders["User-Agent"] = userAgent
        }

        val page = runCatching {
            fetchEmbedPage(
                url = pageUrl,
                // The supplied AfterDark iframe has referrerpolicy=no-referrer.
                // This applies to the embed request, not the subsequent media.
                referer = if (isVidzy && source.referer == null) "" else referer,
                headers = requestHeaders,
                retryForVidzy = isVidzy,
            )
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "Page embed inaccessible service=${source.service} host=$sourceHost", it)
        }.getOrNull() ?: return null

        if (page.okhttpResponse.code !in 200..299) {
            Log.w(TAG, "Page embed refusée service=${source.service} host=$sourceHost status=${page.code}")
            page.body?.close()
            return null
        }

        val finalPageUrl = page.okhttpResponse.request.url.toString()
        val headers = responseHeaders(
            source = source,
            userAgent = userAgent,
            pageUrl = finalPageUrl,
        )
        val pageCookies = ArrayList<Cookie>()
        var cookieResponse: okhttp3.Response? = page.okhttpResponse
        while (cookieResponse != null) {
            pageCookies += Cookie.parseAll(cookieResponse.request.url, cookieResponse.headers)
            cookieResponse = cookieResponse.priorResponse
        }

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

        // Prefer a genuine direct container, when Vidzy offers one, over
        // an HLS master whose first segment works but later segments 403.
        // All candidates still pass the content gate. Never emit an embed URL.
        val orderedCandidates = if (isVidzy) {
            candidates.sortedBy { candidate ->
                if (runCatching { URI(candidate).path.orEmpty().lowercase() }.getOrDefault("")
                        .let { path -> listOf(".mp4", ".m4v", ".webm", ".mkv").any(path::endsWith) }) 0 else 1
            }
        } else candidates.toList()
        for (candidate in orderedCandidates.take(30)) {
            val resolved = validateCandidate(
                candidate = candidate,
                pageUrl = finalPageUrl,
                headers = headers,
                cookies = pageCookies,
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
