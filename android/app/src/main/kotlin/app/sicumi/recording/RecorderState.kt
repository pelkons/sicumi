package app.sicumi.recording

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Снимок идущей записи для экрана и уведомления. null — запись не идёт. */
data class RecordingSnapshot(
    val meetingId: String,
    val title: String,
    /** Записанное время до последнего возобновления. */
    val accumulatedMs: Long,
    /** elapsedRealtime момента последнего старта/возобновления; 0 — на паузе. */
    val resumedAt: Long,
    val bookmarks: List<Long>,
    /** Последние уровни громкости 0..1, самый свежий — в конце. */
    val levels: List<Float>,
) {
    val paused: Boolean get() = resumedAt == 0L

    fun elapsedMs(now: Long = SystemClock.elapsedRealtime()): Long =
        accumulatedMs + if (paused) 0 else now - resumedAt
}

object RecorderState {
    const val LEVELS = 28

    private val _current = MutableStateFlow<RecordingSnapshot?>(null)
    val current: StateFlow<RecordingSnapshot?> = _current.asStateFlow()

    internal fun set(snapshot: RecordingSnapshot?) {
        _current.value = snapshot
    }

    internal fun update(change: (RecordingSnapshot) -> RecordingSnapshot) {
        _current.value = _current.value?.let(change)
    }
}
