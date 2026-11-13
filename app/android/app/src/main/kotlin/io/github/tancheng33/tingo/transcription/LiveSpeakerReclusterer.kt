package io.github.tancheng33.tingo.transcription

data class LiveSpeakerUtterance(
    val key: Long,
    val text: String,
    val embedding: FloatArray,
    val speaker: Int? = null,
)

/** 录音过程中的全场临时说话人重聚类；正式 transcript.json 仍由最终流水线决定。 */
class LiveSpeakerReclusterer(
    private val reclusterEvery: Int = DEFAULT_RECLUSTER_EVERY,
    private val clusterer: (List<FloatArray>) -> List<Int> = { SpeakerClustering().cluster(it) },
) {
    init { require(reclusterEvery > 0) { "reclusterEvery 必须大于 0" } }
    private val items = mutableListOf<LiveSpeakerUtterance>()
    fun add(key: Long, text: String, embedding: FloatArray): List<LiveSpeakerUtterance> {
        val usable = embedding.isUsable()
        items += LiveSpeakerUtterance(key, text, if (usable) embedding.copyOf() else FloatArray(0))
        val validCount = items.count { it.embedding.isNotEmpty() }
        if (validCount == 1 || validCount % reclusterEvery == 0) recluster()
        return snapshot()
    }

    fun snapshot(): List<LiveSpeakerUtterance> = items.map { it.copy(embedding = it.embedding.copyOf()) }

    fun clear() {
        items.clear()
    }

    private fun recluster() {
        val valid = items.filter { it.embedding.isNotEmpty() }
        if (valid.isEmpty()) return
        val dimensions = valid.first().embedding.size
        val compatible = valid.filter { it.embedding.size == dimensions }
        if (compatible.isEmpty()) return
        val labels = clusterer(compatible.map { it.embedding })
        var labelIndex = 0
        items.indices.forEach { index ->
            if (items[index].embedding.isNotEmpty() && items[index].embedding.size == dimensions) {
                // UI 文案使用“临时说话人 1”；聚类内部仍从 0 开始。
                items[index] = items[index].copy(speaker = labels.getOrNull(labelIndex)?.plus(1))
                labelIndex++
            }
        }
    }

    companion object { const val DEFAULT_RECLUSTER_EVERY = 4 }
}

private fun FloatArray.isUsable(): Boolean {
    if (isEmpty() || any { !it.isFinite() }) return false
    return sumOf { it.toDouble() * it.toDouble() } > 1e-12
}
