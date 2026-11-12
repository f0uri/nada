package com.nada.app

import android.content.Context
import java.util.Calendar

/**
 * Persisted updater bookkeeping.
 *
 * The 1.57/1.58 updater kept everything in memory, so "Later" was forgotten on
 * every cold start and the dialog returned on every entry. These values survive
 * process death and app replacement.
 */
class UpdateState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var laterCode: Long
        get() = prefs.getLong(LATER_CODE, 0L)
        set(value) { prefs.edit().putLong(LATER_CODE, value).apply() }

    var laterDay: Int
        get() = prefs.getInt(LATER_DAY, 0)
        set(value) { prefs.edit().putInt(LATER_DAY, value).apply() }

    var promptedCode: Long
        get() = prefs.getLong(PROMPTED_CODE, 0L)
        set(value) { prefs.edit().putLong(PROMPTED_CODE, value).apply() }

    var promptedDay: Int
        get() = prefs.getInt(PROMPTED_DAY, 0)
        set(value) { prefs.edit().putInt(PROMPTED_DAY, value).apply() }

    /** Set when the user pressed Later: version + the local day they chose it. */
    fun rememberLater(code: Long) {
        val today = today()
        prefs.edit().putLong(LATER_CODE, code).putInt(LATER_DAY, today).apply()
    }

    /** Records that an automatic prompt was shown, capping it at one per day. */
    fun rememberPrompt(code: Long) {
        val today = today()
        prefs.edit().putLong(PROMPTED_CODE, code).putInt(PROMPTED_DAY, today).apply()
    }

    /** Last version we know was installed, so a finished update reads as current. */
    var settledCode: Long
        get() = prefs.getLong(SETTLED_CODE, 0L)
        set(value) { prefs.edit().putLong(SETTLED_CODE, value).apply() }

    /** True when an update is known to be available; drives the one-line card. */
    var availableCode: Long
        get() = prefs.getLong(AVAILABLE_CODE, 0L)
        set(value) { prefs.edit().putLong(AVAILABLE_CODE, value).apply() }

    fun today(): Int {
        val now = Calendar.getInstance()
        return UpdatePolicy.dayKey(now.get(Calendar.YEAR), now.get(Calendar.DAY_OF_YEAR))
    }

    companion object {
        private const val FILE = "nada_update"
        private const val LATER_CODE = "later_code"
        private const val LATER_DAY = "later_day"
        private const val PROMPTED_CODE = "prompted_code"
        private const val PROMPTED_DAY = "prompted_day"
        private const val SETTLED_CODE = "settled_code"
        private const val AVAILABLE_CODE = "available_code"
    }
}
