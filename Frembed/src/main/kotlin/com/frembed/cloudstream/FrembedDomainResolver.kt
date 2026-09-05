package com.frembed.cloudstream

import android.util.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal class FrembedDomainResolver(
    private val requestHeaders: Map<String, String>,
) {
    private val resolutionMutex = Mutex()

    @Volatile
    private var cachedOrigin: String? = null

    suspend fun resolve(): String {
        cachedOrigin?.let { return it }

        return resolutionMutex.withLock {
            cachedOrigin?.let { return@withLock it }

            resolveFromKeepLink()?.let { resolved ->
                cachedOrigin = resolved
                Log.i(TAG, "Domaine Frembed obtenu depuis KeepLink.txt : $resolved")
                return@withLock resolved
            }

            Log.w(TAG, "KeepLink.txt invalide ou indisponible, recours à crt.sh")
            val candidates = discoverCandidates()
            if (candidates.isEmpty()) {
                throw ErrorLoadingException(
                    "Aucun domaine Frembed trouvé dans les certificats publics",
                )
            }

            // Candidates are ordered by certificate acquisition date. A real
            // catalogue page is both the reachability and identity check.
            for (validationBatch in candidates.chunked(MAX_PARALLEL_PROBES)) {
                val results = coroutineScope {
                    validationBatch.map { candidate ->
                        async { validateCandidate(candidate) }
                    }.awaitAll()
                }

                // awaitAll preserves input order: a newer valid domain wins.
                results.firstOrNull { it != null }?.let { resolved ->
                    cachedOrigin = resolved
                    return@withLock resolved
                }
            }

            throw ErrorLoadingException(
                "Aucun domaine Frembed actuellement utilisable",
            )
        }
    }

    private fun normalizeOrigin(rawValue: String): String? {
        val uri = runCatching { URI(rawValue.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null

        if (uri.scheme != "https") return null
        if (uri.userInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        if (!FREMBED_DOMAIN.matches(host)) return null

        return "https://$host"
    }

    suspend fun invalidate(origin: String) {
        resolutionMutex.withLock {
            if (cachedOrigin == origin) cachedOrigin = null
        }
    }

    /**
     * Fast path: only GitHub's KeepLink.txt, the announced Frembed site and
     * that site's own public API are contacted. Nothing is persisted.
     */
    private suspend fun resolveFromKeepLink(): String? {
        val response = runCatching {
            app.get(
                url = KEEP_LINK_URL,
                headers = mapOf(
                    "Accept" to "text/plain",
                    "User-Agent" to requestHeaders["User-Agent"].orEmpty(),
                ),
                cacheTime = 0,
                timeout = KEEP_LINK_TIMEOUT_SECONDS,
            )
        }.onFailure { error ->
            Log.w(TAG, "Lecture de KeepLink.txt impossible", error)
        }.getOrNull() ?: return null

        if (response.okhttpResponse.code !in 200..299) return null

        val candidate = response.text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull(::normalizeOrigin)
            .firstOrNull()
            ?: return null

        val validatedOrigin = validateCandidate(candidate) ?: return null
        return validatedOrigin.takeIf { validatePublicApi(it) }
    }

    private suspend fun validatePublicApi(candidateOrigin: String): Boolean {
        val response = runCatching {
            app.get(
                url = "$candidateOrigin/api/public/v1/movies?limit=1&page=1",
                headers = requestHeaders + ("Accept" to "application/json"),
                referer = "$candidateOrigin/api-docs",
                cacheTime = 0,
                timeout = API_PROBE_TIMEOUT_SECONDS,
            )
        }.onFailure { error ->
            Log.w(TAG, "API Frembed inaccessible sur $candidateOrigin", error)
        }.getOrNull() ?: return false

        if (response.okhttpResponse.code !in 200..299) return false

        val finalOrigin = response.okhttpResponse.request.url.let { url ->
            val port = if (url.port != 443) ":${url.port}" else ""
            normalizeOrigin("${url.scheme}://${url.host}$port")
        } ?: return false
        if (finalOrigin != candidateOrigin) return false

        val payload = runCatching { JSONObject(response.text) }.getOrNull()
            ?: return false
        if (payload.optInt("status", 0) != 200) return false

        val items = payload.optJSONObject("result")
            ?.optJSONArray("items")
            ?: return false
        val firstItem = items.optJSONObject(0) ?: return false

        return firstItem.optString("title").isNotBlank() &&
            firstItem.optString("tmdb").isNotBlank()
    }

    private suspend fun discoverCandidates(): List<String> {
        var lastHttpCode: Int? = null

        for (attempt in 1..DISCOVERY_ATTEMPTS) {
            val response = runCatching {
                app.get(
                    url = DISCOVERY_URL,
                    headers = mapOf(
                        "Accept" to "application/json",
                        "User-Agent" to requestHeaders["User-Agent"].orEmpty(),
                    ),
                    cacheTime = 0,
                    timeout = DISCOVERY_TIMEOUT_SECONDS,
                )
            }.getOrNull()

            if (response != null) {
                lastHttpCode = response.okhttpResponse.code
                if (response.okhttpResponse.code in 200..299) {
                    runCatching { JSONArray(response.text) }
                        .getOrNull()
                        ?.let { return parseCandidates(it) }
                }
            }

            if (attempt < DISCOVERY_ATTEMPTS) delay(DISCOVERY_RETRY_DELAY_MS)
        }

        val detail = lastHttpCode?.let { " (HTTP $it)" }.orEmpty()
        throw ErrorLoadingException(
            "Impossible de consulter le registre des domaines Frembed$detail",
        )
    }

    private fun parseCandidates(certificates: JSONArray): List<String> {
        val newestCertificateByDomain = LinkedHashMap<String, String>()

        for (index in 0 until certificates.length()) {
            val certificate = certificates.optJSONObject(index) ?: continue
            // crt.sh's entry timestamp is the closest available signal for
            // when this Frembed hostname was newly obtained/activated. Fall
            // back to the certificate validity start on older responses.
            val acquiredAt = certificate.optString("entry_timestamp", "")
                .ifBlank { certificate.optString("not_before", "") }

            sequenceOf(
                certificate.optString("common_name", ""),
                certificate.optString("name_value", ""),
            ).flatMap { it.lineSequence() }
                .mapNotNull(::normalizeDomain)
                .forEach { domain ->
                    val previous = newestCertificateByDomain[domain]
                    if (previous == null || acquiredAt > previous) {
                        newestCertificateByDomain[domain] = acquiredAt
                    }
                }
        }

        return newestCertificateByDomain.entries
            .sortedByDescending { it.value }
            .map { "https://${it.key}" }
    }

    private fun normalizeDomain(rawValue: String): String? {
        var value = rawValue.trim().lowercase()
        if (value.startsWith("*.")) value = value.removePrefix("*.")
        if (value.startsWith("www.")) value = value.removePrefix("www.")

        return value.takeIf { FREMBED_DOMAIN.matches(it) }
    }

    private suspend fun validateCandidate(candidateOrigin: String): String? {
        val response = runCatching {
            app.get(
                url = "$candidateOrigin/movies",
                headers = requestHeaders,
                referer = "$candidateOrigin/",
                cacheTime = 0,
                timeout = PROBE_TIMEOUT_SECONDS,
            )
        }.getOrNull() ?: return null

        if (response.okhttpResponse.code !in 200..299) return null

        val finalOrigin = response.okhttpResponse.request.url.let { url ->
            val scheme = url.scheme
            val host = url.host.lowercase()
            if (scheme != "https" || !FREMBED_DOMAIN.matches(host)) return null
            "$scheme://$host" + if (url.port != 443) ":${url.port}" else ""
        }

        val html = response.text
        val plainText = TAG_REGEX.replace(html, " ")
        if (REJECTED_PAGE_MARKERS.any { plainText.contains(it, ignoreCase = true) }) {
            return null
        }

        // A usable domain exposes several genuine film detail links and a
        // pagination marker. This rejects blank/404/parked/address pages.
        val catalogueLinks = CATALOGUE_LINK_REGEX.findAll(html).take(3).count()
        return finalOrigin.takeIf {
            catalogueLinks >= 3 && PAGE_COUNT_REGEX.containsMatchIn(plainText)
        }
    }

    internal companion object {
        const val TAG = "FrembedResolver"
        const val KEEP_LINK_ORIGIN = "https://raw.githubusercontent.com"
        const val KEEP_LINK_URL =
            "$KEEP_LINK_ORIGIN/yorik100/Cloudstream/refs/heads/main/KeepLink.txt"

        const val DISCOVERY_ORIGIN = "https://crt.sh"
        const val DISCOVERY_URL =
            "$DISCOVERY_ORIGIN/?Identity=frembed.%25&output=json"

        const val DISCOVERY_ATTEMPTS = 2
        const val DISCOVERY_TIMEOUT_SECONDS = 25L
        const val DISCOVERY_RETRY_DELAY_MS = 1_000L
        const val KEEP_LINK_TIMEOUT_SECONDS = 8L
        const val API_PROBE_TIMEOUT_SECONDS = 8L
        const val PROBE_TIMEOUT_SECONDS = 8L
        const val MAX_PARALLEL_PROBES = 10

        val FREMBED_DOMAIN = Regex(
            "^frembed\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$",
            RegexOption.IGNORE_CASE,
        )
        val CATALOGUE_LINK_REGEX = Regex(
            """href\s*=\s*[\"'][^\"']*/movies/[^\"'/]+/\d+/?[\"']""",
            RegexOption.IGNORE_CASE,
        )
        val PAGE_COUNT_REGEX = Regex(
            """(?i)\bpage\s+\d+\s*/\s*\d+""",
        )
        val TAG_REGEX = Regex("""<[^>]+>""")
        val REJECTED_PAGE_MARKERS = listOf("Nouvelle adresse", "Ouvrir le site")
    }
}
