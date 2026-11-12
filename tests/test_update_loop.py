"""Offline regression guards for the 1.57/1.58 update loop (fixed in 1.59).

Source-level contract checks always run. The behavioural layer compiles the pure
Kotlin policy and asserts it on a JVM, and is skipped when no toolchain exists.
Nothing here touches the network or a device.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app" / "src" / "main"
JAVA = APP / "java" / "com" / "nada" / "app"
UPDATER = JAVA / "AppUpdateManager.kt"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


# ---------------------------------------------------------------------------
# Root cause 1: "Later" was never persisted
# ---------------------------------------------------------------------------

def test_later_is_persisted_not_a_no_op():
    src = read(UPDATER)
    # The old code was .setNegativeButton(R.string.update_later, null)
    assert "setNegativeButton(R.string.update_later, null)" not in src.split("private fun showAvailable")[1].split("private fun showUpToDate")[0]
    assert "state.rememberLater(update.code)" in src
    assert "setOnCancelListener { state.rememberLater(update.code) }" in src


def test_update_state_survives_process_death():
    src = read(JAVA / "UpdateState.kt")
    assert "getSharedPreferences" in src
    for key in ("later_code", "later_day", "prompted_code", "prompted_day"):
        assert f'"{key}"' in src


def test_prompt_is_capped_once_per_version_per_day():
    src = read(UPDATER)
    assert "UpdatePolicy.shouldShowDialog(" in src
    assert "if (!manual) state.rememberPrompt(found.code)" in src


def test_no_dialog_while_download_or_install_in_progress():
    src = read(UPDATER)
    assert "inProgress = busy.get()" in src
    policy = read(JAVA / "UpdatePolicy.kt")
    assert "if (inProgress) return false" in policy


# ---------------------------------------------------------------------------
# Root cause 2: busy leaked on the install-permission path -> stuck 100%
# ---------------------------------------------------------------------------

def test_busy_released_on_every_early_return():
    src = read(UPDATER)
    install = src.split("private fun install(")[1].split("private fun onInstallTimeout")[0]
    permission_block = install.split("if (!canInstall()) {")[1].split("return")[0]
    assert "busy.set(false)" in permission_block, "permission path must release busy"
    assert "dismiss()" in permission_block, "permission path must close the progress dialog"


def test_progress_dialog_is_cancelable():
    src = read(UPDATER)
    progress = src.split("private fun showProgress(")[1].split("private fun showError")[0]
    assert ".setCancelable(true)" in progress
    assert ".setCancelable(false)" not in progress


def test_failures_show_error_with_retry():
    src = read(UPDATER)
    assert "R.string.update_retry" in src
    assert "private fun showError(" in src
    # download failure and install failure both route through showError
    assert src.count("showError(") >= 5
    strings = read(APP / "res" / "values" / "strings.xml")
    assert '<string name="update_retry">إعادة المحاولة</string>' in strings


def test_install_timeout_cannot_leave_a_frozen_dialog():
    src = read(UPDATER)
    assert "INSTALL_TIMEOUT_MS" in src
    assert "postDelayed({ onInstallTimeout(" in src
    timeout = src.split("private fun onInstallTimeout(")[1].split("fun onInstallResult")[0]
    assert "dismiss()" in timeout
    assert "showError(" in timeout


def test_install_result_always_clears_busy_and_dialog():
    src = read(UPDATER)
    result = src.split("fun onInstallResult(")[1].split("private fun describe")[0]
    assert "installing = false" in result
    assert "busy.set(false)" in result
    assert "dismiss()" in result


def test_pending_user_action_is_handled_once():
    src = read(JAVA / "UpdateInstallActivity.kt")
    assert "PackageInstaller.STATUS_PENDING_USER_ACTION" in src
    assert "Intent.EXTRA_INTENT" in src
    assert "startActivity(confirmation" in src
    # every branch reports back so the dialog cannot hang
    assert src.count("manager.onInstallResult(") >= 4


def test_session_params_and_permissions_in_place():
    src = read(UPDATER)
    assert "USER_ACTION_NOT_REQUIRED" in src
    assert "PendingIntent.FLAG_MUTABLE" in src
    manifest = read(APP / "AndroidManifest.xml")
    assert "android.permission.REQUEST_INSTALL_PACKAGES" in manifest
    assert "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION" in manifest


def test_session_is_abandoned_on_failure():
    src = read(UPDATER)
    assert "abandonSession(sessionId)" in src


# ---------------------------------------------------------------------------
# Root cause 3: download/verification correctness
# ---------------------------------------------------------------------------

def test_redirects_are_followed():
    src = read(UPDATER)
    assert ".followRedirects(true)" in src
    assert ".followSslRedirects(true)" in src


def test_apk_is_verified_before_install():
    src = read(UPDATER)
    assert "verifyHeader(temp)" in src
    assert "UpdatePolicy.looksLikeApk(head)" in src
    assert "MIN_APK" in src
    assert "validateApk(target, update)" in src
    # verification happens before the file is staged for install
    assert src.index("validateApk(target, update)") < src.index("pendingApk = target")


def test_truncated_download_is_rejected():
    src = read(UPDATER)
    assert 'if (total > 0 && seen != total) throw IOException("truncated")' in src


def test_manifest_lag_cannot_break_the_update():
    """raw.githubusercontent.com lags; an APK newer than the manifest is valid."""
    policy = read(JAVA / "UpdatePolicy.kt")
    assert "apkCode >= manifestCode" in policy
    src = read(UPDATER)
    assert "UpdatePolicy.acceptApk(code, update.code, installedCode())" in src
    assert "code != update.code" not in src


def test_installed_version_code_is_really_compared():
    src = read(UPDATER)
    assert "info.longVersionCode" in src
    assert "found.code > installed" in src


# ---------------------------------------------------------------------------
# Requirement: the check box shows exactly one line
# ---------------------------------------------------------------------------

def test_check_box_has_only_the_two_allowed_lines():
    js = read(APP / "assets" / "update.js")
    assert 'yes: "يوجد تحديث"' in js
    assert 'no: "لا يوجد تحديث"' in js
    # the body is set to exactly one of the two strings, nothing concatenated
    assert "body.textContent = available ? t.yes : t.no;" in js
    assert "changelog" not in js
    assert "update_keep_data" not in js


def test_check_box_is_mirrored_and_wired():
    assert read(APP / "assets" / "update.js") == read(ROOT / "static" / "update.js")
    a = read(APP / "assets" / "index.html")
    assert a == read(ROOT / "static" / "index.html")
    assert '<p id="updateEntryBody">لا يوجد تحديث</p>' in a
    main = read(JAVA / "MainActivity.kt")
    assert "fun renderUpdateState(hasUpdate: Boolean)" in main
    assert "window.nadaUpdateState" in main
    assert "fun updateAvailable(): Boolean" in main


def test_dialog_description_is_one_short_line():
    src = read(UPDATER)
    available = src.split("private fun showAvailable(")[1].split("private fun showUpToDate")[0]
    assert "update.changelog" not in available
    assert "R.string.update_keep_data" not in available
    assert "ScrollView" not in available


def test_no_secret_is_logged():
    for path in (UPDATER, JAVA / "UpdateInstallActivity.kt", JAVA / "UpdateState.kt"):
        src = read(path)
        assert "Log." not in src
        assert "println(" not in src
        assert "printStackTrace" not in src


def test_protected_files_untouched():
    base = "fc8ed60264652df31119ae97b5dfbf7a559b6350"
    if subprocess.run(["git", "cat-file", "-e", base + "^{commit}"], cwd=ROOT,
                      capture_output=True).returncode != 0:
        pytest.skip("base commit not in this clone")
    changed = set(subprocess.run(
        ["git", "diff", "--name-only", base], cwd=ROOT,
        capture_output=True, text=True, check=True).stdout.split())
    protected = {
        "app/src/main/java/com/nada/app/NadaServer.kt",
        "app/src/main/java/com/nada/app/PageMediaSniffer.kt",
        "app/src/main/java/com/nada/app/BannerAds.kt",
        "app/src/main/java/com/nada/app/ListnrService.kt",
        "app/src/main/java/com/nada/app/InstagramClient.kt",
        "app/src/main/java/com/nada/app/ReelWatchService.kt",
        "app/src/main/java/com/nada/app/ShareNodes.kt",
        "app/src/main/java/com/nada/app/BubbleService.kt",
        "app/src/main/java/com/nada/app/QuickSaveService.kt",
        "app/src/main/assets/reelwatch.js",
        "app/src/main/assets/app.js",
        "app/src/main/assets/styles.css",
        "signing/nada.jks",
        "server.py",
    }
    assert protected.isdisjoint(changed), sorted(protected & changed)


def test_version_bumped_to_59():
    gradle = read(ROOT / "app" / "build.gradle.kts")
    assert "versionCode = 59" in gradle
    assert 'versionName = "1.59"' in gradle
    manifest = json.loads(read(ROOT / "version.json"))
    assert manifest["version"] == "1.59"
    assert manifest["versionCode"] == 59


# ---------------------------------------------------------------------------
# Behavioural layer: run the real Kotlin policy on a JVM
# ---------------------------------------------------------------------------

KOTLIN_LIB = os.environ.get("NADA_KOTLIN_LIB", "/home/user/.cache/kt/package/lib")
JAVA_BIN = os.environ.get(
    "NADA_JAVA",
    "/home/user/.cache/venv-jdk/lib/python3.11/site-packages/jdk4py/java-runtime/bin/java",
)


@pytest.fixture(scope="module")
def policy_run():
    lib = Path(KOTLIN_LIB)
    java = Path(JAVA_BIN) if Path(JAVA_BIN).exists() else shutil.which("java")
    if not ((lib / "kotlin-compiler.jar").exists() and (lib / "kotlin-stdlib.jar").exists() and java):
        pytest.skip("no local Kotlin compiler / JDK; source guards still ran")
    harness = """
import com.nada.app.UpdatePolicy

var passed = 0
fun check(name: String, actual: Any?, expected: Any?) {
    if (actual.toString() != expected.toString()) throw AssertionError("$name: want $expected got $actual")
    passed++
}

fun main() {
    val today = UpdatePolicy.dayKey(2026, 316)
    val tomorrow = UpdatePolicy.dayKey(2026, 317)

    // Nothing newer -> never prompt.
    check("same version", UpdatePolicy.shouldShowDialog(58,58,0,0,0,0,today,false,false), false)
    check("older manifest", UpdatePolicy.shouldShowDialog(57,58,0,0,0,0,today,false,false), false)

    // THE REPORTED BUG: every cold start re-prompted.
    check("first cold start prompts", UpdatePolicy.shouldShowDialog(59,57,0,0,0,0,today,false,false), true)
    check("second entry same day silent", UpdatePolicy.shouldShowDialog(59,57,0,0,59,today,today,false,false), false)
    check("third entry same day silent", UpdatePolicy.shouldShowDialog(59,57,0,0,59,today,today,false,false), false)
    check("next day may prompt", UpdatePolicy.shouldShowDialog(59,57,0,0,59,today,tomorrow,false,false), true)

    // Later is remembered for that version, for that day.
    check("later same day silent", UpdatePolicy.shouldShowDialog(59,57,59,today,0,0,today,false,false), false)
    check("later next day allowed", UpdatePolicy.shouldShowDialog(59,57,59,today,0,0,tomorrow,false,false), true)
    check("later for older version ignored", UpdatePolicy.shouldShowDialog(60,57,59,today,0,0,today,false,false), true)

    // Manual check always answers, but never during a download/install.
    check("manual after later", UpdatePolicy.shouldShowDialog(59,57,59,today,59,today,today,true,false), true)
    check("manual while busy", UpdatePolicy.shouldShowDialog(59,57,0,0,0,0,today,true,true), false)
    check("auto while busy", UpdatePolicy.shouldShowDialog(59,57,0,0,0,0,today,false,true), false)
    check("busy even if newer", UpdatePolicy.shouldShowDialog(99,57,0,0,0,0,today,false,true), false)

    // After installing, the dialog must never return for that version.
    check("installed now current", UpdatePolicy.shouldShowDialog(59,59,0,0,59,today,today,false,false), false)
    check("installed manual current", UpdatePolicy.shouldShowDialog(59,59,0,0,0,0,today,true,false), false)

    // APK acceptance tolerates manifest lag but never downgrades.
    check("exact match", UpdatePolicy.acceptApk(59,59,57), true)
    check("manifest lagging", UpdatePolicy.acceptApk(60,59,57), true)
    check("apk older than manifest", UpdatePolicy.acceptApk(58,59,57), false)
    check("apk equals installed", UpdatePolicy.acceptApk(57,57,57), false)
    check("apk older than installed", UpdatePolicy.acceptApk(56,59,57), false)

    // ZIP magic rejects an HTML error page.
    check("apk magic", UpdatePolicy.looksLikeApk(byteArrayOf(0x50,0x4B,0x03,0x04)), true)
    check("html page", UpdatePolicy.looksLikeApk("<!DO".toByteArray()), false)
    check("empty file", UpdatePolicy.looksLikeApk(byteArrayOf()), false)
    check("short file", UpdatePolicy.looksLikeApk(byteArrayOf(0x50,0x4B)), false)

    // Progress never shows 100 before the last byte.
    check("0 of 100", UpdatePolicy.percent(0,100), 0)
    check("half", UpdatePolicy.percent(50,100), 50)
    check("99.5 caps at 99", UpdatePolicy.percent(995,1000), 99)
    check("complete", UpdatePolicy.percent(100,100), 100)
    check("unknown total", UpdatePolicy.percent(500,0), 0)
    check("unknown total large", UpdatePolicy.percent(999999,-1), 0)

    println("ASSERTIONS=$passed")
    println("ALL PASS")
}
"""
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        src = tmp / "Harness.kt"
        src.write_text(harness, encoding="utf-8")
        out = tmp / "out"
        cp = os.pathsep.join(str(p) for p in lib.glob("*.jar"))
        comp = subprocess.run(
            [str(java), "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
             str(JAVA / "UpdatePolicy.kt"), str(src),
             "-cp", str(lib / "kotlin-stdlib.jar"), "-no-stdlib", "-jvm-target", "17",
             "-d", str(out)],
            capture_output=True, text=True, timeout=600)
        assert comp.returncode == 0, comp.stdout + comp.stderr
        run = subprocess.run(
            [str(java), "-cp", os.pathsep.join([str(out), str(lib / "kotlin-stdlib.jar")]), "HarnessKt"],
            capture_output=True, text=True, timeout=120)
        assert run.returncode == 0, run.stdout + run.stderr
        return run.stdout


def test_update_policy_behaviour(policy_run):
    assert "ALL PASS" in policy_run
    count = int(re.search(r"ASSERTIONS=(\d+)", policy_run).group(1))
    assert count >= 30
