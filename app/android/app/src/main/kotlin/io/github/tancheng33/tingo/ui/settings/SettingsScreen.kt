package io.github.tancheng33.tingo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.settings.AppLanguage

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
    onDeleteLlmModel: () -> Unit = {},
    libraryBusy: Boolean = false,
    libraryResult: LibraryOpResult? = null,
    onBackupLibrary: () -> Unit = {},
    onRestoreLibrary: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppLanguage.entries.forEach { option ->
                    FilterChip(
                        selected = option == language,
                        onClick = { onLanguageChange(option) },
                        label = {
                            Text(
                                when (option) {
                                    AppLanguage.ZH -> stringResource(R.string.settings_language_zh)
                                    AppLanguage.EN -> stringResource(R.string.settings_language_en)
                                },
                            )
                        },
                    )
                }
            }
        }

        HorizontalDivider()

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.settings_data_root), style = MaterialTheme.typography.titleMedium)
            Text(dataRoot, style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.settings_data_root_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_models), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_models_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelRow(
                label = stringResource(R.string.settings_models_asr),
                group = models.asr,
                onDelete = onDeleteAsrModels,
            )
            ModelRow(
                label = stringResource(R.string.settings_models_llm),
                group = models.llm,
                onDelete = onDeleteLlmModel,
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
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        HorizontalDivider()

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_library), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_library_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBackupLibrary, enabled = !libraryBusy) {
                    Text(stringResource(R.string.settings_library_backup))
                }
                OutlinedButton(onClick = onRestoreLibrary, enabled = !libraryBusy) {
                    Text(stringResource(R.string.settings_library_restore))
                }
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
                    color = if (result == LibraryOpResult.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        HorizontalDivider()

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!group.isEmpty) {
            TextButton(onClick = { confirming = true }) {
                Text(stringResource(R.string.settings_models_delete))
            }
        }
    }
}

/** 字节 → MB，界面只显示整数兆，够用且不啰嗦。 */
private const val MB = 1_000_000L
