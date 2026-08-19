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
 */
class EventLog(context: Context) {

    enum class Kind { START, STOP, CLIENT_IN, CLIENT_OUT, RECOVER, WARNING, ERROR }

    data class Entry(val timestamp: Long, val kind: Kind, val message: String) {
        fun format(): String = "${TIME_FORMAT.format(Date(timestamp))}  ${icon()} $message"

        /** Zeitstempel mit Jahr -- fuer den Export, wo Eintraege Wochen alt sein koennen. */
        fun formatLong(): String = "${FULL_FORMAT.format(Date(timestamp))}  ${icon()} $message"

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

    @Synchronized
    fun add(kind: Kind, message: String) {
        entries.addLast(Entry(System.currentTimeMillis(), kind, message))
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
                entries.addLast(
                    Entry(
                        item.getLong("t"),
                        Kind.valueOf(item.getString("k")),
                        item.getString("m"),
                    ),
                )
            }
        }
    }

    private fun save() {
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject()
                    .put("t", entry.timestamp)
                    .put("k", entry.kind.name)
                    .put("m", entry.message),
            )
        }
        sp.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val CAPACITY = 100
        private const val KEY = "entries"
        private val TIME_FORMAT = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY)
        private val FULL_FORMAT = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)
    }
}
