package io.github.tancheng33.tingo.ui.detail

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
            var tab by remember { mutableIntStateOf(initialTab) }
            Column(modifier.fillMaxSize()) {
                Text(
                    text = detail?.summary?.title.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.detail_tab_minutes)) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.detail_tab_transcript)) })
                }
                if (tab == 0) {
                    MinutesPane(
                        minutes = state.minutes,
                        minutesFailure = state.minutesFailure,
                        fishbone = detail?.fishbone,
                        hasMinutes = !detail?.minutesText.isNullOrBlank(),
                    )
                } else {
                    TranscriptPane(turns = detail?.transcript?.turns.orEmpty(), onSentenceClick = onSentenceClick)
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
    hasMinutes: Boolean,
) {
    when {
        minutesFailure != null -> Box(Modifier.fillMaxSize().padding(24.dp)) {
            Text(stringResource(R.string.detail_minutes_parse_failed, failureText(minutesFailure)))
        }

        minutes == null && !hasMinutes -> Box(Modifier.fillMaxSize().padding(24.dp)) {
            Text(stringResource(R.string.detail_no_minutes))
        }

        minutes == null -> Box(Modifier.fillMaxSize().padding(24.dp)) {
            Text(stringResource(R.string.detail_no_minutes))
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

@Composable
private fun TranscriptPane(turns: List<Turn>, onSentenceClick: (ULong) -> Unit) {
    if (turns.isEmpty()) {
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
            }
        }
    }
}

@Composable
private fun failureText(kind: FailureKind): String = when (kind) {
    FailureKind.INVALID_INPUT -> stringResource(R.string.error_invalid_input)
    FailureKind.MISSING -> stringResource(R.string.error_missing)
    FailureKind.MALFORMED -> stringResource(R.string.error_malformed)
    FailureKind.PERMISSION_DENIED -> stringResource(R.string.error_permission_denied)
    FailureKind.INTERNAL -> stringResource(R.string.error_internal)
}
