package com.autoscroll.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper

/**
 * Безперервний скрол через «віртуальний палець»: довгий мазок, що продовжується
 * сегментами по SEG мс. Швидкість можна змінювати на льоту.
 */
class Scroller(private val service: AccessibilityService) {
    companion object { private const val SEG = 120L; const val X_FRACTION = 0.42f }

    var speed = 1f
    /** true = гортати вниз (палець рухається вгору) */
    var down = true
    var running = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var stroke: StrokeDescription? = null
    private var x = 0f
    private var y = 0f
    private var travelled = 0f
    private var fracCarry = 0f
    private var generation = 0

    private val dm get() = service.resources.displayMetrics
    private val span get() = dm.heightPixels * 0.45f

    fun start(down: Boolean, speed: Float) {
        this.down = down
        this.speed = speed
        if (running) return
        running = true
        generation++
        handler.removeCallbacksAndMessages(null)
        if (stroke == null) beginStroke(generation)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun pxPerSeg(): Float = Prefs.baseDp(service) * dm.density * speed * SEG / 1000f

    private fun beginStroke(gen: Int) {
        if (!running || gen != generation) return
        x = dm.widthPixels * X_FRACTION
        y = if (down) dm.heightPixels * 0.72f else dm.heightPixels * 0.28f
        travelled = 0f
        val d = step()
        val p = Path().apply { moveTo(x, y); lineTo(x, y + d) }
        y += d
        val s = StrokeDescription(p, 0, SEG, true)
        dispatch(s, gen)
    }

    private fun step(): Float {
        val raw = pxPerSeg() + fracCarry
        val px = maxOf(1f, raw)
        travelled += px
        return if (down) -px else px
    }

    private fun next(gen: Int) {
        val prev = stroke ?: return
        val finish = !running || gen != generation || travelled >= span
        val d = if (!running) (if (down) -1f else 1f) else step()
        val p = Path().apply { moveTo(x, y); lineTo(x, y + d) }
        y += d
        // при зупинці палець спершу «завмирає», щоб список не доїжджав за інерцією
        val s = prev.continueStroke(p, 0, if (!running) 300L else SEG, !finish)
        if (finish) {
            dispatchFinal(s)
        } else dispatch(s, gen)
    }

    private fun dispatch(s: StrokeDescription, gen: Int) {
        stroke = s
        val ok = service.dispatchGesture(GestureDescription.Builder().addStroke(s).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { next(gen) }
                override fun onCancelled(g: GestureDescription?) { restartLater() }
            }, handler)
        if (!ok) restartLater()
    }

    private fun dispatchFinal(s: StrokeDescription) {
        val ok = service.dispatchGesture(GestureDescription.Builder().addStroke(s).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    stroke = null
                    val gen = generation
                    if (running) handler.postDelayed({ beginStroke(gen) }, 16)
                }
                override fun onCancelled(g: GestureDescription?) { restartLater() }
            }, handler)
        if (!ok) restartLater()
    }

    /** Користувач торкнувся екрана або система перервала жест — продовжуємо трохи згодом */
    private fun restartLater() {
        stroke = null
        val gen = ++generation
        if (running) handler.postDelayed({ beginStroke(gen) }, 600)
    }
}

