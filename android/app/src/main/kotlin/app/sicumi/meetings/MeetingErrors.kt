package app.sicumi.meetings

import android.content.Context
import androidx.annotation.StringRes
import app.sicumi.R

/** Коды ошибок конвейера → текст для пользователя. */
object MeetingErrors {

    fun message(context: Context, meeting: Meeting): String {
        val code = meeting.error.orEmpty()
        val atProcessing = meeting.failedAt == MeetingStatus.Summarizing
        return when {
            code == "no_key:transcription" -> context.getString(R.string.error_no_key_transcription)
            code == "no_key:processing" -> context.getString(R.string.error_no_key_processing)
            code == "auth" -> context.getString(if (atProcessing) R.string.error_auth_processing else R.string.error_auth_transcription)
            code == "network" -> context.getString(R.string.error_network)
            code == "rate" -> context.getString(R.string.error_rate)
            code == "too_large" -> context.getString(R.string.error_too_large)
            code == "audio" -> context.getString(R.string.error_audio)
            code == "empty" -> context.getString(R.string.error_empty)
            code.startsWith("http:") -> context.getString(R.string.error_http, code.removePrefix("http:"))
            else -> context.getString(R.string.error_other, code.removePrefix("other:"))
        }
    }

    /** Ошибка, которую исправляют в настройках (ключ отсутствует или отклонён). */
    fun needsSettings(meeting: Meeting): Boolean {
        val code = meeting.error.orEmpty()
        return code.startsWith("no_key:") || code == "auth"
    }

    @StringRes
    fun statusLabel(status: MeetingStatus): Int = when (status) {
        MeetingStatus.Recording -> R.string.status_recording
        MeetingStatus.Recorded -> R.string.status_recorded
        MeetingStatus.Preparing -> R.string.status_preparing
        MeetingStatus.Transcribing -> R.string.status_transcribing
        MeetingStatus.Summarizing -> R.string.status_summarizing
        MeetingStatus.Ready -> R.string.status_ready
        MeetingStatus.Failed -> R.string.status_failed
    }
}
