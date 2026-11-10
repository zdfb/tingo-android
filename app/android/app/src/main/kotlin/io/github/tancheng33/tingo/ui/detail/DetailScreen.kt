package io.github.tancheng33.tingo.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.tancheng33.tingo.ui.theme.TingoDimens
import io.github.tancheng33.tingo.ui.components.PillTabs
import io.github.tancheng33.tingo.ui.theme.TingoTheme
import androidx.compose.foundation.layout.heightIn
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
import io.github.tancheng33.tingo.transcription.Transcript
import io.github.tancheng33.tingo.audio.PlaybackState
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.github.tancheng33.tingo.ui.components.PillButton
import io.github.tancheng33.tingo.ui.components.PillKind
import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.ui.components.TextInputDialog
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
    /** "还没配模型服务"时按钮跳设置页 */
    onOpenSettings: () -> Unit = {},
    onRename: (String) -> Unit = {},
    onSetTags: (List<String>) -> Unit = {},
    /** 从搜索命中进来时，打开就跳到这句话 */
    initialSeekMs: Long? = null,
    onSaveMinutes: (String) -> Unit = {},
    onExportSrt: () -> Unit = {},
    onExportDocx: () -> Unit = {},
    onExportPdf: () -> Unit = {},
    onExportExcalidraw: () -> Unit = {},
    history: List<io.github.tancheng33.tingo.engine.MinutesVersionInfo> = emptyList(),
    /** 正在查看的历史版本全文；非空时盖一层只读面板 */
    preview: VersionPreview? = null,
    onClosePreview: () -> Unit = {},
    onOpenVersion: (String) -> Unit = {},
    onRestoreVersion: (String) -> Unit = {},
    onDeleteVersion: (String) -> Unit = {},
    onRenameSpeaker: (Int, String) -> Unit = { _, _ -> },
    onGuessSpeakers: () -> Unit = {},
    onApplyGuess: (Int) -> Unit = {},
    speakers: List<io.github.tancheng33.tingo.engine.SpeakerInfo> = emptyList(),
    guessing: Boolean = false,
    guessFailure: GuessFailure? = null,
    guessNote: GuessNote? = null,
    onExport: () -> Unit = {},
    /** 导出归档 zip（录音 + transcript.json + 纪要） */
    onExportBundle: (() -> Unit)? = null,
    exportEnabled: Boolean = false,
    playback: PlaybackState = PlaybackState(),
    onTogglePlay: () -> Unit = {},
    /** 跳到指定毫秒并开始播放（搜索命中跳转用） */
    onPlayFrom: (Long) -> Unit = {},
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
            // 只跳一次：用户随后自己拖动进度条不该被"初始位置"拽回去
            androidx.compose.runtime.LaunchedEffect(initialSeekMs) {
                if (initialSeekMs != null) onPlayFrom(initialSeekMs)
            }
            var renameOpen by remember { mutableStateOf(false) }
            var tagsOpen by remember { mutableStateOf(false) }
            var menuOpen by remember { mutableStateOf(false) }
            var editOpen by remember { mutableStateOf(false) }
            var historyOpen by remember { mutableStateOf(false) }
            Column(modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = detail?.summary?.title.orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = TingoTheme.colors.text,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
                    // 改名与标签收进一个溢出菜单：标题行已经有两个分享入口，再塞两个图标太挤
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.detail_more_actions),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.detail_rename), color = TingoTheme.colors.text) },
                                onClick = {
                                    menuOpen = false
                                    renameOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.detail_tags), color = TingoTheme.colors.text) },
                                onClick = {
                                    menuOpen = false
                                    tagsOpen = true
                                },
                            )
                            if (detail?.minutesText != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.detail_edit_minutes), color = TingoTheme.colors.text) },
                                    onClick = {
                                        menuOpen = false
                                        editOpen = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.detail_history, history.size),
                                            color = TingoTheme.colors.text,
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        historyOpen = true
                                    },
                                )
                            }
                            // Word 与 PDF 是同一条触发条件：有正文才给（空白纪要导出只会得到空文件）
                            if (!detail?.minutesText.isNullOrBlank()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.detail_export_docx), color = TingoTheme.colors.text) },
                                    onClick = {
                                        menuOpen = false
                                        onExportDocx()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.detail_export_pdf), color = TingoTheme.colors.text) },
                                    onClick = {
                                        menuOpen = false
                                        onExportPdf()
                                    },
                                )
                            }
                            if (detail?.transcript != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.detail_export_srt), color = TingoTheme.colors.text) },
                                    onClick = {
                                        menuOpen = false
                                        onExportSrt()
                                    },
                                )
                            }
                            if (detail?.fishbone != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.detail_export_excalidraw), color = TingoTheme.colors.text) },
                                    onClick = {
                                        menuOpen = false
                                        onExportExcalidraw()
                                    },
                                )
                            }
                        }
                    }
                }
                if (editOpen && detail?.minutesText != null) {
                    MinutesEditor(
                        initial = detail.minutesText.orEmpty(),
                        onDismiss = { editOpen = false },
                        onSave = { text ->
                            editOpen = false
                            onSaveMinutes(text)
                        },
                    )
                }
                if (preview != null) {
                    VersionPreviewDialog(
                        preview = preview,
                        onClose = onClosePreview,
                        onRestore = { onRestoreVersion(preview.id) },
                    )
                }
                if (historyOpen) {
                    HistorySheet(
                        versions = history,
                        onOpen = onOpenVersion,
                        onRestore = onRestoreVersion,
                        onDelete = onDeleteVersion,
                        onDismiss = { historyOpen = false },
                    )
                }
                if (renameOpen) {
                    TextInputDialog(
                        title = stringResource(R.string.detail_rename),
                        requireNonBlank = true,
                        initial = detail?.summary?.title.orEmpty(),
                        hint = stringResource(R.string.detail_rename_hint),
                        onDismiss = { renameOpen = false },
                        onConfirm = { title ->
                            renameOpen = false
                            onRename(title)
                        },
                    )
                }
                if (tagsOpen) {
                    TextInputDialog(
                        title = stringResource(R.string.detail_tags),
                        initial = detail?.summary?.tags.orEmpty().joinToString(","),
                        hint = stringResource(R.string.detail_tags_hint),
                        onDismiss = { tagsOpen = false },
                        onConfirm = { text ->
                            tagsOpen = false
                            onSetTags(text.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() })
                        },
                    )
                }
                if (speakers.isNotEmpty()) {
                    SpeakerBar(
                        speakers = speakers,
                        guessing = guessing,
                        failure = guessFailure,
                        note = guessNote,
                        onRename = onRenameSpeaker,
                        onGuess = onGuessSpeakers,
                        onApplyGuess = onApplyGuess,
                    )
                }
                if (playback.available) {
                    PlaybackBar(playback = playback, onTogglePlay = onTogglePlay)
                }
                // 分段控件（桌面版同样的"灰底 + 白色浮起项"），比 M3 的 TabRow 更接近桌面观感
                if (showMinutesTab) {
                    PillTabs(
                        tabs = listOf(
                            stringResource(R.string.detail_tab_minutes),
                            stringResource(R.string.detail_tab_transcript),
                        ),
                        selected = tab,
                        onSelect = { tab = it },
                        modifier = Modifier.padding(horizontal = TingoDimens.gutter),
                    )
                } else {
                    PillTabs(
                        tabs = listOf(stringResource(R.string.detail_tab_transcript)),
                        selected = 0,
                        onSelect = {},
                        modifier = Modifier.padding(horizontal = TingoDimens.gutter),
                    )
                }
                if (showMinutesTab && tab == 0) {
                    MinutesPane(
                        minutes = state.minutes,
                        minutesFailure = state.minutesFailure,
                        fishbone = detail?.fishbone,
                        generation = minutesGeneration,
                        onGenerate = onGenerateMinutes,
                        onCancel = onCancelMinutes,
                        onOpenSettings = onOpenSettings,
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
    onOpenSettings: () -> Unit,
) {
    MinutesGenerationBar(
        state = generation,
        onGenerate = onGenerate,
        onCancel = onCancel,
        onOpenSettings = onOpenSettings,
        // 有转写、还没有纪要时才给"生成"按钮：没转写点了也没用，已有纪要时再给一个等于噪音
        hasTranscript = minutes == null && minutesFailure == null && generation !is MinutesUiState.NotConfigured,
    )
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

/**
 * 播放条：只有一个按钮和一条进度。
 *
 * 会议录音的用法是"点某句话回听那一段"，所以这里不做完整播放器（快进、倍速、列表），
 * 只给一个播放/暂停和一条能看出进度的线。
 */
@Composable
private fun PlaybackBar(playback: PlaybackState, onTogglePlay: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 用文字按钮而不是图标：播放/暂停的图标在核心图标集里不全，文字也更明确
        TextButton(onClick = onTogglePlay) {
            Text(stringResource(if (playback.playing) R.string.player_pause else R.string.player_play))
        }
        LinearProgressIndicator(
            progress = { playback.progress },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${Transcript.mmss(playback.positionMs)} / ${Transcript.mmss(playback.durationMs)}",
            style = MaterialTheme.typography.bodySmall,
        )
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
private fun MinutesGenerationBar(
    state: MinutesUiState,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    hasTranscript: Boolean,
) {
    TaskStatusBar(
        status = state.toTaskStatus(),
        actionLabel = stringResource(R.string.minutes_generate),
        // 未配置时按钮给的是"去设置"：重试同一个请求不会有结果
        onAction = if (state is MinutesUiState.NotConfigured) onOpenSettings else onGenerate,
        onCancel = onCancel,
        idleAction = hasTranscript,
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
    MinutesUiState.NotConfigured -> TaskStatus.Blocked(
        message = stringResource(R.string.err_llm_not_configured),
        actionLabelRes = R.string.detail_minutes_go_settings,
    )
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

/** 一个带初始值的输入对话框：改名与标签共用。 */
/**
 * 说话人一排：每人一个可点的 chip（点开改名），右边是"让 AI 猜名字"。
 *
 * 名字是本机会议数据的一部分，改完立刻写回 transcript；AI 建议只作提示，
 * 不自动应用——猜错了会把转写稿改花，用户点一下才采纳。
 */
@Composable
private fun SpeakerBar(
    speakers: List<io.github.tancheng33.tingo.engine.SpeakerInfo>,
    guessing: Boolean,
    failure: GuessFailure?,
    note: GuessNote?,
    onRename: (Int, String) -> Unit,
    onGuess: () -> Unit,
    onApplyGuess: (Int) -> Unit,
) {
    val colors = TingoTheme.colors
    var editing by remember { mutableStateOf<Int?>(null) }
    val editingSpeaker = editing?.let { index -> speakers.firstOrNull { it.index == index.toUInt() } }

    if (editingSpeaker != null) {
        TextInputDialog(
            title = stringResource(R.string.detail_speaker_rename),
            requireNonBlank = true,
            initial = editingSpeaker.name,
            hint = stringResource(R.string.detail_speaker_rename_hint),
            onDismiss = { editing = null },
            onConfirm = { name ->
                editing = null
                onRename(editingSpeaker.index.toInt(), name)
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            speakers.forEach { speaker ->
                Row(
                    modifier = Modifier
                        .background(colors.seg, TingoDimens.cardShape)
                        .clickable { editing = speaker.index.toInt() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(speaker.name, style = MaterialTheme.typography.labelMedium, color = colors.text)
                    if (speaker.suggested.isNotBlank()) {
                        // 建议只作提示：用户点它才写进转写
                        Text(
                            stringResource(R.string.detail_speaker_apply_guess, speaker.suggested),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.blue,
                            // 用 role 而不是覆盖 contentDescription：读屏要能念出"猜是「林舟」"
                            modifier = Modifier
                                .semantics { role = androidx.compose.ui.semantics.Role.Button }
                                .clickable { onApplyGuess(speaker.index.toInt()) },
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                label = stringResource(if (guessing) R.string.detail_speaker_guessing else R.string.detail_speaker_guess),
                onClick = onGuess,
                kind = PillKind.Ghost,
                enabled = !guessing,
            )
        }
        failure?.let {
            Text(
                text = failureMessage(it.reasonRes, it.detail),
                style = MaterialTheme.typography.bodySmall,
                color = colors.red,
            )
        }
        if (note == GuessNote.NoClue) {
            Text(
                stringResource(R.string.detail_speaker_no_clue),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
        // 证据：AI 猜名字的依据（桌面端也把原话摆出来），让用户判断该不该采纳
        speakers.firstOrNull { it.evidence.isNotBlank() }?.let { speaker ->
            Text(
                text = speaker.evidence,
                style = MaterialTheme.typography.labelSmall,
                color = colors.faint,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 纪要多行编辑器。
 *
 * 桌面端有"编辑纪要"（改完保存，旧版自动进历史），手机上同样需要：
 * 模型写完总要人过一遍，直接在手机上改比导出再改回更实际。
 */
@Composable
private fun MinutesEditor(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val colors = TingoTheme.colors
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text(stringResource(R.string.detail_edit_minutes), color = colors.text) },
        text = {
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.blue),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 360.dp)
                    .background(colors.seg, TingoDimens.controlShape)
                    .padding(12.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .semantics { contentDescription = "minutes-editor" },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) {
                Text(stringResource(R.string.action_save), color = colors.blue)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = colors.muted) }
        },
    )
}

/** 纪要历史：预览、恢复、删除。恢复本身也可退回，所以不弹二次确认。 */
@Composable
private fun HistorySheet(
    versions: List<io.github.tancheng33.tingo.engine.MinutesVersionInfo>,
    onOpen: (String) -> Unit,
    onRestore: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = TingoTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text(stringResource(R.string.detail_history, versions.size), color = colors.text) },
        text = {
            if (versions.isEmpty()) {
                Text(stringResource(R.string.detail_history_empty), color = colors.muted)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(versions, key = { it.id }) { version ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val savedAt = remember(version.id) { formatSavedAt(version.savedAt) }
                            Text(
                                text = savedAt,
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.text,
                            )
                            Text(
                                text = version.preview.replace('\n', ' '),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onOpen(version.id) }) {
                                    Text(stringResource(R.string.detail_history_open), color = colors.blue)
                                }
                                TextButton(onClick = { onRestore(version.id) }) {
                                    Text(stringResource(R.string.detail_history_restore), color = colors.blue)
                                }
                                // 删除后无法恢复：第一次点变成"再点一次"，与桌面一致
                                var confirming by remember(version.id) { mutableStateOf(false) }
                                TextButton(
                                    onClick = {
                                        if (confirming) onDelete(version.id) else confirming = true
                                    },
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (confirming) R.string.detail_history_delete_confirm else R.string.library_delete,
                                        ),
                                        color = colors.red,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close), color = colors.blue) }
        },
    )
}

/** 历史条目的时间：本地时区的"月-日 时:分"，手机上看年代没意义。 */
private fun formatSavedAt(millis: ULong): String {
    val zone = java.util.TimeZone.getDefault()
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    fmt.timeZone = zone
    return fmt.format(java.util.Date(millis.toLong()))
}

/** 历史版本的只读预览：能看全文，也能直接恢复成当前版本。 */
@Composable
private fun VersionPreviewDialog(
    preview: VersionPreview,
    onClose: () -> Unit,
    onRestore: () -> Unit,
) {
    val colors = TingoTheme.colors
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = colors.card,
        title = { Text(stringResource(R.string.detail_history_preview_title), color = colors.text) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = preview.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onRestore) {
                Text(stringResource(R.string.detail_history_restore), color = colors.blue)
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_close), color = colors.muted) }
        },
    )
}
