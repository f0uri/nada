package com.nada.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds

class BannerAds(
    private val activity: Activity,
    private val slot: FrameLayout,
    private val notify: (visible: Boolean, heightCss: Int) -> Unit,
) {
    private var adView: AdView? = null
    private var loaded = false
    private var allowed = true
    private var heightCss = 0
    private var started = false
    private var attempts = 0
    private val handler = Handler(Looper.getMainLooper())
    private val retry = Runnable { runCatching { load() } }

    fun start() {
        if (started) return
        started = true
        slot.elevation = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            20f,
            slot.resources.displayMetrics,
        )
        slot.bringToFront()
        runCatching {
            ViewCompat.setOnApplyWindowInsetsListener(slot) { view, insets ->
                val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                val extra = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    88f,
                    view.resources.displayMetrics,
                ).toInt()
                val params = view.layoutParams as FrameLayout.LayoutParams
                params.bottomMargin = extra + nav
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                view.layoutParams = params
                insets
            }
            slot.requestApplyInsets()
        }
        runCatching {
            MobileAds.initialize(activity) {
                activity.runOnUiThread {
                    runCatching { attach() }
                    handler.postDelayed(retry, 400)
                }
            }
        }.onFailure {
            started = false
            handler.postDelayed({ start() }, 8_000)
        }
    }

    private fun attach() {
        if (adView != null || activity.isFinishing) return
        val density = activity.resources.displayMetrics.density
        val view = AdView(activity)
        view.adUnitId = activity.getString(R.string.admob_banner_id)
        view.setAdSize(AdSize.BANNER)
        view.adListener = object : AdListener() {
            override fun onAdLoaded() {
                loaded = true
                attempts = 0
                handler.removeCallbacks(retry)
                val px = view.adSize?.getHeightInPixels(activity) ?: (50 * density).toInt()
                heightCss = (px / density).toInt().coerceAtLeast(50)
                paint()
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                loaded = false
                heightCss = 0
                paint()
                scheduleRetry()
            }
        }
        slot.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        adView = view
    }

    private fun load() {
        if (activity.isFinishing) return
        if (adView == null) attach()
        val view = adView ?: return
        if (loaded) return
        attempts += 1
        runCatching { view.loadAd(AdRequest.Builder().build()) }.onFailure { scheduleRetry() }
    }

    private fun scheduleRetry() {
        if (activity.isFinishing) return
        val delay = when {
            attempts < 2 -> 2_000L
            attempts < 5 -> 8_000L
            else -> 30_000L
        }
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, delay)
    }

    fun setAllowed(on: Boolean) {
        allowed = on
        runCatching { paint() }
        if (on && !loaded) {
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, 300)
        }
    }

    fun pause() {
        handler.removeCallbacks(retry)
        runCatching { adView?.pause() }
    }

    fun resume() {
        runCatching { adView?.resume() }
        if (!loaded) {
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, 600)
        }
    }

    fun destroy() {
        handler.removeCallbacks(retry)
        runCatching { adView?.destroy() }
        adView = null
        loaded = false
    }

    private fun paint() {
        val show = allowed && loaded
        slot.visibility = if (show) View.VISIBLE else View.GONE
        if (show) slot.bringToFront()
        runCatching { notify(show, if (show) heightCss else 0) }
    }
}
