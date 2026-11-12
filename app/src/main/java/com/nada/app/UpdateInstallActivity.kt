package com.nada.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle

/**
 * Receives the PackageInstaller session result without adding an install screen.
 *
 * Every branch reports back to [AppUpdateManager] so the progress dialog is
 * always closed and a failure always offers a retry.
 */
class UpdateInstallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val manager = AppUpdateManager.get(this)
        val code = intent?.getLongExtra(EXTRA_CODE, 0L) ?: 0L
        val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            ?: PackageInstaller.STATUS_FAILURE
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants one confirmation. Show it exactly once and keep
                // the session alive; the result comes back as a new intent.
                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    intent?.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirmation != null) {
                    val started = runCatching {
                        startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.isSuccess
                    if (!started) {
                        manager.onInstallResult(code, false, getString(R.string.update_failed))
                    }
                } else {
                    manager.onInstallResult(code, false, getString(R.string.update_failed))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                manager.onInstallResult(code, true, null)
                // The package manager replaced this app in place; data is intact.
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                if (launch != null) {
                    runCatching {
                        startActivity(
                            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        )
                    }
                }
            }
            else -> {
                val detail = intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                manager.onInstallResult(code, false, detail ?: getString(R.string.update_failed))
            }
        }
        finish()
    }

    companion object {
        const val ACTION = "com.nada.app.UPDATE_INSTALL_RESULT"
        const val EXTRA_CODE = "code"
    }
}
