package com.frembed.cloudstream

import android.content.SharedPreferences
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.net.URI

internal class FrembedDomainResolver(
    private val requestHeaders: Map<String, String>,
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
                // The provider's catalogue request is itself the validation.
                // Avoid duplicate API/page/player probes on every restart.
                remember(persistedOrigin)
                return@withLock persistedOrigin
            }

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
                    remember(resolved)
                    return@withLock resolved
                }
            }

            throw ErrorLoadingException(
                "Aucun domaine Frembed actuellement utilisable",
            )
        }
    }

    private fun readPersistedOrigin(): String? {
        val rawValue = preferences.getString(PREFERENCE_LAST_ORIGIN, null)
            ?: return null
        val origin = normalizePersistedOrigin(rawValue)
        if (origin == null) clearPersistedOrigin()
        return origin
    }

    private fun normalizePersistedOrigin(rawValue: String): String? {
        val uri = runCatching { URI(rawValue.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null

        if (uri.scheme != "https") return null
        if (uri.userInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        if (!FREMBED_DOMAIN.matches(host)) return null

        return "https://$host"
    }

    private fun remember(origin: String) {
        cachedOrigin = origin
        preferences.edit()
            .putString(PREFERENCE_LAST_ORIGIN, origin)
            .commit()
    }

    private fun clearPersistedOrigin() {
        preferences.edit()
            .remove(PREFERENCE_LAST_ORIGIN)
            .commit()
    }

    suspend fun invalidate(origin: String) {
        resolutionMutex.withLock {
            if (cachedOrigin == origin) cachedOrigin = null
            if (readPersistedOrigin() == origin) clearPersistedOrigin()
        }
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
        const val PREFERENCES_NAME = "frembed_domain_resolver"
        const val PREFERENCE_LAST_ORIGIN = "last_valid_origin"

        const val DISCOVERY_ORIGIN = "https://crt.sh"
        const val DISCOVERY_URL =
            "$DISCOVERY_ORIGIN/?Identity=frembed.%25&output=json"

        const val DISCOVERY_ATTEMPTS = 2
        const val DISCOVERY_TIMEOUT_SECONDS = 25L
        const val DISCOVERY_RETRY_DELAY_MS = 1_000L
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
