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
                Log.i(TAG, "Domaine AfterDark obtenu par HTTP classique : $origin")
                return@withLock origin
            }

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

        val candidate = extractCurrentOrigin(source.text) ?: return null
        return validateWithClassicHttp(candidate)
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
        if (!isUsableTargetPage(response.text)) return null

        return finalOrigin
    }

    internal companion object {
        const val LEGACY_PREFERENCES_NAME = "afterdark_domains"
        const val SOURCE_ORIGIN = "https://cherishmylove.space"
        const val SOURCE_URL = "$SOURCE_ORIGIN/"
        const val SOURCE_HOST = "cherishmylove.space"
        const val CLASSIC_TIMEOUT_SECONDS = 15L
        const val TAG = "AfterDarkResolver"

        val SOURCE_HEADERS = mapOf(
            "User-Agent" to (
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/149.0 Mobile Safari/537.36"
                ),
            "Accept" to "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "fr-FR,fr;q=0.9,en;q=0.7",
        )

        val REJECTED_DIRECTORY_MARKERS = listOf(
            "Nouvelle adresse",
            "Ouvrir le site",
        )
        val REJECTED_MISSING_PAGE_MARKERS = listOf(
            "404 Not Found",
            "Page not found",
            "Page introuvable",
            "Cette page n'existe pas",
        )
        val CLOUDFLARE_CHALLENGE_MARKERS = listOf(
            "Just a moment",
            "Checking your browser",
            "cf-chl-",
            "challenge-platform",
        )
        val ANCHOR_TAG = Regex(
            "<a\\b[^>]*>.*?</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val HREF = Regex(
            "\\bhref\\s*=\\s*([\"'])(.*?)\\1",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        fun extractCurrentOrigin(html: String): String? {
            if (!html.contains("Afterdark", ignoreCase = true)) return null

            val tag = ANCHOR_TAG.findAll(html)
                .map { it.value }
                .firstOrNull { anchor ->
                    anchor.contains("Ouvrir le site", ignoreCase = true) &&
                        anchor.contains("href", ignoreCase = true)
                }
                ?: return null

            val rawUrl = HREF.find(tag)?.groupValues?.getOrNull(2) ?: return null
            return normalizeOrigin(rawUrl)?.takeUnless(::isSourceOrigin)
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

        fun isUsableTargetPage(page: String): Boolean {
            if (page.isBlank()) return false
            if (REJECTED_DIRECTORY_MARKERS.any { page.contains(it, ignoreCase = true) }) {
                return false
            }
            if (REJECTED_MISSING_PAGE_MARKERS.any { page.contains(it, ignoreCase = true) }) {
                return false
            }
            if (CLOUDFLARE_CHALLENGE_MARKERS.any { page.contains(it, ignoreCase = true) }) {
                return false
            }
            return true
        }
    }
}
