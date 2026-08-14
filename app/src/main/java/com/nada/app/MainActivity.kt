package com.nada.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Build
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var authOverlay: View
    private lateinit var authWeb: WebView
    private lateinit var server: NadaServer
    private val instagram = InstagramClient()
    private var pageReady = false
    private var pendingUrl: String? = null
    private var pendingStoryUserId: String = ""
    private var storyFetchBusy = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        web = findViewById(R.id.web)
        authOverlay = findViewById(R.id.authOverlay)
        authWeb = findViewById(R.id.authWeb)

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
        web.addJavascriptInterface(StoryBridge(), "NadaBridge")
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
        setupAuthWeb()
        findViewById<Button>(R.id.authClose).setOnClickListener { closeStoryLogin() }
        web.loadUrl(NadaServer.BASE)
        queueUrl(extractInstagramUrl(intent))
        restoreBubble()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (authOverlay.visibility == View.VISIBLE) {
                        closeStoryLogin()
                        return
                    }
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupAuthWeb() {
        authWeb.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportMultipleWindows(false)
        }
        authWeb.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (authOverlay.visibility != View.VISIBLE || storyFetchBusy) return
                val host = runCatching { Uri.parse(url.orEmpty()).host.orEmpty() }.getOrDefault("")
                if (!host.endsWith("instagram.com")) return
                val raw = CookieManager.getInstance().getCookie("https://www.instagram.com").orEmpty()
                if (!raw.contains("sessionid=")) return
                val session = keepSessionCookies(raw)
                if (session.isBlank() || pendingStoryUserId.isBlank()) return
                storyFetchBusy = true
                Thread {
                    val stories = try {
                        instagram.fetchStoriesOnce(pendingStoryUserId, session)
                    } catch (_: Exception) {
                        JSONArray()
                    }
                    runOnUiThread {
                        deliverStories(stories)
                        closeStoryLogin()
                    }
                }.start()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val host = request?.url?.host.orEmpty()
                return !(host == "instagram.com" || host.endsWith(".instagram.com") || host.endsWith("facebook.com") || host.endsWith(".facebook.com"))
            }
        }
    }

    inner class StoryBridge {
        @JavascriptInterface
        fun requestStories(userId: String, username: String) {
            val id = userId.filter { it.isDigit() }.take(20)
            if (id.isBlank()) return
            runOnUiThread { openStoryLogin(id) }
        }

        @JavascriptInterface
        fun setBubble(on: Boolean) {
            runOnUiThread { applyBubble(on) }
        }

        @JavascriptInterface
        fun isBubbleOn(): Boolean {
            return Prefs.bubbleOn(this@MainActivity) && Settings.canDrawOverlays(this@MainActivity)
        }
    }

    private fun applyBubble(on: Boolean) {
        Prefs.setBubble(this, on)
        if (!on) {
            BubbleService.stop(this)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 26)
        }
        if (Build.VERSION.SDK_INT < 29) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 27)
        }
        BubbleService.start(this)
    }

    private fun restoreBubble() {
        if (Prefs.bubbleOn(this) && Settings.canDrawOverlays(this)) {
            BubbleService.start(this)
        }
    }

    private fun openStoryLogin(userId: String) {
        pendingStoryUserId = userId
        storyFetchBusy = false
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(authWeb, false)
        wipeWebCookies()
        authOverlay.visibility = View.VISIBLE
        authWeb.loadUrl("https://www.instagram.com/accounts/login/")
    }

    private fun closeStoryLogin() {
        pendingStoryUserId = ""
        storyFetchBusy = false
        wipeWebCookies()
        authWeb.stopLoading()
        authWeb.loadUrl("about:blank")
        authWeb.clearHistory()
        authWeb.clearCache(true)
        authWeb.clearFormData()
        CookieManager.getInstance().setAcceptCookie(false)
        authOverlay.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun wipeWebCookies() {
        val manager = CookieManager.getInstance()
        manager.removeAllCookies(null)
        manager.flush()
    }

    private fun keepSessionCookies(raw: String): String {
        return raw.split(';')
            .map { it.trim() }
            .filter { part ->
                val name = part.substringBefore('=').lowercase()
                name == "sessionid" || name == "ds_user_id" || name == "csrftoken"
            }
            .joinToString("; ")
    }

    private fun deliverStories(stories: JSONArray) {
        if (!::web.isInitialized || !pageReady) return
        val payload = JSONObject.quote(stories.toString())
        web.evaluateJavascript("window.nadaStoriesReady && window.nadaStoriesReady($payload)", null)
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
        wipeWebCookies()
        super.onDestroy()
    }

    companion object {
        private val INSTAGRAM_URL = Regex(
            "https?://(?:www\\\\.)?(?:instagram\\\\.com|instagr\\\\.am)/\\\\S+",
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
