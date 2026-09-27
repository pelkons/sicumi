package app.sicumi.meeting

import android.media.MediaPlayer
import app.sicumi.meetings.AudioSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Проигрывает сегменты встречи как одну дорожку: позиция — от начала встречи,
 * по окончании сегмента автоматически переходит к следующему.
 */
class SegmentPlayer(private val dir: File, private val segments: List<AudioSegment>, val durationMs: Long) {

    data class State(val playing: Boolean, val positionMs: Long)

    private val _state = MutableStateFlow(State(false, 0))
    val state: StateFlow<State> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var index = -1

    fun toggle() = if (_state.value.playing) pause() else play()

    fun play() {
        if (player == null && _state.value.positionMs >= durationMs - 300) _state.value = State(false, 0)
        val p = player ?: run {
            seekTo(_state.value.positionMs)
            player
        } ?: return
        p.start()
        _state.value = _state.value.copy(playing = true)
    }

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        _state.value = State(false, currentPosition())
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceIn(0, durationMs.coerceAtLeast(0))
        val i = segments.indexOfLast { it.startMs <= target }.coerceAtLeast(0)
        if (segments.isEmpty()) return
        val wasPlaying = _state.value.playing
        if (i != index || player == null) open(i)
        player?.seekTo((target - segments[i].startMs).toInt())
        _state.value = State(wasPlaying, target)
        if (wasPlaying) player?.start()
    }

    /** Вызывать периодически из UI, пока идёт воспроизведение. */
    fun tick() {
        if (_state.value.playing) _state.value = _state.value.copy(positionMs = currentPosition())
    }

    fun release() {
        player?.release()
        player = null
        index = -1
    }

    private fun currentPosition(): Long {
        val p = player ?: return _state.value.positionMs
        val base = segments.getOrNull(index)?.startMs ?: 0L
        return base + p.currentPosition
    }

    private fun open(i: Int) {
        player?.release()
        player = null
        val file = File(dir, segments[i].file)
        if (!file.exists()) return
        val p = MediaPlayer()
        try {
            p.setDataSource(file.absolutePath)
            p.prepare()
        } catch (e: Exception) {
            p.release()
            return
        }
        p.setOnCompletionListener {
            if (i + 1 < segments.size) {
                open(i + 1)
                player?.start()
            } else {
                _state.value = State(false, durationMs)
                release()
            }
        }
        player = p
        index = i
    }
}
