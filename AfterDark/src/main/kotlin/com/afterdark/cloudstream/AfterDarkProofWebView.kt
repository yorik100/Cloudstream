package com.afterdark.cloudstream

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.net.Uri
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object AfterDarkProofWebView {
    private const val PROOF_HEADER = "x-nabi-proof"
    private const val TIMEOUT_MS = 180_000L

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun acquire(
        request: PlaybackRequest,
        mainUrl: String,
    ): ProofSession? {
        val deferred = CompletableDeferred<ProofSession?>()
        var dialog: Dialog? = null
        var webView: WebView? = null

        withContext(Dispatchers.Main) {
            val activity = AfterDarkRuntime.currentActivity()
            if (activity == null || activity.isFinishing) {
                deferred.complete(null)
                return@withContext
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
                    append("Si le site demande ses étapes habituelles avant lecture, termine-les aussi. ")
                    append("Cette fenêtre se fermera automatiquement dès que la preuve de lecture aura été émise.")
                }
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(28, 24, 28, 18)
            }

            val cancel = Button(activity).apply {
                text = "Annuler"
                setOnClickListener {
                    if (!deferred.isCompleted) deferred.complete(null)
                    dialog?.dismiss()
                }
            }

            val controls = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(18, 0, 18, 12)
                addView(
                    cancel,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }

            webView = WebView(activity).apply {
                setBackgroundColor(Color.BLACK)

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.loadsImagesAutomatically = true
                settings.mediaPlaybackRequiresUserGesture = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(false)
                settings.cacheMode = WebSettings.LOAD_DEFAULT

                val cookies = CookieManager.getInstance()
                cookies.setAcceptCookie(true)
                cookies.setAcceptThirdPartyCookies(this, true)

                webChromeClient = WebChromeClient()

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        resourceRequest: WebResourceRequest?
                    ): WebResourceResponse? {
                        captureIfProofRequest(
                            view = view,
                            webRequest = resourceRequest,
                            playbackRequest = request,
                            targetHost = targetHost,
                            mainUrl = mainUrl,
                            deferred = deferred,
                            onCaptured = {
                                activity.runOnUiThread {
                                    dialog?.dismiss()
                                }
                            }
                        )
                        return super.shouldInterceptRequest(view, resourceRequest)
                    }
                }
            }

            root.addView(
                info,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            root.addView(
                controls,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            root.addView(
                webView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )

            dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                setContentView(root)
                setCancelable(true)
                setOnCancelListener {
                    if (!deferred.isCompleted) deferred.complete(null)
                }
                setOnDismissListener {
                    if (!deferred.isCompleted) deferred.complete(null)
                }
                show()
            }

            webView?.loadUrl(watchUrl)
        }

        val result = withTimeoutOrNull(TIMEOUT_MS) {
            deferred.await()
        }

        withContext(Dispatchers.Main) {
            runCatching { dialog?.dismiss() }
            runCatching {
                webView?.stopLoading()
                webView?.loadUrl("about:blank")
                webView?.removeAllViews()
                webView?.destroy()
            }
        }

        return result
    }

    private fun captureIfProofRequest(
        view: WebView?,
        webRequest: WebResourceRequest?,
        playbackRequest: PlaybackRequest,
        targetHost: String?,
        mainUrl: String,
        deferred: CompletableDeferred<ProofSession?>,
        onCaptured: () -> Unit,
    ) {
        if (webRequest == null || deferred.isCompleted) return
        if (!webRequest.method.equals("GET", ignoreCase = true)) return

        val uri = webRequest.url ?: return
        if (!uri.host.equals(targetHost, ignoreCase = true)) return
        if (uri.path != "/api/sources") return

        if (uri.getQueryParameter("tmdbId") != playbackRequest.tmdbId.toString()) return
        if (uri.getQueryParameter("type") != playbackRequest.type) return

        if (playbackRequest.type == "tv") {
            playbackRequest.season?.let {
                if (uri.getQueryParameter("season") != it.toString()) return
            }
            playbackRequest.episode?.let {
                if (uri.getQueryParameter("episode") != it.toString()) return
            }
        }

        val proof = webRequest.requestHeaders.entries
            .firstOrNull { it.key.equals(PROOF_HEADER, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: return

        val cookie = CookieManager.getInstance().getCookie(mainUrl)
        val userAgent = view?.settings?.userAgentString
            ?.takeIf { it.isNotBlank() }
            ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/149.0 Mobile Safari/537.36"

        if (deferred.complete(ProofSession(proof, cookie, userAgent))) {
            onCaptured()
        }
    }
}
