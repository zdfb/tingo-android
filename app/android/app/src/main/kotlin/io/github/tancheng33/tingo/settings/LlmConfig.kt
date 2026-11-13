package io.github.tancheng33.tingo.settings

import java.net.URI
import java.net.InetAddress

fun publicHttpsAddress(value: String): Boolean = runCatching {
    val uri = URI(value.trim())
    val host = uri.host?.lowercase() ?: return false
    if (!uri.scheme.equals("https", true) || uri.userInfo != null || uri.fragment != null ||
        host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
        (!host.contains('.') && !host.contains(':'))) return false
    if (host.contains(':') || host.all { it.isDigit() || it == '.' }) {
        return isPublicAddress(InetAddress.getByName(host))
    }
    true
}.getOrDefault(false)

fun isPublicAddress(address: InetAddress): Boolean {
    val bytes = address.address
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return false
    if (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) return false
    if (bytes.size == 4) {
        val a = bytes[0].toInt() and 255
        val b = bytes[1].toInt() and 255
        if (a == 0 || a >= 224 || (a == 100 && b in 64..127) || (a == 198 && b in 18..19)) return false
    }
    return true
}

/** 接口协议，取值与桌面版 `config.toml` 的 `[llm] api` 一致。 */
enum class LlmProtocol(val wire: String) {
    OPENAI("openai"),
    ANTHROPIC("anthropic"),
    ;

    companion object {
        fun fromWire(value: String?): LlmProtocol =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) } ?: OPENAI
    }
}

/**
 * 纪要用的模型服务配置，与桌面版 `[llm]` 段同一套语义。
 *
 * 默认全空：不预置任何服务商，避免把某家的地址和模型名硬编码进产品。
 */
data class LlmConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val protocol: LlmProtocol = LlmProtocol.OPENAI,
) {
    /**
     * 三项都填了才算配好。
     *
     * 只有地址没有模型时也算"没配"——半配置跑起来只会得到一个 400，
     * 不如在点按钮之前就说清楚。
     */
    val isConfigured: Boolean
        get() = publicHttpsAddress(baseUrl) && apiKey.isNotBlank() && model.isNotBlank()
}
