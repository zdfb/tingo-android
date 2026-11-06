package io.github.tancheng33.tingo.ui.record

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tancheng33.tingo.R

/**
 * 录音页占位。
 *
 * 采集链路（麦克风 / 屏幕内录 / 前台服务）由后续变更实现，这里明确告诉用户
 * 当前版本还不能录，而不是给一个按下没有反应的按钮。
 */
@Composable
fun RecordScreen(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.record_placeholder),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
