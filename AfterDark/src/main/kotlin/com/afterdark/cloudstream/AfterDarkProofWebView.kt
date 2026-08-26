package com.afterdark.cloudstream

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object AfterDarkProofWebView {
    private const val PROOF_HEADER = "x-nabi-proof"
    private const val TIMEOUT_MS = 180_000L

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun acquire(
        request: PlaybackRequest,
        mainUrl: String,
    ): ProofSession? = suspendCoroutine { continuation ->
        val finished = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        var dialog: Dialog? = null
        var webView: WebView? = null

        lateinit var timeoutRunnable: Runnable

        fun finish(result: ProofSession?) {
            if (!finished.compareAndSet(false, true)) return

            handler.removeCallbacks(timeoutRunnable)
            handler.post {
                runCatching { dialog?.setOnDismissListener(null) }
                runCatching { dialog?.dismiss() }
                runCatching {
                    webView?.stopLoading()
                    webView?.loadUrl("about:blank")
                    webView?.removeAllViews()
                    webView?.destroy()
                }
                dialog = null
                webView = null
            }

            continuation.resume(result)
        }

        timeoutRunnable = Runnable { finish(null) }

        handler.post {
            try {
                val activity = AfterDarkRuntime.currentActivity()
            if (activity == null || activity.isFinishing) {
                finish(null)
                return@post
            }

            val targetHost = Uri.parse(mainUrl).host
            val watchUrl = request.watchUrl(mainUrl)

            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.BLACK)
            }

            val info = TextView(activity).apply {
                text = buildString {
                    append("AfterDark — vérification officielle\n")
                    append("Effectue la vérification affichée par AfterDark. ")
                    append("Termine aussi les étapes demandées par le site avant la lecture. ")
                    append("Cette fenêtre se fermera automatiquement quand AfterDark aura émis la preuve.")
                }
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(24, 20, 24, 12)
            }

            val cancel = Button(activity).apply {
                text = "Annuler"
                setOnClickListener { finish(null) }
            }

            val controls = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(16, 0, 16, 10)
                addView(
                    cancel,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }

            val browser = WebView(activity)
            webView = browser

            browser.setBackgroundColor(Color.BLACK)
            browser.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(browser, true)
            }

            // Cache WebView settings on the UI thread.
            val browserUserAgent = browser.settings.userAgentString
                ?.takeIf { it.isNotBlank() }
                ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/149.0 Mobile Safari/537.36"

            fun finishWithCapturedProof(captured: CapturedSourceRequest) {
                // shouldInterceptRequest() is not a UI-thread callback.
                handler.post {
                    if (finished.get()) return@post

                    val cookie = runCatching {
                        CookieManager.getInstance().getCookie(mainUrl)
                    }.getOrNull()

                    finish(
                        ProofSession(
                            proof = captured.proof,
                            cookie = cookie,
                            userAgent = browserUserAgent,
                            sourceRequestUrl = captured.url,
                            sourceRequestHeaders = captured.headers,
                            sourceReferer = captured.referer,
                        ),
                    )
                }
            }

            browser.webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?,
                ): Boolean {
                    if (resultMsg == null) return false

                    /*
                     * AfterDark's verification can use target=_blank/window.open.
                     * Never dispatch those URLs to Android ACTION_VIEW:
                     * - third-party popups are consumed without opening another app;
                     * - same-host AfterDark popups are redirected into this WebView.
                     *
                     * We do not fabricate a proof. The extension still waits until
                     * AfterDark itself emits x-nabi-proof on /api/sources.
                     */
                    val popup = WebView(activity)
                    popup.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(false)
                    }

                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(popup, true)
                    }

                    popup.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val uri = request?.url ?: return true
                            val scheme = uri.scheme?.lowercase()

                            // Same-host navigation stays inside the verification WebView.
                            if (
                                (scheme == "http" || scheme == "https") &&
                                uri.host.equals(targetHost, ignoreCase = true)
                            ) {
                                browser.post {
                                    if (!finished.get()) {
                                        browser.loadUrl(uri.toString())
                                    }
                                }
                            }

                            // Any external popup is swallowed. No browser/app is launched.
                            view?.post {
                                runCatching { view.stopLoading() }
                                runCatching { view.loadUrl("about:blank") }
                                runCatching { view.destroy() }
                            }
                            return true
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            webRequest: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val captured = captureSourceRequest(
                                webRequest = webRequest,
                                playbackRequest = request,
                                targetHost = targetHost,
                            )
                            if (captured != null) {
                                finishWithCapturedProof(captured)
                            }
                            return super.shouldInterceptRequest(view, webRequest)
                        }
                    }

                    val transport = resultMsg.obj as? WebView.WebViewTransport ?: run {
                        runCatching { popup.destroy() }
                        return false
                    }
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }
            }

            browser.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    webRequest: WebResourceRequest?,
                ): Boolean {
                    val uri = webRequest?.url ?: return true

                    // Subframes (including Turnstile) continue to work normally.
                    if (!webRequest.isForMainFrame) return false

                    val scheme = uri.scheme?.lowercase()
                    if (
                        (scheme == "http" || scheme == "https") &&
                        uri.host.equals(targetHost, ignoreCase = true)
                    ) {
                        return false
                    }

                    // Block top-level navigation away from AfterDark.
                    // This prevents intent:// and external-browser handoff paths.
                    return true
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    webRequest: WebResourceRequest?,
                ): WebResourceResponse? {
                    val captured = captureSourceRequest(
                        webRequest = webRequest,
                        playbackRequest = request,
                        targetHost = targetHost,
                    )

                    if (captured != null) {
                        finishWithCapturedProof(captured)
                    }

                    return super.shouldInterceptRequest(view, webRequest)
                }
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

            dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                setContentView(root)
                setCancelable(true)
                setOnCancelListener { finish(null) }
                setOnDismissListener {
                    if (!finished.get()) finish(null)
                }
                show()
            }

                browser.loadUrl(watchUrl)
                handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
            } catch (_: Exception) {
                // A broken/missing WebView component must not crash CloudStream.
                finish(null)
            }
        }
    }

    private fun captureSourceRequest(
        webRequest: WebResourceRequest?,
        playbackRequest: PlaybackRequest,
        targetHost: String?,
    ): CapturedSourceRequest? {
        if (webRequest == null) return null
        if (!webRequest.method.equals("GET", ignoreCase = true)) return null

        val uri = webRequest.url
        if (!uri.host.equals(targetHost, ignoreCase = true)) return null
        if (uri.path != "/api/sources") return null
        if (uri.getQueryParameter("tmdbId") != playbackRequest.tmdbId.toString()) return null
        if (uri.getQueryParameter("type") != playbackRequest.type) return null

        if (playbackRequest.type == "tv") {
            playbackRequest.season?.let {
                if (uri.getQueryParameter("season") != it.toString()) return null
            }
            playbackRequest.episode?.let {
                if (uri.getQueryParameter("episode") != it.toString()) return null
            }
        }

        val headers = LinkedHashMap<String, String>()
        webRequest.requestHeaders.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                headers[key] = value
            }
        }

        val proof = headers.entries
            .firstOrNull { (key, _) -> key.equals(PROOF_HEADER, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val referer = headers.entries
            .firstOrNull { (key, _) -> key.equals("Referer", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }

        return CapturedSourceRequest(
            proof = proof,
            url = uri.toString(),
            headers = headers,
            referer = referer,
        )
    }

}
