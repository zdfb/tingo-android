package io.github.tancheng33.tingo.ui.settings

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import io.github.tancheng33.tingo.minutes.remote.LlmPresets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.ui.components.TingoConfirmDialog
import io.github.tancheng33.tingo.settings.AppLanguage
import io.github.tancheng33.tingo.settings.LlmConfig
import io.github.tancheng33.tingo.settings.LlmProtocol
import io.github.tancheng33.tingo.ui.components.PillButton
import io.github.tancheng33.tingo.ui.components.PillFilter
import io.github.tancheng33.tingo.ui.components.PillKind
import io.github.tancheng33.tingo.ui.theme.TingoDimens
import io.github.tancheng33.tingo.ui.theme.TingoTheme

/**
 * 设置页（地基版）：界面语言、会议库位置、引擎版本。
 *
 * 模型下载、说话人命名、导出与推送等条目会在各自的功能变更里补上；
 * 这里只放已经真正可用的设置，避免出现点了没反应的开关。
 */
@Composable
fun SettingsScreen(
    language: AppLanguage,
    engineVersion: String,
    dataRoot: String,
    onLanguageChange: (AppLanguage) -> Unit,
    models: SettingsUiState = SettingsUiState(loading = false),
    onDeleteAsrModels: () -> Unit = {},
    onSaveLlmConfig: (LlmConfig) -> Unit = {},
    onFetchModels: (LlmConfig) -> Unit = {},
    onSaveHotwords: (List<String>) -> Unit = {},
    onAddHotword: (String) -> Unit = {},
    onReplayOnboarding: () -> Unit = {},
    onSaveAutoStopSilence: (Int) -> Unit = {},
    /** 清理残留文件（合成后遗留的音轨、part/tmp）；有可清理的内容时才给按钮 */
    onCleanupLeftovers: (() -> Unit)? = null,
    libraryBusy: Boolean = false,
    libraryResult: LibraryOpResult? = null,
    onBackupLibrary: () -> Unit = {},
    onRestoreLibrary: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TingoTheme.colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TingoDimens.gutter, vertical = TingoDimens.gap),
        verticalArrangement = Arrangement.spacedBy(TingoDimens.gap),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleSmall, color = TingoTheme.colors.text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppLanguage.entries.forEach { option ->
                    PillFilter(
                        label = when (option) {
                            AppLanguage.ZH -> stringResource(R.string.settings_language_zh)
                            AppLanguage.EN -> stringResource(R.string.settings_language_en)
                        },
                        selected = option == language,
                        onClick = { onLanguageChange(option) },
                    )
                }
            }
        }

        HorizontalDivider(thickness = TingoDimens.hairline, color = TingoTheme.colors.line)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.settings_data_root), style = MaterialTheme.typography.titleSmall, color = TingoTheme.colors.text)
            Text(dataRoot, style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.settings_data_root_desc),
                style = MaterialTheme.typography.bodySmall,
                color = TingoTheme.colors.muted,
            )
        }

        HorizontalDivider(thickness = TingoDimens.hairline, color = TingoTheme.colors.line)

        models.storage?.let { stats ->
            StorageSection(stats = stats, onCleanupLeftovers = onCleanupLeftovers)
            HorizontalDivider(thickness = TingoDimens.hairline, color = TingoTheme.colors.line)
        }

        LlmServiceSection(
            config = models.llmConfig,
            onSave = onSaveLlmConfig,
            modelList = models.models,
            onFetchModels = onFetchModels,
            english = language == AppLanguage.EN,
        )

        HorizontalDivider(thickness = TingoDimens.hairline, color = TingoTheme.colors.line)

        HotwordsSection(words = models.hotwords, failed = models.hotwordsFailed, onSave = onSaveHotwords)

        // 跨会议术语表：桌面在纪要里给每个术语一个"加进热词"，这里集中成一处，
        // 用户不必翻遍每场会议去找自己该加什么词
        if (models.glossary.isNotEmpty()) {
            GlossarySection(terms = models.glossary, words = models.hotwords, onAdd = onAddHotword)
        }

        HorizontalDivider(thickness = TingoDimens.hairline, color = TingoTheme.colors.line)

        AutoStopSection(minutes = models.autoStopSilenceMin, onSelect = onSaveAutoStopSilence)

        HorizontalDivider(thickness = TingoDimens.hairline, color = TingoTheme.colors.line)

        // 引导走完就不再自动弹；想再看一遍得有个入口（桌面放在"运行环境"里）
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                label = stringResource(R.string.settings_replay_onboarding),
                onClick = onReplayOnboarding,
                kind = PillKind.Ghost,
            )
        }

        HorizontalDivider(thickness = TingoDimens.hairline, color = TingoTheme.colors.line)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_models), style = MaterialTheme.typography.titleSmall, color = TingoTheme.colors.text)
            Text(
                stringResource(R.string.settings_models_desc),
                style = MaterialTheme.typography.bodySmall,
                color = TingoTheme.colors.muted,
            )
            ModelRow(
                label = stringResource(R.string.settings_models_asr),
                group = models.asr,
                onDelete = onDeleteAsrModels,
            )
            models.modelInstall?.let { fraction ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.settings_models_installing),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (models.deleteFailed) {
                Text(
                    stringResource(R.string.settings_models_delete_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = TingoTheme.colors.red,
                )
            }
        }

        HorizontalDivider(thickness = TingoDimens.hairline, color = TingoTheme.colors.line)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_library), style = MaterialTheme.typography.titleSmall, color = TingoTheme.colors.text)
            Text(
                stringResource(R.string.settings_library_desc),
                style = MaterialTheme.typography.bodySmall,
                color = TingoTheme.colors.muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    label = stringResource(R.string.settings_library_backup),
                    onClick = onBackupLibrary,
                    enabled = !libraryBusy,
                )
                PillButton(
                    label = stringResource(R.string.settings_library_restore),
                    onClick = onRestoreLibrary,
                    kind = PillKind.Ghost,
                    enabled = !libraryBusy,
                )
            }
            libraryResult?.let { result ->
                Text(
                    text = when (result) {
                        is LibraryOpResult.BackedUp -> stringResource(R.string.settings_library_backed_up, result.meetings)
                        is LibraryOpResult.Restored -> stringResource(
                            R.string.settings_library_restored,
                            result.restored,
                            result.skipped,
                            result.rejected,
                        )
                        LibraryOpResult.Failed -> stringResource(R.string.settings_library_failed)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result == LibraryOpResult.Failed) TingoTheme.colors.red else TingoTheme.colors.muted,
                )
            }
        }

        HorizontalDivider(thickness = TingoDimens.hairline, color = TingoTheme.colors.line)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleSmall, color = TingoTheme.colors.text)
            Text(
                stringResource(R.string.settings_engine_version, engineVersion),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 一组模型：名称 + 状态与占用 + 删除入口。没下载过就不给删除按钮。 */
@Composable
private fun ModelRow(label: String, group: ModelGroup, onDelete: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }

    if (confirming) {
        TingoConfirmDialog(
            title = stringResource(R.string.settings_models_delete_title),
            body = stringResource(R.string.settings_models_delete_body),
            confirmLabel = stringResource(R.string.settings_models_delete),
            destructive = true,
            onConfirm = {
                confirming = false
                onDelete()
            },
            onDismiss = { confirming = false },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = when {
                    group.isEmpty -> stringResource(R.string.settings_models_empty)
                    group.isReady -> stringResource(R.string.settings_models_ready, group.bytes / MB)
                    else -> stringResource(R.string.settings_models_partial, group.bytes / MB)
                },
                style = MaterialTheme.typography.bodySmall,
                color = TingoTheme.colors.muted,
            )
        }
        if (!group.isEmpty) {
            PillButton(
                label = stringResource(R.string.settings_models_delete),
                onClick = { confirming = true },
                kind = PillKind.Soft,
            )
        }
    }
}

/** 字节 → MB，界面只显示整数兆，够用且不啰嗦。 */
private const val MB = 1_000_000L

/**
 * 模型服务设置：地址、密钥、协议、模型名。
 *
 * 四项全空是默认状态——应用不预置任何服务商。密钥用密码输入框，
 * 避免别人站在旁边就能看全。
 */
@Composable
private fun LlmServiceSection(
    config: LlmConfig,
    onSave: (LlmConfig) -> Unit,
    modelList: ModelListState?,
    onFetchModels: (LlmConfig) -> Unit,
    english: Boolean = false,
) {
    var baseUrl by remember(config) { mutableStateOf(config.baseUrl) }
    var apiKey by remember(config) { mutableStateOf(config.apiKey) }
    var model by remember(config) { mutableStateOf(config.model) }
    var protocol by remember(config) { mutableStateOf(config.protocol) }
    var saved by remember(config) { mutableStateOf(false) }
    val draft = LlmConfig(baseUrl = baseUrl, apiKey = apiKey, model = model, protocol = protocol)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_llm_section), style = MaterialTheme.typography.titleSmall, color = TingoTheme.colors.text)
        Text(
            stringResource(R.string.settings_llm_hint),
            style = MaterialTheme.typography.bodySmall,
            color = TingoTheme.colors.muted,
        )
        // 手填一家服务的三项（地址/协议/模型）最容易错在细节：DeepSeek 没有 /v1、
        // Claude 走的是另一种协议。点一下预设把这些填好，用户只剩粘一个 API Key
        PresetRow(
            current = LlmPresets.match(draft),
            english = english,
            onPick = { id ->
                if (id == LlmPresets.CUSTOM_ID) {
                    // 自定义：清空，免得留在上一家的地址上，看起来像已经填好了
                    baseUrl = ""
                    model = ""
                    apiKey = ""
                    protocol = LlmProtocol.OPENAI
                } else {
                    LlmPresets.find(id)?.let { preset ->
                        // 换服务商连带清密钥：上一家的 key 发到这一家只会 401，
                        // 更糟的是等于把 A 家的密钥送给了 B 家（桌面端同样会清）
                        val filled = preset.applyTo(draft)
                        baseUrl = filled.baseUrl
                        model = filled.model
                        apiKey = filled.apiKey
                        protocol = filled.protocol
                    }
                }
            },
        )
        SettingsField(
            label = stringResource(R.string.settings_llm_base_url),
            value = baseUrl,
            placeholder = stringResource(R.string.settings_llm_base_url_hint),
            onValueChange = { baseUrl = it },
        )
        SettingsField(
            label = stringResource(R.string.settings_llm_api_key),
            value = apiKey,
            placeholder = "",
            secret = true,
            onValueChange = { apiKey = it },
        )
        SettingsField(
            label = stringResource(R.string.settings_llm_model),
            value = model,
            placeholder = stringResource(R.string.settings_llm_model_hint),
            onValueChange = { model = it },
        )
        // 手抄模型名最容易错：能连上就把它有的模型列出来，点一下即填
        ModelListPicker(
            state = modelList,
            // 用表单里正在填的地址与密钥去拉：还没保存、甚至还没有模型名时也要能拉
            onFetch = { onFetchModels(draft) },
            onPick = { model = it },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LlmProtocol.entries.forEach { option ->
                PillFilter(
                    label = when (option) {
                        LlmProtocol.OPENAI -> "OpenAI 兼容"
                        LlmProtocol.ANTHROPIC -> "Anthropic"
                    },
                    selected = option == protocol,
                    onClick = { protocol = option },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton(
                label = stringResource(R.string.action_save),
                onClick = {
                    onSave(draft)
                    saved = true
                },
                enabled = draft.isConfigured,
            )
            if (saved && draft == config) {
                Text(
                    stringResource(R.string.settings_llm_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = TingoTheme.colors.green,
                )
            }
            if (!draft.isConfigured) {
                Text(
                    stringResource(R.string.settings_llm_incomplete),
                    style = MaterialTheme.typography.bodySmall,
                    color = TingoTheme.colors.muted,
                )
            }
        }
    }
}

/** 一个带标签的输入框；[secret] 为真时用密码样式并隐藏已输入内容。 */
@Composable
private fun SettingsField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    secret: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TingoTheme.colors.muted)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = TingoTheme.colors.text),
            cursorBrush = SolidColor(TingoTheme.colors.blue),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(TingoDimens.radiusControl))
                        .background(TingoTheme.colors.seg)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = TingoTheme.colors.faint)
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 会议库占用：总量、音频、可清理的残留，以及占空间最大的几场。 */
@Composable
private fun StorageSection(
    stats: io.github.tancheng33.tingo.engine.StorageStats,
    onCleanupLeftovers: (() -> Unit)?,
) {
    val colors = TingoTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.settings_storage),
            style = MaterialTheme.typography.titleSmall,
            color = colors.text,
        )
        Text(
            stringResource(
                R.string.settings_storage_summary,
                stats.count.toInt(),
                mb(stats.totalBytes),
                mb(stats.audioBytes),
                mb(stats.leftoverBytes),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        stats.largest.take(3).forEach { usage ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    usage.title.ifBlank { usage.id },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text2,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(mb(usage.bytes), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
        }
        if (onCleanupLeftovers != null) {
            PillButton(
                label = stringResource(R.string.settings_storage_cleanup),
                onClick = onCleanupLeftovers,
                kind = PillKind.Soft,
            )
        }
    }
}

/** 字节转 MB：设置页只需要量级，不显示小数。 */
private fun mb(bytes: ULong): String = "${bytes / 1_000_000u} MB"

/** 模型列表一次最多铺几个；其余用户自己填（列表本来只是"抄对名字"的帮助）。 */
private const val MODEL_CHIPS_SHOWN = 20

/** 模型列表：拉取中、失败（带原因）、成功（点一下填进模型名）。 */
@Composable
private fun ModelListPicker(state: ModelListState?, onFetch: () -> Unit, onPick: (String) -> Unit) {
    val colors = TingoTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PillButton(
            label = stringResource(if (state is ModelListState.Loading) R.string.settings_llm_models_loading else R.string.settings_llm_models_fetch),
            onClick = onFetch,
            kind = PillKind.Ghost,
            enabled = state !is ModelListState.Loading,
        )
        when (state) {
            is ModelListState.Failed -> Text(
                text = state.reason,
                style = MaterialTheme.typography.bodySmall,
                color = colors.red,
            )
            is ModelListState.Ready -> FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 只铺前两排：拉回来几十上百个时，全铺出来会把设置页压垮
                state.models.take(MODEL_CHIPS_SHOWN).forEach { name ->
                    PillFilter(label = name, selected = false, onClick = { onPick(name) })
                }
            }
            else -> Unit
        }
    }
}

/**
 * 热词：专有名词、人名、项目代号。
 *
 * 一段文本、一行一个（也接受逗号分隔）——比"加一条删一条"的列表好填，
 * 手机上粘贴一串词进来就能用。保存时由引擎归一化（去空白、去重、限数）。
 */
@Composable
private fun HotwordsSection(words: List<String>, failed: Boolean, onSave: (List<String>) -> Unit) {
    val colors = TingoTheme.colors
    var text by remember(words) { mutableStateOf(words.joinToString("\n")) }
    var saved by remember(words) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.settings_hotwords),
            style = MaterialTheme.typography.titleSmall,
            color = colors.text,
        )
        Text(
            stringResource(R.string.settings_hotwords_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                saved = false
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.text),
            cursorBrush = SolidColor(colors.blue),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp)
                .background(colors.seg, TingoDimens.controlShape)
                .padding(12.dp)
                .semantics { contentDescription = "hotwords" },
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text(
                            stringResource(R.string.settings_hotwords_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.faint,
                        )
                    }
                    inner()
                }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton(
                label = stringResource(R.string.action_save),
                onClick = {
                    onSave(text.split("\n", ",", "，").map { it.trim() }.filter { it.isNotEmpty() })
                    saved = true
                },
                kind = PillKind.Soft,
            )
            // 写盘失败时不能显示"已保存"：那会让用户以为热词进了配置
            if (failed) {
                Text(
                    stringResource(R.string.settings_hotwords_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.red,
                )
            } else if (saved) {
                Text(
                    stringResource(R.string.settings_llm_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.green,
                )
            }
        }
    }
}

/**
 * 静音自动结束录音。
 *
 * 开会开到最后没人说话时还挂着录音，文件变大、转写变慢、播放条后面全是空白。
 * 桌面给的是"连续 N 分钟没人说话就停"（0 = 不自动停），这里给几个常用值而不是自由输入：
 * 手机上点一下比敲数字顺手。
 */
@Composable
private fun AutoStopSection(minutes: Int, onSelect: (Int) -> Unit) {
    val colors = TingoTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.settings_auto_stop),
            style = MaterialTheme.typography.titleSmall,
            color = colors.text,
        )
        Text(
            stringResource(R.string.settings_auto_stop_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 5, 10, 30).forEach { option ->
                PillFilter(
                    label = if (option == 0) {
                        stringResource(R.string.settings_auto_stop_off)
                    } else {
                        stringResource(R.string.settings_auto_stop_minutes, option)
                    },
                    selected = option == minutes,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

/**
 * 服务商预设：横向一排，选中的高亮。
 *
 * 找不出对应预设时选"自定义"——用户自己填的地址不该被硬说成某个服务商。
 * 地址仍然可编辑：预设是填空，不是绑定。
 */
@Composable
private fun PresetRow(current: String, english: Boolean, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 用换行布局而不是横向滚动：滚动会把 Claude / Gemini / 自定义 藏到屏幕外，
        // 用户看不到就不存在。五家一排挤两个，换行后全都露出来
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LlmPresets.all.forEach { preset ->
                PillFilter(
                    label = preset.name(english),
                    selected = preset.id == current,
                    onClick = { onPick(preset.id) },
                )
            }
            PillFilter(
                label = stringResource(R.string.settings_llm_preset_custom),
                selected = current == LlmPresets.CUSTOM_ID,
                onClick = { onPick(LlmPresets.CUSTOM_ID) },
            )
        }
        Text(
            stringResource(R.string.settings_llm_preset_hint),
            style = MaterialTheme.typography.bodySmall,
            color = TingoTheme.colors.muted,
        )
    }
}

/**
 * 术语表：各场纪要里记下的人名、项目代号、听错写法。
 *
 * 只读展示 + 点一下加进热词。桌面端没有独立的术语表页面（术语挂在纪要里），
 * 手机上集中一处更好找；加进热词后两边行为一致：写纪要时按这个写法纠正同音字。
 */
@Composable
private fun GlossarySection(
    terms: List<io.github.tancheng33.tingo.engine.GlossaryTerm>,
    words: List<String>,
    onAdd: (String) -> Unit,
) {
    val colors = TingoTheme.colors
    val added = remember(words) { words.map { it.lowercase() }.toSet() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.settings_glossary, terms.size),
            style = MaterialTheme.typography.titleSmall,
            color = colors.text,
        )
        Text(
            stringResource(R.string.settings_glossary_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            terms.forEach { term ->
                // 已经在热词里的就标成已加：避免用户反复点同一个词
                val already = term.term.lowercase() in added
                PillFilter(
                    label = if (already) {
                        stringResource(R.string.settings_glossary_added, term.term)
                    } else {
                        term.term
                    },
                    selected = already,
                    onClick = { if (!already) onAdd(term.term) },
                )
            }
        }
    }
}
