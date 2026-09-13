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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.lagradost.cloudstream3.app
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

object AfterDarkEmbedWebView {
    private const val TAG = "AfterDarkEmbedWebView"
    private const val MAX_VIDEASY_ENCRYPTED_CHARS = 4_000_000
    private const val VIDEASY_DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/142.0.0.0 Safari/537.36"

    private val mediaExtensions = listOf(
        ".m3u8",
        ".mpd",
        ".mp4",
        ".mkv",
        ".webm",
    )

    private fun videasyTmdbId(url: String): String? =
        runCatching {
            Uri.parse(url).pathSegments
                .let { parts ->
                    val typeIndex = parts.indexOfFirst { it == "tv" || it == "movie" }
                    parts.getOrNull(typeIndex + 1)
                }
                ?.takeIf { value -> value.all { character -> character.isDigit() } }
        }.getOrNull()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolve(
        embedUrl: String,
        sourceName: String,
        referer: String,
    ): ResolvedWebMedia? = suspendCoroutine { continuation ->
        val finished = AtomicBoolean(false)
        val videasyMode = sourceName.equals("videasy", ignoreCase = true)
        val peachifyMode = sourceName.equals("peachify", ignoreCase = true)
        val handler = Handler(Looper.getMainLooper())
        var dialog: Dialog? = null
        var webView: WebView? = null
        val loggedVideasyRequests = AtomicInteger(0)

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

                val platformUserAgent = browser.settings.userAgentString
                    ?.takeIf { it.isNotBlank() }
                    ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/149.0 Mobile Safari/537.36"
                val browserUserAgent = if (videasyMode) {
                    VIDEASY_DESKTOP_USER_AGENT
                } else {
                    platformUserAgent
                }

                if (videasyMode) {
                    browser.settings.userAgentString = browserUserAgent
                    browser.settings.useWideViewPort = true
                    browser.settings.loadWithOverviewMode = true
                    Log.i(TAG, "Videasy utilise le profil Chrome bureau")
                }

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
                            url,
                            contentType,
                            pageUrl,
                            mediaReferer,
                        )
                            ?: return
                        finish(resolved)
                    }

                    @JavascriptInterface
                    fun videasyEncrypted(payload: String?, sourceKey: String?) {
                        if (!videasyMode || finished.get()) return
                        val encrypted = payload?.takeIf {
                            it.isNotBlank() && it.length <= MAX_VIDEASY_ENCRYPTED_CHARS
                        } ?: return
                        val provider = sourceKey.orEmpty()
                        Log.i(TAG, "Réponse Videasy reçue par Chromium ($provider)")

                        CoroutineScope(Dispatchers.IO).launch {
                            val response = runCatching {
                                app.post(
                                    url = "https://enc-dec.app/api/dec-videasy",
                                    json = mapOf(
                                        "text" to encrypted,
                                        "id" to videasyTmdbId(embedUrl).orEmpty(),
                                    ),
                                    headers = mapOf(
                                        "Accept" to "application/json",
                                        "Content-Type" to "application/json",
                                        "User-Agent" to VIDEASY_DESKTOP_USER_AGENT,
                                    ),
                                    cacheTime = 0,
                                )
                            }.onFailure { error ->
                                Log.w(
                                    TAG,
                                    "Déchiffrement Chromium impossible ($provider) : " +
                                        "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                                )
                            }.getOrNull()

                            val mediaUrl: String? = response
                                ?.takeIf { it.okhttpResponse.code in 200..299 }
                                ?.let { decrypted ->
                                    runCatching {
                                        val root = JSONObject(decrypted.text)
                                        val result = when (val value = root.opt("result")) {
                                            is JSONObject -> value
                                            is String -> JSONObject(value)
                                            else -> null
                                        } ?: return@runCatching null

                                        val sources = result.optJSONArray("sources")
                                            ?: return@runCatching null
                                        for (index in 0 until sources.length()) {
                                            val candidate = sources.optJSONObject(index)
                                                ?.optString("url", "")
                                                ?.trim()
                                                .orEmpty()
                                            if (
                                                candidate.startsWith("https://") ||
                                                candidate.startsWith("http://")
                                            ) return@runCatching candidate
                                        }
                                        null
                                    }.onFailure { error ->
                                        Log.w(
                                            TAG,
                                            "Réponse Chromium $provider illisible",
                                            error,
                                        )
                                    }.getOrNull()
                                }

                            if (mediaUrl == null) {
                                Log.w(TAG, "Provider Videasy $provider sans flux")
                                return@launch
                            }

                            finish(
                                ResolvedWebMedia(
                                    url = mediaUrl,
                                    referer = "https://player.videasy.to/",
                                    headers = mapOf(
                                        "User-Agent" to VIDEASY_DESKTOP_USER_AGENT,
                                    ),
                                    type = "m3u8",
                                ),
                            )
                        }
                    }

                    @JavascriptInterface
                    fun activity() = Unit

                    @JavascriptInterface
                    fun unavailable(reason: String?) {
                        if (!videasyMode && !peachifyMode) return

                        // Only explicit rendered player/error states may end a
                        // fallback. Peachify is never stopped by elapsed time.
                        Log.w(TAG, "Source $sourceName indisponible : ${reason.orEmpty()}")
                        finish(null)
                    }
                }

                browser.addJavascriptInterface(
                    bridge,
                    "__AfterDarkMediaBridge",
                )

                if (
                    videasyMode &&
                    WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                ) {
                    runCatching {
                        WebViewCompat.addDocumentStartJavaScript(
                            browser,
                            """
                            (() => {
                              if (window.__afterdarkEarlyVideasyJsonHook) return;
                              window.__afterdarkEarlyVideasyJsonHook = true;

                              const bridge = window.__AfterDarkMediaBridge;
                              if (!bridge || !window.JSON || !JSON.parse) return;

                              if (!window.__afterdarkEarlyFetchHooked && window.fetch) {
                                window.__afterdarkEarlyFetchHooked = true;
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
                                    const responseUrl = response.url || requestedUrl || '';
                                    if (/api\.videasy\.[^/]+\/.*sources-with-title/i.test(responseUrl)) {
                                      response.clone().text().then(payload => {
                                        if (payload) {
                                          const match = responseUrl.match(
                                            /api\.videasy\.[^/]+\/([^/?]+)\//i
                                          );
                                          bridge.videasyEncrypted(
                                            payload,
                                            match ? match[1] : ''
                                          );
                                        }
                                      }).catch(() => {});
                                    }
                                  } catch (_) {}
                                  return response;
                                };
                              }

                              if (
                                !window.__afterdarkEarlyXhrHooked &&
                                window.XMLHttpRequest
                              ) {
                                window.__afterdarkEarlyXhrHooked = true;
                                const originalOpen = XMLHttpRequest.prototype.open;
                                const originalSend = XMLHttpRequest.prototype.send;

                                XMLHttpRequest.prototype.open = function(method, url, ...rest) {
                                  this.__afterdarkVideasyUrl = String(url || '');
                                  return originalOpen.call(this, method, url, ...rest);
                                };

                                XMLHttpRequest.prototype.send = function(...args) {
                                  this.addEventListener('load', () => {
                                    try {
                                      const responseUrl =
                                        this.responseURL || this.__afterdarkVideasyUrl || '';
                                      if (
                                        /api\.videasy\.[^/]+\/.*sources-with-title/i.test(responseUrl) &&
                                        typeof this.responseText === 'string' &&
                                        this.responseText
                                      ) {
                                        const match = responseUrl.match(
                                          /api\.videasy\.[^/]+\/([^/?]+)\//i
                                        );
                                        bridge.videasyEncrypted(
                                          this.responseText,
                                          match ? match[1] : ''
                                        );
                                      }
                                    } catch (_) {}
                                  });
                                  return originalSend.apply(this, args);
                                };
                              }

                              const typeHint = value => {
                                const type = String(value || '').toLowerCase();
                                if (type.includes('hls') || type.includes('m3u8')) {
                                  return 'application/vnd.apple.mpegurl';
                                }
                                if (type.includes('dash') || type.includes('mpd')) {
                                  return 'application/dash+xml';
                                }
                                if (type.includes('mp4') || type.includes('video')) {
                                  return 'video/mp4';
                                }
                                return '';
                              };

                              const looksLikeMedia = value =>
                                /\.m3u8|\.mpd|\.mp4|\.mkv|\.webm/i.test(
                                  String(value || '')
                                );

                              const scan = (value, seen = new WeakSet(), depth = 0) => {
                                if (depth > 10 || value == null) return;
                                if (typeof value === 'string') {
                                  if (looksLikeMedia(value)) {
                                    bridge.media(value, '', location.href, '');
                                  }
                                  return;
                                }
                                if (typeof value !== 'object' || seen.has(value)) return;
                                seen.add(value);

                                try {
                                  const hint = typeHint(
                                    value.type || value.format || value.mimeType
                                  );
                                  const candidate =
                                    value.file ||
                                    value.stream ||
                                    value.playlist ||
                                    value.manifest ||
                                    value.src ||
                                    value.url;
                                  const referer =
                                    value.referer || value.referrer || '';

                                  if (
                                    typeof candidate === 'string' &&
                                    (looksLikeMedia(candidate) || hint)
                                  ) {
                                    bridge.media(
                                      candidate,
                                      hint,
                                      location.href,
                                      String(referer)
                                    );
                                  }

                                  Object.values(value).forEach(child =>
                                    scan(child, seen, depth + 1)
                                  );
                                } catch (_) {}
                              };

                              const originalParse = JSON.parse.bind(JSON);
                              JSON.parse = (...args) => {
                                const result = originalParse(...args);
                                try { scan(result); } catch (_) {}
                                return result;
                              };
                            })();
                            """.trimIndent(),
                            setOf("*"),
                        )
                        Log.i(TAG, "Capture JSON Videasy installée au démarrage du document")
                    }.onFailure { error ->
                        Log.w(TAG, "Capture JSON Videasy document-start indisponible", error)
                    }
                }

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
                            const PEACHIFY_MODE = ${if (peachifyMode) "true" else "false"};

                            const report = (url, contentType = '', mediaReferer = '') => {
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
                              if (type.includes('hls') || type.includes('m3u8')) {
                                return 'application/vnd.apple.mpegurl';
                              }
                              if (type.includes('dash') || type.includes('mpd')) {
                                return 'application/dash+xml';
                              }
                              if (type.includes('mp4') || type.includes('video')) {
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
                                if (isInterestingUrl(value)) report(value, '');
                                return;
                              }

                              if (typeof value !== 'object') return;
                              if (seen.has(value)) return;
                              seen.add(value);

                              try {
                                const hint = mediaTypeHint(
                                  value.type || value.format || value.mimeType
                                );
                                const candidate =
                                  value.file ||
                                  value.stream ||
                                  value.playlist ||
                                  value.manifest ||
                                  value.src ||
                                  value.url;
                                const candidateReferer =
                                  value.referer || value.referrer || '';

                                if (
                                  typeof candidate === 'string' &&
                                  (isInterestingUrl(candidate) || hint)
                                ) {
                                  report(candidate, hint, candidateReferer);
                                }

                                Object.values(value).forEach(child =>
                                  scanStructuredMedia(child, seen, depth + 1)
                                );
                              } catch (_) {}
                            };

                            if (!window.__afterdarkJsonParseHooked && window.JSON) {
                              window.__afterdarkJsonParseHooked = true;
                              const originalJsonParse = JSON.parse.bind(JSON);
                              JSON.parse = (...args) => {
                                const result = originalJsonParse(...args);
                                try { scanStructuredMedia(result); } catch (_) {}
                                return result;
                              };
                            }

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

                                  const clone = response.clone();
                                  clone.text().then(text => {
                                    try {
                                      scanStructuredMedia(JSON.parse(text));
                                    } catch (_) {}
                                  }).catch(() => {});
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

                            const collectPeachifyRoots = () => {
                              const roots = [];
                              const visited = new Set();

                              const visit = root => {
                                if (!root || visited.has(root)) return;
                                visited.add(root);
                                roots.push(root);

                                try {
                                  root.querySelectorAll('*').forEach(element => {
                                    if (element.shadowRoot) visit(element.shadowRoot);
                                  });
                                } catch (_) {}

                                try {
                                  root.querySelectorAll('iframe').forEach(frame => {
                                    try { visit(frame.contentDocument); } catch (_) {}
                                  });
                                } catch (_) {}
                              };

                              visit(document);
                              return roots;
                            };

                            const hasPeachifyNotFoundPanel = () => {
                              if (!PEACHIFY_MODE) return false;

                              return collectPeachifyRoots().some(root => {
                                let closeButton = null;
                                try {
                                  closeButton = [...root.querySelectorAll('button')].find(
                                    element => normalizedText(
                                      element.getAttribute('aria-label')
                                    ) === 'close error message'
                                  );
                                } catch (_) {
                                  return false;
                                }
                                if (!closeButton) return false;

                                let panel = closeButton.parentElement;
                                let depth = 0;

                                while (panel && depth < 12) {
                                  const exactTitle = [...panel.querySelectorAll(
                                    'div,h1,h2,h3,p,span'
                                  )].some(element =>
                                    normalizedText(element.textContent) === '404 - not found'
                                  );
                                  const reloadButton = [...panel.querySelectorAll(
                                    'button,[role="button"]'
                                  )].some(element =>
                                    normalizedText(element.textContent) === 'reload'
                                  );

                                  if (exactTitle && reloadButton) return true;
                                  panel = panel.parentElement;
                                  depth += 1;
                                }

                                return false;
                              });
                            };

                            const checkPeachifyDomState = () => {
                              if (hasPeachifyNotFoundPanel()) {
                                bridge.unavailable('peachify-404-not-found');
                                return true;
                              }
                              return false;
                            };

                            if (
                              PEACHIFY_MODE &&
                              !window.__afterdarkPeachifyNotFoundTimer
                            ) {
                              window.__afterdarkPeachifyNotFoundTimer = setInterval(
                                checkPeachifyDomState,
                                250
                              );
                            }

                            const hasGoBackError = () =>
                              [...document.querySelectorAll('a[href="/"]')].some(anchor =>
                                isVisible(anchor) && normalizedText(anchor.textContent) === 'go back'
                              );

                            const isVideasyPage = () => {
                              if (!VIDEASY_MODE) return false;
                              const host = String(location.hostname || '').toLowerCase();
                              return host === 'player.videasy.net' ||
                                     host.endsWith('.videasy.net') ||
                                     host === 'player.videasy.to' ||
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

                              // Do not infer failure from the absence of the old
                              // player markup. Videasy is a client-side app and
                              // may render its shell before mounting the player.
                              // Only an explicit error or an HTTP/navigation
                              // failure is terminal.
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
                            checkPeachifyDomState();

                            if (!window.__afterdarkMediaObserver) {
                              window.__afterdarkMediaObserver = new MutationObserver(() => {
                                scanMedia();
                                tryVideasyAutoPlay();
                                checkVideasyDomState();
                                checkPeachifyDomState();
                              });
                              window.__afterdarkMediaObserver.observe(document.documentElement, {
                                childList: true,
                                subtree: true,
                                attributes: true,
                                attributeFilter: ['src', 'class', 'style', 'aria-label']
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

                        val videasyHost = uri.host.equals("player.videasy.net", ignoreCase = true) ||
                            uri.host.equals("player.videasy.to", ignoreCase = true)

                        if (
                            videasyMode &&
                            request.isForMainFrame &&
                            videasyHost &&
                            (uri.path.isNullOrBlank() || uri.path == "/")
                        ) {
                            Log.w(TAG, "Videasy a renvoyé vers sa racine : $uri")
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
                            Log.w(TAG, "Erreur HTTP Videasy $statusCode sur ${request.url}")
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
                            Log.w(
                                TAG,
                                "Erreur WebView Videasy ${error?.errorCode}: ${error?.description}",
                            )
                            finish(null)
                        }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): android.webkit.WebResourceResponse? {
                        if (videasyMode && request != null) {
                            val requestUrl = request.url.toString()
                            val lower = requestUrl.lowercase()
                            val diagnostic =
                                "api.videasy" in lower ||
                                    "speedracelight" in lower ||
                                    "source" in lower ||
                                    "stream" in lower ||
                                    "playlist" in lower ||
                                    "manifest" in lower

                            if (diagnostic && loggedVideasyRequests.getAndIncrement() < 40) {
                                Log.i(
                                    TAG,
                                    "Requête Videasy ${request.method}: ${requestUrl.take(700)}",
                                )
                            }
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
                        Log.i(TAG, "Page $sourceName chargée : ${url.orEmpty()}")
                        installHooksAndNudge()
                    }

                    override fun onPageStarted(
                        view: WebView?,
                        url: String?,
                        favicon: android.graphics.Bitmap?,
                    ) {
                        super.onPageStarted(view, url, favicon)

                        // Install the network/JSON hooks before the client-side
                        // application finishes booting. onPageFinished alone is
                        // too late for Videasy's initial source request.
                        handler.post { installHooksAndNudge() }
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

                browser.loadUrl(embedUrl, initialHeaders)
            } catch (_: Exception) {
                finish(null)
            }
        }
    }

}
