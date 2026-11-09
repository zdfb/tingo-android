package io.github.tancheng33.tingo.ui.record

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import io.github.tancheng33.tingo.ui.theme.TingoColors
import io.github.tancheng33.tingo.ui.theme.TingoDimens
import io.github.tancheng33.tingo.ui.theme.TingoTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import io.github.tancheng33.tingo.recording.SystemAudioSupport
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
    /** 试过抓系统声音但没抓到（会议软件不允许抓通常如此） */
    systemAudioMissing: Boolean = false,
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
    // 权限可能在系统设置里被重新打开：每次回到前台都重新判定一次，别把旧结论一直挂着
    var notificationsDenied by remember { mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                notificationsDenied = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
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
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.record_idle_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TingoTheme.colors.muted,
                    )
                    WarningLine(
                        text = stringResource(R.string.record_permission_denied),
                        visible = permissionDenied,
                        emphasis = true,
                    )
                    WarningLine(
                        text = stringResource(R.string.record_system_audio_denied),
                        visible = systemAudioDenied,
                        emphasis = true,
                    )
                    // 系统内录开关：之前写完忘了渲染，等于用户根本没有这个选项
                    SystemAudioToggle(
                        enabled = systemAudioEnabled,
                        supported = android.os.Build.VERSION.SDK_INT >= SystemAudioSupport.MIN_SDK,
                        onChange = onSystemAudioChange,
                    )
                    PrimaryPill(
                        label = stringResource(R.string.record_start),
                        onClick = ::startWithPermission,
                    )
                }

                is RecordUiState.Recording -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TimerText(clockOf(state.elapsedMs.toULong()))
                    LevelBar(level = state.level)
                    WarningLine(
                        text = stringResource(R.string.record_system_audio_missing),
                        visible = systemAudioMissing,
                        emphasis = true,
                    )
                    // 只在真的在录的时候提：没有通知权限时状态栏看不到进度，用户会以为没在录
                    WarningLine(
                        text = stringResource(R.string.record_notification_denied),
                        visible = notificationsDenied,
                        emphasis = false,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(TingoDimens.gap)) {
                        GhostPill(label = stringResource(R.string.record_pause), onClick = onPause)
                        PrimaryPill(label = stringResource(R.string.record_stop), onClick = onStop)
                    }
                    DiscardLink(onClick = onDiscard)
                }

                is RecordUiState.Paused -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TimerText(clockOf(state.elapsedMs.toULong()))
                    Text(
                        text = stringResource(R.string.record_paused_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = TingoTheme.colors.muted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(TingoDimens.gap)) {
                        PrimaryPill(label = stringResource(R.string.record_resume), onClick = onResume)
                        GhostPill(label = stringResource(R.string.record_stop), onClick = onStop)
                    }
                    DiscardLink(onClick = onDiscard)
                }

                is RecordUiState.Finished -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.record_finished),
                        style = MaterialTheme.typography.titleMedium,
                        color = TingoTheme.colors.text,
                    )
                    Text(
                        text = clockOf(state.durationMs.toULong()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TingoTheme.colors.muted,
                    )
                    PrimaryPill(
                        label = stringResource(R.string.record_open_meeting),
                        onClick = { onOpenMeeting(state.meetingId) },
                    )
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
                    PrimaryPill(
                        label = stringResource(R.string.record_start),
                        onClick = ::startWithPermission,
                    )
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
    val colors = TingoTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TingoDimens.gap),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.card, TingoDimens.cardShape)
            .border(BorderStroke(TingoDimens.hairline, colors.line), TingoDimens.cardShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.record_system_audio_label),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
            )
            Text(
                text = stringResource(
                    if (supported) R.string.record_system_audio_hint else R.string.record_system_audio_unsupported,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = colors.muted,
            )
        }
        Switch(
            checked = enabled && supported,
            enabled = supported,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.blue,
                checkedThumbColor = colors.card,
                uncheckedTrackColor = colors.seg,
                uncheckedThumbColor = colors.faint,
            ),
        )
    }
}

/**
 * 一行提示：给就能看见，不给就什么都不画。
 *
 * 录音页有好几种"降级了但还能用"的情况（没通知权限、没系统声音、没麦克风权限），
 * 各自写一遍 Text + 颜色太重复，这里收成一处。
 *
 * @param emphasis true 表示这次录音确实少了东西（红字）；false 表示只是提醒（次要色）
 */
@Composable
private fun WarningLine(text: String, visible: Boolean, emphasis: Boolean) {
    if (!visible) return
    val colors = TingoTheme.colors
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (emphasis) colors.red else colors.muted,
        modifier = Modifier
            .background(if (emphasis) colors.red.copy(alpha = 0.08f) else colors.seg, TingoDimens.controlShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** 黑色胶囊主按钮：桌面版的主按钮就是这个样子（黑底白字、圆角拉满）。 */
@Composable
private fun PrimaryPill(label: String, onClick: () -> Unit) {
    val colors = TingoTheme.colors
    Box(
        modifier = Modifier
            .height(52.dp)
            .background(colors.black, TingoDimens.pillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            color = if (colors.black == TingoColors.black) androidx.compose.ui.graphics.Color.White else colors.bg,
        )
    }
}

/** 次要动作：描边胶囊。 */
@Composable
private fun GhostPill(label: String, onClick: () -> Unit) {
    val colors = TingoTheme.colors
    Box(
        modifier = Modifier
            .height(52.dp)
            .border(BorderStroke(TingoDimens.hairline, colors.line), TingoDimens.pillShape)
            .background(colors.card, TingoDimens.pillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = TextStyle(fontSize = 15.sp), color = colors.text2)
    }
}

@Composable
private fun DiscardLink(onClick: () -> Unit) {
    val colors = TingoTheme.colors
    Text(
        text = stringResource(R.string.record_discard),
        style = MaterialTheme.typography.labelLarge,
        color = colors.faint,
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp),
    )
}

/** 计时：大号、字距收紧、等宽数字，读秒不跳。 */
@Composable
private fun TimerText(text: String) {
    val colors = TingoTheme.colors
    Text(
        text = text,
        style = TextStyle(
            fontSize = 52.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-1.2).sp,
            fontFeatureSettings = "tnum",
        ),
        color = colors.text,
    )
}

/** 电平：一根细条，安静时不晃眼。 */
@Composable
private fun LevelBar(level: Float) {
    val colors = TingoTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(colors.seg, TingoDimens.pillShape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .background(colors.blue, TingoDimens.pillShape),
        )
    }
}
