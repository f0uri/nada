package com.nada.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class ShareCatcherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        catchShare(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        catchShare(intent)
    }

    private fun catchShare(intent: Intent?) {
        val url = IgLinks.fromIntent(intent)
        val stream = IgLinks.streamFrom(intent)
        when {
            url.isNotBlank() || stream != null -> {
                if (url.isNotBlank()) Prefs.setLastIgUrl(this, url)
                val started = runCatching { QuickSaveService.start(this, url, stream) }.isSuccess
                if (!started) {
                    runCatching { QuickSaveService.start(this, url, stream) }
                }
            }
            else -> QuickSaveService.notifyNeedLink(this)
        }
        finish()
        overridePendingTransition(0, 0)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
