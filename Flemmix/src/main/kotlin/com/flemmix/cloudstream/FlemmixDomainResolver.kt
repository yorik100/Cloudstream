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

    suspend fun resolve(): String {
        cachedOrigin?.let { return it }

        return resolutionMutex.withLock {
            cachedOrigin?.let { return@withLock it }

            resolveFromRegistry()?.let { resolved ->
                cachedOrigin = resolved
                Log.i(TAG, "Domaine Flemmix/Wiflix obtenu depuis la page d'annonce : $resolved")
                return@withLock resolved
            }

            Log.w(TAG, "Page d'annonce indisponible ou invalide, essai de KeepLink3.txt")
            resolveFromKeepLink()?.let { resolved ->
                cachedOrigin = resolved
                Log.i(TAG, "Domaine Flemmix/Wiflix obtenu depuis KeepLink3.txt : $resolved")
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

        val candidate = extractRegistryTarget(response.text) ?: return null
        return validateCandidate(candidate)
    }

    private fun extractRegistryTarget(html: String): String? {
        for (anchor in ANCHOR_REGEX.findAll(html)) {
            val label = normalizeText(TAG_REGEX.replace(anchor.groupValues[2], " "))
            if (ACCESS_NOW_MARKERS.none(label::contains)) continue

            normalizeOrigin(decodeHtml(anchor.groupValues[1]))?.let { candidate ->
                if (candidate != REGISTRY_ORIGIN) return candidate
            }
        }

        for (match in WINDOW_OPEN_REGEX.findAll(decodeHtml(html))) {
            val candidate = normalizeOrigin(match.groupValues[1]) ?: continue
            if (candidate != REGISTRY_ORIGIN) return candidate
        }

        return null
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
            Log.w(TAG, "Lecture de KeepLink3.txt impossible", error)
        }.getOrNull() ?: return null

        if (response.okhttpResponse.code !in 200..299) return null

        val candidate = response.text.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull(::normalizeOrigin)
            .firstOrNull()
            ?: return null

        return validateCandidate(candidate)
    }

    private suspend fun validateCandidate(candidate: String): String? {
        val response = runCatching {
            app.get(
                url = "$candidate/film-en-streaming/",
                headers = requestHeaders,
                referer = "$candidate/",
                cacheTime = 0,
                timeout = VALIDATION_TIMEOUT_SECONDS,
            )
        }.getOrNull() ?: return null

        if (response.okhttpResponse.code !in 200..299) return null

        val finalUrl = response.okhttpResponse.request.url
        val finalOrigin = normalizeOrigin(
            "${finalUrl.scheme}://${finalUrl.host}" +
                if (finalUrl.port != 443) ":${finalUrl.port}" else "",
        ) ?: return null

        val catalogueLinks = DETAIL_LINK_REGEX.findAll(response.text)
            .map { it.groupValues[1] }
            .distinct()
            .take(MINIMUM_CATALOGUE_LINKS)
            .count()

        return finalOrigin.takeIf { catalogueLinks >= MINIMUM_CATALOGUE_LINKS }
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
            "$KEEP_LINK_ORIGIN/yorik100/Cloudstream/refs/heads/main/KeepLink3.txt"

        const val RESOLVER_TIMEOUT_SECONDS = 10L
        const val VALIDATION_TIMEOUT_SECONDS = 12L
        const val MINIMUM_CATALOGUE_LINKS = 3

        val HOST_REGEX = Regex(
            "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$",
            RegexOption.IGNORE_CASE,
        )
        val ANCHOR_REGEX = Regex(
            """<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val WINDOW_OPEN_REGEX = Regex(
            """window\.open\(\s*["'](https://[^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        val DETAIL_LINK_REGEX = Regex(
            """href\s*=\s*["'](?:https://[^/"']+)?/(?:film|serie)-en-streaming/(\d+-[^"']+\.html)["']""",
            RegexOption.IGNORE_CASE,
        )
        val TAG_REGEX = Regex("""<[^>]+>""")
        val ACCESS_NOW_MARKERS = listOf(
            "acceder maintenant",
            "acceder au site",
            "ouvrir le site",
            "continuer vers le site",
        )
    }
}
