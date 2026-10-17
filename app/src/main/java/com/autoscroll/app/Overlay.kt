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
    private var pillLabel: TextView? = null
    private var speed = Prefs.speed(s)

    fun toggle() {
        when {
            menu != null -> hideMenu()
            scroller.running || pill != null -> { handler.removeCallbacks(resume); scroller.stop(); hideWatcher(); showMenu() }
            else -> showMenu()
        }
    }

    init { handler.post { ensureWatcher() } }

    fun destroy() { stopAll(); hideMenu(); hideWatcher() }

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
        val panel = buildScalePanel { v -> speed = v; speedBtn.text = Prefs.fmt(v) }
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

    private fun buildScalePanel(onSet: (Float) -> Unit): View {
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
            setOnClickListener { panel.visibility = View.GONE }
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
    private var tapsNeeded = 2

    /** Яка програма зараз на екрані: від неї залежить, скільки тапів потрібно (2 чи 3). */
    fun onAppChanged(pkg: String) { tapsNeeded = if (pkg in DOUBLE_TAP_APPS) 3 else 2; android.util.Log.d("Autoscroll", "taps=$tapsNeeded") }

    /** Будь-яка зміна вікна (кнопки «назад», «додому», «вікна») обнуляє серію тапів. */
    fun resetTaps() { taps = 0; lastTapDown = 0; lastTapUp = 0; outsideTaps = 0; lastOutside = 0 }
    private val resume = Runnable { scroller.start(scrollDown, speed) }

    private companion object {
        const val TAP_MAX = 250L        // довше — це утримання
        const val DEDUPE = 90L          // один і той самий дотик не рахуємо двічі
        const val MULTI_TAP = 400L      // проміжок між тапами в серії
        // Програми, де подвійний тап уже щось робить (лайк, зум, реакція) — там наш жест потрійний.
        val DOUBLE_TAP_APPS = setOf(
            "com.instagram.android", "com.instagram.barcelona", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
            "com.google.android.youtube", "com.facebook.katana", "com.facebook.lite", "com.facebook.orca",
            "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
            "com.whatsapp", "com.whatsapp.w4b", "com.viber.voip", "com.snapchat.android", "com.pinterest",
            "com.reddit.frontpage", "com.twitter.android", "com.linkedin.android", "com.vkontakte.android",
            "com.google.android.apps.photos", "com.sec.android.gallery3d", "com.miui.gallery", "com.android.gallery3d",
            "com.google.android.apps.maps", "com.android.chrome", "org.mozilla.firefox", "com.opera.browser",
            "com.sec.android.app.sbrowser", "com.amazon.kindle", "com.adobe.reader", "com.google.android.apps.docs",
            "com.netflix.mediaclient", "com.google.android.apps.youtube.music", "com.tinder", "com.bumble.app",
        )
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
            MotionEvent.ACTION_DOWN -> {
                // цей дотик уже зарахований через перерваний жест
                if (now - lastTapDown < DEDUPE) { downAt = now; return true }
                if (shieldTap(now)) return true
                downAt = now
            }
            // CANCEL приходить, коли дотик людини зупиняє віртуальний палець, — це той самий тап
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (now - downAt < TAP_MAX) {
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
        android.util.Log.d("Autoscroll", "multi-tap outside")
        when {
            menu != null -> {}
            paused -> unpause()
            pill == null -> startScroll(scrollDown)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureWatcher() {
        if (watcher != null) return
        val v = View(s).apply {
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    // поки відкрита клавіатура — людина набирає текст, наш жест не діє
                    if ((s as? AutoScrollService)?.keyboardVisible() == true) { outsideTaps = 0; lastOutside = 0; return@setOnTouchListener false }
                    val now = android.os.SystemClock.uptimeMillis()
                    outsideTaps = if (now - lastOutside < MULTI_TAP) outsideTaps + 1 else 1
                    lastOutside = now
                    if (outsideTaps >= tapsNeeded) { outsideTaps = 0; lastOutside = 0; onTripleOutside() }
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
        pillLabel?.text = (if (paused) "⏸ " else if (scrollDown) "▼ " else "▲ ") + Prefs.fmt(speed)
    }

    fun stopAll() {
        handler.removeCallbacks(resume)
        scroller.stop(); hidePill()
        if (menu == null) ensureWatcher()
    }

    // ---------------- Пігулка під час скролу ----------------
    @SuppressLint("ClickableViewAccessibility")
    private fun showPill(down: Boolean) {
        pill?.let { runCatching { wm.removeView(it) } }; pill = null
        val box = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = round(0xE61A1D23.toInt(), dp(24).toFloat(), 0x557CF5C4)
            setPadding(dp(14), dp(8), dp(6), dp(8))
            elevation = dp(8).toFloat()
        }
        val label = text((if (down) "▼ " else "▲ ") + Prefs.fmt(speed), 15f, SpeedScaleView.ACCENT, true)
        pillLabel = label
        val stop = text("■", 16f, Color.WHITE).apply {
            background = round(0x33FFFFFF, dp(18).toFloat())
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { stopAll() }
        }
        box.addView(label)
        box.addView(stop, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(10) })

        val p = params(WRAP_CONTENT, WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN).apply {
            gravity = Gravity.TOP or Gravity.END; x = dp(10); y = dp(120)
        }
        // тап по написі — пауза і відкрити коло; перетягування — перемістити
        var startY = 0f; var startPY = 0; var moved = false
        label.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; startPY = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - startY).toInt()
                    if (Math.abs(dy) > dp(6)) moved = true
                    if (moved) { p.y = (startPY + dy).coerceAtLeast(0); runCatching { wm.updateViewLayout(box, p) } }
                }
                MotionEvent.ACTION_UP -> if (!moved) { handler.removeCallbacks(resume); scroller.stop(); hideWatcher(); showMenu() }
            }
            true
        }
        wm.addView(box, p)
        pill = box
    }

    private fun hidePill() {
        hideShields()
        pill?.let { runCatching { wm.removeView(it) } }
        pill = null; pillLabel = null; paused = false
    }
}




