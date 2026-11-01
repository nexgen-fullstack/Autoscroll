package com.autoscroll.app

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class AutoScrollService : AccessibilityService() {
    companion object {
        var instance: AutoScrollService? = null
            private set
        private const val COMBO_WINDOW = 500L
    }

    private lateinit var overlay: Overlay
    private lateinit var scroller: Scroller

    private var upAt = 0L; private var upHeld = false; private var upEaten = false
    private var dnAt = 0L; private var dnHeld = false; private var dnEaten = false


    override fun onServiceConnected() {
        instance = this
        scroller = Scroller(this)
        overlay = Overlay(this, scroller)
    }

    fun refreshButton() { if (::overlay.isInitialized) overlay.refreshButton() }
    fun wake() { if (::overlay.isInitialized) overlay.wake() }

    fun toggle() { if (::overlay.isInitialized) overlay.toggle() }

    /** Гучність + і − натиснуті одночасно → відкрити/закрити коло. Окремі натискання працюють як звичайно. */
    override fun onKeyEvent(e: KeyEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        // «Назад» після відкритого двома тапами посилання — скрол їде далі (саму кнопку не перехоплюємо)
        if (e.keyCode == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP && ::overlay.isInitialized) overlay.onBack()
        val isUp = e.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (!isUp && e.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!Prefs.active(this)) return false

        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.repeatCount > 0) return if (isUp) upEaten else dnEaten
            if (isUp) {
                upHeld = true; upAt = now; upEaten = false
                if (dnHeld && now - dnAt < COMBO_WINDOW) { upEaten = true; toggle(); return true }
            } else {
                dnHeld = true; dnAt = now; dnEaten = false
                if (upHeld && now - upAt < COMBO_WINDOW) { dnEaten = true; toggle(); return true }
            }
            return false
        }
        if (e.action == KeyEvent.ACTION_UP) {
            return if (isUp) { upHeld = false; upEaten.also { upEaten = false } }
            else { dnHeld = false; dnEaten.also { dnEaten = false } }
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || !::overlay.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || isKeyboard(pkg)) return
        android.util.Log.d("Autoscroll", "window: $pkg ${event.className}")
        // «назад», «додому», «вікна», перехід в іншу програму чи екран — скрол вимикається.
        // Спливаючі віконця всередині тієї ж програми (меню «Копіювати» після виділення слова) — ні.
        val cls = event.className?.toString().orEmpty()
        val sameScreen = pkg == lastPkg && cls == lastActivity
        val popup = !cls.endsWith("Activity") && pkg != "com.android.systemui" && !isLauncher(pkg)
        if (!popup && !sameScreen) overlay.stopAll()
        if (cls.endsWith("Activity")) lastActivity = cls
        // серію тапів обнуляємо лише при зміні програми (або «додому»/«вікна»),
        // а не на кожне вікно всередині програми — інакше перший тап «губиться»
        if (pkg != lastPkg || pkg == "com.android.systemui" || isLauncher(pkg)) overlay.resetTaps()
        if (isLauncher(pkg)) overlay.clearResume()
        lastPkg = pkg
    }
    private var lastPkg = ""
    private var lastActivity = ""
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val launchers by lazy {
        packageManager.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0
        ).map { it.activityInfo.packageName }.toSet()
    }
    private fun isLauncher(pkg: String) = pkg in launchers

    /**
     * Зараз вводять текст чи код: відкрита клавіатура, екран блокування (PIN, пароль),
     * набір номера, системне вікно — тоді тапи по екрану не вмикають автоскрол.
     */
    fun typingOrLocked(): Boolean {
        if (getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) return true
        if (lastPkg == "com.android.systemui" || lastPkg in DIALERS) return true
        return keyboardVisible()
    }

    private val DIALERS = setOf(
        "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer", "com.android.contacts",
        "com.motorola.dialer", "com.android.incallui",
    )

    /** Чи є на екрані щось, що гортається (калькулятор, набір номера — ні). Якщо не вдалося перевірити — вважаємо, що є. */
    fun canScroll(): Boolean = runCatching {
        val roots = windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root }
        if (roots.isEmpty()) return true
        var budget = 1500
        fun scan(n: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
            if (n == null || budget-- <= 0) return false
            if (n.isScrollable) return true
            for (i in 0 until n.childCount) if (scan(n.getChild(i))) return true
            return false
        }
        roots.any { scan(it) } || budget <= 0
    }.getOrDefault(true)

    /** Чи відкрита зараз клавіатура (тоді тапи по екрану — це набір тексту, а не наш жест). */
    fun keyboardVisible() = runCatching {
        windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }.getOrDefault(false)

    /** Клавіатура, що з'являється, — це не перехід в іншу програму. */
    private fun isKeyboard(pkg: String) =
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.enabledInputMethodList?.any { it.packageName == pkg } == true

    override fun onInterrupt() { if (::scroller.isInitialized) scroller.stop() }

    override fun onDestroy() {
        instance = null
        if (::overlay.isInitialized) overlay.destroy()
        super.onDestroy()
    }
}




