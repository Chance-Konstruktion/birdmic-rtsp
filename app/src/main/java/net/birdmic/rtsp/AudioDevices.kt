package net.birdmic.rtsp

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder

/**
 * Auswahl der Aufnahmequelle.
 *
 * Android bietet keine Moeglichkeit, ein bestimmtes physisches Mikrofon direkt
 * anzusprechen. Welches der verbauten Mikrofone der Audiotreiber liefert,
 * haengt an der gewaehlten AudioSource -- diese Zuordnung legt der Hersteller
 * im HAL fest. Die Beschreibungen hier sind deshalb die ueblichen Faelle, keine
 * Garantie: welche Quelle bei einem konkreten Geraet unten oder oben landet,
 * zeigt am Ende nur der Hoertest.
 */
enum class SourceChoice(
    val value: Int,
    val label: String,
    val hint: String,
) {
    AUTO(
        Prefs.SOURCE_AUTO,
        "Automatisch",
        "Bestes Rohsignal: UNPROCESSED, sonst VOICE_RECOGNITION. Oft das obere Mikrofon.",
    ),
    UNPROCESSED(
        MediaRecorder.AudioSource.UNPROCESSED,
        "UNPROCESSED (roh)",
        "Ohne jede Nachbearbeitung. Beste Qualität, aber meist das obere Referenzmikrofon.",
    ),
    MIC(
        MediaRecorder.AudioSource.MIC,
        "MIC (Hauptmikrofon)",
        "Das Standardmikrofon, auf den meisten Geräten das untere.",
    ),
    VOICE_COMMUNICATION(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        "Telefonmikrofon",
        "Die Quelle für Telefonate – zuverlässig das untere Mikrofon. Achtung: der Treiber legt hier oft Echounterdrückung und Rauschfilter fest darüber, die sich nicht abschalten lassen.",
    ),
    VOICE_RECOGNITION(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        "Spracherkennung",
        "Wenig bearbeitet, für Sprache optimiert.",
    ),
    CAMCORDER(
        MediaRecorder.AudioSource.CAMCORDER,
        "Kameramikrofon",
        "Das Mikrofon in Blickrichtung der Hauptkamera, also auf der Rückseite.",
    ),
    ;

    companion object {
        fun fromValue(value: Int): SourceChoice =
            entries.firstOrNull { it.value == value } ?: AUTO
    }
}

/** Ein auswaehlbares Eingabegeraet, wie es die Oberflaeche anzeigt. */
data class InputChoice(
    val key: String,
    val label: String,
    val deviceId: Int,
)

object AudioDevices {

    /**
     * Die Adresse eines Eingabegeraets gibt es erst ab Android 9. Darunter
     * bleibt sie leer -- der Aufruf wuerde dort mit NoSuchMethodError abstuerzen.
     */
    fun addressOf(device: AudioDeviceInfo): String {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return ""
        return device.address?.takeIf { it.isNotBlank() && it != "0" } ?: ""
    }

    /** "Automatisch" plus alles, was das Geraet als Eingang meldet. */
    fun list(audioManager: AudioManager): List<InputChoice> {
        val result = mutableListOf(InputChoice("", "Automatisch", -1))
        val devices = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        }.getOrDefault(emptyList())

        val seenPerType = HashMap<Int, Int>()
        for (device in devices) {
            val index = seenPerType.getOrDefault(device.type, 0)
            seenPerType[device.type] = index + 1
            result.add(
                InputChoice(
                    key = keyOf(device, index),
                    label = labelOf(device, index),
                    deviceId = device.id,
                ),
            )
        }
        return result
    }

    /**
     * Sucht das gespeicherte Geraet wieder. Die numerische Geraete-ID wechselt
     * nach einem Neustart, deshalb wird ueber Typ + Adresse + Position gesucht.
     */
    fun find(audioManager: AudioManager, key: String): AudioDeviceInfo? {
        if (key.isBlank()) return null
        val devices = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        }.getOrDefault(emptyList())

        val seenPerType = HashMap<Int, Int>()
        for (device in devices) {
            val index = seenPerType.getOrDefault(device.type, 0)
            seenPerType[device.type] = index + 1
            if (keyOf(device, index) == key) return device
        }
        return null
    }

    private fun keyOf(device: AudioDeviceInfo, index: Int): String =
        "${device.type}|${addressOf(device)}|$index"

    private fun labelOf(device: AudioDeviceInfo, index: Int): String {
        val base = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Eingebautes Mikrofon"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-Mikrofon"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Klinken-Mikrofon"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth-Mikrofon"
            else -> "Eingang (Typ ${device.type})"
        }
        // Die Adresse ist auf vielen Geraeten sprechend ("bottom", "top", "back").
        val address = addressOf(device).ifBlank { null }
        return when {
            address != null -> "$base ($address)"
            index > 0 -> "$base ${index + 1}"
            else -> base
        }
    }

    /** Uebersetzt die Position aus MicrophoneInfo in "unten"/"oben"/"Rückseite". */
    fun describePosition(x: Float, y: Float, z: Float): String? {
        // Koordinatensystem laut MicrophoneInfo: x nach rechts, y nach oben,
        // z nach vorne (zum Nutzer). Nullpunkt ist die Geraetemitte.
        if (x == 0f && y == 0f && z == 0f) return null
        val vertical = when {
            y < -0.02f -> "unten"
            y > 0.02f -> "oben"
            else -> null
        }
        val facing = if (z < -0.002f) "Rückseite" else null
        return listOfNotNull(vertical, facing).joinToString(", ").ifBlank { null }
    }
}
