package com.nada.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class BubbleService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: View? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification(Prefs.bubbleVisible(this)))
        if (Prefs.bubbleVisible(this)) showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setBubble(this, false)
                Prefs.setBubbleVisible(this, true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW -> {
                Prefs.setBubble(this, true)
                Prefs.setBubbleVisible(this, true)
                showBubble()
                refreshNotification(true)
                return START_STICKY
            }
            ACTION_HIDE -> {
                Prefs.setBubbleVisible(this, false)
                removeBubble()
                refreshNotification(false)
                return START_STICKY
            }
        }
        if (Prefs.bubbleVisible(this)) showBubble() else removeBubble()
        refreshNotification(Prefs.bubbleVisible(this))
        return START_STICKY
    }

    override fun onDestroy() {
        removeBubble()
        super.onDestroy()
    }

    private fun showBubble() {
        if (bubble != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val size = dp(58)
        val view = GlassBubbleView(this)
        val layout = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(180)
        }
        var downX = 0
        var downY = 0
        var startX = 0
        var startY = 0
        var dragged = false
        var longPressed = false
        val hideLater = Runnable {
            if (dragged || longPressed) return@Runnable
            longPressed = true
            Prefs.setBubbleVisible(this, false)
            removeBubble()
            refreshNotification(false)
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX.toInt()
                    downY = event.rawY.toInt()
                    startX = layout.x
                    startY = layout.y
                    dragged = false
                    longPressed = false
                    view.removeCallbacks(hideLater)
                    view.postDelayed(hideLater, 520)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - downX
                    val dy = event.rawY.toInt() - downY
                    if (dx * dx + dy * dy > dp(8) * dp(8)) {
                        dragged = true
                        view.removeCallbacks(hideLater)
                    }
                    layout.x = startX + dx
                    layout.y = startY + dy
                    runCatching { wm.updateViewLayout(view, layout) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(hideLater)
                    if (!dragged && !longPressed && event.actionMasked == MotionEvent.ACTION_UP) {
                        pulse(view)
                        saveInBackground()
                    }
                    true
                }
                else -> false
            }
        }
        bubble = view
        params = layout
        wm.addView(view, layout)
    }

    private fun removeBubble() {
        val view = bubble ?: return
        runCatching { windowManager?.removeView(view) }
        bubble = null
        params = null
        windowManager = null
    }

    private fun pulse(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(0.88f)
            .scaleY(0.88f)
            .setDuration(80)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            .start()
    }

    private fun saveInBackground() {
        // With one-tap mode enabled, never fall through to QuickSaveActivity's
        // last-URL fallback: if the accessibility service cannot own this tap,
        // fail safely rather than downloading the previous reel again.
        if (Prefs.reelWatchOn(this)) {
            if (!ReelWatchService.takeBubbleTap()) QuickSaveService.notifyNeedLink(this)
            return
        }
        val last = Prefs.lastIgUrl(this)
        val started = runCatching { startActivity(QuickSaveActivity.intent(this)) }.isSuccess
        if (!started) {
            if (last.isNotBlank()) QuickSaveService.start(this, last)
            else QuickSaveService.notifyNeedLink(this)
        }
    }

    private fun refreshNotification(visible: Boolean) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(visible))
    }

    private fun buildNotification(visible: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.bubble_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            description = getString(R.string.bubble_channel)
        }
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val show = PendingIntent.getService(
            this,
            5,
            Intent(this, BubbleService::class.java).setAction(ACTION_SHOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hide = PendingIntent.getService(
            this,
            6,
            Intent(this, BubbleService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.bubble_label))
            .setContentText(getString(if (visible) R.string.bubble_hint else R.string.bubble_hidden))
            .setContentIntent(if (visible) open else show)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
        if (visible) {
            builder.addAction(0, getString(R.string.bubble_hide), hide)
        } else {
            builder.addAction(0, getString(R.string.bubble_show), show)
        }
        builder.addAction(0, getString(R.string.bubble_off), stop)
        return builder.build()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
    }

    companion object {
        private const val CHANNEL_ID = "nada_bubble"
        private const val NOTIF_ID = 26
        private const val ACTION_STOP = "com.nada.app.STOP_BUBBLE"
        private const val ACTION_SHOW = "com.nada.app.SHOW_BUBBLE"
        private const val ACTION_HIDE = "com.nada.app.HIDE_BUBBLE"

        fun start(context: Context) {
            Prefs.setBubble(context, true)
            Prefs.setBubbleVisible(context, true)
            val intent = Intent(context, BubbleService::class.java).setAction(ACTION_SHOW)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            Prefs.setBubble(context, false)
            context.stopService(Intent(context, BubbleService::class.java))
        }
    }
}

object Prefs {
    private const val FILE = "nada_prefs"
    private const val BUBBLE = "bubble_on"
    private const val VISIBLE = "bubble_visible"
    private const val LAST_URL = "last_ig_url"
    private const val REEL_WATCH = "reel_watch_on"

    fun bubbleOn(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(BUBBLE, false)
    }

    fun setBubble(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(BUBBLE, on).apply()
    }

    fun bubbleVisible(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(VISIBLE, true)
    }

    fun setBubbleVisible(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(VISIBLE, on).apply()
    }

    fun lastIgUrl(context: Context): String {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(LAST_URL, "").orEmpty()
    }

    fun setLastIgUrl(context: Context, url: String) {
        val clean = IgLinks.fromText(url).ifBlank { IgLinks.tidy(url) }
        if (clean.isBlank()) return
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(LAST_URL, clean).apply()
    }

    /** Opt-in for one-tap download of the reel being watched. OFF by default. */
    fun reelWatchOn(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(REEL_WATCH, false)
    }

    fun setReelWatch(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(REEL_WATCH, on).apply()
    }
}
