package io.github.tancheng33.tingo.ui.onboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.recording.CaptureProbeResult
import io.github.tancheng33.tingo.ui.components.PillButton
import io.github.tancheng33.tingo.ui.components.PillKind
import io.github.tancheng33.tingo.ui.components.TingoPageTitle
import io.github.tancheng33.tingo.ui.theme.TingoDimens
import io.github.tancheng33.tingo.ui.theme.TingoTheme

/**
 * 新手引导：五步，每步都能跳过。
 *
 * 与桌面 `welcome.ts` 同一套步骤与文案口径（认识 Tingo → 识别模型 → 录音权限 →
 * 大模型 → 示例会议）。差别在于内容：手机的识别模型是随包内置的，
 * 所以第二步通常是"已就绪"，而不是"下载 2 GB"。
 */
@Composable
fun OnboardingScreen(
    state: OnboardingViewModel.UiState,
    steps: List<OnboardingViewModel.Step>,
    llmConfigured: Boolean,
    micGranted: Boolean,
    systemAudioSupported: Boolean,
    testing: Boolean,
    probe: CaptureProbeResult?,
    onTest: () -> Unit,
    onRequestPermission: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onInstallDemo: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TingoTheme.colors
    val index = steps.indexOf(state.step)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StepBar(steps = steps, index = index)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.step) {
                OnboardingViewModel.Step.HELLO -> Welcome()
                OnboardingViewModel.Step.MODEL -> ModelStep(ready = state.modelReady)
                OnboardingViewModel.Step.PERMISSION -> PermissionStep(
                    micGranted = micGranted,
                    systemAudioSupported = systemAudioSupported,
                    testing = testing,
                    probe = probe,
                    onTest = onTest,
                    onRequestPermission = onRequestPermission,
                )
                OnboardingViewModel.Step.LLM -> LlmStep(configured = llmConfigured, onOpenSettings = onOpenSettings)
                OnboardingViewModel.Step.DEMO -> DemoStep(
                    installing = state.installingDemo,
                    failed = state.demoFailed,
                    onInstall = onInstallDemo,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            PillButton(label = stringResource(R.string.onboard_skip), onClick = onSkip, kind = PillKind.Ghost)
            Spacer(Modifier.weight(1f))
            if (index > 0) {
                PillButton(label = stringResource(R.string.onboard_back), onClick = onBack, kind = PillKind.Ghost)
            }
            PillButton(
                label = stringResource(
                    if (index == steps.lastIndex) R.string.onboard_start else R.string.onboard_next,
                ),
                onClick = onNext,
                enabled = !state.installingDemo,
            )
        }
    }
}

/** 顶部的步骤指示：走过的打勾、当前的高亮。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepBar(steps: List<OnboardingViewModel.Step>, index: Int) {
    val colors = TingoTheme.colors
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        steps.forEachIndexed { position, step ->
            val done = position < index
            val now = position == index
            Row(
                modifier = Modifier
                    .clip(TingoDimens.pillShape)
                    .background(if (now) colors.blueSoft else colors.seg)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(if (now) colors.blue else colors.card, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (done) "✓" else (position + 1).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (now) colors.onBlack else if (done) colors.green else colors.muted,
                    )
                }
                Text(
                    text = label(step),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (now) colors.blue else colors.muted,
                )
            }
        }
    }
}

@Composable
private fun label(step: OnboardingViewModel.Step): String = stringResource(
    when (step) {
        OnboardingViewModel.Step.HELLO -> R.string.onboard_step_hello
        OnboardingViewModel.Step.MODEL -> R.string.onboard_step_model
        OnboardingViewModel.Step.PERMISSION -> R.string.onboard_step_permission
        OnboardingViewModel.Step.LLM -> R.string.onboard_step_llm
        OnboardingViewModel.Step.DEMO -> R.string.onboard_step_demo
    },
)

@Composable
private fun Welcome() {
    val colors = TingoTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.tingo_mark),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.size(72.dp),
        )
        TingoPageTitle(text = stringResource(R.string.onboard_hello_title))
        Text(
            stringResource(R.string.onboard_hello_body),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.text2,
        )
        Spacer(Modifier.size(4.dp))
        Point(stringResource(R.string.onboard_point_local_title), stringResource(R.string.onboard_point_local_body))
        Point(stringResource(R.string.onboard_point_record_title), stringResource(R.string.onboard_point_record_body))
        Point(stringResource(R.string.onboard_point_source_title), stringResource(R.string.onboard_point_source_body))
    }
}

@Composable
private fun Point(title: String, body: String) {
    val colors = TingoTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(8.dp)
                .background(colors.blue, CircleShape),
        )
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.text)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        }
    }
}

@Composable
private fun ModelStep(ready: Boolean) {
    val colors = TingoTheme.colors
    TingoPageTitle(text = stringResource(R.string.onboard_step_model))
    Text(
        text = stringResource(if (ready) R.string.onboard_model_ready else R.string.onboard_model_missing),
        style = MaterialTheme.typography.bodyMedium,
        color = if (ready) colors.green else colors.red,
    )
    Text(
        stringResource(R.string.onboard_model_body),
        style = MaterialTheme.typography.bodySmall,
        color = colors.muted,
    )
}

@Composable
private fun PermissionStep(
    micGranted: Boolean,
    systemAudioSupported: Boolean,
    testing: Boolean,
    probe: CaptureProbeResult?,
    onTest: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val colors = TingoTheme.colors
    TingoPageTitle(text = stringResource(R.string.onboard_step_permission))
    Status(
        label = stringResource(R.string.onboard_perm_mic),
        ok = micGranted,
        detail = stringResource(if (micGranted) R.string.onboard_perm_granted else R.string.onboard_perm_ask_later),
    )
    // Android 录系统声音要么靠系统内录（不同机型支持不一样），要么靠扬声器回采；
    // 这里只说明"能不能录"，具体权限在真正开录时才请求
    Status(
        label = stringResource(R.string.onboard_perm_system),
        ok = systemAudioSupported,
        detail = stringResource(
            if (systemAudioSupported) R.string.onboard_perm_system_ok else R.string.onboard_perm_system_limited,
        ),
    )
    Text(
        stringResource(R.string.onboard_perm_body),
        style = MaterialTheme.typography.bodySmall,
        color = colors.muted,
    )
    // 桌面这一步是真的录 3 秒看有没有声音；只写"已授权"骗不过设备被占用、
    // 或系统输入被静音这类常见故障
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton(
            label = stringResource(if (testing) R.string.onboard_test_running else R.string.onboard_test_mic),
            onClick = onTest,
            kind = PillKind.Ghost,
            enabled = !testing,
        )
        // 没授权时试录必然失败：直接给授权入口，别让人去猜"该去哪开"
        if (!micGranted) {
            PillButton(
                label = stringResource(R.string.onboard_perm_request),
                onClick = onRequestPermission,
                kind = PillKind.Ghost,
            )
        }
    }
    probe?.let { result ->
        val text = when {
            result.failure != null -> stringResource(R.string.onboard_test_failed)
            result.heardSomething -> stringResource(R.string.onboard_test_heard, result.seconds)
            else -> stringResource(R.string.onboard_test_silent, result.seconds)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            // 三态三色：正常绿、没声音琥珀、出错红——前两种颜色一样时，
            // "麦克风坏了"会被当成"房间太安静"
            color = when {
                result.failure != null -> colors.red
                result.heardSomething -> colors.green
                else -> colors.amber
            },
        )
    }
    // 系统内录要过一次系统"录屏"授权，引导里弹它只会让人困惑：直说在录音页才问
    Text(
        stringResource(R.string.onboard_test_system_note),
        style = MaterialTheme.typography.bodySmall,
        color = colors.faint,
    )
}

@Composable
private fun Status(label: String, ok: Boolean, detail: String) {
    val colors = TingoTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (ok) "✓" else "!", color = if (ok) colors.green else colors.amber)
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.text)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
    }
}

@Composable
private fun LlmStep(configured: Boolean, onOpenSettings: () -> Unit) {
    val colors = TingoTheme.colors
    TingoPageTitle(text = stringResource(R.string.onboard_step_llm))
    Text(
        text = stringResource(if (configured) R.string.onboard_llm_ready else R.string.onboard_llm_missing),
        style = MaterialTheme.typography.bodyMedium,
        color = if (configured) colors.green else colors.amber,
    )
    Text(
        stringResource(R.string.onboard_llm_body),
        style = MaterialTheme.typography.bodySmall,
        color = colors.muted,
    )
    if (!configured) {
        PillButton(
            label = stringResource(R.string.onboard_llm_open_settings),
            onClick = onOpenSettings,
            kind = PillKind.Soft,
        )
    }
}

@Composable
private fun DemoStep(installing: Boolean, failed: Boolean, onInstall: () -> Unit) {
    val colors = TingoTheme.colors
    TingoPageTitle(text = stringResource(R.string.onboard_step_demo))
    Text(
        stringResource(R.string.onboard_demo_body),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.muted,
    )
    PillButton(
        label = stringResource(if (installing) R.string.onboard_demo_installing else R.string.onboard_demo_install),
        onClick = onInstall,
        kind = PillKind.Soft,
        enabled = !installing,
    )
    if (failed) {
        Text(
            stringResource(R.string.onboard_demo_failed),
            style = MaterialTheme.typography.bodySmall,
            color = colors.red,
        )
    }
}
