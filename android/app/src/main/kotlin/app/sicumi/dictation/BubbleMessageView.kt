package app.sicumi.dictation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import app.sicumi.R

/**
 * Сообщение рядом с кнопкой диктовки: что пошло не так и что делать.
 * Живёт в отдельном окне accessibility-сервиса, поэтому видно поверх любого приложения
 * (обычный Toast из сервиса на части прошивок не показывается).
 */
@SuppressLint("ViewConstructor")
class BubbleMessageView(context: Context, maxWidthPx: Int) : TextView(context) {

    private val density = resources.displayMetrics.density

    init {
        background = GradientDrawable().apply {
            cornerRadius = 18 * density
            setColor(INK)
        }
        elevation = 8 * density
        val h = (16 * density).toInt()
        val v = (12 * density).toInt()
        setPadding(h, v, h, v)
        maxWidth = maxWidthPx
        setTextColor(WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setLineSpacing(0f, 1.15f)
        typeface = try {
            resources.getFont(R.font.heebo)
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        textDirection = View.TEXT_DIRECTION_RTL
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    /** Текст ошибки и, если есть, подсказка-действие второй строкой (мандариновым цветом). */
    fun show(message: String, actionHint: String?) {
        val text = SpannableStringBuilder(message)
        if (actionHint != null) {
            text.append('\n')
            val start = text.length
            text.append(actionHint)
            text.setSpan(ForegroundColorSpan(TANGERINE), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        setText(text)
    }

    private companion object {
        const val INK = 0xFF17123A.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val TANGERINE = 0xFFFF8A3D.toInt()
    }
}
