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
import android.widget.TextView
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
 *  - the install session is committed on a worker thread and its result is
 *    handled by [UpdateInstallActivity], including STATUS_PENDING_USER_ACTION;
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
        val pad = dp(activity, 24)
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
            // Cancelable: a stuck dialog must never trap the user.
            .setCancelable(true)
            .setNegativeButton(R.string.update_later, null)
            .show()
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
        bar?.let {
            it.isIndeterminate = progress == 0
            it.progress = progress
        }
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls()

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
        progressText = app.getString(R.string.update_installing)
        showProgress(activity)
        Thread {
            var sessionId = -1
            try {
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    setSize(apk.length())
                    if (Build.VERSION.SDK_INT >= 31) {
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }
                }
                sessionId = installer.createSession(params)
                val id = sessionId
                installer.openSession(id).use { session ->
                    apk.inputStream().use { input ->
                        session.openWrite("ig-save.apk", 0, apk.length()).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                    val intent = Intent(app, UpdateInstallActivity::class.java)
                        .setAction(UpdateInstallActivity.ACTION)
                        .putExtra(UpdateInstallActivity.EXTRA_CODE, update.code)
                    // Mutable is required: the installer fills in EXTRA_STATUS.
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pending = PendingIntent.getActivity(app, id, intent, flags)
                    session.commit(pending.intentSender)
                }
                // The result arrives in UpdateInstallActivity. Mark that we are
                // waiting so a resume does not start a second session.
                installing = true
                main.post {
                    progressText = app.getString(R.string.update_installing)
                    renderProgress()
                }
                main.postDelayed({ onInstallTimeout(activity, update, apk) }, INSTALL_TIMEOUT_MS)
            } catch (err: Exception) {
                if (sessionId >= 0) {
                    runCatching { app.packageManager.packageInstaller.abandonSession(sessionId) }
                }
                val reason = describe(err)
                main.post {
                    installing = false
                    busy.set(false)
                    dismiss()
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        showError(activity, reason) { install(activity, apk, update) }
                    }
                }
            }
        }.start()
    }

    /**
     * Safety net: if the installer never calls back, close the progress dialog
     * and offer a retry instead of sitting at 100% forever.
     */
    private fun onInstallTimeout(activity: Activity, update: Update, apk: File) {
        if (!installing) return
        installing = false
        busy.set(false)
        if (installedCode() >= update.code) {
            state.availableCode = 0L
            state.settledCode = installedCode()
            dismiss()
            notifyCard(activity)
            return
        }
        dismiss()
        if (!activity.isFinishing && !activity.isDestroyed) {
            showError(activity, app.getString(R.string.update_failed)) { install(activity, apk, update) }
        }
    }

    /** Called by UpdateInstallActivity once the session reports a result. */
    fun onInstallResult(code: Long, success: Boolean, message: String?) {
        installing = false
        busy.set(false)
        main.removeCallbacksAndMessages(null)
        val activity = host
        main.post {
            dismiss()
            if (success || installedCode() >= code) {
                pendingApk = null
                pendingUpdate = null
                state.availableCode = 0L
                state.settledCode = installedCode()
                state.rememberPrompt(code)
                if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                    notifyCard(activity)
                }
                return@post
            }
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                val apk = pendingApk
                val update = pendingUpdate
                showError(
                    activity,
                    message?.takeIf { it.isNotBlank() } ?: app.getString(R.string.update_failed),
                ) {
                    if (apk != null && update != null && apk.exists()) install(activity, apk, update)
                    else download(activity, update ?: return@showError)
                }
            }
        }
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
        private const val INSTALL_TIMEOUT_MS = 90_000L

        @Volatile private var instance: AppUpdateManager? = null

        /** True while a committed session has not reported back yet. */
        @Volatile var installing: Boolean = false
            private set

        fun get(context: Context): AppUpdateManager =
            instance ?: synchronized(this) {
                instance ?: AppUpdateManager(context.applicationContext).also { instance = it }
            }
    }
}
