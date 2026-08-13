package net.birdmic.rtsp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Rollendes Spektrogramm.
 *
 * Zeigt, was das Mikrofon gerade sieht: Stoerquellen in der Naehe, ob der
 * Frequenzgang oben abbricht, ob eine Aufnahmequelle mehr liefert als eine
 * andere. Damit laesst sich die Aufstellung pruefen, ohne jedes Mal zum Pi zu
 * wechseln.
 *
 * Die Spalten werden ringfoermig in eine kleine Bitmap geschrieben und beim
 * Zeichnen in zwei Blits auf die Ansicht skaliert -- das vermeidet, bei jedem
 * Bild die gesamte Bitmap zu verschieben.
 */
class SpectrogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val bitmap = Bitmap.createBitmap(COLUMNS, ROWS, Bitmap.Config.ARGB_8888)
    private val column = IntArray(ROWS)
    private var head = 0

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }
    private val srcLeft = Rect()
    private val srcRight = Rect()
    private val dstLeft = Rect()
    private val dstRight = Rect()

    /** Obere Grenze der Anzeige. Darueber ist bei Vogelstimmen praktisch nichts. */
    var maxFrequencyHz: Int = 16000
    var sampleRate: Int = 48000

    init {
        bitmap.eraseColor(BACKGROUND)
        // Schattenwurf auf Text braucht Software-Rendering.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /**
     * Haengt eine neue Spalte an. [magnitudes] sind lineare Betraege 0..1 ueber
     * die FFT-Bins; nur der Bereich bis [maxFrequencyHz] wird dargestellt.
     */
    fun push(magnitudes: FloatArray) {
        val nyquist = sampleRate / 2f
        val usableBins = ((maxFrequencyHz / nyquist) * magnitudes.size)
            .roundToInt().coerceIn(1, magnitudes.size)

        for (row in 0 until ROWS) {
            // Zeile 0 ist oben, also hohe Frequenzen oben.
            val fraction = (ROWS - 1 - row).toFloat() / (ROWS - 1)
            val bin = (fraction * (usableBins - 1)).roundToInt()
            column[row] = colorFor(magnitudes[bin])
        }
        bitmap.setPixels(column, 0, 1, head, 0, 1, ROWS)
        head = (head + 1) % COLUMNS
        postInvalidateOnAnimation()
    }

    fun clear() {
        bitmap.eraseColor(BACKGROUND)
        head = 0
        invalidate()
    }

    /** dB-Skala, damit leise Rufe ueberhaupt sichtbar werden. */
    private fun colorFor(magnitude: Float): Int {
        val db = 20f * log10(magnitude.coerceAtLeast(1e-7f))
        // -90 dB (Stille) bis -10 dB (sehr laut) auf 0..1 abbilden.
        val t = ((db + 90f) / 80f).coerceIn(0f, 1f)
        return ramp(t)
    }

    /** Schwarz → Blau → Grün → Gelb → Weiß. */
    private fun ramp(t: Float): Int {
        val stops = RAMP
        val scaled = t * (stops.size - 1)
        val index = scaled.toInt().coerceIn(0, stops.size - 2)
        val local = scaled - index
        val a = stops[index]
        val b = stops[index + 1]
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * local).roundToInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * local).roundToInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * local).roundToInt()
        return Color.rgb(r, g, bl)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        // Der aelteste Teil steht rechts vom Schreibkopf und gehoert nach links.
        val tail = COLUMNS - head
        val splitX = (tail.toFloat() / COLUMNS * w).roundToInt()

        srcLeft.set(head, 0, COLUMNS, ROWS)
        dstLeft.set(0, 0, splitX, h)
        canvas.drawBitmap(bitmap, srcLeft, dstLeft, paint)

        if (head > 0) {
            srcRight.set(0, 0, head, ROWS)
            dstRight.set(splitX, 0, w, h)
            canvas.drawBitmap(bitmap, srcRight, dstRight, paint)
        }

        drawFrequencyLabels(canvas, h)
    }

    private fun drawFrequencyLabels(canvas: Canvas, h: Int) {
        var khz = 4
        while (khz * 1000 < maxFrequencyHz) {
            val y = h - (khz * 1000f / maxFrequencyHz) * h
            canvas.drawText("$khz kHz", 8f, y - 4f, labelPaint)
            khz += 4
        }
    }

    companion object {
        private const val COLUMNS = 320
        private const val ROWS = 160
        private const val BACKGROUND = Color.BLACK

        private val RAMP = intArrayOf(
            Color.rgb(0, 0, 0),
            Color.rgb(20, 20, 90),
            Color.rgb(0, 110, 140),
            Color.rgb(30, 170, 80),
            Color.rgb(220, 200, 40),
            Color.rgb(255, 255, 255),
        )
    }
}
