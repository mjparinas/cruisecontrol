package dev.aiauto

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.graphics.drawable.Icon

/** Opens the voice first new task screen from Quick Settings. */
class QuickTaskTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let {
            it.state = Tile.STATE_ACTIVE
            it.label = "New task"
            it.subtitle = "Cruise Control"
            it.icon = Icon.createWithResource(this, R.drawable.ic_glyph)
            it.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        startActivityAndCollapse(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_WIDGET_MIC)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }
}
