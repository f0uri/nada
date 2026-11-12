package com.nada.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/** Receives the PackageInstaller session result without adding an install screen. */
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
        val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            ?: PackageInstaller.STATUS_FAILURE
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    intent?.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirmation != null) {
                    runCatching {
                        startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure {
                        Toast.makeText(this, R.string.update_failed, Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this, R.string.update_failed, Toast.LENGTH_LONG).show()
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // The package manager has replaced this app in place; data is intact.
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                if (launch != null) {
                    startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
            }
            else -> {
                Toast.makeText(this, R.string.update_failed, Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }

    companion object {
        const val ACTION = "com.nada.app.UPDATE_INSTALL_RESULT"
    }
}
