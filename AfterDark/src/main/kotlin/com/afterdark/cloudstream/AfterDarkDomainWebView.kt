package com.afterdark.cloudstream

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Interactive, TV-friendly fallback used only when the regular HTTP resolver
 * cannot read the official AfterDark registry.
 */
internal object AfterDarkDomainWebView {
    private const val TIMEOUT_MS = 180_000L
    private const val DOM_POLL_MS = 750L
    private const val BRIDGE_NAME = "AfterDarkDomainBridge"
    private const val RESOLVER_ENGINE_NAMESPACE = "domain-resolver"

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    suspend fun resolve(): String? = suspendCoroutine { continuation ->
        val finished = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        var dialog: Dialog? = null
        var webView: WebView? = null
        var infoView: TextView? = null
        var candidateOrigin: String? = null
        var mainFrameHttpCode: Int? = null
        var domPoll: Runnable? = null
        val cronetHosts = ConcurrentHashMap<String, Unit>().apply {
            put(AfterDarkDomainResolver.SOURCE_HOST, Unit)
        }

        lateinit var timeoutRunnable: Runnable

        fun showInfo(message: String) {
            handler.post { infoView?.text = message }
        }

        fun stopDomPolling() {
            domPoll?.let(handler::removeCallbacks)
            domPoll = null
        }

        fun finish(result: String?) {
            if (!finished.compareAndSet(false, true)) return

            handler.removeCallbacks(timeoutRunnable)
            stopDomPolling()
            handler.post {
                runCatching { dialog?.setOnDismissListener(null) }
                runCatching { dialog?.dismiss() }
                runCatching {
                    webView?.removeJavascriptInterface(BRIDGE_NAME)
                    webView?.stopLoading()
                    webView?.loadUrl("about:blank")
                    webView?.removeAllViews()
                    webView?.destroy()
                }
                dialog = null
                webView = null
                infoView = null
            }

            continuation.resume(result)
        }

        fun returnToRegistry(reason: String) {
            if (finished.get()) return
            stopDomPolling()
            candidateOrigin = null
            mainFrameHttpCode = null
            showInfo("$reason\nRetour au registre officiel AfterDark…")
            webView?.loadUrl(AfterDarkDomainResolver.SOURCE_URL)
        }

        timeoutRunnable = Runnable {
            showInfo("Délai dépassé pendant la recherche de l'adresse AfterDark.")
            finish(null)
        }

        handler.post {
            try {
                val activity = AfterDarkRuntime.currentActivity()
                if (activity == null || activity.isFinishing) {
                    finish(null)
                    return@post
                }

                val root = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.BLACK)
                }

                val info = TextView(activity).apply {
                    text = buildString {
                        append("AfterDark — recherche de l'adresse officielle\n")
                        append("Le client HTTP n'a pas pu lire le registre. ")
                        append("Tu peux interagir avec la page ; l'adresse sera détectée automatiquement.")
                    }
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(24, 18, 24, 12)
                }
                infoView = info

                val browser = WebView(activity)
                webView = browser
                browser.setBackgroundColor(Color.BLACK)
                browser.isFocusable = true
                browser.isFocusableInTouchMode = true
                browser.webChromeClient = WebChromeClient()
                browser.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadsImagesAutomatically = true
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(browser, true)
                }

                fun probeDom() {
                    if (finished.get()) return

                    val candidate = candidateOrigin
                    if (candidate == null) {
                        browser.evaluateJavascript(
                            """
                            (() => {
                              const normalize = value =>
                                String(value || '').replace(/\s+/g, ' ').trim();
                              const elements = Array.from(
                                document.querySelectorAll('a[href],button,[role="button"]')
                              );
                              const link = elements.find(element =>
                                normalize(element.textContent).toLowerCase()
                                  .includes('ouvrir le site')
                              );
                              if (link) {
                                const href = link.href || link.getAttribute('href') || '';
                                if (href) $BRIDGE_NAME.onCandidate(String(href));
                              }
                            })();
                            """.trimIndent(),
                            null,
                        )
                    } else {
                        browser.evaluateJavascript(
                            """
                            (() => {
                              const title = String(document.title || '');
                              const text = String(document.body?.innerText || '').slice(0, 200000);
                              $BRIDGE_NAME.onTargetSnapshot(
                                String(location.href || ''),
                                title,
                                text
                              );
                            })();
                            """.trimIndent(),
                            null,
                        )
                    }
                }

                fun scheduleDomPolling() {
                    stopDomPolling()
                    val runnable = object : Runnable {
                        override fun run() {
                            if (finished.get()) return
                            probeDom()
                            handler.postDelayed(this, DOM_POLL_MS)
                        }
                    }
                    domPoll = runnable
                    handler.post(runnable)
                }

                val bridge = object {
                    @JavascriptInterface
                    fun onCandidate(rawUrl: String?) {
                        handler.post candidatePost@{
                            if (finished.get() || candidateOrigin != null) return@candidatePost

                            val origin = rawUrl
                                ?.let { AfterDarkDomainResolver.normalizeOrigin(it) }
                                ?.takeUnless { AfterDarkDomainResolver.isSourceOrigin(it) }
                            if (origin == null) {
                                showInfo("Le registre a fourni une adresse invalide.")
                                return@candidatePost
                            }

                            candidateOrigin = origin
                            URI(origin).host
                                ?.lowercase()
                                ?.removePrefix("www.")
                                ?.let { cronetHosts[it] = Unit }
                            mainFrameHttpCode = null
                            stopDomPolling()
                            showInfo("Adresse trouvée. Validation de $origin…")
                            browser.loadUrl("$origin/")
                        }
                    }

                    @JavascriptInterface
                    fun onTargetSnapshot(pageUrl: String?, title: String?, bodyText: String?) {
                        handler.post snapshotPost@{
                            if (finished.get() || candidateOrigin == null) return@snapshotPost

                            val finalOrigin = pageUrl
                                ?.let { AfterDarkDomainResolver.normalizeOrigin(it) }
                                ?.takeUnless { AfterDarkDomainResolver.isSourceOrigin(it) }
                                ?: return@snapshotPost
                            val page = buildString {
                                append(title.orEmpty())
                                append('\n')
                                append(bodyText.orEmpty())
                            }

                            when (mainFrameHttpCode) {
                                404, 410 -> {
                                    returnToRegistry(
                                        "Adresse rejetée : page inexistante " +
                                            "(HTTP $mainFrameHttpCode).",
                                    )
                                    return@snapshotPost
                                }
                            }

                            if (
                                AfterDarkDomainResolver.REJECTED_DIRECTORY_MARKERS.any {
                                    page.contains(it, ignoreCase = true)
                                }
                            ) {
                                returnToRegistry(
                                    "Adresse rejetée : elle pointe vers une autre page d'annuaire.",
                                )
                                return@snapshotPost
                            }

                            if (
                                AfterDarkDomainResolver.REJECTED_MISSING_PAGE_MARKERS.any {
                                    page.contains(it, ignoreCase = true)
                                }
                            ) {
                                returnToRegistry("Adresse rejetée : la page n'existe pas.")
                                return@snapshotPost
                            }

                            if (
                                page.isBlank() ||
                                AfterDarkDomainResolver.CLOUDFLARE_CHALLENGE_MARKERS.any {
                                    page.contains(it, ignoreCase = true)
                                }
                            ) {
                                showInfo(
                                    "Validation en cours… Termine la vérification affichée si nécessaire.",
                                )
                                return@snapshotPost
                            }

                            val code = mainFrameHttpCode
                            if (code != null && code !in 200..299) {
                                showInfo(
                                    "La page répond HTTP $code. Tu peux utiliser Recharger.",
                                )
                                return@snapshotPost
                            }

                            finish(finalOrigin)
                        }
                    }
                }
                browser.addJavascriptInterface(bridge, BRIDGE_NAME)

                browser.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? = proxyResolverRequest(
                        request = request,
                        allowedHosts = cronetHosts,
                        onMainFrameFailure = ::showInfo,
                    ) ?: super.shouldInterceptRequest(view, request)

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val scheme = request?.url?.scheme?.lowercase()
                        return scheme != "http" && scheme != "https"
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        mainFrameHttpCode = null
                        stopDomPolling()
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (finished.get()) return
                        scheduleDomPolling()
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            mainFrameHttpCode = errorResponse?.statusCode
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            stopDomPolling()
                            showInfo(
                                "Chargement impossible dans le WebView. " +
                                    "Vérifie la page puis utilise Recharger.",
                            )
                        }
                    }

                    override fun onReceivedSslError(
                        view: WebView?,
                        handlerValue: SslErrorHandler?,
                        error: SslError?,
                    ) {
                        handlerValue?.cancel()
                        stopDomPolling()
                        showInfo(
                            "Connexion HTTPS refusée par le réseau. " +
                                "Le certificat n'a pas été contourné ; utilise Recharger.",
                        )
                    }
                }

                val reload = Button(activity).apply {
                    text = "Recharger"
                    isFocusable = true
                    setOnClickListener {
                        mainFrameHttpCode = null
                        val target = candidateOrigin?.let { "$it/" }
                            ?: AfterDarkDomainResolver.SOURCE_URL
                        browser.loadUrl(target)
                    }
                }
                val cancel = Button(activity).apply {
                    text = "Annuler"
                    isFocusable = true
                    setOnClickListener { finish(null) }
                }
                val controls = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setPadding(16, 0, 16, 10)
                    addView(reload)
                    addView(cancel)
                }

                root.addView(
                    info,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                root.addView(
                    controls,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                root.addView(
                    browser,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    ),
                )

                val resolverDialog = Dialog(activity).apply {
                    setContentView(root)
                    setCancelable(true)
                    setOnCancelListener { finish(null) }
                    setOnDismissListener { finish(null) }
                    window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
                    show()
                    window?.setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
                dialog = resolverDialog

                handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
                browser.loadUrl(AfterDarkDomainResolver.SOURCE_URL)
                browser.requestFocus()
            } catch (error: Throwable) {
                Log.e(AfterDarkDomainResolver.TAG, "WebView du registre AfterDark impossible", error)
                finish(null)
            }
        }
    }

    private fun proxyResolverRequest(
        request: WebResourceRequest?,
        allowedHosts: Map<String, Unit>,
        onMainFrameFailure: (String) -> Unit,
    ): WebResourceResponse? {
        if (request == null) return null
        if (!request.method.equals("GET", ignoreCase = true)) return null

        val uri = request.url
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host
            ?.lowercase()
            ?.removePrefix("www.")
            ?: return null
        if (!allowedHosts.containsKey(host)) return null

        val headers = LinkedHashMap<String, String>()
        request.requestHeaders.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) headers[key] = value
        }

        // The wifi's own DNS resolver is what's actually poisoned here (not
        // TLS/SNI inspection) — resolve over DoH, bypassing it entirely, then
        // pin Cronet to that IP for this host. SNI/certificate validation
        // still use the real hostname, only the resolver step is skipped.
        val overrideIp = AfterDarkDohResolver.resolveBlocking(host)

        return runCatching {
            AfterDarkCronetClient.getBlockingWithRetry(
                url = uri.toString(),
                headers = headers,
                timeoutMs = if (request.isForMainFrame) 45_000L else 30_000L,
                enableDnsHttpsRecords = true,
                hostResolverOverride = overrideIp?.let { host to it },
                engineNamespace = RESOLVER_ENGINE_NAMESPACE,
                maxAttempts = 2,
            ).toWebResponse(
                defaultMimeType = if (request.isForMainFrame) {
                    "text/html"
                } else {
                    "application/octet-stream"
                },
            )
        }.getOrElse { error ->
            Log.e(
                AfterDarkDomainResolver.TAG,
                "Proxy Cronet du resolver impossible pour ${uri.host}",
                error,
            )
            if (!request.isForMainFrame) return null

            onMainFrameFailure(
                "Connexion Cronet au registre impossible. Utilise Recharger pour réessayer.",
            )
            resolverFailureResponse(error)
        }
    }

    private fun resolverFailureResponse(error: Throwable): WebResourceResponse {
        val diagnostic = TextUtils.htmlEncode(
            "${error.javaClass.simpleName}: ${error.message ?: "aucun détail"}",
        )
        return WebResourceResponse(
            "text/html",
            "UTF-8",
            ByteArrayInputStream(
                """
                <!doctype html>
                <html lang="fr"><meta charset="utf-8">
                <body style="background:#000;color:#fff;font-family:sans-serif;padding:24px">
                <h2>Registre AfterDark inaccessible</h2>
                <p>Les deux tentatives Cronet ont échoué.</p>
                <p style="color:#aaa;word-break:break-word">$diagnostic</p>
                </body></html>
                """.trimIndent().toByteArray(),
            ),
        )
    }

    private fun AfterDarkCronetResponse.toWebResponse(
        defaultMimeType: String,
    ): WebResourceResponse {
        val contentType = header("Content-Type").orEmpty()
        val mimeType = contentType
            .substringBefore(';')
            .trim()
            .takeIf { it.isNotBlank() }
            ?: defaultMimeType
        val charset = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)
            .find(contentType)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim('"', '\'')
            ?: "UTF-8"

        val responseHeaders = LinkedHashMap<String, String>()
        headers.forEach { (key, values) ->
            if (
                !key.equals("Content-Encoding", ignoreCase = true) &&
                !key.equals("Content-Length", ignoreCase = true) &&
                !key.equals("Transfer-Encoding", ignoreCase = true) &&
                !key.equals("Set-Cookie", ignoreCase = true) &&
                values.isNotEmpty()
            ) {
                responseHeaders[key] = values.joinToString(", ")
            }
        }

        return WebResourceResponse(
            mimeType,
            charset,
            statusCode,
            reasonPhrase(statusCode),
            responseHeaders,
            ByteArrayInputStream(body),
        )
    }

    private fun reasonPhrase(statusCode: Int): String = when (statusCode) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        206 -> "Partial Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        410 -> "Gone"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "HTTP $statusCode"
    }
}
