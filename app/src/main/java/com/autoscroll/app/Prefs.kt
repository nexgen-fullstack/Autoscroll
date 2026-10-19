package com.autoscroll.app

import android.content.Context

object Prefs {
    const val MIN_SPEED = 0.10f
    const val MAX_SPEED = 1.75f
    private const val FILE = "autoscroll"

    fun speed(c: Context) = c.getSharedPreferences(FILE, 0).getFloat("speed2", 0.25f).coerceIn(MIN_SPEED, MAX_SPEED)
    fun setSpeed(c: Context, v: Float) = c.getSharedPreferences(FILE, 0).edit().putFloat("speed2", v).apply()

    /** Швидкість 1× у dp за секунду */
    fun baseDp(c: Context) = c.getSharedPreferences(FILE, 0).getInt("base", 120)
    fun setBaseDp(c: Context, v: Int) = c.getSharedPreferences(FILE, 0).edit().putInt("base", v).apply()

    /** false — закрито червоним ✕: нічого не показується і не реагує, поки не відкриють додаток */
    fun active(c: Context) = c.getSharedPreferences(FILE, 0).getBoolean("active", true)
    fun setActive(c: Context, v: Boolean) = c.getSharedPreferences(FILE, 0).edit().putBoolean("active", v).apply()

    /** Напівпрозора кнопка на екрані */
    fun buttonOn(c: Context) = c.getSharedPreferences(FILE, 0).getBoolean("btn", true)
    fun setButtonOn(c: Context, v: Boolean) = c.getSharedPreferences(FILE, 0).edit().putBoolean("btn", v).apply()
    fun buttonPos(c: Context) = c.getSharedPreferences(FILE, 0).let { it.getInt("bx", -1) to it.getInt("by", -1) }
    fun setButtonPos(c: Context, x: Int, y: Int) = c.getSharedPreferences(FILE, 0).edit().putInt("bx", x).putInt("by", y).apply()

    fun clamp(v: Float) = (Math.round(v.coerceIn(MIN_SPEED, MAX_SPEED) * 100f) / 100f)
    fun fmt(v: Float) = String.format(java.util.Locale.US, "%.2f×", v).replace('.', ',')
}

