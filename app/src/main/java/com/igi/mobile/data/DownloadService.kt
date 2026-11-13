package com.igi.mobile.data

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.igi.mobile.IgiApplication
import com.igi.mobile.MainActivity
import com.igi.mobile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository by lazy { GameRepository.get(this) }
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_CANCEL) {
            repository.cancelDownload()
            return START_NOT_STICKY
        }

        val all = intent.getBooleanExtra(EXTRA_ALL, false)
        val missions = intent.getStringArrayListExtra(EXTRA_MISSIONS)
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            .orEmpty()

        val initial = buildNotification(0, 0L, 0L)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            initial,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )

        job?.cancel()
        job = scope.launch {
            repository.performDownload(missions, all) { state ->
                val progress = state.progress
                val percent = ((progress?.fraction ?: 0f) * 100f).toInt().coerceIn(0, 100)
                notifyProgress(percent, progress?.doneBytes ?: 0L, progress?.totalBytes ?: 0L)
            }
            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(percent: Int, done: Long, total: Long): Notification {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancel = PendingIntent.getService(
            this,
            2,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, IgiApplication.CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Preparing IGI offline data")
            .setContentText(progressText(percent, done, total))
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Cancel", cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notifyProgress(percent: Int, done: Long, total: Long) {
        if (percent % 2 != 0 && done < total) return
        val notification = buildNotification(percent, done, total)
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun progressText(percent: Int, done: Long, total: Long): String {
        val doneMb = String.format(Locale.US, "%.1f", done / 1048576.0)
        val totalMb = String.format(Locale.US, "%.1f", total / 1048576.0)
        return if (total > 0L) "$percent%  •  $doneMb / $totalMb MB" else "Starting download…"
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.igi.mobile.action.START_DOWNLOAD"
        private const val ACTION_CANCEL = "com.igi.mobile.action.CANCEL_DOWNLOAD"
        private const val EXTRA_ALL = "all"
        private const val EXTRA_MISSIONS = "missions"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context, missions: Set<Int>, allMissions: Boolean) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ALL, allMissions)
                putStringArrayListExtra(EXTRA_MISSIONS, ArrayList(missions.map { it.toString() }))
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
