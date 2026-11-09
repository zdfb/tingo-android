package io.github.tancheng33.tingo.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
    /** 清空回收站；null 表示当前没有可清理的内容 */
    onPurgeTrash: (() -> Unit)? = null,
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
            StorageSection(stats = stats, onPurgeTrash = onPurgeTrash)
            HorizontalDivider(thickness = TingoDimens.hairline, color = TingoTheme.colors.line)
        }

        LlmServiceSection(config = models.llmConfig, onSave = onSaveLlmConfig)

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
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.settings_models_delete_title)) },
            text = { Text(stringResource(R.string.settings_models_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onDelete()
                    },
                ) { Text(stringResource(R.string.settings_models_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel)) }
            },
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
private fun LlmServiceSection(config: LlmConfig, onSave: (LlmConfig) -> Unit) {
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
private fun StorageSection(stats: io.github.tancheng33.tingo.engine.StorageStats, onPurgeTrash: (() -> Unit)?) {
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
        if (onPurgeTrash != null) {
            PillButton(
                label = stringResource(R.string.settings_storage_purge),
                onClick = onPurgeTrash,
                kind = PillKind.Soft,
            )
        }
    }
}

/** 字节转 MB：设置页只需要量级，不显示小数。 */
private fun mb(bytes: ULong): String = "${bytes / 1_000_000u} MB"
