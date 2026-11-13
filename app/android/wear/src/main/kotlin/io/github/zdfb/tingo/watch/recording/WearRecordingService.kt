package io.github.zdfb.tingo.watch.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.zdfb.tingo.watch.communication.WearChannelSender
import io.github.zdfb.tingo.wear.protocol.WearAudioFrame
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** 手表端录音服务：16 kHz PCM 分片发送，连接未就绪时仍保留本地帧文件。 */
class WearRecordingService : Service() {
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var sender: WearChannelSender? = null
    private var sessionId: String = ""
    private var archive: FileOutputStream? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        createNotificationChannel()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        sessionId = UUID.randomUUID().toString()
        val dir = File(filesDir, "tingo-watch/$sessionId").apply { mkdirs() }
        archive = FileOutputStream(File(dir, "audio.frames"), false)
        sender = WearChannelSender(this).also { it.open(sessionId) }
        startCapture()
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val buffer = ByteArray(maxOf(minBuffer, FRAME_BYTES) * 2)
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            buffer.size,
        )
        check(audio.state == AudioRecord.STATE_INITIALIZED) { "手表麦克风初始化失败" }
        recorder = audio
        running = true
        audio.startRecording()
        worker = Thread({
            val pending = ShortArray(FRAME_SAMPLES)
            var pendingCount = 0
            var sequence = 0L
            try {
                while (running) {
                    val readBytes = audio.read(buffer, 0, buffer.size)
                    if (readBytes <= 0) continue
                    val shorts = readBytes / 2
                    val samples = ByteBuffer.wrap(buffer, 0, readBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    repeat(shorts) {
                        pending[pendingCount++] = samples.get()
                        if (pendingCount == pending.size) {
                            val frame = WearAudioFrame(sessionId, sequence++, SAMPLE_RATE, 1, pending.copyOf())
                            archiveFrame(frame)
                            sender?.send(frame)
                            pendingCount = 0
                        }
                    }
                }
            } finally {
                if (pendingCount > 0) {
                    val frame = WearAudioFrame(sessionId, sequence, SAMPLE_RATE, 1, pending.copyOf(pendingCount))
                    archiveFrame(frame)
                    sender?.send(frame)
                }
            }
        }, "tingo-watch-recorder").apply { isDaemon = true; start() }
    }

    private fun archiveFrame(frame: WearAudioFrame) {
        val bytes = io.github.zdfb.tingo.wear.protocol.WearFrameDecoder.encode(frame)
        archive?.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(bytes.size).array())
        archive?.write(bytes)
        archive?.flush()
    }

    override fun onDestroy() {
        running = false
        runCatching { recorder?.stop() }
        runCatching { worker?.join(1_000) }
        runCatching { recorder?.release() }
        sender?.close()
        archive?.close()
        recorder = null
        worker = null
        sender = null
        archive = null
        super.onDestroy()
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("听过 · 正在录音")
        .setContentText("手机端实时转写中")
        .setOngoing(true)
        .build()

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "手表录音", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 5_120
        private const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val CHANNEL_ID = "tingo-watch-recording"
        private const val NOTIFICATION_ID = 7
    }
}
