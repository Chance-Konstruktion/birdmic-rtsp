package net.birdmic.rtsp

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt

/**
 * Liest das Mikrofon in einem eigenen Thread und verteilt 20-ms-Bloecke als
 * Big-Endian-PCM (RTP L16) an alle angemeldeten Senken.
 *
 * Fuer die Vogelerkennung ist wichtig, dass das Signal moeglichst unbearbeitet
 * bleibt: AGC pumpt bei leisen Rufen, Noise Suppression frisst genau die
 * breitbandigen Anteile, an denen BirdNET Arten unterscheidet. Die Effekte
 * werden deshalb, wo das Geraet es zulaesst, explizit abgeschaltet.
 *
 * Fuer den Dauerbetrieb laeuft die Aufnahme unter einem Watchdog: wenn ein
 * anderer Prozess das Mikrofon wegnimmt oder der Treiber aussteigt, wird
 * AudioRecord mit wachsender Wartezeit neu geoeffnet, statt still zu verstummen.
 */
class AudioEngine(
    private val audioManager: AudioManager,
    val sampleRate: Int,
    private val gain: Float,
    private val sourcePreference: Int = Prefs.SOURCE_AUTO,
    private val inputDeviceKey: String = "",
    requestedChannels: Int = 1,
    private val highPassHz: Int = 0,
    private val onEvent: ((EventLog.Kind, String) -> Unit)? = null,
) {
    /** Empfaenger eines fertigen 20-ms-Blocks in Netzwerk-Byteorder. */
    fun interface Sink {
        fun onAudio(bigEndianPcm: ByteArray, frames: Int)
    }

    /** 1 = mono, 2 = stereo. Faellt auf mono zurueck, wenn das Geraet kein Stereo kann. */
    var channels: Int = if (requestedChannels == 2) 2 else 1
        private set

    /** Frames pro Paket: 20 ms -> bei 48 kHz 960 Frames. */
    val framesPerPacket: Int = sampleRate / 50

    private val sinks = CopyOnWriteArrayList<Sink>()
    @Volatile private var thread: Thread? = null
    @Volatile private var running = false

    /** Aktueller Pegel 0.0..1.0 (RMS), nur fuer die Anzeige. */
    @Volatile var level: Float = 0f
        private set

    /** Spitzenwert des letzten Blocks, 0.0..1.0. */
    @Volatile var peak: Float = 0f
        private set

    /**
     * Spitzenwert mit Nachlauf: faellt langsam ab, damit kurze Transienten
     * nicht zwischen zwei Bildschirmaktualisierungen verschwinden.
     */
    @Volatile var peakHold: Float = 0f
        private set

    /**
     * Anzahl uebersteuerter Samples seit dem Start. Wichtig, weil die
     * Verstaerkung von Hand eingestellt wird: Uebersteuerung zerstoert genau
     * die lauten Nahaufnahmen, die sonst die besten Erkennungen liefern.
     */
    @Volatile var clippedSamples: Long = 0L
        private set

    /** Verarbeitete Frames insgesamt -- Grundlage fuer die Laufzeitanzeige. */
    @Volatile var totalFrames: Long = 0L
        private set

    /** Z.B. "MIC 48000 Hz mono, internes Mikrofon". */
    @Volatile var sourceDescription: String = "-"
        private set

    /** Was das System ueber das tatsaechlich aktive Mikrofon meldet, wenn verfuegbar. */
    @Volatile var activeMicrophone: String? = null
        private set

    /** Wie oft der Watchdog die Aufnahme neu starten musste. */
    @Volatile var restarts: Int = 0
        private set

    @Volatile var lastError: String? = null
        private set

    // Keep handles to effects so each recorder lifecycle releases its resources.
    private var processingEffects: List<AudioEffect> = emptyList()

    fun addSink(sink: Sink) = sinks.add(sink)
    fun removeSink(sink: Sink) = sinks.remove(sink)

    /**
     * Oeffnet das Mikrofon einmal synchron, damit ein echter Fehler (fehlende
     * Berechtigung, belegtes Geraet) sofort gemeldet werden kann, und uebergibt
     * die Aufnahme danach an den Watchdog-Thread.
     */
    fun start(): Boolean {
        if (running) return true
        val first = openRecorder() ?: return false
        running = true
        thread = Thread({ supervise(first) }, "birdmic-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
        level = 0f
        activeMicrophone = null
    }

    /** Haelt die Aufnahme am Leben, solange der Dienst laeuft. */
    private fun supervise(firstRecord: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var record: AudioRecord? = firstRecord
        var backoffMs = 1000L

        while (running) {
            val current = record ?: openRecorder()
            record = null

            if (current == null) {
                // Mikrofon gerade nicht zu haben -- spaeter erneut versuchen,
                // aber nicht in einer Endlosschleife den Akku leersaugen.
                Log.w(TAG, "Mikrofon nicht verfuegbar, neuer Versuch in ${backoffMs}ms")
                sleepInterruptibly(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                continue
            }

            val stoppedCleanly = pump(current)
            runCatching { current.stop() }
            releaseProcessing()
            runCatching { current.release() }
            level = 0f

            if (!running) break

            restarts++
            Log.w(TAG, "Aufnahme neu gestartet (Nr. $restarts), sauber=$stoppedCleanly")
            onEvent?.invoke(
                EventLog.Kind.RECOVER,
                "Aufnahme neu gestartet (${lastError ?: "Grund unbekannt"})",
            )
            sleepInterruptibly(if (stoppedCleanly) 500L else backoffMs)
            if (!stoppedCleanly) backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }

        // stop() can race with the initial thread start. If the loop never runs,
        // it still owns the first AudioRecord returned by start().
        record?.let { pending ->
            runCatching { pending.stop() }
            releaseProcessing()
            runCatching { pending.release() }
        }
    }

    private fun sleepInterruptibly(millis: Long) {
        val deadline = System.currentTimeMillis() + millis
        while (running && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /**
     * Kopiert die zuletzt aufgenommenen Samples (mono heruntergemischt) fuer die
     * Spektrogramm-Anzeige. Gibt false zurueck, wenn noch nichts anliegt.
     */
    fun analysisSnapshot(dest: ShortArray): Boolean {
        if (dest.size != ANALYSIS_SIZE) return false
        synchronized(analysisBuffer) {
            if (!analysisFilled) return false
            // Ringpuffer in zeitlicher Reihenfolge auslesen.
            val tail = ANALYSIS_SIZE - analysisWrite
            System.arraycopy(analysisBuffer, analysisWrite, dest, 0, tail)
            System.arraycopy(analysisBuffer, 0, dest, tail, analysisWrite)
        }
        return true
    }

    private val analysisBuffer = ShortArray(ANALYSIS_SIZE)
    private var analysisWrite = 0
    private var analysisFilled = false

    /** Liest, bis der Dienst stoppt oder die Aufnahme ausfaellt. true = sauber beendet. */
    private fun pump(record: AudioRecord): Boolean {
        val samplesPerPacket = framesPerPacket * channels
        val samples = ShortArray(samplesPerPacket)
        val filter = if (highPassHz > 0) HighPassFilter(highPassHz.toFloat(), sampleRate, channels) else null
        // Ein einziger Ausgabepuffer: die Senken verschicken synchron und
        // kopieren vorher in ihren eigenen RTP-Puffer, also ist das sicher --
        // und erspart im Dauerbetrieb Muell fuer den Garbage Collector.
        val out = ByteArray(samplesPerPacket * 2)
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                lastError = "Aufnahme startete nicht"
                return false
            }
            lastError = null
            readActiveMicrophone(record)

            while (running) {
                var read = 0
                while (read < samplesPerPacket && running) {
                    val n = record.read(samples, read, samplesPerPacket - read)
                    if (n <= 0) {
                        Log.w(TAG, "AudioRecord.read -> $n")
                        lastError = "Aufnahme unterbrochen (Code $n)"
                        return false
                    }
                    read += n
                }
                if (!running) return true

                // Erst filtern, dann verstaerken: sonst wuerde der Vorverstaerker
                // das Rumpeln mit anheben, das gleich danach entfernt wird.
                filter?.process(samples, read)

                var sumSquares = 0.0
                var blockPeak = 0
                var clipped = 0
                for (i in 0 until read) {
                    var s = samples[i].toInt()
                    if (gain != 1f) {
                        val amplified = (s * gain).toInt()
                        if (amplified > 32767 || amplified < -32768) clipped++
                        s = amplified.coerceIn(-32768, 32767)
                    } else if (s >= 32767 || s <= -32768) {
                        clipped++
                    }
                    samples[i] = s.toShort()
                    val magnitude = if (s < 0) -s else s
                    if (magnitude > blockPeak) blockPeak = magnitude
                    sumSquares += (s * s).toDouble()
                    // L16 ist per RFC 3551 Big Endian, AudioRecord liefert Little Endian.
                    out[i * 2] = (s shr 8).toByte()
                    out[i * 2 + 1] = s.toByte()
                }

                level = (sqrt(sumSquares / read) / 32768.0).toFloat()
                peak = blockPeak / 32768f
                peakHold = if (peak > peakHold) peak else peakHold * PEAK_DECAY
                if (clipped > 0) clippedSamples += clipped

                // Der RTP-Zeitstempel zaehlt Frames, nicht Einzelsamples.
                val frames = read / channels
                totalFrames += frames
                storeForAnalysis(samples, read)
                for (sink in sinks) {
                    runCatching { sink.onAudio(out, frames) }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Audio-Loop abgebrochen", e)
            lastError = e.message
            return false
        }
    }

    /** Schreibt die Samples mono heruntergemischt in den Ringpuffer der Anzeige. */
    private fun storeForAnalysis(samples: ShortArray, count: Int) {
        synchronized(analysisBuffer) {
            var i = 0
            while (i < count) {
                val value = if (channels == 2 && i + 1 < count) {
                    ((samples[i].toInt() + samples[i + 1].toInt()) / 2).toShort()
                } else {
                    samples[i]
                }
                analysisBuffer[analysisWrite] = value
                analysisWrite = (analysisWrite + 1) % ANALYSIS_SIZE
                if (analysisWrite == 0) analysisFilled = true
                i += channels
            }
        }
    }

    /**
     * Ab Android 9 verraet das System, welche Mikrofone gerade aktiv sind und wo
     * sie sitzen. Damit laesst sich pruefen, ob wirklich das untere Mikrofon
     * benutzt wird, statt es nur zu vermuten.
     */
    private fun readActiveMicrophone(record: AudioRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            activeMicrophone = null
            return
        }
        activeMicrophone = runCatching {
            record.activeMicrophones.mapNotNull { mic ->
                val position = mic.position
                val where = AudioDevices.describePosition(position.x, position.y, position.z)
                val address = mic.address?.takeIf { it.isNotBlank() }
                listOfNotNull(address, where).joinToString(" ").ifBlank { null }
            }.distinct().joinToString(" + ").ifBlank { null }
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord? {
        // Stereo zuerst pruefen: viele Geraete melden dafuer gar keinen Puffer.
        var mask = channelMask()
        var minBuf = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0 && channels == 2) {
            Log.w(TAG, "Stereo nicht unterstuetzt, weiche auf mono aus")
            channels = 1
            mask = channelMask()
            minBuf = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        }
        if (minBuf <= 0) {
            lastError = "Samplerate $sampleRate Hz wird nicht unterstuetzt"
            return null
        }
        // Grosszuegiger Puffer: das Geraet laeuft tagelang durch, kurze
        // Scheduling-Aussetzer duerfen keine Luecken im Stream erzeugen.
        val bufferBytes = maxOf(minBuf * 4, framesPerPacket * channels * 2 * 16)

        for (source in candidateSources()) {
            val record = try {
                AudioRecord(source, sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord($source) fehlgeschlagen", e)
                lastError = e.message
                continue
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                continue
            }
            disableProcessing(record.audioSessionId)

            val device = selectInputDevice()
            val deviceSelected = device != null &&
                runCatching { record.setPreferredDevice(device) }.getOrDefault(false)
            if (device != null && !deviceSelected) {
                Log.w(TAG, "Bevorzugtes Eingabegeraet konnte nicht gesetzt werden; Android waehlt die Quelle")
            }
            sourceDescription = buildString {
                append(SourceChoice.fromValue(source).label)
                append(" · $sampleRate Hz ")
                append(if (channels == 2) "stereo" else "mono")
                append(", ").append(device?.takeIf { deviceSelected }?.let { describeDevice(it) } ?: "automatisch")
                if (highPassHz > 0) append(" · Hochpass $highPassHz Hz")
            }
            lastError = null
            return record
        }
        if (lastError == null) lastError = "Mikrofon konnte nicht geoeffnet werden"
        return null
    }

    private fun channelMask(): Int =
        if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

    /**
     * Die Wunschquelle zuerst; danach Rueckfallebenen, damit ein Geraet, das
     * die gewaehlte Quelle nicht kennt, nicht komplett stumm bleibt.
     */
    private fun candidateSources(): List<Int> {
        val fallbacks = mutableListOf<Int>()
        if (sourcePreference != Prefs.SOURCE_AUTO) {
            fallbacks.add(sourcePreference)
        } else {
            val unprocessedSupported =
                audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            if (unprocessedSupported) {
                fallbacks.add(MediaRecorder.AudioSource.UNPROCESSED)
            }
            // VOICE_RECOGNITION ist auf vielen Altgeraeten die Quelle mit der
            // geringsten Nachbearbeitung, wenn UNPROCESSED fehlt.
            fallbacks.add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        }
        fallbacks.add(MediaRecorder.AudioSource.MIC)
        return fallbacks.distinct()
    }

    /**
     * Explizit gewaehltes Geraet, sonst ein angestecktes USB- oder
     * Klinken-Mikrofon -- das ist dem verbauten Telefonmikrofon klanglich
     * weit ueberlegen.
     */
    private fun selectInputDevice(): AudioDeviceInfo? {
        AudioDevices.find(audioManager, inputDeviceKey)?.let { return it }
        if (inputDeviceKey.isNotBlank()) {
            Log.w(TAG, "Gespeichertes Eingabegeraet nicht gefunden, nutze Automatik")
        }

        val devices = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        }.getOrDefault(emptyList())

        val ranking = listOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )
        for (type in ranking) {
            devices.firstOrNull { it.type == type }?.let { return it }
        }
        return null
    }

    private fun describeDevice(device: AudioDeviceInfo): String = when (device.type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-Mikrofon"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Klinken-Mikrofon"
        AudioDeviceInfo.TYPE_BUILTIN_MIC ->
            AudioDevices.addressOf(device).ifBlank { null }?.let { "internes Mikrofon ($it)" }
                ?: "internes Mikrofon"
        else -> "Eingang ${device.type}"
    }

    private fun disableProcessing(sessionId: Int) {
        val created = mutableListOf<AudioEffect>()

        fun disable(effect: AudioEffect?) {
            if (effect == null) return
            created += effect
            runCatching { effect.enabled = false }
        }

        runCatching {
            if (AutomaticGainControl.isAvailable()) disable(AutomaticGainControl.create(sessionId))
        }
        runCatching {
            if (NoiseSuppressor.isAvailable()) disable(NoiseSuppressor.create(sessionId))
        }
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) disable(AcousticEchoCanceler.create(sessionId))
        }
        processingEffects = created
    }

    private fun releaseProcessing() {
        processingEffects.forEach { effect -> runCatching { effect.release() } }
        processingEffects = emptyList()
    }

    companion object {
        private const val TAG = "BirdMic/Audio"
        private const val MAX_BACKOFF_MS = 30_000L

        /** Fenstergroesse der Spektrogramm-Analyse; bei 48 kHz sind das 46,9 Hz je Bin. */
        const val ANALYSIS_SIZE = 1024

        /** Abklingfaktor des Spitzenwert-Nachlaufs, angewandt je 20-ms-Block. */
        private const val PEAK_DECAY = 0.92f
    }
}
