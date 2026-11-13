package io.github.tancheng33.tingo.ui.record

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.ui.clockOf
import io.github.tancheng33.tingo.ui.components.PillButton
import io.github.tancheng33.tingo.ui.components.PillKind
import io.github.tancheng33.tingo.ui.components.TingoConfirmDialog
import io.github.tancheng33.tingo.ui.theme.TingoDimens
import io.github.tancheng33.tingo.ui.theme.TingoTheme

/** The current recording is the workspace; controls stay visible while the transcript scrolls. */
@Composable
internal fun RecordingWorkspace(
    state: RecordUiState,
    lines: List<String>,
    entries: List<RecordViewModel.LiveEntry>,
    incomplete: Boolean,
    highlightSaveFailed: Boolean,
    highlights: List<Long>,
    systemAudioMissing: Boolean,
    notificationsDenied: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onMark: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TingoTheme.colors
    val paused = state is RecordUiState.Paused
    val elapsed = when (state) {
        is RecordUiState.Recording -> state.elapsedMs
        is RecordUiState.Paused -> state.elapsedMs
        else -> 0L
    }
    val level = (state as? RecordUiState.Recording)?.level ?: 0f
    var confirmDiscard by remember { mutableStateOf(false) }
    if (confirmDiscard) {
        TingoConfirmDialog(
            title = stringResource(R.string.record_discard),
            body = stringResource(R.string.record_discard_confirm),
            confirmLabel = stringResource(R.string.record_discard),
            destructive = true,
            onConfirm = { confirmDiscard = false; onDiscard() },
            onDismiss = { confirmDiscard = false },
        )
    }
    Column(modifier.fillMaxSize().background(colors.bg).padding(horizontal = TingoDimens.gutter)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = TingoDimens.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.nav_record), style = MaterialTheme.typography.titleMedium, color = colors.text)
                Text(
                    stringResource(if (paused) R.string.record_workspace_paused else R.string.record_workspace_active),
                    style = MaterialTheme.typography.labelMedium, color = colors.muted,
                )
            }
            Text(clockOf(elapsed.toULong()), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = colors.text)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).background(colors.seg, TingoDimens.pillShape)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(level.coerceIn(0f, 1f)).background(colors.blue, TingoDimens.pillShape))
        }
        Column(Modifier.padding(vertical = TingoDimens.gapTight), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (incomplete) Text(stringResource(R.string.record_live_incomplete), color = colors.red, style = MaterialTheme.typography.bodySmall)
            if (highlightSaveFailed) Text(stringResource(R.string.record_highlight_save_failed), color = colors.red, style = MaterialTheme.typography.bodySmall)
            if (systemAudioMissing) Text(stringResource(R.string.record_system_audio_missing), color = colors.red, style = MaterialTheme.typography.bodySmall)
            if (notificationsDenied) Text(stringResource(R.string.record_notification_denied), color = colors.muted, style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider(color = colors.line)
        val listState = rememberLazyListState()
        val avatarColors = listOf(colors.blue, colors.cyan, colors.green, colors.amber)
        val bubbleColors = listOf(
            colors.blueSoft,
            colors.seg,
            colors.green.copy(alpha = 0.12f),
            colors.amber.copy(alpha = 0.14f),
        )
        val count = if (entries.isNotEmpty()) entries.size else lines.size
        LaunchedEffect(count) {
            if (count > 0 && listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("live-transcript"),
            state = listState,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(TingoDimens.gap),
        ) {
            if (entries.isNotEmpty()) {
                items(count = entries.size, key = { index -> entries[entries.lastIndex - index].key }) { index ->
                    val entry = entries[entries.lastIndex - index]
                    val speakerIndex = entry.speaker ?: 0
                    val colorIndex = speakerIndex.mod(avatarColors.size)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = TingoDimens.gapTight),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            Modifier.size(38.dp).background(avatarColors[colorIndex], CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = entry.speaker?.let { "S$it" } ?: "我",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = if (colorIndex == 1) colors.text else colors.onBlack,
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = entry.speaker?.let { stringResource(R.string.record_provisional_speaker, it) }
                                        ?: stringResource(R.string.record_speaker_unknown),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                    color = colors.text2,
                                )
                                Text(
                                    text = stringResource(R.string.record_live_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.faint,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = bubbleColors[colorIndex],
                                        shape = RoundedCornerShape(
                                            topStart = 5.dp,
                                            topEnd = 16.dp,
                                            bottomEnd = 16.dp,
                                            bottomStart = 16.dp,
                                        ),
                                    )
                                    .border(TingoDimens.hairline, colors.lineSoft, RoundedCornerShape(16.dp))
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                            ) {
                                Text(
                                    text = entry.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.text,
                                )
                            }
                        }
                    }
                }
            } else if (lines.isNotEmpty()) {
                items(lines.asReversed()) { line -> Text(line, style = MaterialTheme.typography.bodyLarge, color = colors.text) }
            } else {
                item { Text(stringResource(R.string.record_workspace_listening), color = colors.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = TingoDimens.gutter)) }
            }
        }
        Text(stringResource(R.string.record_live_hint), style = MaterialTheme.typography.labelMedium, color = colors.muted)
        if (entries.isNotEmpty()) Text(stringResource(R.string.record_speaker_provisional_hint), style = MaterialTheme.typography.labelMedium, color = colors.muted)
        if (highlights.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(TingoDimens.gapTight), modifier = Modifier.fillMaxWidth().padding(top = TingoDimens.gapTight)) {
                item { Text(stringResource(R.string.record_highlights), style = MaterialTheme.typography.labelMedium, color = colors.muted) }
                items(highlights) { position ->
                    Text(clockOf(position.toULong()), style = MaterialTheme.typography.labelMedium, color = colors.blue)
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = TingoDimens.gapTight), color = colors.line)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onMark) { Text(stringResource(R.string.record_mark_highlight)) }
            TextButton(onClick = { confirmDiscard = true }) { Text(stringResource(R.string.record_discard), color = colors.muted) }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = TingoDimens.gap), horizontalArrangement = Arrangement.spacedBy(TingoDimens.gap)) {
            PillButton(label = stringResource(if (paused) R.string.record_resume else R.string.record_pause), onClick = if (paused) onResume else onPause, kind = PillKind.Ghost, modifier = Modifier.weight(1f))
            PillButton(label = stringResource(R.string.record_stop), onClick = onStop, modifier = Modifier.weight(1f))
        }
    }
}
