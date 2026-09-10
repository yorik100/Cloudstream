package com.xalaflix.cloudstream

import android.util.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import java.net.URI

internal class XalaflixDomainResolver(
    private val requestHeaders: Map<String, String>,
) {
    private val mutex = Mutex()

    @Volatile
    private var cachedOrigin: String? = null

    fun resolvedOriginOrNull(): String? = cachedOrigin

    suspend fun resolve(): String {
        cachedOrigin?.let { return it }
        return mutex.withLock {
            cachedOrigin?.let { return@withLock it }

            resolveFromRegistry()?.let {
                cachedOrigin = it
                Log.i(TAG, "Domaine Xalaflix obtenu depuis la page d'annonce : $it")
                return@withLock it
            }
            Log.w(TAG, "Page d'annonce indisponible ou invalide, essai de KeepLinkXalaflix.txt")
            resolveFromKeepLink()?.let {
                cachedOrigin = it
                Log.i(TAG, "Domaine Xalaflix obtenu depuis KeepLinkXalaflix.txt : $it")
                return@withLock it
            }
            throw ErrorLoadingException("Aucun domaine Xalaflix utilisable")
        }
    }

    suspend fun invalidate(origin: String) = mutex.withLock {
        if (cachedOrigin == origin) cachedOrigin = null
    }

    private suspend fun resolveFromRegistry(): String? {
        val response = runCatching {
            app.get(REGISTRY_URL, headers = requestHeaders, cacheTime = 0, timeout = 10L)
        }.getOrNull() ?: return null
        if (response.okhttpResponse.code !in 200..299) return null

        val document = Jsoup.parse(response.text, REGISTRY_URL)
        val marked = document.select("a[href]").firstOrNull { anchor ->
            val label = anchor.text().lowercase()
            ACCESS_MARKERS.any(label::contains)
        }?.absUrl("href")
        val candidates = (sequenceOf(marked) +
            document.select("a[href]").asSequence().map { it.absUrl("href") })
            .mapNotNull(::normalizeOrigin)
            .filter { it != REGISTRY_ORIGIN }
            .distinct()

        for (candidate in candidates) {
            validate(candidate)?.let { return it }
        }
        return null
    }

    private suspend fun resolveFromKeepLink(): String? {
        val response = runCatching {
            app.get(
                KEEP_LINK_URL,
                headers = mapOf("Accept" to "text/plain", "User-Agent" to requestHeaders["User-Agent"].orEmpty()),
                cacheTime = 0,
                timeout = 10L,
            )
        }.getOrNull() ?: return null
        if (response.okhttpResponse.code !in 200..299) return null
        for (candidate in response.text.lineSequence().mapNotNull(::normalizeOrigin).distinct()) {
            validate(candidate)?.let { return it }
        }
        return null
    }

    private suspend fun validate(candidate: String): String? {
        val response = runCatching {
            app.get("$candidate/movies", headers = requestHeaders, referer = "$candidate/", cacheTime = 0, timeout = 12L)
        }.getOrNull() ?: return null
        if (response.okhttpResponse.code !in 200..299) return null
        val finalUrl = response.okhttpResponse.request.url
        val origin = normalizeOrigin("${finalUrl.scheme}://${finalUrl.host}") ?: return null
        val count = Jsoup.parse(response.text, "$origin/")
            .select("a[href*=/movie/], a[href*=/tv-show/]").map { it.absUrl("href") }.distinct().size
        return origin.takeIf { count >= 3 }
    }

    private fun normalizeOrigin(value: String?): String? {
        val uri = runCatching { URI(value?.trim().orEmpty()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.scheme?.lowercase() != "https" || uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return null
        if (!HOST_REGEX.matches(host)) return null
        return "https://$host"
    }

    companion object {
        const val TAG = "XalaflixResolver"
        const val REGISTRY_ORIGIN = "https://xalaflix.online"
        const val REGISTRY_URL = "$REGISTRY_ORIGIN/"
        const val KEEP_LINK_ORIGIN = "https://raw.githubusercontent.com"
        const val KEEP_LINK_URL =
            "$KEEP_LINK_ORIGIN/yorik100/Cloudstream/refs/heads/main/KeepLinkXalaflix.txt"
        private val ACCESS_MARKERS = listOf("acceder a la page d'accueil", "accéder à la page d'accueil", "ouvrir le site", "acceder au site")
        private val HOST_REGEX = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$", RegexOption.IGNORE_CASE)
    }
}
