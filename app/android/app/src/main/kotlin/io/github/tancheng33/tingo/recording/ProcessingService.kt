package io.github.tancheng33.tingo.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.tancheng33.tingo.MainActivity
import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.settings.AppLocale
import io.github.tancheng33.tingo.settings.AppSettings
import io.github.tancheng33.tingo.settings.SharedPrefsStore

/** Keeps automatic processing visible while the application is in the background. */
class ProcessingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val texts = AppLocale.wrap(this, AppSettings(SharedPrefsStore(this)).language)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, texts.getString(R.string.processing_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(texts.getString(R.string.processing_notification_title))
            .setContentText(texts.getString(R.string.processing_notification_body))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "tingo-processing"
        private const val NOTIFICATION_ID = 112
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, ProcessingService::class.java))
        fun stop(context: Context) { context.stopService(Intent(context, ProcessingService::class.java)) }
    }
}
