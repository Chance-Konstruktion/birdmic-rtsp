package net.birdmic.rtsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hochpass gegen Wind, Verkehr und Gebaeudevibration.
 *
 * Unterhalb von etwa 150 Hz sitzt bei einer Aussenaufnahme fast nur Stoerung.
 * Die frisst Dynamikreserve und bringt fuer die Erkennung nichts -- die tiefste
 * heimische Vogelstimme (Uhu) liegt bei rund 250 Hz.
 *
 * Zwei kaskadierte Biquads in Butterworth-Anordnung ergeben 24 dB pro Oktave.
 * Gerechnet wird in double, damit die Filterzustaende bei tiefen Grenzfrequenzen
 * nicht in der Rundung untergehen.
 */
class HighPassFilter(
    cutoffHz: Float,
    sampleRate: Int,
    private val channels: Int,
) {
    // Butterworth-Guetefaktoren fuer eine Kaskade aus zwei Biquads (4. Ordnung).
    private val sections = arrayOf(
        Biquad(cutoffHz, sampleRate, 0.5412),
        Biquad(cutoffHz, sampleRate, 1.3066),
    )

    // Ein Zustandssatz pro Kanal und Sektion.
    private val state = Array(sections.size) { Array(channels) { BiquadState() } }

    /** Filtert einen Block interleavter Samples in-place. */
    fun process(samples: ShortArray, count: Int) {
        for (i in 0 until count) {
            val channel = if (channels == 1) 0 else i % channels
            var value = samples[i].toDouble()
            for (s in sections.indices) {
                value = sections[s].step(state[s][channel], value)
            }
            samples[i] = value.toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    fun reset() {
        for (row in state) for (st in row) st.reset()
    }

    /** Koeffizienten nach der RBJ-Audio-EQ-Cookbook-Formel fuer einen Hochpass. */
    private class Biquad(cutoffHz: Float, sampleRate: Int, q: Double) {
        val b0: Double
        val b1: Double
        val b2: Double
        val a1: Double
        val a2: Double

        init {
            val w0 = 2.0 * PI * cutoffHz / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha

            b0 = ((1.0 + cosW0) / 2.0) / a0
            b1 = (-(1.0 + cosW0)) / a0
            b2 = ((1.0 + cosW0) / 2.0) / a0
            a1 = (-2.0 * cosW0) / a0
            a2 = (1.0 - alpha) / a0
        }

        /** Direct Form I. */
        fun step(s: BiquadState, x: Double): Double {
            val y = b0 * x + b1 * s.x1 + b2 * s.x2 - a1 * s.y1 - a2 * s.y2
            s.x2 = s.x1; s.x1 = x
            s.y2 = s.y1; s.y1 = y
            return y
        }
    }

    private class BiquadState {
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0

        fun reset() {
            x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
        }
    }
}
