package com.nada.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var server: NadaServer
    private val instagram = InstagramClient()
    private var pageReady = false
    private var pendingUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        web = findViewById(R.id.web)

        server = NadaServer(this, instagram)
        server.start()

        CookieManager.getInstance().setAcceptCookie(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                deliverPending()
                offerClipboard()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url ?: return true
                val host = target.host.orEmpty()
                if (host == "127.0.0.1" || host == "localhost") return false
                val href = target.toString()
                if (INSTAGRAM_URL.containsMatchIn(href)) {
                    queueUrl(href)
                }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean = false
        }
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return@setDownloadListener
            val host = parsed.host.orEmpty()
            if (host != "127.0.0.1" && host != "localhost") return@setDownloadListener
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { "nada.bin" }
            val request = DownloadManager.Request(parsed)
                .setMimeType(mimeType)
                .addRequestHeader("User-Agent", userAgent)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                .setTitle(name)
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
        }
        web.loadUrl(NadaServer.BASE)
        queueUrl(extractInstagramUrl(intent))

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (web.canGoBack()) {
                        web.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    override fun onResume() {
        super.onResume()
        offerClipboard()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        queueUrl(extractInstagramUrl(intent))
    }

    private fun offerClipboard() {
        if (!pageReady || !::web.isInitialized) return
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = runCatching { clip.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
        val url = INSTAGRAM_URL.find(text.orEmpty())?.value ?: return
        web.evaluateJavascript("window.nadaOfferClip && window.nadaOfferClip(${JSONObject.quote(url)})", null)
    }

    private fun queueUrl(url: String?) {
        val clean = url?.trim().orEmpty()
        if (clean.isBlank()) return
        pendingUrl = clean
        deliverPending()
    }

    private fun deliverPending() {
        val url = pendingUrl ?: return
        if (!pageReady || !::web.isInitialized) return
        val encoded = JSONObject.quote(url)
        web.evaluateJavascript("window.nadaOpen && window.nadaOpen($encoded)", null)
        pendingUrl = null
    }

    override fun onDestroy() {
        if (::server.isInitialized) {
            server.stop()
        }
        super.onDestroy()
    }

    companion object {
        private val INSTAGRAM_URL = Regex(
            "https?://(?:www\\.)?(?:instagram\\.com|instagr\\.am)/\\S+",
            RegexOption.IGNORE_CASE,
        )

        fun extractInstagramUrl(intent: Intent?): String? {
            if (intent == null) return null
            val blob = listOfNotNull(
                intent.getStringExtra(Intent.EXTRA_TEXT),
                intent.getStringExtra(Intent.EXTRA_SUBJECT),
                intent.dataString,
            ).joinToString(" ")
            return INSTAGRAM_URL.find(blob)?.value?.trimEnd(')', ']', '"', '\'', '>', ',', '،')
        }
    }
}
