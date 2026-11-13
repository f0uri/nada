"""Offline guards for the v1.58 one-tap bubble download.

Two layers:
  * source-level contract checks that need no Android toolchain (always run);
  * behavioural checks that compile the pure Kotlin decision logic and run it on
    a JVM (skipped when no Kotlin compiler / JDK is available locally).

Nothing here touches the network or a device.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app" / "src" / "main"
JAVA = APP / "java" / "com" / "nada" / "app"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

NEW_SOURCES = [JAVA / "ReelWatchService.kt", JAVA / "ShareNodes.kt", JAVA / "BubbleDiagnostics.kt",
                JAVA / "DownloadDiagnostics.kt"]


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


# --------------------------------------------------------------------------
# Manifest + service configuration
# --------------------------------------------------------------------------

def test_manifest_binds_accessibility_permission():
    tree = ET.parse(APP / "AndroidManifest.xml")
    services = tree.getroot().findall("application/service")
    svc = next(s for s in services if s.get(ANDROID_NS + "name") == ".ReelWatchService")
    assert svc.get(ANDROID_NS + "permission") == "android.permission.BIND_ACCESSIBILITY_SERVICE"
    assert svc.get(ANDROID_NS + "exported") == "true"
    actions = [a.get(ANDROID_NS + "name") for a in svc.findall("intent-filter/action")]
    assert actions == ["android.accessibilityservice.AccessibilityService"]
    meta = svc.find("meta-data")
    assert meta.get(ANDROID_NS + "name") == "android.accessibilityservice"
    assert meta.get(ANDROID_NS + "resource") == "@xml/reel_watch_service"


def test_service_config_is_instagram_only_and_cannot_tap_coordinates():
    tree = ET.parse(APP / "res" / "xml" / "reel_watch_service.xml")
    cfg = tree.getroot()
    assert cfg.tag == "accessibility-service"
    assert cfg.get(ANDROID_NS + "packageNames") == "com.instagram.android"
    assert cfg.get(ANDROID_NS + "canRetrieveWindowContent") == "true"
    # No gesture capability => the service physically cannot tap a coordinate.
    assert cfg.get(ANDROID_NS + "canPerformGestures") == "false"
    assert cfg.get(ANDROID_NS + "canTakeScreenshot") == "false"
    # Window-state only: no text-typed / content-changed surveillance.
    assert cfg.get(ANDROID_NS + "accessibilityEventTypes") == "typeWindowStateChanged"


def test_service_is_off_by_default():
    src = read(JAVA / "BubbleService.kt")
    assert 'private const val REEL_WATCH = "reel_watch_on"' in src
    assert "getBoolean(REEL_WATCH, false)" in src


def test_bubble_keeps_legacy_path_when_feature_is_off():
    src = read(JAVA / "BubbleService.kt")
    body = src.split("private fun saveInBackground()")[1].split("private fun refreshNotification")[0]
    assert "if (Prefs.reelWatchOn(this) && ReelWatchService.takeBubbleTap()) return" in body
    # The original three-step fallback must still be there, in order.
    assert body.index("QuickSaveActivity.intent(this)") < body.index("QuickSaveService.start(this, last)")
    assert body.index("QuickSaveService.start(this, last)") < body.index("QuickSaveService.notifyNeedLink(this)")


# --------------------------------------------------------------------------
# No coordinates, no loops, no stray taps
# --------------------------------------------------------------------------

@pytest.mark.parametrize("path", NEW_SOURCES, ids=lambda p: p.name)
def test_new_code_has_no_positional_tap_api(path):
    src = read(path)
    code = "\n".join(line for line in src.splitlines() if not line.strip().startswith(("*", "//", "/*")))
    forbidden = [
        "dispatchGesture",
        "GestureDescription",
        "StrokeDescription",
        "MotionEvent.obtain",
        "injectInputEvent",
        "clickAt",
        "tapAt",
        "getBoundsInScreen",
        "boundsInScreen",
        "setOnTouchListener",
    ]
    for token in forbidden:
        assert token not in code, f"{path.name} must not use {token}"
    # The single allowed global action is Back, used once to close the share
    # sheet after a successful Copy tap (1.66): it targets no control.
    assert code.count("performGlobalAction(") <= 1
    assert set(re.findall(r"GLOBAL_ACTION_\w+", code)) <= {"GLOBAL_ACTION_BACK"}
    # No x/y coordinate pairs feeding a tap.
    assert not re.search(r"\b(?:tap|click|touch)\w*\(\s*\d+\s*,\s*\d+", code, re.IGNORECASE)


def test_clicks_are_node_actions_only():
    src = read(JAVA / "ReelWatchService.kt")
    assert src.count("performAction(") == 1
    assert "AccessibilityNodeInfo.ACTION_CLICK" in src


def test_at_most_one_share_tap_and_one_copy_tap():
    src = read(JAVA / "ReelWatchService.kt")
    assert "if (shareTapped ||" in src
    assert "if (copyTapped ||" in src
    assert "shareTapped = true" in src and "copyTapped = true" in src
    # Bounded run: a deadline, a scan cap and a node cap.
    for token in ("RUN_BUDGET_MS", "MAX_SCANS", "MAX_NODES", "MAX_DEPTH"):
        assert token in src


def test_click_refuses_non_instagram_nodes():
    src = read(JAVA / "ReelWatchService.kt")
    click = src.split("private fun click(")[1].split("private fun readClipboard")[0]
    # Only Instagram, plus the system share-sheet packages for the Copy step.
    assert "pkg == ShareNodes.INSTAGRAM_PACKAGE" in click
    assert "ShareNodes.RESOLVER_PACKAGES" in click
    assert "if (!inScope) return false" in click
    # Share never allows the resolver; only the Copy step does.
    assert "if (!click(share.node))" in src
    assert "if (!click(copy.node, allowResolver = true))" in src


def test_fallback_toast_text_is_exact():
    strings = read(APP / "res" / "values" / "strings.xml")
    assert '<string name="reel_watch_need_link">انسخ رابط الريل أولاً</string>' in strings
    src = read(JAVA / "ReelWatchService.kt")
    assert "R.string.reel_watch_need_link" in src
    assert src.count("Toast.makeText") == 1


def test_ignores_accessibility_events_until_bubble_tap():
    src = read(JAVA / "ReelWatchService.kt")
    assert "override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit" in src


def test_resolver_and_protected_files_untouched():
    """Pins the v1.58 scope: the feature must not edit the resolver or the UI core.

    Skipped when the v1.57 base commit is not in the local clone (shallow CI
    checkout); the structural guards above still run everywhere.
    """
    base = "0b6728b415b62978af34ed91c02d226087ab29e4"
    has_base = subprocess.run(
        ["git", "cat-file", "-e", base + "^{commit}"],
        cwd=ROOT, capture_output=True, text=True,
    )
    if has_base.returncode != 0:
        pytest.skip("v1.57 base commit not present in this clone")
    changed = subprocess.run(
        ["git", "diff", "--name-only", base],
        cwd=ROOT, capture_output=True, text=True, check=True,
    ).stdout.split()
    protected = {
        "app/src/main/java/com/nada/app/NadaServer.kt",
        "app/src/main/java/com/nada/app/PageMediaSniffer.kt",
        "app/src/main/java/com/nada/app/BannerAds.kt",
        # 1.68 owns the download core: failures name their step and every
        # terminal path records shareable evidence. The listnr-only design
        # itself is unchanged (no fallback resolver exists).
        "app/src/main/java/com/nada/app/IgLinks.kt",
        "app/src/main/java/com/nada/app/QuickSaveActivity.kt",
        "app/src/main/java/com/nada/app/ShareCatcherActivity.kt",
        # 1.63 owns the trilingual WebView work, so app.js is no longer
        # protected here (the download core above still is).
        "app/src/main/assets/styles.css",
        "static/styles.css",
        "signing/nada.jks",
        "server.py",
    }
    assert protected.isdisjoint(changed), sorted(protected & set(changed))


def test_copy_click_climbs_deeper_sheets():
    """1.63: the Copy tap may climb up to 8 ancestors (deep sheet rows)."""
    src = read(JAVA / "ReelWatchService.kt")
    assert "private const val CLICK_PARENT_HOPS = 8" in src


def _span(src: str, start_marker: str) -> str:
    i = src.index(start_marker)
    j = src.index("{", i)
    depth = 0
    for p in range(j, len(src)):
        if src[p] == "{":
            depth += 1
        elif src[p] == "}":
            depth -= 1
            if depth == 0:
                return src[i:p + 1]
    raise AssertionError("unbalanced span")


def test_sheet_dialog_windows_are_searched():
    """1.65: the share sheet is a dialog window, and it must be searched.

    Instagram's sheet is TYPE_APPLICATION_PANEL, not TYPE_APPLICATION, so
    filtering by type skipped it: the sheet opened, "نسخ الرابط" sat on
    screen, and the Copy tap never happened (1.63 on-device report).
    """
    src = read(JAVA / "ReelWatchService.kt")
    span = _span(src, "private fun searchRoots()")
    assert "TYPE_APPLICATION" not in span
    assert "isSearchablePackage" in span
    assert "private const val MAX_ROOTS = 6" in src


def test_copy_click_allows_unknown_package():
    """1.65: a node that reports no package may still be clicked.

    The node was found inside a searched Instagram / resolver root, so a
    missing package name must not refuse the Copy tap.
    """
    src = read(JAVA / "ReelWatchService.kt")
    span = _span(src, "private fun click(")
    assert "pkg.isNullOrEmpty()" in span


def test_copy_walk_runs_when_share_found():
    """1.66: the tree walk must run whenever the Copy node is missing.

    In the sheet the bottom row also holds a "Share" label, so a found Share
    used to suppress the walk and the Copy node was never looked for a
    second way (1.65 on-device report: sheet open, "نسخ الرابط" on screen,
    no tap).
    """
    src = read(JAVA / "ReelWatchService.kt")
    span = _span(src, "private fun scan()")
    assert "if (found.first == null && found.second == null" not in span
    assert "if (found.second == null" in span
    assert "collectByIdWalk(roots)" in span


def test_copy_failure_reports_its_stage():
    """1.66: the give-up toast says where the run stopped.

    "Copy the link first" for every failure hid the cause; the next fix
    needs to know whether Copy was never found, found but untappable, or
    Share was never found.
    """
    src = read(JAVA / "ReelWatchService.kt")
    for marker in ("failStage", "copyClickFailed", "reel_watch_no_copy",
                   "reel_watch_copy_click_failed", "reel_watch_no_share"):
        assert marker in src, marker
    for values in ("values", "values-en", "values-fr"):
        strings = read(APP / "res" / values / "strings.xml")
        for name in ("reel_watch_no_copy", "reel_watch_copy_click_failed",
                     "reel_watch_no_share"):
            assert name in strings, (values, name)


def test_sheet_closes_after_copy():
    """1.66: after a successful Copy tap the sheet is closed with Back."""
    src = read(JAVA / "ReelWatchService.kt")
    span = _span(src, "private fun scan()")
    assert "performGlobalAction(GLOBAL_ACTION_BACK)" in span


def test_every_terminal_path_derives_a_stage():
    """1.67: the 7 s net, retryOrGiveUp and scheduleScan all reach readClipboard.

    Deriving the stage inside readClipboard (and again defensively in
    needLinkToast) means no caller can forget it. The pure stageFor rule is
    exercised on the JVM below.
    """
    src = read(JAVA / "ReelWatchService.kt")
    read_clip = _span(src, "private fun readClipboard()")
    toast = _span(src, "private fun needLinkToast()")
    for span in (read_clip, toast):
        assert "BubbleDiagnostics.stageFor(phase, copyClickFailed)" in span
    # The three bypass callers still exist and still terminate through it.
    assert "handler.postDelayed({ readClipboard() }, RUN_BUDGET_MS)" in src
    for name in ("private fun retryOrGiveUp()", "private fun scheduleScan()"):
        assert "readClipboard()" in _span(src, name)
    # The safety rule itself is pure and tested without Android.
    diag = read(JAVA / "BubbleDiagnostics.kt")
    assert 'copyClickFailed -> "copy_click"' in diag
    assert 'phase == ShareNodes.PHASE_WAIT_SHEET -> "no_copy"' in diag
    assert 'else -> "no_share"' in diag


def test_give_up_paths_persist_run_evidence():
    src = read(JAVA / "ReelWatchService.kt")
    toast = _span(src, "private fun needLinkToast()")
    assert 'persistDiagnostics("need_link")' in toast
    persist = _span(src, "private fun persistDiagnostics(")
    assert "getExternalFilesDir(null)" in persist
    assert "BubbleDiagnostics.FILE_NAME" in persist
    assert "writeText(diag.report())" in persist
    assert "diag.summary()" in persist
    # Every observed run records roots, both Copy passes and click attempts.
    assert "diag.observeRoots(" in src
    assert 'diag.observeCopy("query"' in src
    assert 'diag.observeCopy("walk"' in src
    assert "diag.beginClick(" in src
    assert "diag.observeClick(" in src
    assert "diag.queryTime(" in src and "diag.walkTime(" in src


def test_debug_row_is_one_line_and_shareable():
    a = read(APP / "assets" / "index.html")
    assert a.count('id="reelWatchDebug"') == 1
    assert '<section' not in a.split('id="reelWatchDebug"')[1].split("</article>")[0]
    js = read(APP / "assets" / "reelwatch.js")
    assert "lastBubbleRun" in js and "shareBubbleRun" in js
    main = read(JAVA / "MainActivity.kt")
    assert "fun lastBubbleRun(): String" in main
    assert "fun shareBubbleRun()" in main
    assert "Intent.ACTION_SEND" in main
    paths = read(APP / "res" / "xml" / "file_paths.xml")
    assert '<external-files-path name="bubble-debug" path="." />' in paths
    for values in ("values", "values-en", "values-fr"):
        assert "reel_watch_share_debug" in read(APP / "res" / values / "strings.xml")


def test_diagnostics_do_not_change_tap_safety_or_budget():
    src = read(JAVA / "ReelWatchService.kt")
    assert "private const val RUN_BUDGET_MS = 7000L" in src
    assert "private const val FAST_SCAN_GAP_MS = 100L" in src
    assert "private const val MAX_SCANS = 60" in src
    assert src.count("performAction(") == 1
    assert src.count("Toast.makeText") == 1
    nodes = read(JAVA / "ShareNodes.kt")
    assert "looksLikeCopyLabel" not in _span(nodes, "fun nextAction(")
    assert "looksLikeCopyLabel" not in _span(src, "private fun scan()")
    assert "looksLikeCopyLabel" not in _span(src, "private fun click(")


def test_version_bumped_consistently():
    gradle = read(ROOT / "app" / "build.gradle.kts")
    # Derived from Gradle so a later release bump does not need a test edit.
    name = re.search(r'versionName = "([^"]+)"', gradle).group(1)
    code = int(re.search(r"versionCode = (\d+)", gradle).group(1))
    assert f"versionCode = {code}" in gradle
    assert f'versionName = "{name}"' in gradle
    manifest = json.loads(read(ROOT / "version.json"))
    assert manifest["version"] == name
    assert manifest["versionCode"] == code
    assert manifest["apkUrl"].endswith("/releases/latest/download/nada-debug.apk")


def test_card_is_additive_and_mirrored():
    a = read(APP / "assets" / "index.html")
    b = read(ROOT / "static" / "index.html")
    assert a == b
    assert 'id="reelWatchCard"' in a
    assert '<script src="/reelwatch.js"></script>' in a
    # Existing cards and scripts are still present.
    for marker in ('id="updateCheckBtn"', 'id="bubbleBtn"', 'id="batteryBtn"', '<script src="/app.js">'):
        assert marker in a
    assert read(APP / "assets" / "reelwatch.js") == read(ROOT / "static" / "reelwatch.js")


def test_no_redaction_artifacts_in_sources():
    for path in list(JAVA.glob("*.kt")) + [APP / "assets" / "reelwatch.js"]:
        assert "REDACTED" not in read(path), path


# --------------------------------------------------------------------------
# Behavioural tests: compile the pure Kotlin logic and run it on a JVM
# --------------------------------------------------------------------------

KOTLIN_LIB = os.environ.get("NADA_KOTLIN_LIB", "/home/user/.cache/kt/package/lib")
JAVA_BIN = os.environ.get(
    "NADA_JAVA",
    "/home/user/.cache/venv-jdk/lib/python3.11/site-packages/jdk4py/java-runtime/bin/java",
)


def _toolchain():
    lib = Path(KOTLIN_LIB)
    compiler = lib / "kotlin-compiler.jar"
    stdlib = lib / "kotlin-stdlib.jar"
    java = Path(JAVA_BIN) if Path(JAVA_BIN).exists() else shutil.which("java")
    if not (compiler.exists() and stdlib.exists() and java):
        return None
    return lib, str(java)


@pytest.fixture(scope="module")
def share_nodes_run():
    tools = _toolchain()
    if tools is None:
        pytest.skip("no local Kotlin compiler / JDK; source-level guards still ran")
    lib, java = tools
    harness = """
import com.nada.app.ShareNodes
import com.nada.app.BubbleDiagnostics
import com.nada.app.DownloadDiagnostics

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual.toString() != expected.toString()) {
        throw AssertionError("$name: expected $expected got $actual")
    }
    println("ok $name")
}

fun main() {
    // --- Share matching -------------------------------------------------
    check("share exact en", ShareNodes.shareScore("Share", null), 3)
    check("share exact ar", ShareNodes.shareScore("مشاركة", null), 3)
    check("share ar reel", ShareNodes.shareScore("مشاركة الريل", null), 3)
    check("share id", ShareNodes.shareScore(null, "com.instagram.android:id/row_feed_button_share"), 2)
    check("share prefix", ShareNodes.shareScore("Share reel to a friend", null), 1)
    check("share exact fr", ShareNodes.shareScore("Partager", null), 3)
    check("share prefix fr", ShareNodes.shareScore("Partager le reel à un ami", null), 1)
    check("share unrelated", ShareNodes.shareScore("Settings", null), 0)
    check("share like button", ShareNodes.shareScore("Like", null), 0)
    check("share null", ShareNodes.shareScore(null, null), 0)

    // --- Copy matching --------------------------------------------------
    check("copy exact en", ShareNodes.copyScore("Copy link", null, null), 4)
    check("copy desc en", ShareNodes.copyScore(null, "Copy link", null), 4)
    check("copy exact ar", ShareNodes.copyScore("نسخ الرابط", null, null), 4)
    check("copy ar reel", ShareNodes.copyScore("نسخ رابط الريل", null, null), 4)
    check("copy id", ShareNodes.copyScore(null, null, "com.instagram.android:id/copy_link"), 3)
    check("copy prefix", ShareNodes.copyScore("Copy link to reel", null, null), 4)
    check("copy bare", ShareNodes.copyScore("Copy", null, null), 1)
    check("copy exact fr", ShareNodes.copyScore("Copier le lien", null, null), 4)
    check("copy unrelated", ShareNodes.copyScore("Save", null, null), 0)
    check("copy null", ShareNodes.copyScore(null, null, null), 0)

    // --- Normalisation --------------------------------------------------
    check("norm case", ShareNodes.normalize("  COPY   Link  "), "copy link")
    check("norm tatweel", ShareNodes.normalize("نسخ الراــــبط"), "نسخ الرابط")
    check("norm diacritics", ShareNodes.normalize("نَسْخُ الرّابِط"), "نسخ الرابط")
    check("norm null", ShareNodes.normalize(null), "")

    // --- Decision table -------------------------------------------------
    val find = ShareNodes.PHASE_FIND_SHARE
    val wait = ShareNodes.PHASE_WAIT_SHEET
    check("open reel taps share", ShareNodes.nextAction(find, 0, 3), ShareNodes.Step.SHARE)
    check("share id taps", ShareNodes.nextAction(find, 0, 2), ShareNodes.Step.SHARE)
    check("weak share taps", ShareNodes.nextAction(find, 0, 1), ShareNodes.Step.SHARE)
    check("nothing found gives up", ShareNodes.nextAction(find, 0, 0), ShareNodes.Step.GIVE_UP)
    check("bare copy on reel is ignored", ShareNodes.nextAction(find, 1, 0), ShareNodes.Step.GIVE_UP)
    check("copy id on reel is ignored", ShareNodes.nextAction(find, 2, 0), ShareNodes.Step.GIVE_UP)
    check("already open sheet copies", ShareNodes.nextAction(find, 4, 3), ShareNodes.Step.COPY)
    check("sheet waits", ShareNodes.nextAction(wait, 0, 3), ShareNodes.Step.WAIT)
    check("sheet bare copy waits", ShareNodes.nextAction(wait, 1, 0), ShareNodes.Step.WAIT)
    check("sheet prefix copy waits", ShareNodes.nextAction(wait, 2, 0), ShareNodes.Step.WAIT)
    check("sheet copy id accepted", ShareNodes.nextAction(wait, 3, 0), ShareNodes.Step.COPY)
    check("sheet exact copy accepted", ShareNodes.nextAction(wait, 4, 0), ShareNodes.Step.COPY)
    check("unknown phase gives up", ShareNodes.nextAction(99, 4, 3), ShareNodes.Step.GIVE_UP)

    // --- Terminal stage derivation (covers every readClipboard caller) ----
    check("stage find", BubbleDiagnostics.stageFor(find, false), "no_share")
    check("stage sheet", BubbleDiagnostics.stageFor(wait, false), "no_copy")
    check("stage click find", BubbleDiagnostics.stageFor(find, true), "copy_click")
    check("stage click sheet", BubbleDiagnostics.stageFor(wait, true), "copy_click")
    check("stage unknown", BubbleDiagnostics.stageFor(99, false), "no_share")

    // --- Diagnostic-only label hint never feeds the decision table --------
    check("hint ar", ShareNodes.looksLikeCopyLabel("نسخ الرابط", null), true)
    check("hint en", ShareNodes.looksLikeCopyLabel(null, "Copy link"), true)
    check("hint fr", ShareNodes.looksLikeCopyLabel("Copier le lien", null), true)
    check("hint unrelated", ShareNodes.looksLikeCopyLabel("Save", null), false)
    check("hint null", ShareNodes.looksLikeCopyLabel(null, null), false)
    check("hint does not score", ShareNodes.copyScore("انسخ", null, null), 0)
    check("hint does not tap", ShareNodes.nextAction(wait, 0, 0), ShareNodes.Step.WAIT)

    // --- Evidence counters and bounded report -----------------------------
    val d = BubbleDiagnostics()
    d.beginScan(1, wait, 120)
    d.observeRoots(listOf("com.instagram.android:type1", "com.instagram.android:type2"), false, false)
    d.queryTime(15)
    d.observeCopy("query", "نسخ الرابط", null, 4, false)
    d.walkTime(30)
    d.observeCopy("walk", null, "Copy link", 4, true)
    d.beginClick(copy = true)
    d.observeClick(onSelf = false, hops = 2, result = false)
    d.elapsedMs = 7000
    d.stage = BubbleDiagnostics.stageFor(wait, true)
    d.outcome = "need_link"
    check("roots counted", d.roots, 2)
    check("query best keeps usable gate", d.bestCopy, 0)
    check("walk best", d.walkCopy, 4)
    check("filtered label seen", d.copySeen, 2)
    check("filtered count", d.copyFiltered, 1)
    check("copy click attempted", d.copyClickTried, true)
    check("click result", d.clickResult, "false")
    check("click ancestor", d.clickOn, "ancestor2")
    val summary = d.summary()
    check("summary one line", summary.contains("\\n"), false)
    check("summary roots", summary.contains("roots=2"), true)
    check("summary stage", summary.contains("stage=copy_click"), true)
    val report = d.report()
    check("report has scan", report.contains("scan=1 phase=1 at=120 roots=2"), true)
    check("report has windows", report.contains("com.instagram.android:type2"), true)
    check("report has label", report.contains("نسخ الرابط"), true)
    check("report has click", report.contains("clickOn=ancestor2"), true)
    check("report has outcome", report.contains("outcome=need_link"), true)
    for (i in 2..80) d.beginScan(i, wait, i.toLong())
    check("history bounded", d.report().lines().count { it.startsWith("scan=") }, 60)
    // --- Download diagnostics: every failure names its step (1.68) -------
    check("classify dns", DownloadDiagnostics.classify(java.net.UnknownHostException("host")), "dns")
    check("classify timeout", DownloadDiagnostics.classify(java.net.SocketTimeoutException("timeout")), "timeout")
    check("classify call timeout", DownloadDiagnostics.classify(java.io.InterruptedIOException("timeout")), "timeout")
    check("classify connect", DownloadDiagnostics.classify(java.net.ConnectException("failed to connect")), "connect")
    check("classify tls", DownloadDiagnostics.classify(javax.net.ssl.SSLHandshakeException("bad cert")), "tls")
    check("classify read", DownloadDiagnostics.classify(java.io.EOFException("unexpected end of stream")), "read")
    check("classify io", DownloadDiagnostics.classify(java.io.IOException("boom")), "io")
    val g = DownloadDiagnostics()
    g.url = "https://www.instagram.com/reel/AbCdEfGhIj/"
    g.begin(DownloadDiagnostics.STEP_START)
    g.begin(DownloadDiagnostics.STEP_LISTNR)
    g.listnrDetail = "timeout \\u00d72"
    g.note("listnr timeout (attempt 1)")
    g.begin(DownloadDiagnostics.STEP_CDN)
    g.cdnHost = "scontent.cdninstagram.com"
    g.cdnDetail = "HTTP 403"
    g.finish("fail:cdn")
    check("stage kept", g.stage, DownloadDiagnostics.STEP_CDN)
    check("outcome names step", g.outcome, "fail:cdn")
    check("elapsed recorded", g.elapsedMs >= 0, true)
    val gsum = g.summary()
    check("sum one line", gsum.contains("\\n"), false)
    check("sum stage", gsum.contains("stage=cdn"), true)
    check("sum listnr", gsum.contains("listnr=timeout"), true)
    check("sum cdn host", gsum.contains("scontent.cdninstagram.com HTTP 403"), true)
    check("sum bytes", gsum.contains("bytes=0"), true)
    val grep = g.report()
    check("rep url", grep.contains("reel/AbCdEfGhIj/"), true)
    check("rep step order", grep.contains("step: begin listnr"), true)
    check("rep note", grep.contains("note: listnr timeout (attempt 1)"), true)
    check("rep store blank", grep.contains("store=-"), true)
    g.finish("ok")
    check("first outcome wins", g.outcome, "fail:cdn")
    check("notes cap on fresh run", run {
        val h = DownloadDiagnostics()
        for (i in 1..50) h.note("n$i")
        h.report().lines().count { it.startsWith("note: ") }
    }, 12)
    check("steps cap on fresh run", run {
        val h = DownloadDiagnostics()
        for (i in 1..50) h.begin("s$i")
        h.report().lines().count { it.startsWith("step: ") }
    }, 24)
    println("ALL PASS")
}
"""
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        src = tmp / "Harness.kt"
        src.write_text(harness, encoding="utf-8")
        out = tmp / "out"
        cp = os.pathsep.join(str(p) for p in lib.glob("*.jar"))
        compile_run = subprocess.run(
            [java, "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
             str(JAVA / "ShareNodes.kt"), str(JAVA / "BubbleDiagnostics.kt"),
             str(JAVA / "DownloadDiagnostics.kt"), str(src),
             "-cp", str(lib / "kotlin-stdlib.jar"), "-no-stdlib", "-jvm-target", "17",
             "-d", str(out)],
            capture_output=True, text=True, timeout=600,
        )
        assert compile_run.returncode == 0, compile_run.stdout + compile_run.stderr
        run = subprocess.run(
            [java, "-cp", os.pathsep.join([str(out), str(lib / "kotlin-stdlib.jar")]), "HarnessKt"],
            capture_output=True, text=True, timeout=120,
        )
        assert run.returncode == 0, run.stdout + run.stderr
        return run.stdout


def test_kotlin_label_and_decision_logic(share_nodes_run):
    assert "ALL PASS" in share_nodes_run
    assert share_nodes_run.count("ok ") >= 60


def test_stale_clipboard_cannot_download_the_wrong_reel():
    """After Copy link we require a clip Instagram wrote just now."""
    src = read(JAVA / "ReelWatchService.kt")
    assert "copyTappedAt = System.currentTimeMillis()" in src
    assert "clip.description?.timestamp" in src
    assert "stamped + CLIP_SKEW_MS < copyTappedAt" in src


def test_clipboard_focus_borrow_uses_accessibility_overlay():
    """Android 10+ only lets the focused uid read the clipboard."""
    src = read(JAVA / "ReelWatchService.kt")
    assert "TYPE_ACCESSIBILITY_OVERLAY" in src
    assert "FLAG_NOT_TOUCHABLE" in src
    # A focusable window must NOT set FLAG_NOT_FOCUSABLE, or it cannot take focus.
    assert "FLAG_NOT_FOCUSABLE" not in src
    assert "addOnWindowFocusChangeListener" in src
    assert "FOCUS_TIMEOUT_MS" in src
    # The overlay is always removed again.
    assert "removeView(view)" in src


def test_node_search_is_bounded_and_uses_cheap_query_first():
    src = read(JAVA / "ReelWatchService.kt")
    assert "findAccessibilityNodeInfosByText" in src
    assert "collectByQuery(roots)" in src
    assert "collectByIdWalk(roots)" in src
    assert "WALK_SLACK_MS" in src
