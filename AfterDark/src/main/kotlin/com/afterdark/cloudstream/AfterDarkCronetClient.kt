package com.afterdark.cloudstream

import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import com.google.android.gms.net.CronetProviderInstaller
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.NetworkException
import org.chromium.net.QuicException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal data class AfterDarkCronetResponse(
    val requestedUrl: String,
    val finalUrl: String,
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    val negotiatedProtocol: String,
) {
    val text: String
        get() = body.toString(Charsets.UTF_8)

    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()
}

/**
 * Small blocking/coroutine bridge around the Google Play services Cronet
 * provider. WebView resource interception is already executed off the UI
 * thread, so the blocking entry point is intentional there.
 */
internal object AfterDarkCronetClient {
    private const val PROVIDER_TIMEOUT_SECONDS = 20L
    private const val READ_BUFFER_SIZE = 64 * 1024
    private const val MAX_BODY_BYTES = 16 * 1024 * 1024
    private const val DEFAULT_ENGINE_NAMESPACE = "playback"

    private const val TAG = "AfterDarkCronet"

    private val engineLock = Any()
    private val callbackExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "AfterDark-Cronet").apply { isDaemon = true }
    }

    private val cronetEngines = LinkedHashMap<String, CronetEngine>()

    @Volatile
    private var providerReady = false

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 30_000L,
        enableDnsHttpsRecords: Boolean = false,
        hostResolverOverride: Pair<String, String>? = null,
        engineNamespace: String = DEFAULT_ENGINE_NAMESPACE,
    ): AfterDarkCronetResponse = withContext(Dispatchers.IO) {
        getBlocking(url, headers, timeoutMs, enableDnsHttpsRecords, hostResolverOverride, engineNamespace)
    }

    fun getBlocking(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 30_000L,
        enableDnsHttpsRecords: Boolean = false,
        hostResolverOverride: Pair<String, String>? = null,
        engineNamespace: String = DEFAULT_ENGINE_NAMESPACE,
    ): AfterDarkCronetResponse {
        check(LooperGuard.isNotMainThread()) {
            "Une requête Cronet bloquante ne peut pas être lancée sur le thread UI"
        }

        val engine = getOrCreateEngine(url, enableDnsHttpsRecords, hostResolverOverride, engineNamespace)
        val finished = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val output = ByteArrayOutputStream()
        var response: AfterDarkCronetResponse? = null
        var failure: Throwable? = null
        lateinit var activeRequest: UrlRequest

        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(
                request: UrlRequest,
                info: UrlResponseInfo,
                newLocationUrl: String,
            ) {
                request.followRedirect()
            }

            override fun onResponseStarted(
                request: UrlRequest,
                info: UrlResponseInfo,
            ) {
                request.read(ByteBuffer.allocateDirect(READ_BUFFER_SIZE))
            }

            override fun onReadCompleted(
                request: UrlRequest,
                info: UrlResponseInfo,
                byteBuffer: ByteBuffer,
            ) {
                byteBuffer.flip()
                val chunk = ByteArray(byteBuffer.remaining())
                byteBuffer.get(chunk)

                if (output.size() + chunk.size > MAX_BODY_BYTES) {
                    failure = IllegalStateException("Réponse Cronet trop volumineuse")
                    request.cancel()
                    return
                }

                output.write(chunk)
                byteBuffer.clear()
                request.read(byteBuffer)
            }

            override fun onSucceeded(
                request: UrlRequest,
                info: UrlResponseInfo,
            ) {
                if (!finished.compareAndSet(false, true)) return
                response = info.toResponse(url, output.toByteArray())
                done.countDown()
            }

            override fun onFailed(
                request: UrlRequest,
                info: UrlResponseInfo?,
                error: CronetException,
            ) {
                if (!finished.compareAndSet(false, true)) return
                Log.e(TAG, "Échec ${describeFailure(url, error)}", error)
                failure = error
                done.countDown()
            }

            override fun onCanceled(
                request: UrlRequest,
                info: UrlResponseInfo?,
            ) {
                if (!finished.compareAndSet(false, true)) return
                if (failure == null) failure = IllegalStateException("Requête Cronet annulée")
                done.countDown()
            }
        }

        val requestBuilder = engine.newUrlRequestBuilder(url, callback, callbackExecutor)
            .setHttpMethod("GET")

        val requestHeaders = sanitizedHeaders(url, headers)
        requestHeaders.forEach { (name, value) ->
            requestBuilder.addHeader(name, value)
        }

        activeRequest = requestBuilder.build()
        activeRequest.start()

        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            activeRequest.cancel()
            Log.e(TAG, "Timeout Cronet après ${timeoutMs} ms pour $url")
            throw TimeoutException("Délai Cronet dépassé pour $url")
        }

        failure?.let { throw it }
        return checkNotNull(response) { "Cronet n'a retourné aucune réponse" }
            .also(::synchronizeCookies)
    }

    fun getBlockingWithRetry(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 30_000L,
        enableDnsHttpsRecords: Boolean = false,
        hostResolverOverride: Pair<String, String>? = null,
        engineNamespace: String = DEFAULT_ENGINE_NAMESPACE,
        maxAttempts: Int = 2,
    ): AfterDarkCronetResponse {
        require(maxAttempts >= 1) { "maxAttempts doit être supérieur ou égal à 1" }
        var lastFailure: Throwable? = null

        for (attempt in 1..maxAttempts) {
            try {
                return getBlocking(
                    url = url,
                    headers = headers,
                    timeoutMs = timeoutMs,
                    enableDnsHttpsRecords = enableDnsHttpsRecords,
                    hostResolverOverride = hostResolverOverride,
                    engineNamespace = engineNamespace,
                )
            } catch (error: Throwable) {
                lastFailure = error
                val retryable = error is NetworkException && error.immediatelyRetryable()
                if (!retryable || attempt == maxAttempts) throw error

                Log.w(
                    TAG,
                    "Connexion immédiatement réessayable; tentative " +
                        "${attempt + 1}/$maxAttempts pour $url",
                    error,
                )
            }
        }

        throw checkNotNull(lastFailure)
    }

    private fun getOrCreateEngine(
        url: String,
        enableDnsHttpsRecords: Boolean,
        hostResolverOverride: Pair<String, String>?,
        engineNamespace: String,
    ): CronetEngine {
        val uri = Uri.parse(url)
        val host = uri.host
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Hôte Cronet invalide : $url")
        val port = uri.port.takeIf { it > 0 } ?: 443
        val profile = if (enableDnsHttpsRecords) "dns-https" else "standard"
        val overrideKey = hostResolverOverride
            ?.let { (overrideHost, overrideIp) -> "$overrideHost=$overrideIp" }
            ?: "none"
        val namespace = engineNamespace.takeIf { it.isNotBlank() }
            ?: DEFAULT_ENGINE_NAMESPACE
        val engineKey = "$namespace:$host:$port:$profile:$overrideKey"

        return synchronized(engineLock) {
            cronetEngines[engineKey]?.let { return@synchronized it }

            val context = AfterDarkRuntime.applicationContext()
                ?: throw IllegalStateException("Contexte AfterDark indisponible")

            if (!providerReady) {
                Tasks.await(
                    CronetProviderInstaller.installProvider(context),
                    PROVIDER_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
                providerReady = true
            }

            val builder = CronetEngine.Builder(context)
                .enableHttp2(true)
                .enableQuic(true)
                // Do not force HTTP/3 with addQuicHint(). Some filtered Wi-Fi
                // networks reject the first QUIC exchange with
                // ERR_QUIC_PROTOCOL_ERROR and Cronet then marks that request as
                // non-retryable. Leaving protocol selection to Cronet restores
                // its normal HTTP/2/QUIC negotiation and fallback behaviour.
                .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISABLED, 0L)

            val experimentalOptions = buildExperimentalOptions(enableDnsHttpsRecords, hostResolverOverride)
            val experimentalOptionsApplied = experimentalOptions != null &&
                applyCronetExperimentalOptions(builder, experimentalOptions)

            builder.build()
                .also { engine ->
                    cronetEngines[engineKey] = engine
                    Log.i(
                        TAG,
                        "Moteur prêt pour $engineKey; protocole adaptatif; " +
                            "AsyncDNS/HTTPS-SVCB=" +
                            (if (enableDnsHttpsRecords && experimentalOptionsApplied) "actifs" else "inactifs") +
                            "; HostResolverRules=" +
                            (if (hostResolverOverride != null && experimentalOptionsApplied) "actif" else "inactif"),
                    )
                }
        }
    }

    /**
     * Builds Cronet's experimental-options JSON. Two independent knobs share
     * this payload:
     *  - AsyncDNS/HTTPS-SVCB: Cronet's own resolver + HTTPS DNS records,
     *    originally added for ECH discovery.
     *  - HostResolverRules: forces Cronet to skip the network-provided DNS
     *    resolver entirely for one host and connect directly to a given IP
     *    (typically resolved beforehand via [AfterDarkDohResolver]). This is
     *    what actually matters on networks that filter by poisoning DNS
     *    answers rather than by inspecting TLS — the SNI sent to that IP is
     *    still the real hostname, so certificate validation is unaffected.
     */
    private fun buildExperimentalOptions(
        enableDnsHttpsRecords: Boolean,
        hostResolverOverride: Pair<String, String>?,
    ): String? {
        if (!enableDnsHttpsRecords && hostResolverOverride == null) return null

        val fields = mutableListOf<String>()
        if (enableDnsHttpsRecords) {
            fields += "\"AsyncDNS\": {\"enable\": true}"
            fields += "\"UseDnsHttpsSvcb\": {\"use_alpn\": true}"
        }
        hostResolverOverride?.let { (overrideHost, overrideIp) ->
            fields += "\"HostResolverRules\": " +
                "{\"host_resolver_rules\": \"MAP $overrideHost $overrideIp\"}"
        }

        return "{${fields.joinToString(",\n")}}"
    }

    /**
     * play-services-cronet 18.0.1 exposes an older compile-time API even when
     * Google Play services installs a newer native provider. Calling the
     * long-standing experimental-options API reflectively keeps this source
     * compatible.
     */
    private fun applyCronetExperimentalOptions(
        builder: CronetEngine.Builder,
        options: String,
    ): Boolean {
        val method = builder.javaClass.methods.firstOrNull { candidate ->
            candidate.name == "setExperimentalOptions" &&
                candidate.parameterTypes.contentEquals(arrayOf(String::class.java))
        }
        if (method == null) {
            Log.w(TAG, "Ce fournisseur Cronet n'expose pas setExperimentalOptions")
            return false
        }

        return runCatching {
            method.invoke(builder, options)
            true
        }.getOrElse { error ->
            Log.w(TAG, "Options expérimentales Cronet refusées : $options", error)
            false
        }
    }

    private fun describeFailure(url: String, error: CronetException): String = buildString {
        append(url)
        append(" : ")
        append(error.javaClass.simpleName)
        error.message?.takeIf { it.isNotBlank() }?.let {
            append(" (")
            append(it)
            append(')')
        }
        if (error is NetworkException) {
            append("; code=")
            append(error.errorCode)
            append("; interne=")
            append(error.cronetInternalErrorCode)
            append("; retryable=")
            append(error.immediatelyRetryable())
        }
        if (error is QuicException) {
            append("; quic=")
            append(error.quicDetailedErrorCode)
        }
    }

    private fun sanitizedHeaders(
        url: String,
        supplied: Map<String, String>,
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val suppliedCookie = supplied.entries
            .firstOrNull { it.key.equals("Cookie", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }

        supplied.forEach { (name, value) ->
            if (
                name.isNotBlank() &&
                value.isNotBlank() &&
                !name.equals("Host", ignoreCase = true) &&
                !name.equals("Connection", ignoreCase = true) &&
                !name.equals("Content-Length", ignoreCase = true) &&
                !name.equals("Accept-Encoding", ignoreCase = true) &&
                !name.equals("Cookie", ignoreCase = true)
            ) {
                result[name] = value
            }
        }

        result["Accept-Encoding"] = "identity"

        runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { result["Cookie"] = it }
            ?: suppliedCookie?.let { result["Cookie"] = it }

        return result
    }

    private fun UrlResponseInfo.toResponse(
        requestedUrl: String,
        bytes: ByteArray,
    ): AfterDarkCronetResponse = AfterDarkCronetResponse(
        requestedUrl = requestedUrl,
        finalUrl = url,
        statusCode = httpStatusCode,
        headers = allHeaders,
        body = bytes,
        negotiatedProtocol = negotiatedProtocol.orEmpty(),
    )

    private fun synchronizeCookies(response: AfterDarkCronetResponse) {
        response.headers.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }
            .forEach { cookie ->
                runCatching {
                    CookieManager.getInstance().setCookie(response.finalUrl, cookie)
                }
            }
    }

    private object LooperGuard {
        fun isNotMainThread(): Boolean =
            android.os.Looper.myLooper() != android.os.Looper.getMainLooper()
    }
}
