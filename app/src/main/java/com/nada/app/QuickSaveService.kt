package com.nada.app

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.ArrayDeque

class QuickSaveService : Service() {
    private val instagram = InstagramClient()
    private val jobs = ArrayDeque<SaveJob>()
    private val lock = Any()
    private var working = false
    private var diag = DownloadDiagnostics()

    private class SaveJob(val url: String, val stream: String)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = tidyIgUrl(intent?.getStringExtra(EXTRA_URL).orEmpty())
        val stream = intent?.getStringExtra(EXTRA_STREAM).orEmpty()
        synchronized(lock) {
            if (url.isNotBlank() || stream.isNotBlank()) {
                jobs.addLast(SaveJob(url, stream))
            }
            if (working) return START_NOT_STICKY
            if (jobs.isEmpty()) {
                notifyNeedLink(this)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            working = true
            goForeground(getString(R.string.save_stage_link), busy = true)
            Thread { drain() }.start()
        }
        return START_NOT_STICKY
    }

    private fun drain() {
        try {
            while (true) {
                val next = synchronized(lock) {
                    if (jobs.isNotEmpty()) jobs.removeFirst() else null
                } ?: break
                diag = DownloadDiagnostics().also {
                    it.url = next.url.ifBlank { next.stream }
                    it.begin(DownloadDiagnostics.STEP_START)
                }
                try {
                    runSave(next)
                    if (diag.outcome.isBlank()) diag.finish("ok")
                } catch (ex: Exception) {
                    val detail = (ex.message ?: ex.javaClass.simpleName).take(60)
                    diag.note("unexpected ${diag.stage}: $detail")
                    diag.finish("error:${diag.stage}")
                    notifyDone(unexpectedAt(diag.stage, ex), error = true)
                } finally {
                    persist(diag)
                }
            }
        } finally {
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
    }

    private fun runSave(job: SaveJob) {
        goForeground(getString(R.string.save_stage_link), busy = true)
        if (job.stream.isNotBlank() && shouldSaveStream(job)) {
            goForeground(getString(R.string.save_stage_file), busy = true)
            val ok = runCatching { saveStreamQuiet(job.stream) }.isSuccess
            if (!ok) diag.note("stream save failed; falling back to link")
            if (ok) return
        }
        if (job.url.isBlank()) {
            failWith(getString(R.string.save_fail_url))
            return
        }
        goForeground(getString(R.string.save_stage_video), busy = true)
        // Listnr is the only resolve source. A failure uses the existing error
        // notification; there is no resolver retry chain or page-sniffing fallback.
        diag.begin(DownloadDiagnostics.STEP_LISTNR)
        val result = runCatching { instagram.prepareSave(job.url, diag) }
        result.fold(
            onSuccess = { batch ->
                try {
                    goForeground(getString(R.string.save_stage_file), busy = true)
                    for (file in batch.files) writeRemote(file)
                    val who = "@${batch.username}"
                    val detail = getString(R.string.save_files, batch.files.size)
                    diag.finish("ok")
                    notifyDone("$who · $detail", error = false)
                } catch (ex: InstagramClient.ApiException) {
                    failWith(mapError(ex))
                } catch (ex: Exception) {
                    failWith(unexpectedAt(diag.stage, ex))
                }
            },
            onFailure = { err ->
                if (job.stream.isNotBlank()) {
                    goForeground(getString(R.string.save_stage_file), busy = true)
                    val ok = runCatching { saveStreamQuiet(job.stream) }.isSuccess
                    if (ok) return
                }
                if (err is InstagramClient.ApiException) failWith(mapError(err))
                else failWith(unexpectedAt(diag.stage, err))
            },
        )
    }

    private fun shouldSaveStream(job: SaveJob): Boolean {
        val uri = runCatching { Uri.parse(job.stream) }.getOrNull() ?: return false
        val mime = contentResolver.getType(uri).orEmpty().lowercase()
        val streamIsVideo = mime.contains("video") || mime.contains("mp4")
        val looksPost = IgLinks.looksLikePost(job.url)
        return when {
            streamIsVideo -> true
            job.url.isBlank() -> true
            looksPost -> false
            else -> mime.startsWith("image/")
        }
    }

    private fun saveStreamQuiet(raw: String) {
        diag.begin(DownloadDiagnostics.STEP_STORE)
        val uri = Uri.parse(raw)
        val resolver = contentResolver
        val mime = resolver.getType(uri) ?: "video/mp4"
        val ext = when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
            mime.contains("mp4") || mime.contains("video") -> "mp4"
            mime.contains("image") -> "jpg"
            else -> "bin"
        }
        val name = "instagram_${System.currentTimeMillis()}.$ext"
        resolver.openInputStream(uri)?.use { input ->
            storeBytes(input, name, mime)
        } ?: error("in")
        notifyDone(name, error = false)
    }

    private fun writeRemote(file: InstagramClient.SaveFile) {
        diag.begin(DownloadDiagnostics.STEP_CDN)
        val stream = instagram.pullFile(file.url, diag)
        try {
            val body = stream.body ?: error("empty")
            diag.begin(DownloadDiagnostics.STEP_STORE)
            storeBytes(body.byteStream(), file.name, file.mime)
        } finally {
            stream.close()
        }
    }

    private fun saveStream(raw: String) {
        goForeground(getString(R.string.save_working))
        try {
            val uri = Uri.parse(raw)
            val resolver = contentResolver
            val mime = resolver.getType(uri) ?: "video/mp4"
            val ext = when {
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                mime.contains("mp4") || mime.contains("video") -> "mp4"
                mime.contains("image") -> "jpg"
                else -> "bin"
            }
            val name = "instagram_${System.currentTimeMillis()}.$ext"
            resolver.openInputStream(uri)?.use { input ->
                storeBytes(input, name, mime)
            } ?: error("in")
            notifyDone(name, error = false)
        } catch (ex: Exception) {
            failWith(unexpectedAt(DownloadDiagnostics.STEP_STORE, ex))
        }
    }

    private fun storeBytes(input: java.io.InputStream, name: String, mime: String) {
        val limited = LimitedInput(input, 120L * 1024 * 1024)
        try {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val out = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("store")
            try {
                contentResolver.openOutputStream(out)?.use { limited.copyTo(it) } ?: error("out")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(out, values, null, null)
            } catch (err: Exception) {
                runCatching { contentResolver.delete(out, null, null) }
                throw err
            }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            File(dir, name).outputStream().use { limited.copyTo(it) }
        }
        } catch (err: Exception) {
            if (diag.storeDetail.isBlank()) {
                diag.storeDetail = (err.message ?: err.javaClass.simpleName).take(48)
            }
            throw err
        } finally {
            diag.bytes = limited.copied
        }
    }

    private class LimitedInput(private val inner: java.io.InputStream, private val max: Long) : java.io.InputStream() {
        private var seen = 0L
        val copied: Long get() = seen
        override fun read(): Int {
            val value = inner.read()
            if (value >= 0) account(1)
            return value
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n > 0) account(n.toLong())
            return n
        }
        private fun account(n: Long) {
            seen += n
            if (seen > max) error("size")
        }
        override fun close() = inner.close()
    }

    private fun mapError(ex: InstagramClient.ApiException): String {
        return when (ex.code) {
            "url" -> getString(R.string.save_fail_url)
            "rate" -> getString(R.string.save_fail_rate)
            "missing", "empty", "blocked", "login" -> getString(R.string.save_fail_missing)
            "network" -> getString(R.string.save_fail_resolve, diag.listnrDetail.ifBlank { "network" })
            "upstream" -> getString(R.string.save_fail_upstream, ex.status.toString())
            "cdn", "host" -> getString(R.string.save_fail_cdn, diag.cdnDetail.ifBlank { ex.status.toString() })
            "store", "io" -> getString(R.string.save_fail_store, diag.storeDetail.ifBlank { "write" })
            "token", "expired" -> getString(R.string.save_fail_unexpected, ex.code)
            // Only codes with no known meaning ever reach the generic line.
            else -> getString(R.string.save_fail_net)
        }
    }

    /** Names the step and the real cause for an unexpected failure. */
    private fun unexpectedAt(stage: String, ex: Throwable): String {
        val detail = (ex.message ?: ex.javaClass.simpleName).take(48)
        return when (stage) {
            DownloadDiagnostics.STEP_LISTNR -> getString(R.string.save_fail_resolve, detail)
            DownloadDiagnostics.STEP_CDN -> getString(R.string.save_fail_cdn, detail)
            DownloadDiagnostics.STEP_STORE -> getString(R.string.save_fail_store, detail)
            else -> getString(R.string.save_fail_unexpected, stage)
        }
    }

    /** Marks the current step as the failed one, then notifies. */
    private fun failWith(text: String) {
        diag.finish("fail:${diag.stage}")
        notifyDone(text, error = true)
    }

    /** Persists the one-line summary and the shareable last-run report. */
    private fun persist(run: DownloadDiagnostics) {
        runCatching {
            getSharedPreferences(DownloadDiagnostics.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(DownloadDiagnostics.KEY, run.summary())
                .apply()
        }
        runCatching {
            val dir = getExternalFilesDir(null) ?: return@runCatching
            File(dir, DownloadDiagnostics.FILE_NAME).writeText(run.report())
        }
    }

    private fun goForeground(text: String, busy: Boolean = true) {
        val notification = buildNotification(getString(R.string.save_working), text, ongoing = true, error = false, busy = busy)
        if (Build.VERSION.SDK_INT >= 34) {
            val started = runCatching {
                startForeground(WORK_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }.isSuccess
            if (!started) {
                runCatching {
                    startForeground(WORK_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
                }.onFailure {
                    @Suppress("DEPRECATION")
                    startForeground(WORK_ID, notification)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            startForeground(WORK_ID, notification)
        }
    }

    private fun notifyDone(text: String, error: Boolean) {
        val title = if (error) getString(R.string.save_fail) else getString(R.string.save_started)
        manager().notify(DONE_ID, buildNotification(title, text, ongoing = false, error = error, busy = false))
    }

    private fun buildNotification(title: String, text: String, ongoing: Boolean, error: Boolean, busy: Boolean): Notification {
        ensureChannel(this)
        val downloads = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        val target = if (downloads.resolveActivity(packageManager) != null) {
            downloads
        } else {
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val open = PendingIntent.getActivity(
            this,
            4,
            target,
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
        const val EXTRA_STREAM = "stream"
        private const val CHANNEL_ID = "nada_save"
        private const val WORK_ID = 27
        private const val DONE_ID = 28

        fun start(context: Context, url: String, stream: Uri? = null) {
            val intent = Intent(context, QuickSaveService::class.java).putExtra(EXTRA_URL, url)
            if (stream != null) intent.putExtra(EXTRA_STREAM, stream.toString())
            ContextCompat.startForegroundService(context, intent)
        }

        fun startStream(context: Context, uri: Uri) {
            start(context, "", uri)
        }

        fun tidyIgUrl(raw: String): String = IgLinks.fromText(raw).ifBlank { IgLinks.tidy(raw) }

        /** One-line summary of the last save attempt; blank when none yet. */
        fun lastRunSummary(context: Context): String = runCatching {
            context.getSharedPreferences(DownloadDiagnostics.PREFS, Context.MODE_PRIVATE)
                .getString(DownloadDiagnostics.KEY, null)
                .orEmpty()
        }.getOrDefault("")

        /** The shareable last-run report, only once a save actually ran. */
        fun lastRunFile(context: Context): File? {
            val dir = runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: return null
            return File(dir, DownloadDiagnostics.FILE_NAME).takeIf { it.isFile && it.length() > 0L }
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
