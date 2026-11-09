package io.github.tancheng33.tingo.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tancheng33.tingo.ui.theme.TingoDimens
import io.github.tancheng33.tingo.ui.theme.TingoTheme

/**
 * 胶囊控件。桌面版只有两种按钮（黑色主按钮、描边次按钮）加一种小筛选胶囊，
 * 这里各收成一个组件：颜色、圆角、最小触控高度、禁用态、无障碍语义都在这一处定义。
 *
 * 之前每个页面各写一遍 Box + clickable，结果是四处不一致：有的 44dp、有的 52dp，
 * 有的丢了 Role/selected，禁用时还看不出来（点了没反应）。收成一个组件就不会再漂。
 */

enum class PillKind { Primary, Ghost, Soft }

@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: PillKind = PillKind.Primary,
    enabled: Boolean = true,
) {
    val colors = TingoTheme.colors
    // 禁用态必须"看得见"：桌面版 .btn[disabled] 是 opacity .45，手机上没有透明度就靠淡色
    val alpha = if (enabled) 1f else 0.45f
    val background = when (kind) {
        PillKind.Primary -> colors.black.copy(alpha = if (enabled) 1f else 0.35f)
        PillKind.Ghost -> colors.card
        PillKind.Soft -> colors.blueSoft
    }
    val content = when (kind) {
        PillKind.Primary -> colors.onBlack
        PillKind.Ghost -> colors.text2
        PillKind.Soft -> colors.blue
    }
    Box(
        modifier = modifier
            .heightIn(min = TingoDimens.touchTarget)
            .background(background, TingoDimens.pillShape)
            .then(
                if (kind == PillKind.Ghost) {
                    Modifier.border(BorderStroke(TingoDimens.hairline, colors.line), TingoDimens.pillShape)
                } else {
                    Modifier
                },
            )
            .semantics { role = Role.Button }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = content.copy(alpha = alpha),
        )
    }
}

/** 小筛选胶囊（"只看归档"这类）：选中是主色淡底，未选中是描边。 */
@Composable
fun PillFilter(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    labelColor: Color? = null,
) {
    val colors = TingoTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = TingoDimens.touchTarget)
            .background(if (selected) colors.blueSoft else colors.card, TingoDimens.pillShape)
            .border(
                BorderStroke(TingoDimens.hairline, if (selected) colors.blueSoft else colors.line),
                TingoDimens.pillShape,
            )
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = labelColor ?: if (selected) colors.blue else colors.muted,
        )
    }
}

/**
 * 灰底分段控件（详情页"纪要 / 转写"、设置页"中文 / English"）。
 *
 * 只有一个分段时画成静态标签的样子：不给它按钮语义，否则用户按了没反应更困惑。
 */
@Composable
fun PillTabs(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TingoTheme.colors
    val interactive = tabs.size > 1
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.seg, TingoDimens.controlShape)
            .padding(3.dp),
    ) {
        tabs.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = TingoDimens.touchTarget)
                    .background(if (active) colors.card else colors.seg, TingoDimens.controlShape)
                    .then(
                        if (active) {
                            Modifier.border(BorderStroke(TingoDimens.hairline, colors.line), TingoDimens.controlShape)
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (interactive) {
                            Modifier
                                .semantics {
                                    role = Role.Tab
                                    this.selected = active
                                }
                                .clickable { onSelect(index) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) colors.text else colors.muted,
                )
            }
        }
    }
}
