package id.my.sir.mpdclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Indikator bar sinyal 4 tingkat (level 0..4).
 * level = -1 berarti belum diketahui.
 */
class SignalBarsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var level: Int = -1
        set(value) {
            val v = value.coerceIn(-1, 4)
            if (field != v) {
                field = v
                invalidate()
            }
        }

    var barOnColor: Int = Color.BLACK
    var barOffColor: Int = Color.parseColor("#DDDDDD")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = 4
        val gap = width * 0.10f
        val bw = (width - gap * (n - 1)) / n
        if (bw <= 0f) return
        for (i in 0 until n) {
            val bh = height * (i + 1f) / n
            val left = i * (bw + gap)
            paint.color = if (level >= 0 && i < level) barOnColor else barOffColor
            canvas.drawRect(left, height - bh, left + bw, height.toFloat(), paint)
        }
    }
}
