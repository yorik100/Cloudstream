package com.afterdark.cloudstream

import android.util.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

internal class AfterDarkDomainResolver {
    private val resolutionMutex = Mutex()

    @Volatile
    private var cachedOrigin: String? = null

    /**
     * Resolves the current AfterDark origin with the regular CloudStream HTTP
     * client. Only a successful resolution is cached, and only in RAM.
     *
     * A failed attempt is deliberately not remembered: the next home-page or
     * search request must try again.
     */
    suspend fun resolve(): String? {
        cachedOrigin?.let { return it }

        return resolutionMutex.withLock {
            cachedOrigin?.let { return@withLock it }

            resolveWithClassicHttp()?.let { origin ->
                cachedOrigin = origin
                Log.i(TAG, "Domaine AfterDark obtenu depuis cherishmylove.space : $origin")
                return@withLock origin
            }

            Log.w(
                TAG,
                "cherishmylove.space invalide ou indisponible, essai de KeepLinkAfterDark.txt",
            )
            resolveFromKeepLink()?.let { origin ->
                cachedOrigin = origin
                Log.i(TAG, "Domaine AfterDark obtenu depuis KeepLinkAfterDark.txt : $origin")
                return@withLock origin
            }

            Log.w(TAG, "KeepLinkAfterDark.txt invalide ou indisponible")
            null
        }
    }

    private suspend fun resolveWithClassicHttp(): String? {
        val source = runCatching {
            app.get(
                url = SOURCE_URL,
                headers = SOURCE_HEADERS,
                cacheTime = 0,
                timeout = CLASSIC_TIMEOUT_SECONDS,
            )
        }.onFailure { error ->
            Log.w(TAG, "Échec du registre avec le client HTTP classique", error)
        }.getOrNull() ?: return null

        if (source.okhttpResponse.code !in 200..299) {
            Log.w(TAG, "Registre AfterDark : HTTP ${source.okhttpResponse.code}")
            return null
        }

        // Prefer the published access link, but do not require a specific
        // heading, phrase or HTML template from the announcement page.
        for (candidate in extractCurrentOrigins(source.text)) {
            validateWithClassicHttp(candidate)?.let { return it }
        }
        return null
    }

    /**
     * Fallback equivalent to Frembed's KeepLink path: read the first valid
     * HTTPS origin published in KeepLinkAfterDark.txt, then validate the actual site
     * before accepting or caching it. Nothing is persisted on the device.
     */
    private suspend fun resolveFromKeepLink(): String? {
        val response = runCatching {
            app.get(
                url = KEEP_LINK_URL,
                headers = mapOf(
                    "Accept" to "text/plain",
                    "User-Agent" to SOURCE_HEADERS["User-Agent"].orEmpty(),
                ),
                cacheTime = 0,
                timeout = KEEP_LINK_TIMEOUT_SECONDS,
            )
        }.onFailure { error ->
            Log.w(TAG, "Lecture de KeepLinkAfterDark.txt impossible", error)
        }.getOrNull() ?: return null

        if (response.okhttpResponse.code !in 200..299) return null

        for (candidate in response.text.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull(::normalizeOrigin)
            .distinct()
        ) {
            validateWithClassicHttp(candidate)?.let { return it }
        }
        return null
    }

    private suspend fun validateWithClassicHttp(candidateOrigin: String): String? {
        val normalizedCandidate = normalizeOrigin(candidateOrigin) ?: return null
        if (isSourceOrigin(normalizedCandidate)) return null

        val response = runCatching {
            app.get(
                url = "$normalizedCandidate/",
                headers = SOURCE_HEADERS,
                cacheTime = 0,
                timeout = CLASSIC_TIMEOUT_SECONDS,
            )
        }.onFailure { error ->
            Log.w(TAG, "Validation HTTP classique impossible pour $normalizedCandidate", error)
        }.getOrNull() ?: return null

        if (response.okhttpResponse.code !in 200..299) return null

        val finalOrigin = response.okhttpResponse.request.url.let { url ->
            val scheme = url.scheme
            val host = url.host.lowercase().removePrefix("www.")
            if (scheme != "https" || host.isBlank()) return null
            if (url.port != 443) return null
            "https://$host"
        }
        if (isSourceOrigin(finalOrigin)) return null

        return finalOrigin
    }

    internal companion object {
        const val LEGACY_PREFERENCES_NAME = "afterdark_domains"
        const val SOURCE_ORIGIN = "https://cherishmylove.space"
        const val SOURCE_URL = "$SOURCE_ORIGIN/"
        const val SOURCE_HOST = "cherishmylove.space"
        const val KEEP_LINK_ORIGIN = "https://raw.githubusercontent.com"
        const val KEEP_LINK_URL =
            "$KEEP_LINK_ORIGIN/yorik100/Cloudstream/refs/heads/main/KeepLinkAfterDark.txt"
        const val CLASSIC_TIMEOUT_SECONDS = 15L
        const val KEEP_LINK_TIMEOUT_SECONDS = 8L
        const val TAG = "AfterDarkResolver"

        val SOURCE_HEADERS = mapOf(
            "User-Agent" to (
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/149.0 Mobile Safari/537.36"
                ),
            "Accept" to "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "fr-FR,fr;q=0.9,en;q=0.7",
        )

        val ANCHOR_TAG = Regex(
            "<a\\b[^>]*>.*?</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val HREF = Regex(
            "\\bhref\\s*=\\s*([\"'])(.*?)\\1",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        fun extractCurrentOrigins(html: String): List<String> {
            // The announcement's wording and CSS may change. Collect HTTPS
            // links generically and validate the destination over HTTP.
            val anchors = ANCHOR_TAG.findAll(html)
                .mapNotNull { tag ->
                    val href = HREF.find(tag.value)?.groupValues?.getOrNull(2)
                    val origin = href?.let(::normalizeOrigin)
                        ?.takeUnless(::isSourceOrigin)
                    origin?.let { value ->
                        // Existing call-to-action is a preference, not a gate.
                        val preferred = tag.value.contains(
                            "Ouvrir le site", ignoreCase = true,
                        )
                        preferred to value
                    }
                }
                .toList()
            return anchors.sortedByDescending { it.first }
                .map { it.second }
                .distinct()
        }

        fun normalizeOrigin(rawValue: String): String? {
            val uri = runCatching { URI(rawValue.trim()) }.getOrNull() ?: return null
            val host = uri.host
                ?.lowercase()
                ?.removePrefix("www.")
                ?.takeIf { it.isNotBlank() }
                ?: return null

            if (uri.scheme != "https") return null
            if (uri.userInfo != null) return null
            if (uri.port != -1 && uri.port != 443) return null

            return "https://$host"
        }

        fun isSourceOrigin(origin: String): Boolean {
            val host = runCatching { URI(origin).host }.getOrNull() ?: return true
            return host.removePrefix("www.").equals(SOURCE_HOST, ignoreCase = true)
        }

    }
}
