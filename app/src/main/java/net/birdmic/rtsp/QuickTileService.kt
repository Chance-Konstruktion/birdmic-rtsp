package net.birdmic.rtsp

import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/**
 * Kachel in den Schnelleinstellungen zum Starten und Stoppen.
 *
 * Praktisch, wenn das Telefon fest verbaut ist und man nur kurz den Stream
 * anhalten will, ohne die App zu suchen.
 */
class QuickTileService : TileService() {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            // Ohne Mikrofonberechtigung waere der Start sinnlos -- App oeffnen,
            // damit der Nutzer sie erteilen kann.
            openApp()
            return
        }

        if (RtspService.State.running) {
            startService(Intent(this, RtspService::class.java).setAction(RtspService.ACTION_STOP))
        } else {
            ContextCompat.startForegroundService(
                this, Intent(this, RtspService::class.java).setAction(RtspService.ACTION_START),
            )
        }
        // Der Dienst braucht einen Moment, bis sich der Zustand aendert.
        handler.postDelayed({ refresh() }, 500)
    }

    /**
     * Ab Android 14 wirft die Intent-Variante von startActivityAndCollapse eine
     * UnsupportedOperationException -- dort ist ein PendingIntent Pflicht.
     */
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val pending = android.app.PendingIntent.getActivity(
                this, 0, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val running = RtspService.State.running
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.contentDescription = if (running) {
            getString(R.string.tile_running, RtspService.State.clients)
        } else {
            getString(R.string.status_stopped)
        }
        tile.updateTile()
    }
}
