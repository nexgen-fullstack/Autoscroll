package com.autoscroll.app

import android.content.Context

object Prefs {
    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 2.50f
    private const val FILE = "autoscroll"

    fun speed(c: Context) = c.getSharedPreferences(FILE, 0).getFloat("speed", 1f)
    fun setSpeed(c: Context, v: Float) = c.getSharedPreferences(FILE, 0).edit().putFloat("speed", v).apply()

    /** Швидкість 1× у dp за секунду */
    fun baseDp(c: Context) = c.getSharedPreferences(FILE, 0).getInt("base", 120)
    fun setBaseDp(c: Context, v: Int) = c.getSharedPreferences(FILE, 0).edit().putInt("base", v).apply()

    fun clamp(v: Float) = (Math.round(v.coerceIn(MIN_SPEED, MAX_SPEED) * 100f) / 100f)
    fun fmt(v: Float) = String.format(java.util.Locale.US, "%.2f×", v).replace('.', ',')
}
