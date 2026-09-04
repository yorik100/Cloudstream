package com.afterdark.cloudstream

import com.lagradost.cloudstream3.ErrorLoadingException
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

internal class AfterDarkDomainResolver {
    private val resolutionMutex = Mutex()

    @Volatile
    private var cachedOrigin: String? = null

    suspend fun resolve(): String {
        cachedOrigin?.let { return it }

        return resolutionMutex.withLock {
            cachedOrigin?.let { return@withLock it }

            var lastHttpCode: Int? = null
            var lastFailure: Throwable? = null

            for (attempt in 1..SOURCE_ATTEMPTS) {
                val responseResult = runCatching {
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

                    if (response.statusCode in 200..299) {
                        extractCurrentOrigin(response.text)?.let { resolved ->
                            probeEncryptedSni(resolved)
                            cachedOrigin = resolved
                            return@withLock resolved
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

    private suspend fun probeEncryptedSni(origin: String) {
        val result = runCatching {
            AfterDarkCronetClient.get(
                url = "$origin/cdn-cgi/trace",
                headers = SOURCE_HEADERS,
                timeoutMs = ECH_PROBE_TIMEOUT_MS,
            )
        }.getOrNull()

        val encrypted = result
            ?.takeIf { it.statusCode in 200..299 }
            ?.text
            ?.lineSequence()
            ?.firstOrNull { it.startsWith("sni=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.equals("encrypted", ignoreCase = true)

        Log.i(
            TAG,
            "Cronet ${result?.negotiatedProtocol ?: "indisponible"}; " +
                "ECH=${when (encrypted) {
                    true -> "chiffré"
                    false -> "non chiffré"
                    null -> "non vérifiable"
                }}",
        )
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
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val sourceHost = URI(SOURCE_ORIGIN).host.removePrefix("www.")

        if (uri.scheme != "https") return null
        if (uri.userInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        if (host.equals(sourceHost, ignoreCase = true)) return null

        return "https://$host"
    }

    internal companion object {
        const val SOURCE_ORIGIN = "https://cherishmylove.space"
        const val SOURCE_URL = "$SOURCE_ORIGIN/"

        const val SOURCE_ATTEMPTS = 2
        const val SOURCE_TIMEOUT_MS = 15_000L
        const val ECH_PROBE_TIMEOUT_MS = 10_000L
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

        val ANCHOR_TAG = Regex("<a\\b[^>]*>.*?</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val HREF = Regex("\\bhref\\s*=\\s*([\"'])(.*?)\\1", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }
}
