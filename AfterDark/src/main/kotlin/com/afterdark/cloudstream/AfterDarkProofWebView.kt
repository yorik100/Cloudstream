package com.afterdark.cloudstream

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
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
        val mediaCaptureArmed = AtomicBoolean(false)
        val currentFallbackService = AtomicReference<String?>(null)
        val emptyOfficialResponse = AtomicReference<CapturedSourceResponse?>(null)
        val verificationButtonHasAppeared = AtomicBoolean(false)
        val cloudflareErrorReloadInProgress = AtomicBoolean(false)
        val turnstileNativeTapInProgress = AtomicBoolean(false)
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
            ) {
                if (target == null || finished.get()) return

                target.evaluateJavascript(
                    """
                    (() => {
                      const OPEN_LINK_TEXT = "ouvrir le lien";
                      const EXPECTED_HOST = '$verificationHostForJs';
                      const SEEN_KEY = "__afterdark_verification_button_seen";

                      if (
                        !EXPECTED_HOST ||
                        String(location.hostname || "").toLowerCase() !==
                          EXPECTED_HOST.toLowerCase()
                      ) {
                        return;
                      }

                      const normalize = value =>
                        String(value || "").replace(/\s+/g, " ").trim();

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

                        markSeen();

                        const state = buttonState(button);
                        const openedStates =
                          window.__afterdarkAutoOpenedStates || new Set();
                        window.__afterdarkAutoOpenedStates = openedStates;

                        if (!state || openedStates.has(state)) return true;

                        openedStates.add(state);
                        button.dataset.afterdarkAutoOpenedState = state;

                        try {
                          button.click();
                          return true;
                        } catch (_) {
                          return false;
                        }
                      };

                      findAndClick();

                      if (window.__afterdarkAutoOpenObserver) {
                        try {
                          window.__afterdarkAutoOpenObserver.disconnect();
                        } catch (_) {}
                      }

                      const observer = new MutationObserver(findAndClick);

                      observer.observe(document.documentElement, {
                        childList: true,
                        subtree: true,
                        characterData: true
                      });

                      window.__afterdarkAutoOpenObserver = observer;
                    })();
                    """.trimIndent(),
                    null,
                )
            }

            fun resolveTurnstileCandidateFromFrame(
                childX: Double,
                childY: Double,
                childViewportWidth: Double,
                childViewportHeight: Double,
                sourceHref: String?,
            ) {
                if (
                    finished.get() ||
                    officialPlayerMode.get() ||
                    cloudflareErrorReloadInProgress.get()
                ) return

                val href = sourceHref.orEmpty()
                if (
                    !href.startsWith(
                        "https://challenges.cloudflare.com/",
                        ignoreCase = true,
                    )
                ) {
                    Log.w(
                        TAG,
                        "Candidat Turnstile ignoré: frame non Cloudflare: $href",
                    )
                    return
                }

                if (
                    !childX.isFinite() ||
                    !childY.isFinite() ||
                    !childViewportWidth.isFinite() ||
                    !childViewportHeight.isFinite() ||
                    childViewportWidth <= 0.0 ||
                    childViewportHeight <= 0.0
                ) {
                    Log.w(
                        TAG,
                        "Candidat Turnstile invalide: " +
                            "point=($childX,$childY) " +
                            "viewport=($childViewportWidth,$childViewportHeight)",
                    )
                    return
                }

                browser.post {
                    if (finished.get() || officialPlayerMode.get()) {
                        return@post
                    }

                    val hrefJson = JSONObject.quote(href)

                    browser.evaluateJavascript(
                        """
                        (() => {
                          const resolver =
                            window.__afterdarkResolveTurnstileCandidate;

                          if (typeof resolver !== "function") {
                            return "NO_TOP_RESOLVER";
                          }

                          try {
                            return String(
                              resolver({
                                x: $childX,
                                y: $childY,
                                viewportWidth: $childViewportWidth,
                                viewportHeight: $childViewportHeight,
                                sourceHref: $hrefJson
                              })
                            );
                          } catch (error) {
                            return "TOP_RESOLVER_ERROR:" +
                              String(error || "");
                          }
                        })();
                        """.trimIndent(),
                    ) { result ->
                        Log.i(
                            TAG,
                            "Résolution Turnstile top-frame: " +
                                result.orEmpty().take(1000),
                        )
                    }
                }
            }

            fun dispatchTurnstileNativeTap(
                cssX: Double,
                cssY: Double,
                viewportWidth: Double,
                viewportHeight: Double,
                sourceHref: String?,
            ) {
                if (
                    finished.get() ||
                    officialPlayerMode.get() ||
                    cloudflareErrorReloadInProgress.get() ||
                    !turnstileNativeTapInProgress.compareAndSet(false, true)
                ) return

                val href = sourceHref.orEmpty()
                if (
                    !href.startsWith(
                        "https://challenges.cloudflare.com/",
                        ignoreCase = true,
                    )
                ) {
                    turnstileNativeTapInProgress.set(false)
                    Log.w(
                        TAG,
                        "Tap Turnstile ignoré: frame non Cloudflare: $href",
                    )
                    return
                }

                browser.post {
                    if (
                        finished.get() ||
                        officialPlayerMode.get() ||
                        browser.width <= 0 ||
                        browser.height <= 0
                    ) {
                        turnstileNativeTapInProgress.set(false)
                        return@post
                    }

                    val viewportW = viewportWidth
                        .takeIf { it.isFinite() && it > 0.0 }
                        ?: browser.width.toDouble()

                    val viewportH = viewportHeight
                        .takeIf { it.isFinite() && it > 0.0 }
                        ?: browser.height.toDouble()

                    val localX = (
                        cssX * browser.width.toDouble() / viewportW
                    ).toFloat()

                    val localY = (
                        cssY * browser.height.toDouble() / viewportH
                    ).toFloat()

                    if (
                        !localX.isFinite() ||
                        !localY.isFinite() ||
                        localX < 0f ||
                        localY < 0f ||
                        localX > browser.width.toFloat() ||
                        localY > browser.height.toFloat()
                    ) {
                        turnstileNativeTapInProgress.set(false)
                        Log.w(
                            TAG,
                            "Coordonnées Turnstile hors WebView: " +
                                "css=($cssX,$cssY) " +
                                "viewport=($viewportW,$viewportH) " +
                                "view=${browser.width}x${browser.height} " +
                                "local=($localX,$localY)",
                        )
                        return@post
                    }

                    browser.requestFocus()

                    val downTime = SystemClock.uptimeMillis()

                    val down = MotionEvent.obtain(
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
                        browser.dispatchTouchEvent(down)
                    }.getOrDefault(false)

                    down.recycle()

                    // Keep the native press duration inside the
                    // 45-60 ms compatibility window used by different
                    // Android WebView/browser input stacks.
                    val pressDurationMs =
                        kotlin.random.Random.nextLong(45L, 61L)
                    browser.postDelayed(
                        {
                            val upTime = SystemClock.uptimeMillis()

                            val up = MotionEvent.obtain(
                                downTime,
                                upTime,
                                MotionEvent.ACTION_UP,
                                localX,
                                localY,
                                0,
                            ).apply {
                                source = InputDevice.SOURCE_TOUCHSCREEN
                            }

                            val upHandled = runCatching {
                                browser.dispatchTouchEvent(up)
                            }.getOrDefault(false)

                            up.recycle()

                            Log.i(
                                TAG,
                                "Tap Turnstile natif envoyé: " +
                                    "css=($cssX,$cssY) " +
                                    "viewport=($viewportW,$viewportH) " +
                                    "local=($localX,$localY) " +
                                    "DOWN=$downHandled UP=$upHandled " +
                                    "press=${pressDurationMs}ms " +
                                    "frame=$href",
                            )

                            browser.postDelayed(
                                {
                                    turnstileNativeTapInProgress.set(false)
                                },
                                650L,
                            )
                        },
                        pressDurationMs,
                    )
                }
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

            fun hideOfficialPeachifyControls() {
                if (finished.get()) return

                browser.post {
                    if (finished.get()) return@post

                    browser.evaluateJavascript(
                        """
                        (() => {
                          const normalize = value =>
                            String(value || "")
                              .replace(/\s+/g, " ")
                              .trim()
                              .toLocaleLowerCase("fr-FR");

                          const hide = element => {
                            if (!element) return false;

                            element.style.setProperty(
                              "display",
                              "none",
                              "important"
                            );
                            element.setAttribute(
                              "data-afterdark-hidden-peachify",
                              "true"
                            );
                            return true;
                          };

                          const hideControls = () => {
                            let changed = false;

                            const interactive = Array.from(
                              document.querySelectorAll(
                                'button,a,[role="button"]'
                              )
                            );

                            for (const element of interactive) {
                              const text = normalize(
                                element.textContent
                              );
                              const aria = normalize(
                                element.getAttribute("aria-label")
                              );

                              const isSources =
                                text === "sources" ||
                                aria === "sources";

                              const isBack =
                                text === "back" ||
                                text === "retour" ||
                                aria === "back" ||
                                aria === "retour";

                              if (isSources || isBack) {
                                changed =
                                  hide(element) ||
                                  changed;
                              }
                            }

                            return changed;
                          };

                          // Peachify cleanup is unconditional:
                          // hide now, then keep the controls hidden if React
                          // recreates them.
                          hideControls();

                          if (
                            !window.__afterdarkPeachifyControlsObserver
                          ) {
                            const observer =
                              new MutationObserver(() => {
                                hideControls();
                              });

                            observer.observe(
                              document.documentElement,
                              {
                                childList: true,
                                subtree: true,
                                characterData: true,
                                attributes: true,
                                attributeFilter: [
                                  "class",
                                  "style",
                                  "aria-label"
                                ]
                              }
                            );

                            window.__afterdarkPeachifyControlsObserver =
                              observer;
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

                          const findPeachifyButton = () =>
                            interactive().find(element => {
                              const text = normalize(element.textContent);
                              return text === "peachify" ||
                                     text.includes("peachify");
                            });

                          let sourceMenuOpened = false;

                          const trySelectPeachify = () => {
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

                            const peachify = findPeachifyButton();
                            if (!peachify) return false;

                            window.__afterdarkPreferredSourceSelected = true;
                            window.__afterdarkPreferredSourceLocked = true;

                            try {
                              peachify.click();
                            } catch (_) {
                              window.__afterdarkPreferredSourceSelected = false;
                              window.__afterdarkPreferredSourceLocked = false;
                              return false;
                            }

                            try {
                              window.AfterDarkNative.preferredSourceSelected(
                                "peachify"
                              );
                            } catch (_) {}

                            return true;
                          };

                          if (trySelectPeachify()) {
                            window.__afterdarkPreferredSourceObserverInstalled = false;
                            return;
                          }

                          const observer = new MutationObserver(() => {
                            if (trySelectPeachify()) {
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
                    fun turnstileDebug(message: String?) {
                        Log.i(
                            TAG,
                            "Turnstile JS: ${message.orEmpty().take(1000)}",
                        )
                    }

                    @JavascriptInterface
                    fun turnstileFrameCandidate(
                        childX: Double,
                        childY: Double,
                        childViewportWidth: Double,
                        childViewportHeight: Double,
                        sourceHref: String?,
                    ) {
                        handler.post {
                            resolveTurnstileCandidateFromFrame(
                                childX = childX,
                                childY = childY,
                                childViewportWidth = childViewportWidth,
                                childViewportHeight = childViewportHeight,
                                sourceHref = sourceHref,
                            )
                        }
                    }

                    @JavascriptInterface
                    fun turnstileNativeTap(
                        cssX: Double,
                        cssY: Double,
                        viewportWidth: Double,
                        viewportHeight: Double,
                        sourceHref: String?,
                    ) {
                        handler.post {
                            dispatchTurnstileNativeTap(
                                cssX = cssX,
                                cssY = cssY,
                                viewportWidth = viewportWidth,
                                viewportHeight = viewportHeight,
                                sourceHref = sourceHref,
                            )
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
                            hideOfficialPeachifyControls()
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
                                service?.takeIf { it.isNotBlank() } ?: "peachify",
                            )
                            mediaCaptureArmed.set(true)
                            Log.i(
                                TAG,
                                "Source AfterDark préférée sélectionnée: ${service.orEmpty()}",
                            )

                            if (
                                service.isNullOrBlank() ||
                                service.equals("peachify", ignoreCase = true)
                            ) {
                                hideOfficialPeachifyControls()
                            }
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

                            if (service.equals("peachify", ignoreCase = true)) {
                                hideOfficialPeachifyControls()
                            }
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

                              try {
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
                          // Deep Turnstile locator + Android bridge relay.
                          //
                          // No cross-frame postMessage is used here.
                          //
                          // Flow:
                          // Cloudflare frame finds control -> Android bridge ->
                          // main frame resolves the Cloudflare iframe rectangle ->
                          // Android dispatches the real WebView touch.
                          // -------------------------------------------------
                          if (!window.__afterdarkTrustedTurnstileLocator) {
                            window.__afterdarkTrustedTurnstileLocator = true;

                            const turnstileRoots = new Set();
                            const turnstileLastRequest =
                              new WeakMap();

                            turnstileRoots.add(document);

                            const turnstileDebug = message => {
                              try {
                                window.AfterDarkNative.turnstileDebug(
                                  String(message || "")
                                );
                              } catch (_) {}
                            };

                            const currentTurnstileHost = () => {
                              try {
                                return String(
                                  location.hostname || ""
                                ).toLowerCase();
                              } catch (_) {
                                return "";
                              }
                            };

                            const isCloudflareTurnstileFrame = () =>
                              currentTurnstileHost() ===
                                "challenges.cloudflare.com";

                            turnstileDebug(
                              "LOCATOR CHARGE frame=" +
                              String(location.href || "")
                            );

                            /*
                             * Log what Chromium actually generates from the
                             * native WebView touch.
                             */
                            if (isCloudflareTurnstileFrame()) {
                              for (
                                const eventName of [
                                  "pointerdown",
                                  "pointerup",
                                  "mousedown",
                                  "mouseup",
                                  "click"
                                ]
                              ) {
                                window.addEventListener(
                                  eventName,
                                  event => {
                                    if (!event) return;

                                    const target =
                                      event.target || null;

                                    turnstileDebug(
                                      "EVENT " +
                                      eventName +
                                      " isTrusted=" +
                                      String(
                                        event.isTrusted === true
                                      ) +
                                      " target=" +
                                      String(
                                        target &&
                                        target.tagName || ""
                                      ) +
                                      " x=" +
                                      String(
                                        Math.round(
                                          Number(
                                            event.clientX || 0
                                          ) * 100
                                        ) / 100
                                      ) +
                                      " y=" +
                                      String(
                                        Math.round(
                                          Number(
                                            event.clientY || 0
                                          ) * 100
                                        ) / 100
                                      )
                                    );
                                  },
                                  true
                                );
                              }
                            }

                            /*
                             * Capture CLOSED Shadow DOM roots created after
                             * document-start.
                             */
                            try {
                              const originalAttachShadow =
                                Element.prototype.attachShadow;

                              Element.prototype.attachShadow =
                                function(init) {
                                  const shadow =
                                    originalAttachShadow.call(
                                      this,
                                      init
                                    );

                                  turnstileRoots.add(shadow);

                                  turnstileDebug(
                                    "SHADOW CAPTURE mode=" +
                                    String(
                                      init && init.mode
                                        ? init.mode
                                        : ""
                                    ) +
                                    " host=" +
                                    String(this.tagName || "")
                                  );

                                  return shadow;
                                };

                              turnstileDebug(
                                "Hook attachShadow installe"
                              );
                            } catch (error) {
                              turnstileDebug(
                                "attachShadow erreur " +
                                String(error || "")
                              );
                            }

                            const visibleTurnstileElement =
                              element => {
                                if (!element) return false;

                                try {
                                  const rect =
                                    element.getBoundingClientRect();
                                  const style =
                                    getComputedStyle(element);

                                  return (
                                    rect.width > 0 &&
                                    rect.height > 0 &&
                                    style.display !== "none" &&
                                    style.visibility !==
                                      "hidden" &&
                                    style.opacity !== "0"
                                  );
                                } catch (_) {
                                  return false;
                                }
                              };

                            const collectTurnstileRoots = () => {
                              const queue =
                                Array.from(turnstileRoots);
                              const seen = new Set();

                              while (queue.length) {
                                const root = queue.shift();

                                if (
                                  !root ||
                                  seen.has(root)
                                ) {
                                  continue;
                                }

                                seen.add(root);
                                turnstileRoots.add(root);

                                let elements = [];

                                try {
                                  elements =
                                    root.querySelectorAll("*");
                                } catch (_) {}

                                for (const element of elements) {
                                  try {
                                    if (
                                      element.shadowRoot &&
                                      !seen.has(
                                        element.shadowRoot
                                      )
                                    ) {
                                      turnstileRoots.add(
                                        element.shadowRoot
                                      );
                                      queue.push(
                                        element.shadowRoot
                                      );
                                    }
                                  } catch (_) {}
                                }
                              }

                              return Array.from(
                                turnstileRoots
                              );
                            };

                            const turnstilePriority = element => {
                              try {
                                if (
                                  element.matches(
                                    'input[type="checkbox"]'
                                  )
                                ) return 0;

                                if (
                                  element.getAttribute(
                                    "role"
                                  ) === "checkbox"
                                ) return 1;

                                if (
                                  String(
                                    element.tagName || ""
                                  ).toLowerCase() === "label"
                                ) return 2;

                                if (
                                  String(
                                    element.tagName || ""
                                  ).toLowerCase() === "button"
                                ) return 3;

                                if (
                                  element.getAttribute(
                                    "role"
                                  ) === "button"
                                ) return 4;
                              } catch (_) {}

                              return 10;
                            };

                            const findTurnstileCandidates = () => {
                              const result = [];

                              for (
                                const root of
                                  collectTurnstileRoots()
                              ) {
                                let elements = [];

                                try {
                                  elements =
                                    root.querySelectorAll(
                                      [
                                        'input[type="checkbox"]',
                                        '[role="checkbox"]',
                                        "label",
                                        "button",
                                        '[role="button"]'
                                      ].join(",")
                                    );
                                } catch (_) {}

                                for (const element of elements) {
                                  if (
                                    !visibleTurnstileElement(
                                      element
                                    )
                                  ) {
                                    continue;
                                  }

                                  const text = [
                                    element.textContent,
                                    element.getAttribute &&
                                      element.getAttribute(
                                        "aria-label"
                                      ),
                                    element.getAttribute &&
                                      element.getAttribute(
                                        "title"
                                      )
                                  ]
                                    .filter(Boolean)
                                    .join(" ")
                                    .toLowerCase();

                                  const checkbox =
                                    (
                                      element.matches &&
                                      element.matches(
                                        'input[type="checkbox"]'
                                      )
                                    ) ||
                                    (
                                      element.getAttribute &&
                                      element.getAttribute(
                                        "role"
                                      ) === "checkbox"
                                    );

                                  const interesting =
                                    checkbox ||
                                    text.includes("verify") ||
                                    text.includes(
                                      "verification"
                                    ) ||
                                    text.includes(
                                      "vérification"
                                    ) ||
                                    text.includes("human") ||
                                    text.includes("humain") ||
                                    text.includes(
                                      "turnstile"
                                    );

                                  if (interesting) {
                                    result.push(element);
                                  }
                                }
                              }

                              return Array.from(new Set(result))
                                .sort((a, b) => {
                                  const priority =
                                    turnstilePriority(a) -
                                    turnstilePriority(b);

                                  if (priority !== 0) {
                                    return priority;
                                  }

                                  try {
                                    const aRect =
                                      a.getBoundingClientRect();
                                    const bRect =
                                      b.getBoundingClientRect();

                                    return (
                                      aRect.width *
                                        aRect.height -
                                      bRect.width *
                                        bRect.height
                                    );
                                  } catch (_) {
                                    return 0;
                                  }
                                });
                            };

                            const describeTurnstile =
                              element => {
                                try {
                                  const rect =
                                    element.getBoundingClientRect();

                                  return (
                                    "tag=" +
                                    String(
                                      element.tagName || ""
                                    ) +
                                    " role=" +
                                    String(
                                      element.getAttribute &&
                                      element.getAttribute(
                                        "role"
                                      ) || ""
                                    ) +
                                    " type=" +
                                    String(
                                      element.getAttribute &&
                                      element.getAttribute(
                                        "type"
                                      ) || ""
                                    ) +
                                    " rect=" +
                                    [
                                      Math.round(
                                        rect.x * 100
                                      ) / 100,
                                      Math.round(
                                        rect.y * 100
                                      ) / 100,
                                      Math.round(
                                        rect.width * 100
                                      ) / 100,
                                      Math.round(
                                        rect.height * 100
                                      ) / 100
                                    ].join(",")
                                  );
                                } catch (_) {
                                  return "description-error";
                                }
                              };

                            /*
                             * Runs only in the TOP frame.
                             *
                             * The top frame already captured its closed shadow
                             * roots at document-start, so it can locate a
                             * Cloudflare iframe even when that iframe is inside
                             * a closed component shadow tree.
                             */
                            if (window === window.top) {
                              window.__afterdarkResolveTurnstileCandidate =
                                data => {
                                  if (!data) {
                                    return "NO_DATA";
                                  }

                                  const childX =
                                    Number(data.x);
                                  const childY =
                                    Number(data.y);
                                  const childW =
                                    Number(
                                      data.viewportWidth
                                    );
                                  const childH =
                                    Number(
                                      data.viewportHeight
                                    );

                                  if (
                                    !Number.isFinite(childX) ||
                                    !Number.isFinite(childY) ||
                                    !Number.isFinite(childW) ||
                                    !Number.isFinite(childH) ||
                                    childW <= 0 ||
                                    childH <= 0
                                  ) {
                                    return "BAD_CHILD_COORDS";
                                  }

                                  const frames = [];

                                  for (
                                    const root of
                                      collectTurnstileRoots()
                                  ) {
                                    let values = [];

                                    try {
                                      values =
                                        root.querySelectorAll(
                                          "iframe,frame"
                                        );
                                    } catch (_) {}

                                    for (const frame of values) {
                                      if (
                                        !frames.includes(frame)
                                      ) {
                                        frames.push(frame);
                                      }
                                    }
                                  }

                                  const cloudflareFrames =
                                    frames
                                      .map(frame => {
                                        let src = "";

                                        try {
                                          src =
                                            String(
                                              frame.src ||
                                              frame.getAttribute(
                                                "src"
                                              ) ||
                                              ""
                                            );
                                        } catch (_) {}

                                        let host = "";

                                        try {
                                          host =
                                            new URL(
                                              src,
                                              location.href
                                            ).hostname
                                              .toLowerCase();
                                        } catch (_) {}

                                        let rect = null;

                                        try {
                                          rect =
                                            frame
                                              .getBoundingClientRect();
                                        } catch (_) {}

                                        if (
                                          host !==
                                            "challenges.cloudflare.com" ||
                                          !rect ||
                                          rect.width <= 0 ||
                                          rect.height <= 0
                                        ) {
                                          return null;
                                        }

                                        // Prefer a frame whose rendered aspect
                                        // and dimensions resemble the child
                                        // viewport that reported the checkbox.
                                        const widthRatio =
                                          rect.width / childW;
                                        const heightRatio =
                                          rect.height / childH;

                                        const score =
                                          Math.abs(
                                            Math.log(
                                              Math.max(
                                                widthRatio,
                                                0.0001
                                              )
                                            )
                                          ) +
                                          Math.abs(
                                            Math.log(
                                              Math.max(
                                                heightRatio,
                                                0.0001
                                              )
                                            )
                                          );

                                        return {
                                          frame,
                                          src,
                                          rect,
                                          score
                                        };
                                      })
                                      .filter(Boolean)
                                      .sort(
                                        (a, b) =>
                                          a.score - b.score
                                      );

                                  const match =
                                    cloudflareFrames[0] ||
                                    null;

                                  if (!match) {
                                    turnstileDebug(
                                      "TOP iframe Cloudflare introuvable " +
                                      "frames=" +
                                      String(frames.length)
                                    );
                                    return "NO_CF_IFRAME";
                                  }

                                  const rect = match.rect;

                                  const mappedX =
                                    rect.left +
                                    childX *
                                      rect.width /
                                      childW;

                                  const mappedY =
                                    rect.top +
                                    childY *
                                      rect.height /
                                      childH;

                                  turnstileDebug(
                                    "TOP RESOLVE child=" +
                                    childX.toFixed(2) +
                                    "," +
                                    childY.toFixed(2) +
                                    " childViewport=" +
                                    childW.toFixed(2) +
                                    "x" +
                                    childH.toFixed(2) +
                                    " iframe=" +
                                    [
                                      rect.left,
                                      rect.top,
                                      rect.width,
                                      rect.height
                                    ]
                                      .map(value =>
                                        Number(value)
                                          .toFixed(2)
                                      )
                                      .join(",") +
                                    " mapped=" +
                                    mappedX.toFixed(2) +
                                    "," +
                                    mappedY.toFixed(2)
                                  );

                                  try {
                                    window.AfterDarkNative
                                      .turnstileNativeTap(
                                        mappedX,
                                        mappedY,
                                        Number(
                                          window.innerWidth
                                        ),
                                        Number(
                                          window.innerHeight
                                        ),
                                        String(
                                          data.sourceHref ||
                                          ""
                                        )
                                      );
                                  } catch (error) {
                                    turnstileDebug(
                                      "TOP native tap erreur " +
                                      String(error || "")
                                    );
                                    return "NATIVE_TAP_ERROR";
                                  }

                                  return (
                                    "OK:" +
                                    mappedX.toFixed(2) +
                                    "," +
                                    mappedY.toFixed(2)
                                  );
                                };
                            }

                            const requestTurnstileNativeTap =
                              element => {
                                if (
                                  !element ||
                                  !isCloudflareTurnstileFrame()
                                ) {
                                  return false;
                                }

                                const now = Date.now();
                                const lastRequest =
                                  turnstileLastRequest.get(
                                    element
                                  ) || 0;

                                if (
                                  now - lastRequest < 1800
                                ) {
                                  return false;
                                }

                                turnstileLastRequest.set(
                                  element,
                                  now
                                );

                                let rect;

                                try {
                                  rect =
                                    element.getBoundingClientRect();
                                } catch (_) {
                                  return false;
                                }

                                let x =
                                  rect.left +
                                  rect.width / 2;
                                let y =
                                  rect.top +
                                  rect.height / 2;
                                let tapStrategy =
                                  "element-center";
                                let tapOffsetX = 0.0;
                                let tapOffsetY = 0.0;

                                /*
                                 * Pick a floating-point point inside the central
                                 * half of a measured target rectangle.
                                 *
                                 * offsetX ∈ [-width/4, +width/4]
                                 * offsetY ∈ [-height/4, +height/4]
                                 *
                                 * There is no fixed pixel coordinate here.
                                 */
                                const offsetPointInCentralHalf =
                                  targetRect => {
                                    const centerX =
                                      targetRect.left +
                                      targetRect.width / 2;
                                    const centerY =
                                      targetRect.top +
                                      targetRect.height / 2;

                                    const offsetX =
                                      (
                                        Math.random() * 2.0 -
                                        1.0
                                      ) *
                                      (
                                        targetRect.width /
                                        4.0
                                      );

                                    const offsetY =
                                      (
                                        Math.random() * 2.0 -
                                        1.0
                                      ) *
                                      (
                                        targetRect.height /
                                        4.0
                                      );

                                    return {
                                      x: centerX + offsetX,
                                      y: centerY + offsetY,
                                      offsetX,
                                      offsetY
                                    };
                                  };

                                const elementTag =
                                  String(
                                    element.tagName || ""
                                  ).toLowerCase();

                                /*
                                 * Fully geometry-driven checkbox targeting.
                                 *
                                 * No fixed X/Y coordinate and no absolute
                                 * checkbox size threshold is used.
                                 *
                                 * We first resolve the label rectangle, then
                                 * search its descendants for the visual box
                                 * whose geometry best matches a checkbox:
                                 * - square-ish
                                 * - close to the label height
                                 * - vertically centered
                                 * - towards the left edge
                                 *
                                 * All scores are dimensionless ratios, so the
                                 * result scales with CSS zoom, screen density,
                                 * emulator resolution and iframe resizing.
                                 */
                                let labelElement = null;

                                try {
                                  if (elementTag === "label") {
                                    labelElement = element;
                                  } else if (element.closest) {
                                    labelElement =
                                      element.closest("label");
                                  }
                                } catch (_) {}

                                const labelRect = (() => {
                                  try {
                                    if (labelElement) {
                                      const value =
                                        labelElement
                                          .getBoundingClientRect();

                                      if (
                                        value.width > 0 &&
                                        value.height > 0
                                      ) {
                                        return value;
                                      }
                                    }
                                  } catch (_) {}

                                  return rect;
                                })();

                                const labelWidth =
                                  Math.max(
                                    Number.EPSILON,
                                    labelRect.width
                                  );
                                const labelHeight =
                                  Math.max(
                                    Number.EPSILON,
                                    labelRect.height
                                  );
                                const labelCenterY =
                                  labelRect.top +
                                  labelRect.height / 2;

                                let bestVisualBox = null;
                                let bestVisualScore =
                                  Number.POSITIVE_INFINITY;

                                try {
                                  const geometryCandidates = [];

                                  if (labelElement) {
                                    geometryCandidates.push(
                                      labelElement
                                    );

                                    for (
                                      const child of
                                        labelElement
                                          .querySelectorAll("*")
                                    ) {
                                      geometryCandidates.push(
                                        child
                                      );
                                    }
                                  } else {
                                    geometryCandidates.push(
                                      element
                                    );

                                    if (element.querySelectorAll) {
                                      for (
                                        const child of
                                          element
                                            .querySelectorAll("*")
                                      ) {
                                        geometryCandidates.push(
                                          child
                                        );
                                      }
                                    }
                                  }

                                  for (
                                    const candidateBox of
                                      geometryCandidates
                                  ) {
                                    let candidateRect;

                                    try {
                                      candidateRect =
                                        candidateBox
                                          .getBoundingClientRect();
                                    } catch (_) {
                                      continue;
                                    }

                                    if (
                                      candidateRect.width <= 0 ||
                                      candidateRect.height <= 0
                                    ) {
                                      continue;
                                    }

                                    /*
                                     * Ignore anything whose box is effectively
                                     * the whole label. Comparison is relative,
                                     * not pixel-based.
                                     */
                                    const widthFraction =
                                      candidateRect.width /
                                      labelWidth;
                                    const heightFraction =
                                      candidateRect.height /
                                      labelHeight;

                                    if (
                                      widthFraction > 0.8 &&
                                      heightFraction > 0.8
                                    ) {
                                      continue;
                                    }

                                    const centerX =
                                      candidateRect.left +
                                      candidateRect.width / 2;
                                    const centerY =
                                      candidateRect.top +
                                      candidateRect.height / 2;

                                    /*
                                     * Dimensionless score:
                                     *
                                     * squareError:
                                     *   0 when width == height
                                     *
                                     * sizeError:
                                     *   0 when box height == label height
                                     *
                                     * verticalError:
                                     *   0 when vertically centered
                                     *
                                     * leftError:
                                     *   0 at label's left edge
                                     */
                                    const squareError =
                                      Math.abs(
                                        Math.log(
                                          candidateRect.width /
                                          candidateRect.height
                                        )
                                      );

                                    const sizeError =
                                      Math.abs(
                                        Math.log(
                                          candidateRect.height /
                                          labelHeight
                                        )
                                      );

                                    const verticalError =
                                      Math.abs(
                                        centerY -
                                        labelCenterY
                                      ) /
                                      labelHeight;

                                    const leftError =
                                      Math.max(
                                        0,
                                        centerX -
                                        labelRect.left
                                      ) /
                                      labelWidth;

                                    const score =
                                      squareError * 2 +
                                      sizeError +
                                      verticalError +
                                      leftError;

                                    if (
                                      Number.isFinite(score) &&
                                      score <
                                        bestVisualScore
                                    ) {
                                      bestVisualScore = score;
                                      bestVisualBox =
                                        candidateRect;
                                    }
                                  }
                                } catch (error) {
                                  turnstileDebug(
                                    "Recherche geometry dynamique erreur: " +
                                    String(error || "")
                                  );
                                }

                                if (bestVisualBox) {
                                  const point =
                                    offsetPointInCentralHalf(
                                      bestVisualBox
                                    );

                                  x = point.x;
                                  y = point.y;
                                  tapOffsetX =
                                    point.offsetX;
                                  tapOffsetY =
                                    point.offsetY;

                                  tapStrategy =
                                    "dynamic-visual-box-offset";

                                  turnstileDebug(
                                    "Case visuelle dynamique: " +
                                    [
                                      bestVisualBox.x,
                                      bestVisualBox.y,
                                      bestVisualBox.width,
                                      bestVisualBox.height
                                    ]
                                      .map(value =>
                                        Number(value)
                                          .toFixed(2)
                                      )
                                      .join(",") +
                                    " score=" +
                                    bestVisualScore.toFixed(4) +
                                    " offset=" +
                                    tapOffsetX.toFixed(3) +
                                    "," +
                                    tapOffsetY.toFixed(3)
                                  );
                                } else if (
                                  labelRect.width > 0 &&
                                  labelRect.height > 0
                                ) {
                                  /*
                                   * Pure layout fallback.
                                   *
                                   * The checkbox region is inferred as a square
                                   * whose side is the current label height.
                                   * Its center therefore depends only on the
                                   * measured layout:
                                   *
                                   * x = label.left + label.height / 2
                                   * y = label.top  + label.height / 2
                                   *
                                   * No pixel coordinate is hardcoded.
                                   */
                                  const inferredSide =
                                    Math.min(
                                      labelRect.height,
                                      labelRect.width
                                    );

                                  const inferredRect = {
                                    left:
                                      labelRect.left,
                                    top:
                                      labelRect.top +
                                      (
                                        labelRect.height -
                                        inferredSide
                                      ) / 2,
                                    width:
                                      inferredSide,
                                    height:
                                      inferredSide
                                  };

                                  const point =
                                    offsetPointInCentralHalf(
                                      inferredRect
                                    );

                                  x = point.x;
                                  y = point.y;
                                  tapOffsetX =
                                    point.offsetX;
                                  tapOffsetY =
                                    point.offsetY;

                                  tapStrategy =
                                    "dynamic-label-square-offset";

                                  turnstileDebug(
                                    "Case déduite dynamiquement du label: " +
                                    "label=" +
                                    [
                                      labelRect.x,
                                      labelRect.y,
                                      labelRect.width,
                                      labelRect.height
                                    ]
                                      .map(value =>
                                        Number(value)
                                          .toFixed(2)
                                      )
                                      .join(",") +
                                    " inferredSide=" +
                                    inferredSide.toFixed(2) +
                                    " offset=" +
                                    tapOffsetX.toFixed(3) +
                                    "," +
                                    tapOffsetY.toFixed(3)
                                  );
                                }

                                turnstileDebug(
                                  "CANDIDAT " +
                                  describeTurnstile(
                                    element
                                  ) +
                                  " strategy=" +
                                  tapStrategy +
                                  " tap=" +
                                  x.toFixed(3) +
                                  "," +
                                  y.toFixed(3) +
                                  " offset=" +
                                  tapOffsetX.toFixed(3) +
                                  "," +
                                  tapOffsetY.toFixed(3)
                                );

                                try {
                                  window.AfterDarkNative
                                    .turnstileFrameCandidate(
                                      x,
                                      y,
                                      Number(
                                        window.innerWidth
                                      ),
                                      Number(
                                        window.innerHeight
                                      ),
                                      String(
                                        location.href || ""
                                      )
                                    );

                                  turnstileDebug(
                                    "CANDIDAT envoye au bridge Android"
                                  );

                                  return true;
                                } catch (error) {
                                  turnstileDebug(
                                    "bridge candidat erreur " +
                                    String(error || "")
                                  );
                                  return false;
                                }
                              };

                            let lastTurnstileSummary = "";

                            const scanTurnstile = () => {
                              if (
                                !isCloudflareTurnstileFrame()
                              ) {
                                return;
                              }

                              const candidates =
                                findTurnstileCandidates();

                              let totalElements = 0;
                              let buttons = 0;
                              let checkboxes = 0;

                              for (
                                const root of turnstileRoots
                              ) {
                                try {
                                  totalElements +=
                                    root.querySelectorAll(
                                      "*"
                                    ).length;

                                  buttons +=
                                    root.querySelectorAll(
                                      "button"
                                    ).length;

                                  checkboxes +=
                                    root.querySelectorAll(
                                      'input[type="checkbox"],' +
                                      '[role="checkbox"]'
                                    ).length;
                                } catch (_) {}
                              }

                              const summary =
                                "SCAN roots=" +
                                turnstileRoots.size +
                                " elements=" +
                                totalElements +
                                " buttons=" +
                                buttons +
                                " checkboxes=" +
                                checkboxes +
                                " candidates=" +
                                candidates.length +
                                " viewport=" +
                                String(
                                  window.innerWidth
                                ) +
                                "x" +
                                String(
                                  window.innerHeight
                                );

                              if (
                                summary !==
                                lastTurnstileSummary
                              ) {
                                lastTurnstileSummary =
                                  summary;
                                turnstileDebug(summary);
                              }

                              const candidate =
                                candidates[0] || null;

                              if (candidate) {
                                requestTurnstileNativeTap(
                                  candidate
                                );
                              }
                            };

                            const startTurnstileScanner = () => {
                              scanTurnstile();

                              try {
                                const observer =
                                  new MutationObserver(
                                    scanTurnstile
                                  );

                                observer.observe(
                                  document.documentElement,
                                  {
                                    childList: true,
                                    subtree: true,
                                    attributes: true,
                                    attributeFilter: [
                                      "class",
                                      "style",
                                      "role",
                                      "type",
                                      "aria-label",
                                      "disabled"
                                    ]
                                  }
                                );

                                window
                                  .__afterdarkTurnstileObserver =
                                    observer;
                              } catch (error) {
                                turnstileDebug(
                                  "MutationObserver erreur " +
                                  String(error || "")
                                );
                              }

                              window
                                .__afterdarkTurnstilePoller =
                                  setInterval(
                                    scanTurnstile,
                                    500
                                  );
                            };

                            if (document.documentElement) {
                              startTurnstileScanner();
                            } else {
                              document.addEventListener(
                                "DOMContentLoaded",
                                startTurnstileScanner,
                                { once: true }
                              );
                            }
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
                Log.i(TAG, "Locator Turnstile + relay Android installé dans toutes les frames")
            } else {
                Log.w(TAG, "Clicker Turnstile profond multi-frame indisponible")
            }

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
                    verificationButtonHasAppeared.set(true)
                cloudflareErrorReloadInProgress.set(false)
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
                            installAutoOpenAndPlay(view)
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
                override fun onPageFinished(
                    view: WebView?,
                    url: String?,
                ) {
                    super.onPageFinished(view, url)
                    cloudflareErrorReloadInProgress.set(false)

                    if (!officialPlayerMode.get()) {
                        installAutoOpenAndPlay(view)
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
