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
import android.media.projection.MediaProjectionManager
import androidx.core.content.ContextCompat
import io.github.tancheng33.tingo.MainActivity
import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.settings.AppLocale
import io.github.tancheng33.tingo.settings.AppSettings
import io.github.tancheng33.tingo.settings.SharedPrefsStore
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
        withSystemAudio = intent?.getBooleanExtra(EXTRA_SYSTEM_AUDIO, false) ?: withSystemAudio
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
            intent?.let { attachProjection(it) }
        } else {
            updateNotification(state)
            intent?.let { attachProjection(it) }
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

    /**
     * 在前台服务已经带 mediaProjection 类型跑起来之后，再向系统要这条投影。
     *
     * 失败（用户撤销授权、系统拒绝）时回调 null，界面据此说"这次没抓到对方声音"，
     * 麦克风那一路继续录，不因为内录失败而整场作废。
     */
    private fun attachProjection(intent: Intent) {
        // 带类型的 getParcelableExtra 是 API 33 才有的：minSdk 26 上直接调用会在
        // Android 8/9 抛 NoSuchMethodError，每次开始录音都崩在 onStartCommand 里
        val data: Intent? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PROJECTION, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_PROJECTION)
        }
        if (data == null) return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            // Android 10 以下没有 AudioPlaybackCapture：明确告诉界面"没抓到"，别静默
            RecordingKeepAlive.onSystemAudioCapture(null)
            return
        }
        val manager = ContextCompat.getSystemService(this, MediaProjectionManager::class.java)
        val capture = runCatching {
            val projection = manager?.getMediaProjection(android.app.Activity.RESULT_OK, data)
            projection?.let { SystemAudioCapture(it) }
        }.getOrNull()
        RecordingKeepAlive.onSystemAudioCapture(capture)
        if (capture == null) runCatching { manager?.getMediaProjection(0, data)?.stop() }
    }

    /** 是否同时录系统声音：决定要不要把 mediaProjection 类型一起声明。 */
    private var withSystemAudio = false

    private fun startForegroundCompat(state: RecordUiState) {
        val type = if (withSystemAudio) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
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

    /**
     * 通知文案用**应用内选择的语言**，不是系统语言。
     *
     * 服务拿到的 Context 没套过 AppLocale（只有 Activity 在 attachBaseContext 里套了），
     * 所以中文界面配英文系统时会冒出英文通知。
     */
    private fun buildNotification(state: RecordUiState): Notification {
        val texts = AppLocale.wrap(this, AppSettings(SharedPrefsStore(this)).language)
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
        val titleTemplate = texts.getString(
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
            .setContentText(texts.getString(R.string.recording_notification_body))
            .setContentIntent(open)
            // 规格要求在通知栏就能结束录音：不必回到应用
            .addAction(0, texts.getString(R.string.recording_notification_stop), stop)
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
        /**
         * @param systemAudio 是否在抓系统声音（会议软件的对方声音）。为 true 时前台服务
         *   必须同时声明 mediaProjection 类型，否则 Android 14 会直接杀掉进程。
         */
        /**
         * @param projection 屏幕录制授权结果。必须由**服务**在 startForeground 之后调用
         *   `getMediaProjection`——Android 14 起，前台服务没带 mediaProjection 类型就取投影
         *   会抛 SecurityException，直接崩在授权的回调里。
         */
        fun start(context: Context, systemAudio: Boolean = false, projection: Intent? = null) {
            val intent = Intent(context, RecordingService::class.java)
            intent.putExtra(EXTRA_SYSTEM_AUDIO, systemAudio)
            if (projection != null) intent.putExtra(EXTRA_PROJECTION, projection)
            ContextCompat.startForegroundService(context, intent)
        }

        const val EXTRA_SYSTEM_AUDIO = "systemAudio"
        const val EXTRA_PROJECTION = "projection"

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

    /**
     * 系统声音那一路准备好（或确认失败，参数为 null）时回调。
     *
     * 没有接收者时（界面已经销毁、或录音根本没开始）就地把投影停掉：
     * 否则系统的"正在录屏"指示会一直亮着，而用户看不到任何正在录的东西。
     */
    var onSystemAudioCapture: (SystemAudioCapture?) -> Unit = { capture ->
        runCatching { capture?.stop() }
    }
}
