package com.autoscroll.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Екран налаштувань: увімкнення сервісу, базова швидкість, інструкція. */
class MainActivity : Activity() {
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Number) = (v.toFloat() * d).toInt()
    private lateinit var status: TextView
    private lateinit var enableBtn: TextView
    private lateinit var btnToggle: TextView
    private fun refreshBtnToggle() {
        btnToggle.text = if (Prefs.buttonOn(this)) "Згорнута шкала на екрані: показана" else "Згорнута шкала на екрані: прихована"
    }
    private val baseBtns = mutableListOf<Pair<Int, TextView>>()

    private fun bg(color: Int, r: Float, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = r * d; if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun tv(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); setLineSpacing(0f, 1.25f)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0E1013.toInt()
        window.navigationBarColor = 0xFF0E1013.toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(32))
        }
        fun gap(h: Int) = col.addView(android.view.View(this), LinearLayout.LayoutParams(1, dp(h)))

        col.addView(tv("Автоскрол", 30f, Color.WHITE, true))
        gap(4)
        col.addView(tv("Плавно гортає будь-яку програму з точною швидкістю.", 15f, 0x99FFFFFF.toInt()))
        gap(20)

        status = tv("", 15f, Color.WHITE, true).apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        col.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        gap(10)
        enableBtn = tv("", 15f, 0xFF0E1013.toInt(), true).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(14), dp(16), dp(14))
            background = bg(SpeedScaleView.ACCENT, 26f)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        col.addView(enableBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        gap(10)
        val test = tv("Відкрити коло зараз", 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(14), dp(16), dp(14))
            background = bg(0x22FFFFFF, 26f)
            setOnClickListener { AutoScrollService.instance?.toggle() }
        }
        col.addView(test, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        gap(10)
        btnToggle = tv("", 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(14), dp(16), dp(14))
            background = bg(0x22FFFFFF, 26f)
            setOnClickListener {
                Prefs.setButtonOn(this@MainActivity, !Prefs.buttonOn(this@MainActivity))
                AutoScrollService.instance?.refreshButton(); refreshBtnToggle()
            }
        }
        col.addView(btnToggle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        gap(28)

        col.addView(tv("Швидкість 1×", 18f, Color.WHITE, true))
        gap(4)
        col.addView(tv("Скільки гортати за секунду при швидкості 1,00×. Решта швидкостей — множник від неї.", 13f, 0x99FFFFFF.toInt()))
        gap(10)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(60 to "Повільно", 120 to "Середньо", 200 to "Швидко", 320 to "Дуже").forEach { (v, name) ->
            val b = tv(name, 13f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12))
                setOnClickListener { Prefs.setBaseDp(this@MainActivity, v); refreshBase() }
            }
            baseBtns += v to b
            row.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        gap(28)

        col.addView(tv("Як викликати коло", 18f, Color.WHITE, true))
        gap(8)
        listOf(
            "1. Гучність + і Гучність − одночасно (натисніть обидві разом).",
            "2. Жест спеціальних можливостей: у Налаштування → Спеціальні можливості → Автоскрол → «Ярлик» виберіть «Кнопка/жест спеціальних можливостей». Потім свайп двома пальцями знизу вгору відкриває коло.",
            "3. Плитка «Автоскрол» у шторці швидких налаштувань (додайте через олівець ✎).",
        ).forEach { col.addView(tv(it, 14f, 0xDDFFFFFF.toInt())); gap(8) }
        gap(16)

        col.addView(tv("Як користуватися", 18f, Color.WHITE, true))
        gap(8)
        listOf(
            "• У колі натисніть ▲ ВГОРУ або ▼ ВНИЗ — почнеться скрол.",
            "• Торкніться цифри в центрі кола — з'явиться шкала 0,25…1,75. Тягніть повзунок на будь-яке значення (1,23; 1,68 …), кнопки ±0,01 і ±0,1 для точності.",
            "• Під час скролу праворуч зверху є маленька панель: тап по ній — пауза і коло, ■ — стоп. Її можна перетягнути.",
            "• 2 тапи будь-де — увімкнути скрол; під час скролу 2 тапи — пауза (можна виділяти текст), ще 2 — далі. У програмах, де подвійний тап уже зайнятий (Instagram, TikTok, WhatsApp…), — 3 тапи. Поки відкрита клавіатура, тапи не діють.",
            "• Згорнута шкала (напівпрозора «⇅ 1,00×»): 3 тапи по ній — розгортається шкала, вибираєте швидкість і ▲/▼ — скрол поїхав, шкала згорнулась. Тапи по ній не натискають те, що під нею. Її можна перетягнути.",
            "• Червона кнопка «✕ Закрити Автоскрол повністю» у розгорнутій шкалі прибирає все з екрана і вимикає жести. Щоб повернути — просто відкрийте цей додаток.",
            "• На екрані блокування, при наборі коду, номера чи тексту на клавіатурі тапи по екрану автоскрол не вмикають.",
            "• Два пальці на екрані й утримання 1,5 с — вимкнути скрол.",
            "• Один тап по екрану — пауза 2,5 секунди, потім скрол продовжується.",
            "• Затисніть палець — скрол стоїть, поки тримаєте; відпустили — їде далі.",
        ).forEach { col.addView(tv(it, 14f, 0xDDFFFFFF.toInt())); gap(8) }
        gap(16)
        col.addView(tv("Кнопку живлення і дотик чотирма пальцями Android не дає перехоплювати стороннім додаткам, тому замість них — способи вище.", 12.5f, 0x77FFFFFF.toInt()))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0E1013.toInt())
            addView(col)
        })
    }

    private fun refreshBase() {
        val cur = Prefs.baseDp(this)
        baseBtns.forEach { (v, b) ->
            val on = v == cur
            b.background = bg(if (on) SpeedScaleView.ACCENT else 0x1AFFFFFF, 20f)
            b.setTextColor(if (on) 0xFF0E1013.toInt() else Color.WHITE)
        }
    }

    override fun onResume() {
        super.onResume()
        // відкрили додаток — якщо його закривали червоним ✕, вмикаємо знову
        Prefs.setActive(this, true)
        AutoScrollService.instance?.wake()
        val on = AutoScrollService.instance != null
        status.text = if (on) "●  Сервіс увімкнено — можна користуватися" else "●  Сервіс вимкнено"
        status.setTextColor(if (on) SpeedScaleView.ACCENT else 0xFFFF8A80.toInt())
        status.background = bg(if (on) 0x1A7CF5C4 else 0x1AFF8A80, 18f)
        enableBtn.text = if (on) "Налаштування спец. можливостей" else "Увімкнути Автоскрол"
        refreshBase(); refreshBtnToggle()
    }
}

