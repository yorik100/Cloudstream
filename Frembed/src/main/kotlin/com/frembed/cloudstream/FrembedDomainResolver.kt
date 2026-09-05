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
import org.json.JSONObject
import java.net.URI

internal class FrembedDomainResolver(
    private val requestHeaders: Map<String, String>,
    private val streamHeaders: Map<String, String>,
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
                    remember(resolved)
                    return@withLock resolved
                }

                clearPersistedOrigin()
            }

            val candidates = discoverCandidates()
            if (candidates.isEmpty()) {
                throw ErrorLoadingException(
                    "Aucun domaine Frembed trouvé dans les certificats publics",
                )
            }

            // Candidates are already ordered from the newest certificate to
            // the oldest one. Work through recent groups first and stop as
            // soon as one group contains a usable domain.
            for (recentBatch in candidates.chunked(MAX_PARALLEL_PRECHECKS)) {
                val reachableCandidates = precheckCandidates(recentBatch)

                for (validationBatch in reachableCandidates.chunked(MAX_PARALLEL_PROBES)) {
                    val results = coroutineScope {
                        validationBatch.map { candidate ->
                            async { validateCandidate(candidate) }
                        }.awaitAll()
                    }

                    // awaitAll keeps input order, so a successful newer
                    // candidate wins over an older one from the same batch.
                    results.firstOrNull { it != null }?.let { resolved ->
                        remember(resolved)
                        return@withLock resolved
                    }
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

    /**
     * Cheap reachability pass performed before the expensive Frembed API and
     * playback checks. Any HTTP response proves that DNS, TCP and TLS reached
     * the host; the full validation below still rejects parked, obsolete or
     * otherwise unusable domains.
     */
    private suspend fun precheckCandidates(candidates: List<String>): List<String> =
        coroutineScope {
            candidates.map { candidate ->
                async {
                    val reachable = runCatching {
                        app.head(
                            url = "$candidate/",
                            headers = requestHeaders,
                            timeout = PRECHECK_TIMEOUT_SECONDS,
                        )
                    }.isSuccess

                    candidate.takeIf { reachable }
                }
            }.awaitAll().filterNotNull()
        }

    private suspend fun validateCandidate(candidateOrigin: String): String? {
        val probeUrl = "$candidateOrigin/api/films" +
            "?id=$VALIDATION_TMDB_ID&idType=tmdb"

        val response = runCatching {
            app.get(
                url = probeUrl,
                headers = requestHeaders,
                referer = "$candidateOrigin/films?id=$VALIDATION_TMDB_ID",
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

        val root = runCatching { JSONObject(response.text) }.getOrNull()
            ?: return null

        val filmPage = "$finalOrigin/films?id=$VALIDATION_TMDB_ID"
        val serverUrls = extractServerUrls(root, finalOrigin)
        if (serverUrls.isEmpty()) return null

        // Validate the film page once per candidate. The previous flow fetched
        // this exact page again for every server link, which multiplied the
        // resolution time on domains exposing several players.
        val filmPageResponse = runCatching {
            app.get(
                url = filmPage,
                headers = requestHeaders,
                referer = "$finalOrigin/",
                cacheTime = 0,
                timeout = PROBE_TIMEOUT_SECONDS,
            )
        }.getOrNull() ?: return null

        if (filmPageResponse.okhttpResponse.code !in 200..299) return null

        for (serverUrl in serverUrls) {
            if (
                validatesPlaybackRedirect(
                    origin = finalOrigin,
                    filmPage = filmPage,
                    serverUrl = serverUrl,
                )
            ) {
                return finalOrigin
            }
        }

        return null
    }

    private fun extractServerUrls(root: JSONObject, origin: String): List<String> {
        val result = LinkedHashSet<String>()
        val links = root.optJSONArray("links")
        if (links != null) {
            for (index in 0 until links.length()) {
                val item = links.optJSONObject(index) ?: continue
                resolveServerUrl(origin, item.optString("url", ""))
                    ?.let(result::add)
            }
        }

        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!LEGACY_LINK_KEY.matches(key)) continue
            resolveServerUrl(origin, root.optString(key, ""))
                ?.let(result::add)
        }

        return result.toList()
    }

    private fun resolveServerUrl(origin: String, rawUrl: String): String? {
        if (rawUrl.isBlank()) return null

        return runCatching {
            val absolute = when {
                rawUrl.startsWith("//") -> "https:$rawUrl"
                else -> URI(origin).resolve(rawUrl).toString()
            }
            val uri = URI(absolute)
            absolute.takeIf {
                (uri.scheme == "https" || uri.scheme == "http") &&
                    !uri.host.isNullOrBlank()
            }
        }.getOrNull()
    }

    private suspend fun validatesPlaybackRedirect(
        origin: String,
        filmPage: String,
        serverUrl: String,
    ): Boolean {
        val response = runCatching {
            app.get(
                url = serverUrl,
                headers = streamHeaders,
                referer = filmPage,
                allowRedirects = false,
                cacheTime = 0,
                timeout = PROBE_TIMEOUT_SECONDS,
            )
        }.getOrNull() ?: return false

        if (response.okhttpResponse.code !in 300..399) return false

        val location = response.headers["Location"] ?: return false
        val target = resolveServerUrl(serverUrl, location) ?: return false
        if ("test-video" in target.lowercase()) return false

        val originHost = runCatching { URI(origin).host }.getOrNull() ?: return false
        val targetHost = runCatching { URI(target).host }.getOrNull() ?: return false

        return !targetHost.equals(originHost, ignoreCase = true)
    }

    internal companion object {
        const val PREFERENCES_NAME = "frembed_domain_resolver"
        const val PREFERENCE_LAST_ORIGIN = "last_valid_origin"

        const val DISCOVERY_ORIGIN = "https://crt.sh"
        const val DISCOVERY_URL =
            "$DISCOVERY_ORIGIN/?Identity=frembed.%25&output=json"

        const val VALIDATION_TMDB_ID = 533535
        const val DISCOVERY_ATTEMPTS = 2
        const val DISCOVERY_TIMEOUT_SECONDS = 25L
        const val DISCOVERY_RETRY_DELAY_MS = 1_000L
        const val PRECHECK_TIMEOUT_SECONDS = 3L
        const val PROBE_TIMEOUT_SECONDS = 8L
        const val MAX_PARALLEL_PRECHECKS = 12
        const val MAX_PARALLEL_PROBES = 6

        val FREMBED_DOMAIN = Regex(
            "^frembed\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$",
            RegexOption.IGNORE_CASE,
        )
        val LEGACY_LINK_KEY = Regex("^link\\d+(?:vostfr|vo)?$", RegexOption.IGNORE_CASE)
    }
}
