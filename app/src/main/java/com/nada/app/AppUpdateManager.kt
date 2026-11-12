package com.nada.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app OTA: check, download with visible progress, then self-update.
 *
 * Invariants that fix the 1.57/1.58 loop:
 *  - every exit path releases [busy] and closes the progress dialog, so a
 *    failure can never leave a non-cancelable dialog frozen at 100%;
 *  - installing hands the verified APK to the system installer (the Reel
 *    Checker path): the user taps Install once and the app reinstalls over
 *    itself with data kept. No PackageInstaller session, so nothing can hang
 *    on "installing".
 *  - "Later" and the daily prompt cap are persisted in [UpdateState];
 *  - the APK is verified (ZIP magic, size, package, version, signature) before
 *    it is ever handed to the installer.
 */
class AppUpdateManager private constructor(private val app: Context) {
    data class Update(val version: String, val code: Long, val apkUrl: String, val changelog: String)

    private val main = Handler(Looper.getMainLooper())
    private val state = UpdateState(app)
    private val busy = AtomicBoolean(false)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        // GitHub /releases/latest/download answers with a 302 to a signed CDN
        // host; both must be followed or we would save an HTML redirect body.
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile private var pendingApk: File? = null
    @Volatile private var pendingUpdate: Update? = null
    @Volatile private var progress = 0
    @Volatile private var progressText = ""
    private var dialog: AlertDialog? = null
    private var bar: ProgressBar? = null
    private var label: TextView? = null
    private var percentView: TextView? = null
    private var host: Activity? = null

    /** True while a download or an install session is in flight. */
    fun isBusy(): Boolean = busy.get()

    /** Drives the one-line check card: true = "يوجد تحديث". */
    fun hasUpdate(): Boolean = state.availableCode > installedCode()

    fun check(activity: Activity, manual: Boolean) {
        host = activity
        if (busy.get()) {
            // Never stack a second dialog on a running download/install.
            if (manual) showProgress(activity)
            return
        }
        Thread {
            var update: Update? = null
            var failed = false
            try {
                val request = Request.Builder()
                    .url("$MANIFEST_URL?t=${System.currentTimeMillis()}")
                    .header("Cache-Control", "no-cache")
                    .header("Accept", "application/json")
                    .build()
                update = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("manifest ${response.code}")
                    parseManifest(response.body?.string().orEmpty())
                }
            } catch (_: Exception) {
                failed = true
            }
            val installed = installedCode()
            val found = update
            main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                if (failed) {
                    if (manual) showError(activity, app.getString(R.string.update_check_failed), null)
                    return@post
                }
                val newer = found != null && found.code > installed
                if (found == null) {
                    state.availableCode = 0L
                    notifyCard(activity)
                    if (manual) showUpToDate(activity)
                    return@post
                }
                state.availableCode = if (newer) found.code else 0L
                notifyCard(activity)
                if (!newer) {
                    // Up to date: clear any stale prompt bookkeeping.
                    state.settledCode = installed
                    if (manual) showUpToDate(activity)
                    return@post
                }
                val allowed = UpdatePolicy.shouldShowDialog(
                    updateCode = found.code,
                    installedCode = installed,
                    laterCode = state.laterCode,
                    laterDay = state.laterDay,
                    promptedCode = state.promptedCode,
                    promptedDay = state.promptedDay,
                    today = state.today(),
                    manual = manual,
                    inProgress = busy.get(),
                )
                if (!allowed) return@post
                if (!manual) state.rememberPrompt(found.code)
                showAvailable(activity, found)
            }
        }.start()
    }

    /** Called from onResume: finish an install that was waiting on permission. */
    fun resumePending(activity: Activity) {
        host = activity
        notifyCard(activity)
        val file = pendingApk ?: return
        val update = pendingUpdate ?: return
        if (busy.get()) return
        if (!file.exists()) {
            pendingApk = null
            pendingUpdate = null
            return
        }
        if (canInstall()) install(activity, file, update)
    }

    fun dismiss() {
        runCatching { dialog?.dismiss() }
        dialog = null
        bar = null
        label = null
        percentView = null
    }

    /** Drops the Activity reference so a background result cannot leak it. */
    fun onHostDestroyed(activity: Activity) {
        if (host === activity) host = null
        dismiss()
    }

    /** Pushes the exact one-line status into the About card. */
    fun notifyCard(activity: Activity) {
        if (activity !is MainActivity) return
        runCatching { activity.renderUpdateState(hasUpdate()) }
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
        val pad = dp(activity, 24)
        // One short line only: no changelog dump.
        val message = TextView(activity).apply {
            text = app.getString(R.string.update_available, update.version)
            textSize = 15f
            setPadding(pad, pad, pad, pad)
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setView(message)
            .setPositiveButton(R.string.update_now) { _, _ -> download(activity, update) }
            .setNegativeButton(R.string.update_later) { _, _ ->
                // Persisted, so a cold start does not ask again today.
                state.rememberLater(update.code)
            }
            .setOnCancelListener { state.rememberLater(update.code) }
            .show()
    }

    private fun showUpToDate(activity: Activity) {
        dismiss()
        val pad = dp(activity, 24)
        val message = TextView(activity).apply {
            text = app.getString(R.string.update_none)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setView(message)
            .setPositiveButton(R.string.update_ok, null)
            .show()
    }

    private fun download(activity: Activity, update: Update) {
        if (!busy.compareAndSet(false, true)) return
        progress = 0
        progressText = app.getString(R.string.update_downloading, 0)
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
                                val pct = UpdatePolicy.percent(seen, total)
                                if (pct != progress) {
                                    progress = pct
                                    progressText = app.getString(R.string.update_downloading, pct)
                                    main.post { renderProgress() }
                                }
                            }
                            output.flush()
                        }
                    }
                    if (total > 0 && seen != total) throw IOException("truncated")
                }
                if (temp.length() < MIN_APK) throw IOException("apk too small")
                verifyHeader(temp)
                if (!temp.renameTo(target)) throw IOException("rename")
                part = null
                validateApk(target, update)
                pendingApk = target
                pendingUpdate = update
                main.post {
                    progress = 100
                    progressText = app.getString(R.string.update_installing)
                    renderProgress()
                    // Release busy before install() re-acquires it, so a failure
                    // inside install can never wedge the flag.
                    busy.set(false)
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        resumePending(activity)
                    } else {
                        dismiss()
                    }
                }
            } catch (err: Exception) {
                runCatching { part?.delete() }
                val reason = describe(err)
                main.post {
                    busy.set(false)
                    pendingApk = null
                    pendingUpdate = null
                    dismiss()
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        showError(activity, reason) { download(activity, update) }
                    }
                }
            }
        }.start()
    }

    /** Reads the first bytes so an HTML error page is rejected before install. */
    private fun verifyHeader(file: File) {
        val head = ByteArray(4)
        file.inputStream().use { input ->
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
        }
        if (!UpdatePolicy.looksLikeApk(head)) throw IOException("not an apk")
    }

    private fun showProgress(activity: Activity) {
        dismiss()
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_update_glassy, null)
        root.findViewById<TextView>(R.id.update_title).text = app.getString(R.string.update_title)
        percentView = root.findViewById<TextView>(R.id.update_percent)
        label = root.findViewById<TextView>(R.id.update_status)
        bar = root.findViewById<ProgressBar>(R.id.update_bar).apply {
            max = 100
            isIndeterminate = progress == 0
            progress = this@AppUpdateManager.progress
        }
        label?.text = progressText
        percentView?.text = "${progress}%"
        dialog = AlertDialog.Builder(activity)
            .setView(root)
            // Cancelable: a stuck dialog must never trap the user.
            .setCancelable(true)
            .setNegativeButton(R.string.update_later, null)
            .show()
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    /** Short error with a retry action; never a frozen progress dialog. */
    private fun showError(activity: Activity, message: String, retry: (() -> Unit)?) {
        dismiss()
        val pad = dp(activity, 24)
        val view = TextView(activity).apply {
            text = message
            textSize = 15f
            setPadding(pad, pad, pad, pad)
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setView(view)
            .setCancelable(true)
            .setNegativeButton(R.string.update_ok, null)
        if (retry != null) {
            builder.setPositiveButton(R.string.update_retry) { _, _ -> retry() }
        }
        dialog = builder.show()
    }

    private fun renderProgress() {
        label?.text = progressText
        percentView?.text = "${progress}%"
        bar?.let {
            it.isIndeterminate = progress == 0
            it.progress = progress
        }
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls()

    /**
     * Installs the update the way Reel Checker does: hand the downloaded APK
     * to the system installer, which reinstalls the app over itself (data
     * kept). The user taps Install once. No PackageInstaller session, no
     * silent-install handshake, nothing that can hang on "installing".
     */
    private fun install(activity: Activity, apk: File, update: Update) {
        if (!busy.compareAndSet(false, true)) return
        if (!canInstall()) {
            // Keep the APK so onResume can finish after the user grants it.
            busy.set(false)
            dismiss()
            showError(activity, app.getString(R.string.update_allow_installs)) {
                runCatching {
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")),
                    )
                }
            }
            return
        }
        dismiss()
        busy.set(false)
        if (!installWithSystemInstaller(activity, apk)) {
            showError(activity, app.getString(R.string.update_failed)) { install(activity, apk, update) }
        }
    }

    /** Opens the system installer on the downloaded APK (the Reel Checker path). */
    private fun installWithSystemInstaller(activity: Activity, apk: File): Boolean {
        if (!apk.exists()) return false
        val uri = runCatching {
            FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", apk)
        }.getOrNull() ?: return false
        return runCatching {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(view)
            true
        }.getOrDefault(false)
    }

    private fun describe(err: Exception): String {
        val base = app.getString(R.string.update_failed)
        val tag = when (err.message) {
            "signature" -> app.getString(R.string.update_err_signature)
            "package", "bad apk", "not an apk" -> app.getString(R.string.update_err_file)
            "version" -> app.getString(R.string.update_err_version)
            "truncated", "apk too small" -> app.getString(R.string.update_err_file)
            else -> null
        }
        return if (tag == null) base else "$base\n$tag"
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
        if (!UpdatePolicy.acceptApk(code, update.code, installedCode())) throw IOException("version")
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

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val MANIFEST_URL = "https://raw.githubusercontent.com/f0uri/nada/main/version.json"
        private const val MAX_APK = 120L * 1024 * 1024
        private const val MIN_APK = 100L * 1024

        @Volatile private var instance: AppUpdateManager? = null

        fun get(context: Context): AppUpdateManager =
            instance ?: synchronized(this) {
                instance ?: AppUpdateManager(context.applicationContext).also { instance = it }
            }
    }
}
