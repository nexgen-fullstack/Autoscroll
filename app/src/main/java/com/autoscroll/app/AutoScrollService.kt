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
        android.util.Log.d("Autoscroll", "window: $pkg")
        // «назад», «додому», «вікна», перехід в іншу програму — скрол вимикається
        overlay.stopAll()
        // серію тапів обнуляємо лише при зміні програми (або «додому»/«вікна»),
        // а не на кожне вікно всередині програми — інакше перший тап «губиться»
        if (pkg != lastPkg || pkg == "com.android.systemui" || isLauncher(pkg)) overlay.resetTaps()
        lastPkg = pkg
    }
    private var lastPkg = ""
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




