package io.github.tancheng33.tingo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tancheng33.tingo.R

/**
 * "先下载模型、再跑一段耗时任务"的状态条模型。
 *
 * 转写与纪要生成是同一种形状（缺模型 → 下载/运行中 → 成功/失败），
 * 这套状态只定义一次，两个界面各自把自己的状态映射过来。
 */
sealed interface TaskStatus {
    data object Idle : TaskStatus

    /** @param missingMb 还要下载多少 MB */
    data class NeedModel(val missingMb: Long) : TaskStatus

    /** @param fraction null 表示"在跑但没法量化进度" */
    data class Running(val fraction: Float?, val labelRes: Int) : TaskStatus

    data object Done : TaskStatus

    data class Failed(val message: String) : TaskStatus
}

/**
 * @param actionLabel 按钮文案（缺模型/失败时用）
 * @param idleAction 空闲时是否也给一个"开始"按钮（已有结果时由调用方传 false）
 */
@Composable
fun TaskStatusBar(
    status: TaskStatus,
    actionLabel: String,
    onAction: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    idleAction: Boolean = false,
) {
    when (status) {
        TaskStatus.Idle -> if (idleAction) {
            Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }

        is TaskStatus.NeedModel -> Column(
            modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.task_need_model, status.missingMb),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onAction) { Text(actionLabel) }
        }

        is TaskStatus.Running -> Column(
            modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val fraction = status.fraction
            if (fraction == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
            Text(stringResource(status.labelRes), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }

        TaskStatus.Done -> Unit

        is TaskStatus.Failed -> Column(
            modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                status.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
