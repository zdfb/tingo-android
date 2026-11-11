package io.github.tancheng33.tingo.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.draw.clip
import io.github.tancheng33.tingo.data.BatchAction
import io.github.tancheng33.tingo.engine.TrashItem
import io.github.tancheng33.tingo.ui.components.PillButton
import io.github.tancheng33.tingo.ui.components.PillKind
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.ui.components.TextInputDialog
import io.github.tancheng33.tingo.ui.components.TingoConfirmDialog
import io.github.tancheng33.tingo.data.MeetingCard
import io.github.tancheng33.tingo.engine.FailureKind
import io.github.tancheng33.tingo.ui.detail.failureText
import io.github.tancheng33.tingo.ui.theme.TingoDimens
import io.github.tancheng33.tingo.ui.theme.TingoTheme
import io.github.tancheng33.tingo.ui.components.PillFilter
import io.github.tancheng33.tingo.ui.components.TingoPageTitle
import io.github.tancheng33.tingo.ui.theme.TingoType
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * 列表上的管理动作。
 *
 * 参数一次性有十来个，散着传会让签名读不出重点，所以打包成一个对象——
 * 界面层只关心"有哪些动作"，不关心它们各自从哪来。
 */
class LibraryManageActions(
    val onToggleSelection: (String) -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onBatch: (BatchAction, String) -> Unit = { _, _ -> },
    val onDeleteToTrash: (String) -> Unit = {},
    val onShowTrash: () -> Unit = {},
    val onShowMeetings: () -> Unit = {},
    val onRestore: (String) -> Unit = {},
    val onPurge: (String) -> Unit = {},
    /** 清空整个回收站 */
    val onEmptyTrash: () -> Unit = {},
)

/**
 * 会议库列表。
 *
 * 视觉上对着桌面版的"会议记录"页：近白底、白卡片 + 1px 细描边、16dp 圆角，
 * 标题与摘要用正文黑，时间与标签退到灰阶。列表项只回答三件事——什么时候开的、
 * 开了多久、一句话讲了什么；转写与纪要细节留在详情页。
 */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onOpenMeeting: (String) -> Unit,
    onRefresh: () -> Unit,
    onQueryChange: (String) -> Unit,
    onArchivedOnlyChange: (Boolean) -> Unit,
    /** 点击搜索命中：打开会议并跳到该毫秒 */
    onOpenAtHit: (String, ULong) -> Unit = { _, _ -> },
    onUnreadableClick: (MeetingCard) -> Unit = {},
    onSetArchived: (MeetingCard, Boolean) -> Unit = { _, _ -> },
    onDelete: (MeetingCard) -> Unit = {},
    manage: LibraryManageActions = LibraryManageActions(),
    /** 回收站内容；[state] 为空且 [trashVisible] 为真时显示回收站视图 */
    trash: List<TrashItem> = emptyList(),
    trashVisible: Boolean = false,
    batchFailures: Int = 0,
    onStartRecording: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = TingoTheme.colors
    if (trashVisible) {
        TrashView(
            items = trash,
            onRestore = manage.onRestore,
            onPurge = manage.onPurge,
            onBack = manage.onShowMeetings,
            onEmpty = manage.onEmptyTrash,
            modifier = modifier,
        )
        return
    }
    when (state) {
        LibraryUiState.Loading -> DefaultLibraryScaffold(
            modifier = modifier,
            onQueryChange = onQueryChange,
            onArchivedOnlyChange = onArchivedOnlyChange,
            onShowTrash = manage.onShowTrash,
        ) {
            CircularProgressIndicator(color = colors.blue, strokeWidth = 2.dp)
        }

        LibraryUiState.Empty -> DefaultLibraryScaffold(
            modifier = modifier,
            onQueryChange = onQueryChange,
            onArchivedOnlyChange = onArchivedOnlyChange,
            onShowTrash = manage.onShowTrash,
        ) {
            MessageBlock(
                title = stringResource(R.string.library_empty),
                body = stringResource(R.string.library_empty_body),
                action = stringResource(R.string.library_start_recording),
                onAction = onStartRecording,
                actionKind = PillKind.Primary,
            )
        }

        is LibraryUiState.Failed -> DefaultLibraryScaffold(
            modifier = modifier,
            onQueryChange = onQueryChange,
            onArchivedOnlyChange = onArchivedOnlyChange,
            onShowTrash = manage.onShowTrash,
        ) {
            MessageBlock(
                title = stringResource(R.string.error_title),
                body = stringResource(R.string.library_failed),
                action = stringResource(R.string.action_refresh),
                onAction = onRefresh,
            )
        }

        is LibraryUiState.Content -> Column(modifier.fillMaxSize()) {
            LibraryHeader(
                query = state.query,
                onQueryChange = onQueryChange,
                archivedOnly = state.archivedOnly,
                onArchivedOnlyChange = onArchivedOnlyChange,
                onShowTrash = manage.onShowTrash,
                countLabel = if (state.items.size == state.totalCount) {
                    stringResource(R.string.library_count, state.totalCount)
                } else {
                    stringResource(R.string.library_count_filtered, state.items.size, state.totalCount)
                },
            )
            if (state.selection.isNotEmpty()) {
                BatchBar(
                    count = state.selection.size,
                    failures = batchFailures,
                    onSelectAll = manage.onSelectAll,
                    onClear = manage.onClearSelection,
                    onBatch = manage.onBatch,
                )
            } else if (state.query.isNotBlank() && state.hits.isNotEmpty()) {
                SearchHits(state = state, onOpenAt = onOpenAtHit)
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = TingoDimens.gutter,
                    end = TingoDimens.gutter,
                    top = 4.dp,
                    bottom = TingoDimens.gutter,
                ),
                verticalArrangement = Arrangement.spacedBy(TingoDimens.gapTight),
            ) {
                if (state.items.isEmpty() && state.hits.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(TingoDimens.gutter),
                            contentAlignment = Alignment.Center,
                        ) {
                            MessageBlock(
                                title = stringResource(R.string.library_no_results),
                                body = stringResource(R.string.library_no_results_body),
                                action = stringResource(R.string.library_clear_filters),
                                onAction = {
                                    onQueryChange("")
                                    if (state.archivedOnly) onArchivedOnlyChange(false)
                                },
                            )
                        }
                    }
                } else {
                    items(state.items, key = { it.id }) { card ->
                        MeetingCardRow(
                            card = card,
                            query = state.query,
                            selected = card.id in state.selection,
                            selecting = state.selection.isNotEmpty(),
                            onClick = {
                                if (state.selection.isNotEmpty()) manage.onToggleSelection(card.id) else onOpenMeeting(card.id)
                            },
                            onLongClick = { manage.onToggleSelection(card.id) },
                            onUnreadableClick = { onUnreadableClick(card) },
                            onSetArchived = { onSetArchived(card, it) },
                            onDelete = { manage.onDeleteToTrash(card.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultLibraryScaffold(
    modifier: Modifier,
    onQueryChange: (String) -> Unit,
    onArchivedOnlyChange: (Boolean) -> Unit,
    onShowTrash: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        LibraryHeader(
            query = "",
            onQueryChange = onQueryChange,
            archivedOnly = false,
            onArchivedOnlyChange = onArchivedOnlyChange,
            onShowTrash = onShowTrash,
            countLabel = stringResource(R.string.library_count, 0),
        )
        Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

/**
 * 标题区 + 搜索 + 筛选，全部放在同一个吸顶块里。
 *
 * 搜索框做成一枚"分段底色"的胶囊而不是带描边的输入框：桌面版也是这种"输入藏在底色里"的做法，
 * 手机屏幕上更少一层线框，列表更安静。
 */
@Composable
private fun LibraryHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    archivedOnly: Boolean,
    onArchivedOnlyChange: (Boolean) -> Unit,
    countLabel: String,
    onShowTrash: () -> Unit,
) {
    val colors = TingoTheme.colors
    val searchLabel = stringResource(R.string.library_search_hint)
    Column(
        modifier = Modifier.padding(
            start = TingoDimens.gutter,
            end = TingoDimens.gutter,
            top = 4.dp,
            bottom = TingoDimens.gap,
        ),
        verticalArrangement = Arrangement.spacedBy(TingoDimens.gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TingoPageTitle(
                text = stringResource(R.string.library_title),
                modifier = Modifier.weight(1f),
            )
            Text(text = countLabel, style = MaterialTheme.typography.labelMedium, color = colors.faint)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TingoDimens.gapTight),
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.seg, TingoDimens.controlShape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = colors.faint,
                modifier = Modifier.size(17.dp),
            )
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = searchLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.faint,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = TingoType.body, color = colors.text),
                    cursorBrush = SolidColor(colors.blue),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {}),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = searchLabel },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(TingoDimens.gapTight), verticalAlignment = Alignment.CenterVertically) {
            PillFilter(
                label = stringResource(R.string.library_filter_archived),
                selected = archivedOnly,
                onClick = { onArchivedOnlyChange(!archivedOnly) },
            )
            // 回收站入口：删除是可恢复的动作，就必须有个地方能把会议找回来
            PillButton(
                label = stringResource(R.string.library_trash_entry),
                onClick = onShowTrash,
                kind = PillKind.Ghost,
            )
        }
    }
}

@Composable
private fun MessageBlock(
    title: String,
    body: String? = null,
    action: String,
    onAction: () -> Unit,
    actionKind: PillKind = PillKind.Ghost,
) {
    val colors = TingoTheme.colors
    Column(
        modifier = Modifier.padding(TingoDimens.gutter),
        verticalArrangement = Arrangement.spacedBy(TingoDimens.gapTight),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.text)
        if (body != null) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        }
        PillButton(label = action, onClick = onAction, kind = actionKind)
    }
}

@Composable
private fun MeetingCardRow(
    card: MeetingCard,
    /** 当前搜索词，用来高亮命中的字 */
    query: String,
    selected: Boolean,
    /** 正在多选：卡片左侧显示勾选圈 */
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onUnreadableClick: () -> Unit,
    onSetArchived: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = TingoTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        TingoConfirmDialog(
            title = stringResource(R.string.library_delete_title),
            body = stringResource(R.string.library_delete_body),
            confirmLabel = stringResource(R.string.library_delete_confirm),
            destructive = true,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.card, TingoDimens.cardShape)
            .border(BorderStroke(TingoDimens.hairline, colors.line), TingoDimens.cardShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                SelectionDot(selected = selected)
            }
            Text(
                text = highlighted(card.title.ifBlank { card.id }, query),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (card.unreadable) {
                Text(
                    text = stringResource(R.string.library_unreadable),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.amber,
                    modifier = Modifier
                        .clickable(onClick = onUnreadableClick)
                        .background(colors.seg, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.library_more),
                        tint = colors.faint,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = colors.card,
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (card.archived) R.string.library_unarchive else R.string.library_archive,
                                ),
                                color = colors.text,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onSetArchived(!card.archived)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_delete), color = colors.red) },
                        onClick = {
                            menuOpen = false
                            confirmDelete = true
                        },
                    )
                }
            }
        }
        Text(
            text = listOf(card.date, card.time, card.duration).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = colors.muted,
        )
        if (card.oneLiner.isNotBlank()) {
            Text(
                text = highlighted(card.oneLiner, query),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        if (card.tags.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                card.tags.forEach { tag ->
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.blue,
                        modifier = Modifier
                            .background(colors.blueSoft, TingoDimens.pillShape)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/** 把命中关键词的片段画成主色加粗；没命中就原样画。 */
@Composable
private fun highlighted(text: String, query: String): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    val accent = TingoTheme.colors.blue
    return buildAnnotatedString {
        Highlight.split(text, query).forEach { piece ->
            if (piece.matched) {
                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(piece.text) }
            } else {
                append(piece.text)
            }
        }
    }
}

/** 多选时卡片左侧的勾选圈：选中用主色实心，未选用细描边。 */
@Composable
private fun SelectionDot(selected: Boolean) {
    val colors = TingoTheme.colors
    Box(
        modifier = Modifier
            .padding(end = 10.dp)
            .size(18.dp)
            .clip(CircleShape)
            .background(if (selected) colors.blue else colors.card)
            .border(BorderStroke(TingoDimens.hairline, if (selected) colors.blue else colors.line), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = colors.onBlack,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** 多选后的批量操作条：最常用的四个动作直接给按钮，标签通过输入框补。 */
@Composable
private fun BatchBar(
    count: Int,
    failures: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onBatch: (BatchAction, String) -> Unit,
) {
    val colors = TingoTheme.colors
    // 加标签与移除标签用的是同一个输入框，靠这个记住当前要执行哪个动作
    var tagDialog by remember { mutableStateOf<BatchAction?>(null) }
    // 「只删录音」不可撤销：点按钮先弹确认
    var confirmDeleteAudio by remember { mutableStateOf(false) }
    if (tagDialog != null) {
        TextInputDialog(
            title = stringResource(
                if (tagDialog == BatchAction.TAG_REMOVE) R.string.library_batch_untag_title else R.string.library_batch_tag_title,
            ),
            requireNonBlank = true,
            onDismiss = { tagDialog = null },
            onConfirm = { tag ->
                val action = tagDialog
                tagDialog = null
                if (action != null) onBatch(action, tag)
            },
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TingoDimens.gutter, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.library_selected_count, count),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
            )
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.library_select_all), color = colors.blue) }
            TextButton(onClick = onClear) { Text(stringResource(R.string.action_cancel), color = colors.muted) }
        }
        if (confirmDeleteAudio) {
            TingoConfirmDialog(
                title = stringResource(R.string.detail_delete_audio_confirm_title),
                body = stringResource(R.string.library_batch_delete_audio_body),
                confirmLabel = stringResource(R.string.detail_delete_audio),
                destructive = true,
                onConfirm = {
                    confirmDeleteAudio = false
                    onBatch(BatchAction.DELETE_AUDIO, "")
                },
                onDismiss = { confirmDeleteAudio = false },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.library_archive), { onBatch(BatchAction.ARCHIVE, "") }, kind = PillKind.Soft)
            PillButton(
                stringResource(R.string.library_unarchive),
                { onBatch(BatchAction.UNARCHIVE, "") },
                kind = PillKind.Soft,
            )
            PillButton(
                stringResource(R.string.library_batch_tag),
                { tagDialog = BatchAction.TAG_ADD },
                kind = PillKind.Soft,
            )
            PillButton(
                stringResource(R.string.library_batch_untag),
                { tagDialog = BatchAction.TAG_REMOVE },
                kind = PillKind.Soft,
            )
            // 「只删录音」是真删（腾出空间、不能撤销）：必须先弹确认，和单场详情页同一个文案
            PillButton(
                stringResource(R.string.library_batch_delete_audio),
                { confirmDeleteAudio = true },
                kind = PillKind.Soft,
            )
            // 删除进回收站，可恢复；界面在动作前不来第二遍确认，因为回收站本身就是后悔药
            PillButton(stringResource(R.string.library_delete), { onBatch(BatchAction.DELETE, "") }, kind = PillKind.Primary)
        }
        if (failures > 0) {
            Text(
                stringResource(R.string.library_batch_partial, failures),
                style = MaterialTheme.typography.bodySmall,
                color = colors.red,
            )
        }
    }
}

/** 搜索结果：按会议分组显示命中片段，点一条跳到那个时间点。 */
@Composable
private fun SearchHits(state: LibraryUiState.Content, onOpenAt: (String, ULong) -> Unit) {
    val colors = TingoTheme.colors
    val grouped = state.hits.groupBy { it.meetingId }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TingoDimens.gutter, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.expandedTerms.size > 1) {
            Text(
                stringResource(R.string.library_search_expanded, state.expandedTerms.joinToString("、")),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
            )
        }
        grouped.entries.take(3).forEach { (meetingId, hits) ->
            val first = hits.first()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = first.title.ifBlank { meetingId },
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.text,
                )
                hits.take(3).forEach { hit ->
                    Text(
                        text = if (hit.inMinutes) {
                            stringResource(R.string.library_hit_in_minutes)
                        } else {
                            "${hit.speaker}：${hit.snippet}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.blue,
                        modifier = Modifier.clickable { onOpenAt(meetingId, hit.startMs) },
                    )
                }
            }
        }
    }
}

/** 回收站：恢复或彻底清除。 */
@Composable
private fun TrashView(
    items: List<TrashItem>,
    onRestore: (String) -> Unit,
    onPurge: (String) -> Unit,
    onBack: () -> Unit,
    onEmpty: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TingoTheme.colors
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = TingoDimens.gutter, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TingoPageTitle(
                stringResource(R.string.library_trash_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back), color = colors.blue) }
            if (items.isNotEmpty()) {
                TextButton(onClick = onEmpty) {
                    Text(stringResource(R.string.library_trash_empty_all), color = colors.red)
                }
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.library_trash_empty), color = colors.muted)
            }
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(TingoDimens.gutter),
            verticalArrangement = Arrangement.spacedBy(TingoDimens.gapTight),
        ) {
            items(items, key = { it.id }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.card, TingoDimens.cardShape)
                        .border(BorderStroke(TingoDimens.hairline, colors.line), TingoDimens.cardShape)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.id, style = MaterialTheme.typography.bodyMedium, color = colors.text)
                        Text(
                            "${item.bytes / 1_000_000u} MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.muted,
                        )
                    }
                    TextButton(onClick = { onRestore(item.id) }) {
                        Text(stringResource(R.string.library_trash_restore), color = colors.blue)
                    }
                    TextButton(onClick = { onPurge(item.id) }) {
                        Text(stringResource(R.string.library_trash_purge), color = colors.red)
                    }
                }
            }
        }
    }
}
