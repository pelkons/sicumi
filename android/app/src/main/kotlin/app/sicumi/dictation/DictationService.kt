package app.sicumi.dictation

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.sicumi.MainActivity
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
 * Каждое нажатие отзывается вибрацией; любая проблема показывается сообщением рядом с кнопкой.
 */
class DictationService : AccessibilityService() {

    /** Что пошло не так и куда отправить пользователя, чтобы это исправить. */
    private data class Problem(val text: String, val fix: Fix? = null)

    private enum class Fix { Settings, Setup }

    private val main = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var bubble: BubbleView? = null
    private var messageView: BubbleMessageView? = null
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
        hideMessage()
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
        val size = bubbleSize()
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

    private fun bubbleSize() = (64 * resources.displayMetrics.density).toInt()

    private fun onBubbleTap() {
        val view = bubble ?: return
        when {
            recorder != null -> stopAndProcess(view)
            !busy -> startRecording(view)
        }
    }

    private fun startRecording(view: BubbleView) {
        hideMessage()
        // Проверяем всё, что можно проверить до записи, чтобы не заставлять говорить впустую.
        precheck()?.let {
            fail(it)
            return
        }
        val r = PcmRecorder(audioFile())
        if (!r.start()) {
            fail(Problem(getString(R.string.bubble_mic_failed)))
            return
        }
        recorder = r
        startedAt = SystemClock.elapsedRealtime()
        Haptics.start(this)
        view.setState(BubbleView.State.Recording)
        main.postDelayed(autoStop, MAX_RECORDING_MS)
    }

    private fun precheck(): Problem? = when {
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
            Problem(getString(R.string.bubble_no_permission), Fix.Setup)
        !engine.hasKey() -> Problem(getString(R.string.dictation_no_key), Fix.Settings)
        !isOnline() -> Problem(getString(R.string.bubble_offline))
        else -> null
    }

    private val autoStop = Runnable { bubble?.let { if (recorder != null) stopAndProcess(it) } }

    private fun stopAndProcess(view: BubbleView) {
        val current = recorder ?: return
        main.removeCallbacks(autoStop)
        recorder = null
        busy = true
        Haptics.stop(this)
        // Поле запоминаем в момент остановки: пока идёт распознавание, фокус может уйти.
        val target = findEditableFocus()
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        view.setState(BubbleView.State.Processing)
        scope.launch {
            withContext(Dispatchers.IO) { current.stop() }
            val problem: Problem = when {
                elapsed < MIN_RECORDING_MS -> Problem(getString(R.string.bubble_too_short))
                current.silenced == true -> Problem(getString(R.string.dictation_mic_silenced))
                current.peak == 0 -> Problem(getString(R.string.dictation_empty))
                else -> try {
                    when (val result = engine.run(audioFile())) {
                        is DictationResult.Text -> {
                            val inserted = insertText(result.text, target)
                            finish()
                            Haptics.success(this@DictationService)
                            bubble?.setState(BubbleView.State.Done)
                            if (!inserted) showMessage(Problem(getString(R.string.bubble_copied)))
                            main.postDelayed({ if (recorder == null) bubble?.setState(BubbleView.State.Idle) }, 1000)
                            return@launch
                        }
                        DictationResult.NoKey -> Problem(getString(R.string.dictation_no_key), Fix.Settings)
                        DictationResult.Empty -> Problem(getString(R.string.dictation_empty))
                    }
                } catch (e: ApiException) {
                    problemFor(e)
                } catch (e: Exception) {
                    Problem(getString(R.string.dictation_failed, e.javaClass.simpleName))
                }
            }
            finish()
            fail(problem)
        }
    }

    private fun finish() {
        audioFile().delete()
        busy = false
    }

    /** Ошибка: вибрация, «!» на кнопке и сообщение рядом с ней. */
    private fun fail(problem: Problem) {
        Haptics.error(this)
        bubble?.setState(BubbleView.State.Error)
        showMessage(problem)
        main.postDelayed({
            if (recorder == null && !busy) bubble?.setState(BubbleView.State.Idle)
        }, ERROR_STATE_MS)
    }

    private fun problemFor(e: ApiException): Problem {
        val provider = "⁨${ApiSelection(this).selected(ApiPurpose.Dictation).name}⁩"
        return when {
            e.code == 0 -> Problem(getString(R.string.dictation_network))
            e.isAuth -> Problem(getString(R.string.dictation_key_invalid, provider), Fix.Settings)
            e.code == 429 -> Problem(getString(R.string.dictation_rate_limited, provider))
            else -> Problem(getString(R.string.dictation_failed_code, provider, e.code))
        }
    }

    private val hideMessageRunnable = Runnable { hideMessage() }

    /** Сообщение над кнопкой. Нажатие по нему открывает нужный экран Sicumi (если есть что исправлять). */
    private fun showMessage(problem: Problem) {
        val wm = windowManager ?: return
        hideMessage()
        val metrics = resources.displayMetrics
        val margin = (24 * metrics.density).toInt()
        val view = BubbleMessageView(this, metrics.widthPixels - 2 * margin)
        val hint = when (problem.fix) {
            Fix.Settings -> getString(R.string.bubble_fix_settings)
            Fix.Setup -> getString(R.string.bubble_fix_setup)
            null -> null
        }
        view.show(problem.text, hint)
        val maxY = metrics.heightPixels - (160 * metrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = minOf(posY + bubbleSize() + (10 * metrics.density).toInt(), maxY)
        }
        view.setOnClickListener {
            hideMessage()
            problem.fix?.let(::openApp)
        }
        wm.addView(view, lp)
        messageView = view
        main.postDelayed(hideMessageRunnable, if (problem.fix != null) MESSAGE_WITH_ACTION_MS else MESSAGE_MS)
    }

    private fun hideMessage() {
        main.removeCallbacks(hideMessageRunnable)
        val view = messageView ?: return
        windowManager?.removeView(view)
        messageView = null
    }

    private fun openApp(fix: Fix) {
        val route = when (fix) {
            Fix.Settings -> MainActivity.OPEN_SETTINGS
            Fix.Setup -> MainActivity.OPEN_DICTATION
        }
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Если система не дала открыть экран, сообщение уже было показано — этого достаточно.
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun audioFile() = File(cacheDir, "dictation.wav")

    /** Вставка в позицию курсора активного поля; если не вышло — в буфер обмена (false). */
    private fun insertText(dictated: String, target: AccessibilityNodeInfo?): Boolean {
        val node = target?.takeIf { it.refresh() && it.isEditable } ?: findEditableFocus()
        if (node == null) {
            copyToClipboard(dictated)
            return false
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
            return false
        }
        val cursor = insertAt + text.length
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            },
        )
        return true
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Sicumi", text))
    }

    companion object {
        /** Нажатия короче полусекунды считаем случайными. */
        private const val MIN_RECORDING_MS = 500L
        /** Диктовка ограничена 5 минутами (лимиты размера запроса у провайдеров). */
        private const val MAX_RECORDING_MS = 5 * 60 * 1000L
        private const val ERROR_STATE_MS = 2500L
        private const val MESSAGE_MS = 4500L
        private const val MESSAGE_WITH_ACTION_MS = 7000L

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
