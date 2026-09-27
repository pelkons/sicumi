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
import app.sicumi.providers.ApiException
import app.sicumi.providers.ApiPurpose
import app.sicumi.providers.ApiSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Плавающая кнопка диктовки поверх любых приложений.
 * Нажатие — запись, повторное нажатие — стоп, распознавание у выбранного провайдера
 * и вставка чистого текста в активное поле (или в буфер обмена, если поле не принимает текст).
 */
class DictationService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var bubble: BubbleView? = null
    private var recorder: PcmRecorder? = null
    private var startedAt = 0L
    private var busy = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val engine by lazy { DictationEngine(this) }

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
        main.removeCallbacksAndMessages(null)
        scope.cancel()
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
        if (recorder == null) startRecording(view) else stopAndProcess(view)
    }

    private fun startRecording(view: BubbleView) {
        if (busy) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast(getString(R.string.bubble_no_permission))
            return
        }
        val r = PcmRecorder(audioFile())
        if (!r.start()) {
            toast(getString(R.string.bubble_mic_failed))
            return
        }
        recorder = r
        startedAt = SystemClock.elapsedRealtime()
        view.setState(BubbleView.State.Recording)
        main.postDelayed(autoStop, MAX_RECORDING_MS)
    }

    private val autoStop = Runnable { bubble?.let { if (recorder != null) stopAndProcess(it) } }

    private fun stopAndProcess(view: BubbleView) {
        val current = recorder ?: return
        main.removeCallbacks(autoStop)
        recorder = null
        busy = true
        // Поле запоминаем в момент остановки: пока идёт распознавание, фокус может уйти.
        val target = findEditableFocus()
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        view.setState(BubbleView.State.Processing)
        scope.launch {
            withContext(Dispatchers.IO) { current.stop() }
            val message: String? = when {
                elapsed < MIN_RECORDING_MS -> null
                current.silenced == true -> getString(R.string.dictation_mic_silenced)
                current.peak == 0 -> getString(R.string.dictation_empty)
                else -> try {
                    when (val result = engine.run(audioFile())) {
                        is DictationResult.Text -> {
                            insertText(result.text, target)
                            finish(success = true)
                            return@launch
                        }
                        DictationResult.NoKey -> getString(R.string.dictation_no_key)
                        DictationResult.Empty -> getString(R.string.dictation_empty)
                    }
                } catch (e: ApiException) {
                    errorMessage(e)
                } catch (e: Exception) {
                    getString(R.string.dictation_failed, e.javaClass.simpleName)
                }
            }
            message?.let(::toast)
            finish(success = false)
        }
    }

    private fun finish(success: Boolean) {
        audioFile().delete()
        if (success) {
            bubble?.setState(BubbleView.State.Done)
            main.postDelayed({
                bubble?.setState(BubbleView.State.Idle)
                busy = false
            }, 1000)
        } else {
            bubble?.setState(BubbleView.State.Idle)
            busy = false
        }
    }

    private fun errorMessage(e: ApiException): String {
        val provider = ApiSelection(this).selected(ApiPurpose.Dictation).name
        return when {
            e.code == 0 -> getString(R.string.dictation_network)
            e.isAuth -> getString(R.string.dictation_key_invalid, provider)
            e.code == 429 -> getString(R.string.dictation_rate_limited, provider)
            else -> getString(R.string.dictation_failed_code, provider, e.code)
        }
    }

    private fun audioFile() = File(cacheDir, "dictation.wav")

    /** Вставка в позицию курсора активного поля; если не вышло — в буфер обмена. */
    private fun insertText(dictated: String, target: AccessibilityNodeInfo?) {
        val node = target?.takeIf { it.refresh() && it.isEditable } ?: findEditableFocus()
        if (node == null) {
            copyToClipboard(dictated)
            return
        }
        val raw = node.text?.toString().orEmpty()
        val base = if (node.isShowingHintText) "" else raw
        val selStart = node.textSelectionStart
        val selEnd = node.textSelectionEnd
        val hasSelection = selStart in 0..base.length && selEnd in selStart..base.length
        val insertAt = if (hasSelection) selStart else base.length
        // Отделяем пробелом от предыдущего слова, если курсор стоит сразу после текста.
        val text = if (insertAt > 0 && !base[insertAt - 1].isWhitespace()) " $dictated" else dictated
        val newText = if (hasSelection) {
            base.substring(0, selStart) + text + base.substring(selEnd)
        } else {
            base + text
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            copyToClipboard(dictated)
            return
        }
        val cursor = insertAt + text.length
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
        toast(getString(R.string.bubble_copied))
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    companion object {
        /** Нажатия короче полусекунды считаем случайными. */
        private const val MIN_RECORDING_MS = 500L
        /** Диктовка ограничена 5 минутами (лимиты размера запроса у провайдеров). */
        private const val MAX_RECORDING_MS = 5 * 60 * 1000L

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
