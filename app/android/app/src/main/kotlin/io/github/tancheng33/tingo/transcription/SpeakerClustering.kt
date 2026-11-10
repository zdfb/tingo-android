package io.github.tancheng33.tingo.transcription

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 说话人聚类：移植桌面 `cluster.rs`（FunASR / SpeechBrain 的谱聚类 + 按余弦合并）。
 *
 * 为什么不是"按相似度两两合并"：那套平均连接法在会议里会把"同一个人换个语气"拆成两个人、
 * 又会把"两个人声音接近"并成一个，短会议上尤其明显。桌面用的是谱聚类——
 * 在余弦相似度图上做拉普拉斯特征分解、用特征值间隔估计人数，再按中心余弦合并。
 *
 * 门限与原实现逐条对齐：`n < 20` 一律算一个人（段太少时聚类只会瞎分）、
 * 最大 15 人、合并阈值 0.78、剪枝比例 0.022、k-means 10 次取最优（固定种子，结果可复现）。
 *
 * **一处刻意的偏差**：谱聚类只取 [SPECTRAL_LIMIT] 个片段做子采样。桌面用 faer（LAPACK）
 * 直接分解 1500×1500，Android 没有等价库，纯 Kotlin 的 Jacobi 分解在 256 维时已是秒级，
 * 再大就明显拖慢转写收尾。子采样后的其余片段按余弦归到最近的中心，流程与桌面一致。
 */
class SpeakerClustering {

    /**
     * @param embeddings 每段一个声纹向量，长度必须一致
     * @return 每段的说话人编号，从 0 开始，按首次出现顺序排列
     */
    fun cluster(embeddings: List<FloatArray>): List<Int> {
        if (embeddings.isEmpty()) return emptyList()
        require(embeddings.all { it.size == embeddings.first().size }) { "声纹向量长度不一致" }
        val n = embeddings.size
        // 段太少时聚类只会瞎分：桌面同样直接算作一个人
        if (n < MIN_SEGMENTS_FOR_CLUSTERING) return List(n) { 0 }

        val unit = embeddings.map { normalize(it) }
        val labels = if (n <= SPECTRAL_LIMIT) {
            spectral(unit)
        } else {
            // 均匀抽 limit 个做谱聚类，其余按余弦归到最近的中心
            val picked = (0 until SPECTRAL_LIMIT).map { i ->
                (i * (n - 1) + (SPECTRAL_LIMIT - 1) / 2) / (SPECTRAL_LIMIT - 1)
            }
            val subLabels = spectral(picked.map { unit[it] })
            val k = (subLabels.maxOrNull() ?: 0) + 1
            val centers = (0 until k).map { c ->
                normalize(
                    mean(
                        picked.indices
                            .filter { subLabels[it] == c }
                            .map { unit[picked[it]] },
                    ),
                )
            }
            unit.map { e -> argMaxFloat(centers.map { dot(e, it) }) }
        }
        return mergeByCosine(labels, embeddings, MERGE_THRESHOLD)
    }

    /** 余弦相似度图上的谱聚类：剪枝 → 拉普拉斯 → 特征分解 → 间隔定人数 → k-means。 */
    private fun spectral(unit: List<FloatArray>): List<Int> {
        val n = unit.size
        val affinity = Array(n) { i -> DoubleArray(n) { j -> dot(unit[i], unit[j]).toDouble() } }
        // p 剪枝：每行只保留最相似的一小部分（保底 6 个邻居）
        val p = if (n.toDouble() * PRUNE_RATIO < 6.0) 6.0 / n else PRUNE_RATIO
        val zeros = ((1.0 - p) * n).toInt()
        for (i in 0 until n) {
            val order = (0 until n).sortedBy { affinity[i][it] }
            for (j in order.take(zeros)) affinity[i][j] = 0.0
        }
        val sym = Array(n) { i ->
            DoubleArray(n) { j -> if (i == j) 0.0 else 0.5 * (affinity[i][j] + affinity[j][i]) }
        }
        val degree = DoubleArray(n) { i -> (0 until n).sumOf { abs(sym[i][it]) } }
        val laplacian = Array(n) { i -> DoubleArray(n) { j -> if (i == j) degree[i] else -sym[i][j] } }

        val (values, vectors) = jacobiEigen(laplacian)
        // 特征值升序：看前 maxSpeakers+1 个之间的间隔，最大间隔处就是说话人数
        val top = (MAX_SPEAKERS + 1).coerceAtMost(n)
        val lambdas = values.take(top)
        val gaps = lambdas.zipWithNext { a, b -> b - a }
        val k = if (gaps.isEmpty()) 1 else argMaxDouble(gaps) + 1
        val features = (0 until n).map { i -> DoubleArray(k) { c -> vectors[i][c] } }
        return kMeans(features, k)
    }

    /** k-means：k-means++ 初始化、固定种子、跑 10 次取平方误差最小的那次（结果可复现）。 */
    private fun kMeans(x: List<DoubleArray>, k: Int): List<Int> {
        if (k <= 1) return List(x.size) { 0 }
        val rng = Xorshift(SEED)
        var best = Double.POSITIVE_INFINITY
        var bestLabels = List(x.size) { 0 }
        repeat(10) {
            val centers = ArrayList<DoubleArray>()
            centers += x[(rng.next() * x.size).toInt().coerceIn(0, x.size - 1)].copyOf()
            while (centers.size < k) {
                val d = x.map { p -> centers.minOf { c -> squaredDistance(p, c) } }
                val total = d.sum()
                var r = rng.next() * total
                var pick = x.size - 1
                for (i in d.indices) {
                    r -= d[i]
                    if (r <= 0.0) {
                        pick = i
                        break
                    }
                }
                centers += x[pick].copyOf()
            }
            val labels = IntArray(x.size)
            for (round in 0 until MAX_KMEANS_ROUNDS) {
                var changed = false
                for (i in x.indices) {
                    val l = argMin(centers.map { squaredDistance(x[i], it) })
                    if (l != labels[i]) changed = true
                    labels[i] = l
                }
                for (c in centers.indices) {
                    val members = x.indices.filter { labels[it] == c }
                    if (members.isNotEmpty()) {
                        centers[c] = DoubleArray(centers[c].size) { d ->
                            members.sumOf { x[it][d] } / members.size
                        }
                    }
                }
                // 一整轮没有任何点换簇 = 收敛，再迭代也不动（`return@repeat` 是跳过本轮，不是退出）
                if (!changed) break
            }
            val inertia = x.indices.sumOf { squaredDistance(x[it], centers[labels[it]]) }
            if (inertia < best) {
                best = inertia
                bestLabels = labels.toList()
            }
        }
        return bestLabels
    }

    /**
     * 中心余弦相似度超过阈值的两个说话人合并，反复直到没有可合并的。
     *
     * 与桌面 `merge_by_cos` 同一个阈值：谱聚类偶尔会把一个人拆成两簇，这一步把拆开的合回去。
     */
    private fun mergeByCosine(labels: List<Int>, emb: List<FloatArray>, threshold: Float): List<Int> {
        var current = relabel(labels)
        while (true) {
            val k = (current.maxOrNull() ?: 0) + 1
            if (k <= 1) return sortedByFirstSpoken(current)
            val centers = (0 until k).map { c ->
                normalize(mean(emb.indices.filter { current[it] == c }.map { emb[it] }))
            }
            var best = Float.NEGATIVE_INFINITY
            var keep = 0
            var drop = 0
            for (i in 0 until k) {
                for (j in i + 1 until k) {
                    val s = dot(centers[i], centers[j])
                    if (s > best) {
                        best = s
                        keep = i
                        drop = j
                    }
                }
            }
            if (best < threshold) return sortedByFirstSpoken(current)
            current = relabel(current.map { if (it == drop) keep else it })
        }
    }

    /** 编号压缩成 0..k-1（合并之后会留空洞）。 */
    private fun relabel(labels: List<Int>): List<Int> {
        val map = HashMap<Int, Int>()
        return labels.map { map.getOrPut(it) { map.size } }
    }

    /** 按首次出现的顺序重新编号：界面上"说话人1"要对应第一个开口的人。 */
    private fun sortedByFirstSpoken(labels: List<Int>): List<Int> {
        val order = labels.distinct()
        return labels.map { order.indexOf(it) }
    }

    /**
     * 对称矩阵的 Jacobi 特征分解。返回值按特征值升序。
     *
     * @return 特征值与该矩阵的右侧特征向量（`vectors[i][c]` 是第 i 个样本在第 c 个特征向量上的分量）
     */
    private fun jacobiEigen(matrix: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
        val n = matrix.size
        val a = Array(n) { matrix[it].copyOf() }
        val v = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
        for (sweep in 0 until MAX_SWEEPS) {
            var off = 0.0
            for (i in 0 until n) for (j in i + 1 until n) off += a[i][j] * a[i][j]
            // 非对角元都小到可以忽略：已经对角化了，再扫也不会更准（不加这个退出就永远跑满 30 轮）
            if (off < EPSILON) break
            for (p in 0 until n - 1) {
                for (q in p + 1 until n) {
                    if (abs(a[p][q]) < EPSILON) continue
                    val theta = (a[q][q] - a[p][p]) / (2.0 * a[p][q])
                    val t = (if (theta >= 0) 1.0 else -1.0) /
                        (abs(theta) + sqrt(theta * theta + 1.0))
                    val c = 1.0 / sqrt(t * t + 1.0)
                    val s = t * c
                    for (k in 0 until n) {
                        val akp = a[k][p]
                        val akq = a[k][q]
                        a[k][p] = c * akp - s * akq
                        a[k][q] = s * akp + c * akq
                    }
                    for (k in 0 until n) {
                        val apk = a[p][k]
                        val aqk = a[q][k]
                        a[p][k] = c * apk - s * aqk
                        a[q][k] = s * apk + c * aqk
                    }
                    for (k in 0 until n) {
                        val vkp = v[k][p]
                        val vkq = v[k][q]
                        v[k][p] = c * vkp - s * vkq
                        v[k][q] = s * vkp + c * vkq
                    }
                }
            }
        }
        val order = (0 until n).sortedBy { a[it][it] }
        return order.map { a[it][it] }.toDoubleArray() to
            Array(n) { i -> DoubleArray(n) { c -> v[i][order[c]] } }
    }

    private fun squaredDistance(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sum
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (k in a.indices) sum += a[k] * b[k]
        return sum
    }

    private fun mean(vectors: List<FloatArray>): FloatArray {
        if (vectors.isEmpty()) return FloatArray(0)
        val out = FloatArray(vectors.first().size)
        vectors.forEach { v -> v.indices.forEach { out[it] += v[it] } }
        return FloatArray(out.size) { out[it] / vectors.size }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm == 0f) return v
        return FloatArray(v.size) { v[it] / norm }
    }

    private fun argMaxFloat(values: List<Float>): Int = values.indices.maxBy { values[it] }

    private fun argMaxDouble(values: List<Double>): Int = values.indices.maxBy { values[it] }

    private fun argMin(values: List<Double>): Int = values.indices.minBy { values[it] }

    /** 固定种子的 xorshift：同一份声纹每次得到同样的说话人编号。 */
    private class Xorshift(seed: Long) {
        private var state = seed

        fun next(): Double {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            return (state ushr 11).toDouble() / (1L shl 53).toDouble()
        }
    }

    companion object {
        /** 段数少于这个就当一个人（桌面同一条件：少段聚类只会瞎分） */
        const val MIN_SEGMENTS_FOR_CLUSTERING = 20

        /** 桌面 `MAX_SPKS` */
        const val MAX_SPEAKERS = 15

        /** 桌面 `MERGE_THR`：中心余弦相似度超过它就把两个说话人合并 */
        const val MERGE_THRESHOLD = 0.78f

        /** 桌面 `PVAL`：每行保留的邻居比例 */
        const val PRUNE_RATIO = 0.022

        /** 谱聚类最多取多少段（见类注释里的偏差说明） */
        const val SPECTRAL_LIMIT = 256

        private const val SEED = -7046029254386353131L // 0x9E3779B97F4A7C15
        private const val EPSILON = 1e-12
        private const val MAX_SWEEPS = 30
        private const val MAX_KMEANS_ROUNDS = 100
    }
}
