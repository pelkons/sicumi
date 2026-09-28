package app.sicumi.dictation

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Тактильная отдача кнопки диктовки: старт, стоп, готово, ошибка — каждое ощущается по-своему. */
object Haptics {

    fun start(context: Context) = play(context, predefined(VibrationEffect.EFFECT_HEAVY_CLICK) ?: VibrationEffect.createOneShot(35, 255))

    fun stop(context: Context) = play(context, predefined(VibrationEffect.EFFECT_CLICK) ?: VibrationEffect.createOneShot(20, 180))

    fun success(context: Context) = play(context, predefined(VibrationEffect.EFFECT_DOUBLE_CLICK) ?: VibrationEffect.createWaveform(longArrayOf(0, 20, 70, 20), -1))

    fun error(context: Context) = play(context, VibrationEffect.createWaveform(longArrayOf(0, 60, 90, 60, 90, 60), -1))

    private fun predefined(effect: Int): VibrationEffect? =
        if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(effect) else null

    private fun play(context: Context, effect: VibrationEffect) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                vibrator.vibrate(effect)
            }
        } catch (e: Exception) {
            // Вибрация — дополнение, а не обязательная часть: без неё диктовка работает.
        }
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
}
