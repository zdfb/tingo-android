package io.github.tancheng33.tingo.transcription

import kotlin.math.sqrt

/**
 * 说话人聚类：把每段的声纹向量归到同一个说话人。
 *
 * 做法与桌面版一致——先按余弦相似度的"平均连接"合并，相似度低于阈值就停。
 * 不用固定人数：开会来几个人事先不知道。阈值沿用桌面调的 0.62。
 */
class SpeakerClustering(
    private val threshold: Float = 0.62f,
) {
    /**
     * @param embeddings 每段一个向量，长度必须一致
     * @return 每段的说话人编号，从 0 开始，按首次出现顺序排列
     */
    fun cluster(embeddings: List<FloatArray>): List<Int> {
        if (embeddings.isEmpty()) return emptyList()
        require(embeddings.all { it.size == embeddings.first().size }) { "声纹向量长度不一致" }

        val normalized = embeddings.map { normalize(it) }
        // 每个簇是一组下标
        var clusters = normalized.indices.map { listOf(it) }

        while (clusters.size > 1) {
            var best: Pair<Int, Int>? = null
            var bestScore = -1f
            for (i in clusters.indices) {
                for (j in i + 1 until clusters.size) {
                    val score = averageSimilarity(clusters[i], clusters[j], normalized)
                    if (score > bestScore) {
                        bestScore = score
                        best = i to j
                    }
                }
            }
            val pair = best ?: break
            if (bestScore < threshold) break
            val (i, j) = pair
            clusters = clusters.filterIndexed { index, _ -> index != i && index != j } +
                listOf(clusters[i] + clusters[j])
        }

        // 簇内按最早出现的段排序，编号按首次发言顺序
        val ordered = clusters.sortedBy { it.min() }
        val result = IntArray(embeddings.size)
        ordered.forEachIndexed { speaker, members -> members.forEach { result[it] = speaker } }
        return result.toList()
    }

    private fun averageSimilarity(a: List<Int>, b: List<Int>, vectors: List<FloatArray>): Float {
        var sum = 0f
        var count = 0
        for (i in a) {
            for (j in b) {
                sum += dot(vectors[i], vectors[j])
                count++
            }
        }
        return if (count == 0) 0f else sum / count
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (k in a.indices) sum += a[k] * b[k]
        return sum
    }

    private fun normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm == 0f) return v
        return FloatArray(v.size) { v[it] / norm }
    }
}
