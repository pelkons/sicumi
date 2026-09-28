package app.sicumi.dictation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import app.sicumi.R
import kotlin.math.abs

/** Плавающая кнопка диктовки. Обычный View (не Compose): живёт в окне accessibility-сервиса. */
@SuppressLint("ViewConstructor")
class BubbleView(context: Context) : FrameLayout(context) {

    enum class State { Idle, Recording, Processing, Done, Error }

    var onTap: (() -> Unit)? = null
    var onDrag: ((dx: Int, dy: Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val icon = ImageView(context)
    private val bg = GradientDrawable()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    init {
        // «Капля»: разные радиусы углов (порядок: TL, TR, BR, BL; по два значения на угол).
        val r1 = dp(26f); val r2 = dp(32f); val r3 = dp(29f); val r4 = dp(22f)
        bg.cornerRadii = floatArrayOf(r1, r1, r2, r2, r3, r3, r4, r4)
        background = bg
        elevation = dp(6f)
        addView(icon, LayoutParams(dp(28f).toInt(), dp(28f).toInt(), Gravity.CENTER))
        contentDescription = context.getString(R.string.bubble_start)
        isClickable = true
        setState(State.Idle)
    }

    fun setState(state: State) {
        when (state) {
            State.Idle -> style(VIOLET, R.drawable.ic_mic, WHITE)
            State.Recording -> style(TANGERINE, R.drawable.ic_wave, INK)
            State.Processing -> style(VIOLET2, R.drawable.ic_wave, WHITE)
            State.Done -> style(SUCCESS_BG, R.drawable.ic_check, SUCCESS)
            State.Error -> style(PEACH, R.drawable.ic_alert, PEACH_TEXT)
        }
    }

    private fun style(background: Int, iconRes: Int, tint: Int) {
        bg.setColor(background)
        icon.setImageResource(iconRes)
        icon.setColorFilter(tint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; lastX = downX; lastY = downY; dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) dragging = true
                if (dragging) {
                    onDrag?.invoke((e.rawX - lastX).toInt(), (e.rawY - lastY).toInt())
                    lastX = e.rawX; lastY = e.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) performClick()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    private fun dp(v: Float) = v * density

    private companion object {
        const val VIOLET = 0xFF4B2FD6.toInt()
        const val VIOLET2 = 0xFF5D44E6.toInt()
        const val TANGERINE = 0xFFFF8A3D.toInt()
        const val INK = 0xFF17123A.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val SUCCESS = 0xFF1D6B43.toInt()
        const val SUCCESS_BG = 0xFFD8F5E4.toInt()
        const val PEACH = 0xFFFFE3CF.toInt()
        const val PEACH_TEXT = 0xFF8A3E0B.toInt()
    }
}
