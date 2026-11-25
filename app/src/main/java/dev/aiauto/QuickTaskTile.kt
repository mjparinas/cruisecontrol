package dev.aiauto

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Opens the voice first new task screen from Quick Settings. */
class QuickTaskTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let {
            it.state = Tile.STATE_ACTIVE
            it.label = "New task"
            // Subtitles arrived in Android 10; older phones just show the label.
            if (Build.VERSION.SDK_INT >= 29) it.subtitle = "Cruise Control"
            it.icon = Icon.createWithResource(this, R.drawable.ic_glyph)
            it.updateTile()
        }
    }

    // The old call only runs below Android 14, where it's the only one there is.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_WIDGET_MIC)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // Android 14 throws for the Intent version in apps that target it, so newer phones get a PendingIntent.
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
