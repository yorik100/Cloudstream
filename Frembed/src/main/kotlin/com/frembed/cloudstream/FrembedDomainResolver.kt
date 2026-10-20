package com.frembed.cloudstream

import android.util.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

            resolveFromConfig()?.let { resolved ->
                cachedOrigin = resolved
                Log.i(TAG, "Domaine Frembed obtenu depuis config.json : $resolved")
                return@withLock resolved
            }

            Log.w(
                TAG,
                "config.json invalide ou indisponible, essai de KeepLinkFrembed.txt",
            )

            resolveFromKeepLink()?.let { resolved ->
                cachedOrigin = resolved
                Log.i(
                    TAG,
                    "Domaine Frembed obtenu depuis KeepLinkFrembed.txt : $resolved",
                )
                return@withLock resolved
            }

            throw ErrorLoadingException(
                "Aucun domaine Frembed actuellement utilisable",
            )
        }
    }

    /**
     * config.json publie les URL complètes à tester.
     * Le résolveur ne déduit jamais l'adresse depuis le nom du service.
     */
    private suspend fun resolveFromConfig(): String? {
        val response = runCatching {
            app.get(
                url = CONFIG_URL,
                headers = mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to requestHeaders["User-Agent"].orEmpty(),
                ),
                cacheTime = 0,
                timeout = CONFIG_TIMEOUT_SECONDS,
            )
        }.onFailure { error ->
            Log.w(TAG, "Lecture de config.json impossible", error)
        }.getOrNull() ?: return null

        if (response.okhttpResponse.code !in 200..299) return null

        val config = runCatching { JSONObject(response.text) }.getOrNull()
            ?: return null

        val main = normalizeOrigin(config.optString("main", ""))
        val backup = normalizeOrigin(config.optString("backup", ""))

        if (main != null) {
            validateCandidate(main)?.let { resolved ->
                Log.i(TAG, "config.json/main validé : $resolved")
                return resolved
            }
            Log.w(TAG, "config.json/main invalide ou inaccessible : $main")
        } else {
            Log.w(TAG, "config.json/main absent ou invalide")
        }

        if (backup == null) {
            Log.w(TAG, "config.json/backup absent ou invalide")
            return null
        }

        if (backup == main) {
            Log.i(TAG, "config.json/backup identique à main, second test ignoré")
            return null
        }

        Log.i(TAG, "Échec de main, validation de config.json/backup : $backup")

        return validateCandidate(backup).also { resolved ->
            if (resolved != null) {
                Log.i(TAG, "config.json/backup validé : $resolved")
            } else {
                Log.w(TAG, "config.json/backup invalide ou inaccessible : $backup")
            }
        }
    }

    /**
     * Validation générique, comme pour les autres résolveurs :
     * HTTPS + hostname DNS syntaxiquement valide.
     *
     * Aucun nom, suffixe, TLD ou motif propre au site cible n'est imposé.
     */
    private fun normalizeOrigin(rawValue: String): String? {
        val uri = runCatching { URI(rawValue.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null

        if (uri.scheme?.lowercase() != "https") return null
        if (uri.userInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        if (!HOST_REGEX.matches(host)) return null

        return "https://$host"
    }

    suspend fun invalidate(origin: String) {
        resolutionMutex.withLock {
            if (cachedOrigin == origin) cachedOrigin = null
        }
    }

    /**
     * KeepLink publie lui aussi une ou plusieurs URL complètes.
     * On les valide dans l'ordre, sans deviner le domaine.
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
            Log.w(TAG, "Lecture de KeepLinkFrembed.txt impossible", error)
        }.getOrNull() ?: return null

        if (response.okhttpResponse.code !in 200..299) return null

        for (
            candidate in response.text
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .mapNotNull(::normalizeOrigin)
                .distinct()
        ) {
            validateCandidate(candidate)?.let { return it }
        }

        return null
    }

    /**
     * Valide le comportement du service, jamais son hostname.
     *
     * L'API est essayée directement. Si l'ancienne adresse ne redirige que sa
     * page d'accueil, on récupère l'origine finale de cette redirection puis
     * on reteste exactement la même API.
     */
    private suspend fun validateCandidate(candidateOrigin: String): String? {
        validatePublicApi(candidateOrigin)?.let { return it }

        val redirectedOrigin = resolveRedirectedOrigin(candidateOrigin)
            ?: return null

        if (redirectedOrigin == candidateOrigin) return null

        Log.i(
            TAG,
            "Redirection d'origine détectée : " +
                "$candidateOrigin -> $redirectedOrigin",
        )

        return validatePublicApi(redirectedOrigin)
    }

    /**
     * L'identité est validée par la structure JSON réellement consommée par
     * le provider. Une redirection HTTPS vers un hostname différent est
     * acceptée et l'origine finale est renvoyée.
     */
    private suspend fun validatePublicApi(candidateOrigin: String): String? {
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
        }.getOrNull() ?: return null

        if (response.okhttpResponse.code !in 200..299) return null

        val finalOrigin = response.okhttpResponse.request.url.let { url ->
            normalizeOrigin(
                "${url.scheme}://${url.host}" +
                    if (url.port != 443) ":${url.port}" else "",
            )
        } ?: return null

        // A successful JSON API response establishes domain usability.
        // Empty catalogues and changes in item fields do not make the
        // domain invalid. A JSON-level explicit API error is still rejected.
        val payload = runCatching { JSONObject(response.text) }.getOrNull()
            ?: return null
        if (payload.has("status") && payload.optInt("status", 200) !in 200..299) {
            return null
        }

        if (finalOrigin != candidateOrigin) {
            Log.i(
                TAG,
                "Redirection API acceptée : $candidateOrigin -> $finalOrigin",
            )
        }

        return finalOrigin
    }

    /**
     * Résout l'origine finale d'une redirection HTTPS sans utiliser le HTML
     * pour reconnaître le site.
     */
    private suspend fun resolveRedirectedOrigin(candidateOrigin: String): String? {
        val response = runCatching {
            app.get(
                url = "$candidateOrigin/",
                headers = requestHeaders,
                cacheTime = 0,
                timeout = REDIRECT_TIMEOUT_SECONDS,
            )
        }.onFailure { error ->
            Log.w(
                TAG,
                "Résolution de redirection impossible pour $candidateOrigin",
                error,
            )
        }.getOrNull() ?: return null

        if (response.okhttpResponse.code !in 200..299) return null

        return response.okhttpResponse.request.url.let { url ->
            normalizeOrigin(
                "${url.scheme}://${url.host}" +
                    if (url.port != 443) ":${url.port}" else "",
            )
        }
    }

    internal companion object {
        const val TAG = "FrembedResolver"

        const val CONFIG_ORIGIN = "https://raw.githubusercontent.com"
        const val CONFIG_URL =
            "$CONFIG_ORIGIN/kingofthrone73-dotcom/frembed-config/refs/heads/main/config.json"

        const val KEEP_LINK_ORIGIN = "https://raw.githubusercontent.com"
        const val KEEP_LINK_URL =
            "$KEEP_LINK_ORIGIN/yorik100/Cloudstream/refs/heads/main/KeepLinkFrembed.txt"

        const val CONFIG_TIMEOUT_SECONDS = 8L
        const val KEEP_LINK_TIMEOUT_SECONDS = 8L
        const val API_PROBE_TIMEOUT_SECONDS = 8L
        const val REDIRECT_TIMEOUT_SECONDS = 8L

        val HOST_REGEX = Regex(
            "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?" +
                "(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$",
            RegexOption.IGNORE_CASE,
        )
    }
}
