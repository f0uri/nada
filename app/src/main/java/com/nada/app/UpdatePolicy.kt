package com.nada.app

/**
 * Pure decision logic for the in-app updater.
 *
 * No Android imports so the rules that caused the 1.57/1.58 update loop can be
 * compiled and asserted on a plain JVM.
 */
object UpdatePolicy {
    /** Stable local-calendar day key, e.g. 2026-11-12 -> 2026316. */
    fun dayKey(year: Int, dayOfYear: Int): Int = year * 1000 + dayOfYear

    /**
     * Whether the "update available" dialog may be shown.
     *
     * Rules:
     *  - never when there is nothing newer;
     *  - never while a download or install is running (that caused the stuck
     *    100% dialog to be re-shown on top of itself);
     *  - a manual check always answers, because the user explicitly asked;
     *  - after "Later", never again for that version on the same day;
     *  - otherwise at most one automatic prompt per version per day, so a cold
     *    start can never re-show it on every entry.
     */
    fun shouldShowDialog(
        updateCode: Long,
        installedCode: Long,
        laterCode: Long,
        laterDay: Int,
        promptedCode: Long,
        promptedDay: Int,
        today: Int,
        manual: Boolean,
        inProgress: Boolean,
    ): Boolean {
        if (updateCode <= installedCode) return false
        if (inProgress) return false
        if (manual) return true
        if (updateCode == laterCode && today == laterDay) return false
        if (updateCode == promptedCode && today == promptedDay) return false
        return true
    }

    /**
     * APK acceptance. The manifest is served from raw.githubusercontent.com,
     * which caches for a few minutes, while /releases/latest/download already
     * serves the newer file. Requiring an exact code match turned that normal
     * lag into a permanently failing update, so a newer APK is accepted.
     */
    fun acceptApk(apkCode: Long, manifestCode: Long, installedCode: Long): Boolean =
        apkCode > installedCode && apkCode >= manifestCode

    /** A ZIP (APK) local file header, so an HTML error page is never installed. */
    fun looksLikeApk(first4: ByteArray): Boolean {
        if (first4.size < 4) return false
        return first4[0] == 0x50.toByte() && // P
            first4[1] == 0x4B.toByte() && // K
            first4[2] == 0x03.toByte() &&
            first4[3] == 0x04.toByte()
    }

    /** Progress must be monotonic and must only reach 100 when fully written. */
    fun percent(seen: Long, total: Long): Int {
        if (total <= 0L) return 0
        if (seen >= total) return 100
        return ((seen * 100) / total).toInt().coerceIn(0, 99)
    }
}
