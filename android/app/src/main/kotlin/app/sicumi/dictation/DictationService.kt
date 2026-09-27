package app.sicumi.dictation

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import app.sicumi.R
import java.io.File

/**
 * Прототип фазы 0: плавающая кнопка поверх любых приложений.
 * Нажатие — запись, повторное нажатие — стоп и вставка текста в активное поле.
 * Пока вместо распознанного текста вставляется отчёт о записи (длительность и громкость).
 */
class DictationService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var bubble: BubbleView? = null
    private var recorder: PcmRecorder? = null
    private var startedAt = 0L
    private var busy = false

    // Позиция кнопки (от левого нижнего угла), переживает скрытие.
    private var posX = 0
    private var posY = 0

    override fun onServiceConnected() {
        windowManager = getSystemService(WindowManager::class.java)
        val d = resources.displayMetrics.density
        posX = (16 * d).toInt()
        posY = (360 * d).toInt()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (recorder != null || busy) return
        if (findEditableFocus() != null) showBubble() else hideBubble()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        recorder?.stop()
        recorder = null
        hideBubble()
        super.onDestroy()
    }

    private fun findEditableFocus(): AccessibilityNodeInfo? {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return if (node.isEditable && !node.isPassword) node else null
    }

    private fun showBubble() {
        if (bubble != null) return
        val wm = windowManager ?: return
        val size = (64 * resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.LEFT
            x = posX
            y = posY
        }
        val view = BubbleView(this)
        view.onTap = ::onBubbleTap
        view.onDrag = { dx, dy ->
            lp.x += dx
            lp.y -= dy
            posX = lp.x
            posY = lp.y
            wm.updateViewLayout(view, lp)
        }
        wm.addView(view, lp)
        bubble = view
    }

    private fun hideBubble() {
        val view = bubble ?: return
        windowManager?.removeView(view)
        bubble = null
    }

    private fun onBubbleTap() {
        val view = bubble ?: return
        val current = recorder
        if (current == null) {
            if (busy) return
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                toast(R.string.bubble_no_permission)
                return
            }
            val r = PcmRecorder(File(cacheDir, "dictation.wav"))
            if (!r.start()) {
                toast(R.string.bubble_mic_failed)
                return
            }
            recorder = r
            startedAt = SystemClock.elapsedRealtime()
            view.setState(BubbleView.State.Recording)
        } else {
            recorder = null
            busy = true
            view.setState(BubbleView.State.Processing)
            Thread {
                current.stop()
                val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000f
                val text = when {
                    current.silenced == true -> getString(R.string.probe_silenced)
                    current.peak == 0 -> getString(R.string.probe_silent)
                    else -> getString(R.string.probe_ok, seconds, current.peak)
                }
                main.post {
                    insertText(text)
                    bubble?.setState(BubbleView.State.Done)
                    main.postDelayed({
                        bubble?.setState(BubbleView.State.Idle)
                        busy = false
                    }, 1000)
                }
            }.start()
        }
    }

    /** Вставка в позицию курсора активного поля; если не вышло — в буфер обмена. */
    private fun insertText(text: String) {
        val node = findEditableFocus()
        if (node == null) {
            copyToClipboard(text)
            return
        }
        val raw = node.text?.toString().orEmpty()
        val base = if (node.isShowingHintText) "" else raw
        val selStart = node.textSelectionStart
        val selEnd = node.textSelectionEnd
        val hasSelection = selStart in 0..base.length && selEnd in selStart..base.length
        val newText = if (hasSelection) {
            base.substring(0, selStart) + text + base.substring(selEnd)
        } else {
            base + text
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            copyToClipboard(text)
            return
        }
        val cursor = (if (hasSelection) selStart else base.length) + text.length
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            },
        )
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Sicumi", text))
        toast(R.string.bubble_copied)
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val self = ComponentName(context, DictationService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == self }
        }
    }
}
