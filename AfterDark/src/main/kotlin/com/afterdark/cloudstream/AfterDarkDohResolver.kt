package com.afterdark.cloudstream

import android.util.Log
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

/**
 * Resolves a hostname to an IPv4 address over DNS-over-HTTPS, contacting the
 * resolver by its raw IP address so that no system DNS lookup is required to
 * reach it in the first place.
 *
 * This exists because captive/managed Wi-Fi networks (hotels, hospitals…)
 * commonly filter by poisoning their own DNS resolver rather than by
 * inspecting TLS (SNI/ECH). Chrome, Firefox and Samsung Internet all ship
 * DNS-over-HTTPS enabled by default, so they ignore the network's resolver
 * and are unaffected; Android's WebView and this plugin's Cronet client both
 * use the network-provided resolver by default and get poisoned answers.
 * Resolving here and feeding the result into Cronet's HostResolverRules
 * (see [AfterDarkCronetClient]) reproduces what Chrome/Firefox do natively.
 */
internal data class AfterDarkDohResult(val ip: String?, val detail: String)

internal object AfterDarkDohResolver {
    private const val TAG = "AfterDarkDoh"
    private const val TIMEOUT_MS = 8_000

    // Cloudflare and Google's DoH JSON endpoints, contacted by raw IP so no
    // DNS lookup is needed to reach them. Both carry IP SAN entries for
    // these addresses, and both speak the same "Answer" JSON schema, so one
    // parser (extractFirstIp) covers all of them. Google is tried second in
    // case a given network specifically blocks Cloudflare's resolver IPs.
    private data class DohEndpoint(val ip: String, val path: String)

    private val DOH_ENDPOINTS = listOf(
        DohEndpoint("1.1.1.1", "/dns-query"),
        DohEndpoint("1.0.0.1", "/dns-query"),
        DohEndpoint("8.8.8.8", "/resolve"),
        DohEndpoint("8.8.4.4", "/resolve"),
    )

    private val cache = ConcurrentHashMap<String, String>()

    /** Blocking; call from a background thread only. */
    fun resolveBlocking(host: String): String? = resolveWithDetail(host).ip

    /**
     * Same resolution as [resolveBlocking], but also reports why it failed
     * when it does — which DoH endpoints were tried and what happened to
     * each. Needed right now to tell apart "DoH itself is blocked on this
     * network" from "DoH worked but Cronet still can't reach the real IP"
     * without another guess-and-test round trip.
     */
    fun resolveWithDetail(host: String): AfterDarkDohResult {
        cache[host]?.let { return AfterDarkDohResult(it, "IP en cache : $it") }

        val attempts = mutableListOf<String>()
        for (endpoint in DOH_ENDPOINTS) {
            val outcome = runCatching { query(endpoint, host) }
            outcome.onFailure { error ->
                Log.w(TAG, "Échec DoH pour $host via ${endpoint.ip}", error)
                attempts += "${endpoint.ip}${endpoint.path} : " +
                    "${error.javaClass.simpleName}${error.message?.let { " ($it)" }.orEmpty()}"
            }

            val resolved = outcome.getOrNull()
            if (resolved != null) {
                cache[host] = resolved
                return AfterDarkDohResult(resolved, "résolu $host → $resolved via ${endpoint.ip}")
            }
            if (outcome.isSuccess) {
                attempts += "${endpoint.ip}${endpoint.path} : réponse reçue sans enregistrement A"
            }
        }

        val detail = "DoH indisponible pour $host : " +
            attempts.joinToString("; ").ifBlank { "aucun résolveur n'a répondu" }
        Log.w(TAG, detail)
        return AfterDarkDohResult(null, detail)
    }

    private fun query(endpoint: DohEndpoint, host: String): String? {
        val url = URL("https://${endpoint.ip}${endpoint.path}?name=$host&type=A")
        val connection = url.openConnection() as HttpsURLConnection

        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/dns-json")

            if (connection.responseCode !in 200..299) return null

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            extractFirstIp(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractFirstIp(dnsJsonBody: String): String? {
        val answers = JSONObject(dnsJsonBody).optJSONArray("Answer") ?: return null

        for (i in 0 until answers.length()) {
            val entry = answers.getJSONObject(i)
            // Type 1 = A record (IPv4). Skip CNAME (5) and others.
            if (entry.optInt("type") == 1) {
                entry.optString("data").takeIf { it.isNotBlank() }?.let { return it }
            }
        }

        return null
    }
}
