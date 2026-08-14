package com.nada.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle

class QuickSaveActivity : Activity() {
    private var handed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) handOff()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handed = false
        handOff()
    }

    override fun onResume() {
        super.onResume()
        window.decorView.postDelayed({
            if (!isFinishing && !handed) handOff()
        }, 350)
    }

    private fun handOff() {
        if (handed || isFinishing) return
        handed = true
        val url = IgLinks.fromText(clipText()).ifBlank { Prefs.lastIgUrl(this) }
        if (url.isBlank()) {
            QuickSaveService.notifyNeedLink(this)
        } else {
            Prefs.setLastIgUrl(this, url)
            QuickSaveService.start(this, url)
        }
        finish()
        overridePendingTransition(0, 0)
    }

    private fun clipText(): String {
        val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        return runCatching {
            clip.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        }.getOrDefault("")
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        fun intent(from: android.content.Context): Intent {
            return Intent(from, QuickSaveActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_HISTORY,
            )
        }
    }
}
