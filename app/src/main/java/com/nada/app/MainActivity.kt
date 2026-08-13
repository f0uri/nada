package com.nada.app

import android.annotation.SuppressLint
import android.app.DownloadManager
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

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                deliverPending()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString().orEmpty()
                return if (INSTAGRAM_URL.containsMatchIn(target)) {
                    queueUrl(target)
                    true
                } else {
                    false
                }
            }
        }
        web.webChromeClient = WebChromeClient()
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url))
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        queueUrl(extractInstagramUrl(intent))
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
