package com.nada.app

import java.io.File
import java.util.Locale

/**
 * Pure evidence recorder for the save pipeline: listnr resolve -> cdn pull ->
 * store. Bounded and shareable by construction: it never holds media bytes,
 * never holds a signed CDN URL, and never holds query strings — only step
 * names, hosts, HTTP statuses, counts and timings.
 *
 * Every terminal path in QuickSaveService writes one report here, so the
 * failure notification and the About-card line always name the step that
 * actually failed instead of the generic network message.
 */
class DownloadDiagnostics {
    private val startedAtMs = System.currentTimeMillis()

    /** Input reel/post URL (never a CDN URL). */
    var url: String = ""
    var stage: String = STEP_START
    var outcome: String = ""
    var elapsedMs: Long = -1L

    /** e.g. "HTTP 400: No video found in response" — set by ListnrService. */
    var listnrDetail: String = ""

    /** Bounded outcome of the quicksave fallback; never contains a URL. */
    var quickSaveDetail: String = ""

    /** CDN host only (no path, no query). */
    var cdnHost: String = ""
    var cdnDetail: String = ""

    /** Store/write failure detail, e.g. "IOException: …". */
    var storeDetail: String = ""
    var bytes: Long = 0L

    /** True when the video came from the quicksave fallback, not listnr. */
    var viaQuickSave: Boolean = false

    val steps = ArrayList<String>()
    val notes = ArrayList<String>()

    /** Marks the beginning of a pipeline step; keeps the report ordered. */
    fun begin(step: String) {
        stage = step
        if (steps.size < MAX_STEPS) steps += "begin $step"
    }

    /** Short bounded observation (statuses, attempts, causes). */
    fun note(message: String) {
        if (notes.size < MAX_NOTES) notes += message.take(MAX_NOTE_CHARS)
    }

    /** First call wins; later stages must not overwrite the real outcome. */
    fun finish(outcome: String) {
        if (this.outcome.isNotBlank()) return
        this.outcome = outcome
        elapsedMs = System.currentTimeMillis() - startedAtMs
    }

    /** One line for the About card and the prefs mirror. */
    fun summary(): String = buildString {
        append("stage=").append(stage)
        append(" outcome=").append(outcome.ifBlank { "running" })
        append(" listnr=").append(listnrDetail.ifBlank { "-" })
        append(" quicksave=").append(quickSaveDetail.ifBlank { "-" })
        append(" cdn=").append(cdnHost.ifBlank { "-" })
        if (cdnDetail.isNotBlank()) append(' ').append(cdnDetail)
        append(" bytes=").append(bytes)
        append(" ms=").append(elapsedMs)
        append(" via=").append(if (viaQuickSave) "quicksave" else "listnr")
    }

    /** Multi-line report for download-last-run.txt (shareable). */
    fun report(): String = buildString {
        append("ig-save download diagnostics\n")
        append("url=").append(url.take(MAX_URL_CHARS)).append('\n')
        append("stage=").append(stage).append('\n')
        append("outcome=").append(outcome.ifBlank { "running" }).append('\n')
        append("listnr=").append(listnrDetail.ifBlank { "-" }).append('\n')
        append("quicksave=").append(quickSaveDetail.ifBlank { "-" }).append('\n')
        append("via=").append(if (viaQuickSave) "quicksave" else "listnr").append('\n')
        append("cdn=").append(cdnHost.ifBlank { "-" })
        if (cdnDetail.isNotBlank()) append(' ').append(cdnDetail)
        append('\n')
        append("store=").append(storeDetail.ifBlank { "-" }).append('\n')
        append("bytes=").append(bytes).append('\n')
        append("elapsed=").append(elapsedMs).append("ms\n")
        for (step in steps) append("step: ").append(step).append('\n')
        for (note in notes) append("note: ").append(note).append('\n')
    }

    companion object {
        const val FILE_NAME = "download-last-run.txt"
        const val PREFS = "nada_download_debug"
        const val KEY = "last_run"
        const val STEP_START = "start"
        const val STEP_LISTNR = "listnr"
        const val STEP_CDN = "cdn"
        const val STEP_STORE = "store"
        const val MAX_STEPS = 24
        const val MAX_NOTES = 12
        const val MAX_NOTE_CHARS = 140
        const val MAX_URL_CHARS = 64

        /**
         * Names the transport-level cause so a timeout can be told apart from
         * DNS, TLS, connect or read failures — pure java.*, JVM-testable.
         */
        fun classify(throwable: Throwable): String {
            val message = (throwable.message ?: "").lowercase(Locale.US)
            return when {
                throwable is java.net.UnknownHostException -> "dns"
                throwable is javax.net.ssl.SSLException -> "tls"
                throwable is java.net.ConnectException -> "connect"
                throwable is java.net.SocketTimeoutException -> "timeout"
                message.contains("timeout") -> "timeout"
                throwable is java.net.SocketException ||
                    message.contains("connection reset") ||
                    message.contains("connection refused") ||
                    message.contains("failed to connect") -> "connect"
                throwable is java.io.EOFException ||
                    message.contains("unexpected end of stream") -> "read"
                else -> "io"
            }
        }
    }
}
