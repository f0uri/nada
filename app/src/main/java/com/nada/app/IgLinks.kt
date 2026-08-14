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
    private val CODE_RE = Regex("^[A-Za-z0-9_-]{5,32}$")
    private val SKIP_SEGMENTS = setOf("p", "tv", "reel", "reels", "videos", "share", "stories", "embed")

    fun fromText(raw: String): String {
        val text = stripMarks(raw)
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
        val found = stripMarks(raw).trimEnd(')', ']', '"', '\'', '>', ',', '،', '.', '…')
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

    fun shortcodeFrom(url: String): String? {
        val path = runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault("")
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.any { it.equals("share", ignoreCase = true) || it.equals("stories", ignoreCase = true) }) {
            return null
        }
        val typeAt = parts.indexOfFirst { it.lowercase() in setOf("p", "tv", "reel", "reels") }
        if (typeAt < 0) return null
        val code = parts.drop(typeAt + 1).firstOrNull { it.lowercase() !in SKIP_SEGMENTS } ?: return null
        return if (CODE_RE.matches(code)) code else null
    }

    private fun stripMarks(raw: String): String {
        return raw.trim()
            .replace("\u200e", "")
            .replace("\u200f", "")
            .replace("\u202a", "")
            .replace("\u202b", "")
            .replace("\u202c", "")
            .replace("\u2066", "")
            .replace("\u2067", "")
            .replace("\u2068", "")
            .replace("\u2069", "")
    }
}
