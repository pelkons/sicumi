package app.sicumi.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.sicumi.MainActivity
import app.sicumi.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Держит процесс живым, пока идёт обработка встреч (загрузка аудио провайдеру может занять минуты).
 * Сама работа — в MeetingProcessor; сервис только показывает уведомление и завершается, когда очередь пуста.
 */
class ProcessingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    private var watcher: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Каждый startForegroundService обязан получить startForeground.
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        watcher?.cancel()
        watcher = scope.launch {
            MeetingProcessor.activeCount.first { it == 0 }
            // stopSelfResult с последним startId: если пришёл новый запуск, сервис продолжит работу.
            if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
        }
        return START_NOT_STICKY
    }

    /** Android 15+: лимит времени dataSync исчерпан — сервис нужно остановить, обработка продолжится без него. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wave)
            .setContentTitle(getString(R.string.processing_notification_title))
            .setContentText(getString(R.string.processing_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setColor(getColor(R.color.violet))
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.processing_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val CHANNEL_ID = "processing"
        private const val NOTIFICATION_ID = 43

        /** Если система не разрешает старт из фона, обработка всё равно идёт, пока жив процесс. */
        fun ensureRunning(context: Context) {
            try {
                context.startForegroundService(Intent(context, ProcessingService::class.java))
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException (API 31+) и т.п.
            }
        }
    }
}
