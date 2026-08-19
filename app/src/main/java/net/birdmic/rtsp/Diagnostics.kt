package net.birdmic.rtsp

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Baut den Diagnosebericht -- das, was man verschickt, wenn etwas nicht laeuft.
 *
 * Ein Logbuch allein beantwortet die wichtigen Fragen naemlich nicht: auf
 * welchem Geraet, mit welcher Aufnahmequelle, seit wann, mit wie vielen
 * Neustarts. Deshalb steht das alles im Kopf der Datei, und erst danach kommen
 * die Eintraege -- aelteste zuerst, so wie man einen Fehler nachliest.
 */
object Diagnostics {

    private val FILE_STAMP = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.GERMANY)
    private val HEADER_STAMP = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)

    fun fileName(): String = "birdmic-log-${FILE_STAMP.format(Date())}.txt"

    fun report(context: Context, prefs: Prefs, log: EventLog): String {
        val state = RtspService.State
        val entries = log.all()

        return buildString {
            line("BirdMic ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})")
            line("Bericht vom ${HEADER_STAMP.format(Date())}")
            line("")

            section("Gerät")
            line("Modell:      ${Build.MANUFACTURER} ${Build.MODEL}")
            line("Produkt:     ${Build.BRAND}/${Build.PRODUCT} (${Build.DEVICE})")
            line("Android:     ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            line("Build:       ${Build.DISPLAY}")
            line("")

            section("Einstellungen")
            line("Port:        ${prefs.port}, Pfad /${prefs.path}")
            line("Abtastrate:  ${prefs.sampleRate} Hz, ${if (prefs.channels == 2) "stereo" else "mono"}")
            line("Quelle:      ${SourceChoice.fromValue(prefs.audioSource).label}")
            line("Eingabegerät:${if (prefs.inputDeviceKey.isBlank()) " automatisch" else " ${prefs.inputDeviceKey}"}")
            line("Verstärkung: ${prefs.gain}")
            line("Hochpass:    ${if (prefs.highPassHz == 0) "aus" else "${prefs.highPassHz} Hz"}")
            line("Empfänger:   max ${limit(prefs.maxClients)} gesamt, ${limit(prefs.maxClientsPerIp)} je Adresse")
            line("UDP-Timeout: ${if (prefs.udpTimeoutSeconds == 0) "aus" else "${prefs.udpTimeoutSeconds} s"}")
            line("Zugangsschutz: ${if (prefs.authUser.isBlank()) "aus" else "an (Benutzer ${prefs.authUser})"}")
            line("mDNS:        ${if (prefs.mdnsEnabled) prefs.mdnsName else "aus"}")
            line("Webserver:   ${if (prefs.webEnabled) "Port ${prefs.webPort}" else "aus"}")
            line("Autostart:   ${if (prefs.autostart) "an" else "aus"}")
            line("Akkuwarnung: ${if (prefs.batteryWarnCelsius == 0) "aus" else "${prefs.batteryWarnCelsius} °C"}")
            line("")

            section("Zustand")
            if (state.running) {
                line("Läuft seit:  ${formatDuration(state.uptimeSeconds)}")
                line("Adresse:     ${state.url}")
                line("Empfänger:   ${state.clients}")
                for (name in state.clientNames) line("             $name")
                line("Gesendet:    ${state.bytesSent} Bytes")
                line("Quelle aktiv:${" " + state.source}")
                state.activeMicrophone?.let { line("Mikrofon:    $it") }
                line("Neustarts:   ${state.restarts}")
                line("Übersteuert: ${state.clipped} Samples")
                line("Akku:        ${state.batterySummary}")
            } else {
                line("Stream läuft nicht.")
                state.error?.let { line("Letzter Fehler: $it") }
            }
            line("")

            section("Logbuch (${entries.size} Einträge, älteste zuerst)")
            if (entries.isEmpty()) {
                line("(leer)")
            } else {
                for (entry in entries) line(entry.formatLong())
            }
        }
    }

    private fun limit(value: Int) = if (value == 0) "unbegrenzt" else value.toString()

    private fun StringBuilder.line(text: String) {
        append(text).append('\n')
    }

    private fun StringBuilder.section(title: String) {
        line("--- $title ".padEnd(60, '-'))
    }

    fun formatDuration(seconds: Long): String {
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (days > 0) {
            "%dd %02d:%02d:%02d".format(days, hours, minutes, secs)
        } else {
            "%02d:%02d:%02d".format(hours, minutes, secs)
        }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000L -> "%.0f kB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}
