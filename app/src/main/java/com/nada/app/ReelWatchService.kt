package com.nada.app

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import java.io.File

/**
 * One-tap download of the reel that is on screen inside Instagram.
 *
 * Contract (kept deliberately narrow):
 *  - the service is OFF until the user enables it in Android accessibility settings;
 *  - it does nothing at all until the floating bubble is tapped;
 *  - it only acts while [ShareNodes.INSTAGRAM_PACKAGE] owns the active window,
 *    except after Share was tapped, when the share sheet may own it;
 *  - it clicks at most one Share control and at most one "Copy link" control,
 *    found by label / content-description / resource-id — never by coordinates;
 *    Share only opens the sheet (it can never send the reel to a person), so
 *    even a prefix match may tap it; Copy stays exact-label-or-resource-id
 *    only — a weak match is never clicked there, because a wrong tap in the
 *    sheet could send the reel to a person. If the Copy label is not exact
 *    (for example the UI language changed), the service stops and falls back
 *    to the clipboard instead of guessing, and it never touches settings or
 *    the language;
 *  - once Share was tapped, the sheet may be Instagram's own sheet or the
 *    system share sheet: the search covers both, and the Copy tap is allowed
 *    in either window. The wait phase polls fast (100 ms) so the sheet only
 *    flashes and closes by itself — the user taps nothing after the bubble;
 *  - after Share is clicked, Copy is searched for up to 10 s without another
 *    Share tap, giving Instagram time to expose the link action; a click that
 *    fails is retried while that search budget lasts;
 *  - if the share flow is not there, it falls back to the clipboard and then to a
 *    single short toast. No taps outside the share flow.
 *
 * The service subscribes only to Instagram window-state events and ignores them;
 * screen content is read only during a bubble-triggered run.
 */
class ReelWatchService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var finished = false
    private var clipboardTried = false
    private var phase = ShareNodes.PHASE_FIND_SHARE
    private var shareTapped = false
    private var copyTapped = false
    private var copyTappedAt = 0L
    @Volatile private var clipboardChangedAfterCopy = false
    private var watchedClipboard: ClipboardManager? = null
    private var clipboardChangeListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var copyClickFailed = false
    /** Why the run gave up: no_share / no_copy / copy_click / save_start. */
    private var failStage = ""
    /** Evidence for this run: written to a file and shown as one debug line. */
    private var diag = BubbleDiagnostics()
    private var startedAt = 0L
    private var diagPersisted = false
    private var scans = 0
    private var deadline = 0L
    private var deadlineCallback: Runnable? = null
    private var focusView: View? = null
    private var focusDone = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    /** Window-state events are ignored on purpose: nothing runs without a tap. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        abort()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        abort()
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        abort()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun abort() {
        if (running) persistDiagnostics("interrupted")
        running = false
        finished = true
        handler.removeCallbacksAndMessages(null)
        deadlineCallback = null
        stopClipboardWatch()
        dropFocusWindow()
    }

    /**
     * Called from the bubble. Returns true when this service took the tap, so the
     * caller must not run the legacy clipboard path. The foreground check happens
     * here, synchronously, so a tap outside Instagram keeps the old behaviour.
     */
    private fun takeTap(): Boolean {
        if (!Prefs.reelWatchOn(this)) return false
        if (running) return true
        if (!instagramIsForeground()) return false
        running = true
        finished = false
        clipboardTried = false
        phase = ShareNodes.PHASE_FIND_SHARE
        shareTapped = false
        stopClipboardWatch()
        copyTapped = false
        copyTappedAt = 0L
        clipboardChangedAfterCopy = false
        copyClickFailed = false
        failStage = ""
        diag = BubbleDiagnostics()
        diagPersisted = false
        startedAt = System.currentTimeMillis()
        scans = 0
        armDeadline(RUN_BUDGET_MS)
        handler.post { scan() }
        return true
    }

    /** Resets the bounded scan deadline and its matching terminal callback. */
    private fun armDeadline(durationMs: Long) {
        deadlineCallback?.let { handler.removeCallbacks(it) }
        deadline = System.currentTimeMillis() + durationMs
        val callback = Runnable { readClipboard() }
        deadlineCallback = callback
        handler.postDelayed(callback, durationMs)
    }

    /**
     * Only the package name of the active window is inspected here. The tree is
     * never walked unless that package is Instagram.
     */
    private fun instagramIsForeground(): Boolean {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        if (root != null) return root.packageName?.toString() == ShareNodes.INSTAGRAM_PACKAGE
        val active = runCatching {
            windows.firstOrNull {
                it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION
            }
        }.getOrNull()
        val pkg = runCatching { active?.root?.packageName?.toString() }.getOrNull()
        return pkg == ShareNodes.INSTAGRAM_PACKAGE
    }

    /**
     * Roots worth searching. Before Share is tapped, Instagram only. Once Share
     * was tapped, Instagram plus the system share-sheet windows, because the
     * Copy link node may live in either one. Every window type is searched:
     * Instagram's sheet is a dialog (TYPE_APPLICATION_PANEL), not an
     * application window, and filtering by type silently skipped it — the
     * sheet opened, "نسخ الرابط" sat on screen, and the Copy tap never
     * happened (1.63 on-device report). The package check still gates
     * everything, so no other app's windows are ever searched.
     */
    private fun searchRoots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>(MAX_ROOTS)
        val active = runCatching { rootInActiveWindow }.getOrNull()
        if (active != null && ShareNodes.isSearchablePackage(active.packageName?.toString(), phase)) {
            out += active
        }
        val available = runCatching { windows }.getOrDefault(emptyList())
        val extra = runCatching {
            available.mapNotNull { it.root }
                .filter { ShareNodes.isSearchablePackage(it.packageName?.toString(), phase) }
        }.getOrDefault(emptyList())
        for (root in extra) {
            if (out.size >= MAX_ROOTS) break
            if (out.none { it == root }) out += root
        }
        recordRoots(out, available.isEmpty(), extra.isEmpty() && out.isNotEmpty())
        return out
    }

    /**
     * Evidence for candidate (b): if the sheet window never appears in
     * service.windows, only rootInActiveWindow is searched and the run says so.
     */
    private fun recordRoots(
        roots: List<AccessibilityNodeInfo>,
        windowsEmpty: Boolean,
        activeOnly: Boolean,
    ) {
        val described = ArrayList<String>(roots.size)
        for (root in roots) {
            val window = runCatching { root.window }.getOrNull()
            val pkg = runCatching { root.packageName?.toString() }.getOrNull() ?: "?"
            // Raw window type: 1=application, 2=panel/dialog. -1=unknown.
            val type = runCatching { window?.type }.getOrNull() ?: -1
            described += "$pkg:type$type"
        }
        diag.observeRoots(described, windowsEmpty, activeOnly)
    }

    private class Candidate(val node: AccessibilityNodeInfo, val score: Int)

    /**
     * Fast pass: the text search runs inside Instagram's process, so each query
     * is a single IPC instead of one per node. It matches text and
     * content-description, which covers both languages.
     */
    private fun collectByQuery(roots: List<AccessibilityNodeInfo>): Pair<Candidate?, Candidate?> {
        var share: Candidate? = null
        var copy: Candidate? = null
        for (root in roots) {
            for (query in ShareNodes.SHARE_QUERIES) {
                val hits = runCatching { root.findAccessibilityNodeInfosByText(query) }.getOrNull()
                for (node in hits.orEmpty()) {
                    val ok = usable(node)
                    val score = ShareNodes.shareScore(descOf(node), idOf(node))
                    if (ok) {
                        diag.observeShare(score)
                        if (score > (share?.score ?: 0)) share = Candidate(node, score)
                    }
                }
                if (share != null && share.score >= 3) break
            }
            for (query in ShareNodes.COPY_QUERIES) {
                val hits = runCatching { root.findAccessibilityNodeInfosByText(query) }.getOrNull()
                for (node in hits.orEmpty()) {
                    val text = textOf(node)
                    val desc = descOf(node)
                    val score = ShareNodes.copyScore(text, desc, idOf(node))
                    val ok = usable(node)
                    diag.observeCopy("query", text, desc, score, ok)
                    if (!ok) continue
                    if (score > (copy?.score ?: 0)) copy = Candidate(node, score)
                }
                if (copy != null && copy.score >= 4) break
            }
        }
        return share to copy
    }

    private fun usable(node: AccessibilityNodeInfo): Boolean {
        val visible = runCatching { node.isVisibleToUser }.getOrDefault(false)
        val enabled = runCatching { node.isEnabled }.getOrDefault(false)
        return visible && enabled
    }

    private fun idOf(node: AccessibilityNodeInfo): String? =
        runCatching { node.viewIdResourceName }.getOrNull()

    private fun descOf(node: AccessibilityNodeInfo): String? =
        runCatching { node.contentDescription?.toString() }.getOrNull()

    private fun textOf(node: AccessibilityNodeInfo): String? =
        runCatching { node.text?.toString() }.getOrNull()

    /**
     * Fallback pass for controls that expose only a resource-id. Bounded in node
     * count and depth so a pathological tree can never stall the main thread.
     * No geometry is ever consulted.
     */
    private fun collectByIdWalk(roots: List<AccessibilityNodeInfo>): Pair<Candidate?, Candidate?> {
        var share: Candidate? = null
        var copy: Candidate? = null
        var budget = MAX_NODES
        val stack = ArrayList<Pair<AccessibilityNodeInfo, Int>>()
        for (root in roots.reversed()) stack.add(root to 0)
        while (stack.isNotEmpty() && budget > 0) {
            val (node, depth) = stack.removeAt(stack.size - 1)
            budget--
            val text = textOf(node)
            val desc = descOf(node)
            val ok = usable(node)
            // Observe Copy-looking labels even when the existing visibility
            // gate rejects them. The node/depth caps still bound this pass.
            if (ok || ShareNodes.looksLikeCopyLabel(text, desc)) {
                val id = idOf(node)
                val c = ShareNodes.copyScore(text, desc, id)
                diag.observeCopy("walk", text, desc, c, ok)
                if (ok) {
                    val s = ShareNodes.shareScore(desc, id)
                    diag.observeShare(s)
                    if (s > 0 && s > (share?.score ?: 0)) share = Candidate(node, s)
                    if (c > 0 && c > (copy?.score ?: 0)) copy = Candidate(node, c)
                }
            }
            if (depth >= MAX_DEPTH) continue
            val count = runCatching { node.childCount }.getOrDefault(0)
            for (i in count - 1 downTo 0) {
                if (budget <= 0) break
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                stack.add(child to depth + 1)
            }
        }
        return share to copy
    }

    private fun scan() {
        if (finished) return
        if (System.currentTimeMillis() > deadline) {
            readClipboard()
            return
        }
        // Before Share is tapped the reel must be on screen. Once Share was
        // tapped, the sheet may own the foreground window (Instagram's own
        // sheet or the system share sheet), so the run keeps going until the
        // deadline instead of aborting on the window change.
        if (phase == ShareNodes.PHASE_FIND_SHARE && !instagramIsForeground()) {
            failStage = "no_share"
            readClipboard()
            return
        }
        scans++
        val now = System.currentTimeMillis()
        diag.beginScan(scans, phase, now - startedAt)
        val roots = searchRoots()
        val queryStart = System.currentTimeMillis()
        var found = collectByQuery(roots)
        diag.queryTime(System.currentTimeMillis() - queryStart)
        // Walk the tree whenever the Copy node is still missing: in the sheet
        // the bottom row also holds a "Share" label, so a found Share used to
        // suppress the walk and the Copy node was never looked for a second
        // way (1.65 on-device report: sheet open, "نسخ الرابط" on screen,
        // no tap). The walk is capped by MAX_NODES / MAX_DEPTH.
        if (found.second == null &&
            System.currentTimeMillis() + WALK_SLACK_MS < deadline
        ) {
            val walkStart = System.currentTimeMillis()
            val walked = collectByIdWalk(roots)
            diag.walkTime(System.currentTimeMillis() - walkStart)
            found = (found.first ?: walked.first) to walked.second
        } else if (found.second == null) {
            diag.note("walk skipped: less than WALK_SLACK_MS left")
        }
        val share = found.first
        val copy = found.second
        when (ShareNodes.nextAction(phase, copy?.score ?: 0, share?.score ?: 0)) {
            ShareNodes.Step.SHARE -> {
                if (shareTapped || share == null) {
                    failStage = "no_share"
                    readClipboard()
                    return
                }
                if (!click(share.node)) {
                    // The node may become clickable a moment later; retry
                    // while the budget lasts instead of abandoning the run.
                    retryOrGiveUp()
                    return
                }
                shareTapped = true
                diag.shareTapped = true
                phase = ShareNodes.PHASE_WAIT_SHEET
                // Give Instagram's share sheet a full 10 seconds to expose
                // Copy link. Keep scanning only; do not issue another Share
                // tap while this phase is active.
                armDeadline(COPY_SEARCH_BUDGET_MS)
                scheduleScan()
            }
            ShareNodes.Step.COPY -> {
                if (copyTapped || copy == null) {
                    failStage = if (copyClickFailed) "copy_click" else "no_copy"
                    readClipboard()
                    return
                }
                watchClipboardForCopy()
                if (!click(copy.node, allowResolver = true)) {
                    stopClipboardWatch()
                    copyClickFailed = true
                    retryOrGiveUp()
                    return
                }
                copyTapped = true
                diag.copyTapped = true
                copyTappedAt = System.currentTimeMillis()
                // Wait for a fresh clipboard write before closing the sheet.
                // Otherwise Back can race Instagram's Copy handler and leave
                // the previous reel URL in the clipboard.
                handler.postDelayed({ readClipboard() }, COPY_SETTLE_MS)
            }
            ShareNodes.Step.WAIT -> scheduleScan()
            ShareNodes.Step.GIVE_UP -> {
                failStage = "no_share"
                readClipboard()
            }
        }
    }

    /** Retry the scan while the budget lasts; otherwise fall back to the clipboard. */
    private fun retryOrGiveUp() {
        if (finished) return
        if (scans >= MAX_SCANS || System.currentTimeMillis() + scanGapMs() > deadline) {
            readClipboard()
            return
        }
        scheduleScan()
    }

    /** Fast polling once the sheet is awaited, so it only flashes. */
    private fun scanGapMs(): Long =
        if (phase == ShareNodes.PHASE_WAIT_SHEET) FAST_SCAN_GAP_MS else SCAN_GAP_MS

    private fun scheduleScan() {
        if (finished) return
        val gap = scanGapMs()
        if (scans >= MAX_SCANS || System.currentTimeMillis() + gap > deadline) {
            readClipboard()
            return
        }
        handler.postDelayed({ scan() }, gap)
    }

    /**
     * ACTION_CLICK on the node or its nearest clickable parent. No gestures.
     * [allowResolver] is true only for the Copy step, whose node may live in
     * the system share-sheet window once Share was tapped.
     */
    private fun click(node: AccessibilityNodeInfo, allowResolver: Boolean = false): Boolean {
        // Only the Copy call allows the resolver, so this also identifies the
        // attempted action without changing either call site's safety gate.
        diag.beginClick(copy = allowResolver)
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < CLICK_PARENT_HOPS) {
            val active = current
            val pkg = runCatching { active.packageName?.toString() }.getOrNull()
            // A node found inside a searched Instagram / resolver root may
            // report no package at all; refusing it would strand the Copy
            // tap even though the node is inside an approved window.
            val inScope = pkg == ShareNodes.INSTAGRAM_PACKAGE ||
                (allowResolver && pkg != null && pkg in ShareNodes.RESOLVER_PACKAGES) ||
                pkg.isNullOrEmpty()
            if (!inScope) return false.also { diag.blockedClick("out_of_scope") }
            val clickable = runCatching { active.isClickable }.getOrDefault(false)
            if (hops == 0) diag.selfClickable = clickable
            if (clickable) {
                val result = runCatching {
                    active.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
                diag.observeClick(onSelf = hops == 0, hops = hops, result = result)
                // 1.67 phone evidence: the Copy node is found, but the first
                // clickable row ancestor can swallow the tap. Keep climbing —
                // a higher ancestor may act — and only give up when the
                // bounded chain is exhausted.
                if (result) {
                    diag.note("click ok at hop $hops")
                    return true
                }
            }
            current = runCatching { active.parent }.getOrNull()
            hops++
        }
        diag.note("click failed: no hop acted")
        diag.blockedClick("no_clickable_ancestor")
        return false
    }

    /**
     * Terminal step, reached once per tap: read the clipboard, save the reel it
     * holds, or show the single "copy the link first" toast.
     */
    private fun readClipboard() {
        if (finished || clipboardTried) return
        deadlineCallback?.let { handler.removeCallbacks(it) }
        deadlineCallback = null
        clipboardTried = true
        // Covers both the initial Share search and the 10 s Copy search, as
        // well as retryOrGiveUp and scheduleScan. No terminal path can report
        // an empty stage. Never save an old clipboard URL unless this run
        // actually confirmed a Copy action.
        if (failStage.isBlank()) {
            failStage = BubbleDiagnostics.stageFor(phase, copyClickFailed)
        }
        if (!copyTapped) {
            stopClipboardWatch()
            diag.note("clipboard ignored: Copy action not confirmed")
            needLink()
            return
        }
        val link = reelFrom(clipText())
        if (link.isNotBlank()) {
            stopClipboardWatch()
            dismissShareSheet()
            save(link)
            return
        }
        // Android 10+ only lets the focused uid read the clipboard, so borrow
        // focus for a moment with a 1x1 transparent accessibility overlay.
        withFocus {
            val second = reelFrom(clipText())
            stopClipboardWatch()
            if (second.isNotBlank()) {
                dismissShareSheet()
                save(second)
            } else {
                if (copyTapped) {
                    failStage = "copy_stale"
                    diag.note("Copy acted but clipboard did not refresh")
                }
                needLink()
            }
        }
    }

    /** Dismiss Instagram's sheet only after a fresh copied URL is confirmed. */
    private fun dismissShareSheet() {
        if (shareTapped && copyTapped) {
            runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
        }
    }

    /** Watch for a clipboard write specifically caused by this Copy action. */
    private fun watchClipboardForCopy() {
        stopClipboardWatch()
        clipboardChangedAfterCopy = false
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            if (!clipboardChangedAfterCopy) diag.note("clipboard changed after Copy tap")
            clipboardChangedAfterCopy = true
        }
        if (runCatching { manager.addPrimaryClipChangedListener(listener) }.isSuccess) {
            watchedClipboard = manager
            clipboardChangeListener = listener
        } else {
            diag.note("clipboard change watch unavailable")
        }
    }

    private fun stopClipboardWatch() {
        val manager = watchedClipboard
        val listener = clipboardChangeListener
        if (manager != null && listener != null) {
            runCatching { manager.removePrimaryClipChangedListener(listener) }
        }
        watchedClipboard = null
        clipboardChangeListener = null
    }

    private fun clipText(): String {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
        return runCatching {
            val clip = manager.primaryClip ?: return@runCatching ""
            // Only use a link when the clipboard changed after this tap, or its
            // platform timestamp proves it is fresh. A missing timestamp is
            // not permission to reuse whatever reel URL was already there.
            if (copyTapped) {
                val stamped = runCatching { clip.description?.timestamp ?: 0L }.getOrDefault(0L)
                val fresh = if (stamped != 0L) {
                    stamped + CLIP_SKEW_MS >= copyTappedAt
                } else {
                    clipboardChangedAfterCopy
                }
                if (!fresh) {
                    diag.note("clipboard not fresh after Copy tap")
                    return@runCatching ""
                }
            }
            clip.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        }.getOrDefault("")
    }

    private fun reelFrom(raw: String): String {
        val url = IgLinks.fromText(raw)
        if (url.isBlank() || !IgLinks.looksLikePost(url)) return ""
        return url
    }

    /**
     * Adds a transparent, untouchable, focusable 1x1 overlay so this uid owns the
     * focused window, runs [block] once, then removes it and hands focus back.
     * TYPE_ACCESSIBILITY_OVERLAY needs no extra permission for this service.
     */
    private fun withFocus(block: () -> Unit) {
        if (finished) return
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            block()
            return
        }
        val view = View(this).apply {
            setBackgroundColor(0x00000000)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "NadaClipboardFocus"
        }
        focusDone = false
        val settle = {
            if (!focusDone) {
                focusDone = true
                if (!finished) block()
                dropFocusWindow()
            }
        }
        val added = runCatching { wm.addView(view, params) }.isSuccess
        if (!added) {
            block()
            return
        }
        focusView = view
        view.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) handler.post { settle() }
        }
        handler.postDelayed({ settle() }, FOCUS_TIMEOUT_MS)
    }

    private fun dropFocusWindow() {
        val view = focusView ?: return
        focusView = null
        runCatching { (getSystemService(WINDOW_SERVICE) as? WindowManager)?.removeView(view) }
    }

    private fun save(url: String) {
        if (finished) return
        finished = true
        running = false
        handler.removeCallbacksAndMessages(null)
        deadlineCallback = null
        stopClipboardWatch()
        dropFocusWindow()
        Prefs.setLastIgUrl(this, url)
        runCatching { QuickSaveService.start(this, url) }
            .onSuccess { persistDiagnostics("saved") }
            .onFailure {
                failStage = "save_start"
                needLinkToast()
            }
    }

    private fun needLink() {
        if (finished) return
        finished = true
        running = false
        handler.removeCallbacksAndMessages(null)
        deadlineCallback = null
        stopClipboardWatch()
        dropFocusWindow()
        needLinkToast()
    }

    private fun needLinkToast() {
        if (failStage.isBlank()) {
            failStage = BubbleDiagnostics.stageFor(phase, copyClickFailed)
        }
        persistDiagnostics("need_link")
        // Tell the user (and the next fix) exactly where the run stopped,
        // instead of the same "copy the link first" for every failure.
        val text = when (failStage) {
            "no_copy" -> getString(R.string.reel_watch_no_copy)
            "copy_click" -> getString(R.string.reel_watch_copy_click_failed)
            "copy_stale" -> getString(R.string.reel_watch_copy_stale)
            "no_share" -> getString(R.string.reel_watch_no_share)
            else -> getString(R.string.reel_watch_need_link)
        }
        runCatching {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Written only after a bubble-triggered run ends. The summary lives in a
     * separate debug preferences file so the normal Prefs contract is untouched.
     * The report stays in this app's private external files directory.
     */
    private fun persistDiagnostics(outcome: String) {
        if (diagPersisted) return
        diagPersisted = true
        diag.elapsedMs = System.currentTimeMillis() - startedAt
        diag.phase = phase
        diag.shareTapped = shareTapped
        diag.copyTapped = copyTapped
        diag.stage = if (outcome == "saved") "saved" else failStage
        diag.outcome = outcome
        runCatching {
            getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE).edit()
                .putString(DEBUG_SUMMARY, diag.summary()).apply()
        }
        val dir = runCatching { getExternalFilesDir(null) }.getOrNull()
        if (dir == null) {
            diag.fileError = "external_files_dir_unavailable"
        } else {
            runCatching { File(dir, BubbleDiagnostics.FILE_NAME).writeText(diag.report()) }
                .onFailure { diag.fileError = it.javaClass.simpleName }
        }
    }

    companion object {
        private const val DEBUG_PREFS = "nada_bubble_debug"
        private const val DEBUG_SUMMARY = "last_run"
        private const val RUN_BUDGET_MS = 7000L
        private const val COPY_SEARCH_BUDGET_MS = 10_000L
        private const val SCAN_GAP_MS = 320L
        private const val FAST_SCAN_GAP_MS = 100L
        private const val MAX_SCANS = 140
        private const val MAX_NODES = 900
        private const val WALK_SLACK_MS = 900L
        private const val MAX_DEPTH = 40
        private const val MAX_ROOTS = 6
        // Copy rows in the sheet can sit several layouts deep (icon + text +
        // row + list), so the click is allowed to climb further up the tree.
        private const val CLICK_PARENT_HOPS = 8
        private const val COPY_SETTLE_MS = 500L
        private const val CLIP_SKEW_MS = 1500L
        private const val FOCUS_TIMEOUT_MS = 420L

        @Volatile
        private var instance: ReelWatchService? = null

        /** Internal summary used to decide whether About shows its share control. */
        fun lastRunSummary(context: Context): String = runCatching {
            context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
                .getString(DEBUG_SUMMARY, "").orEmpty()
        }.getOrDefault("")

        fun lastRunFile(context: Context): File? = runCatching {
            context.getExternalFilesDir(null)?.let { File(it, BubbleDiagnostics.FILE_NAME) }
                ?.takeIf { it.isFile }
        }.getOrNull()

        /** True when Android reports our accessibility component as enabled. */
        fun isServiceEnabled(context: Context): Boolean {
            val expected = ComponentName(context, ReelWatchService::class.java)
            val flat = expected.flattenToString()
            val short = expected.flattenToShortString()
            val enabled = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            }.getOrNull().orEmpty()
            if (enabled.split(':').any { it.equals(flat, true) || it.equals(short, true) }) return true
            return instance != null
        }

        /**
         * Bubble entry point. True = this service owns the tap; false = the caller
         * must keep its previous clipboard behaviour untouched.
         */
        fun takeBubbleTap(): Boolean {
            val service = instance ?: return false
            return runCatching { service.takeTap() }.getOrDefault(false)
        }
    }
}
