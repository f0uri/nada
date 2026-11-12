package com.nada.app

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** In-app OTA: check, download with visible progress, then self-update. */
class AppUpdateManager private constructor(private val app: Context) {
    data class Update(val version: String, val code: Long, val apkUrl: String, val changelog: String)

    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
    @Volatile private var pendingApk: File? = null
    @Volatile private var pendingUpdate: Update? = null
    @Volatile private var downloading = false
    @Volatile private var progress = 0
    @Volatile private var progressText = ""
    private var dialog: AlertDialog? = null
    private var bar: ProgressBar? = null
    private var label: TextView? = null

    fun check(activity: Activity, manual: Boolean) {
        if (busy.get()) {
            if (manual) showProgress(activity)
            return
        }
        Thread {
            try {
                val request = Request.Builder()
                    .url("$MANIFEST_URL?t=${System.currentTimeMillis()}")
                    .header("Cache-Control", "no-cache")
                    .header("Accept", "application/json")
                    .build()
                val update = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("manifest ${response.code}")
                    parseManifest(response.body?.string().orEmpty())
                }
                val installed = installedCode()
                main.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    when {
                        update != null && update.code > installed -> showAvailable(activity, update)
                        manual -> Toast.makeText(activity, R.string.update_current, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
                if (manual) main.post {
                    if (!activity.isFinishing) Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    fun resumePending(activity: Activity) {
        val file = pendingApk ?: return
        val update = pendingUpdate ?: return
        if (downloading) return
        if (canInstall()) install(activity, file, update)
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
        bar = null
        label = null
    }

    private fun parseManifest(body: String): Update? {
        val json = JSONObject(body)
        val code = json.optLong("versionCode", -1)
        val version = json.optString("version").trim()
        val apkUrl = json.optString("apkUrl").trim()
        val changelog = json.optString("changelog").trim()
        if (code <= 0 || version.isBlank() || !allowedApkUrl(apkUrl)) return null
        return Update(version.take(40), code, apkUrl, changelog.take(4000))
    }

    private fun allowedApkUrl(raw: String): Boolean {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.host != "github.com") return false
        val path = uri.path.orEmpty()
        return (path.startsWith("/f0uri/nada/releases/download/") ||
            path.startsWith("/f0uri/nada/releases/latest/download/")) && path.endsWith(".apk")
    }

    private fun installedCode(): Long {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
    }

    private fun showAvailable(activity: Activity, update: Update) {
        dismiss()
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val message = TextView(activity).apply {
            text = "${getString(activity, R.string.update_available, update.version)}\n\n${update.changelog}\n\n${getString(activity, R.string.update_keep_data)}"
            textSize = 15f
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(activity).apply { addView(message) }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setView(scroll)
            .setPositiveButton(R.string.update_now) { _, _ -> download(activity, update) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun download(activity: Activity, update: Update) {
        if (!busy.compareAndSet(false, true)) return
        downloading = true
        progress = 0
        progressText = getString(activity, R.string.update_downloading, 0)
        showProgress(activity)
        Thread {
            var part: File? = null
            try {
                val dir = File(app.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, "ig-save-${update.code}.apk")
                val temp = File(dir, "ig-save-${update.code}.apk.part")
                part = temp
                val request = Request.Builder().url(update.apkUrl).header("Cache-Control", "no-cache").build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("apk ${response.code}")
                    val body = response.body ?: throw IOException("empty apk")
                    val total = body.contentLength()
                    var seen = 0L
                    body.byteStream().use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                seen += n
                                if (seen > MAX_APK) throw IOException("apk too large")
                                output.write(buffer, 0, n)
                                val pct = if (total > 0) ((seen * 100) / total).toInt().coerceIn(0, 100) else 0
                                if (pct != progress) {
                                    progress = pct
                                    progressText = getString(app, R.string.update_downloading, pct)
                                    main.post { renderProgress() }
                                }
                            }
                        }
                    }
                }
                if (!temp.renameTo(target)) throw IOException("rename")
                validateApk(target, update)
                downloading = false
                pendingApk = target
                pendingUpdate = update
                main.post {
                    dismiss()
                    if (!activity.isFinishing && !activity.isDestroyed) resumePending(activity)
                }
            } catch (_: Exception) {
                runCatching { part?.delete() }
                downloading = false
                busy.set(false)
                main.post {
                    dismiss()
                    if (!activity.isFinishing) Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showProgress(activity: Activity) {
        dismiss()
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        label = TextView(activity).apply {
            text = progressText
            gravity = Gravity.CENTER
        }
        bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = progress == 0
            progress = this@AppUpdateManager.progress
        }
        box.addView(label)
        box.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad / 2 })
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setView(box)
            .setCancelable(false)
            .show()
    }

    private fun renderProgress() {
        label?.text = progressText
        bar?.let {
            it.isIndeterminate = progress == 0
            it.progress = progress
        }
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls()

    private fun install(activity: Activity, apk: File, update: Update) {
        if (!canInstall()) {
            Toast.makeText(activity, R.string.update_allow_installs, Toast.LENGTH_LONG).show()
            runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")),
                )
            }
            return
        }
        try {
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                setSize(apk.length())
                if (Build.VERSION.SDK_INT >= 31) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("ig-save.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val intent = Intent(app, UpdateInstallActivity::class.java)
                    .setAction(UpdateInstallActivity.ACTION)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getActivity(app, id, intent, flags)
                session.commit(pending.intentSender)
            }
            busy.set(false)
            pendingApk = null
            pendingUpdate = null
        } catch (_: Exception) {
            busy.set(false)
            Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Reject an APK that is not this app, not newer, or not signed by the installed key. */
    @Suppress("DEPRECATION")
    private fun validateApk(apk: File, update: Update) {
        val pm = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            else android.content.pm.PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: throw IOException("bad apk")
        if (info.packageName != app.packageName) throw IOException("package")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        if (code != update.code || code <= installedCode()) throw IOException("version")
        val installed = pm.getPackageInfo(app.packageName, flags)
        if (signatures(info) != signatures(installed)) throw IOException("signature")
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: android.content.pm.PackageInfo): Set<String> {
        val signs = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            info.signatures
        } ?: return emptySet()
        return signs.map { android.util.Base64.encodeToString(it.toByteArray(), android.util.Base64.NO_WRAP) }.toSet()
    }

    private fun getString(context: Context, id: Int, vararg args: Any): String = context.getString(id, *args)

    companion object {
        private const val MANIFEST_URL = "https://raw.githubusercontent.com/f0uri/nada/main/version.json"
        private const val MAX_APK = 120L * 1024 * 1024
        @Volatile private var instance: AppUpdateManager? = null

        fun get(context: Context): AppUpdateManager =
            instance ?: synchronized(this) {
                instance ?: AppUpdateManager(context.applicationContext).also { instance = it }
            }
    }
}
