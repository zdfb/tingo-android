package io.github.tancheng33.tingo.ui.record

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import io.github.tancheng33.tingo.data.MeetingCard
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import io.github.tancheng33.tingo.ui.components.PillButton
import io.github.tancheng33.tingo.ui.components.PillKind
import io.github.tancheng33.tingo.ui.theme.TingoDimens
import io.github.tancheng33.tingo.ui.theme.TingoTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
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
import io.github.tancheng33.tingo.ui.tasks.RunningTask
import io.github.tancheng33.tingo.ui.tasks.TaskKind
import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.recording.SystemAudioSupport
import io.github.tancheng33.tingo.ui.clockOf

/**
 * 录音页：开始 / 暂停 / 继续 / 结束，外加一个电平条。
 *
 * 权限在这里请求而不是在启动时一次性要：只在用户真的要录音时才打扰他，
 * 拒绝之后仍然可以浏览会议库。
 */
/**
 * 首页底部的一行状态：对应桌面侧栏的环境块。
 *
 * @param level 决定圆点颜色；[StatusLevel.None] 表示正常
 */
data class HomeStatus(val text: HomeStatusText, val level: StatusLevel = StatusLevel.None)

/**
 * 首页状态行的文案来源。
 *
 * 原来这里是拼好的中文字符串，切到英文界面后首页仍是中文（设置页与别处都翻了）。
 * 改成"先给出是什么状态"，文案由界面按当前语言取。
 */
sealed interface HomeStatusText {
    data object ModelsReady : HomeStatusText

    data object ModelsMissing : HomeStatusText

    /** 已配置纪要服务：显示模型名（用户自己填的，不翻译） */
    data class ModelName(val name: String) : HomeStatusText

    data object LlmMissing : HomeStatusText

    data class Version(val name: String) : HomeStatusText
}

enum class StatusLevel { None, Warn, Bad }

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
    /** 最近几场会议：桌面端侧栏的"最近"在手机上落在录制页下方 */
    recent: List<MeetingCard> = emptyList(),
    onOpenRecent: (String) -> Unit = {},
    /** 底部状态行：识别模型、纪要服务、版本，对应桌面侧栏的环境块 */
    statusRows: List<HomeStatus> = emptyList(),
    /** 导入已有录音：桌面端是把文件拖进窗口，手机上是选文件 */
    onPickAudio: () -> Unit = {},
    /** 上一场是"没人说话"自动结束的（分钟数）；null 表示不是自动结束 */
    autoStoppedAfter: Int? = null,
    /** 正在后台跑的长任务（转写、纪要、导入）：桌面对应侧栏那块"进行中任务" */
    runningTasks: List<RunningTask> = emptyList(),
    onOpenTask: (String) -> Unit = {},
    /** 导入进度 0..1；null 表示没有在导入 */
    importProgress: Float? = null,
    /** 这一次导入失败的原因（格式不支持 / 读不出来），null 表示没失败 */
    importFailure: RecordViewModel.ImportFailure? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 麦克风权限的结果由外壳层决定（它负责申请与启动），这里只负责把"没授权"如实说出来
    val permissionDenied = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED
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
                    PillButton(
                        label = stringResource(R.string.record_start),
                        onClick = onStart,
                    )
                    if (autoStoppedAfter != null) {
                        // 自动停录要说明白：否则用户回来看见"没在录"会以为应用崩了
                        Text(
                            text = stringResource(R.string.record_auto_stopped, autoStoppedAfter),
                            style = MaterialTheme.typography.bodySmall,
                            color = TingoTheme.colors.muted,
                        )
                    }
                    ImportEntry(
                        progress = importProgress,
                        failure = importFailure,
                        onPick = onPickAudio,
                    )
                    RunningTasks(tasks = runningTasks, onOpen = onOpenTask)
                    RecentAndStatus(recent = recent, statusRows = statusRows, onOpenRecent = onOpenRecent)
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
                        PillButton(label = stringResource(R.string.record_pause), onClick = onPause, kind = PillKind.Ghost)
                        PillButton(label = stringResource(R.string.record_stop), onClick = onStop)
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
                        PillButton(label = stringResource(R.string.record_resume), onClick = onResume)
                        PillButton(label = stringResource(R.string.record_stop), onClick = onStop)
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
                    PillButton(
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
                    PillButton(
                        label = stringResource(R.string.record_start),
                        onClick = onStart,
                    )
                    if (autoStoppedAfter != null) {
                        // 自动停录要说明白：否则用户回来看见"没在录"会以为应用崩了
                        Text(
                            text = stringResource(R.string.record_auto_stopped, autoStoppedAfter),
                            style = MaterialTheme.typography.bodySmall,
                            color = TingoTheme.colors.muted,
                        )
                    }
                    ImportEntry(
                        progress = importProgress,
                        failure = importFailure,
                        onPick = onPickAudio,
                    )
                    RunningTasks(tasks = runningTasks, onOpen = onOpenTask)
                    RecentAndStatus(recent = recent, statusRows = statusRows, onOpenRecent = onOpenRecent)
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
/**
 * 导入入口 + 进度 + 结果。
 *
 * 桌面端是把音频拖进窗口；手机上是一个按钮加系统选择器。
 * 一小时的录音解码加转写要几分钟，没有进度条用户会以为卡死了；
 * 失败也要分原因说清楚（格式不支持 vs 读不出来），而不是一句"失败了"。
 */
@Composable
private fun ImportEntry(
    progress: Float?,
    failure: RecordViewModel.ImportFailure?,
    onPick: () -> Unit,
) {
    val colors = TingoTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PillButton(
            label = stringResource(R.string.record_import),
            onClick = onPick,
            kind = PillKind.Ghost,
            enabled = progress == null,
        )
        when {
            progress != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.widthIn(max = 240.dp),
            ) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.record_import_progress, (progress * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }

            failure != null -> Text(
                text = stringResource(
                    when (failure) {
                        RecordViewModel.ImportFailure.UNSUPPORTED -> R.string.record_import_unsupported
                        RecordViewModel.ImportFailure.UNREADABLE -> R.string.record_import_failed
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.red,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

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

/**
 * 首页的"最近"与状态行。
 *
 * 桌面端这两块在侧栏（"最近"列表 + 环境块）；手机没有侧栏，放在录制按钮下方，
 * 顺手能给"上一次会议在哪"和"现在能不能生成纪要"一个一眼可见的答案。
 */
@Composable
private fun RecentAndStatus(
    recent: List<MeetingCard>,
    statusRows: List<HomeStatus>,
    onOpenRecent: (String) -> Unit,
) {
    val colors = TingoTheme.colors
    if (recent.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.home_recent),
                style = MaterialTheme.typography.labelMedium,
                color = colors.muted,
            )
            recent.forEach { card ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.card, TingoDimens.cardShape)
                        .border(BorderStroke(TingoDimens.hairline, colors.line), TingoDimens.cardShape)
                        .clickable { onOpenRecent(card.id) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = card.title.ifBlank { card.id },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.text,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            text = listOfNotNull(card.date.takeIf { it.isNotBlank() }, card.time.takeIf { it.isNotBlank() })
                                .joinToString(" "),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.muted,
                        )
                    }
                }
            }
        }
    }
    if (statusRows.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            statusRows.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                when (row.level) {
                                    StatusLevel.None -> colors.green
                                    StatusLevel.Warn -> colors.amber
                                    StatusLevel.Bad -> colors.red
                                },
                            ),
                    )
                    Text(
                        text = when (val text = row.text) {
                            HomeStatusText.ModelsReady -> stringResource(R.string.home_models_ready)
                            HomeStatusText.ModelsMissing -> stringResource(R.string.home_models_missing)
                            HomeStatusText.LlmMissing -> stringResource(R.string.home_llm_missing)
                            is HomeStatusText.ModelName -> text.name
                            is HomeStatusText.Version -> "v${text.name}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.muted,
                    )
                }
            }
        }
    }
}

/**
 * "进行中"：谁在跑、跑到哪。
 *
 * 桌面把这块放在侧栏，手机上一离开详情页就看不见了——而转写要跑几分钟，
 * 正是最需要"它在动"的时刻。没有进度数字的（纪要）就只显示在写，不编一个百分比。
 */
@Composable
private fun RunningTasks(tasks: List<RunningTask>, onOpen: (String) -> Unit) {
    if (tasks.isEmpty()) return
    val colors = TingoTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.record_running_tasks, tasks.size),
            style = MaterialTheme.typography.titleSmall,
            color = colors.text,
        )
        tasks.forEach { task ->
            // 用 key 绑定行身份：任务完成时会从中间抽走一行，没有 key 会让进度条串到别的行上
            key(task.key) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.card, TingoDimens.cardShape)
                    .clickable { onOpen(task.meetingId) }
                    .padding(TingoDimens.gapTight),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.text,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(task.kind.labelRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                    )
                }
                val fraction = task.fraction
                if (fraction == null) {
                    // 没有百分比就不画进度条：一条停在 0 的条比没有更让人以为卡住了
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
            }
            }
        }
    }
}

/** 任务种类对应的界面文案。 */
private fun TaskKind.labelRes(): Int = when (this) {
    TaskKind.TRANSCRIBE -> R.string.task_transcribe
    TaskKind.MINUTES -> R.string.task_minutes
}
