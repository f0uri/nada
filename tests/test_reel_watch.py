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

NEW_SOURCES = [JAVA / "ReelWatchService.kt", JAVA / "ShareNodes.kt"]


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
        "performGlobalAction",
        "clickAt",
        "tapAt",
        "getBoundsInScreen",
        "boundsInScreen",
        "setOnTouchListener",
    ]
    for token in forbidden:
        assert token not in code, f"{path.name} must not use {token}"
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
        "app/src/main/java/com/nada/app/ListnrService.kt",
        "app/src/main/java/com/nada/app/InstagramClient.kt",
        "app/src/main/java/com/nada/app/IgLinks.kt",
        "app/src/main/java/com/nada/app/QuickSaveService.kt",
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
             str(JAVA / "ShareNodes.kt"), str(src),
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
    assert share_nodes_run.count("ok ") >= 30


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
