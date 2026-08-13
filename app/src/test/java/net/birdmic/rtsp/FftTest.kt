package net.birdmic.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Prueft die FFT gegen bekannte Signale: ein Sinus muss genau in seinem Bin
 * landen, Stille darf nichts anzeigen.
 */
class FftTest {

    private val size = 1024
    private val sampleRate = 48000

    private fun spectrumOf(frequency: Double, amplitude: Double = 1.0): FloatArray {
        val input = ShortArray(size) {
            (sin(2.0 * PI * frequency * it / sampleRate) * 32767 * amplitude).toInt().toShort()
        }
        val output = FloatArray(size / 2)
        Fft(size).magnitudes(input, output)
        return output
    }

    private fun binOf(frequency: Double): Int = (frequency * size / sampleRate).toInt()

    @Test
    fun `findet einen 1 kHz Ton im richtigen Bin`() {
        val spectrum = spectrumOf(1000.0)
        val peak = spectrum.indices.maxByOrNull { spectrum[it] }!!
        // Bin-Breite ist 46,9 Hz -- eine Abweichung von einem Bin ist normal.
        assertTrue("Spitze lag bei Bin $peak statt ${binOf(1000.0)}",
            kotlin.math.abs(peak - binOf(1000.0)) <= 1)
    }

    @Test
    fun `findet einen 5 kHz Ton im richtigen Bin`() {
        val spectrum = spectrumOf(5000.0)
        val peak = spectrum.indices.maxByOrNull { spectrum[it] }!!
        assertTrue("Spitze lag bei Bin $peak statt ${binOf(5000.0)}",
            kotlin.math.abs(peak - binOf(5000.0)) <= 1)
    }

    @Test
    fun `Stille ergibt ein leeres Spektrum`() {
        val output = FloatArray(size / 2)
        Fft(size).magnitudes(ShortArray(size), output)
        assertTrue("Stille darf keine Energie zeigen", output.all { it < 1e-6f })
    }

    @Test
    fun `Vollaussteuerung liegt nahe eins`() {
        val spectrum = spectrumOf(1000.0, 1.0)
        val peak = spectrum.max()
        // Normierung ist auf Vollaussteuerung ausgelegt; Fensterung und
        // Bin-Verschmierung kosten etwas, daher ein grosszuegiges Fenster.
        assertTrue("Spitzenwert war $peak, erwartet nahe 1.0", peak in 0.5f..1.2f)
    }

    @Test
    fun `leisere Signale ergeben kleinere Betraege`() {
        val loud = spectrumOf(2000.0, 1.0).max()
        val quiet = spectrumOf(2000.0, 0.1).max()
        assertEquals("Zehntel Amplitude sollte Zehntel Betrag geben",
            0.1, (quiet / loud).toDouble(), 0.02)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `verweigert Groessen die keine Zweierpotenz sind`() {
        Fft(1000)
    }
}
