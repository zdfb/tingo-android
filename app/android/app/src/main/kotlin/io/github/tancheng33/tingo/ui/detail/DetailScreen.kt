package io.github.tancheng33.tingo.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.tancheng33.tingo.ui.theme.TingoDimens
import io.github.tancheng33.tingo.ui.components.PillTabs
import io.github.tancheng33.tingo.ui.theme.TingoTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.github.tancheng33.tingo.ui.components.PillButton
import io.github.tancheng33.tingo.ui.components.PillKind
import io.github.tancheng33.tingo.ui.components.TingoPageTitle
import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.ui.components.TextInputDialog
import io.github.tancheng33.tingo.ui.components.TingoDialog
import io.github.tancheng33.tingo.ui.components.TingoTextDialog
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
    /** 给某位说话人改名；第三个参数在名字落盘后执行（"保存并重新生成纪要"用） */
    onRenameSpeaker: (Int, String, () -> Unit) -> Unit = { _, _, _ -> },
    /** 「只删录音」：录音移到回收站，转写与纪要留着（提示文案由外层给） */
    onDeleteRecording: () -> Unit = {},
    /** 改名后接着重新生成纪要（桌面的"保存并重新生成纪要"） */
    onRegenerateMinutes: () -> Unit = {},
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
    highlights: List<Long> = emptyList(),
    includeHighlights: Boolean = true,
    onIncludeHighlightsChange: (Boolean) -> Unit = {},
) {
    val pageTitle = state.detail?.summary?.title?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.nav_meetings)
    when {
        state.loading -> Column(
            modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TingoPageTitle(pageTitle, style = MaterialTheme.typography.headlineSmall)
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        state.failure != null -> Column(
            modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TingoPageTitle(pageTitle, style = MaterialTheme.typography.headlineSmall)
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
            var deleteAudioOpen by remember { mutableStateOf(false) }
            Column(modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TingoPageTitle(
                        text = pageTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
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
                            if (onExportBundle != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.detail_export_bundle), color = TingoTheme.colors.text) },
                                    enabled = exportEnabled,
                                    onClick = {
                                        menuOpen = false
                                        onExportBundle()
                                    },
                                )
                            }
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
                            // 有录音才给「只删录音」：没有录音的会议点了只会报错
                            // 能播就等于有录音：引擎的 hasAudio 只认混音后的"录音.*"，而崩溃/未混音的
                            // 会议只剩 mic/system 原始音轨——照样能播、也能删
                            if (state.playback.available || detail?.summary?.hasAudio == true) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.detail_delete_audio),
                                            color = TingoTheme.colors.text,
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        deleteAudioOpen = true
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
                if (deleteAudioOpen) {
                    io.github.tancheng33.tingo.ui.components.TingoConfirmDialog(
                        title = stringResource(R.string.detail_delete_audio_confirm_title),
                        body = stringResource(R.string.detail_delete_audio_confirm_body),
                        confirmLabel = stringResource(R.string.detail_delete_audio),
                        onDismiss = { deleteAudioOpen = false },
                        onConfirm = {
                            deleteAudioOpen = false
                            onDeleteRecording()
                        },
                    )
                }
                if (renameOpen) {
                    TextInputDialog(
                        title = stringResource(R.string.detail_rename),
                        // 留空是"用纪要标题或目录名"（提示里就是这么写的）：不能挡
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
                        hasMinutes = detail?.minutesText.isNullOrBlank().not(),
                        onRegenerate = onRegenerateMinutes,
                    )
                }
                if (playback.available) {
                    PlaybackBar(playback = playback, onTogglePlay = onTogglePlay)
                    if (highlights.isNotEmpty()) {
                        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            highlights.forEach { position ->
                                TextButton(onClick = { onPlayFrom(position) }) {
                                    Text(clockOf(position.toULong()))
                                }
                            }
                        }
                    }
                }
                if (highlights.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onIncludeHighlightsChange(!includeHighlights) }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = includeHighlights, onCheckedChange = onIncludeHighlightsChange)
                        Text(stringResource(R.string.detail_include_highlights))
                    }
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
                        transcript = detail?.transcript,
                        onPlayFrom = onSentenceClick,
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
    /** 转写正文：鱼骨图的"有没有原话支撑"与时间戳跳转都要它 */
    transcript: io.github.tancheng33.tingo.engine.Transcript?,
    onPlayFrom: (ULong) -> Unit,
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
                        FishboneCard(fishbone, transcript = transcript, onPlayFrom = onPlayFrom)
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

/**
 * 鱼骨图：分支 + 每条分支的**原文核对**。
 *
 * 桌面把"结论有出处"当卖点：每条分支会数它引用了多少段原话、共多少字，
 * 一段都对不上就直接标「无原文依据」。手机上原来只把时间戳画成不可点的胶囊，
 * 等于把"编出来的原因"和"有原话的原因"画成一样。
 *
 * @param onPlayFrom 点时间戳就跳到那句开始播（与转写里的句子同一个动作）
 */
@Composable
private fun FishboneCard(
    fishbone: Fishbone,
    transcript: io.github.tancheng33.tingo.engine.Transcript?,
    onPlayFrom: (ULong) -> Unit,
) {
    val colors = TingoTheme.colors
    val summary = FishboneGrounding.summarize(fishbone, transcript)
    // 展开哪一条（按标题记，重新加载纪要后标题不变就不会错位）
    var expanded by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(fishbone.problem, style = MaterialTheme.typography.titleSmall)
        FishboneDiagram(fishbone = fishbone, transcript = transcript)
        Text(
            text = if (summary.withoutEvidence > 0) {
                stringResource(
                    R.string.detail_fishbone_summary_partial,
                    summary.branches,
                    summary.quotes,
                    summary.withoutEvidence,
                )
            } else {
                stringResource(R.string.detail_fishbone_summary, summary.branches, summary.quotes)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (summary.withoutEvidence > 0) colors.red else colors.muted,
        )
        fishbone.branches.filter { it.title.isNotBlank() }.forEach { branch ->
            val grounding = FishboneGrounding.ground(branch.refs, transcript)
            // 点一下展开原话：桌面上每条分支点开就能看到"这句话是谁在哪一刻说的"。
            // 只有真的有引用才可点，否则展开一个空盒子只会让人困惑。
            val expandable = grounding.quotes.isNotEmpty()
            val expandedNow = expanded == branch.title
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (expandable) {
                            Modifier.clickable { expanded = if (expandedNow) null else branch.title }
                        } else {
                            Modifier
                        },
                    ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(branch.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (branch.detail.isNotBlank()) {
                    Text(branch.detail, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    text = when (grounding.level) {
                        FishboneGrounding.Level.NONE -> stringResource(R.string.detail_fishbone_no_evidence)
                        FishboneGrounding.Level.WEAK -> stringResource(
                            R.string.detail_fishbone_weak,
                            grounding.quotes.size,
                            grounding.chars,
                        )
                        FishboneGrounding.Level.OK -> stringResource(
                            R.string.detail_fishbone_quotes,
                            grounding.quotes.size,
                            grounding.chars,
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    // 桌面配色：对不上原话是"红色"（问题），依据很少是"橙色"（提醒），够了才是灰
                    color = when (grounding.level) {
                        FishboneGrounding.Level.NONE -> colors.red
                        FishboneGrounding.Level.WEAK -> colors.amber
                        FishboneGrounding.Level.OK -> colors.muted
                    },
                )
                if (expandable && expandedNow) {
                    grounding.quotes.forEach { quote ->
                        FishboneQuoteRow(quote = quote, onPlayFrom = onPlayFrom)
                    }
                } else if (branch.refs.isNotEmpty()) {
                    // 折叠时显示模型给的时间戳：核对上的去重排序、可点回听；对不上的照原样列出
                    // （不可点）。两边都不藏，用户才看得出"这条分支引用了什么"。
                    val refs = FishboneGrounding.split(branch.refs, transcript)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        refs.quotes.forEach { quote ->
                            AssistChip(
                                onClick = { onPlayFrom(quote.ms.toULong()) },
                                // 显示纪要里写的时间戳原文：换成句子起点会让人以为"时间戳被改了"
                                label = { Text(quote.ref.ifBlank { Transcript.mmss(quote.ms) }) },
                            )
                        }
                        refs.unresolved.forEach { ref ->
                            AssistChip(onClick = {}, enabled = false, label = { Text(ref) })
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/** 手机端鱼骨图总览；分支详情仍由下面的证据列表负责展开。 */
@Composable
private fun FishboneDiagram(
    fishbone: Fishbone,
    transcript: io.github.tancheng33.tingo.engine.Transcript?,
) {
    val colors = TingoTheme.colors
    val branches = fishbone.branches.filter { it.title.isNotBlank() }
    val branchColors = branches.map { branch ->
        when (FishboneGrounding.ground(branch.refs, transcript).level) {
            FishboneGrounding.Level.NONE -> colors.red
            FishboneGrounding.Level.WEAK -> colors.amber
            FishboneGrounding.Level.OK -> colors.green
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.card, TingoDimens.cardShape)
            .border(BorderStroke(TingoDimens.hairline, colors.line), TingoDimens.cardShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(104.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height * 0.52f
                val start = size.width * 0.08f
                val end = size.width * 0.92f
                drawLine(colors.blue, androidx.compose.ui.geometry.Offset(start, y), androidx.compose.ui.geometry.Offset(end, y), 3.dp.toPx(), StrokeCap.Round)
                drawLine(colors.blue, androidx.compose.ui.geometry.Offset(end - 12.dp.toPx(), y - 8.dp.toPx()), androidx.compose.ui.geometry.Offset(end, y), 3.dp.toPx(), StrokeCap.Round)
                drawLine(colors.blue, androidx.compose.ui.geometry.Offset(end - 12.dp.toPx(), y + 8.dp.toPx()), androidx.compose.ui.geometry.Offset(end, y), 3.dp.toPx(), StrokeCap.Round)
                branches.take(6).forEachIndexed { index, _ ->
                    val x = start + (end - start) * ((index + 1).toFloat() / (branches.take(6).size + 1))
                    val upper = index % 2 == 0
                    val branchY = if (upper) y - 32.dp.toPx() else y + 32.dp.toPx()
                    drawLine(colors.blue, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Offset(x - 22.dp.toPx(), branchY), 2.dp.toPx(), StrokeCap.Round)
                    drawCircle(branchColors[index], radius = 5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x - 22.dp.toPx(), branchY))
                }
                drawCircle(colors.blue, radius = 8.dp.toPx(), center = androidx.compose.ui.geometry.Offset(end, y))
            }
            Box(
                modifier = Modifier.align(Alignment.CenterEnd).background(colors.blueSoft, TingoDimens.controlShape).padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(fishbone.problem, style = MaterialTheme.typography.labelMedium, color = colors.blue, maxLines = 2)
            }
        }
        branches.take(6).forEachIndexed { index, branch ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(branchColors[index], CircleShape))
                Text(branch.title, style = MaterialTheme.typography.labelMedium, color = colors.text2, maxLines = 1)
            }
        }
        if (branches.size > 6) {
            Text(stringResource(R.string.detail_fishbone_more, branches.size - 6), style = MaterialTheme.typography.labelSmall, color = colors.muted)
        }
    }
}

/**
 * 鱼骨图里的一条引用：时间戳（点它回听）+ 说话人 + 原话。
 *
 * 与桌面同一个信息量：桌面在分支下面展开的就是"谁说、什么时候、说了什么"。
 * 没有这段原话，用户只能相信模型总结的因果，没法自己判断。
 */
@Composable
private fun FishboneQuoteRow(quote: FishboneGrounding.Quote, onPlayFrom: (ULong) -> Unit) {
    val colors = TingoTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(
            onClick = { onPlayFrom(quote.ms.toULong()) },
            label = { Text(quote.ref.ifBlank { Transcript.mmss(quote.ms) }) },
        )
        Column(Modifier.weight(1f)) {
            if (quote.speaker.isNotBlank()) {
                Text(
                    text = quote.speaker,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                )
            }
            Text(quote.text, style = MaterialTheme.typography.bodySmall, color = colors.text2)
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
    // 分段提炼时把"第几段"显示出来（长会议一两分钟没反馈，用户以为卡住了）
    is MinutesUiState.Generating -> TaskStatus.Running(null, R.string.minutes_generating, stage = stage)
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
    /** 改名；第二个回调在名字落盘之后才执行（"保存并重新生成"要用它） */
    onRename: (Int, String, () -> Unit) -> Unit,
    onGuess: () -> Unit,
    onApplyGuess: (Int) -> Unit,
    /** 有纪要时给"保存并重新生成"：改完名字旧纪要里的称呼就旧了 */
    hasMinutes: Boolean = false,
    onRegenerate: () -> Unit = {},
) {
    val colors = TingoTheme.colors
    var editing by remember { mutableStateOf<Int?>(null) }
    val editingSpeaker = editing?.let { index -> speakers.firstOrNull { it.index == index.toUInt() } }

    if (editingSpeaker != null) {
        TextInputDialog(
            title = stringResource(R.string.detail_speaker_rename),
            // 留空是"恢复说话人N"：这里也不该挡
            initial = editingSpeaker.name,
            hint = stringResource(R.string.detail_speaker_rename_hint),
            onDismiss = { editing = null },
            onConfirm = { name ->
                editing = null
                onRename(editingSpeaker.index.toInt(), name, {})
            },
            secondaryAction = if (hasMinutes) stringResource(R.string.detail_save_and_regenerate) else null,
            onSecondary = if (hasMinutes) {
                { name ->
                    editing = null
                    // 重新生成要等名字写完：传成回调，名字落盘之后才触发
                    onRename(editingSpeaker.index.toInt(), name, onRegenerate)
                }
            } else {
                null
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
    var text by remember { mutableStateOf(initial) }
    TingoTextDialog(
        title = stringResource(R.string.detail_edit_minutes),
        text = text,
        editable = true,
        onTextChange = { text = it },
        contentDescription = "minutes-editor",
        confirmLabel = stringResource(R.string.action_save),
        onConfirm = { onSave(text) },
        onDismiss = onDismiss,
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
    TingoDialog(
        title = stringResource(R.string.detail_history, versions.size),
        onDismiss = onDismiss,
        confirm = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close), color = colors.blue) }
        },
        content = {
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
    TingoTextDialog(
        title = stringResource(R.string.detail_history_preview_title),
        text = preview.text,
        confirmLabel = stringResource(R.string.detail_history_restore),
        onConfirm = onRestore,
        onDismiss = onClose,
    )
}
