package io.github.tancheng33.tingo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.ui.theme.TingoDimens
import io.github.tancheng33.tingo.ui.theme.TingoTheme

/**
 * 全应用统一的弹层外观。
 *
 * 各页原来各自写 `AlertDialog`，结果配色慢慢分叉：有的忘了传 `containerColor`，
 * 弹出来是 Material 默认的浅紫底配深色字，深色主题下几乎看不清。这里把
 * 底色、标题色、按钮色、留白一次定好，各页只给内容。
 *
 * @param confirm 主按钮（保存/删除/确定）
 * @param dismiss 次按钮；null 表示只给一个"关闭"
 */
@Composable
fun TingoDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: @Composable () -> Unit,
    dismiss: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = TingoTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text(title, color = colors.text, style = MaterialTheme.typography.titleMedium) },
        text = { content() },
        confirmButton = confirm,
        dismissButton = {
            if (dismiss != null) {
                dismiss()
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close), color = colors.muted)
                }
            }
        },
    )
}

/**
 * 确认框：一句话说明后果 + 取消/确认。
 *
 * @param destructive 删除类动作：确认按钮用红色，别让它看起来和"保存"一样安全
 */
@Composable
fun TingoConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val colors = TingoTheme.colors
    TingoDialog(
        title = title,
        onDismiss = onDismiss,
        confirm = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) colors.red else colors.blue)
            }
        },
        dismiss = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = colors.muted)
            }
        },
    ) {
        Text(body, color = colors.muted, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 一段可滚动（或可编辑）的长文本弹层：历史版本预览、纪要编辑都用它。
 *
 * 单独一个组件是因为这里最容易出错：不设最大高度会顶满整屏、不设滚动
 * 长内容就看不全。
 */
@Composable
fun TingoTextDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    editable: Boolean = false,
    onTextChange: (String) -> Unit = {},
    contentDescription: String? = null,
) {
    val colors = TingoTheme.colors
    TingoDialog(
        title = title,
        onDismiss = onDismiss,
        confirm = {
            if (confirmLabel != null && onConfirm != null) {
                TextButton(onClick = onConfirm) { Text(confirmLabel, color = colors.blue) }
            }
        },
    ) {
        if (editable) {
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.text),
                cursorBrush = SolidColor(colors.blue),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 360.dp)
                    .background(colors.seg, TingoDimens.controlShape)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
                    .then(
                        if (contentDescription == null) {
                            Modifier
                        } else {
                            Modifier.semanticsLabel(contentDescription)
                        },
                    ),
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = colors.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

/** 给真机 UI 测试一个稳定的定位名（Compose 的画布节点没有文本可抓）。 */
private fun Modifier.semanticsLabel(label: String): Modifier = this.semantics { contentDescription = label }
