package app.sicumi.meetings

import android.content.Context
import app.sicumi.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Форматирование названий, дат и длительностей. Цифры и время всегда в LTR-порядке. */
object MeetingTitles {

    fun default(context: Context, time: Long): String =
        context.getString(R.string.meeting_default_title, dateTime(time))

    /** 28.09.26, 14:30 */
    fun dateTime(time: Long): String = SimpleDateFormat("dd.MM.yy, HH:mm", Locale.ROOT).format(Date(time))

    /** 42:17 или 1:05:03 */
    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%02d:%02d".format(Locale.ROOT, m, s)
    }

    /** 00:42:17 — для большого таймера записи. */
    fun timer(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d".format(Locale.ROOT, total / 3600, (total % 3600) / 60, total % 60)
    }
}
