package com.flemmix.cloudstream

import android.util.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.text.Normalizer

internal class FlemmixDomainResolver(
    private val requestHeaders: Map<String, String>,
) {
    private val resolutionMutex = Mutex()

    @Volatile
    private var cachedOrigin: String? = null

    fun resolvedOriginOrNull(): String? = cachedOrigin

    suspend fun resolve(): String {
        cachedOrigin?.let { return it }

        return resolutionMutex.withLock {
            cachedOrigin?.let { return@withLock it }

            // Primary source: the live announcement page. GitHub is strictly
            // a fallback if that page cannot supply a usable origin.
            resolveFromRegistry()?.let { resolved ->
                cachedOrigin = resolved
                Log.i(TAG, "Domaine Flemmix/Wiflix obtenu depuis neufneuf.space : $resolved")
                return@withLock resolved
            }

            Log.w(TAG, "neufneuf.space indisponible ou sans domaine valide : recours à KeepLinkFlemmix.txt")
            resolveFromKeepLink()?.let { resolved ->
                cachedOrigin = resolved
                Log.i(TAG, "Domaine Flemmix/Wiflix obtenu depuis KeepLinkFlemmix.txt (secours) : $resolved")
                return@withLock resolved
            }

            throw ErrorLoadingException("Aucun domaine Flemmix/Wiflix utilisable")
        }
    }

    suspend fun invalidate(origin: String) {
        resolutionMutex.withLock {
            if (cachedOrigin == origin) cachedOrigin = null
        }
    }

    private suspend fun resolveFromRegistry(): String? {
        val response = runCatching {
            app.get(
                url = REGISTRY_URL,
                headers = requestHeaders,
                cacheTime = 0,
                timeout = RESOLVER_TIMEOUT_SECONDS,
            )
        }.onFailure { error ->
            Log.w(TAG, "Lecture de la page d'annonce impossible", error)
        }.getOrNull() ?: return null

        if (response.okhttpResponse.code !in 200..299) return null

        val candidates = extractRegistryTargets(response.text)
        for (candidate in candidates) {
            validateCandidate(candidate)?.let { return it }
        }
        return null
    }

    private fun extractRegistryTargets(html: String): List<String> {
        // Extract only the advertised primary link, never arbitrary
        // external links that might belong to other services.
        val preferred = runCatching {
            val label = SPAN_REGEX.findAll(html).firstOrNull { span ->
                normalizeText(TAG_REGEX.replace(span.groupValues[1], " ")) ==
                    PRIMARY_LABEL
            } ?: return@runCatching null
            val after = html.substring(label.range.last + 1)
            val link = ANCHOR_REGEX.find(after) ?: return@runCatching null
            normalizeOrigin(decodeHtml(link.groupValues[1]))
        }.getOrNull()

        // A registry can link to entirely different services. Never
        // validate every outgoing link as though it were Flemmix.
        return listOfNotNull(preferred)
            .filter { it != REGISTRY_ORIGIN }
    }

    private suspend fun resolveFromKeepLink(): String? {
        val response = runCatching {
            app.get(
                url = KEEP_LINK_URL,
                headers = mapOf(
                    "Accept" to "text/plain",
                    "User-Agent" to requestHeaders["User-Agent"].orEmpty(),
                ),
                cacheTime = 0,
                timeout = RESOLVER_TIMEOUT_SECONDS,
            )
        }.onFailure { error ->
            Log.w(TAG, "Lecture de KeepLinkFlemmix.txt impossible", error)
        }.getOrNull() ?: return null

        if (response.okhttpResponse.code !in 200..299) return null

        for (candidate in response.text.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull(::normalizeOrigin)
            .distinct()
        ) {
            validateCandidate(candidate)?.let { return it }
        }
        return null
    }

    private suspend fun validateCandidate(candidate: String): String? {
        // A working HTTPS response on the provider's catalogue route is
        // sufficient. CSS layout and number of cards are not domain checks.
        for (route in listOf("/film-en-streaming/")) {
            val response = runCatching {
                app.get(
                    url = candidate + route,
                    headers = requestHeaders,
                    referer = "$candidate/",
                    cacheTime = 0,
                    timeout = VALIDATION_TIMEOUT_SECONDS,
                )
            }.getOrNull() ?: continue

            if (response.okhttpResponse.code !in 200..299) continue
            val finalUrl = response.okhttpResponse.request.url
            val finalOrigin = normalizeOrigin(
                "${finalUrl.scheme}://${finalUrl.host}" +
                    if (finalUrl.port != 443) ":${finalUrl.port}" else "",
            ) ?: continue
            return finalOrigin
        }
        return null
    }

    private fun normalizeOrigin(rawValue: String): String? {
        val uri = runCatching { URI(rawValue.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null

        if (uri.scheme?.lowercase() != "https") return null
        if (uri.userInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        if (!HOST_REGEX.matches(host)) return null

        return "https://$host"
    }

    private fun normalizeText(value: String): String {
        val decomposed = Normalizer.normalize(decodeHtml(value), Normalizer.Form.NFKD)
        return decomposed
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun decodeHtml(value: String): String = value
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&apos;", "'", ignoreCase = true)

    internal companion object {
        const val TAG = "FlemmixResolver"
        const val REGISTRY_ORIGIN = "https://www.neufneuf.space"
        const val REGISTRY_URL = "$REGISTRY_ORIGIN/"
        const val KEEP_LINK_ORIGIN = "https://raw.githubusercontent.com"
        const val KEEP_LINK_URL =
            "$KEEP_LINK_ORIGIN/yorik100/Cloudstream/refs/heads/main/KeepLinkFlemmix.txt"

        const val RESOLVER_TIMEOUT_SECONDS = 10L
        const val VALIDATION_TIMEOUT_SECONDS = 12L
        const val PRIMARY_LABEL = "lien principal"

        val HOST_REGEX = Regex(
            "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$",
            RegexOption.IGNORE_CASE,
        )
        val SPAN_REGEX = Regex(
            """<span\b[^>]*>(.*?)</span>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val ANCHOR_REGEX = Regex(
            """<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val TAG_REGEX = Regex("""<[^>]+>""")
    }
}
