package net.birdmic.rtsp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Startet den Stream nach einem Neustart wieder -- das Geraet haengt am
 * Ladekabel im Garten und soll nach einem Stromausfall von allein zurueckkommen.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = Prefs(context)
        if (!prefs.autostart || !prefs.wasRunning) return

        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "Autostart uebersprungen: Mikrofonberechtigung fehlt")
            return
        }

        Log.i(TAG, "Autostart nach $action")
        // Ab Android 14 darf ein Mikrofon-Vordergrunddienst nicht aus
        // BOOT_COMPLETED heraus gestartet werden. Der Dienst faengt das selbst
        // ab und meldet es; hier bleibt nur, den Aufruf nicht abstuerzen zu lassen.
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RtspService::class.java).setAction(RtspService.ACTION_START),
            )
        }.onFailure { Log.w(TAG, "Autostart von Android abgelehnt", it) }
    }

    companion object {
        private const val TAG = "BirdMic/Boot"
    }
}
