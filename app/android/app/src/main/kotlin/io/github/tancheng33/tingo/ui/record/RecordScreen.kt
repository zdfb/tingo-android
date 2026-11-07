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
import androidx.compose.material3.Switch
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
    /** 用户是否勾选"同时录下会议软件的声音" */
    systemAudioEnabled: Boolean = false,
    onSystemAudioChange: (Boolean) -> Unit = {},
    /** 系统声音这一路是否真的在录；false 表示没抓到（会议软件不允许抓通常如此） */
    systemAudioActive: Boolean = false,
    /** 用户拒绝了屏幕录制授权 */
    systemAudioDenied: Boolean = false,
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

    // 通知权限只影响"看得见录音状态"，没有它录音照样能进行
    var notificationsDenied by remember { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // 没有通知权限录音照样能进行，但状态栏就看不见了——必须说清楚
        notificationsDenied = !granted
    }

    fun startWithPermission() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val notifications = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!notifications) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        onStart()
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
                    if (notificationsDenied) {
                        Text(
                            stringResource(R.string.record_notification_denied),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (systemAudioDenied) {
                        Text(
                            stringResource(R.string.record_system_audio_denied),
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
                    if (systemAudioEnabled && !systemAudioActive) {
                        Text(
                            text = stringResource(R.string.record_system_audio_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
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
                    Text(
                        text = if (state.detail.isNullOrBlank()) {
                            stringResource(state.reasonRes)
                        } else {
                            stringResource(state.reasonRes, state.detail)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = ::startWithPermission) { Text(stringResource(R.string.record_start)) }
                }
            }
        }
    }
}

/**
 * "同时录下会议软件的声音"开关。
 *
 * 只在 Android 10 以上出现；低版本没有 AudioPlaybackCapture，与其给个点不动的开关，
 * 不如直接说清楚为什么没有。
 */
@Composable
private fun SystemAudioToggle(enabled: Boolean, supported: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Switch(checked = enabled && supported, enabled = supported, onCheckedChange = onChange)
        Column {
            Text(stringResource(R.string.record_system_audio_label), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(
                    if (supported) R.string.record_system_audio_hint else R.string.record_system_audio_unsupported,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
