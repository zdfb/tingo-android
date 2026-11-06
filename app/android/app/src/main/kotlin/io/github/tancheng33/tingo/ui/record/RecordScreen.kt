package io.github.tancheng33.tingo.ui.record

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.ui.clockOf

/**
 * 录音页：开始 / 暂停 / 继续 / 结束，外加一个电平条。
 *
 * 权限在这里请求而不是在启动时一次性要：只在用户真的要录音时才打扰他，
 * 拒绝之后仍然可以浏览会议库。
 */
@Composable
fun RecordScreen(
    state: RecordUiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
    onOpenMeeting: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionDenied = !granted
        if (granted) onStart()
    }

    fun startWithPermission() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) onStart() else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            when (state) {
                RecordUiState.Idle -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.record_idle_hint), style = MaterialTheme.typography.bodyMedium)
                    if (permissionDenied) {
                        Text(
                            stringResource(R.string.record_permission_denied),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(onClick = ::startWithPermission) {
                        Text(stringResource(R.string.record_start))
                    }
                }

                is RecordUiState.Recording -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        clockOf(state.elapsedMs.toULong()),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    LinearProgressIndicator(
                        progress = { state.level },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onPause) { Text(stringResource(R.string.record_pause)) }
                        Button(onClick = onStop) { Text(stringResource(R.string.record_stop)) }
                    }
                    TextButton(onClick = onDiscard) { Text(stringResource(R.string.record_discard)) }
                }

                is RecordUiState.Paused -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        clockOf(state.elapsedMs.toULong()),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(stringResource(R.string.record_paused_hint), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onResume) { Text(stringResource(R.string.record_resume)) }
                        OutlinedButton(onClick = onStop) { Text(stringResource(R.string.record_stop)) }
                    }
                    TextButton(onClick = onDiscard) { Text(stringResource(R.string.record_discard)) }
                }

                is RecordUiState.Finished -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.record_finished), style = MaterialTheme.typography.titleMedium)
                    Text(
                        clockOf(state.durationMs.toULong()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { onOpenMeeting(state.meetingId) }) {
                        Text(stringResource(R.string.record_open_meeting))
                    }
                }

                is RecordUiState.Failed -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.error_title), style = MaterialTheme.typography.titleMedium)
                    Text(state.reason, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = ::startWithPermission) { Text(stringResource(R.string.record_start)) }
                }
            }
        }
    }
}
