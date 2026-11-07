package io.github.tancheng33.tingo.audio

import android.media.MediaPlayer
import java.io.File

/**
 * 用平台自带的 `MediaPlayer` 播 wav。
 *
 * 不引第三方播放器：会议录音是单文件 16 kHz 单声道 wav，`seekTo` 与拖动进度足够，
 * 为这点需求多背一个依赖不划算。
 */
class MediaPlayerAudioPlayer : AudioPlayer {

    private var player: MediaPlayer? = null
    private var durationMs: Long = 0
    private var pendingSeekMs: Long? = null

    override fun prepare(file: File, onReady: (durationMs: Long) -> Unit) {
        release()
        val mp = try {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener {
                    durationMs = it.duration.toLong().coerceAtLeast(0)
                    pendingSeekMs?.let { position -> it.seekTo(position.toInt()) }
                    pendingSeekMs = null
                    onReady(durationMs)
                }
                setOnCompletionListener { it.seekTo(0); it.pause() }
                prepareAsync()
            }
        } catch (e: Exception) {
            onReady(0)
            return
        }
        player = mp
    }

    override fun play() {
        runCatching { player?.start() }
    }

    override fun pause() {
        runCatching { player?.pause() }
    }

    override fun seekTo(positionMs: Long) {
        val mp = player ?: run {
            pendingSeekMs = positionMs
            return
        }
        val target = positionMs.coerceIn(0, durationMs.coerceAtLeast(0))
        // 还没准备好时 seekTo 会抛 IllegalStateException，先记下来等 onPrepared
        pendingSeekMs = target
        runCatching { mp.seekTo(target.toInt()); pendingSeekMs = null }
    }

    override val positionMs: Long
        get() = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)

    override val isPlaying: Boolean
        get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    override fun release() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        durationMs = 0
        pendingSeekMs = null
    }
}
