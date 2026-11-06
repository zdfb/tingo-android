package io.github.tancheng33.tingo.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.data.MeetingCard
import io.github.tancheng33.tingo.engine.FailureKind
import io.github.tancheng33.tingo.ui.detail.failureText

/**
 * 会议库列表。
 *
 * 列表项显示"什么时候开的、开了多久、一句话讲了什么"，这三件事是从列表进入详情
 * 之前唯一需要知道的信息；转写与纪要细节留给详情页。
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
    if (actionError != null) {
        Text(
            text = stringResource(R.string.library_action_failed, failureText(actionError)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    when (state) {
        LibraryUiState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        LibraryUiState.Empty -> Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.library_empty), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.action_refresh)) }
            }
        }

        is LibraryUiState.Failed -> Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.error_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.library_failed), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.action_refresh)) }
            }
        }

        is LibraryUiState.Content -> Column(modifier.fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                singleLine = true,
                label = { Text(stringResource(R.string.library_search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                FilterChip(
                    selected = state.archivedOnly,
                    onClick = { onArchivedOnlyChange(!state.archivedOnly) },
                    label = { Text(stringResource(R.string.library_filter_archived)) },
                )
                Text(
                    text = if (state.items.size == state.totalCount) {
                        stringResource(R.string.library_count, state.totalCount)
                    } else {
                        stringResource(R.string.library_count_filtered, state.items.size, state.totalCount)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.items, key = { it.id }) { card ->
                    MeetingCardRow(
                        card = card,
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

@Composable
private fun MeetingCardRow(
    card: MeetingCard,
    onClick: () -> Unit,
    onUnreadableClick: () -> Unit,
    onSetArchived: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.library_delete_title)) },
            text = { Text(stringResource(R.string.library_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text(stringResource(R.string.library_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = card.title.ifBlank { card.id },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (card.unreadable) {
                    // 读不出来的目录要能让用户看见它到底是哪个文件夹，否则无从排查
                    AssistChip(
                        onClick = onUnreadableClick,
                        label = { Text(stringResource(R.string.library_unreadable)) },
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.library_more),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (card.archived) R.string.library_unarchive else R.string.library_archive,
                                    ),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onSetArchived(!card.archived)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_delete)) },
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (card.oneLiner.isNotBlank()) {
                Text(text = card.oneLiner, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            }
            if (card.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    card.tags.forEach { tag ->
                        Text(text = "#$tag", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
