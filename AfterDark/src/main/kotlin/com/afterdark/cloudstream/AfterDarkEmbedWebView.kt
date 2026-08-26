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
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
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
        val videasyMode = sourceName.equals("videasy", ignoreCase = true)
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

        timeoutRunnable = Runnable {
            // Peachify/other fallbacks keep a safety timeout. Videasy must not
            // be declared unavailable from elapsed loading time.
            if (!videasyMode) finish(null)
        }

        fun extendTimeout(delayMs: Long = USER_ACTIVITY_EXTENSION_MS) {
            if (finished.get() || videasyMode) return
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

                    @JavascriptInterface
                    fun unavailable(reason: String?) {
                        if (!videasyMode) return

                        // This is only called from explicit Videasy signals:
                        // sources-with-title failure/empty response, decrypted
                        // sources[] empty, or an explicit media error.
                        finish(null)
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

                fun isVideasySourceResolutionRequest(
                    webRequest: WebResourceRequest?,
                ): Boolean {
                    if (!videasyMode || webRequest == null) return false
                    if (!webRequest.method.equals("GET", ignoreCase = true)) return false

                    val uri = webRequest.url
                    val host = uri.host?.lowercase().orEmpty()
                    val path = uri.path?.lowercase().orEmpty()

                    return host == "api.speedracelight.com" &&
                        path.endsWith("/sources-with-title")
                }

                fun executeVideasySourceResolutionOnce(
                    webRequest: WebResourceRequest,
                ): WebResourceResponse? {
                    val requestUrl = webRequest.url.toString()
                    val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        instanceFollowRedirects = true
                        connectTimeout = 20_000
                        readTimeout = 60_000
                        useCaches = false

                        webRequest.requestHeaders.forEach { (key, value) ->
                            if (
                                key.isNotBlank() &&
                                value.isNotBlank() &&
                                !key.equals("Host", ignoreCase = true) &&
                                !key.equals("Connection", ignoreCase = true) &&
                                !key.equals("Content-Length", ignoreCase = true) &&
                                !key.equals("Cookie", ignoreCase = true) &&
                                !key.equals("Accept-Encoding", ignoreCase = true)
                            ) {
                                setRequestProperty(key, value)
                            }
                        }

                        setRequestProperty("Accept-Encoding", "identity")

                        CookieManager.getInstance()
                            .getCookie(requestUrl)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { setRequestProperty("Cookie", it) }
                    }

                    return try {
                        val statusCode = connection.responseCode
                        val stream = if (statusCode >= 400) {
                            connection.errorStream
                        } else {
                            connection.inputStream
                        }
                        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)

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

                        val mimeType = connection.contentType
                            ?.substringBefore(";")
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: "application/octet-stream"

                        val reason = connection.responseMessage
                            ?.takeIf { it.isNotBlank() }
                            ?: "HTTP $statusCode"

                        // This is Videasy's own availability result. A terminal
                        // 404/410 means the requested episode has no source on
                        // Videasy. 204/empty successful responses are also
                        // explicit no-source results, not loading heuristics.
                        if (
                            statusCode == 404 ||
                            statusCode == 410 ||
                            statusCode == 204 ||
                            (statusCode in 200..299 && bytes.isEmpty())
                        ) {
                            finish(null)
                        }

                        WebResourceResponse(
                            mimeType,
                            null,
                            statusCode,
                            reason,
                            responseHeaders,
                            ByteArrayInputStream(bytes),
                        )
                    } catch (_: Exception) {
                        // A real request failure is an explicit resolver failure.
                        finish(null)
                        null
                    } finally {
                        connection.disconnect()
                    }
                }

                fun installHooksAndNudge() {
                    if (finished.get()) return

                    browser.evaluateJavascript(
                        """
                        (() => {
                          try {
                            const bridge = window.__AfterDarkMediaBridge;
                            if (!bridge) return;

                            const VIDEASY_MODE = ${'$'}{if (videasyMode) "true" else "false"};

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

                            const isVideasySourceRequest = url => {
                              if (!VIDEASY_MODE) return false;
                              const value = String(url || '').toLowerCase();
                              return value.includes('/sources-with-title');
                            };

                            const sourceLooksPlayable = source => {
                              if (!source) return false;

                              if (typeof source === 'string') {
                                return /^https?:\/\//i.test(source) ||
                                       source.includes('.m3u8') ||
                                       source.includes('.mpd') ||
                                       source.includes('.mp4');
                              }

                              if (typeof source !== 'object') return false;

                              const candidate =
                                source.url ||
                                source.file ||
                                source.src ||
                                source.source ||
                                source.stream ||
                                '';

                              return typeof candidate === 'string' &&
                                     candidate.length > 0;
                            };

                            const inspectVideasyPayload = (value, rawText = '') => {
                              if (!VIDEASY_MODE) return;

                              try {
                                const raw = String(rawText || '');
                                const rawLooksLikeMediaPayload =
                                  raw.includes('"sources"') &&
                                  (
                                    raw.includes('"subtitles"') ||
                                    raw.includes('"tracks"') ||
                                    raw.includes('"captions"')
                                  );

                                const visit = (node, depth = 0) => {
                                  if (!node || typeof node !== 'object' || depth > 3) {
                                    return false;
                                  }

                                  if (Array.isArray(node.sources)) {
                                    const companionKeys =
                                      Array.isArray(node.subtitles) ||
                                      Array.isArray(node.tracks) ||
                                      Array.isArray(node.captions) ||
                                      rawLooksLikeMediaPayload;

                                    if (companionKeys) {
                                      if (node.sources.some(sourceLooksPlayable)) {
                                        window.__afterdarkVideasySourcesConfirmed = true;
                                      } else {
                                        bridge.unavailable('videasy-sources-empty');
                                      }
                                      return true;
                                    }
                                  }

                                  for (const key of ['data', 'result', 'payload', 'response']) {
                                    if (visit(node[key], depth + 1)) return true;
                                  }

                                  return false;
                                };

                                visit(value);
                              } catch (_) {}
                            };

                            if (VIDEASY_MODE && !window.__afterdarkJsonParseHooked) {
                              window.__afterdarkJsonParseHooked = true;
                              const originalJsonParse = JSON.parse;

                              JSON.parse = function(text, reviver) {
                                const value = originalJsonParse.call(this, text, reviver);
                                try {
                                  inspectVideasyPayload(value, text);
                                } catch (_) {}
                                return value;
                              };
                            }

                            if (!window.__afterdarkFetchHooked && window.fetch) {
                              window.__afterdarkFetchHooked = true;
                              const originalFetch = window.fetch.bind(window);

                              window.fetch = async (...args) => {
                                const requestedUrl =
                                  typeof args[0] === 'string'
                                    ? args[0]
                                    : args[0] && args[0].url
                                      ? args[0].url
                                      : '';

                                let response;

                                try {
                                  response = await originalFetch(...args);
                                } catch (error) {
                                  if (isVideasySourceRequest(requestedUrl)) {
                                    bridge.unavailable('videasy-source-network-error');
                                  }
                                  throw error;
                                }

                                try {
                                  const url = response.url || requestedUrl || '';

                                  const contentType =
                                    response.headers && response.headers.get
                                      ? response.headers.get('content-type') || ''
                                      : '';

                                  if (isVideasySourceRequest(url)) {
                                    // This is Videasy's own source-resolution
                                    // request, not a player-loading timer.
                                    if (
                                      response.status === 204 ||
                                      response.status === 404 ||
                                      response.status === 410 ||
                                      response.status >= 500
                                    ) {
                                      bridge.unavailable(
                                        'videasy-source-http-' + response.status
                                      );
                                    } else if (response.clone) {
                                      response.clone().text().then(text => {
                                        if (!String(text || '').trim()) {
                                          bridge.unavailable('videasy-source-empty-response');
                                        }
                                      }).catch(() => {});
                                    }
                                  }

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
                                        this.readyState === 4 &&
                                        isVideasySourceRequest(url)
                                      ) {
                                        if (
                                          this.status === 0 ||
                                          this.status === 204 ||
                                          this.status === 404 ||
                                          this.status === 410 ||
                                          this.status >= 500
                                        ) {
                                          bridge.unavailable(
                                            'videasy-source-http-' + this.status
                                          );
                                        } else if (
                                          typeof this.responseText === 'string' &&
                                          !this.responseText.trim()
                                        ) {
                                          bridge.unavailable(
                                            'videasy-source-empty-response'
                                          );
                                        }
                                      }

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
                                if (
                                  VIDEASY_MODE &&
                                  !media.__afterdarkErrorHooked
                                ) {
                                  media.__afterdarkErrorHooked = true;
                                  media.addEventListener('error', () => {
                                    if (!window.__afterdarkVideasySourcesConfirmed) {
                                      bridge.unavailable('videasy-media-error');
                                    }
                                  });
                                }

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
                    ): WebResourceResponse? {
                        if (isVideasySourceResolutionRequest(request)) {
                            return executeVideasySourceResolutionOnce(request!!)
                                ?: super.shouldInterceptRequest(view, request)
                        }

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

                // Videasy availability is decided only by explicit source/media
                // signals. It never falls through to Peachify because "it took
                // too long". Peachify itself keeps the generic safety timeout.
                if (!videasyMode) {
                    handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
                }
            } catch (_: Exception) {
                finish(null)
            }
        }
    }
}
