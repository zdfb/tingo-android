package io.github.tancheng33.tingo.recording

import android.app.Notification
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
import io.github.tancheng33.tingo.ui.clockOf
import io.github.tancheng33.tingo.ui.record.RecordUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 录音期间的前台服务。
 *
 * 录制本身仍由 `RecordViewModel` 驱动（它持有采集与文件写入），这个服务只做一件事：
 * 让系统知道"用户正在录音"，从而在切后台、锁屏时不被回收，并在通知栏常驻一条状态。
 *
 * 反过来，服务被用户从通知栏结束（或系统回收）时，会把状态广播回 [RecordingKeepAlive]，
 * 界面据此把录音标记为失败，而不是假装还在录。
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        observer = scope.launch {
            RecordingKeepAlive.state.collect { state ->
                if (state == null) {
                    stopSelf()
                    return@collect
                }
                updateNotification(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 通知上的"结束录音"：请求界面收尾，然后自己退出
            RecordingKeepAlive.onStopRequested()
            stopSelf()
            return START_NOT_STICKY
        }
        val state = RecordingKeepAlive.state.value
        if (state == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            // 没有麦克风权限时系统会拒绝 microphone 类型的前台服务；
            // 这时候录音本身也起不来，安静退出比崩一次更好
            val ok = runCatching {
                startForegroundCompat(state)
                true
            }.getOrDefault(false)
            if (!ok) {
                // 已经被 startForegroundService 拉起来了，系统要求必须调一次 startForeground；
                // 用不带类型的方式满足契约，然后立刻退出（录音本来也起不来）
                runCatching { startForeground(RecordingNotification.NOTIFICATION_ID, buildNotification(state)) }
                stopSelf()
                return START_NOT_STICKY
            }
            started = true
        } else {
            updateNotification(state)
        }
        // 不要 START_STICKY：录音流已经没了，重启一个空服务只会让用户困惑
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        RecordingKeepAlive.onServiceDestroyed()
        super.onDestroy()
    }

    private fun startForegroundCompat(state: RecordUiState) {
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(
            this,
            RecordingNotification.NOTIFICATION_ID,
            buildNotification(state),
            type,
        )
    }

    private fun updateNotification(state: RecordUiState) {
        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java)
        manager?.notify(RecordingNotification.NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: RecordUiState): Notification {
        val recording = state is RecordUiState.Recording
        val elapsed = when (state) {
            is RecordUiState.Recording -> clockOf(state.elapsedMs.toULong())
            is RecordUiState.Paused -> clockOf(state.elapsedMs.toULong())
            else -> clockOf(0u)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val titleTemplate = getString(
            if (recording) R.string.recording_title_recording else R.string.recording_title_paused,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, RecordingNotification.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(RecordingNotification.title(titleTemplate, elapsed))
            .setContentText(getString(R.string.recording_notification_body))
            .setContentIntent(open)
            // 规格要求在通知栏就能结束录音：不必回到应用
            .addAction(0, getString(R.string.recording_notification_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createChannel() {
        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            RecordingNotification.CHANNEL_ID,
            getString(R.string.recording_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            description = getString(R.string.recording_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "io.github.tancheng33.tingo.action.STOP_RECORDING"
        /** 开始或更新前台服务；已在运行时不重复 startForeground。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}

/**
 * 录音状态在进程内的传递点。
 *
 * 服务与 ViewModel 通过它共享"当前是不是在录"：没有它，服务就不知道该显示什么、
 * 什么时候收工。
 */
object RecordingKeepAlive {
    private val _state = MutableStateFlow<RecordUiState?>(null)
    val state: StateFlow<RecordUiState?> = _state.asStateFlow()

    /** 由界面在状态变化时调用。 */
    fun publish(state: RecordUiState?) {
        _state.value = state
    }

    /** 服务被系统或用户结束时回调；界面据此把停在录音中的状态判为失败。 */
    var onServiceDestroyed: () -> Unit = {}

    /** 用户在通知栏点"结束录音"时回调；界面负责走正常的 stop 流程（保存文件）。 */
    var onStopRequested: () -> Unit = {}
}
