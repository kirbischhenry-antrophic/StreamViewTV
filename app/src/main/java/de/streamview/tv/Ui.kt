package de.streamview.tv

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Kleine Helfer für eine dunkle, fernbedienungsfreundliche Oberfläche ohne zusätzliche Bibliotheken. */
object Ui {
    const val BG = 0xFF0E1116.toInt()
    const val CARD = 0xFF1C232C.toInt()
    const val ACCENT = 0xFF1E88E5.toInt()
    const val TEXT_DIM = 0xFFB0BEC5.toInt()
    const val DIALOG_THEME = android.R.style.Theme_Material_Dialog_Alert

    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun focusBackground(c: Context, normal: Int = CARD): StateListDrawable {
        fun shape(color: Int, stroke: Int) = GradientDrawable().apply {
            setColor(color); cornerRadius = dp(c, 10f).toFloat(); setStroke(dp(c, 3f), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(ACCENT, Color.WHITE))
            addState(intArrayOf(android.R.attr.state_pressed), shape(0xFF1565C0.toInt(), Color.WHITE))
            addState(intArrayOf(), shape(normal, Color.TRANSPARENT))
        }
    }

    fun button(c: Context, text: String, sizeSp: Float = 18f): Button = Button(c).apply {
        this.text = text
        isAllCaps = false
        textSize = sizeSp
        setTextColor(Color.WHITE)
        isFocusable = true
        stateListAnimator = null
        background = focusBackground(c)
        val p = dp(c, 12f)
        setPadding(p * 2, p, p * 2, p)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
    }

    fun title(c: Context, text: String, sizeSp: Float = 28f) = TextView(c).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
    }

    fun label(c: Context, text: String, sizeSp: Float = 15f, color: Int = TEXT_DIM) = TextView(c).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
    }

    fun rowParams(c: Context, bottomDp: Float = 10f) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(c, bottomDp) }

    fun dialog(c: Context): AlertDialog.Builder = AlertDialog.Builder(c, DIALOG_THEME)

    fun toast(c: Context, msg: String, long: Boolean = false) =
        Toast.makeText(c.applicationContext, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}
