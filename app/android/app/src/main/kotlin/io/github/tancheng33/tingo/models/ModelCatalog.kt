package io.github.tancheng33.tingo.models

import org.json.JSONObject

/**
 * 模型清单里的一条。
 *
 * @param sha256 有值时必须校验；留空表示"这一版还没算摘要"，此时只校验体积。
 */
data class ModelFile(
    val id: String,
    val fileName: String,
    val url: String,
    val bytes: Long,
    val sha256: String? = null,
)

/**
 * 随 APK 打包的模型清单。
 *
 * 模型不进 APK（三百多兆），进 APK 的只有这份清单：谁、多大、从哪下、摘要是什么。
 * 清单放在 assets 里，改模型不需要改代码。
 */
class ModelCatalog(val files: List<ModelFile>) {

    fun byId(id: String): ModelFile? = files.firstOrNull { it.id == id }

    val totalBytes: Long
        get() = files.sumOf { it.bytes }

    companion object {
        /** 解析 `assets/models.json`；字段缺失或类型不对就报错，不做"猜"。 */
        fun parse(json: String): ModelCatalog {
            val root = JSONObject(json)
            val array = root.getJSONArray("files")
            val files = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                ModelFile(
                    id = o.getString("id"),
                    fileName = o.getString("file_name"),
                    url = o.getString("url"),
                    bytes = o.getLong("bytes"),
                    sha256 = o.optString("sha256").takeIf { it.isNotBlank() },
                )
            }
            return ModelCatalog(files)
        }
    }
}
