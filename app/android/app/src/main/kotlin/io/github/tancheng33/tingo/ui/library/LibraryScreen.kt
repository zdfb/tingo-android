package io.github.tancheng33.tingo.ui.library

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.AlertDialog
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
import io.github.tancheng33.tingo.data.MeetingCard
import io.github.tancheng33.tingo.engine.FailureKind
import io.github.tancheng33.tingo.ui.detail.failureText
import io.github.tancheng33.tingo.ui.theme.TingoDimens
import io.github.tancheng33.tingo.ui.theme.TingoTheme
import io.github.tancheng33.tingo.ui.theme.TingoType
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction

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
    onUnreadableClick: (MeetingCard) -> Unit = {},
    onSetArchived: (MeetingCard, Boolean) -> Unit = { _, _ -> },
    onDelete: (MeetingCard) -> Unit = {},
    actionError: FailureKind? = null,
    modifier: Modifier = Modifier,
) {
    val colors = TingoTheme.colors
    if (actionError != null) {
        Text(
            text = stringResource(R.string.library_action_failed, failureText(actionError)),
            style = MaterialTheme.typography.bodySmall,
            color = colors.red,
            modifier = Modifier.fillMaxWidth().padding(horizontal = TingoDimens.gutter, vertical = 8.dp),
        )
    }
    when (state) {
        LibraryUiState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.blue, strokeWidth = 2.dp)
        }

        LibraryUiState.Empty -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MessageBlock(
                title = stringResource(R.string.library_empty),
                action = stringResource(R.string.action_refresh),
                onAction = onRefresh,
            )
        }

        is LibraryUiState.Failed -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                countLabel = if (state.items.size == state.totalCount) {
                    stringResource(R.string.library_count, state.totalCount)
                } else {
                    stringResource(R.string.library_count_filtered, state.items.size, state.totalCount)
                },
            )
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
                items(state.items, key = { it.id }) { card ->
                    MeetingCardRow(
                        card = card,
                        query = state.query,
                        onClick = { onOpenMeeting(card.id) },
                        onUnreadableClick = { onUnreadableClick(card) },
                        onSetArchived = { onSetArchived(card, it) },
                        onDelete = { onDelete(card) },
                    )
                }
            }
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
) {
    val colors = TingoTheme.colors
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
            Text(
                text = stringResource(R.string.app_name),
                style = TextStyle(fontSize = 17.dp.value.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
                color = colors.text,
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
                        text = stringResource(R.string.library_search_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.faint,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = TingoType.body, color = colors.text),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {}),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(TingoDimens.gapTight)) {
            FilterPill(
                selected = archivedOnly,
                label = stringResource(R.string.library_filter_archived),
                onClick = { onArchivedOnlyChange(!archivedOnly) },
            )
        }
    }
}

/** 小胶囊筛选：选中时用主色淡底 + 主色字，未选中是描边灰。 */
@Composable
private fun FilterPill(selected: Boolean, label: String, onClick: () -> Unit) {
    val colors = TingoTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(if (selected) colors.blueSoft else colors.card, TingoDimens.pillShape)
            .border(
                BorderStroke(TingoDimens.hairline, if (selected) colors.blueSoft else colors.line),
                TingoDimens.pillShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.blue else colors.muted,
        )
    }
}

@Composable
private fun MessageBlock(title: String, body: String? = null, action: String, onAction: () -> Unit) {
    val colors = TingoTheme.colors
    Column(
        modifier = Modifier.padding(TingoDimens.gutter),
        verticalArrangement = Arrangement.spacedBy(TingoDimens.gapTight),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.text)
        if (body != null) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        }
        TextButton(onClick = onAction) {
            Text(action, style = MaterialTheme.typography.labelLarge, color = colors.blue)
        }
    }
}

@Composable
private fun MeetingCardRow(
    card: MeetingCard,
    /** 当前搜索词，用来高亮命中的字 */
    query: String,
    onClick: () -> Unit,
    onUnreadableClick: () -> Unit,
    onSetArchived: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = TingoTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = colors.card,
            title = { Text(stringResource(R.string.library_delete_title), color = colors.text) },
            text = { Text(stringResource(R.string.library_delete_body), color = colors.muted) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text(stringResource(R.string.library_delete_confirm), color = colors.red) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel), color = colors.muted)
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.card, TingoDimens.cardShape)
            .border(BorderStroke(TingoDimens.hairline, colors.line), TingoDimens.cardShape)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
