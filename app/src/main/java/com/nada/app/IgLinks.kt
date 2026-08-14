package com.nada.app

import android.content.Intent
import android.net.Uri
import android.os.Build

object IgLinks {
    private val ANY = Regex(
        """https?://(?:www\.)?(?:l\.)?(?:instagram\.com|instagr\.am)/[^\s<>"'\]\)]+""",
        RegexOption.IGNORE_CASE,
    )
    private val L_IG = Regex(
        """https?://(?:www\.)?l\.instagram\.com/[^\s<>"']+""",
        RegexOption.IGNORE_CASE,
    )

    fun fromText(raw: String): String {
        val text = raw.trim()
        if (text.isBlank()) return ""
        L_IG.find(text)?.value?.let { link ->
            val nested = runCatching {
                Uri.parse(link).getQueryParameter("u").orEmpty()
            }.getOrDefault("")
            if (nested.isNotBlank()) {
                val decoded = runCatching { Uri.decode(nested) }.getOrDefault(nested)
                val inner = fromText(decoded)
                if (inner.isNotBlank()) return inner
            }
        }
        val match = ANY.find(text) ?: return ""
        return tidy(match.value)
    }

    fun tidy(raw: String): String {
        val found = raw.trim().trimEnd(')', ']', '"', '\'', '>', ',', '،', '.', '…')
        if (found.isBlank()) return ""
        return try {
            val uri = Uri.parse(found)
            val host = uri.host.orEmpty().lowercase()
            if (host.contains("l.instagram.com")) {
                val nested = uri.getQueryParameter("u").orEmpty()
                if (nested.isNotBlank()) return tidy(Uri.decode(nested))
            }
            if (!host.contains("instagram.com") && !host.contains("instagr.am")) return ""
            val path = uri.path.orEmpty().replace(Regex("/{2,}"), "/").trimEnd('/')
            if (path.isBlank() || path == "/") "" else "https://www.instagram.com$path/"
        } catch (_: Exception) {
            found.substringBefore('?').substringBefore('#')
        }
    }

    fun fromIntent(intent: Intent?): String {
        if (intent == null) return ""
        val parts = mutableListOf<String>()
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { parts += it }
        intent.getStringExtra(Intent.EXTRA_SUBJECT)?.let { parts += it }
        intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let { parts += it }
        intent.dataString?.let { parts += it }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).text?.toString()?.let { parts += it }
                clip.getItemAt(i).uri?.toString()?.let { parts += it }
            }
        }
        for (part in parts) {
            val url = fromText(part)
            if (url.isNotBlank()) return url
        }
        return ""
    }

    fun streamFrom(intent: Intent?): Uri? {
        if (intent == null) return null
        val extra = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        extra?.let { return it }
        val clip = intent.clipData ?: return null
        for (i in 0 until clip.itemCount) {
            clip.getItemAt(i).uri?.let { return it }
        }
        return null
    }

    fun needsExpand(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty().lowercase()
        return host.contains("l.instagram.com") ||
            path.contains("/share") ||
            path.startsWith("/s/") ||
            path.contains("/share/")
    }
}
