package net.birdmic.rtsp

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Prueft den Frequenzgang: tiefe Toene muessen stark gedaempft werden, hohe
 * praktisch unveraendert durchkommen. Genau darum geht es beim Wind- und
 * Rumpelfilter.
 */
class HighPassFilterTest {

    private val sampleRate = 48000
    private val cutoff = 150f

    /** Erzeugt einen Sinus und gibt das Verhaeltnis Ausgang/Eingang als RMS zurueck. */
    private fun gainAt(frequency: Double): Double {
        val length = sampleRate // eine Sekunde
        val input = ShortArray(length) {
            (sin(2.0 * PI * frequency * it / sampleRate) * 10000).toInt().toShort()
        }
        val inputRms = rms(input, 0)

        val filter = HighPassFilter(cutoff, sampleRate, 1)
        filter.process(input, length)

        // Die erste Haelfte enthaelt das Einschwingen des Filters, daher nur
        // den eingeschwungenen Teil messen.
        val outputRms = rms(input, length / 2)
        return outputRms / inputRms
    }

    private fun rms(data: ShortArray, from: Int): Double {
        var sum = 0.0
        for (i in from until data.size) sum += data[i].toDouble() * data[i].toDouble()
        return sqrt(sum / (data.size - from))
    }

    @Test
    fun `dampft 30 Hz sehr stark`() {
        val gain = gainAt(30.0)
        assertTrue("30 Hz sollte fast verschwinden, war $gain", gain < 0.05)
    }

    @Test
    fun `dampft 50 Hz deutlich`() {
        val gain = gainAt(50.0)
        assertTrue("50 Hz sollte stark gedaempft sein, war $gain", gain < 0.2)
    }

    @Test
    fun `laesst 1 kHz praktisch unveraendert`() {
        val gain = gainAt(1000.0)
        assertTrue("1 kHz sollte fast unveraendert sein, war $gain", gain > 0.95)
    }

    @Test
    fun `laesst 4 kHz unveraendert`() {
        val gain = gainAt(4000.0)
        assertTrue("4 kHz sollte unveraendert sein, war $gain", gain > 0.95)
    }

    @Test
    fun `schneidet die tiefste Vogelstimme nicht an`() {
        // Der Uhu liegt bei etwa 250 Hz -- der muss weitgehend durchkommen.
        val gain = gainAt(250.0)
        assertTrue("250 Hz darf nicht stark gedaempft werden, war $gain", gain > 0.7)
    }

    @Test
    fun `stereo behandelt beide Kanaele getrennt`() {
        // Links 30 Hz, rechts 2 kHz, interleaved. Nach dem Filter muss rechts
        // deutlich lauter sein als links.
        val frames = sampleRate
        val data = ShortArray(frames * 2)
        for (i in 0 until frames) {
            data[i * 2] = (sin(2.0 * PI * 30.0 * i / sampleRate) * 10000).toInt().toShort()
            data[i * 2 + 1] = (sin(2.0 * PI * 2000.0 * i / sampleRate) * 10000).toInt().toShort()
        }
        HighPassFilter(cutoff, sampleRate, 2).process(data, data.size)

        var left = 0.0
        var right = 0.0
        for (i in frames / 2 until frames) {
            left += data[i * 2].toDouble() * data[i * 2]
            right += data[i * 2 + 1].toDouble() * data[i * 2 + 1]
        }
        assertTrue("Rechts (2 kHz) muss deutlich lauter sein als links (30 Hz)", right > left * 100)
    }
}
