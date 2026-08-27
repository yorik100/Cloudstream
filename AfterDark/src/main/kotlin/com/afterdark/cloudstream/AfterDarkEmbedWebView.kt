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
import android.webkit.WebResourceError
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

                        // Only explicit Videasy UI/player states call this:
                        // error page, missing rendered player, main-frame error,
                        // or an explicit HTML5 media error.
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

                fun installHooksAndNudge() {
                    if (finished.get()) return

                    browser.evaluateJavascript(
                        """
                        (() => {
                          try {
                            const bridge = window.__AfterDarkMediaBridge;
                            if (!bridge) return;

                            const VIDEASY_MODE = ${if (videasyMode) "true" else "false"};

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
                                  const requestedUrl =
                                    typeof args[0] === 'string'
                                      ? args[0]
                                      : args[0] && args[0].url
                                        ? args[0].url
                                        : '';

                                  const url = response.url || requestedUrl || '';
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

                            if (
                              VIDEASY_MODE &&
                              !window.__afterdarkVideasyLoadHooked
                            ) {
                              window.__afterdarkVideasyLoadHooked = true;
                              window.addEventListener('load', checkVideasyDomState, {
                                once: true
                              });
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

                            const normalizedText = value =>
                              String(value || '')
                                .replace(/\s+/g, ' ')
                                .trim()
                                .toLowerCase();

                            const isVisible = element => {
                              if (!element || !element.isConnected) return false;
                              const style = getComputedStyle(element);
                              if (
                                style.display === 'none' ||
                                style.visibility === 'hidden' ||
                                Number(style.opacity || 1) === 0
                              ) return false;
                              const rect = element.getBoundingClientRect();
                              return rect.width > 0 && rect.height > 0;
                            };

                            const hasGoBackError = () =>
                              [...document.querySelectorAll('a[href="/"]')].some(anchor =>
                                isVisible(anchor) && normalizedText(anchor.textContent) === 'go back'
                              );

                            const isVideasyPage = () => {
                              if (!VIDEASY_MODE) return false;
                              const host = String(location.hostname || '').toLowerCase();
                              return host === 'player.videasy.to' ||
                                     host.endsWith('.videasy.to');
                            };

                            const findVideasyPlayButton = () => {
                              if (!isVideasyPage()) return null;

                              // Prefer the exact Videasy play icon supplied by
                              // the player: <path d="M8 5v14l11-7z">.
                              const iconButton = [...document.querySelectorAll('svg')].
                                map(svg => ({
                                  svg,
                                  path: svg.querySelector('path[d="M8 5v14l11-7z"]')
                                })).
                                find(item =>
                                  item.path &&
                                  isVisible(item.svg) &&
                                  item.svg.closest('button,[role="button"]')
                                );

                              if (iconButton) {
                                const owner = iconButton.svg.closest('button,[role="button"]');
                                if (owner && isVisible(owner)) return owner;
                              }

                              // Fallback for future Videasy markup changes.
                              return [...document.querySelectorAll('button,[role="button"]')]
                                .find(element => {
                                  if (!isVisible(element)) return false;
                                  const label = normalizedText(
                                    element.getAttribute('aria-label') ||
                                    element.getAttribute('title') ||
                                    element.textContent
                                  );
                                  return label === 'play' ||
                                         label === 'lecture' ||
                                         label === 'lire';
                                }) || null;
                            };

                            const tryVideasyAutoPlay = () => {
                              if (!isVideasyPage()) return false;
                              if (window.__afterdarkVideasyPlayClicked) return true;

                              const playButton = findVideasyPlayButton();
                              if (!playButton) return false;

                              try {
                                window.__afterdarkVideasyPlayClicked = true;
                                bridge.activity();
                                playButton.click();
                                return true;
                              } catch (_) {
                                window.__afterdarkVideasyPlayClicked = false;
                                return false;
                              }
                            };

                            const playerSelectors = [
                              'video',
                              'audio',
                              'iframe[src]',
                              '.video-js',
                              '.plyr',
                              '.jwplayer',
                              '[data-player]',
                              '[data-testid*="player" i]',
                              '[class*="video-player" i]',
                              '[class*="media-player" i]'
                            ];

                            const hasPlayer = () =>
                              playerSelectors.some(selector =>
                                [...document.querySelectorAll(selector)].some(isVisible)
                              );

                            const hasLoadingState = () => {
                              const selectors = [
                                '[role="progressbar"]',
                                '[aria-busy="true"]',
                                '.animate-spin',
                                '.spinner',
                                '[class*="loading" i]'
                              ];

                              if (
                                selectors.some(selector =>
                                  [...document.querySelectorAll(selector)].some(isVisible)
                                )
                              ) return true;

                              return [...document.querySelectorAll('div,span,p')].some(element => {
                                if (!isVisible(element)) return false;
                                const text = normalizedText(element.textContent);
                                return text === 'loading' ||
                                       text === 'loading...' ||
                                       text === 'chargement' ||
                                       text === 'chargement...';
                              });
                            };

                            const renderedAppRoot = () => {
                              const root =
                                document.querySelector('#root') ||
                                document.querySelector('#app') ||
                                document.querySelector('main');

                              if (!root || !isVisible(root)) return null;
                              if (!root.children || root.children.length === 0) return null;
                              return root;
                            };

                            const checkVideasyDomState = () => {
                              if (!VIDEASY_MODE) return;

                              if (hasGoBackError()) {
                                bridge.unavailable('videasy-go-back');
                                return;
                              }

                              if (hasPlayer()) {
                                window.__afterdarkVideasyPlayerSeen = true;
                                return;
                              }

                              // "No player at all" is only terminal when the
                              // application has actually rendered content and
                              // is not presenting a loading state. This is a DOM
                              // state check, not an elapsed-time heuristic.
                              const root = renderedAppRoot();
                              if (
                                document.readyState === 'complete' &&
                                root &&
                                !hasLoadingState() &&
                                !window.__afterdarkVideasyPlayerSeen
                              ) {
                                const text = normalizedText(root.textContent);
                                const hasInteractiveOrMessage =
                                  text.length > 0 ||
                                  !!root.querySelector('a,button,[role="button"],svg,img');

                                if (hasInteractiveOrMessage) {
                                  bridge.unavailable('videasy-no-player');
                                }
                              }
                            };

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
                            tryVideasyAutoPlay();
                            checkVideasyDomState();

                            if (!window.__afterdarkMediaObserver) {
                              window.__afterdarkMediaObserver = new MutationObserver(() => {
                                scanMedia();
                                tryVideasyAutoPlay();
                                checkVideasyDomState();
                              });
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
                                    bridge.unavailable('videasy-media-error');
                                  });
                                }

                                media.muted = true;
                                media.autoplay = true;
                                const p = media.play();
                                if (p && p.catch) p.catch(() => {});
                              } catch (_) {}
                            });

                            if (!VIDEASY_MODE) {
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

                        if (
                            videasyMode &&
                            request.isForMainFrame &&
                            uri.host.equals("player.videasy.to", ignoreCase = true) &&
                            (uri.path.isNullOrBlank() || uri.path == "/")
                        ) {
                            finish(null)
                            return true
                        }

                        // Keep HTTP(S) redirects inside this resolver WebView.
                        // Never hand off intent:// or custom schemes to another app.
                        return scheme != "http" && scheme != "https"
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: android.webkit.WebResourceResponse?,
                    ) {
                        super.onReceivedHttpError(view, request, errorResponse)

                        val statusCode = errorResponse?.statusCode
                        if (
                            videasyMode &&
                            request?.isForMainFrame == true &&
                            (statusCode == 404 || statusCode == 410)
                        ) {
                            finish(null)
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        super.onReceivedError(view, request, error)

                        if (videasyMode && request?.isForMainFrame == true) {
                            finish(null)
                        }
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

                        if (!videasyMode) {
                            handler.postDelayed({ installHooksAndNudge() }, 1_000L)
                            handler.postDelayed({ installHooksAndNudge() }, 3_000L)
                            handler.postDelayed({ installHooksAndNudge() }, 6_000L)
                            handler.postDelayed({ installHooksAndNudge() }, 12_000L)
                        }
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

                // Videasy availability is decided from its rendered player/UI
                // state and explicit main-frame/media failures. It never falls
                // through to Peachify because an arbitrary timer expired.
                if (!videasyMode) {
                    handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
                }
            } catch (_: Exception) {
                finish(null)
            }
        }
    }
}
