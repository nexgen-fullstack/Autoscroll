package com.autoscroll.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Коло керування посередині екрана + маленька «пігулка» під час скролу. */
class Overlay(private val s: AccessibilityService, private val scroller: Scroller) {
    private val wm = s.getSystemService(WindowManager::class.java)
    private val d = s.resources.displayMetrics.density
    private fun dp(v: Number) = (v.toFloat() * d).toInt()
    private val handler = Handler(Looper.getMainLooper())

    private var menu: View? = null
    private var pill: View? = null
    private var speed = Prefs.speed(s)

    /** Червоний ✕: закрити все повністю, доки людина знову не відкриє додаток «Автоскрол». */
    fun shutdown() {
        Prefs.setActive(s, false)
        collapseScale(false); hideMenu(); stopAll()
        hideButton(); hideWatcher()
    }

    /** Додаток відкрили знову — повертаємо кнопку і жести. */
    fun wake() {
        Prefs.setActive(s, true)
        refreshButton()
        if (pill == null && menu == null && expanded == null) ensureWatcher()
    }

    /** Тимчасово сховатися (відкрита програма зі своєю автопрокруткою) і повернутися, коли її закрили. */
    private var suspended = false
    fun setSuspended(on: Boolean) {
        if (suspended == on) return
        suspended = on
        android.util.Log.d("Autoscroll", "suspended=$on")
        if (on) { collapseScale(false); hideMenu(); stopAll(); hideButton(); hideWatcher() }
        else if (Prefs.active(s)) { refreshButton(); if (pill == null && menu == null && expanded == null) ensureWatcher() }
    }

    fun toggle() {
        if (!Prefs.active(s) || suspended) return
        when {
            menu != null -> hideMenu()
            scroller.running || pill != null -> { handler.removeCallbacks(resume); scroller.stop(); hideWatcher(); showMenu() }
            else -> showMenu()
        }
    }

    init { handler.post { ensureWatcher(); refreshButton() } }

    fun destroy() { stopAll(); hideMenu(); hideWatcher(); hideButton() }

    // ---------------- Напівпрозора кнопка на екрані ----------------
    // 2 тапи (3 — у програмах, де подвійний тап зайнятий) — увімкнути/вимкнути скрол.
    // Менше тапів — вони проходять «крізь» кнопку в програму під нею (меню сайту тощо).
    // Утримання — маленька шкала швидкості з червоним ✕. Перетягування — перемістити.
    private var button: View? = null
    private var buttonLp: WindowManager.LayoutParams? = null
    private var buttonLabel: TextView? = null
    private var expanded: View? = null
    private var btnTouchAt = 0L
    private val btnPts = mutableListOf<Pair<Float, Float>>()
    private var ignoreOutsideUntil = 0L
    private val flushTaps = Runnable {
        val pts = btnPts.toList(); btnPts.clear()
        // під час скролу тап по кнопці нічого не натискає — щоб не зупиняти «віртуальний палець»
        if (pts.isNotEmpty() && (pill == null || paused)) forwardTaps(pts)
    }
    private val longPress = Runnable { btnLong = true; btnPts.clear(); handler.removeCallbacks(flushTaps); expandScale() }
    private var btnLong = false

    fun refreshButton() { if (Prefs.buttonOn(s) && Prefs.active(s) && !suspended) showButton() else { hideButton(); collapseScale(false) } }

    @SuppressLint("ClickableViewAccessibility")
    private fun showButton() {
        if (button != null || expanded != null || suspended) return
        val v = text("⇅ " + Prefs.fmt(Prefs.speed(s)), 10.5f, SpeedScaleView.ACCENT, true).apply {
            background = round(0xFF1A1D23.toInt(), dp(14).toFloat(), SpeedScaleView.ACCENT)
            setPadding(dp(7), dp(3), dp(7), dp(3))
            alpha = 0.35f
        }
        val (sx, sy) = Prefs.buttonPos(s)
        val p = params(WRAP_CONTENT, WRAP_CONTENT, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (sx >= 0) sx else s.resources.displayMetrics.widthPixels - dp(110)
            y = if (sy >= 0) sy else s.resources.displayMetrics.heightPixels / 2
        }
        keepOffLane(v, p)
        var rx = 0f; var ry = 0f; var px = 0; var py = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    btnTouchAt = android.os.SystemClock.uptimeMillis(); rx = e.rawX; ry = e.rawY; px = p.x; py = p.y
                    moved = false; btnLong = false; v.alpha = 0.7f
                    handler.removeCallbacks(flushTaps) // серія триває
                    handler.postDelayed(longPress, LONG_PRESS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - rx; val dy = e.rawY - ry
                    if (!moved && Math.hypot(dx.toDouble(), dy.toDouble()) > dp(10)) { moved = true; handler.removeCallbacks(longPress); btnPts.clear() }
                    if (moved) {
                        p.x = (px + dx).toInt().coerceAtLeast(0); p.y = (py + dy).toInt().coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    v.alpha = 0.35f
                    btnTouchAt = android.os.SystemClock.uptimeMillis()
                    if (moved) { keepOffLane(v, p); runCatching { wm.updateViewLayout(v, p) }; Prefs.setButtonPos(s, p.x, p.y) }
                    else if (e.actionMasked == MotionEvent.ACTION_UP && !btnLong) onButtonTap(e.rawX, e.rawY)
                }
            }
            true
        }
        runCatching { wm.addView(v, p); button = v; buttonLp = p; buttonLabel = v }
    }

    private fun onButtonTap(x: Float, y: Float) {
        handler.removeCallbacks(flushTaps)
        btnPts += x to y
        if (btnPts.size >= tapsNeeded) {
            btnPts.clear()
            android.util.Log.d("Autoscroll", "button x$tapsNeeded -> toggle")
            if (pill != null) stopAll() else { speed = Prefs.speed(s); startScroll(scrollDown) }
            return
        }
        handler.postDelayed(flushTaps, MULTI_TAP)
    }

    /** Тапи, яких не вистачило для нашого жесту, «проходять крізь» кнопку в програму під нею. */
    private fun forwardTaps(pts: List<Pair<Float, Float>>) {
        val v = button ?: return; val p = buttonLp ?: return
        android.util.Log.d("Autoscroll", "forward ${pts.size} tap(s)")
        ignoreOutsideUntil = android.os.SystemClock.uptimeMillis() + 300 + pts.size * 150
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(v, p) }
        val restore = {
            if (button === v) {
                p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                runCatching { wm.updateViewLayout(v, p) }
            }
        }
        handler.postDelayed({
            val g = android.accessibilityservice.GestureDescription.Builder()
            pts.take(10).forEachIndexed { i, (x, y) ->
                g.addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(
                    android.graphics.Path().apply { moveTo(x, y) }, i * 130L, 40L))
            }
            val ok = runCatching {
                s.dispatchGesture(g.build(), object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(d: android.accessibilityservice.GestureDescription?) { handler.postDelayed(restore, 50) }
                    override fun onCancelled(d: android.accessibilityservice.GestureDescription?) { handler.post(restore) }
                }, handler)
            }.getOrDefault(false)
            if (!ok) restore()
        }, 60)
    }

    /** Маленька шкала біля кнопки (скрол на цей час зупиняється). Тап поза нею — згорнути. */
    @SuppressLint("ClickableViewAccessibility")
    private fun expandScale() {
        if (expanded != null) return
        val bx = buttonLp?.x ?: 0; val by = buttonLp?.y ?: 0
        val bw = button?.width ?: 0; val bh = button?.height ?: dp(36)
        handler.removeCallbacks(resume); scroller.stop(); hideWatcher(); hidePill()
        hideButton()
        speed = Prefs.speed(s)

        val box = LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            background = round(0xF7121418.toInt(), dp(20).toFloat(), 0x447CF5C4)
            setPadding(dp(8), dp(8), dp(8), dp(2))
            elevation = dp(16).toFloat()
        }
        // зверху: ▲ Вгору, ▼ Вниз і червоний ✕ — закрити автоскрол повністю
        val row = LinearLayout(s).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun dirBtn(t: String, down: Boolean) = text(t, 13f, 0xFF0E1013.toInt(), true).apply {
            background = round(SpeedScaleView.ACCENT, dp(16).toFloat())
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setOnClickListener { collapseScale(true); startScroll(down) }
        }
        row.addView(dirBtn("▲ Вгору", false), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(4), 0) })
        row.addView(dirBtn("▼ Вниз", true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(6), 0) })
        val closeAll = text("✕", 14f, Color.WHITE, true).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE5393B.toInt()) }
            contentDescription = "Закрити Автоскрол повністю"
            setOnClickListener { shutdown() }
        }
        row.addView(closeAll, LinearLayout.LayoutParams(dp(30), dp(30)))
        box.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // під ними — тільки шкала: швидкість вибирається пальцем
        val scale = SpeedScaleView(s).apply { this.value = speed; compact = true }
        scale.onChange = { v ->
            val c = Prefs.clamp(v)
            speed = c; Prefs.setSpeed(s, c); scroller.speed = c
        }
        box.addView(scale, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // тап будь-де поза шкалою — згорнути її назад у кнопку
        box.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) collapseScale(true)
            false
        }

        val w = dp(240); val h = dp(130)
        val sw = s.resources.displayMetrics.widthPixels; val sh = screenH()
        val p = params(w, WRAP_CONTENT, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bx + bw / 2 - w / 2).coerceIn(dp(8), (sw - w - dp(8)).coerceAtLeast(dp(8)))
            // під кнопкою, а якщо внизу не вміщається — над нею
            y = (if (by + bh + dp(6) + h < sh - navBarH()) by + bh + dp(6) else by - h - dp(6))
                .coerceIn(dp(24), (sh - navBarH() - h).coerceAtLeast(dp(24)))
        }
        runCatching { wm.addView(box, p); expanded = box }
    }

    /** Згорнути шкалу назад у напівпрозору кнопку. */
    private fun collapseScale(showBack: Boolean) {
        expanded?.let { runCatching { wm.removeView(it) } }
        expanded = null
        if (showBack) {
            if (Prefs.buttonOn(s)) showButton()
            if (pill == null && menu == null) ensureWatcher()
        }
    }

    /** Кнопка має лишатися над невидимим шаром, тому після шару додаємо її заново. */
    private fun raiseButton() {
        val v = button ?: return; val p = buttonLp ?: return
        keepOffLane(v, p)
        runCatching { wm.removeView(v); wm.addView(v, p) }
    }

    /**
     * Кнопка не може стояти на смузі, якою гортає «віртуальний палець»:
     * інакше він натискає і тягне саму кнопку. Зсуваємо її в бік.
     */
    private fun keepOffLane(v: View, p: WindowManager.LayoutParams) {
        val w = if (v.width > 0) v.width else dp(75)
        val cx = (s.resources.displayMetrics.widthPixels * Scroller.X_FRACTION).toInt()
        val l = cx - dp(16); val r = cx + dp(16)
        if (p.x < r && p.x + w > l) p.x = if (p.x + w / 2 < cx && l - w >= 0) l - w else r
    }

    private fun hideButton() {
        button?.let { runCatching { wm.removeView(it) } }
        button = null; buttonLp = null; buttonLabel = null
    }

    private fun params(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
    )

    private fun round(color: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke != 0) setStroke(dp(1.5), stroke)
    }

    private fun text(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(s).apply {
        text = t; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    // ---------------- Коло ----------------
    private fun showMenu() {
        hidePill(); hideWatcher()
        if (menu != null) return
        speed = Prefs.speed(s)
        val root = FrameLayout(s).apply { setBackgroundColor(0x99000000.toInt()) }
        root.setOnClickListener { hideMenu() }

        val size = dp(290)
        val circle = FrameLayout(s).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(0xF01A1D23.toInt()); setStroke(dp(1.5), 0x33FFFFFF)
            }
            isClickable = true // кліки по колу не закривають меню
            elevation = dp(12).toFloat()
        }
        val col = LinearLayout(s).apply { orientation = LinearLayout.VERTICAL }

        val up = text("▲\nВГОРУ", 15f, Color.WHITE, true).apply {
            setLineSpacing(0f, 1.1f)
            setOnClickListener { startScroll(false) }
        }
        val downBtn = text("ВНИЗ\n▼", 15f, Color.WHITE, true).apply {
            setLineSpacing(0f, 1.1f)
            setOnClickListener { startScroll(true) }
        }

        // центр: значення швидкості, по натисканню — шкала
        val center = FrameLayout(s)
        val speedBtn = text(Prefs.fmt(speed), 30f, SpeedScaleView.ACCENT, true).apply {
            background = round(0x22FFFFFF, dp(30).toFloat(), 0x447CF5C4)
            setPadding(dp(22), dp(6), dp(22), dp(6))
        }
        val hint = text("змінити швидкість", 11f, 0x88FFFFFF.toInt())
        val speedBox = LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(speedBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(hint)
        }
        center.addView(speedBox, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))

        col.addView(up, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        col.addView(center, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1.1f))
        col.addView(downBtn, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        circle.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(circle, FrameLayout.LayoutParams(size, size, Gravity.CENTER))

        // панель шкали (з'являється поверх центру кола)
        val panel = buildScalePanel({ v -> speed = v; speedBtn.text = Prefs.fmt(v) })
        panel.visibility = View.GONE
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(10); rightMargin = dp(10)
        })
        speedBtn.setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        val close = text("✕  Закрити", 14f, 0xCCFFFFFF.toInt()).apply {
            background = round(0x33FFFFFF, dp(22).toFloat())
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener { hideMenu() }
        }
        root.addView(close, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER).apply {
            topMargin = size / 2 + dp(70)
        })

        wm.addView(root, params(MATCH_PARENT, MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE))
        menu = root
    }

    private fun buildScalePanel(onSet: (Float) -> Unit, onDone: ((View) -> Unit)? = null): View {
        val panel = LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            background = round(0xF7121418.toInt(), dp(24).toFloat(), 0x447CF5C4)
            setPadding(dp(6), dp(12), dp(6), dp(12))
            elevation = dp(20).toFloat()
            isClickable = true
        }
        val value = text(Prefs.fmt(speed), 32f, SpeedScaleView.ACCENT, true)
        val scale = SpeedScaleView(s).apply { this.value = speed }
        fun set(v: Float) {
            val c = Prefs.clamp(v)
            value.text = Prefs.fmt(c); scale.value = c; onSet(c); Prefs.setSpeed(s, c)
            buttonLabel?.text = "⇅ " + Prefs.fmt(c)
            scroller.speed = c
        }
        scale.onChange = { set(it) }

        fun btn(t: String, delta: Float) = text(t, 14f, Color.WHITE, true).apply {
            background = round(0x22FFFFFF, dp(18).toFloat())
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setOnClickListener { set(speed + delta) }
        }
        fun lp() = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(3), 0, dp(3), 0) }

        val row = LinearLayout(s).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("−0,1", -0.1f), lp()); row.addView(btn("−0,01", -0.01f), lp())
        row.addView(btn("+0,01", 0.01f), lp()); row.addView(btn("+0,1", 0.1f), lp())

        panel.addView(value)
        panel.addView(scale, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        panel.addView(row)
        val done = text("Готово", 14f, 0xFF0E1013.toInt(), true).apply {
            background = round(SpeedScaleView.ACCENT, dp(20).toFloat())
            setPadding(dp(28), dp(9), dp(28), dp(9))
            setOnClickListener { if (onDone != null) onDone(panel) else panel.visibility = View.GONE }
        }
        panel.addView(done, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.CENTER; topMargin = dp(10)
        })
        return panel
    }

    private fun hideMenu() {
        menu?.let { runCatching { wm.removeView(it) } }
        menu = null
        if (pill == null) ensureWatcher()
    }

    private fun startScroll(down: Boolean) {
        android.util.Log.d("Autoscroll", "startScroll down=$down speed=$speed")
        hideMenu(); hideWatcher(); paused = false
        Prefs.setSpeed(s, speed)
        scrollDown = down
        showShields()
        showPill(down)
        // невелика пауза, щоб вікно меню встигло зникнути
        handler.postDelayed(resume, 250)
    }

    // ---------------- Невидимий шар, що ловить дотики під час скролу ----------------
    // Шар закриває весь екран, крім вузької смужки, якою гортає «віртуальний палець».
    private val shields = mutableListOf<View>()
    private var scrollDown = true
    private var downAt = 0L
    private var lastTapUp = 0L
    private var taps = 0
    private var lastTapDown = 0L
    private val tapsNeeded = 2         // всюди: 2 тапи — увімкнути, 2 тапи — вимкнути

    /** Будь-яка зміна вікна (кнопки «назад», «додому», «вікна») обнуляє серію тапів. */
    fun resetTaps() { taps = 0; lastTapDown = 0; lastTapUp = 0; outsideTaps = 0; lastOutside = 0 }
    private val resume = Runnable { android.util.Log.d("Autoscroll", "resume"); scroller.start(scrollDown, speed) }

    private companion object {
        const val TAP_MAX = 250L        // довше — це утримання
        const val TWO_FINGER_HOLD = 1500L
        const val DEDUPE = 90L          // один і той самий дотик не рахуємо двічі
        const val MULTI_TAP = 400L      // проміжок між тапами в серії
        const val LONG_PRESS = 550L     // утримання кнопки — маленька шкала
        const val TAP_PAUSE = 2500L     // пауза після одного тапу
        const val HOLD_RESUME = 150L    // продовжити після відпускання
    }

    init {
        // перший дотик під час руху «віртуального пальця» система віддає не шару, а скасовує жест
        scroller.onInterrupted = {
            val now = android.os.SystemClock.uptimeMillis()
            if (shields.isNotEmpty() && now - lastTapDown >= DEDUPE && !shieldTap(now)) {
                downAt = now; lastTapUp = now
                handler.postDelayed(resume, TAP_PAUSE)
            }
        }
    }

    // Два пальці одночасно, утримання 1,5 с — вимкнути скрол
    private var fingers = 0
    private val twoFingerStop = Runnable { fingers = 0; stopAll() }
    private fun checkTwoFingers() {
        if (fingers < 2) return
        taps = 0; lastTapDown = 0
        handler.removeCallbacks(twoFingerStop); handler.postDelayed(twoFingerStop, TWO_FINGER_HOLD)
    }

    /** Ще один тап у серії. true — серія набрана, скрол на паузі. */
    private fun shieldTap(now: Long): Boolean {
        handler.removeCallbacks(resume)
        scroller.stop()
        taps = if (now - lastTapDown < MULTI_TAP + TAP_MAX) taps + 1 else 1
        lastTapDown = now
        android.util.Log.d("Autoscroll", "shield tap $taps/$tapsNeeded")
        if (taps >= tapsNeeded) { taps = 0; lastTapDown = 0; pause(); return true }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onShieldTouch(e: MotionEvent): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> { fingers++; checkTwoFingers(); return true }
            MotionEvent.ACTION_POINTER_UP -> { fingers = (fingers - 1).coerceAtLeast(0); handler.removeCallbacks(twoFingerStop); return true }
            MotionEvent.ACTION_DOWN -> {
                fingers++
                // другий палець на іншій половині екрана — це не тап, а жест двома пальцями
                if (fingers >= 2) { checkTwoFingers(); return true }
                // цей дотик уже зарахований через перерваний жест
                if (now - lastTapDown < DEDUPE) { downAt = now; return true }
                dragY = e.rawY; dragging = false; dragPending = 0f
                if (shieldTap(now)) return true
                downAt = now
            }
            // затиснули палець і ведете вгору-вниз — сторінка їде за пальцем
            MotionEvent.ACTION_MOVE -> {
                if (fingers >= 2) return true
                val dy = e.rawY - dragY
                if (!dragging && Math.abs(dy) > dp(10)) { dragging = true; taps = 0; lastTapDown = 0; handler.removeCallbacks(resume) }
                if (dragging) {
                    dragPending += dy; dragY = e.rawY
                    handler.removeCallbacks(flushDrag); handler.postDelayed(flushDrag, 30)
                }
                return true
            }
            // CANCEL приходить, коли дотик людини зупиняє віртуальний палець, — це той самий тап
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                fingers = (fingers - 1).coerceAtLeast(0); handler.removeCallbacks(twoFingerStop)
                if (dragging) {
                    dragging = false; handler.removeCallbacks(flushDrag)
                    val left = dragPending; dragPending = 0f
                    lastTapUp = 0
                    handler.postDelayed(resume, injectDrag(left) + HOLD_RESUME)
                } else if (now - downAt < TAP_MAX) {
                    lastTapUp = now
                    handler.postDelayed(resume, TAP_PAUSE)
                } else {
                    lastTapUp = 0
                    handler.postDelayed(resume, HOLD_RESUME)
                }
            }
        }
        return true
    }

    private var dragY = 0f
    private var dragging = false
    private var dragPending = 0f
    private var dragBusyUntil = 0L
    private val flushDrag = Runnable { if (dragging && Math.abs(dragPending) >= 1f) { val d = dragPending; dragPending = 0f; injectDrag(d) } }

    /**
     * Протягнути сторінку на dy пікселів (як пальцем) смугою «віртуального пальця».
     * Наприкінці палець завмирає, щоб сторінка не летіла далі за інерцією. Повертає тривалість, мс.
     */
    private fun injectDrag(dy: Float): Long {
        if (Math.abs(dy) < 1f) return 0
        val now = android.os.SystemClock.uptimeMillis()
        // попередній мазок ще не закінчився — додамо до наступного
        if (now < dragBusyUntil && dragging) { dragPending += dy; return 0 }
        val h = screenH() - navBarH()
        val slop = dp(9) * Math.signum(dy) // програма «з'їдає» перші пікселі, поки не зрозуміє, що це прокрутка
        val total = (dy + slop).coerceIn(-h * 0.8f, h * 0.8f)
        val x = s.resources.displayMetrics.widthPixels * Scroller.X_FRACTION
        val y0 = (h / 2f - total / 2f)
        val dur = (Math.abs(total) * 0.9f).toLong().coerceIn(60, 400)
        val move = android.accessibilityservice.GestureDescription.StrokeDescription(
            android.graphics.Path().apply { moveTo(x, y0); lineTo(x, y0 + total) }, 0, dur, true)
        val hold = move.continueStroke(android.graphics.Path().apply { moveTo(x, y0 + total); lineTo(x, y0 + total) }, 0, 90, false)
        dragBusyUntil = now + dur + 120
        android.util.Log.d("Autoscroll", "drag $dy dur=$dur")
        runCatching {
            s.dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(move).build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: android.accessibilityservice.GestureDescription?) {
                        runCatching { s.dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(hold).build(), null, handler) }
                    }
                    override fun onCancelled(g: android.accessibilityservice.GestureDescription?) { android.util.Log.d("Autoscroll", "drag cancelled") }
                }, handler)
        }
        return dur + 120
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showShields() {
        hideShields()
        val w = s.resources.displayMetrics.widthPixels
        val cx = (w * Scroller.X_FRACTION).toInt()
        val gap = dp(6)
        listOf(0 to cx - gap, cx + gap to w).forEach { (from, to) ->
            val v = View(s).apply { setOnTouchListener { _, e -> onShieldTouch(e) } }
            // шар закінчується над панеллю навігації — «назад», «додому», «вікна» працюють як звичайно
            val p = params(to - from, screenH() - navBarH(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS).apply {
                gravity = Gravity.TOP or Gravity.START; x = from; y = 0
            }
            wm.addView(v, p); shields += v
        }
        raiseButton()
    }

    private fun screenH(): Int =
        if (android.os.Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.height()
        else android.util.DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }.heightPixels

    private fun navBarH(): Int {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val i = wm.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars() or android.view.WindowInsets.Type.mandatorySystemGestures())
            return maxOf(i.bottom, dp(24))
        }
        val id = s.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return maxOf(if (id > 0) s.resources.getDimensionPixelSize(id) else 0, dp(24))
    }

    private fun hideShields() {
        fingers = 0; handler.removeCallbacks(twoFingerStop)
        shields.forEach { runCatching { wm.removeView(it) } }
        shields.clear()
    }

    // ---------------- Потрійний тап: будь-де — старт, під час скролу — пауза, на паузі — продовжити ----------------
    // Під час паузи шару немає, тож текст можна виділяти. Дотики ловимо крихітним вікном
    // з FLAG_WATCH_OUTSIDE_TOUCH: воно отримує ACTION_OUTSIDE, а дотик іде далі в програму.
    private var watcher: View? = null
    private var lastOutside = 0L
    private var outsideTaps = 0
    private var paused = false

    private fun pause() {
        handler.removeCallbacks(resume)
        scroller.stop(); hideShields(); setPillPaused(true)
        paused = true
        ensureWatcher()
    }

    private fun onTripleOutside() {
        android.util.Log.d("Autoscroll", "multi-tap outside menu=${menu != null} exp=${expanded != null} paused=$paused pill=${pill != null}")
        when {
            menu != null || expanded != null -> {}
            paused -> unpause()
            pill == null -> startScroll(scrollDown)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureWatcher() {
        if (watcher != null || !Prefs.active(s) || suspended) return
        val v = View(s).apply {
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    // поки відкрита клавіатура — людина набирає текст, наш жест не діє
                    if ((s as? AutoScrollService)?.typingOrLocked() == true) { outsideTaps = 0; lastOutside = 0; return@setOnTouchListener false }
                    val now = android.os.SystemClock.uptimeMillis()
                    // це наші ж тапи, передані програмі крізь кнопку
                    if (now < ignoreOutsideUntil) { outsideTaps = 0; lastOutside = 0; return@setOnTouchListener false }
                    outsideTaps = if (now - lastOutside < MULTI_TAP) outsideTaps + 1 else 1
                    lastOutside = now
                    if (outsideTaps >= tapsNeeded) {
                        outsideTaps = 0; lastOutside = 0
                        // тапи по нашій кнопці теж «зовні» для цього вікна — їх обробляє сама кнопка
                        handler.postDelayed({
                            if (android.os.SystemClock.uptimeMillis() - btnTouchAt > 800) onTripleOutside()
                        }, 60)
                    }
                }
                false
            }
        }
        val p = params(1, 1,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { wm.addView(v, p); watcher = v }
    }

    private fun unpause() {
        hideWatcher(); setPillPaused(false); paused = false
        showShields()
        handler.postDelayed(resume, 300)
    }

    private fun hideWatcher() {
        watcher?.let { runCatching { wm.removeView(it) } }
        watcher = null; lastOutside = 0; outsideTaps = 0
    }

    private fun setPillPaused(paused: Boolean) {
        buttonLabel?.text = (if (paused) "⏸ " else if (scrollDown) "▼ " else "▲ ") + Prefs.fmt(speed)
    }

    fun stopAll() {
        handler.removeCallbacks(resume)
        scroller.stop(); hidePill()
        if (menu == null) ensureWatcher()
    }

    // ---------------- Стан скролу ----------------
    // Окремої пігулки більше немає: напрямок і швидкість показує та сама згорнута шкала.
    private fun showPill(down: Boolean) {
        pill = View(s) // лише позначка «сесія скролу триває», на екран не додається
        buttonLabel?.text = (if (down) "▼ " else "▲ ") + Prefs.fmt(speed)
        raiseButton()
    }

    private fun hidePill() {
        hideShields()
        pill?.let { runCatching { wm.removeView(it) } }
        pill = null; paused = false
        buttonLabel?.text = "⇅ " + Prefs.fmt(Prefs.speed(s))
    }
}




