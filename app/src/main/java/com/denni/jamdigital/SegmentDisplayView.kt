package com.denni.jamdigital

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * View kustom yang menggambar teks dengan gaya seven-segment
 * (mendukung digit 0-9 dan karakter ':').
 * Segmen yang menyala berwarna hitam, yang mati abu-abu sangat muda.
 */
class SegmentDisplayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var displayText: String = "88:88:88"
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
                invalidate()
            }
        }

    var segmentOnColor: Int = Color.BLACK
    var segmentOffColor: Int = Color.parseColor("#EDEDED")

    // bit: a=0, b=1, c=2, d=3, e=4, f=5, g=6
    //      --a--
    //     |     |
    //     f     b
    //      --g--
    //     |     |
    //     e     c
    //      --d--
    private val segMap = mapOf(
        '0' to 0b0111111,
        '1' to 0b0000110,
        '2' to 0b1011011,
        '3' to 0b1001111,
        '4' to 0b1100110,
        '5' to 0b1101101,
        '6' to 0b1111101,
        '7' to 0b0000111,
        '8' to 0b1111111,
        '9' to 0b1101111
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val segPath = Path()

    private var unit = 0f        // lebar satu sel digit
    private var charX = FloatArray(0)
    private var baseY = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)

        // digit = 1.0 unit, ':' = 0.5 unit, spasi antar karakter = 0.22 unit
        var units = 0f
        for (c in displayText) {
            units += (if (c == ':') 0.5f else 1.0f) + 0.22f
        }
        units -= 0.22f
        if (units <= 0f) units = 1f

        var u = w / units
        u = min(u, h / 2.04f) // tinggi digit = 2 * unit
        unit = u

        val totalW = units * u
        baseY = (h - 2f * u) / 2f
        var x = (w - totalW) / 2f
        charX = FloatArray(displayText.length)
        for (i in displayText.indices) {
            charX[i] = x
            x += ((if (displayText[i] == ':') 0.5f else 1.0f) + 0.22f) * u
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val u = unit
        if (u <= 0f || charX.isEmpty()) return
        val t = u * 0.17f // ketebalan segmen

        for (i in displayText.indices) {
            val c = displayText[i]
            val x = charX[i]
            if (c == ':') {
                drawColon(canvas, x, u, t)
                continue
            }
            val mask = segMap[c] ?: 0
            for (s in 0..6) {
                paint.color = if (mask and (1 shl s) != 0) segmentOnColor else segmentOffColor
                drawSegment(canvas, s, x, baseY, u, t)
            }
        }
    }

    private fun drawSegment(canvas: Canvas, seg: Int, x0: Float, y0: Float, w: Float, t: Float) {
        val h = 2f * w
        when (seg) {
            0 -> hSeg(canvas, x0 + t * 0.6f, y0 + t / 2f, w - t * 1.2f, t)            // a
            6 -> hSeg(canvas, x0 + t * 0.6f, y0 + w, w - t * 1.2f, t)                 // g
            3 -> hSeg(canvas, x0 + t * 0.6f, y0 + h - t / 2f, w - t * 1.2f, t)        // d
            5 -> vSeg(canvas, x0 + t / 2f, y0 + t * 0.6f, w - t * 1.2f, t)            // f
            1 -> vSeg(canvas, x0 + w - t / 2f, y0 + t * 0.6f, w - t * 1.2f, t)        // b
            4 -> vSeg(canvas, x0 + t / 2f, y0 + w + t * 0.6f, w - t * 1.2f, t)        // e
            2 -> vSeg(canvas, x0 + w - t / 2f, y0 + w + t * 0.6f, w - t * 1.2f, t)    // c
        }
    }

    /** Segmen horizontal berbentuk heksagon (ujung runcing). */
    private fun hSeg(canvas: Canvas, x: Float, cy: Float, len: Float, t: Float) {
        val hh = t / 2f
        segPath.rewind()
        segPath.moveTo(x, cy)
        segPath.lineTo(x + hh, cy - hh)
        segPath.lineTo(x + len - hh, cy - hh)
        segPath.lineTo(x + len, cy)
        segPath.lineTo(x + len - hh, cy + hh)
        segPath.lineTo(x + hh, cy + hh)
        segPath.close()
        canvas.drawPath(segPath, paint)
    }

    /** Segmen vertikal berbentuk heksagon (ujung runcing). */
    private fun vSeg(canvas: Canvas, cx: Float, y: Float, len: Float, t: Float) {
        val hh = t / 2f
        segPath.rewind()
        segPath.moveTo(cx, y)
        segPath.lineTo(cx + hh, y + hh)
        segPath.lineTo(cx + hh, y + len - hh)
        segPath.lineTo(cx, y + len)
        segPath.lineTo(cx - hh, y + len - hh)
        segPath.lineTo(cx - hh, y + hh)
        segPath.close()
        canvas.drawPath(segPath, paint)
    }

    private fun drawColon(canvas: Canvas, x: Float, u: Float, t: Float) {
        paint.color = segmentOnColor
        val cx = x + u * 0.25f
        val r = t * 0.62f
        canvas.drawCircle(cx, baseY + u * 0.62f, r, paint)
        canvas.drawCircle(cx, baseY + u * 1.38f, r, paint)
    }
}
