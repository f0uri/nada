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
    available = src.split("private fun showAvailable(")[1].split("private fun showUpToDate")[0]
    assert "state.rememberLater(update.code)" in available
    # The glassy status dialog forwards its cancel to the caller's onCancel.
    assert "setOnCancelListener { onCancel?.invoke() }" in src


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
    install = src.split("private fun install(")[1].split("private fun installWithSystemInstaller")[0]
    permission_block = install.split("if (!canInstall()) {")[1].split("return")[0]
    assert "busy.set(false)" in permission_block, "permission path must release busy"
    assert "dismiss()" in permission_block, "permission path must close the progress dialog"


def test_progress_dialog_is_cancelable():
    src = read(UPDATER)
    progress = src.split("private fun showProgress(")[1].split("private fun showError")[0]
    assert ".setCancelable(true)" in progress
    assert ".setCancelable(false)" not in progress


def test_progress_dialog_is_glassy():
    src = read(UPDATER)
    assert "R.layout.dialog_update_glassy" in src
    assert "R.id.update_percent" in src
    layout = read(APP / "res" / "layout" / "dialog_update_glassy.xml")
    assert "update_percent" in layout
    assert "update_bar" in layout
    assert "update_status" in layout
    assert "update_card_glassy" in layout
    assert "update_progress_gold" in layout
    card = read(APP / "res" / "drawable" / "update_card_glassy.xml")
    assert "D4AF37" in card
    progress = read(APP / "res" / "drawable" / "update_progress_gold.xml")
    assert "D4AF37" in progress


def test_update_status_dialog_is_glassy():
    src = read(UPDATER)
    helper = src.split("private fun showStatusDialog(")[1].split("private fun download(")[0]
    assert "R.layout.dialog_update_status_glassy" in helper
    assert "ColorDrawable(Color.TRANSPARENT)" in helper
    assert "setCancelable(true)" in helper
    available = src.split("private fun showAvailable(")[1].split("private fun showUpToDate")[0]
    assert "showStatusDialog(" in available
    assert "download(activity, update)" in available
    assert "rememberLater(update.code)" in available
    up_to_date = src.split("private fun showUpToDate(")[1].split("private fun showStatusDialog(")[0]
    assert "showStatusDialog(" in up_to_date
    layout = read(APP / "res" / "layout" / "dialog_update_status_glassy.xml")
    assert "update_status_title" in layout
    assert "update_status_message" in layout
    assert "update_status_primary" in layout
    assert "update_status_secondary" in layout
    assert "update_card_glassy" in layout
    assert "update_button_gold" in layout
    button = read(APP / "res" / "drawable" / "update_button_gold.xml")
    assert "D4AF37" in button


def test_failures_show_error_with_retry():
    src = read(UPDATER)
    assert "R.string.update_retry" in src
    assert "private fun showError(" in src
    # download failure and install failure both route through showError
    assert src.count("showError(") >= 5
    strings = read(APP / "res" / "values" / "strings.xml")
    assert '<string name="update_retry">إعادة المحاولة</string>' in strings



def test_update_installs_via_system_installer():
    src = read(UPDATER)
    install = src.split("private fun install(")[1].split("private fun installWithSystemInstaller")[0]
    # The install step hands the APK to the system installer (Reel Checker path).
    assert "installWithSystemInstaller(activity, apk)" in install
    assert "FileProvider.getUriForFile" in src
    assert "Intent.ACTION_VIEW" in src
    assert '"application/vnd.android.package-archive"' in src
    assert "FLAG_GRANT_READ_URI_PERMISSION" in src
    manifest = read(APP / "AndroidManifest.xml")
    assert "androidx.core.content.FileProvider" in manifest
    assert "com.nada.app.fileprovider" in manifest
    paths = read(APP / "res" / "xml" / "file_paths.xml")
    assert "cache-path" in paths
    assert 'path="updates/"' in paths


def test_no_silent_session_machinery():
    src = read(UPDATER)
    # No PackageInstaller session: nothing to hang, no callback to lose.
    assert "createSession" not in src
    assert "USER_ACTION_NOT_REQUIRED" not in src
    assert "UpdateResultReceiver" not in src
    assert "UpdateInstallActivity" not in src
    assert "onInstallTimeout" not in src
    assert "INSTALL_TIMEOUT_MS" not in src
    assert "abandonSession" not in src
    manifest = read(APP / "AndroidManifest.xml")
    assert "UpdateResultReceiver" not in manifest
    assert "UpdateInstallActivity" not in manifest
    assert "UPDATE_PACKAGES_WITHOUT_USER_ACTION" not in manifest


def test_install_permission_in_place():
    src = read(UPDATER)
    assert "canInstall()" in src
    manifest = read(APP / "AndroidManifest.xml")
    assert "android.permission.REQUEST_INSTALL_PACKAGES" in manifest

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
    for path in (UPDATER, JAVA / "UpdateState.kt"):
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
        # 1.60 owns the bubble files, so they are no longer protected here.
        "app/src/main/java/com/nada/app/BubbleService.kt",
        # 1.68/1.71 own the download pipeline and listnr-first fallback.
        # 1.63 owns the trilingual WebView work, and 1.73 owns responsive
        # About-card wrapping, so styles.css is no longer protected here.
        "signing/nada.jks",
        "server.py",
    }
    assert protected.isdisjoint(changed), sorted(protected & changed)


def test_version_bumped_to_76():
    gradle = read(ROOT / "app" / "build.gradle.kts")
    assert "versionCode = 76" in gradle
    assert 'versionName = "1.76"' in gradle
    manifest = json.loads(read(ROOT / "version.json"))
    assert manifest["version"] == "1.76"
    assert manifest["versionCode"] == 76


# ---------------------------------------------------------------------------
# 1.71: quicksave fallback when listnr cannot find the reel
# ---------------------------------------------------------------------------

def test_quicksave_fallback_when_listnr_cannot_find_the_reel():
    """1.71: listnr first; quicksave only when listnr fails. The listnr
    cause is kept when both fail, and the diagnostic names the source."""
    qs = read(JAVA / "QuickSaveFallback.kt")
    assert 'const val ENDPOINT = "https://quicksave.my/api/info"' in qs
    assert '"https://quicksave.my"' in qs
    assert 'optJSONArray("formats")' in qs
    assert "bestHeight" in qs
    assert "30, TimeUnit.SECONDS" in qs
    ic = read(JAVA / "InstagramClient.kt")
    fetch = ic.split("private fun fetchMediaItem(")[1].split("fun prepareSave(")[0]
    assert "listnr.resolve(" in fetch
    assert "quickSave.resolve(" in fetch
    assert "viaQuickSave = true" in fetch
    assert "throw listnrEx" in fetch
    diag = read(JAVA / "DownloadDiagnostics.kt")
    assert "var viaQuickSave" in diag
    assert '" via="' in diag
    assert "var quickSaveDetail" in diag
    assert '" quicksave="' in diag
    assert '"quicksave="' in diag
    # A failed fallback used to be swallowed while the original Listnr 400
    # was rethrown, making the About line look as if Listnr was the only try.
    assert 'quickSaveDetail = "HTTP ${response.code}"' in qs
    assert 'quickSaveDetail = kind' in qs
    assert 'quickSaveDetail = "no video format"' in qs


# ---------------------------------------------------------------------------
# 1.72: the WebView fits the fixed 402px UI to the screen (no zoomed page)
# ---------------------------------------------------------------------------

def test_webview_fits_the_page_to_the_screen():
    """1.72: the About page looked zoomed in — content cut off on the sides.
    The main WebView must honour the viewport meta and shrink the fixed
    design to the display width, and system font scaling must not blow the
    fixed layout up.
    """
    src = read(JAVA / "MainActivity.kt")
    web = src.split("web.settings.apply {")[1].split("}")[0]
    assert "useWideViewPort = true" in web
    assert "loadWithOverviewMode = true" in web
    assert "textZoom = 100" in web


def test_about_page_diagnostics_and_paragraphs_wrap():
    """1.73: remove no-wrap styles that forced diagnostic summaries past
    the viewport; constrain cards and wrap long words in both CSS mirrors.
    """
    html = read(APP / "assets" / "index.html")
    assert 'id="downloadDebug" class="fine" dir="ltr" hidden' in html
    assert 'id="reelWatchDebug" class="fine" dir="ltr" hidden' in html
    assert "white-space:nowrap;overflow-x:auto" not in html
    css = read(APP / "assets" / "styles.css")
    assert css == read(ROOT / "static" / "styles.css")
    assert ".card { min-width: 0; max-width: 100%;" in css
    assert '.card p.fine[dir="ltr"] { white-space: normal;' in css
    assert "overflow-wrap: anywhere; word-break: break-word" in css
    download_js = read(APP / "assets" / "downloaddebug.js")
    bubble_js = read(APP / "assets" / "reelwatch.js")
    assert 'row.textContent = line ? strings().share : ""' in download_js
    assert 'row.textContent = line ? strings().debugShare : ""' in bubble_js
    assert 'row.textContent = line;' not in download_js + bubble_js
    assert "roots=" not in download_js + bubble_js
    assert "bestCopy=" not in download_js + bubble_js


# ---------------------------------------------------------------------------
# 1.70: the download failure shows listnr's real status and message
# ---------------------------------------------------------------------------

def test_listnr_failure_shows_its_real_status_and_message():
    """1.70: the notification shows listnr's real HTTP status and body
    message (e.g. HTTP 400: No video found), never the mapped 502 that hid
    the cause on the user's phone.
    """
    src = read(JAVA / "ListnrService.kt")
    block = src.split("if (!response.isSuccessful)")[1].split("val body = response.body")[0]
    assert 'optString("message")' in block
    assert 'diag?.listnrDetail = "HTTP ${response.code}"' in block
    qs = read(JAVA / "QuickSaveService.kt")
    map_error = qs.split("private fun mapError(")[1].split("private fun unexpectedAt(")[0]
    upstream_line = next(
        line for line in map_error.splitlines() if '"upstream"' in line
    )
    # The detail comes from the diagnostic (listnr's real status/message);
    # the mapped status is only the blank-fallback, never the shown value.
    assert "diag.listnrDetail.ifBlank" in upstream_line
    assert "ex.status.toString()" not in upstream_line
    # The string carries the detail verbatim — no hardcoded "HTTP" doubling it.
    for values in ("values", "values-en", "values-fr"):
        text = read(APP / "res" / values / "strings.xml")
        assert '(HTTP %1$s)' not in text
        assert 'name="save_fail_upstream"' in text


# ---------------------------------------------------------------------------
# 1.68: every download failure names the step that actually failed
# ---------------------------------------------------------------------------

def test_download_failure_names_its_step():
    """listnr / cdn / store each carry their own message; no path hides behind
    the generic save_fail_net line (that line survives only for unknown codes).
    """
    src = read(JAVA / "QuickSaveService.kt")
    map_error = src.split("private fun mapError(")[1].split("private fun unexpectedAt(")[0]
    assert '"network" -> getString(R.string.save_fail_resolve' in map_error
    assert '"upstream" -> getString(R.string.save_fail_upstream' in map_error
    assert '"cdn", "host" -> getString(R.string.save_fail_cdn' in map_error
    assert '"store", "io" -> getString(R.string.save_fail_store' in map_error
    # Exactly one generic line remains: the unknown-code else branch.
    assert src.count("R.string.save_fail_net") == 1
    drain = src.split("private fun drain()")[1].split("private fun runSave(")[0]
    assert "R.string.save_fail_net" not in drain
    assert "unexpectedAt(diag.stage, ex)" in drain
    assert "persist(diag)" in drain
    run_save = src.split("private fun runSave(")[1].split("private fun shouldSaveStream(")[0]
    assert "R.string.save_fail_net" not in run_save
    assert "failWith(" in run_save
    assert "diag.begin(DownloadDiagnostics.STEP_LISTNR)" in run_save
    write_remote = src.split("private fun writeRemote(")[1].split("private fun saveStream(")[0]
    assert "STEP_CDN" in write_remote and "STEP_STORE" in write_remote
    unexpected = src.split("private fun unexpectedAt(")[1].split("private fun failWith(")[0]
    for stage in ("STEP_LISTNR", "STEP_CDN", "STEP_STORE"):
        assert stage in unexpected
    # The store step records the real write cause and the byte count.
    store = src.split("private fun storeBytes(")[1].split("private fun mapError(")[0]
    assert "diag.storeDetail" in store
    assert "diag.bytes = limited.copied" in store


def test_listnr_resolve_retries_transport_failures_once():
    """One slow handshake must not kill every save: transport failures retry
    once with backoff and a 20s budget; HTTP errors keep their real code.
    """
    src = read(JAVA / "ListnrService.kt")
    assert "const val MAX_ATTEMPTS = 2" in src
    assert "attempt < MAX_ATTEMPTS" in src
    assert "RETRY_BACKOFF_MS" in src
    assert "DownloadDiagnostics.classify(ex)" in src
    assert 'ApiException(502, "network")' in src
    assert "listnr HTTP ${response.code}" in src
    assert "CALL_TIMEOUT_SECONDS = 20L" in src
    diag = read(JAVA / "DownloadDiagnostics.kt")
    for kind in ('"dns"', '"tls"', '"connect"', '"timeout"', '"read"', '"io"'):
        assert kind in diag
    # ListnrService itself stays listnr-only; the quicksave fallback lives
    # in InstagramClient.fetchMediaItem, which tries it only after listnr
    # throws (1.71).
    assert src.count("ENDPOINT") >= 1
    assert "bff.listnr.tech" in src


def test_cdn_pull_retries_and_names_its_cause():
    src = read(JAVA / "InstagramClient.kt")
    pull = src.split("fun pullFile(")[1].split("fun warmSession(")[0]
    assert "MAX_CDN_ATTEMPTS" in pull
    assert "DownloadDiagnostics.classify(ex)" in pull
    assert "diag.cdnHost" in pull
    assert "diag.cdnDetail" in pull
    assert "cdn HTTP $code" in pull
    # A resolved URL that fails the host allowlist is named, not generic.
    resolve = src.split("fun resolve(rawUrl: String, diag")[1].split("private fun fetchMediaItem(")[0]
    assert 'ApiException(400, "host")' in resolve
    # The recorder never holds a signed CDN URL.
    recorder = read(JAVA / "DownloadDiagnostics.kt")
    assert "remoteUrl" not in recorder
    assert 'const val STEP_LISTNR = "listnr"' in recorder
    assert 'const val STEP_CDN = "cdn"' in recorder
    assert 'const val STEP_STORE = "store"' in recorder


def test_download_diagnostic_persists_and_is_shareable():
    src = read(JAVA / "QuickSaveService.kt")
    assert "persist(diag)" in src
    assert "DownloadDiagnostics.FILE_NAME" in src
    assert "fun lastRunSummary(context: Context)" in src
    assert "fun lastRunFile(context: Context)" in src
    main = read(JAVA / "MainActivity.kt")
    assert "fun lastDownloadRun(): String" in main
    assert "fun shareDownloadRun()" in main
    assert "shareLastDownloadRun()" in main
    paths = read(APP / "res" / "xml" / "file_paths.xml")
    assert '<external-files-path name="download-debug" path="." />' in paths
    for values in ("values", "values-en", "values-fr"):
        text = read(APP / "res" / values / "strings.xml")
        for key in ("save_fail_resolve", "save_fail_upstream", "save_fail_cdn",
                    "save_fail_store", "save_fail_unexpected", "download_share_debug"):
            assert f'name="{key}"' in text, (values, key)
    html = read(APP / "assets" / "index.html")
    assert html.count('id="downloadDebug"') == 1
    assert '<script src="/downloaddebug.js"></script>' in html
    assert read(APP / "assets" / "index.html") == read(ROOT / "static" / "index.html")
    js = read(APP / "assets" / "downloaddebug.js")
    assert js == read(ROOT / "static" / "downloaddebug.js")
    assert "lastDownloadRun" in js and "shareDownloadRun" in js
    diag = read(JAVA / "DownloadDiagnostics.kt")
    assert 'FILE_NAME = "download-last-run.txt"' in diag
    assert "MAX_NOTES" in diag and "MAX_STEPS" in diag
    # The About row renders the same one-line summary the service persisted.
    persist = src.split("private fun persist(")[1]
    assert "run.summary()" in persist
    assert "run.report()" in persist
    assert "DownloadDiagnostics.FILE_NAME" in persist


def test_resume_pending_installs_without_permission():
    """1.64: a missing install permission must never strand the dialog.

    After the download, resumePending used to return silently when
    canInstall() was false, freezing the dialog at 100% "installing"
    (1.63 on-device report). It must hand the APK to install(), which
    shows the "allow installs" error with the Settings action instead.
    """
    src = read(UPDATER)
    assert "if (canInstall()) install(activity, file, update)" not in src
    assert "install(activity, file, update)" in src
    assert "R.string.update_allow_installs" in src


def test_download_bar_matches_preview():
    """1.64: the download bar matches the approved preview.

    Shimmer sweep + glowing tip riding the fill + frosted-glass card.
    """
    layout = read(APP / "res" / "layout" / "dialog_update_glassy.xml")
    for view_id in ("update_bar_wrap", "update_shimmer", "update_tip"):
        assert view_id in layout, view_id
    shimmer = read(APP / "res" / "drawable" / "update_shimmer.xml")
    assert "gradient" in shimmer and "#99FFFFFF" in shimmer
    tip = read(APP / "res" / "drawable" / "update_tip_glow.xml")
    assert 'android:type="radial"' in tip and "gradientRadius" in tip
    src = read(UPDATER)
    for marker in (
        "fun positionTip()",
        "fun startShimmer()",
        "fun applyFrost(",
        "ObjectAnimator.ofFloat",
        "FLAG_BLUR_BEHIND",
        "LAYOUT_DIRECTION_RTL",
        "stopShimmer()",
        "barWrap = null",
    ):
        assert marker in src, marker


def test_webview_is_trilingual():
    """1.63: the WebView UI speaks Arabic, English and French.

    app.js carries a full fr dictionary, the language button cycles
    ar -> en -> fr -> ar and shows the next language, and the reel-watch and
    update cards pick fr too. Assets and static/ stay mirrored.
    """
    app_js = read(APP / "assets" / "app.js")
    assert ",fr:{" in app_js
    assert "Télécharger ce fichier" in app_js
    assert "Collez le lien" in app_js
    assert 'n.lang="ar"===n.lang?"en":"fr"===n.lang?"ar":"fr"' in app_js
    assert 'textContent="ar"===n.lang?"EN":"en"===n.lang?"FR":"ع"' in app_js
    assert app_js == read(ROOT / "static" / "app.js")
    for name in ("reelwatch.js", "update.js"):
        js = read(APP / "assets" / name)
        assert "const fr = {" in js, name
        assert 'lang.startsWith("fr")' in js, name
        assert js == read(ROOT / "static" / name), name


def test_native_strings_are_trilingual():
    """1.63: values-en and values-fr translate every translatable string."""
    def names(path):
        text = read(path)
        out = {}
        for m in re.finditer(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', text, re.S):
            out[m.group(1)] = (m.group(2), m.group(3))
        return out

    base = names(APP / "res" / "values" / "strings.xml")
    # Same in every language: brand name, a URL placeholder, a username.
    identical = {"app_name", "placeholder", "userPlaceholder"}
    for lang in ("en", "fr"):
        other = names(APP / "res" / ("values-" + lang) / "strings.xml")
        assert set(other) == set(base), lang
        for name, (attrs, value) in base.items():
            if 'translatable="false"' in attrs:
                assert other[name][1] == value, (lang, name)
            elif name not in identical:
                assert other[name][1] != value, (lang, name)


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
