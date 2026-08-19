package net.birdmic.rtsp

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

/** Einstellungen, absichtlich klein gehalten. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("birdmic", Context.MODE_PRIVATE)

    var port: Int
        get() = sp.getInt("port", 8554)
        set(v) = sp.edit().putInt("port", v).apply()

    /**
     * Der Pfad ist reine Kosmetik -- der Server liefert auf jedem Pfad denselben
     * Track. "audio.wav" macht in Logs und Konfigurationsdateien sofort klar,
     * was da kommt.
     */
    var path: String
        get() = sp.getString("path", DEFAULT_PATH) ?: DEFAULT_PATH
        set(v) = sp.edit().putString("path", v.trim().trimStart('/').ifBlank { DEFAULT_PATH }).apply()

    var sampleRate: Int
        get() = sp.getInt("sample_rate", 48000)
        set(v) = sp.edit().putInt("sample_rate", v).apply()

    /** Digitaler Vorverstaerker; 1.0 = unveraendert. */
    var gain: Float
        get() = sp.getFloat("gain", 1.0f)
        set(v) = sp.edit().putFloat("gain", v).apply()

    /**
     * Gewuenschte MediaRecorder.AudioSource oder [SOURCE_AUTO].
     * Das ist der eigentliche Hebel dafuer, welches physische Mikrofon der
     * Audiotreiber liefert -- eine API "nimm das untere Mikrofon" gibt es nicht.
     */
    var audioSource: Int
        get() = sp.getInt("audio_source", SOURCE_AUTO)
        set(v) = sp.edit().putInt("audio_source", v).apply()

    /** Schluessel des bevorzugten Eingabegeraets, leer = automatisch. */
    var inputDeviceKey: String
        get() = sp.getString("input_device", "") ?: ""
        set(v) = sp.edit().putString("input_device", v).apply()

    /** 1 = mono, 2 = stereo. */
    var channels: Int
        get() = sp.getInt("channels", 1)
        set(v) = sp.edit().putInt("channels", if (v == 2) 2 else 1).apply()

    var autostart: Boolean
        get() = sp.getBoolean("autostart", true)
        set(v) = sp.edit().putBoolean("autostart", v).apply()

    /** Grenzfrequenz des Hochpasses in Hz, 0 = aus. */
    var highPassHz: Int
        get() = sp.getInt("highpass", 0)
        set(v) = sp.edit().putInt("highpass", v).apply()

    /** Leer = keine Authentifizierung. */
    var authUser: String
        get() = sp.getString("auth_user", "") ?: ""
        set(v) = sp.edit().putString("auth_user", v.trim()).apply()

    var authPassword: String
        get() = sp.getString("auth_password", "") ?: ""
        set(v) = sp.edit().putString("auth_password", v).apply()

    /** Meldet den Dienst als birdmic.local im Netz an. */
    var mdnsEnabled: Boolean
        get() = sp.getBoolean("mdns", false)
        set(v) = sp.edit().putBoolean("mdns", v).apply()

    var mdnsName: String
        get() = sp.getString("mdns_name", "BirdMic") ?: "BirdMic"
        set(v) = sp.edit().putString("mdns_name", v.trim().ifBlank { "BirdMic" }).apply()

    /**
     * Obergrenze fuer gleichzeitige Empfaenger, 0 = unbegrenzt.
     *
     * Nicht theoretisch: ein Reconnect-Fehler auf der Empfaengerseite kann
     * dutzende Prozesse starten, die alle gleichzeitig denselben Stream ziehen.
     * Jeder kostet volle Bandbreite -- 13 Empfaenger sind bereits 10 Mbit/s.
     */
    var maxClients: Int
        get() = sp.getInt("max_clients", 4)
        set(v) = sp.edit().putInt("max_clients", v.coerceAtLeast(0)).apply()

    /** Obergrenze je Absenderadresse, 0 = unbegrenzt. */
    var maxClientsPerIp: Int
        get() = sp.getInt("max_clients_per_ip", 2)
        set(v) = sp.edit().putInt("max_clients_per_ip", v.coerceAtLeast(0)).apply()

    /** Sekunden ohne Lebenszeichen, nach denen eine UDP-Session verworfen wird. 0 = nie. */
    var udpTimeoutSeconds: Int
        get() = sp.getInt("udp_timeout", 0)
        set(v) = sp.edit().putInt("udp_timeout", v).apply()

    /**
     * Erzwingt echtes Schwarz statt dem System-Dunkelmodus.
     *
     * Spart im Dauerbetrieb nichts -- der Bildschirm ist dann aus. Nuetzlich
     * beim Ablesen in der Daemmerung und gegen Einbrennen, falls das Display
     * als Statusanzeige dauerhaft laeuft.
     */
    var amoledBlack: Boolean
        get() = sp.getBoolean("amoled_black", false)
        set(v) = sp.edit().putBoolean("amoled_black", v).apply()

    /** Warnschwelle der Akkutemperatur in Grad Celsius, 0 = aus. */
    var batteryWarnCelsius: Int
        get() = sp.getInt("battery_warn", 40)
        set(v) = sp.edit().putInt("battery_warn", v).apply()

    /** Merkt sich, ob der Server lief -- nur dann startet er nach dem Boot wieder. */
    var wasRunning: Boolean
        get() = sp.getBoolean("was_running", false)
        set(v) = sp.edit().putBoolean("was_running", v).apply()

    companion object {
        const val DEFAULT_PATH = "audio.wav"
        const val SOURCE_AUTO = -1

        /** Auswahl der Oberflaeche; 48 kHz ist der Standard und was BirdNET intern nutzt. */
        val SAMPLE_RATES = intArrayOf(48000, 44100, 32000, 22050, 16000)

        /** 0 steht fuer "aus". */
        val HIGH_PASS_OPTIONS = intArrayOf(0, 80, 120, 150, 200, 300)

        /** Erste nicht-lokale IPv4-Adresse, bevorzugt ein wlan-Interface. */
        fun localIpv4(): String? {
            val candidates = ArrayList<Pair<String, String>>()
            runCatching {
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (addr in nif.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            candidates.add(nif.name to (addr.hostAddress ?: continue))
                        }
                    }
                }
            }
            return candidates.firstOrNull { it.first.startsWith("wlan") }?.second
                ?: candidates.firstOrNull()?.second
        }
    }
}
