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
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object AfterDarkEmbedWebView {
    private const val TIMEOUT_MS = 45_000L
    private const val USER_ACTIVITY_EXTENSION_MS = 60_000L

    private val mediaExtensions = listOf(
        ".m3u8",
        ".mpd",
        ".mp4",
        ".mkv",
        ".webm",
    )

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolve(
        embedUrl: String,
        sourceName: String,
        referer: String,
    ): ResolvedWebMedia? = suspendCoroutine { continuation ->
        val finished = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        var dialog: Dialog? = null
        var webView: WebView? = null

        lateinit var timeoutRunnable: Runnable

        fun finish(result: ResolvedWebMedia?) {
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

        fun extendTimeout(delayMs: Long = USER_ACTIVITY_EXTENSION_MS) {
            if (finished.get()) return
            handler.removeCallbacks(timeoutRunnable)
            handler.postDelayed(timeoutRunnable, delayMs)
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
                    text = "AfterDark — résolution de la source $sourceName…"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(24, 18, 24, 12)
                }

                val browser = WebView(activity)
                webView = browser

                browser.setBackgroundColor(Color.BLACK)
                browser.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadsImagesAutomatically = true
                    mediaPlaybackRequiresUserGesture = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    cacheMode = WebSettings.LOAD_DEFAULT
                }

                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(browser, true)
                }

                val browserUserAgent = browser.settings.userAgentString
                    ?.takeIf { it.isNotBlank() }
                    ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/149.0 Mobile Safari/537.36"

                fun originOf(url: String): String? =
                    runCatching {
                        val uri = Uri.parse(url)
                        val scheme = uri.scheme ?: return@runCatching null
                        val host = uri.host ?: return@runCatching null
                        val port = uri.port
                        if (port > 0) "$scheme://$host:$port" else "$scheme://$host"
                    }.getOrNull()

                fun mediaFromJavascript(
                    url: String?,
                    contentType: String?,
                    pageUrl: String?,
                ): ResolvedWebMedia? {
                    val value = url?.trim().orEmpty()
                    if (value.isBlank()) return null

                    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
                    val scheme = uri.scheme?.lowercase()
                    if (scheme != "http" && scheme != "https") return null

                    val cleanPath = value
                        .substringBefore("?")
                        .substringBefore("#")
                        .lowercase()
                    val ct = contentType?.lowercase().orEmpty()

                    val type = when {
                        cleanPath.endsWith(".m3u8") ||
                            "mpegurl" in ct -> "m3u8"

                        cleanPath.endsWith(".mpd") ||
                            "dash+xml" in ct -> "mpd"

                        cleanPath.endsWith(".mp4") ||
                            cleanPath.endsWith(".mkv") ||
                            cleanPath.endsWith(".webm") ||
                            ct.startsWith("video/") ||
                            "application/octet-stream" in ct -> "video"

                        else -> return null
                    }

                    val effectivePage = pageUrl
                        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                        ?: embedUrl

                    val headers = linkedMapOf(
                        "User-Agent" to browserUserAgent,
                        "Referer" to effectivePage,
                    )

                    originOf(effectivePage)?.let { origin ->
                        headers["Origin"] = origin
                    }

                    return ResolvedWebMedia(
                        url = value,
                        referer = effectivePage,
                        headers = headers,
                        type = type,
                    )
                }

                val bridge = object {
                    @JavascriptInterface
                    fun media(
                        url: String?,
                        contentType: String?,
                        pageUrl: String?,
                    ) {
                        val resolved = mediaFromJavascript(url, contentType, pageUrl)
                            ?: return
                        finish(resolved)
                    }

                    @JavascriptInterface
                    fun activity() {
                        extendTimeout()
                    }
                }

                browser.addJavascriptInterface(
                    bridge,
                    "__AfterDarkMediaBridge",
                )

                fun captureMedia(webRequest: WebResourceRequest?): ResolvedWebMedia? {
                    if (webRequest == null) return null
                    if (!webRequest.method.equals("GET", ignoreCase = true)) return null

                    val url = webRequest.url.toString()
                    val lowerUrl = url.lowercase()
                    val cleanPath = lowerUrl.substringBefore("?").substringBefore("#")

                    // Do not capture HLS segments as if they were playlists.
                    if (
                        cleanPath.endsWith(".ts") ||
                        cleanPath.endsWith(".m4s") ||
                        cleanPath.endsWith(".aac") ||
                        cleanPath.endsWith(".vtt") ||
                        cleanPath.endsWith(".srt")
                    ) return null

                    val headers = LinkedHashMap<String, String>()
                    webRequest.requestHeaders.forEach { (key, value) ->
                        if (key.isNotBlank() && value.isNotBlank()) {
                            headers[key] = value
                        }
                    }

                    val accept = headers.entries
                        .firstOrNull { (key, _) -> key.equals("Accept", ignoreCase = true) }
                        ?.value
                        ?.lowercase()
                        .orEmpty()

                    val looksLikeMedia =
                        mediaExtensions.any { ext -> cleanPath.endsWith(ext) } ||
                            "application/vnd.apple.mpegurl" in accept ||
                            "application/x-mpegurl" in accept ||
                            "application/dash+xml" in accept ||
                            accept.startsWith("video/")

                    if (!looksLikeMedia) return null

                    val type = when {
                        cleanPath.endsWith(".m3u8") ||
                            "mpegurl" in accept -> "m3u8"

                        cleanPath.endsWith(".mpd") ||
                            "dash+xml" in accept -> "mpd"

                        else -> "video"
                    }

                    val requestReferer = headers.entries
                        .firstOrNull { (key, _) -> key.equals("Referer", ignoreCase = true) }
                        ?.value
                        ?.takeIf { it.isNotBlank() }

                    if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                        headers["User-Agent"] = browserUserAgent
                    }

                    return ResolvedWebMedia(
                        url = url,
                        referer = requestReferer,
                        headers = headers,
                        type = type,
                    )
                }

                fun installHooksAndNudge() {
                    if (finished.get()) return

                    browser.evaluateJavascript(
                        """
                        (() => {
                          try {
                            const bridge = window.__AfterDarkMediaBridge;
                            if (!bridge) return;

                            const report = (url, contentType = '') => {
                              try {
                                if (!url) return;
                                bridge.media(String(url), String(contentType || ''), location.href);
                              } catch (_) {}
                            };

                            const isInterestingUrl = url => {
                              const value = String(url || '').toLowerCase();
                              return value.includes('.m3u8') ||
                                     value.includes('.mpd') ||
                                     value.includes('.mp4') ||
                                     value.includes('.mkv') ||
                                     value.includes('.webm');
                            };

                            if (!window.__afterdarkFetchHooked && window.fetch) {
                              window.__afterdarkFetchHooked = true;
                              const originalFetch = window.fetch.bind(window);

                              window.fetch = async (...args) => {
                                const response = await originalFetch(...args);
                                try {
                                  const url =
                                    response.url ||
                                    (typeof args[0] === 'string'
                                      ? args[0]
                                      : args[0] && args[0].url) ||
                                    '';

                                  const contentType =
                                    response.headers && response.headers.get
                                      ? response.headers.get('content-type') || ''
                                      : '';

                                  if (
                                    isInterestingUrl(url) ||
                                    /mpegurl|dash\+xml|^video\/|octet-stream/i.test(contentType)
                                  ) {
                                    report(url, contentType);
                                  }
                                } catch (_) {}
                                return response;
                              };
                            }

                            if (!window.__afterdarkXhrHooked && window.XMLHttpRequest) {
                              window.__afterdarkXhrHooked = true;
                              const originalOpen = XMLHttpRequest.prototype.open;
                              const originalSend = XMLHttpRequest.prototype.send;

                              XMLHttpRequest.prototype.open = function(method, url, ...rest) {
                                this.__afterdarkUrl = url;
                                return originalOpen.call(this, method, url, ...rest);
                              };

                              XMLHttpRequest.prototype.send = function(...args) {
                                try {
                                  this.addEventListener('loadstart', () => {
                                    try { bridge.activity(); } catch (_) {}
                                  });

                                  this.addEventListener('readystatechange', () => {
                                    try {
                                      if (this.readyState < 2) return;

                                      const url =
                                        this.responseURL ||
                                        this.__afterdarkUrl ||
                                        '';

                                      const contentType =
                                        this.getResponseHeader('content-type') || '';

                                      if (
                                        isInterestingUrl(url) ||
                                        /mpegurl|dash\+xml|^video\/|octet-stream/i.test(contentType)
                                      ) {
                                        report(url, contentType);
                                      }
                                    } catch (_) {}
                                  });
                                } catch (_) {}

                                return originalSend.apply(this, args);
                              };
                            }

                            if (!window.__afterdarkInteractionHooked) {
                              window.__afterdarkInteractionHooked = true;
                              ['click', 'touchstart', 'keydown'].forEach(eventName => {
                                document.addEventListener(
                                  eventName,
                                  () => {
                                    try { bridge.activity(); } catch (_) {}
                                  },
                                  true
                                );
                              });
                            }

                            const scanMedia = () => {
                              try {
                                document
                                  .querySelectorAll('video,audio,source')
                                  .forEach(media => {
                                    const url =
                                      media.currentSrc ||
                                      media.src ||
                                      media.getAttribute('src') ||
                                      '';
                                    if (isInterestingUrl(url)) {
                                      report(url, '');
                                    }
                                  });

                                if (window.performance && performance.getEntriesByType) {
                                  performance
                                    .getEntriesByType("resource")
                                    .forEach(entry => {
                                      if (isInterestingUrl(entry.name)) {
                                        report(entry.name, '');
                                      }
                                    });
                                }
                              } catch (_) {}
                            };

                            scanMedia();

                            if (!window.__afterdarkMediaObserver) {
                              window.__afterdarkMediaObserver = new MutationObserver(scanMedia);
                              window.__afterdarkMediaObserver.observe(document.documentElement, {
                                childList: true,
                                subtree: true,
                                attributes: true,
                                attributeFilter: ['src']
                              });
                            }

                            document.querySelectorAll('video,audio').forEach(media => {
                              try {
                                media.muted = true;
                                media.autoplay = true;
                                const p = media.play();
                                if (p && p.catch) p.catch(() => {});
                              } catch (_) {}
                            });

                            const candidates = [...document.querySelectorAll(
                              'button,[role="button"],.play,.vjs-big-play-button'
                            )];

                            const button = candidates.find(el => {
                              const text = (
                                el.innerText ||
                                el.getAttribute('aria-label') ||
                                el.getAttribute('title') ||
                                ''
                              ).toLowerCase();

                              return text.includes('play') ||
                                     text.includes('lecture') ||
                                     el.classList.contains('vjs-big-play-button');
                            });

                            if (button) {
                              try {
                                bridge.activity();
                                button.click();
                              } catch (_) {}
                            }
                          } catch (_) {}
                        })();
                        """.trimIndent(),
                        null,
                    )
                }

                browser.webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message?,
                    ): Boolean {
                        // Ads/popups are not required to resolve the actual media.
                        return false
                    }
                }

                browser.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val uri = request?.url ?: return true
                        val scheme = uri.scheme?.lowercase()

                        // Keep HTTP(S) redirects inside this resolver WebView.
                        // Never hand off intent:// or custom schemes to another app.
                        return scheme != "http" && scheme != "https"
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): android.webkit.WebResourceResponse? {
                        val media = captureMedia(request)
                        if (media != null) {
                            finish(media)
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(
                        view: WebView?,
                        url: String?,
                    ) {
                        super.onPageFinished(view, url)
                        extendTimeout()
                        installHooksAndNudge()
                        handler.postDelayed({ installHooksAndNudge() }, 1_000L)
                        handler.postDelayed({ installHooksAndNudge() }, 3_000L)
                        handler.postDelayed({ installHooksAndNudge() }, 6_000L)
                        handler.postDelayed({ installHooksAndNudge() }, 12_000L)
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

                val initialHeaders = mapOf(
                    "Referer" to referer,
                    "User-Agent" to browserUserAgent,
                )

                browser.loadUrl(embedUrl, initialHeaders)
                handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
            } catch (_: Exception) {
                finish(null)
            }
        }
    }
}
