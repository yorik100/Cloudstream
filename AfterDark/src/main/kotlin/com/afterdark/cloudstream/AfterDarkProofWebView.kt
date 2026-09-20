package com.afterdark.cloudstream

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.json.JSONObject

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
        val officialPlayerMode = AtomicBoolean(false)
        val sourceFailoverInProgress = AtomicBoolean(false)
        val preferredSourceSelectionDone = AtomicBoolean(false)
        val playbackControlsHidden = AtomicBoolean(false)
        val mediaCaptureArmed = AtomicBoolean(false)
        val currentFallbackService = AtomicReference<String?>(null)
        val emptyOfficialResponse = AtomicReference<CapturedSourceResponse?>(null)
        val verificationButtonHasAppeared = AtomicBoolean(false)
        val verificationCheckboxPresent = AtomicBoolean(false)
        val cloudflareErrorReloadInProgress = AtomicBoolean(false)
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

        timeoutRunnable = Runnable {
            if (!officialPlayerMode.get()) {
                finish(null)
            }
        }

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
                interactWithVerificationButton: Boolean,
            ) {
                if (target == null || finished.get()) return

                target.evaluateJavascript(
                    """
                    (() => {
                      const OPEN_LINK_TEXT = "ouvrir le lien";
                      const EXPECTED_HOST = '$verificationHostForJs';
                      const INTERACT_WITH_VERIFICATION_BUTTON =
                        ${if (interactWithVerificationButton) "true" else "false"};
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

                        // Remember that AfterDark's verification/open-link
                        // button has appeared. This does not suppress later
                        // Turnstile interaction.
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
                          !INTERACT_WITH_VERIFICATION_BUTTON ||
                          window.__afterdarkCheckboxReported === true
                        ) return false;

                        if (!documentHasInteractiveCheckbox(document)) return false;

                        window.__afterdarkCheckboxReported = true;
                        stopCheckboxWatcher();

                        // Do not reload. Ask native code to tap the rendered
                        // control through Android's WebView input pipeline.
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
                      if (INTERACT_WITH_VERIFICATION_BUTTON) {
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

            fun interactWithInteractiveCheckbox(target: WebView): Boolean {
                val rootNode = runCatching {
                    target.createAccessibilityNodeInfo()
                }.getOrNull() ?: run {
                    verificationCheckboxPresent.set(false)
                    return false
                }

                val pendingNodes = ArrayDeque<AccessibilityNodeInfo>()
                pendingNodes.add(rootNode)
                var visitedNodes = 0
                var found = false

                try {
                    while (pendingNodes.isNotEmpty() && visitedNodes < 512) {
                        val node = pendingNodes.removeFirst()
                        visitedNodes++

                        val isCheckbox = node.isCheckable ||
                            node.className
                                ?.toString()
                                ?.contains("CheckBox", ignoreCase = true) == true ||
                            node.contentDescription
                                ?.toString()
                                ?.contains("checkbox", ignoreCase = true) == true

                        if (
                            isCheckbox &&
                            node.isEnabled &&
                            node.isVisibleToUser
                        ) {
                            found = true

                            // Tap only on the transition "not present" ->
                            // "present". If Cloudflare replaces the control,
                            // it disappears first and the watcher re-arms.
                            if (
                                verificationCheckboxPresent.compareAndSet(
                                    false,
                                    true,
                                )
                            ) {
                                val nodeBounds = Rect()
                                node.getBoundsInScreen(nodeBounds)

                                val webViewLocation = IntArray(2)
                                target.getLocationOnScreen(webViewLocation)

                                val rawX =
                                    nodeBounds.exactCenterX() - webViewLocation[0]
                                val rawY =
                                    nodeBounds.exactCenterY() - webViewLocation[1]

                                val width = target.width.toFloat()
                                val height = target.height.toFloat()

                                val localX =
                                    if (width > 2f) {
                                        rawX.coerceIn(1f, width - 1f)
                                    } else {
                                        rawX
                                    }

                                val localY =
                                    if (height > 2f) {
                                        rawY.coerceIn(1f, height - 1f)
                                    } else {
                                        rawY
                                    }

                                val boundsUsable =
                                    !nodeBounds.isEmpty &&
                                    target.width > 0 &&
                                    target.height > 0 &&
                                    rawX >= 0f &&
                                    rawY >= 0f &&
                                    rawX <= width &&
                                    rawY <= height

                                if (boundsUsable) {
                                    target.requestFocus()

                                    val downTime = SystemClock.uptimeMillis()
                                    val downEvent = MotionEvent.obtain(
                                        downTime,
                                        downTime,
                                        MotionEvent.ACTION_DOWN,
                                        localX,
                                        localY,
                                        0,
                                    ).apply {
                                        source = InputDevice.SOURCE_TOUCHSCREEN
                                    }

                                    val downHandled = runCatching {
                                        target.dispatchTouchEvent(downEvent)
                                    }.getOrDefault(false)
                                    downEvent.recycle()

                                    target.postDelayed(
                                        {
                                            if (
                                                finished.get() ||
                                                officialPlayerMode.get()
                                            ) {
                                                return@postDelayed
                                            }

                                            val upTime =
                                                SystemClock.uptimeMillis()
                                            val upEvent = MotionEvent.obtain(
                                                downTime,
                                                upTime,
                                                MotionEvent.ACTION_UP,
                                                localX,
                                                localY,
                                                0,
                                            ).apply {
                                                source =
                                                    InputDevice.SOURCE_TOUCHSCREEN
                                            }

                                            val upHandled = runCatching {
                                                target.dispatchTouchEvent(
                                                    upEvent,
                                                )
                                            }.getOrDefault(false)
                                            upEvent.recycle()

                                            Log.i(
                                                TAG,
                                                "Tap natif Turnstile " +
                                                    "x=$localX y=$localY " +
                                                    "DOWN=$downHandled " +
                                                    "UP=$upHandled " +
                                                    "bounds=$nodeBounds",
                                            )
                                        },
                                        85L,
                                    )
                                } else {
                                    // Let the next scan retry if the
                                    // accessibility node had unusable bounds.
                                    verificationCheckboxPresent.set(false)

                                    Log.w(
                                        TAG,
                                        "Turnstile détecté mais coordonnées " +
                                            "inutilisables: bounds=$nodeBounds " +
                                            "webView=${target.width}x${target.height}",
                                    )
                                }
                            }

                            runCatching { node.recycle() }
                            break
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

                if (!found) {
                    verificationCheckboxPresent.set(false)
                }

                return found
            }

            fun reloadForCloudflareError(
                code: String?,
                source: String?,
                details: String?,
            ) {
                if (
                    finished.get() ||
                    officialPlayerMode.get() ||
                    !cloudflareErrorReloadInProgress.compareAndSet(false, true)
                ) return

                verificationCheckboxPresent.set(false)

                Log.w(
                    TAG,
                    "Erreur Cloudflare détectée, reload: " +
                        "code=${code.orEmpty()} source=${source.orEmpty()} " +
                        "details=${details.orEmpty().take(500)}",
                )

                browser.post {
                    if (!finished.get() && !officialPlayerMode.get()) {
                        browser.reload()
                    } else {
                        cloudflareErrorReloadInProgress.set(false)
                    }
                }
            }

            fun mediaType(
                url: String,
                contentType: String = "",
            ): String? {
                val lowerUrl = url.lowercase()
                val cleanPath = lowerUrl.substringBefore("?").substringBefore("#")
                val lowerType = contentType.lowercase()

                if (
                    cleanPath.endsWith(".ts") ||
                    cleanPath.endsWith(".m4s") ||
                    cleanPath.endsWith(".aac") ||
                    cleanPath.endsWith(".vtt") ||
                    cleanPath.endsWith(".srt")
                ) return null

                return when {
                    cleanPath.endsWith(".m3u8") ||
                        "mpegurl" in lowerType -> "m3u8"

                    cleanPath.endsWith(".mpd") ||
                        "dash+xml" in lowerType -> "mpd"

                    cleanPath.endsWith(".mp4") ||
                        cleanPath.endsWith(".mkv") ||
                        cleanPath.endsWith(".webm") ||
                        lowerType.startsWith("video/") ||
                        lowerType == "application/octet-stream" -> "video"

                    else -> null
                }
            }

            fun finishWithResolvedMedia(
                media: ResolvedWebMedia,
            ) {
                if (
                    !officialPlayerMode.get() ||
                    !mediaCaptureArmed.compareAndSet(true, false) ||
                    finished.get()
                ) return

                val captured = emptyOfficialResponse.get() ?: run {
                    mediaCaptureArmed.set(true)
                    return
                }

                handler.post {
                    if (finished.get()) return@post

                    val headers = LinkedHashMap<String, String>(media.headers)

                    if (
                        headers.keys.none {
                            it.equals("User-Agent", ignoreCase = true)
                        }
                    ) {
                        headers["User-Agent"] = browserUserAgent
                    }

                    val mediaCookie = runCatching {
                        CookieManager.getInstance().getCookie(media.url)
                    }.getOrNull()

                    if (
                        !mediaCookie.isNullOrBlank() &&
                        headers.keys.none {
                            it.equals("Cookie", ignoreCase = true)
                        }
                    ) {
                        headers["Cookie"] = mediaCookie
                    }

                    val resolved = media.copy(headers = headers)

                    Log.i(
                        TAG,
                        "Flux média officiel capturé: ${resolved.type} ${resolved.url}",
                    )

                    finish(
                        ProofSession(
                            proof = captured.proof,
                            cookie = runCatching {
                                CookieManager.getInstance().getCookie(mainUrl)
                            }.getOrNull(),
                            userAgent = browserUserAgent,
                            sourceRequestUrl = captured.url,
                            sourceRequestHeaders = captured.headers,
                            sourceReferer = captured.referer,
                            sourceResponseStatus = captured.statusCode,
                            sourceResponseBody = captured.body,
                            resolvedMedia = resolved,
                            resolvedService =
                                currentFallbackService.get() ?: "AfterDark",
                        ),
                    )
                }
            }

            fun captureOfficialPlayerMedia(
                webRequest: WebResourceRequest?,
            ): ResolvedWebMedia? {
                if (
                    !officialPlayerMode.get() ||
                    !mediaCaptureArmed.get() ||
                    webRequest == null ||
                    !webRequest.method.equals("GET", ignoreCase = true)
                ) return null

                val url = webRequest.url.toString()
                if (
                    !url.startsWith("http://", ignoreCase = true) &&
                    !url.startsWith("https://", ignoreCase = true)
                ) return null

                val headers = LinkedHashMap<String, String>()
                webRequest.requestHeaders.forEach { (key, value) ->
                    if (key.isNotBlank() && value.isNotBlank()) {
                        headers[key] = value
                    }
                }

                val accept = headers.entries
                    .firstOrNull { (key, _) ->
                        key.equals("Accept", ignoreCase = true)
                    }
                    ?.value
                    .orEmpty()

                val type = mediaType(url, accept) ?: return null

                val referer = headers.entries
                    .firstOrNull { (key, _) ->
                        key.equals("Referer", ignoreCase = true)
                    }
                    ?.value
                    ?.takeIf { it.isNotBlank() }

                return ResolvedWebMedia(
                    url = url,
                    referer = referer,
                    headers = headers,
                    type = type,
                )
            }

            fun reportMediaFromJavaScript(
                url: String?,
                contentType: String?,
                pageUrl: String?,
            ) {
                if (
                    !officialPlayerMode.get() ||
                    !mediaCaptureArmed.get() ||
                    finished.get()
                ) return

                val mediaUrl = url
                    ?.trim()
                    ?.takeIf {
                        it.startsWith("http://", ignoreCase = true) ||
                            it.startsWith("https://", ignoreCase = true)
                    }
                    ?: return

                val type = mediaType(
                    url = mediaUrl,
                    contentType = contentType.orEmpty(),
                ) ?: return

                val referer = pageUrl
                    ?.trim()
                    ?.takeIf {
                        it.startsWith("http://", ignoreCase = true) ||
                            it.startsWith("https://", ignoreCase = true)
                    }

                finishWithResolvedMedia(
                    ResolvedWebMedia(
                        url = mediaUrl,
                        referer = referer,
                        headers = emptyMap(),
                        type = type,
                    ),
                )
            }

            fun hideOfficialControlsAfterPlay() {
                if (
                    !officialPlayerMode.get() ||
                    finished.get() ||
                    !playbackControlsHidden.compareAndSet(false, true)
                ) return

                browser.post {
                    if (finished.get() || !officialPlayerMode.get()) return@post

                    browser.evaluateJavascript(
                        """
                        (() => {
                          const normalize = value =>
                            String(value || "")
                              .replace(/\s+/g, " ")
                              .trim()
                              .toLocaleLowerCase("fr-FR");

                          const hide = element => {
                            if (!element) return;
                            element.style.setProperty(
                              "display",
                              "none",
                              "important"
                            );
                            element.setAttribute(
                              "data-afterdark-hidden-after-play",
                              "true"
                            );
                          };

                          const buttons = Array.from(
                            document.querySelectorAll("button")
                          );

                          const back = buttons.find(button =>
                            normalize(button.getAttribute("aria-label")) ===
                              "retour"
                          );

                          const sources = buttons.find(button =>
                            normalize(button.textContent) === "sources"
                          );

                          // If they share the exact AfterDark toolbar wrapper,
                          // hide the wrapper. Otherwise hide both controls.
                          //
                          // They remain in the DOM intentionally: the fallback
                          // controller can still trigger Sources.click() later
                          // if the current provider exposes "Go Back".
                          if (
                            back &&
                            sources &&
                            back.parentElement &&
                            back.parentElement === sources.parentElement
                          ) {
                            hide(back.parentElement);
                          } else {
                            hide(back);
                            hide(sources);
                          }
                        })();
                        """.trimIndent(),
                        null,
                    )
                }
            }

            fun selectPreferredOfficialSource() {
                if (
                    !officialPlayerMode.get() ||
                    finished.get() ||
                    preferredSourceSelectionDone.get()
                ) return

                browser.post {
                    if (
                        finished.get() ||
                        !officialPlayerMode.get() ||
                        preferredSourceSelectionDone.get()
                    ) return@post

                    browser.evaluateJavascript(
                        """
                        (() => {
                          if (
                            window.__afterdarkPreferredSourceSelected ||
                            window.__afterdarkPreferredSourceLocked ||
                            window.__afterdarkPreferredSourceObserverInstalled
                          ) return;

                          window.__afterdarkPreferredSourceObserverInstalled = true;

                          const normalize = value =>
                            String(value || "")
                              .replace(/\s+/g, " ")
                              .trim()
                              .toLocaleLowerCase("fr-FR");

                          const interactive = () =>
                            Array.from(
                              document.querySelectorAll(
                                'button,a,[role="button"]'
                              )
                            );

                          const findSourcesButton = () =>
                            interactive().find(element =>
                              normalize(element.textContent) === "sources"
                            );

                          const findVideasyButton = () =>
                            interactive().find(element => {
                              const text = normalize(element.textContent);
                              return text === "videasy" ||
                                     text.includes("videasy");
                            });

                          let sourceMenuOpened = false;

                          const trySelectVideasy = () => {
                            if (
                              window.__afterdarkPreferredSourceSelected ||
                              window.__afterdarkPreferredSourceLocked
                            ) {
                              return true;
                            }

                            if (!sourceMenuOpened) {
                              const sources = findSourcesButton();
                              if (!sources) return false;

                              try {
                                // The Sources control may be hidden visually by
                                // our cleanup, but its React click handler still
                                // opens the official AfterDark source selector.
                                sources.click();
                                sourceMenuOpened = true;
                              } catch (_) {
                                return false;
                              }
                            }

                            const videasy = findVideasyButton();
                            if (!videasy) return false;

                            window.__afterdarkPreferredSourceSelected = true;
                            window.__afterdarkPreferredSourceLocked = true;

                            try {
                              videasy.click();
                            } catch (_) {
                              window.__afterdarkPreferredSourceSelected = false;
                              window.__afterdarkPreferredSourceLocked = false;
                              return false;
                            }

                            try {
                              window.AfterDarkNative.preferredSourceSelected(
                                "videasy"
                              );
                            } catch (_) {}

                            return true;
                          };

                          if (trySelectVideasy()) {
                            window.__afterdarkPreferredSourceObserverInstalled = false;
                            return;
                          }

                          const observer = new MutationObserver(() => {
                            if (trySelectVideasy()) {
                              try { observer.disconnect(); } catch (_) {}
                              window.__afterdarkPreferredSourceObserverInstalled = false;
                            }
                          });

                          observer.observe(document.documentElement, {
                            childList: true,
                            subtree: true,
                            characterData: true,
                            attributes: true,
                            attributeFilter: [
                              "class",
                              "style",
                              "aria-hidden"
                            ]
                          });

                          window.__afterdarkPreferredSourceObserver = observer;
                        })();
                        """.trimIndent(),
                        null,
                    )
                }
            }

            fun advanceOfficialSourceAfterFailure(
                failedHost: String?,
            ) {
                if (!officialPlayerMode.get() || finished.get()) return

                val failed = failedHost
                    ?.trim()
                    ?.lowercase()
                    .orEmpty()

                // Peachify was already the last allowed AfterDark fallback.
                if (failed.contains("peachify")) {
                    finish(null)
                    return
                }

                if (!sourceFailoverInProgress.compareAndSet(false, true)) return

                preferredSourceSelectionDone.set(true)
                mediaCaptureArmed.set(false)

                browser.post {
                    if (finished.get() || !officialPlayerMode.get()) {
                        sourceFailoverInProgress.set(false)
                        return@post
                    }

                    browser.evaluateJavascript(
                        """
                        (() => {
                          if (window.__afterdarkOfficialFailoverRunning) return;
                          window.__afterdarkOfficialFailoverRunning = true;
                          window.__afterdarkOfficialSourceAdvanced = false;
                          window.__afterdarkPreferredSourceLocked = true;

                          const normalize = value =>
                            String(value || "")
                              .replace(/\s+/g, " ")
                              .trim()
                              .toLocaleLowerCase("fr-FR");

                          const interactive = () =>
                            Array.from(
                              document.querySelectorAll(
                                'button,a,[role="button"]'
                              )
                            );

                          const sourcesButton = () =>
                            interactive().find(element =>
                              normalize(element.textContent) === "sources"
                            );

                          const peachifyButton = () =>
                            interactive().find(element => {
                              const text = normalize(element.textContent);
                              return text.includes("peachify");
                            });

                          let observer = null;
                          let menuDeadline = null;

                          const cleanup = () => {
                            if (observer) {
                              try { observer.disconnect(); } catch (_) {}
                              observer = null;
                            }
                            if (menuDeadline) {
                              clearTimeout(menuDeadline);
                              menuDeadline = null;
                            }
                            window.__afterdarkOfficialFailoverRunning = false;
                          };

                          const chooseNext = () => {
                            const next = peachifyButton();
                            if (!next) return false;

                            window.__afterdarkOfficialSourceAdvanced = true;
                            try { next.click(); } catch (_) {
                              cleanup();
                              try {
                                window.AfterDarkNative.noNextSource();
                              } catch (_) {}
                              return true;
                            }

                            cleanup();
                            try {
                              window.AfterDarkNative.sourceAdvanced("peachify");
                            } catch (_) {}
                            return true;
                          };

                          const toggle = sourcesButton();
                          if (!toggle) {
                            cleanup();
                            try {
                              window.AfterDarkNative.noNextSource();
                            } catch (_) {}
                            return;
                          }

                          // The button may be visually hidden by our UI cleanup;
                          // HTMLElement.click() still drives AfterDark's React
                          // handler and therefore changes source through the
                          // official page rather than by navigating ourselves.
                          try { toggle.click(); } catch (_) {
                            cleanup();
                            try {
                              window.AfterDarkNative.noNextSource();
                            } catch (_) {}
                            return;
                          }

                          if (chooseNext()) return;

                          observer = new MutationObserver(() => {
                            chooseNext();
                          });

                          observer.observe(document.documentElement, {
                            childList: true,
                            subtree: true,
                            characterData: true,
                            attributes: true,
                            attributeFilter: ["class", "style", "aria-hidden"]
                          });

                          // This deadline is only for the AfterDark source menu
                          // rendering. It is NOT used to decide whether a media
                          // source is available.
                          menuDeadline = setTimeout(() => {
                            if (window.__afterdarkOfficialSourceAdvanced) return;
                            cleanup();
                            try {
                              window.AfterDarkNative.noNextSource();
                            } catch (_) {}
                          }, 3000);
                        })();
                        """.trimIndent(),
                        null,
                    )
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
                        handler.post {
                            if (
                                !finished.get() &&
                                !officialPlayerMode.get() &&
                                !cloudflareErrorReloadInProgress.get()
                            ) {
                                interactWithInteractiveCheckbox(browser)
                            }
                        }
                    }

                    @JavascriptInterface
                    fun cloudflareError(
                        code: String?,
                        source: String?,
                        details: String?,
                    ) {
                        handler.post {
                            reloadForCloudflareError(
                                code = code,
                                source = source,
                                details = details,
                            )
                        }
                    }

                    @JavascriptInterface
                    fun playClicked() {
                        handler.post {
                            hideOfficialControlsAfterPlay()
                        }
                    }

                    @JavascriptInterface
                    fun mediaDetected(
                        url: String?,
                        contentType: String?,
                        pageUrl: String?,
                    ) {
                        reportMediaFromJavaScript(
                            url = url,
                            contentType = contentType,
                            pageUrl = pageUrl,
                        )
                    }

                    @JavascriptInterface
                    fun preferredSourceSelected(service: String?) {
                        handler.post {
                            preferredSourceSelectionDone.set(true)
                            currentFallbackService.set(
                                service?.takeIf { it.isNotBlank() } ?: "videasy",
                            )
                            mediaCaptureArmed.set(true)
                            Log.i(
                                TAG,
                                "Source AfterDark préférée sélectionnée: ${service.orEmpty()}",
                            )
                        }
                    }

                    @JavascriptInterface
                    fun playerNotFound(host: String?) {
                        handler.post {
                            advanceOfficialSourceAfterFailure(host)
                        }
                    }

                    @JavascriptInterface
                    fun sourceAdvanced(service: String?) {
                        handler.post {
                            sourceFailoverInProgress.set(false)
                            currentFallbackService.set(
                                service?.takeIf { it.isNotBlank() } ?: "AfterDark",
                            )
                            mediaCaptureArmed.set(true)
                            Log.i(
                                TAG,
                                "AfterDark a sélectionné la source suivante: ${service.orEmpty()}",
                            )
                        }
                    }

                    @JavascriptInterface
                    fun noNextSource() {
                        handler.post {
                            sourceFailoverInProgress.set(false)
                            Log.i(TAG, "Aucune source AfterDark suivante disponible")
                            finish(null)
                        }
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
                          const EXPECTED_AFTERDARK_HOST = '$verificationHostForJs';

                          const normalize = value =>
                            String(value || "")
                              .replace(/\s+/g, " ")
                              .trim()
                              .toLocaleLowerCase("en-US");

                          const currentHost = () => {
                            try {
                              return String(location.hostname || "").toLowerCase();
                            } catch (_) {
                              return "";
                            }
                          };

                          const reportMedia = (url, contentType = "") => {
                            try {
                              const value = String(url || "");
                              if (!/^https?:\/\//i.test(value)) return;

                              window.AfterDarkNative.mediaDetected(
                                value,
                                String(contentType || ""),
                                String(location.href || "")
                              );
                            } catch (_) {}
                          };

                          const looksLikeMediaUrl = url => {
                            const value = String(url || "")
                              .toLowerCase()
                              .split("?")[0]
                              .split("#")[0];

                            return value.endsWith(".m3u8") ||
                              value.endsWith(".mpd") ||
                              value.endsWith(".mp4") ||
                              value.endsWith(".mkv") ||
                              value.endsWith(".webm");
                          };

                          if (!window.__afterdarkMediaCaptureInstalled) {
                            window.__afterdarkMediaCaptureInstalled = true;

                            if (window.fetch) {
                              const originalFetch = window.fetch.bind(window);
                              window.fetch = async (...args) => {
                                const response = await originalFetch(...args);

                                try {
                                  const requestUrl =
                                    response.url ||
                                    (typeof args[0] === "string"
                                      ? args[0]
                                      : args[0] && args[0].url) ||
                                    "";

                                  const contentType =
                                    response.headers && response.headers.get
                                      ? response.headers.get("content-type") || ""
                                      : "";

                                  if (
                                    looksLikeMediaUrl(requestUrl) ||
                                    /mpegurl|dash\+xml|^video\/|octet-stream/i
                                      .test(contentType)
                                  ) {
                                    reportMedia(requestUrl, contentType);
                                  }
                                } catch (_) {}

                                return response;
                              };
                            }

                            if (window.XMLHttpRequest) {
                              const nativeOpen =
                                XMLHttpRequest.prototype.open;
                              const nativeSend =
                                XMLHttpRequest.prototype.send;

                              XMLHttpRequest.prototype.open =
                                function(method, url) {
                                  this.__afterdarkMediaUrl = String(url || "");
                                  return nativeOpen.apply(this, arguments);
                                };

                              XMLHttpRequest.prototype.send = function() {
                                this.addEventListener("readystatechange", () => {
                                  try {
                                    if (this.readyState < 2) return;

                                    const requestUrl =
                                      this.responseURL ||
                                      this.__afterdarkMediaUrl ||
                                      "";

                                    const contentType =
                                      this.getResponseHeader("content-type") || "";

                                    if (
                                      looksLikeMediaUrl(requestUrl) ||
                                      /mpegurl|dash\+xml|^video\/|octet-stream/i
                                        .test(contentType)
                                    ) {
                                      reportMedia(requestUrl, contentType);
                                    }
                                  } catch (_) {}
                                });

                                return nativeSend.apply(this, arguments);
                              };
                            }

                            const scanMedia = () => {
                              try {
                                document
                                  .querySelectorAll("video,audio,source")
                                  .forEach(media => {
                                    const candidate =
                                      media.currentSrc ||
                                      media.src ||
                                      media.getAttribute("src") ||
                                      "";

                                    if (looksLikeMediaUrl(candidate)) {
                                      reportMedia(candidate, "");
                                    }
                                  });

                                if (
                                  performance &&
                                  performance.getEntriesByType
                                ) {
                                  performance
                                    .getEntriesByType("resource")
                                    .forEach(entry => {
                                      if (looksLikeMediaUrl(entry.name)) {
                                        reportMedia(entry.name, "");
                                      }
                                    });
                                }
                              } catch (_) {}
                            };

                            const mediaObserver = new MutationObserver(scanMedia);

                            const startMediaCapture = () => {
                              scanMedia();
                              mediaObserver.observe(document.documentElement, {
                                childList: true,
                                subtree: true,
                                attributes: true,
                                attributeFilter: ["src"]
                              });
                            };

                            if (document.readyState === "loading") {
                              document.addEventListener(
                                "DOMContentLoaded",
                                startMediaCapture,
                                { once: true }
                              );
                            } else {
                              startMediaCapture();
                            }
                          }

                          // -------------------------------------------------
                          // Official player automation, installed in EVERY
                          // frame by DOCUMENT_START_SCRIPT.
                          // -------------------------------------------------
                          if (!window.__afterdarkPlayerFrameAutomation) {
                            window.__afterdarkPlayerFrameAutomation = true;

                            const clickedPlayButtons =
                              window.__afterdarkClickedPlayButtons || new WeakSet();
                            window.__afterdarkClickedPlayButtons =
                              clickedPlayButtons;

                            const findAndClickPlay = () => {
                              const buttons = Array.from(
                                document.querySelectorAll("button")
                              );

                              const play = buttons.find(button => {
                                try {
                                  return Boolean(
                                    button.querySelector(
                                      'svg path[d="M8 5v14l11-7z"]'
                                    )
                                  );
                                } catch (_) {
                                  return false;
                                }
                              });

                              if (!play || clickedPlayButtons.has(play)) {
                                return false;
                              }

                              clickedPlayButtons.add(play);
                              try {
                                play.click();

                                // The Play control may live inside a cross-origin
                                // iframe. Notify native code so it can hide the
                                // top-level AfterDark controls immediately after
                                // this successful click.
                                try {
                                  window.AfterDarkNative.playClicked();
                                } catch (_) {}

                                return true;
                              } catch (_) {
                                return false;
                              }
                            };

                            const detectProviderFailure = () => {
                              if (window.__afterdarkNotFoundReported) return true;

                              const host = currentHost();

                              // Videasy failure signal:
                              // ONLY the explicit "Go Back" control.
                              const goBack = Array.from(
                                document.querySelectorAll(
                                  'a,button,[role="button"]'
                                )
                              ).find(element => {
                                const label = normalize(element.textContent);
                                if (label !== "go back") return false;

                                // The supplied error UI uses
                                // <a href="/">Go Back</a>.
                                if (
                                  element.tagName &&
                                  element.tagName.toLowerCase() === "a"
                                ) {
                                  const href =
                                    element.getAttribute("href") || "";
                                  return href === "/";
                                }

                                return true;
                              });

                              if (goBack) {
                                window.__afterdarkNotFoundReported = true;
                                try {
                                  window.AfterDarkNative.playerNotFound(host);
                                } catch (_) {}
                                return true;
                              }

                              // Peachify failure signal:
                              // ONLY the exact Reload button supplied by the
                              // player:
                              //
                              // <button type="button"
                              //   class="glass-btn-accent">Reload</button>
                              //
                              // Never interpret Reload outside a Peachify frame.
                              const isPeachify =
                                host === "peachify.top" ||
                                host.endsWith(".peachify.top");

                              if (!isPeachify) return false;

                              const peachifyReload = Array.from(
                                document.querySelectorAll(
                                  'button[type="button"].glass-btn-accent'
                                )
                              ).find(button =>
                                normalize(button.textContent) === "reload"
                              );

                              if (!peachifyReload) return false;

                              window.__afterdarkNotFoundReported = true;
                              try {
                                window.AfterDarkNative.playerNotFound(host);
                              } catch (_) {}
                              return true;
                            };

                            const scanPlayerFrame = () => {
                              detectProviderFailure();
                              findAndClickPlay();
                            };

                            const startPlayerAutomation = () => {
                              scanPlayerFrame();

                              const observer = new MutationObserver(() => {
                                scanPlayerFrame();
                              });

                              observer.observe(document.documentElement, {
                                childList: true,
                                subtree: true,
                                characterData: true,
                                attributes: true,
                                attributeFilter: [
                                  "class",
                                  "style",
                                  "src",
                                  "aria-label"
                                ]
                              });

                              window.__afterdarkPlayerAutomationObserver = observer;
                            };

                            if (document.readyState === "loading") {
                              document.addEventListener(
                                "DOMContentLoaded",
                                startPlayerAutomation,
                                { once: true }
                              );
                            } else {
                              startPlayerAutomation();
                            }
                          }

                          // -------------------------------------------------
                          // Passive Cloudflare/Turnstile error watcher.
                          //
                          // This is the ONLY thing that can request a verification
                          // page reload. Merely seeing a checkbox never reloads.
                          // -------------------------------------------------
                          if (!window.__afterdarkCloudflareErrorWatcher) {
                            window.__afterdarkCloudflareErrorWatcher = true;

                            const extractCloudflareCode = value => {
                              if (value == null) return null;

                              let text;
                              if (value instanceof Error) {
                                text =
                                  String(value.name || "") +
                                  " " +
                                  String(value.message || "");
                              } else if (typeof value === "string") {
                                text = value;
                              } else {
                                try {
                                  text = JSON.stringify(value);
                                } catch (_) {
                                  text = String(value);
                                }
                              }

                              const match = text.match(/\b(\d{6})\b/);
                              return match ? match[1] : null;
                            };

                            const reportCloudflareError = (
                              code,
                              source,
                              details
                            ) => {
                              try {
                                let serialized = "";
                                if (typeof details === "string") {
                                  serialized = details;
                                } else {
                                  try {
                                    serialized = JSON.stringify(details);
                                  } catch (_) {
                                    serialized = String(details || "");
                                  }
                                }

                                window.AfterDarkNative.cloudflareError(
                                  String(code || ""),
                                  String(source || ""),
                                  serialized.slice(0, 2000)
                                );
                              } catch (_) {}
                            };

                            window.addEventListener("error", event => {
                              if (event instanceof ErrorEvent) {
                                const text = [
                                  event.message,
                                  event.error && event.error.message,
                                  event.filename
                                ].filter(Boolean).join(" ");

                                const looksLikeTurnstile =
                                  /cloudflare\s*turnstile/i.test(text) ||
                                  /challenges\.cloudflare\.com/i.test(text);

                                const code = extractCloudflareCode(text);

                                if (looksLikeTurnstile && code) {
                                  reportCloudflareError(
                                    code,
                                    "window.error",
                                    text
                                  );
                                }

                                return;
                              }

                              const target = event.target;
                              const src =
                                (target && (target.src || target.href)) || "";

                              if (
                                typeof src === "string" &&
                                src.includes("challenges.cloudflare.com")
                              ) {
                                reportCloudflareError(
                                  "RESOURCE_LOAD_ERROR",
                                  "resource-error",
                                  src
                                );
                              }
                            }, true);

                            window.addEventListener(
                              "unhandledrejection",
                              event => {
                                const reason = event.reason;
                                const text =
                                  reason instanceof Error
                                    ? String(reason.name || "Error") +
                                      ": " +
                                      String(reason.message || "")
                                    : String(reason);

                                const code = extractCloudflareCode(text);

                                if (
                                  code &&
                                  /cloudflare|turnstile/i.test(text)
                                ) {
                                  reportCloudflareError(
                                    code,
                                    "unhandledrejection",
                                    text
                                  );
                                }
                              }
                            );

                            window.addEventListener("message", event => {
                              if (
                                event.origin !==
                                "https://challenges.cloudflare.com"
                              ) {
                                return;
                              }

                              const code =
                                extractCloudflareCode(event.data);

                              if (code) {
                                reportCloudflareError(
                                  code,
                                  "cloudflare-postMessage",
                                  event.data
                                );
                              }
                            });
                          }

                          // -------------------------------------------------
                          // Cloudflare Turnstile verification-control detector.
                          // It reports the control so native Android input can
                          // tap it. It never requests a reload itself.
                          // -------------------------------------------------
                          if (window.__afterdarkCheckboxFrameDetector) return;
                          window.__afterdarkCheckboxFrameDetector = true;

                          const isCloudflareFrame = () => {
                            const host = currentHost();
                            return host === "challenges.cloudflare.com" ||
                              host.endsWith(".challenges.cloudflare.com");
                          };

                          if (!isCloudflareFrame()) return;

                          const roots = new Set([document]);

                          try {
                            const nativeAttachShadow = Element.prototype.attachShadow;
                            Element.prototype.attachShadow = function() {
                              const shadowRoot =
                                nativeAttachShadow.apply(this, arguments);
                              roots.add(shadowRoot);
                              return shadowRoot;
                            };
                          } catch (_) {}

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

                          const startCheckboxDetector = () => {
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
                              startCheckboxDetector,
                              { once: true }
                            );
                          } else {
                            startCheckboxDetector();
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
                Log.i(TAG, "Détecteur Turnstile installé dans toutes les frames")
            } else {
                Log.w(TAG, "Détecteur Turnstile multi-frame indisponible")
            }

            // Cloudflare Turnstile usually lives in a cross-origin iframe,
            // which page JavaScript cannot inspect. The rendered interactive
            // checkbox is still exposed through WebView's accessibility tree.
            checkboxWatcher = object : Runnable {
                override fun run() {
                    if (finished.get() || officialPlayerMode.get()) return

                    if (!cloudflareErrorReloadInProgress.get()) {
                        interactWithInteractiveCheckbox(browser)
                    }

                    if (!finished.get()) {
                        handler.postDelayed(this, CHECKBOX_POLL_INTERVAL_MS)
                    }
                }
            }
            handler.post(checkboxWatcher!!)

            fun officialBodyHasItems(body: String): Boolean =
                body.lineSequence().any { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty()) {
                        false
                    } else {
                        runCatching {
                            val root = JSONObject(line)
                            val items = root.optJSONArray("items")
                            items != null && items.length() > 0
                        }.getOrDefault(false)
                    }
                }

            fun enterOfficialPlayerMode() {
                if (!officialPlayerMode.compareAndSet(false, true)) return

                // The official /watch page now owns playback. Keep this exact
                // WebView/session alive instead of opening the source URL.
                handler.removeCallbacks(timeoutRunnable)
                checkboxWatcher?.let(handler::removeCallbacks)
                verificationButtonHasAppeared.set(true)
                cloudflareErrorReloadInProgress.set(false)
                verificationCheckboxPresent.set(false)

                handler.post {
                    if (finished.get()) return@post

                    info.visibility = android.view.View.GONE
                    controls.visibility = android.view.View.GONE

                    browser.settings.mediaPlaybackRequiresUserGesture = false
                    browser.requestFocus()
                    browser.requestFocusFromTouch()

                    Log.i(
                        TAG,
                        "API sources vide : conservation de la WebView officielle AfterDark",
                    )

                    selectPreferredOfficialSource()
                }
            }

            fun finishWithCapturedResponse(captured: CapturedSourceResponse) {
                val officialSourcesEmpty =
                    captured.statusCode in 200..299 &&
                        !officialBodyHasItems(captured.body)

                if (officialSourcesEmpty) {
                    emptyOfficialResponse.set(captured)

                    // Return the empty response to AfterDark, but do not close
                    // this WebView. Its own React page will render the fallback
                    // player inside the already-established /watch session.
                    enterOfficialPlayerMode()
                    return
                }

                // Normal non-empty response: hand it back to the provider.
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
                                interactWithVerificationButton = false,
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
                            val sourceResponse = interceptOfficialSources(webRequest)
                            if (sourceResponse != null) return sourceResponse

                            captureOfficialPlayerMedia(webRequest)?.let {
                                finishWithResolvedMedia(it)
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
                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?,
                ) {
                    if (!officialPlayerMode.get()) {
                        verificationCheckboxPresent.set(false)
                    }
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?,
                ) {
                    super.onPageFinished(view, url)
                    cloudflareErrorReloadInProgress.set(false)

                    if (!officialPlayerMode.get()) {
                        installAutoOpenAndPlay(
                            view,
                            interactWithVerificationButton = true,
                        )
                    } else if (!preferredSourceSelectionDone.get()) {
                        selectPreferredOfficialSource()
                    }
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
                    val sourceResponse = interceptOfficialSources(webRequest)
                    if (sourceResponse != null) return sourceResponse

                    captureOfficialPlayerMedia(webRequest)?.let {
                        finishWithResolvedMedia(it)
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
