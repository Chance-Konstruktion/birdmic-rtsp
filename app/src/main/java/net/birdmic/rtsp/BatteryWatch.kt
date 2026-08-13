package net.birdmic.rtsp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Beobachtet Temperatur und Ladezustand des Akkus.
 *
 * Ein Altgeraet, das dauerhaft bei 100 Prozent am Kabel haengt und dabei warm
 * wird, bekommt ueber Monate einen aufgeblaehten Akku -- ein realer
 * Brandrisiko-Faktor. Eine Warnung in der Benachrichtigung ist billiger als ein
 * Schaden.
 */
class BatteryWatch(
    context: Context,
    private val warnCelsius: Int,
    private val onEvent: ((EventLog.Kind, String) -> Unit)? = null,
) {
    // Bewusst der Anwendungskontext: der Zustand haengt am Singleton in
    // RtspService.State, und ein Service-Kontext waere dort ein Leck.
    private val appContext = context.applicationContext
    @Volatile var temperatureCelsius: Float = 0f
        private set

    @Volatile var chargePercent: Int = -1
        private set

    @Volatile var charging: Boolean = false
        private set

    /** true, sobald die Warnschwelle ueberschritten ist. */
    @Volatile var tooHot: Boolean = false
        private set

    private var warnedHot = false
    private var warnedDischarging = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            // Temperatur kommt in Zehntelgrad.
            val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            if (tenths > 0) temperatureCelsius = tenths / 10f

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) chargePercent = level * 100 / scale

            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            evaluate()
        }
    }

    private fun evaluate() {
        tooHot = warnCelsius > 0 && temperatureCelsius >= warnCelsius

        if (tooHot && !warnedHot) {
            warnedHot = true
            onEvent?.invoke(
                EventLog.Kind.WARNING,
                "Akku ${"%.1f".format(temperatureCelsius)} °C – Warnschwelle $warnCelsius °C erreicht",
            )
        } else if (!tooHot && warnedHot && temperatureCelsius < warnCelsius - 3) {
            // Hysterese, damit es an der Schwelle nicht flattert.
            warnedHot = false
        }

        // Nicht am Strom heisst: irgendwann ist Schluss. Einmalig melden.
        if (!charging && !warnedDischarging && chargePercent in 0..100) {
            warnedDischarging = true
            onEvent?.invoke(EventLog.Kind.WARNING, "Läuft auf Akku ($chargePercent %) – kein Ladekabel")
        } else if (charging) {
            warnedDischarging = false
        }
    }

    fun start() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        // Sticky Broadcast: liefert den aktuellen Stand sofort mit.
        // Der Export-Hinweis ist ab Android 14 Pflicht, auch bei Systemsendungen.
        androidx.core.content.ContextCompat.registerReceiver(
            appContext, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun stop() {
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    /** Kurzform fuer die Anzeige, z.B. "34,5 °C · 100 % · lädt". */
    fun summary(): String {
        if (chargePercent < 0) return "-"
        val temp = if (temperatureCelsius > 0) "%.1f °C · ".format(temperatureCelsius) else ""
        return temp + "$chargePercent % · " + if (charging) "lädt" else "Akku"
    }
}
