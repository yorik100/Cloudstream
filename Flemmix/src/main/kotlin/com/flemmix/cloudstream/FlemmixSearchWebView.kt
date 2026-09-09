package com.flemmix.cloudstream

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object FlemmixSearchWebView {
    private const val TIMEOUT_MS = 20_000L

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun load(origin: String, query: String): String? =
        suspendCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val finished = AtomicBoolean(false)
            var webView: WebView? = null

            lateinit var timeout: Runnable
            fun finish(html: String?) {
                if (!finished.compareAndSet(false, true)) return
                handler.removeCallbacks(timeout)
                handler.post {
                    runCatching {
                        val view = webView
                        (view?.parent as? ViewGroup)?.removeView(view)
                        view?.stopLoading()
                        view?.destroy()
                    }
                    webView = null
                }
                continuation.resume(html)
            }

            timeout = Runnable { finish(null) }
            handler.post {
                val activity = FlemmixRuntime.currentActivity()
                if (activity == null || activity.isFinishing) {
                    finish(null)
                    return@post
                }

                try {
                    CookieManager.getInstance().setAcceptCookie(true)
                    val view = WebView(activity).apply {
                        setBackgroundColor(Color.TRANSPARENT)
                        alpha = 0.01f
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/149.0 Mobile Safari/537.36"
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                if (finished.get() || url == "about:blank") return
                                handler.postDelayed({
                                    if (finished.get()) return@postDelayed
                                    view.evaluateJavascript(
                                        "(function(){return document.documentElement.outerHTML;})()",
                                    ) { encoded ->
                                        val html = runCatching {
                                            JSONTokener(encoded).nextValue() as? String
                                        }.getOrNull()
                                        finish(html)
                                    }
                                }, 750L)
                            }
                        }
                    }
                    webView = view
                    activity.addContentView(
                        view,
                        ViewGroup.LayoutParams(1, 1),
                    )
                    val url = Uri.parse("$origin/index.php").buildUpon()
                        .appendQueryParameter("do", "search")
                        .appendQueryParameter("subaction", "search")
                        .appendQueryParameter("story", query)
                        .build()
                        .toString()
                    view.loadUrl(url, mapOf("Referer" to "$origin/"))
                    handler.postDelayed(timeout, TIMEOUT_MS)
                } catch (_: Throwable) {
                    finish(null)
                }
            }
        }
}
