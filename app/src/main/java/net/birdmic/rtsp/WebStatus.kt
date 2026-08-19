package net.birdmic.rtsp

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Fuellt die Antworten des [WebServer] mit Leben. Alles, was Android braucht,
 * steckt hier -- der Server selbst bleibt dadurch reines Java und testbar.
 *
 * Aufgerufen wird von den Webserver-Threads, nicht vom Aufnahme-Thread. Gelesen
 * werden ausschliesslich `@Volatile`-Werte und ein Schnappschuss des
 * Analysepuffers; der Ton laeuft davon voellig unbeeindruckt weiter.
 */
class WebStatus(
    context: Context,
    private val prefs: Prefs,
    private val log: EventLog,
) : WebServer.Provider {

    private val appContext = context.applicationContext

    // FFT und Puffer werden einmal angelegt und unter einer Sperre benutzt --
    // zwei Browser gleichzeitig sollen sich nicht in die Quere kommen.
    private val fftLock = Any()
    private val fft = Fft(AudioEngine.ANALYSIS_SIZE)
    private val samples = ShortArray(AudioEngine.ANALYSIS_SIZE)
    private val magnitudes = FloatArray(AudioEngine.ANALYSIS_SIZE / 2)

    override fun statusJson(): String {
        val state = RtspService.State
        val json = JSONObject()
            .put("app", BuildConfig.VERSION_NAME)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE)
            .put("running", state.running)
            .put("url", if (state.running) state.url else "–")
            .put("status", statusText(state))
            .put("error", state.error != null && !state.running)
            .put("clients", state.clients)
            .put("clientList", JSONArray(state.clientNames))
            .put("level", round3(state.level))
            .put("peak", round3(state.peakHold))
            .put("clipped", state.clipped)
            .put("restarts", state.restarts)
            .put("hot", state.batteryTooHot)
            .put("format", "${prefs.sampleRate} Hz ${if (prefs.channels == 2) "stereo" else "mono"}, L16")

        if (state.running) {
            json.put("uptime", Diagnostics.formatDuration(state.uptimeSeconds))
                .put("bytes", Diagnostics.formatBytes(state.bytesSent))
                .put("source", state.source)
                .put("battery", state.batterySummary)
            state.activeMicrophone?.let { json.put("mic", it) }
            state.mdnsName?.let { json.put("mdns", it) }
        }
        return json.toString()
    }

    override fun logJson(): String {
        val entries = JSONArray()
        for (entry in log.recent(LOG_LINES)) {
            entries.put(
                JSONObject()
                    .put("t", entry.format().substringBefore("  "))
                    .put("i", entry.icon())
                    .put("m", entry.message),
            )
        }
        return JSONObject().put("entries", entries).toString()
    }

    override fun logText(): String = Diagnostics.report(appContext, prefs, log)

    /**
     * Das Spektrum wird auf [BANDS] logarithmisch verteilte Baender
     * zusammengefasst. Nicht aus Sparsamkeit, sondern weil das dem Gehoer
     * entspricht: zwischen 1 und 2 kHz liegt genauso viel Musik wie zwischen
     * 2 und 4 kHz. Linear waeren die unteren zwei Oktaven ein einziger Pixel.
     */
    override fun spectrumJson(): String {
        val engine = RtspService.State.engine
            ?: return JSONObject().put("bands", JSONArray()).toString()

        val bands = JSONArray()
        synchronized(fftLock) {
            if (!engine.analysisSnapshot(samples)) {
                return JSONObject().put("bands", JSONArray()).toString()
            }
            fft.magnitudes(samples, magnitudes)

            val bins = magnitudes.size
            val lowest = 1.0
            val factor = (bins.toDouble() / lowest).pow(1.0 / BANDS)
            var from = lowest
            for (band in 0 until BANDS) {
                val to = (from * factor).coerceAtMost(bins.toDouble())
                var peak = 0f
                var i = from.toInt()
                val end = to.toInt().coerceAtLeast(i + 1).coerceAtMost(bins)
                while (i < end) {
                    peak = max(peak, magnitudes[i])
                    i++
                }
                bands.put(round3(toDecibelScale(peak)))
                from = to
            }
        }
        return JSONObject().put("bands", bands).toString()
    }

    /**
     * Rohbetrag -> 0..1 auf einer dB-Skala. Dieselben Grenzen wie im
     * Spektrogramm der App (-90 dB Stille bis -10 dB sehr laut), damit beide
     * Ansichten nebeneinander dasselbe Bild zeigen.
     */
    private fun toDecibelScale(magnitude: Float): Float {
        val db = 20f * log10(magnitude.coerceAtLeast(1e-7f))
        return ((db + 90f) / 80f).coerceIn(0f, 1f)
    }

    private fun statusText(state: RtspService.State): String = when {
        !state.running && state.error != null -> "Fehler: ${state.error}"
        !state.running -> "Gestoppt"
        state.clients == 0 -> "Bereit – wartet auf Verbindung"
        state.clients == 1 -> "Sendet an 1 Empfänger"
        else -> "Sendet an ${state.clients} Empfänger"
    }

    private fun round3(value: Float): Double = (value * 1000).roundToInt() / 1000.0

    private companion object {
        const val BANDS = 96
        const val LOG_LINES = 30
    }
}
