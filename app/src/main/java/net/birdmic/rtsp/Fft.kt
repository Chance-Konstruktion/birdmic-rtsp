package net.birdmic.rtsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Radix-2-FFT fuer die Spektrogramm-Anzeige.
 *
 * Bewusst klein gehalten und ohne Fremdbibliothek: die Anzeige ist ein
 * Kontrollwerkzeug fuer die Mikrofonaufstellung, keine Messtechnik. Die
 * Analyse selbst macht BirdNET auf dem Pi.
 */
class Fft(private val size: Int) {

    init {
        require(size > 0 && size and (size - 1) == 0) { "FFT-Groesse muss eine Zweierpotenz sein" }
    }

    private val real = DoubleArray(size)
    private val imag = DoubleArray(size)

    /** Hann-Fenster gegen spektrale Leckage, einmal vorberechnet. */
    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2.0 * PI * it / (size - 1)) }

    private val cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(2.0 * PI * it / size) }

    /**
     * Rechnet [input] in Magnituden um. [output] muss size/2 Eintraege haben und
     * enthaelt danach Werte von 0.0 bis 1.0 (normiert auf Vollaussteuerung).
     */
    fun magnitudes(input: ShortArray, output: FloatArray) {
        for (i in 0 until size) {
            real[i] = (input[i] / 32768.0) * window[i]
            imag[i] = 0.0
        }
        transform()
        // Normierung: Hann halbiert die Amplitude, deshalb der Faktor 4/size.
        val scale = 4.0 / size
        for (i in output.indices) {
            output[i] = (hypot(real[i], imag[i]) * scale).toFloat()
        }
    }

    private fun transform() {
        // Bit-Reversal-Permutation.
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var tmp = real[i]; real[i] = real[j]; real[j] = tmp
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp
            }
        }

        // Danielson-Lanczos: Schmetterlinge ueber wachsende Bloecke.
        var len = 2
        while (len <= size) {
            val step = size / len
            var i = 0
            while (i < size) {
                var k = 0
                for (m in i until i + len / 2) {
                    val n = m + len / 2
                    val wr = cosTable[k]
                    val wi = -sinTable[k]
                    val tr = real[n] * wr - imag[n] * wi
                    val ti = real[n] * wi + imag[n] * wr
                    real[n] = real[m] - tr
                    imag[n] = imag[m] - ti
                    real[m] += tr
                    imag[m] += ti
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
