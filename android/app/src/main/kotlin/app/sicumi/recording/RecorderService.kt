package app.sicumi.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import app.sicumi.MainActivity
import app.sicumi.R
import app.sicumi.meetings.AudioSegment
import app.sicumi.meetings.MeetingRepository
import app.sicumi.meetings.MeetingSource
import app.sicumi.meetings.MeetingStatus
import app.sicumi.pipeline.MeetingProcessor
import java.io.File
import kotlin.math.sqrt

/**
 * Запись встречи в foreground-сервисе (тип microphone): продолжается при заблокированном экране
 * и в фоне. AAC 16 кГц моно ~32 кбит/с (≈14 МБ в час). Каждые 20 минут записанного времени
 * начинается новый файл-сегмент: так длинные встречи проходят лимиты размера у провайдеров STT.
 * Пауза не создаёт сегмент — MediaRecorder.pause/resume.
 */
class RecorderService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val repo by lazy { MeetingRepository.get(this) }

    private var recorder: MediaRecorder? = null
    private var meetingId: String? = null
    private var segments = mutableListOf<AudioSegment>()
    private var segmentStartedAtMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            val snapshot = RecorderState.current.value ?: return
            if (!snapshot.paused) {
                val amp = try { recorder?.maxAmplitude ?: 0 } catch (e: Exception) { 0 }
                val level = sqrt(amp / 32767f).coerceIn(0f, 1f)
                RecorderState.update { it.copy(levels = (it.levels + level).takeLast(RecorderState.LEVELS)) }
                if (snapshot.elapsedMs() - segmentStartedAtMs >= SEGMENT_MS) rotateSegment()
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getStringExtra(EXTRA_TITLE).orEmpty())
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_BOOKMARK -> bookmark()
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (recorder != null) finishRecording()
        super.onDestroy()
    }

    private fun start(title: String) {
        if (meetingId != null) {
            goForeground()
            return
        }
        createChannel()
        val meeting = repo.create(title, MeetingSource.Recorded, MeetingStatus.Recording)
        meetingId = meeting.id
        RecorderState.set(
            RecordingSnapshot(
                meetingId = meeting.id,
                title = title,
                accumulatedMs = 0,
                resumedAt = SystemClock.elapsedRealtime(),
                bookmarks = emptyList(),
                levels = emptyList(),
            ),
        )
        goForeground()
        if (!startSegment(0)) {
            Toast.makeText(this, R.string.recording_mic_failed, Toast.LENGTH_LONG).show()
            RecorderState.set(null)
            repo.delete(meeting.id)
            meetingId = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        main.post(tick)
    }

    private fun startSegment(startMs: Long): Boolean {
        val id = meetingId ?: return false
        val file = File(repo.dir(id), "part-%03d.m4a".format(segments.size + 1))
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            segmentStartedAtMs = startMs
            segments.add(AudioSegment(file.name, startMs, "audio/m4a"))
            repo.update(id) { it.copy(segments = segments.toList()) }
            true
        } catch (e: Exception) {
            r.release()
            false
        }
    }

    private fun stopRecorder() {
        val r = recorder ?: return
        recorder = null
        try {
            r.stop()
        } catch (e: RuntimeException) {
            // stop() бросает, если данных не было совсем: такой сегмент пустой.
            segments.lastOrNull()?.let { last ->
                File(repo.dir(meetingId ?: return@let), last.file).delete()
                segments.removeAt(segments.lastIndex)
            }
        } finally {
            r.release()
        }
    }

    private fun rotateSegment() {
        val elapsed = RecorderState.current.value?.elapsedMs() ?: return
        stopRecorder()
        if (!startSegment(elapsed)) stop()
    }

    private fun pause() {
        val snapshot = RecorderState.current.value ?: return
        if (snapshot.paused) return
        try {
            recorder?.pause()
        } catch (e: Exception) {
            return
        }
        RecorderState.update { it.copy(accumulatedMs = it.elapsedMs(), resumedAt = 0L) }
        notifyUpdate()
    }

    private fun resume() {
        val snapshot = RecorderState.current.value ?: return
        if (!snapshot.paused) return
        try {
            recorder?.resume()
        } catch (e: Exception) {
            return
        }
        RecorderState.update { it.copy(resumedAt = SystemClock.elapsedRealtime()) }
        notifyUpdate()
    }

    private fun bookmark() {
        val snapshot = RecorderState.current.value ?: return
        val at = snapshot.elapsedMs()
        RecorderState.update { it.copy(bookmarks = it.bookmarks + at) }
        meetingId?.let { id -> repo.update(id) { it.copy(bookmarks = it.bookmarks + at) } }
        notifyUpdate()
    }

    private fun stop() {
        main.removeCallbacks(tick)
        val id = finishRecording()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (id != null) MeetingProcessor.enqueue(this, id)
    }

    /** Закрывает файл и сохраняет встречу как записанную. Возвращает id, если есть что обрабатывать. */
    private fun finishRecording(): String? {
        val id = meetingId ?: return null
        val snapshot = RecorderState.current.value
        stopRecorder()
        meetingId = null
        RecorderState.set(null)
        if (segments.isEmpty()) {
            repo.delete(id)
            return null
        }
        repo.update(id) {
            it.copy(
                status = MeetingStatus.Recorded,
                durationMs = snapshot?.elapsedMs() ?: it.durationMs,
                segments = segments.toList(),
                bookmarks = snapshot?.bookmarks ?: it.bookmarks,
            )
        }
        return id
    }

    // --- Уведомление ---

    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyUpdate() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val snapshot = RecorderState.current.value
        val paused = snapshot?.paused == true
        val elapsed = snapshot?.elapsedMs() ?: 0L
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_RECORDING, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val bookmarks = snapshot?.bookmarks?.size ?: 0
        val text = buildString {
            append(getString(if (paused) R.string.recording_paused else R.string.recording_active))
            if (bookmarks > 0) append(" · ").append(resources.getQuantityString(R.plurals.bookmarks_count, bookmarks, bookmarks))
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(snapshot?.title?.ifBlank { null } ?: getString(R.string.recording_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(getColor(R.color.violet))
            .addAction(
                if (paused) {
                    action(R.drawable.ic_mic, R.string.recording_resume, ACTION_RESUME)
                } else {
                    action(R.drawable.ic_pause, R.string.recording_pause, ACTION_PAUSE)
                },
            )
            .addAction(action(R.drawable.ic_star, R.string.recording_bookmark, ACTION_BOOKMARK))
            .addAction(action(R.drawable.ic_stop, R.string.recording_stop, ACTION_STOP))
        if (!paused) {
            builder.setUsesChronometer(true).setWhen(System.currentTimeMillis() - elapsed).setShowWhen(true)
        } else {
            builder.setShowWhen(false)
        }
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
    }

    private fun action(icon: Int, title: Int, action: String): Notification.Action {
        val pi = PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, RecorderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), getString(title), pi).build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 42
        private const val TICK_MS = 120L
        private const val SEGMENT_MS = 20 * 60 * 1000L

        const val ACTION_START = "app.sicumi.recording.START"
        const val ACTION_PAUSE = "app.sicumi.recording.PAUSE"
        const val ACTION_RESUME = "app.sicumi.recording.RESUME"
        const val ACTION_BOOKMARK = "app.sicumi.recording.BOOKMARK"
        const val ACTION_STOP = "app.sicumi.recording.STOP"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, title: String) {
            context.startForegroundService(
                Intent(context, RecorderService::class.java).setAction(ACTION_START).putExtra(EXTRA_TITLE, title),
            )
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, RecorderService::class.java).setAction(action))
        }
    }
}
