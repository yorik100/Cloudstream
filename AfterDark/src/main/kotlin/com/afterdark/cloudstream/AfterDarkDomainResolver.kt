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

        // The announcement page can also contain Telegram, social links and
        // advertisements. Exactly ONE link is authoritative: the primary
        // "Ouvrir le site" (or "Ouvrir le lien") button. If it is broken,
        // resolve() must fall back to GitHub, never another announcement link.
        val publishedOrigin = extractCurrentOrigins(source.text).singleOrNull()
            ?: return null
        return validateWithClassicHttp(publishedOrigin)
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
        if (!looksLikeAfterDarkApplication(response.text)) {
            Log.w(TAG, "Domaine $normalizedCandidate : HTTP 2xx mais page AfterDark non reconnue")
            return null
        }

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

        private val CLASS_ATTRIBUTE = Regex(
            "\\bclass\\s*=\\s*([\"'])(.*?)\\1",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val HTML_TAG = Regex("<[^>]+>")
        private val WHITESPACE = Regex("\\s+")
        private val AFTERDARK_TITLE = Regex(
            "<title\\b[^>]*>\\s*Afterdark\\b",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Extract the ONE published origin from the primary CTA only.
         * In particular, this does not maintain a Telegram blocklist: links
         * other than that button are never candidates in the first place.
         */
        fun extractCurrentOrigins(html: String): List<String> {
            val primaryButton = ANCHOR_TAG.findAll(html).firstOrNull { match ->
                val anchor = match.value
                val opening = anchor.substringBefore('>')
                val classes = CLASS_ATTRIBUTE.find(opening)
                    ?.groupValues?.getOrNull(2)
                    ?.split(WHITESPACE)
                    ?.map { it.lowercase() }
                    .orEmpty()
                if ("btn" !in classes || "primary" !in classes) return@firstOrNull false

                val label = HTML_TAG.replace(anchor.substringAfter('>'), " ")
                    .replace("&nbsp;", " ", ignoreCase = true)
                    .replace("&#160;", " ", ignoreCase = true)
                    .trim()
                    .replace(WHITESPACE, " ")
                label.equals("Ouvrir le site", ignoreCase = true) ||
                    label.equals("Ouvrir le lien", ignoreCase = true)
            } ?: return emptyList()

            // Never try another anchor, even when this CTA is malformed.
            // A bad primary target means: try the independent GitHub fallback.
            val selected = HREF.find(primaryButton.value.substringBefore('>'))
                ?.groupValues?.getOrNull(2)
                ?.let(::normalizeOrigin)
                ?.takeUnless(::isSourceOrigin)
            return listOfNotNull(selected)
        }

        /**
         * HTTP 200 alone is not evidence of a working AfterDark frontend.
         * Reject expired landing sites, CDN placeholders and challenges that
         * return HTML 200, so the validated GitHub KeepLink can be tried.
         * This fingerprint matches the real AfterDark SPA, not the registry.
         */
        fun looksLikeAfterDarkApplication(html: String): Boolean {
            val branding = AFTERDARK_TITLE.containsMatchIn(html) ||
                (html.contains("og:site_name", ignoreCase = true) &&
                    html.contains("content=\"Afterdark\"", ignoreCase = true))
            val frontend = html.contains("/assets/index-", ignoreCase = true) &&
                html.contains("<script", ignoreCase = true)
            return branding && frontend
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
