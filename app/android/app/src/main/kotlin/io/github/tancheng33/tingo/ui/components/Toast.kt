package io.github.tancheng33.tingo.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.tancheng33.tingo.ui.theme.TingoDimens
import io.github.tancheng33.tingo.ui.theme.TingoTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一条一闪而过的提示。
 *
 * @param id 递增编号：同样的文字连续出现两次也要能重新计时
 */
data class ToastMessage(val id: Long, val text: String)

/**
 * 全局提示的唯一出处（对应桌面端的 toast）。
 *
 * 原来每页各自在内容区顶部放一条错误条：用户滚到下面时看不到，切页就消失，
 * 而且同一类错误在四五个地方各写一遍。现在统一成"底部一条，几秒后自己走"，
 * 由外壳画一次。
 */
class ToastCenter {

    private val _current = MutableStateFlow<ToastMessage?>(null)
    val current: StateFlow<ToastMessage?> = _current.asStateFlow()

    private var nextId = 0L

    fun show(text: String) {
        if (text.isBlank()) return
        nextId += 1
        _current.value = ToastMessage(nextId, text)
    }

    /** 用户滑掉或计时结束：只清掉当前这条，不误伤后来的。 */
    fun dismiss(id: Long) {
        if (_current.value?.id == id) _current.value = null
    }
}

/** 提示停留时间：够读完一句话，又不至于挡着操作。 */
private const val TOAST_MS = 3_000L

/**
 * 画在内容区底部的一条提示。
 *
 * 自己计时消失：调用方只管 `show()`，不用在每处都挂一个 `LaunchedEffect`。
 */
@Composable
fun ToastHost(center: ToastCenter, modifier: Modifier = Modifier) {
    val message by center.current.collectAsState()
    val colors = TingoTheme.colors
    // 换一条就重开计时：同样的文字连点两次也要重新计时，否则第二条会立刻消失
    LaunchedEffect(message?.id) {
        val id = message?.id ?: return@LaunchedEffect
        delay(TOAST_MS)
        center.dismiss(id)
    }
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Text(
                text = message?.text.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
                modifier = Modifier
                    .padding(TingoDimens.gutter)
                    .fillMaxWidth()
                    .background(colors.seg, TingoDimens.cardShape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}
