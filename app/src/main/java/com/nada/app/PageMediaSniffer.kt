package com.nada.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PageMediaSniffer(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    data class Hit(val url: String, val mime: String)

    fun sniff(pages: List<String>, waitMs: Long = 7_000): Hit? {
        val targets = pages.map { it.trim() }.filter { it.startsWith("http") }.distinct()
        if (targets.isEmpty()) return null
        val hit = AtomicReference<Hit?>(null)
        val video = AtomicReference<Hit?>(null)
        val latch = CountDownLatch(1)
        val holder = AtomicReference<WebView?>(null)
        main.post {
            runCatching {
                val web = WebView(app)
                holder.set(web)
                web.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = UA
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val found = classify(request.url.toString()) ?: return super.shouldInterceptRequest(view, request)
                        if (found.mime.startsWith("video")) {
                            video.set(found)
                            hit.set(found)
                            latch.countDown()
                        } else if (hit.get() == null) {
                            hit.set(found)
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                web.loadUrl(targets.first())
                if (targets.size > 1) {
                    main.postDelayed({
                        if (video.get() == null && holder.get() === web) {
                            runCatching { web.loadUrl(targets[1]) }
                        }
                    }, 3_200)
                }
            }.onFailure { latch.countDown() }
        }
        latch.await(waitMs, TimeUnit.MILLISECONDS)
        main.post {
            val web = holder.getAndSet(null)
            runCatching { web?.stopLoading() }
            runCatching { web?.destroy() }
            runCatching {
                val manager = CookieManager.getInstance()
                manager.removeAllCookies(null)
                manager.flush()
            }
        }
        return video.get() ?: hit.get()
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        fun classify(raw: String): Hit? {
            val url = raw.substringBefore('#').trim()
            if (url.length < 20 || !url.startsWith("http")) return null
            val host = runCatching { android.net.Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
            if (!(host.contains("cdninstagram") || host.contains("fbcdn") || host.endsWith("instagram.com"))) {
                return null
            }
            if (host.contains("instagram.com") && !host.contains("cdn")) return null
            val low = url.lowercase()
            if (low.contains("s150x150") || low.contains("s50x50") || low.contains("/rsrc.php")) return null
            return when {
                low.contains(".mp4") || low.contains("video") && !low.contains("svg") -> Hit(url, "video/mp4")
                low.contains(".jpg") || low.contains(".jpeg") || low.contains("scontent") -> Hit(url, "image/jpeg")
                low.contains(".webp") -> Hit(url, "image/webp")
                else -> null
            }
        }
    }
}
