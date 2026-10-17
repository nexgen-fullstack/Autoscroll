package com.autoscroll.app

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Плитка у шторці швидких налаштувань: відкриває коло. */
class ScrollTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (AutoScrollService.instance != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val svc = AutoScrollService.instance
        if (svc == null) {
            val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION") startActivityAndCollapse(i)
            }
            return
        }
        if (Build.VERSION.SDK_INT >= 31) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        Handler(mainLooper).postDelayed({ svc.toggle() }, 400)
    }
}
