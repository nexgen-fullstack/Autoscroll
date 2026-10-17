package com.autoscroll.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/** Горизонтальна шкала 0,25…2,50 з поділками по 0,25. Значення — будь-яке з кроком 0,01. */
class SpeedScaleView(context: Context) : View(context) {
    var value = 1f
        set(v) { field = Prefs.clamp(v); invalidate() }
    var onChange: ((Float) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val pad = 18 * d
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); strokeWidth = 1.5f * d }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textSize = 10.5f * d; textAlign = Paint.Align.CENTER }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val thumbRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0E1013.toInt(); textSize = 13 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private var dragging = false
    private var lastTick = -1

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (96 * d).toInt())
    }

    private fun xOf(v: Float) = pad + (v - Prefs.MIN_SPEED) / (Prefs.MAX_SPEED - Prefs.MIN_SPEED) * (width - 2 * pad)
    private fun vOf(x: Float) = Prefs.MIN_SPEED + ((x - pad) / (width - 2 * pad)) * (Prefs.MAX_SPEED - Prefs.MIN_SPEED)

    override fun onDraw(c: Canvas) {
        val cy = height * 0.55f
        c.drawLine(pad, cy, width - pad, cy, track)
        c.drawLine(pad, cy, xOf(value), cy, fill)
        var i = 0
        while (true) {
            val v = Prefs.MIN_SPEED + i * 0.25f
            if (v > Prefs.MAX_SPEED + 0.001f) break
            val x = xOf(v)
            val major = (i % 2 == 1) // 0.5, 1.0, 1.5 ...
            c.drawLine(x, cy + 6 * d, x, cy + (if (major) 14 else 10) * d, tick)
            c.drawText(Prefs.fmt(v).dropLast(1).trimEnd('0').trimEnd(','), x, cy + 28 * d, label)
            i++
        }
        // проміжні поділки по 0,05
        var k = 0
        while (true) {
            val v = Prefs.MIN_SPEED + k * 0.05f
            if (v > Prefs.MAX_SPEED + 0.001f) break
            if (k % 5 != 0) { val x = xOf(v); c.drawLine(x, cy + 6 * d, x, cy + 8 * d, tick) }
            k++
        }
        val tx = xOf(value)
        c.drawCircle(tx, cy, 11 * d, thumb)
        c.drawCircle(tx, cy, 11 * d, thumbRing)
        if (dragging) {
            val r = RectF(tx - 28 * d, cy - 46 * d, tx + 28 * d, cy - 20 * d)
            c.drawRoundRect(r, 13 * d, 13 * d, bubble)
            c.drawText(Prefs.fmt(value), tx, cy - 28 * d, bubbleText)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                value = vOf(e.x)
                val t = Math.round(value * 4)
                if (t != lastTick && Math.abs(value * 4 - t) < 0.05f) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); lastTick = t
                }
                onChange?.invoke(value)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; invalidate(); onChange?.invoke(value) }
        }
        return true
    }

    companion object { const val ACCENT = 0xFF7CF5C4.toInt() }
}
