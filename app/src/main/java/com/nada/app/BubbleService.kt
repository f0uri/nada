package com.nada.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat

class BubbleService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setBubble(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
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
        val size = dp(56)
        val view = TextView(this).apply {
            text = "IG"
            setTextColor(0xFFE8C37A.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xF21C1C1C.toInt())
                setStroke(dp(1), 0x66E8C37A.toInt())
            }
            elevation = dp(8).toFloat()
            contentDescription = getString(R.string.bubble_label)
        }
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
            Prefs.setBubble(this, false)
            stopSelf()
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
        startActivity(QuickSaveActivity.intent(this))
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.bubble_channel),
            NotificationManager.IMPORTANCE_MIN,
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
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(getString(R.string.bubble_label))
            .setContentText(getString(R.string.bubble_hint))
            .setContentIntent(open)
            .addAction(0, getString(R.string.bubble_off), stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
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

        fun start(context: Context) {
            val intent = Intent(context, BubbleService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BubbleService::class.java))
        }
    }
}

object Prefs {
    private const val FILE = "nada_prefs"
    private const val BUBBLE = "bubble_on"

    fun bubbleOn(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(BUBBLE, false)
    }

    fun setBubble(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(BUBBLE, on).apply()
    }
}
