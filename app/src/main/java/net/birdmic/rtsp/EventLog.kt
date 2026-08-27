package net.birdmic.rtsp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kleines Logbuch mit Zeitstempeln.
 *
 * Der Neustart-Zaehler sagt nur, *dass* etwas passiert ist. Fuer die Frage,
 * welcher Energiesparmechanismus um drei Uhr nachts zuschlaegt, braucht man den
 * Zeitpunkt. Die letzten [CAPACITY] Eintraege ueberleben einen App-Neustart.
 *
 * **Es darf nur eine Instanz geben** -- deshalb [of] statt des Konstruktors.
 * Bis 2.4 hatten Dienst und Oberflaeche je eine eigene: beide schrieben in
 * dieselbe Datei, aber jede hielt ihre eigene Liste im Speicher. Die Anzeige
 * und der Export zeigten damit den Stand vom Start der Oberflaeche -- in einem
 * Fall sieben Tage alt, waehrend der Dienst munter weiterschrieb.
 */
class EventLog private constructor(context: Context) {

    enum class Kind { START, STOP, CLIENT_IN, CLIENT_OUT, RECOVER, WARNING, ERROR }

    data class Entry(
        val timestamp: Long,
        val kind: Kind,
        val message: String,
        /** Wie oft dieselbe Meldung unmittelbar hintereinander kam. */
        val repeats: Int = 1,
        /** Zeitpunkt der letzten Wiederholung. */
        val lastTimestamp: Long = timestamp,
    ) {
        fun format(): String =
            "${TIME_FORMAT.format(Date(timestamp))}  ${icon()} $message${repeatSuffix(TIME_FORMAT)}"

        /** Zeitstempel mit Jahr -- fuer den Export, wo Eintraege Wochen alt sein koennen. */
        fun formatLong(): String =
            "${FULL_FORMAT.format(Date(timestamp))}  ${icon()} $message${repeatSuffix(FULL_FORMAT)}"

        private fun repeatSuffix(format: SimpleDateFormat): String =
            if (repeats > 1) "  [${repeats}×, zuletzt ${format.format(Date(lastTimestamp))}]" else ""

        fun icon() = when (kind) {
            Kind.START -> "▶"
            Kind.STOP -> "■"
            Kind.CLIENT_IN -> "→"
            Kind.CLIENT_OUT -> "←"
            Kind.RECOVER -> "↻"
            Kind.WARNING -> "⚠"
            Kind.ERROR -> "✖"
        }
    }

    private val sp = context.getSharedPreferences("birdmic_log", Context.MODE_PRIVATE)
    private val entries = ArrayDeque<Entry>()

    init {
        load()
    }

    /**
     * Gleiche Meldung direkt hintereinander wird zusammengefasst, statt 100
     * Plaetze zu belegen.
     *
     * Nicht kosmetisch: Ein Empfaenger, der sich in einer Schleife neu
     * verbindet, hat schon einmal das gesamte Logbuch ueberschrieben -- der
     * Fehler davor, den man haette sehen wollen, war dann weg.
     */
    @Synchronized
    fun add(kind: Kind, message: String) {
        val now = System.currentTimeMillis()
        val last = entries.lastOrNull()
        if (last != null && last.kind == kind && last.message == message) {
            entries.removeLast()
            entries.addLast(last.copy(repeats = last.repeats + 1, lastTimestamp = now))
        } else {
            entries.addLast(Entry(now, kind, message))
        }
        while (entries.size > CAPACITY) entries.removeFirst()
        save()
    }

    /** Neueste zuerst. */
    @Synchronized
    fun recent(limit: Int = CAPACITY): List<Entry> =
        entries.reversed().take(limit)

    /** Aelteste zuerst -- die Reihenfolge, in der man einen Fehler nachliest. */
    @Synchronized
    fun all(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        save()
    }

    private fun load() {
        val raw = sp.getString(KEY, null) ?: return
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val timestamp = item.getLong("t")
                entries.addLast(
                    Entry(
                        timestamp,
                        Kind.valueOf(item.getString("k")),
                        item.getString("m"),
                        item.optInt("n", 1),
                        item.optLong("l", timestamp),
                    ),
                )
            }
        }
    }

    private fun save() {
        val array = JSONArray()
        for (entry in entries) {
            val item = JSONObject()
                .put("t", entry.timestamp)
                .put("k", entry.kind.name)
                .put("m", entry.message)
            if (entry.repeats > 1) {
                item.put("n", entry.repeats).put("l", entry.lastTimestamp)
            }
            array.put(item)
        }
        sp.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val CAPACITY = 100
        private const val KEY = "entries"
        private val TIME_FORMAT = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY)
        private val FULL_FORMAT = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)

        @Volatile private var instance: EventLog? = null

        /** Die eine Instanz fuer den gesamten Prozess. */
        fun of(context: Context): EventLog =
            instance ?: synchronized(this) {
                instance ?: EventLog(context.applicationContext).also { instance = it }
            }
    }
}
