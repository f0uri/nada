package com.nada.app

import android.app.Activity
import android.util.TypedValue
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

    fun start() {
        ViewCompat.setOnApplyWindowInsetsListener(slot) { view, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val extra = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                86f,
                view.resources.displayMetrics,
            ).toInt()
            val params = view.layoutParams as FrameLayout.LayoutParams
            params.bottomMargin = extra + nav
            view.layoutParams = params
            insets
        }
        slot.requestApplyInsets()
        MobileAds.initialize(activity) {
            activity.runOnUiThread { attach() }
        }
    }

    private fun attach() {
        if (adView != null) return
        val density = activity.resources.displayMetrics.density
        val widthDp = (activity.resources.displayMetrics.widthPixels / density).toInt().coerceAtLeast(320)
        val view = AdView(activity)
        view.adUnitId = activity.getString(R.string.admob_banner_id)
        view.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp))
        view.adListener = object : AdListener() {
            override fun onAdLoaded() {
                loaded = true
                val px = view.height.takeIf { it > 0 }
                    ?: view.adSize?.getHeightInPixels(activity)
                    ?: (50 * density).toInt()
                heightCss = (px / density).toInt().coerceAtLeast(50)
                paint()
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                loaded = false
                heightCss = 0
                paint()
            }
        }
        slot.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        adView = view
        view.loadAd(AdRequest.Builder().build())
    }

    fun setAllowed(on: Boolean) {
        allowed = on
        paint()
    }

    fun pause() {
        adView?.pause()
    }

    fun resume() {
        adView?.resume()
    }

    fun destroy() {
        adView?.destroy()
        adView = null
    }

    private fun paint() {
        val show = allowed && loaded
        slot.visibility = if (show) View.VISIBLE else View.GONE
        notify(show, if (show) heightCss else 0)
    }
}
