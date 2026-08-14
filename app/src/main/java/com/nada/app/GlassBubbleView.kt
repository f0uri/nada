package com.nada.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.TypedValue
import android.view.View

class GlassBubbleView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x99E8C37A.toInt()
    }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFE8C37A.toInt()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arrow = Path()
    private var lastW = 0
    private var lastH = 0

    init {
        contentDescription = context.getString(R.string.bubble_label)
        elevation = dp(10f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        lastW = w
        lastH = h
        fill.shader = LinearGradient(
            0f,
            0f,
            0f,
            h.toFloat(),
            intArrayOf(0xCC3A322C.toInt(), 0xD91C1814.toInt()),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        gloss.shader = LinearGradient(
            0f,
            0f,
            0f,
            h * 0.55f,
            intArrayOf(0x55FFFFFF.toInt(), 0x00FFFFFF.toInt()),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        ring.strokeWidth = dp(1.2f)
        icon.strokeWidth = dp(2.1f)
    }

    override fun onDraw(canvas: Canvas) {
        val pad = dp(2f)
        val cx = width / 2f
        val cy = height / 2f
        val r = (width.coerceAtMost(height) / 2f) - pad
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy - r * 0.18f, r * 0.78f, gloss)
        canvas.drawCircle(cx, cy, r, ring)

        val s = r * 0.42f
        arrow.reset()
        arrow.moveTo(cx, cy - s * 0.95f)
        arrow.lineTo(cx, cy + s * 0.35f)
        canvas.drawPath(arrow, icon)
        canvas.drawLine(cx - s * 0.55f, cy + s * 0.05f, cx, cy + s * 0.58f, icon)
        canvas.drawLine(cx + s * 0.55f, cy + s * 0.05f, cx, cy + s * 0.58f, icon)
        canvas.drawLine(cx - s * 0.72f, cy + s * 0.88f, cx + s * 0.72f, cy + s * 0.88f, icon)
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
    }
}
