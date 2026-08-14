package com.nada.app

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.ArrayDeque

class QuickSaveService : Service() {
    private val instagram = InstagramClient()
    private val jobs = ArrayDeque<String>()
    private val lock = Any()
    private var working = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = tidyIgUrl(intent?.getStringExtra(EXTRA_URL).orEmpty())
        synchronized(lock) {
            if (url.isNotBlank()) jobs.addLast(url)
            if (working) return START_NOT_STICKY
            if (jobs.isEmpty()) {
                notifyNeedLink(this)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            working = true
            goForeground(getString(R.string.save_working))
            Thread { drain() }.start()
        }
        return START_NOT_STICKY
    }

    private fun drain() {
        while (true) {
            val url = synchronized(lock) { if (jobs.isEmpty()) null else jobs.removeFirst() } ?: break
            runSave(url)
        }
        synchronized(lock) {
            if (jobs.isNotEmpty()) {
                Thread { drain() }.start()
                return
            }
            working = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun runSave(url: String) {
        goForeground(getString(R.string.save_working))
        try {
            val batch = instagram.prepareSave(url)
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            for (file in batch.files) {
                enqueue(manager, file)
            }
            val who = "@${batch.username}"
            val detail = getString(R.string.save_files, batch.files.size)
            notifyDone("$who · $detail", error = false)
        } catch (ex: InstagramClient.ApiException) {
            notifyDone(mapError(ex), error = true)
        } catch (_: Exception) {
            notifyDone(getString(R.string.save_fail_net), error = true)
        }
    }

    private fun enqueue(manager: DownloadManager, file: InstagramClient.SaveFile) {
        val request = DownloadManager.Request(Uri.parse(file.url))
            .setMimeType(file.mime)
            .setTitle(file.name)
            .setDescription(getString(R.string.app_name))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .addRequestHeader("User-Agent", UA)
            .addRequestHeader("Referer", "https://www.instagram.com/")
            .addRequestHeader("Accept", "*/*")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        val canPublic = Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (canPublic) {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file.name)
        } else {
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, file.name)
        }
        manager.enqueue(request)
    }

    private fun mapError(ex: InstagramClient.ApiException): String {
        return when (ex.code) {
            "url" -> getString(R.string.save_fail_url)
            "rate" -> getString(R.string.save_fail_rate)
            "missing", "empty", "blocked", "login" -> getString(R.string.save_fail_missing)
            else -> getString(R.string.save_fail_net)
        }
    }

    private fun goForeground(text: String) {
        val notification = buildNotification(getString(R.string.save_working), text, ongoing = true, error = false)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(WORK_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(WORK_ID, notification)
        }
    }

    private fun notifyDone(text: String, error: Boolean) {
        val title = if (error) getString(R.string.save_fail) else getString(R.string.save_started)
        manager().notify(DONE_ID, buildNotification(title, text, ongoing = false, error = error))
    }

    private fun buildNotification(title: String, text: String, ongoing: Boolean, error: Boolean): Notification {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this,
            4,
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(if (error) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun manager(): NotificationManager {
        return getSystemService(NotificationManager::class.java)
    }

    companion object {
        const val EXTRA_URL = "url"
        private const val CHANNEL_ID = "nada_save"
        private const val WORK_ID = 27
        private const val DONE_ID = 28
        private const val UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1"
        private val IG_URL = Regex(
            "https?://(?:www\\.)?(?:instagram\\.com|instagr\\.am)/\\S+",
            RegexOption.IGNORE_CASE,
        )

        fun start(context: Context, url: String) {
            val intent = Intent(context, QuickSaveService::class.java).putExtra(EXTRA_URL, url)
            ContextCompat.startForegroundService(context, intent)
        }

        fun tidyIgUrl(raw: String): String {
            val match = IG_URL.find(raw.trim()) ?: return ""
            val found = match.value.trimEnd(')', ']', '"', '\'', '>', ',', '،', '.', '…')
            return try {
                val uri = Uri.parse(found)
                val path = uri.path.orEmpty().replace(Regex("/{2,}"), "/").trimEnd('/')
                if (path.isBlank() || path == "/") "" else "https://www.instagram.com$path/"
            } catch (_: Exception) {
                found.substringBefore('?').substringBefore('#')
            }
        }

        fun notifyNeedLink(context: Context) {
            ensureChannel(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.save_need_link))
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(DONE_ID, notification)
        }

        private fun ensureChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.save_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.save_channel)
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
