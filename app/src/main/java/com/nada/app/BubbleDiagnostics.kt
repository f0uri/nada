package com.nada.app

/**
 * Evidence for one bubble run. Pure Kotlin: the format and counters are tested
 * on a JVM without a device. Nothing here selects a node or triggers a tap.
 *
 * Only Copy-related labels are retained, never clipboard contents, download
 * URLs, passwords, or a dump of unrelated screen text. Counts are observations,
 * not unique nodes: the same label may be seen again on a later scan/pass.
 */
class BubbleDiagnostics {
    data class Scan(
        val number: Int,
        val phase: Int,
        val atMs: Long,
        var roots: Int = 0,
        var queryCopy: Int = 0,
        var walkCopy: Int = -1,
        var queryRawCopy: Int = 0,
        var walkRawCopy: Int = -1,
        var copySeen: Int = 0,
        var copyFiltered: Int = 0,
        var queryMs: Long = 0L,
        var walkMs: Long = -1L,
    ) {
        fun line(): String = "scan=$number phase=$phase at=$atMs roots=$roots " +
            "qCopy=$queryCopy wCopy=$walkCopy qRaw=$queryRawCopy wRaw=$walkRawCopy " +
            "seen=$copySeen filtered=$copyFiltered qMs=$queryMs wMs=$walkMs"
    }

    var phase: Int = ShareNodes.PHASE_FIND_SHARE
    var scans: Int = 0
    var roots: Int = 0
    var rootList: String = ""
    var windowsEmpty: Boolean = false
    var activeRootOnly: Boolean = false
    var bestCopy: Int = 0
    var walkCopy: Int = 0
    var bestShare: Int = 0
    var copySeen: Int = 0
    var copyFiltered: Int = 0
    var shareTapped: Boolean = false
    var copyTapped: Boolean = false
    var clickTried: Boolean = false
    var copyClickTried: Boolean = false
    var shareClickTried: Boolean = false
    var selfClickable: Boolean = false
    var clickOn: String = "n/a"
    var clickResult: String = "n/a"
    var queryMs: Long = 0L
    var walkMs: Long = 0L
    var elapsedMs: Long = 0L
    var stage: String = ""
    var outcome: String = ""
    var fileError: String = ""
    private val history = ArrayList<Scan>()
    private val labels = ArrayList<String>()
    private val notes = ArrayList<String>()
    private var current: Scan? = null

    fun beginScan(number: Int, phase: Int, atMs: Long) {
        if (history.size >= MAX_SCANS) return
        val scan = Scan(number, phase, atMs)
        history += scan
        current = scan
        scans = number
        this.phase = phase
    }

    fun observeRoots(descriptions: List<String>, windowsEmpty: Boolean, activeRootOnly: Boolean) {
        roots = descriptions.size
        rootList = descriptions.joinToString(", ")
        this.windowsEmpty = windowsEmpty
        this.activeRootOnly = activeRootOnly
        current?.roots = roots
        if (windowsEmpty) note("windows list empty")
        if (activeRootOnly) note("rootInActiveWindow only")
        if (roots == 0) note("no searchable root")
    }

    fun queryTime(ms: Long) {
        queryMs += ms
        current?.queryMs = ms
    }

    fun walkTime(ms: Long) {
        walkMs += ms
        current?.walkMs = ms
    }

    /** [usable] is the existing visible+enabled gate, not a matching score. */
    fun observeCopy(pass: String, text: String?, description: String?, score: Int, usable: Boolean) {
        val scan = current
        if (pass == "walk") {
            if (score > (scan?.walkRawCopy ?: -1)) scan?.walkRawCopy = score
            if (usable && score > walkCopy) walkCopy = score
            if (usable && scan != null && score > scan.walkCopy) scan.walkCopy = score
        } else {
            if (score > (scan?.queryRawCopy ?: 0)) scan?.queryRawCopy = score
            if (usable && score > bestCopy) bestCopy = score
            if (usable && scan != null && score > scan.queryCopy) scan.queryCopy = score
        }
        if (!ShareNodes.looksLikeCopyLabel(text, description)) return
        copySeen++
        scan?.let { it.copySeen++ }
        if (!usable) {
            copyFiltered++
            scan?.let { it.copyFiltered++ }
        }
        if (labels.size < MAX_LABELS) {
            val label = listOfNotNull(text, description).joinToString(" / ")
                .replace(Regex("\\s+"), " ").take(100)
            val entry = "$pass usable=$usable score=$score label=$label"
            if (label.isNotBlank() && entry !in labels) labels += entry
        }
    }

    fun observeShare(score: Int) {
        if (score > bestShare) bestShare = score
    }

    fun beginClick(copy: Boolean) {
        clickTried = true
        if (copy) copyClickTried = true else shareClickTried = true
        clickOn = "searching"
        clickResult = "no_action"
    }

    fun blockedClick(reason: String) {
        clickResult = reason
    }

    fun observeClick(onSelf: Boolean, hops: Int, result: Boolean) {
        clickOn = if (onSelf) "self" else "ancestor$hops"
        clickResult = result.toString()
    }

    fun note(text: String) {
        if (notes.size < MAX_NOTES && text.isNotBlank() && text !in notes) notes += text
    }

    /** One line for the existing About card; no additional screen. */
    fun summary(): String = "roots=$roots bestCopy=$bestCopy walkCopy=$walkCopy " +
        "copySeen=$copySeen filtered=$copyFiltered click=$clickResult " +
        "scans=$scans ms=$elapsedMs stage=${stage.ifBlank { "none" }}"

    fun report(): String = buildString {
        append("IG SAVE bubble run\n").append(summary()).append('\n')
        append("phase=").append(phase)
        append(" shareTapped=").append(shareTapped)
        append(" copyTapped=").append(copyTapped)
        append(" bestShare=").append(bestShare).append('\n')
        append("clickTried=").append(clickTried)
        append(" copyClickTried=").append(copyClickTried)
        append(" shareClickTried=").append(shareClickTried)
        append(" selfClickable=").append(selfClickable)
        append(" clickOn=").append(clickOn).append('\n')
        append("windowsEmpty=").append(windowsEmpty)
        append(" activeRootOnly=").append(activeRootOnly)
        append(" queryMs=").append(queryMs)
        append(" walkMs=").append(walkMs).append('\n')
        append("outcome=").append(outcome.ifBlank { "unknown" }).append('\n')
        append("last roots: ").append(rootList.ifBlank { "none" }).append('\n')
        append("walkCopy=-1/wMs=-1 means the walk did not run.\n")
        for (scan in history) append(scan.line()).append('\n')
        for (label in labels) append("copy observation: ").append(label).append('\n')
        for (note in notes) append("note: ").append(note).append('\n')
        if (fileError.isNotBlank()) append("file error: ").append(fileError).append('\n')
    }

    companion object {
        const val FILE_NAME = "bubble-last-run.txt"
        private const val MAX_SCANS = 60
        private const val MAX_LABELS = 8
        private const val MAX_NOTES = 12

        /** The terminal fallback used by every clipboard/give-up path. */
        fun stageFor(phase: Int, copyClickFailed: Boolean): String = when {
            copyClickFailed -> "copy_click"
            phase == ShareNodes.PHASE_WAIT_SHEET -> "no_copy"
            else -> "no_share"
        }
    }
}
