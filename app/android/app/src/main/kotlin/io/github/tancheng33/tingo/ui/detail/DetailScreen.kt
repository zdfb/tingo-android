package io.github.tancheng33.tingo.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.engine.FailureKind
import io.github.tancheng33.tingo.engine.Fishbone
import io.github.tancheng33.tingo.engine.MinutesDoc
import io.github.tancheng33.tingo.engine.SectionKind
import io.github.tancheng33.tingo.engine.Turn
import io.github.tancheng33.tingo.ui.clockOf
import io.github.tancheng33.tingo.ui.components.TaskStatus
import io.github.tancheng33.tingo.ui.components.TaskStatusBar

/**
 * 会议详情：纪要 / 转写两栏。
 *
 * 说话人别名与时间戳都由引擎解析好，这里只做展示。点击某句会回调起始毫秒，
 * 播放器在录音相关的变更里接入。
 */
@Composable
fun DetailScreen(
    state: DetailUiState,
    onRetry: () -> Unit,
    onSentenceClick: (ULong) -> Unit,
    modifier: Modifier = Modifier,
    initialTab: Int = 0,
    /** 能力矩阵里没有纪要解析时（例如换了个精简引擎），不展示纪要栏 */
    showMinutesTab: Boolean = true,
    transcription: TranscriptionUiState = TranscriptionUiState.Idle,
    onTranscribe: () -> Unit = {},
    onCancelTranscribe: () -> Unit = {},
    minutesGeneration: MinutesUiState = MinutesUiState.Idle,
    onGenerateMinutes: () -> Unit = {},
    onCancelMinutes: () -> Unit = {},
    onExport: () -> Unit = {},
    /** 导出归档 zip（录音 + transcript.json + 纪要） */
    onExportBundle: (() -> Unit)? = null,
    exportEnabled: Boolean = false,
) {
    when {
        state.loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        state.failure != null -> Column(
            modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.error_title), style = MaterialTheme.typography.titleMedium)
            Text(failureText(state.failure), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_refresh)) }
        }

        else -> {
            val detail = state.detail
            val tabs = if (showMinutesTab) 2 else 1
            var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, tabs - 1)) }
            Column(modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = detail?.summary?.title.orEmpty(),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    // 有内容才可分享：空会议导出会得到一个空文件，不如不给入口
                    if (onExportBundle != null) {
                        IconButton(onClick = onExportBundle, enabled = exportEnabled) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Share,
                                contentDescription = stringResource(R.string.detail_export_bundle),
                            )
                        }
                    }
                    IconButton(onClick = onExport, enabled = exportEnabled) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(R.string.detail_export),
                        )
                    }
                }
                TabRow(selectedTabIndex = tab) {
                    if (showMinutesTab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.detail_tab_minutes)) })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.detail_tab_transcript)) })
                    } else {
                        Tab(selected = true, onClick = {}, text = { Text(stringResource(R.string.detail_tab_transcript)) })
                    }
                }
                if (showMinutesTab && tab == 0) {
                    MinutesPane(
                        minutes = state.minutes,
                        minutesFailure = state.minutesFailure,
                        fishbone = detail?.fishbone,
                        generation = minutesGeneration,
                        onGenerate = onGenerateMinutes,
                        onCancel = onCancelMinutes,
                    )
                } else {
                    TranscriptPane(
                        turns = detail?.transcript?.turns.orEmpty(),
                        onSentenceClick = onSentenceClick,
                        transcription = transcription,
                        onTranscribe = onTranscribe,
                        onCancelTranscribe = onCancelTranscribe,
                    )
                }
            }
        }
    }
}

@Composable
private fun MinutesPane(
    minutes: MinutesDoc?,
    minutesFailure: FailureKind?,
    fishbone: Fishbone?,
    generation: MinutesUiState,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
) {
    MinutesGenerationBar(state = generation, onGenerate = onGenerate, onCancel = onCancel)
    when {
        minutesFailure != null -> Box(Modifier.fillMaxSize().padding(24.dp)) {
            Text(stringResource(R.string.detail_minutes_parse_failed, failureText(minutesFailure)))
        }

        minutes == null -> {
            if (generation is MinutesUiState.Generating) return
            Box(Modifier.fillMaxSize().padding(24.dp)) {
                Text(stringResource(R.string.detail_no_minutes))
            }
        }

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(minutes.sections) { section ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(section.heading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (section.kind == SectionKind.ROOT_CAUSE && fishbone != null) {
                        FishboneCard(fishbone)
                    } else {
                        // 有 ```fishbone 代码块但没解析出图，就照原文展示并标注，别让用户以为内容丢了
                        if (section.body.contains(FISHBONE_FENCE)) {
                            Text(
                                stringResource(R.string.detail_fishbone_invalid),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Text(section.body.trim(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** 鱼骨图：分支 + 每个分支引用的原话时间戳；没有可解析的图时退回原文。 */
@Composable
private fun FishboneCard(fishbone: Fishbone) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(fishbone.problem, style = MaterialTheme.typography.titleSmall)
        fishbone.branches.forEach { branch ->
            Column(Modifier.fillMaxWidth()) {
                Text(branch.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (branch.detail.isNotBlank()) {
                    Text(branch.detail, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    branch.refs.forEach { ref ->
                        AssistChip(onClick = {}, label = { Text(ref) })
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

private const val FISHBONE_FENCE = "```fishbone"

/** 本地化模板 + 动态部分；没有动态部分时直接取模板，避免留下空的"："。 */
@Composable
private fun failureMessage(reasonRes: Int, detail: String?): String =
    if (detail.isNullOrBlank()) stringResource(reasonRes) else stringResource(reasonRes, detail)

/** 转写状态条：缺模型、下载中、识别中、失败各自给出可操作的下一步。 */
@Composable
private fun TranscriptionStatus(
    state: TranscriptionUiState,
    hasTranscript: Boolean,
    onTranscribe: () -> Unit,
    onCancel: () -> Unit,
) {
    TaskStatusBar(
        status = state.toTaskStatus(),
        actionLabel = stringResource(R.string.transcribe_start),
        onAction = onTranscribe,
        onCancel = onCancel,
        idleAction = !hasTranscript,
    )
}

/** 纪要生成状态条：与转写同一种形状，只是文案与按钮不同。 */
@Composable
private fun MinutesGenerationBar(state: MinutesUiState, onGenerate: () -> Unit, onCancel: () -> Unit) {
    TaskStatusBar(
        status = state.toTaskStatus(),
        actionLabel = stringResource(R.string.minutes_generate),
        onAction = onGenerate,
        onCancel = onCancel,
    )
}

@Composable
private fun TranscriptionUiState.toTaskStatus(): TaskStatus = when (this) {
    TranscriptionUiState.Idle -> TaskStatus.Idle
    is TranscriptionUiState.NeedModels -> TaskStatus.NeedModel(missingMb = missingBytes / 1_000_000)
    is TranscriptionUiState.Downloading -> TaskStatus.Running(fraction, R.string.transcribe_downloading)
    is TranscriptionUiState.Running -> TaskStatus.Running(fraction, R.string.transcribe_running)
    is TranscriptionUiState.Done -> TaskStatus.Done
    is TranscriptionUiState.Failed -> TaskStatus.Failed(failureMessage(reasonRes, detail))
}

@Composable
private fun MinutesUiState.toTaskStatus(): TaskStatus = when (this) {
    MinutesUiState.Idle -> TaskStatus.Idle
    is MinutesUiState.NeedModel -> TaskStatus.NeedModel(missingMb = missingBytes / 1_000_000)
    MinutesUiState.Generating -> TaskStatus.Running(null, R.string.minutes_generating)
    is MinutesUiState.Done -> TaskStatus.Done
    is MinutesUiState.Failed -> TaskStatus.Failed(
        message = failureMessage(reasonRes, detail),
        hint = draft?.let { stringResource(R.string.minutes_draft_saved) },
    )
}

@Composable
private fun TranscriptPane(
    turns: List<Turn>,
    onSentenceClick: (ULong) -> Unit,
    transcription: TranscriptionUiState,
    onTranscribe: () -> Unit,
    onCancelTranscribe: () -> Unit,
) {
    TranscriptionStatus(
        state = transcription,
        hasTranscript = turns.isNotEmpty(),
        onTranscribe = onTranscribe,
        onCancel = onCancelTranscribe,
    )
    if (turns.isEmpty()) {
        if (transcription is TranscriptionUiState.Running || transcription is TranscriptionUiState.Downloading) return
        Box(Modifier.fillMaxSize().padding(24.dp)) { Text(stringResource(R.string.detail_no_transcript)) }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(turns) { turn ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(turn.speakerName, style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { onSentenceClick(turn.startMs) }) {
                        Text(clockOf(turn.startMs), style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(turn.text, style = MaterialTheme.typography.bodyMedium)
                // 转写按说话人合并成一段，但每句自己的时间戳保留下来：定位要靠它
                if (turn.sentences.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        turn.sentences.forEach { sentence ->
                            TextButton(
                                onClick = { onSentenceClick(sentence.startMs) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            ) {
                                Text(clockOf(sentence.startMs), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun failureText(kind: FailureKind): String = when (kind) {
    FailureKind.INVALID_INPUT -> stringResource(R.string.error_invalid_input)
    FailureKind.MISSING -> stringResource(R.string.error_missing)
    FailureKind.MALFORMED -> stringResource(R.string.error_malformed)
    FailureKind.PERMISSION_DENIED -> stringResource(R.string.error_permission_denied)
    FailureKind.INTERNAL -> stringResource(R.string.error_internal)
}
