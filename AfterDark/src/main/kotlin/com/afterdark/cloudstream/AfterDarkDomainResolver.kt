package com.afterdark.cloudstream

import android.content.SharedPreferences
import android.util.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

internal class AfterDarkDomainResolver(
    private val preferences: SharedPreferences,
) {
    private val resolutionMutex = Mutex()

    @Volatile
    private var cachedOrigin: String? = null

    suspend fun resolve(): String {
        cachedOrigin?.let { return it }

        return resolutionMutex.withLock {
            cachedOrigin?.let { return@withLock it }

            readPersistedOrigin()?.let { persistedOrigin ->
                validateCandidate(persistedOrigin)?.let { resolved ->
                    Log.i(TAG, "Domaine AfterDark validé depuis le cache : $resolved")
                    remember(resolved)
                    return@withLock resolved
                }

                Log.w(TAG, "Domaine AfterDark mémorisé devenu invalide")
                clearPersistedOrigin()
            }

            var lastHttpCode: Int? = null
            var lastFailure: Throwable? = null

            for (attempt in 1..SOURCE_ATTEMPTS) {
                val responseResult = runCatching {
                    Log.i(TAG, "Résolution AfterDark via Cronet : tentative $attempt")
                    AfterDarkCronetClient.get(
                        url = SOURCE_URL,
                        headers = SOURCE_HEADERS,
                        timeoutMs = SOURCE_TIMEOUT_MS,
                    )
                }
                lastFailure = responseResult.exceptionOrNull() ?: lastFailure
                val response = responseResult.getOrNull()

                if (response != null) {
                    lastHttpCode = response.statusCode
                    Log.i(
                        TAG,
                        "Registre reçu par Cronet en ${response.negotiatedProtocol}; " +
                            "HTTP ${response.statusCode}",
                    )

                    if (response.statusCode in 200..299) {
                        extractCurrentOrigin(response.text)?.let { candidate ->
                            validateCandidate(candidate)?.let { resolved ->
                                remember(resolved)
                                return@withLock resolved
                            }
                            lastFailure = IllegalStateException(
                                "Le domaine publié par le registre n'est pas un site AfterDark utilisable",
                            )
                        }
                    }
                }

                if (attempt < SOURCE_ATTEMPTS) delay(SOURCE_RETRY_DELAY_MS)
            }

            val detail = lastHttpCode?.let { " (HTTP $it)" }.orEmpty()
            val cause = lastFailure
                ?.message
                ?.takeIf { it.isNotBlank() }
                ?.let { " : $it" }
                .orEmpty()
            throw ErrorLoadingException(
                "Impossible de récupérer l'adresse actuelle d'AfterDark " +
                    "avec Cronet/ECH$detail$cause",
            )
        }
    }

    private fun readPersistedOrigin(): String? {
        val rawValue = preferences.getString(PREFERENCE_LAST_ORIGIN, null)
            ?: return null
        val origin = normalizeOrigin(rawValue)
        if (origin == null) clearPersistedOrigin()
        return origin
    }

    private fun remember(origin: String) {
        cachedOrigin = origin
        preferences.edit()
            .putString(PREFERENCE_LAST_ORIGIN, origin)
            .commit()
    }

    private fun clearPersistedOrigin() {
        cachedOrigin = null
        preferences.edit()
            .remove(PREFERENCE_LAST_ORIGIN)
            .commit()
    }

    private suspend fun validateCandidate(candidateOrigin: String): String? {
        val normalizedCandidate = normalizeOrigin(candidateOrigin) ?: return null
        if (isSourceOrigin(normalizedCandidate)) return null

        val response = runCatching {
            AfterDarkCronetClient.get(
                url = "$normalizedCandidate/",
                headers = SOURCE_HEADERS,
                timeoutMs = VALIDATION_TIMEOUT_MS,
            )
        }.onFailure { error ->
            Log.w(TAG, "Validation Cronet impossible pour $normalizedCandidate", error)
        }.getOrNull() ?: return null

        if (response.statusCode !in 200..299) return null

        val finalOrigin = normalizeOrigin(response.finalUrl) ?: return null
        if (isSourceOrigin(finalOrigin)) return null

        val page = response.text
        if (REJECTED_DIRECTORY_MARKERS.any { page.contains(it, ignoreCase = true) }) {
            Log.w(TAG, "Page annuaire refusée comme domaine AfterDark : $finalOrigin")
            return null
        }
        if (!page.contains("AfterDark", ignoreCase = true)) return null

        Log.i(
            TAG,
            "Domaine AfterDark utilisable : $finalOrigin " +
                "(Cronet ${response.negotiatedProtocol}, HTTP ${response.statusCode})",
        )
        return finalOrigin
    }

    private fun extractCurrentOrigin(html: String): String? {
        if (!html.contains("Afterdark", ignoreCase = true)) return null

        val tag = ANCHOR_TAG.findAll(html)
            .map { it.value }
            .firstOrNull { anchor ->
                anchor.contains("Ouvrir le site", ignoreCase = true) &&
                    anchor.contains("href", ignoreCase = true)
            }
            ?: return null

        val rawUrl = HREF.find(tag)?.groupValues?.getOrNull(2) ?: return null
        return normalizeOrigin(rawUrl)
            ?.takeUnless(::isSourceOrigin)
    }

    private fun normalizeOrigin(rawValue: String): String? {
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

    private fun isSourceOrigin(origin: String): Boolean {
        val host = runCatching { URI(origin).host }.getOrNull() ?: return true
        return host.removePrefix("www.").equals(SOURCE_HOST, ignoreCase = true)
    }

    internal companion object {
        const val PREFERENCES_NAME = "afterdark_domains"
        const val PREFERENCE_LAST_ORIGIN = "last_valid_origin"

        const val SOURCE_ORIGIN = "https://cherishmylove.space"
        const val SOURCE_URL = "$SOURCE_ORIGIN/"
        private const val SOURCE_HOST = "cherishmylove.space"

        const val SOURCE_ATTEMPTS = 2
        const val SOURCE_TIMEOUT_MS = 20_000L
        const val VALIDATION_TIMEOUT_MS = 20_000L
        const val SOURCE_RETRY_DELAY_MS = 1_000L
        const val TAG = "AfterDarkCronet"

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
        val ANCHOR_TAG = Regex(
            "<a\\b[^>]*>.*?</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val HREF = Regex(
            "\\bhref\\s*=\\s*([\"'])(.*?)\\1",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    }
}
