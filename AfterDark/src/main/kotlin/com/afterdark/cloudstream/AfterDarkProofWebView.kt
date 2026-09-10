package com.afterdark.cloudstream

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object AfterDarkProofWebView {
    private const val TAG = "AfterDarkProofWebView"
    private const val PROOF_HEADER = "x-nabi-proof"
    private const val TIMEOUT_MS = 180_000L
    private const val CHECKBOX_POLL_INTERVAL_MS = 250L

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun acquire(
        request: PlaybackRequest,
        mainUrl: String,
    ): ProofSession? = suspendCoroutine { continuation ->
        val finished = AtomicBoolean(false)
        val sourceInterceptStarted = AtomicBoolean(false)
        val verificationButtonHasAppeared = AtomicBoolean(false)
        val checkboxReloadInProgress = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        var dialog: Dialog? = null
        var webView: WebView? = null
        var checkboxWatcher: Runnable? = null

        lateinit var timeoutRunnable: Runnable

        fun finish(result: ProofSession?) {
            if (!finished.compareAndSet(false, true)) return

            handler.removeCallbacks(timeoutRunnable)
            checkboxWatcher?.let(handler::removeCallbacks)
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
            val verificationHostForJs = targetHost
                .orEmpty()
                .replace("\\", "\\\\")
                .replace("'", "\\'")

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
            browser.isFocusable = true
            browser.isFocusableInTouchMode = true
            browser.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = false
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

            fun installAutoOpenAndPlay(
                target: WebView?,
                reloadOnInteractiveCheckbox: Boolean,
            ) {
                if (target == null || finished.get()) return

                target.evaluateJavascript(
                    """
                    (() => {
                      const OPEN_LINK_TEXT = "ouvrir le lien";
                      const EXPECTED_HOST = '$verificationHostForJs';
                      const RELOAD_ON_INTERACTIVE_CHECKBOX =
                        ${if (reloadOnInteractiveCheckbox) "true" else "false"};
                      const SEEN_KEY = "__afterdark_verification_button_seen";

                      if (
                        !EXPECTED_HOST ||
                        String(location.hostname || "").toLowerCase() !==
                          EXPECTED_HOST.toLowerCase()
                      ) {
                        return;
                      }

                      const normalize = value =>
                        String(value || "").replace(/\\s+/g, " ").trim();

                      const wasSeen = () => {
                        if (window.__afterdarkVerificationButtonSeen === true) {
                          return true;
                        }

                        try {
                          return sessionStorage.getItem(SEEN_KEY) === "1";
                        } catch (_) {
                          return false;
                        }
                      };

                      const stopCheckboxWatcher = () => {
                        const timer = window.__afterdarkCheckboxWatcher;
                        if (timer) {
                          clearInterval(timer);
                          window.__afterdarkCheckboxWatcher = null;
                        }
                      };

                      const markSeen = () => {
                        window.__afterdarkVerificationButtonSeen = true;
                        try {
                          sessionStorage.setItem(SEEN_KEY, "1");
                        } catch (_) {}
                        try {
                          window.AfterDarkNative.verificationButtonSeen();
                        } catch (_) {}
                      };

                      const buttonState = element => {
                        const text = normalize(element && element.textContent);
                        if (!text) return null;
                        const normalizedText =
                          text.toLocaleLowerCase("fr-FR");
                        return normalizedText.includes(OPEN_LINK_TEXT)
                          ? normalizedText
                          : null;
                      };

                      const findAndClick = () => {
                        const candidates = Array.from(
                          document.querySelectorAll('a,button,[role="button"]')
                        );

                        const button = candidates.find(
                          element => buttonState(element) !== null
                        );

                        if (!button) return false;

                        // Once this button has appeared, a later/disappearing
                        // checkbox must never reload this verification.
                        markSeen();

                        const state = buttonState(button);
                        const openedStates =
                          window.__afterdarkAutoOpenedStates || new Set();
                        window.__afterdarkAutoOpenedStates = openedStates;

                        // React can reuse the same button or replace it between
                        // any number of intermediate steps. Each distinct text
                        // is clicked once, regardless of the DOM element.
                        if (!state || openedStates.has(state)) return true;

                        openedStates.add(state);
                        button.dataset.afterdarkAutoOpenedState = state;
                        button.click();
                        return true;
                      };

                      const isVisibleInteractiveCheckbox = element => {
                        if (!element || element.disabled) return false;

                        try {
                          const style = element.ownerDocument.defaultView
                            .getComputedStyle(element);
                          if (
                            style.display === "none" ||
                            style.visibility === "hidden"
                          ) return false;
                          const bounds = element.getBoundingClientRect();
                          if (bounds.width <= 0 || bounds.height <= 0) return false;
                        } catch (_) {}

                        return true;
                      };

                      const documentHasInteractiveCheckbox = documentRoot => {
                        if (!documentRoot) return false;

                        const candidates = Array.from(
                          documentRoot.querySelectorAll(
                            'input[type="checkbox"], [role="checkbox"]'
                          )
                        );
                        if (candidates.some(isVisibleInteractiveCheckbox)) {
                          return true;
                        }

                        // Same-origin frames can be inspected directly. The
                        // native accessibility watcher handles Cloudflare's
                        // usual cross-origin Turnstile frame.
                        for (const frame of documentRoot.querySelectorAll("iframe")) {
                          try {
                            if (documentHasInteractiveCheckbox(frame.contentDocument)) {
                              return true;
                            }
                          } catch (_) {}
                        }

                        return false;
                      };

                      const reportInteractiveCheckbox = () => {
                        if (
                          !RELOAD_ON_INTERACTIVE_CHECKBOX ||
                          wasSeen() ||
                          window.__afterdarkCheckboxReported === true
                        ) return false;

                        if (!documentHasInteractiveCheckbox(document)) return false;

                        window.__afterdarkCheckboxReported = true;
                        stopCheckboxWatcher();
                        try {
                          window.AfterDarkNative.interactiveCheckboxSeen();
                        } catch (_) {}
                        return true;
                      };

                      findAndClick();
                      if (reportInteractiveCheckbox()) return;

                      if (window.__afterdarkAutoOpenObserver) {
                        try { window.__afterdarkAutoOpenObserver.disconnect(); } catch (_) {}
                      }

                      const observer = new MutationObserver(() => {
                        findAndClick();
                        if (reportInteractiveCheckbox()) {
                          try { observer.disconnect(); } catch (_) {}
                          window.__afterdarkAutoOpenObserver = null;
                        }
                      });

                      observer.observe(document.documentElement, {
                        childList: true,
                        subtree: true,
                        characterData: true
                      });

                      window.__afterdarkAutoOpenObserver = observer;

                      stopCheckboxWatcher();
                      if (RELOAD_ON_INTERACTIVE_CHECKBOX && !wasSeen()) {
                        window.__afterdarkCheckboxWatcher = setInterval(() => {
                          findAndClick();
                          if (reportInteractiveCheckbox()) {
                            stopCheckboxWatcher();
                          }
                        }, 250);
                      }
                    })();
                    """.trimIndent(),
                    null,
                )
            }

            fun hasInteractiveCheckbox(target: WebView): Boolean {
                val rootNode = runCatching {
                    target.createAccessibilityNodeInfo()
                }.getOrNull() ?: return false
                val pendingNodes = ArrayDeque<AccessibilityNodeInfo>()
                pendingNodes.add(rootNode)
                var visitedNodes = 0

                try {
                    while (pendingNodes.isNotEmpty() && visitedNodes < 512) {
                        val node = pendingNodes.removeFirst()
                        visitedNodes++

                        val isCheckbox = node.isCheckable ||
                            node.className
                                ?.toString()
                                ?.contains("CheckBox", ignoreCase = true) == true

                        if (
                            isCheckbox &&
                            node.isEnabled &&
                            node.isVisibleToUser
                        ) {
                            runCatching { node.recycle() }
                            return true
                        }

                        for (index in 0 until node.childCount) {
                            runCatching { node.getChild(index) }
                                .getOrNull()
                                ?.let(pendingNodes::addLast)
                        }
                        runCatching { node.recycle() }
                    }
                } finally {
                    while (pendingNodes.isNotEmpty()) {
                        runCatching { pendingNodes.removeFirst().recycle() }
                    }
                }

                return false
            }

            fun reloadForInteractiveCheckbox() {
                if (
                    finished.get() ||
                    verificationButtonHasAppeared.get() ||
                    !checkboxReloadInProgress.compareAndSet(false, true)
                ) return

                Log.i(TAG, "Checkbox interactive détectée, rechargement de la vérification")
                browser.post {
                    if (!finished.get() && !verificationButtonHasAppeared.get()) {
                        browser.reload()
                    }
                }
            }

            browser.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun verificationButtonSeen() {
                        verificationButtonHasAppeared.set(true)
                    }

                    @JavascriptInterface
                    fun interactiveCheckboxSeen() {
                        handler.post { reloadForInteractiveCheckbox() }
                    }
                },
                "AfterDarkNative",
            )

            // Unlike evaluateJavascript(), a document-start script is installed
            // in every frame, including Cloudflare's cross-origin Turnstile frame.
            val frameDetectorInstalled = runCatching {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    false
                } else {
                    WebViewCompat.addDocumentStartJavaScript(
                        browser,
                        """
                        (() => {
                          if (window.__afterdarkCheckboxFrameDetector) return;
                          window.__afterdarkCheckboxFrameDetector = true;

                          const isCloudflareFrame = () => {
                            try {
                              const host = String(location.hostname || "").toLowerCase();
                              return host === "challenges.cloudflare.com" ||
                                host.endsWith(".challenges.cloudflare.com");
                            } catch (_) {
                              return false;
                            }
                          };

                          if (!isCloudflareFrame()) return;

                          const roots = new Set([document]);

                          // Le widget Turnstile place son input dans un ShadowRoot
                          // fermé. Un script document-start peut conserver la racine
                          // au moment exact où Cloudflare la crée, même si shadowRoot
                          // retourne ensuite null pour le code JavaScript ordinaire.
                          try {
                            const nativeAttachShadow = Element.prototype.attachShadow;
                            Element.prototype.attachShadow = function() {
                              const shadowRoot = nativeAttachShadow.apply(this, arguments);
                              roots.add(shadowRoot);
                              return shadowRoot;
                            };
                          } catch (_) {}

                          // Turnstile masque l'input natif et dessine la case autour.
                          // Sa présence dans l'iframe Cloudflare suffit donc : contrôler
                          // ses dimensions ou sa visibilité rejetterait la vraie case.
                          const isInteractiveCheckbox = element =>
                            Boolean(element) && !element.disabled;

                          const report = () => {
                            for (const root of Array.from(roots)) {
                              for (const element of root.querySelectorAll("*")) {
                                if (element.shadowRoot) roots.add(element.shadowRoot);
                              }
                            }

                            const checkbox = Array.from(roots)
                              .flatMap(root => Array.from(root.querySelectorAll(
                                'input[type="checkbox"], [role="checkbox"]'
                              )))
                              .find(isInteractiveCheckbox);
                            if (!checkbox) return false;

                            try {
                              window.AfterDarkNative.interactiveCheckboxSeen();
                              return true;
                            } catch (_) {
                              return false;
                            }
                          };

                          const start = () => {
                            if (report()) return;

                            const observer = new MutationObserver(() => {
                              if (report()) observer.disconnect();
                            });
                            observer.observe(document.documentElement, {
                              childList: true,
                              subtree: true,
                              attributes: true,
                              attributeFilter: [
                                "type",
                                "role",
                                "disabled",
                                "style",
                                "class"
                              ]
                            });

                            const poller = setInterval(() => {
                              if (report()) clearInterval(poller);
                            }, 100);
                          };

                          if (document.readyState === "loading") {
                            document.addEventListener(
                              "DOMContentLoaded",
                              start,
                              { once: true }
                            );
                          } else {
                            start();
                          }
                        })();
                        """.trimIndent(),
                        setOf("*"),
                    )
                    true
                }
            }.getOrElse { error ->
                Log.e(TAG, "Impossible d'installer le détecteur multi-frame", error)
                false
            }

            if (frameDetectorInstalled) {
                Log.i(TAG, "Détecteur checkbox installé dans toutes les frames")
            } else {
                Log.w(TAG, "Détecteur multi-frame indisponible")
            }

            // Cloudflare Turnstile usually lives in a cross-origin iframe,
            // which page JavaScript cannot inspect. The rendered interactive
            // checkbox is still exposed through WebView's accessibility tree.
            checkboxWatcher = object : Runnable {
                override fun run() {
                    if (finished.get()) return

                    if (
                        !verificationButtonHasAppeared.get() &&
                        !checkboxReloadInProgress.get() &&
                        hasInteractiveCheckbox(browser)
                    ) {
                        reloadForInteractiveCheckbox()
                    }

                    if (!finished.get()) {
                        handler.postDelayed(this, CHECKBOX_POLL_INTERVAL_MS)
                    }
                }
            }
            handler.post(checkboxWatcher!!)

            fun finishWithCapturedResponse(captured: CapturedSourceResponse) {
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
                            sourceResponseStatus = captured.statusCode,
                            sourceResponseBody = captured.body,
                        ),
                    )
                }
            }

            fun interceptOfficialSources(
                webRequest: WebResourceRequest?,
            ): WebResourceResponse? {
                val requestInfo = captureSourceRequest(
                    webRequest = webRequest,
                    playbackRequest = request,
                    targetHost = targetHost,
                ) ?: return null

                // WebView can expose the same resource through more than one
                // client/window. Only one actual /api/sources request is allowed.
                if (!sourceInterceptStarted.compareAndSet(false, true)) {
                    return WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        ByteArrayInputStream(ByteArray(0)),
                    )
                }

                val intercepted = runCatching {
                    executeSourceRequestOnce(
                        requestInfo = requestInfo,
                    )
                }.getOrNull()

                if (intercepted == null) {
                    // Network interception failed before AfterDark answered.
                    // Do not fabricate a result or close verification.
                    sourceInterceptStarted.set(false)
                    return null
                }

                finishWithCapturedResponse(intercepted.captured)
                return intercepted.webResponse
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
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(false)
                    }

                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(popup, true)
                    }

                    popup.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(
                            view: WebView?,
                            url: String?,
                        ) {
                            super.onPageFinished(view, url)
                            installAutoOpenAndPlay(
                                view,
                                reloadOnInteractiveCheckbox = false,
                            )
                        }

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
                            return interceptOfficialSources(webRequest)
                                ?: super.shouldInterceptRequest(view, webRequest)
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
                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?,
                ) {
                    checkboxReloadInProgress.set(true)
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?,
                ) {
                    super.onPageFinished(view, url)
                    checkboxReloadInProgress.set(false)
                    installAutoOpenAndPlay(
                        view,
                        reloadOnInteractiveCheckbox = true,
                    )
                }

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
                    return interceptOfficialSources(webRequest)
                        ?: super.shouldInterceptRequest(view, webRequest)
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

                browser.requestFocus()
                browser.loadUrl(watchUrl)
                handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
            } catch (_: Exception) {
                // A broken/missing WebView component must not crash CloudStream.
                finish(null)
            }
        }
    }

    private data class SourceRequestInfo(
        val proof: String,
        val url: String,
        val headers: Map<String, String>,
        val referer: String?,
    )

    private data class InterceptedSource(
        val captured: CapturedSourceResponse,
        val webResponse: WebResourceResponse,
    )

    private fun captureSourceRequest(
        webRequest: WebResourceRequest?,
        playbackRequest: PlaybackRequest,
        targetHost: String?,
    ): SourceRequestInfo? {
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

        return SourceRequestInfo(
            proof = proof,
            url = uri.toString(),
            headers = headers,
            referer = referer,
        )
    }

    private fun executeSourceRequestOnce(
        requestInfo: SourceRequestInfo,
    ): InterceptedSource {
        val connection = (URL(requestInfo.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 30_000
            readTimeout = 150_000
            useCaches = false

            requestInfo.headers.forEach { (key, value) ->
                if (
                    !key.equals("Host", ignoreCase = true) &&
                    !key.equals("Connection", ignoreCase = true) &&
                    !key.equals("Content-Length", ignoreCase = true) &&
                    !key.equals("Cookie", ignoreCase = true) &&
                    !key.equals("Accept-Encoding", ignoreCase = true)
                ) {
                    setRequestProperty(key, value)
                }
            }

            // Keep the intercepted response readable as NDJSON text.
            setRequestProperty("Accept-Encoding", "identity")

            CookieManager.getInstance()
                .getCookie(requestInfo.url)
                ?.takeIf { it.isNotBlank() }
                ?.let { setRequestProperty("Cookie", it) }
        }

        try {
            val statusCode = connection.responseCode
            val responseStream = if (statusCode >= 400) {
                connection.errorStream
            } else {
                connection.inputStream
            }

            val bytes = responseStream?.use { it.readBytes() } ?: ByteArray(0)
            val body = bytes.toString(Charsets.UTF_8)
            val mimeType = connection.contentType
                ?.substringBefore(";")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "application/x-ndjson"

            val reason = connection.responseMessage
                ?.takeIf { it.isNotBlank() }
                ?: "HTTP $statusCode"

            val responseHeaders = LinkedHashMap<String, String>()
            connection.headerFields.forEach { (key, values) ->
                if (key != null && !values.isNullOrEmpty()) {
                    if (
                        !key.equals("Content-Encoding", ignoreCase = true) &&
                        !key.equals("Content-Length", ignoreCase = true)
                    ) {
                        responseHeaders[key] = values.joinToString(", ")
                    }
                }
            }

            val captured = CapturedSourceResponse(
                proof = requestInfo.proof,
                url = requestInfo.url,
                headers = requestInfo.headers,
                referer = requestInfo.referer,
                statusCode = statusCode,
                body = body,
            )

            val webResponse = WebResourceResponse(
                mimeType,
                "UTF-8",
                statusCode,
                reason,
                responseHeaders,
                ByteArrayInputStream(bytes),
            )

            return InterceptedSource(
                captured = captured,
                webResponse = webResponse,
            )
        } finally {
            connection.disconnect()
        }
    }

}
