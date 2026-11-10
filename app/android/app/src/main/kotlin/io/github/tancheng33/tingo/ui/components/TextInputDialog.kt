package io.github.tancheng33.tingo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * 改标题、改标签、改人名共用一个输入框。
 *
 * 三处都要"一行字 + 保存/取消"，各写一份只会让样式与快捷键慢慢分叉——
 * 曾经就是这样：一处有占位提示，一处没有；一处允许保存空值，一处不允许。
 */
@Composable
fun TextInputDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initial: String = "",
    hint: String = "",
    /**
     * 空值没有意义的地方（批量加/移除标签）设为 true：按钮按下去也不该悄悄存个空串。
     *
     * 注意别顺手给"改标题""改人名"打开：那两处**留空是有意义的行为**
     * （用纪要标题、恢复"说话人N"），挡住了就等于删掉一个功能。
     */
    requireNonBlank: Boolean = false,
) {
    val colors = TingoTheme.colors
    var text by remember { mutableStateOf(initial) }
    val blocked = requireNonBlank && text.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text(title, color = colors.text) },
        text = {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.text),
                cursorBrush = SolidColor(colors.blue),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.seg, TingoDimens.controlShape)
                    .padding(12.dp)
                    // 真机 UI 测试按文字找不到输入框：给它一个无障碍名。
                    // 用 hint 而不是 title，免得读屏把对话框标题再念一遍
                    .semantics { contentDescription = hint.ifEmpty { title } },
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty() && hint.isNotEmpty()) {
                            Text(hint, style = MaterialTheme.typography.bodyMedium, color = colors.faint)
                        }
                        inner()
                    }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { if (!blocked) onConfirm(text.trim()) }, enabled = !blocked) {
                Text(stringResource(R.string.action_save), color = if (blocked) colors.muted else colors.blue)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = colors.muted) }
        },
    )
}
