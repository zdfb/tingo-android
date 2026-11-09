package io.github.tancheng33.tingo.recording

import io.github.tancheng33.tingo.transcription.WavReader
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 逐块把多路音轨混成 16 kHz 单声道。
 *
 * 规格要求"长录音 MUST NOT 一次性把整段音频读入内存"：一小时双轨大约是
 * 115 MB 的 16 bit 数据、展开成浮点后 300+ MB，整段读必然 OOM。这里内存里只保留
 * 一个块（默认 1 秒），边读边混边写。
 */
object StreamingMixer {

    /** 每块输出的帧数（16 kHz 下 = 1 秒）。 */
    const val CHUNK_FRAMES = 16_000

    /**
     * @param sources 音轨文件（可以是 16 kHz 单声道，也可以是 48 kHz 立体声）
     * @param sink 收到 16 kHz 单声道浮点样本
     */
    fun mix(sources: List<File>, sink: WavWriter, chunkFrames: Int = CHUNK_FRAMES) {
        val tracks = mutableListOf<ChunkedTrack>()
        try {
            sources.forEach { tracks += ChunkedTrack(it) }
            while (true) {
                val chunks = tracks.map { it.next(chunkFrames) }
                if (chunks.all { it.isEmpty() }) break
                val length = chunks.maxOf { it.size }
                if (length == 0) break
                val mixed = FloatArray(length)
                chunks.forEach { chunk ->
                    for (i in chunk.indices) mixed[i] += chunk[i]
                }
                for (i in mixed.indices) mixed[i] = mixed[i].coerceIn(-1f, 1f)
                sink.append(AudioMix.samplesToBytes(mixed))
            }
        } finally {
            tracks.forEach { it.close() }
        }
    }

    /**
     * 一路音轨的分块读取 + 逐块重采样到 16 kHz。
     *
     * 重采样是线性的、跨块连续的：块边界上保留上一个样本参与插值，
     * 否则每小时会多出几十个不连续点（听不出来，但转写的能量特征会抖）。
     */
    private class ChunkedTrack(file: File) {
        private val raf = RandomAccessFile(file, "r")
        private val channels: Int
        private val sourceRate: Int
        private val ratio: Double
        /** 上一个块的最后一个样本，供块边界插值使用。 */
        private var carry = 0f
        private var started = false
        /** 下一块里第一个输出样本的位置（相对"carry + 本块"这个序列）。 */
        private var nextPos = 0.0

        init {
            val header = ByteArray(WavReader.HEADER_BYTES.toInt())
            raf.seek(0)
            val read = raf.read(header)
            require(read == header.size) { "音轨头不完整：${file.name}" }
            channels = ((header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)).coerceAtLeast(1)
            sourceRate = (header[24].toInt() and 0xFF) or
                ((header[25].toInt() and 0xFF) shl 8) or
                ((header[26].toInt() and 0xFF) shl 16) or
                ((header[27].toInt() and 0xFF) shl 24)
            ratio = if (sourceRate > 0) sourceRate.toDouble() / AudioMix.TARGET_RATE else 1.0
            raf.seek(WavReader.HEADER_BYTES)
        }

        /** 读出一块并重采样；没有更多数据时返回空数组。 */
        fun next(outputFrames: Int): FloatArray {
            val framesWanted = (outputFrames * ratio).toInt().coerceAtLeast(1)
            val bytesWanted = framesWanted * channels * 2
            val buffer = ByteArray(bytesWanted)
            val read = raf.read(buffer)
            val frames = if (read <= 0) 0 else read / (channels * 2)
            if (frames == 0) return FloatArray(0)

            val mono = FloatArray(frames)
            val byteBuffer = ByteBuffer.wrap(buffer, 0, frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) sum += byteBuffer.short / 32_768f
                mono[i] = sum / channels
            }

            // 序列 = [carry] + mono，位置 0 对应 carry
            val sequence = FloatArray(frames + 1)
            sequence[0] = if (started) carry else mono[0]
            System.arraycopy(mono, 0, sequence, 1, frames)

            val out = ArrayList<Float>(outputFrames + 1)
            var pos = nextPos
            val lastInterpolatable = sequence.size - 1
            while (pos < lastInterpolatable) {
                val lower = pos.toInt()
                val upper = (lower + 1).coerceAtMost(lastInterpolatable)
                val weight = (pos - lower).toFloat()
                out.add(sequence[lower] * (1 - weight) + sequence[upper] * weight)
                pos += ratio
            }
            // 换到下一块的坐标系：carry 变成 mono 的最后一个样本
            carry = mono[frames - 1]
            started = true
            nextPos = pos - frames
            return out.toFloatArray()
        }

        fun close() = runCatching { raf.close() }.let { }
    }
}
