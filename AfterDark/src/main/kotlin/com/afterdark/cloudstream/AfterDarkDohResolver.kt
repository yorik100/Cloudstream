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
internal object AfterDarkDohResolver {
    private const val TAG = "AfterDarkDoh"
    private const val TIMEOUT_MS = 8_000

    // Cloudflare's DoH certificate carries IP SAN entries for these
    // addresses, so TLS hostname verification succeeds even though we never
    // resolve "cloudflare-dns.com" through any DNS resolver.
    private val DOH_ENDPOINT_IPS = listOf("1.1.1.1", "1.0.0.1")

    private val cache = ConcurrentHashMap<String, String>()

    /** Blocking; call from a background thread only. */
    fun resolveBlocking(host: String): String? {
        cache[host]?.let { return it }

        for (endpointIp in DOH_ENDPOINT_IPS) {
            val resolved = runCatching { query(endpointIp, host) }
                .onFailure { error -> Log.w(TAG, "Échec DoH pour $host via $endpointIp", error) }
                .getOrNull()

            if (resolved != null) {
                cache[host] = resolved
                return resolved
            }
        }

        return null
    }

    private fun query(endpointIp: String, host: String): String? {
        val url = URL("https://$endpointIp/dns-query?name=$host&type=A")
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
