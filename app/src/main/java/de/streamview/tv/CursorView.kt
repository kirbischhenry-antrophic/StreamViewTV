package de.streamview.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View

/** Virtueller Mauszeiger. Zeichnet nur – Eingaben verarbeitet die BrowserActivity. */
class CursorView(context: Context) : View(context) {

    private val unit = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.6f * unit; strokeJoin = Paint.Join.ROUND
    }
    private val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt(); style = Paint.Style.STROKE; strokeWidth = 3 * unit
    }
    private val arrow = Path().apply {
        val s = unit * 1.5f
        moveTo(0f, 0f); lineTo(0f, 17 * s); lineTo(4.5f * s, 13 * s); lineTo(7.5f * s, 19.5f * s)
        lineTo(10 * s, 18.5f * s); lineTo(7 * s, 12 * s); lineTo(12.5f * s, 12 * s); close()
    }

    var cursorX = -1f; private set
    var cursorY = -1f; private set
    private var clickTime = 0L
    var held = false
        set(v) { field = v; invalidate() }

    init {
        isClickable = false
        isFocusable = false
    }

    fun setCursor(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
        cursorY = y.coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
        invalidate()
    }

    fun showClick() {
        clickTime = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (cursorX < 0) setCursor(w / 2f, h / 2f) else setCursor(cursorX, cursorY)
    }

    override fun onDraw(canvas: Canvas) {
        if (cursorX < 0) return
        val dt = SystemClock.uptimeMillis() - clickTime
        if (dt < 300) {
            ripple.alpha = (255 * (1 - dt / 300f)).toInt()
            canvas.drawCircle(cursorX, cursorY, (8 + dt / 300f * 22) * unit, ripple)
            postInvalidateOnAnimation()
        }
        canvas.save()
        canvas.translate(cursorX, cursorY)
        if (held) canvas.scale(0.85f, 0.85f)
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, outline)
        canvas.restore()
    }
}
