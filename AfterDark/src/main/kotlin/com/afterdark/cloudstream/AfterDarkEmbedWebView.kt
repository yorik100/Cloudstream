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
    private const val TAG = "AfterDarkEmbedWebView"

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

        fun finish(result: ResolvedWebMedia?) {
            if (!finished.compareAndSet(false, true)) return

            if (result != null) {
                Log.i(TAG, "Source $sourceName résolue : ${result.type} ${result.url}")
            } else {
                Log.w(TAG, "Résolution $sourceName terminée sans média")
            }

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

                val initialHeaders = mapOf(
                    "Referer" to referer,
                    "User-Agent" to browserUserAgent,
                )

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
                    mediaReferer: String?,
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

                    val effectiveReferer = mediaReferer
                        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                        ?: effectivePage

                    val headers = linkedMapOf(
                        "User-Agent" to browserUserAgent,
                        "Referer" to effectiveReferer,
                    )

                    originOf(effectiveReferer)?.let { origin ->
                        headers["Origin"] = origin
                    }

                    return ResolvedWebMedia(
                        url = value,
                        referer = effectiveReferer,
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
                        mediaReferer: String?,
                    ) {
                        val resolved = mediaFromJavascript(
                            url = url,
                            contentType = contentType,
                            pageUrl = pageUrl,
                            mediaReferer = mediaReferer,
                        ) ?: return

                        finish(resolved)
                    }

                    @JavascriptInterface
                    fun activity() = Unit

                    @JavascriptInterface
                    fun unavailable(reason: String?) {
                        Log.w(
                            TAG,
                            "Source $sourceName indisponible : ${reason.orEmpty()}",
                        )
                        finish(null)
                    }
                }

                browser.addJavascriptInterface(
                    bridge,
                    "__AfterDarkMediaBridge",
                )

                fun captureMedia(
                    webRequest: WebResourceRequest?,
                ): ResolvedWebMedia? {
                    if (webRequest == null) return null
                    if (!webRequest.method.equals("GET", ignoreCase = true)) return null

                    val url = webRequest.url.toString()
                    val cleanPath = url
                        .substringBefore("?")
                        .substringBefore("#")
                        .lowercase()

                    // Never mistake HLS/DASH segments or subtitle files for the
                    // actual playlist/media URL.
                    if (
                        cleanPath.endsWith(".ts") ||
                        cleanPath.endsWith(".m4s") ||
                        cleanPath.endsWith(".aac") ||
                        cleanPath.endsWith(".vtt") ||
                        cleanPath.endsWith(".srt")
                    ) {
                        return null
                    }

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
                        ?.lowercase()
                        .orEmpty()

                    val looksLikeMedia =
                        mediaExtensions.any { extension ->
                            cleanPath.endsWith(extension)
                        } ||
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
                        .firstOrNull { (key, _) ->
                            key.equals("Referer", ignoreCase = true)
                        }
                        ?.value
                        ?.takeIf { it.isNotBlank() }

                    if (headers.keys.none {
                            it.equals("User-Agent", ignoreCase = true)
                        }
                    ) {
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

                            const report = (
                              url,
                              contentType = '',
                              mediaReferer = ''
                            ) => {
                              try {
                                if (!url) return;
                                bridge.media(
                                  String(url),
                                  String(contentType || ''),
                                  location.href,
                                  String(mediaReferer || '')
                                );
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

                            const mediaTypeHint = value => {
                              const type = String(value || '').toLowerCase();

                              if (
                                type.includes('hls') ||
                                type.includes('m3u8')
                              ) {
                                return 'application/vnd.apple.mpegurl';
                              }

                              if (
                                type.includes('dash') ||
                                type.includes('mpd')
                              ) {
                                return 'application/dash+xml';
                              }

                              if (
                                type.includes('mp4') ||
                                type.includes('video')
                              ) {
                                return 'video/mp4';
                              }

                              return '';
                            };

                            const scanStructuredMedia = (
                              value,
                              seen = new WeakSet(),
                              depth = 0
                            ) => {
                              if (depth > 10 || value == null) return;

                              if (typeof value === 'string') {
                                if (isInterestingUrl(value)) {
                                  report(value, '');
                                }
                                return;
                              }

                              if (typeof value !== 'object') return;
                              if (seen.has(value)) return;
                              seen.add(value);

                              try {
                                const hint = mediaTypeHint(
                                  value.type ||
                                  value.format ||
                                  value.mimeType
                                );

                                const candidate =
                                  value.file ||
                                  value.stream ||
                                  value.playlist ||
                                  value.manifest ||
                                  value.src ||
                                  value.url;

                                const candidateReferer =
                                  value.referer ||
                                  value.referrer ||
                                  '';

                                if (
                                  typeof candidate === 'string' &&
                                  (isInterestingUrl(candidate) || hint)
                                ) {
                                  report(
                                    candidate,
                                    hint,
                                    candidateReferer
                                  );
                                }

                                Object.values(value).forEach(child => {
                                  scanStructuredMedia(
                                    child,
                                    seen,
                                    depth + 1
                                  );
                                });
                              } catch (_) {}
                            };

                            if (
                              !window.__afterdarkJsonParseHooked &&
                              window.JSON
                            ) {
                              window.__afterdarkJsonParseHooked = true;
                              const originalJsonParse = JSON.parse.bind(JSON);

                              JSON.parse = (...args) => {
                                const result = originalJsonParse(...args);
                                try {
                                  scanStructuredMedia(result);
                                } catch (_) {}
                                return result;
                              };
                            }

                            if (
                              !window.__afterdarkFetchHooked &&
                              window.fetch
                            ) {
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

                                  const url =
                                    response.url ||
                                    requestedUrl ||
                                    '';

                                  const contentType =
                                    response.headers &&
                                    response.headers.get
                                      ? response.headers.get(
                                          'content-type'
                                        ) || ''
                                      : '';

                                  if (
                                    isInterestingUrl(url) ||
                                    /mpegurl|dash\+xml|^video\/|octet-stream/i
                                      .test(contentType)
                                  ) {
                                    report(url, contentType);
                                  }

                                  if (response.clone) {
                                    response
                                      .clone()
                                      .text()
                                      .then(text => {
                                        try {
                                          scanStructuredMedia(
                                            JSON.parse(text)
                                          );
                                        } catch (_) {}
                                      })
                                      .catch(() => {});
                                  }
                                } catch (_) {}

                                return response;
                              };
                            }

                            if (
                              !window.__afterdarkXhrHooked &&
                              window.XMLHttpRequest
                            ) {
                              window.__afterdarkXhrHooked = true;
                              const originalOpen =
                                XMLHttpRequest.prototype.open;
                              const originalSend =
                                XMLHttpRequest.prototype.send;

                              XMLHttpRequest.prototype.open =
                                function(method, url, ...rest) {
                                  this.__afterdarkUrl = url;
                                  return originalOpen.call(
                                    this,
                                    method,
                                    url,
                                    ...rest
                                  );
                                };

                              XMLHttpRequest.prototype.send =
                                function(...args) {
                                  try {
                                    this.addEventListener(
                                      'readystatechange',
                                      () => {
                                        try {
                                          if (this.readyState < 2) return;

                                          const url =
                                            this.responseURL ||
                                            this.__afterdarkUrl ||
                                            '';

                                          const contentType =
                                            this.getResponseHeader(
                                              'content-type'
                                            ) || '';

                                          if (
                                            isInterestingUrl(url) ||
                                            /mpegurl|dash\+xml|^video\/|octet-stream/i
                                              .test(contentType)
                                          ) {
                                            report(url, contentType);
                                          }

                                          if (
                                            this.readyState === 4 &&
                                            typeof this.responseText ===
                                              'string'
                                          ) {
                                            try {
                                              scanStructuredMedia(
                                                JSON.parse(
                                                  this.responseText
                                                )
                                              );
                                            } catch (_) {}
                                          }
                                        } catch (_) {}
                                      }
                                    );
                                  } catch (_) {}

                                  return originalSend.apply(this, args);
                                };
                            }

                            const normalizedText = value =>
                              String(value || '')
                                .replace(/\s+/g, ' ')
                                .trim()
                                .toLowerCase();

                            const isVisible = element => {
                              if (
                                !element ||
                                !element.isConnected
                              ) {
                                return false;
                              }

                              const style = getComputedStyle(element);

                              if (
                                style.display === 'none' ||
                                style.visibility === 'hidden' ||
                                Number(style.opacity || 1) === 0
                              ) {
                                return false;
                              }

                              const rect =
                                element.getBoundingClientRect();

                              return (
                                rect.width > 0 &&
                                rect.height > 0
                              );
                            };

                            const collectPeachifyRoots = () => {
                              const roots = [];
                              const visited = new Set();

                              const visit = root => {
                                if (!root || visited.has(root)) return;

                                visited.add(root);
                                roots.push(root);

                                try {
                                  root
                                    .querySelectorAll('*')
                                    .forEach(element => {
                                      if (element.shadowRoot) {
                                        visit(element.shadowRoot);
                                      }
                                    });
                                } catch (_) {}

                                try {
                                  root
                                    .querySelectorAll('iframe')
                                    .forEach(frame => {
                                      try {
                                        visit(frame.contentDocument);
                                      } catch (_) {}
                                    });
                                } catch (_) {}
                              };

                              visit(document);
                              return roots;
                            };

                            const hasPeachifyNotFoundPanel = () => {
                              return collectPeachifyRoots().some(root => {
                                let closeButton = null;

                                try {
                                  closeButton = [
                                    ...root.querySelectorAll('button')
                                  ].find(element =>
                                    normalizedText(
                                      element.getAttribute(
                                        'aria-label'
                                      )
                                    ) === 'close error message'
                                  );
                                } catch (_) {
                                  return false;
                                }

                                if (!closeButton) return false;

                                let panel = closeButton.parentElement;
                                let depth = 0;

                                while (panel && depth < 12) {
                                  const exactTitle = [
                                    ...panel.querySelectorAll(
                                      'div,h1,h2,h3,p,span'
                                    )
                                  ].some(element =>
                                    normalizedText(
                                      element.textContent
                                    ) === '404 - not found'
                                  );

                                  const reloadButton = [
                                    ...panel.querySelectorAll(
                                      'button,[role="button"]'
                                    )
                                  ].some(element =>
                                    normalizedText(
                                      element.textContent
                                    ) === 'reload'
                                  );

                                  if (
                                    exactTitle &&
                                    reloadButton
                                  ) {
                                    return true;
                                  }

                                  panel = panel.parentElement;
                                  depth += 1;
                                }

                                return false;
                              });
                            };

                            const checkPeachifyDomState = () => {
                              if (hasPeachifyNotFoundPanel()) {
                                bridge.unavailable(
                                  'peachify-404-not-found'
                                );
                                return true;
                              }

                              return false;
                            };

                            if (
                              !window.__afterdarkPeachifyNotFoundTimer
                            ) {
                              window.__afterdarkPeachifyNotFoundTimer =
                                setInterval(
                                  checkPeachifyDomState,
                                  250
                                );
                            }

                            const scanMedia = () => {
                              try {
                                document
                                  .querySelectorAll(
                                    'video,audio,source'
                                  )
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

                                if (
                                  window.performance &&
                                  performance.getEntriesByType
                                ) {
                                  performance
                                    .getEntriesByType('resource')
                                    .forEach(entry => {
                                      if (
                                        isInterestingUrl(
                                          entry.name
                                        )
                                      ) {
                                        report(entry.name, '');
                                      }
                                    });
                                }
                              } catch (_) {}
                            };

                            const nudgePlayer = () => {
                              document
                                .querySelectorAll('video,audio')
                                .forEach(media => {
                                  try {
                                    media.muted = true;
                                    media.autoplay = true;

                                    const promise = media.play();
                                    if (
                                      promise &&
                                      promise.catch
                                    ) {
                                      promise.catch(() => {});
                                    }
                                  } catch (_) {}
                                });

                              const candidates = [
                                ...document.querySelectorAll(
                                  'button,' +
                                  '[role="button"],' +
                                  '.play,' +
                                  '.vjs-big-play-button'
                                )
                              ];

                              const button = candidates.find(
                                element => {
                                  const text = (
                                    element.innerText ||
                                    element.getAttribute(
                                      'aria-label'
                                    ) ||
                                    element.getAttribute(
                                      'title'
                                    ) ||
                                    ''
                                  ).toLowerCase();

                                  return (
                                    text.includes('play') ||
                                    text.includes('lecture') ||
                                    element.classList.contains(
                                      'vjs-big-play-button'
                                    )
                                  );
                                }
                              );

                              if (button) {
                                try {
                                  button.click();
                                } catch (_) {}
                              }
                            };

                            scanMedia();
                            checkPeachifyDomState();
                            nudgePlayer();

                            if (!window.__afterdarkMediaObserver) {
                              window.__afterdarkMediaObserver =
                                new MutationObserver(() => {
                                  scanMedia();
                                  checkPeachifyDomState();
                                  nudgePlayer();
                                });

                              window.__afterdarkMediaObserver.observe(
                                document.documentElement,
                                {
                                  childList: true,
                                  subtree: true,
                                  attributes: true,
                                  attributeFilter: [
                                    'src',
                                    'class',
                                    'style',
                                    'aria-label'
                                  ]
                                }
                              );
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

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: android.webkit.WebResourceResponse?,
                    ) {
                        super.onReceivedHttpError(
                            view,
                            request,
                            errorResponse,
                        )

                        val statusCode = errorResponse?.statusCode

                        if (
                            request?.isForMainFrame == true &&
                            (statusCode == 404 || statusCode == 410)
                        ) {
                            Log.w(
                                TAG,
                                "Erreur HTTP $sourceName " +
                                    "$statusCode sur ${request.url}",
                            )
                            finish(null)
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        super.onReceivedError(
                            view,
                            request,
                            error,
                        )

                        if (request?.isForMainFrame == true) {
                            Log.w(
                                TAG,
                                "Erreur WebView $sourceName " +
                                    "${error?.errorCode}: " +
                                    "${error?.description}",
                            )
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

                        return super.shouldInterceptRequest(
                            view,
                            request,
                        )
                    }

                    override fun onPageFinished(
                        view: WebView?,
                        url: String?,
                    ) {
                        super.onPageFinished(view, url)
                        Log.i(
                            TAG,
                            "Page $sourceName chargée : " +
                                url.orEmpty(),
                        )
                        installHooksAndNudge()
                    }

                    override fun onPageStarted(
                        view: WebView?,
                        url: String?,
                        favicon: android.graphics.Bitmap?,
                    ) {
                        super.onPageStarted(
                            view,
                            url,
                            favicon,
                        )

                        // Install hooks as soon as navigation begins so client-side
                        // API calls are observed before the player fully mounts.
                        handler.post {
                            installHooksAndNudge()
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

                dialog = Dialog(
                    activity,
                    android.R.style.Theme_Black_NoTitleBar_Fullscreen,
                ).apply {
                    setContentView(root)
                    setCancelable(true)
                    setOnCancelListener {
                        finish(null)
                    }
                    setOnDismissListener {
                        if (!finished.get()) {
                            finish(null)
                        }
                    }
                    show()
                }

                browser.loadUrl(
                    embedUrl,
                    initialHeaders,
                )
            } catch (error: Exception) {
                Log.w(
                    TAG,
                    "Erreur résolution $sourceName",
                    error,
                )
                finish(null)
            }
        }
    }
}
