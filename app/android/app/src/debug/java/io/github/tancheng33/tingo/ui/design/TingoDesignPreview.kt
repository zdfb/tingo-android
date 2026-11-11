/* Hallmark · v2.0.0 · redesign: full · shape: app screen · theme: Newsprint (adapted to Tingo brand) · genre: atmospheric · modes: light+dark · signature: waveform with source timestamps · surface: absolute · built: the project */
/* Hallmark · pre-emit critique: P4 H5 E4 S4 R5 V4 · weakest: S, generic meeting copy remains; local audio and source-linked notes give the product its specific voice */
package io.github.tancheng33.tingo.ui.design

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.WindowInsetsControllerCompat
import io.github.tancheng33.tingo.ui.components.PillTabs
import io.github.tancheng33.tingo.ui.theme.TingoDimens
import io.github.tancheng33.tingo.ui.theme.TingoTheme

data class TingoPreviewVariant(val english: Boolean, val dark: Boolean) {
    fun text(chinese: String, englishText: String): String = if (english) englishText else chinese
}

class TingoPreviewVariantProvider : PreviewParameterProvider<TingoPreviewVariant> {
    override val values = sequenceOf(
        TingoPreviewVariant(english = false, dark = false),
        TingoPreviewVariant(english = false, dark = true),
        TingoPreviewVariant(english = true, dark = false),
        TingoPreviewVariant(english = true, dark = true),
    )
}

@Preview(name = "Onboarding (phone)", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun TingoOnboardingPreview(
    @PreviewParameter(TingoPreviewVariantProvider::class) variant: TingoPreviewVariant,
) {
    PreviewWindow(variant, selectedTab = PreviewTab.Record, showBottomBar = true) {
        OnboardingBody(variant)
    }
}

@Preview(name = "New meeting (phone)", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun TingoRecordHomePreview(
    @PreviewParameter(TingoPreviewVariantProvider::class) variant: TingoPreviewVariant,
) {
    PreviewWindow(variant, selectedTab = PreviewTab.Record, showBottomBar = true) {
        RecordHomeBody(variant)
    }
}

@Preview(name = "Microphone permission (phone)", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun TingoMicPermissionPreview(
    @PreviewParameter(TingoPreviewVariantProvider::class) variant: TingoPreviewVariant,
) {
    PreviewWindow(variant, selectedTab = PreviewTab.Record, showBottomBar = true) {
        RecordHomeBody(variant, microphoneDenied = true)
    }
}

@Preview(name = "Recording (live transcript)", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun TingoLiveRecordingPreview(
    @PreviewParameter(TingoPreviewVariantProvider::class) variant: TingoPreviewVariant,
) {
    PreviewWindow(variant, selectedTab = PreviewTab.Record, showBottomBar = true) {
        LiveRecordingBody(variant)
    }
}

@Preview(name = "Meetings (empty)", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun TingoEmptyLibraryPreview(
    @PreviewParameter(TingoPreviewVariantProvider::class) variant: TingoPreviewVariant,
) {
    PreviewWindow(variant, selectedTab = PreviewTab.Meetings, showBottomBar = true) {
        LibraryBody(variant, empty = true)
    }
}

@Preview(name = "Meetings (grouped list)", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun TingoLibraryPreview(
    @PreviewParameter(TingoPreviewVariantProvider::class) variant: TingoPreviewVariant,
) {
    PreviewWindow(variant, selectedTab = PreviewTab.Meetings, showBottomBar = true) {
        LibraryBody(variant, empty = false)
    }
}

@Preview(name = "Meetings (selection)", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun TingoLibrarySelectionPreview(
    @PreviewParameter(TingoPreviewVariantProvider::class) variant: TingoPreviewVariant,
) {
    PreviewWindow(variant, selectedTab = PreviewTab.Meetings, showBottomBar = true) {
        LibraryBody(variant, empty = false, selecting = true)
    }
}

@Preview(name = "Meeting detail (minutes)", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun TingoMeetingMinutesPreview(
    @PreviewParameter(TingoPreviewVariantProvider::class) variant: TingoPreviewVariant,
) {
    PreviewWindow(variant, selectedTab = PreviewTab.Meetings, showBottomBar = false) {
        MeetingDetailBody(variant, transcript = false)
    }
}

@Preview(name = "Meeting detail (transcript)", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun TingoMeetingTranscriptPreview(
    @PreviewParameter(TingoPreviewVariantProvider::class) variant: TingoPreviewVariant,
) {
    PreviewWindow(variant, selectedTab = PreviewTab.Meetings, showBottomBar = false) {
        MeetingDetailBody(variant, transcript = true)
    }
}

@Preview(name = "Meeting detail (minutes service missing)", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun TingoUnconfiguredMinutesPreview(
    @PreviewParameter(TingoPreviewVariantProvider::class) variant: TingoPreviewVariant,
) {
    PreviewWindow(variant, selectedTab = PreviewTab.Meetings, showBottomBar = false) {
        MeetingDetailBody(variant, transcript = false, minutesServiceMissing = true)
    }
}

@Preview(name = "Settings (phone)", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun TingoSettingsPreview(
    @PreviewParameter(TingoPreviewVariantProvider::class) variant: TingoPreviewVariant,
) {
    PreviewWindow(variant, selectedTab = PreviewTab.Settings, showBottomBar = true) {
        SettingsBody(variant)
    }
}

@Composable
fun TingoDesignGallery() {
    var selectedScreen by remember { mutableStateOf(PreviewScreen.Onboarding) }
    var english by remember { mutableStateOf(false) }
    var dark by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val variant = TingoPreviewVariant(english = english, dark = dark)
    val view = LocalView.current
    val window = view.context.activityOrNull()?.window
    val screens = PreviewScreen.entries

    TingoTheme(darkTheme = dark) {
        SideEffect {
            window?.let {
                WindowInsetsControllerCompat(it, view).isAppearanceLightStatusBars = !dark
                WindowInsetsControllerCompat(it, view).isAppearanceLightNavigationBars = !dark
            }
        }
        Column(
            Modifier.fillMaxSize()
                .background(TingoTheme.colors.bg)
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 18.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    Column(
                        modifier = Modifier.clickable { menuExpanded = true }.padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text("${(screens.indexOf(selectedScreen) + 1).toString().padStart(2, '0')} / ${screens.size}", color = TingoTheme.colors.muted, style = MaterialTheme.typography.labelSmall)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(selectedScreen.label(variant), color = TingoTheme.colors.text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("⌄", color = TingoTheme.colors.muted, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    if (menuExpanded) {
                        Popup(
                            alignment = Alignment.BottomStart,
                            onDismissRequest = { menuExpanded = false },
                            properties = PopupProperties(focusable = true),
                        ) {
                            Column(
                                modifier = Modifier.widthIn(min = 230.dp, max = 280.dp)
                                    .heightIn(max = 540.dp)
                                    .clip(TingoDimens.cardShape)
                                    .background(TingoTheme.colors.card)
                                    .border(BorderStroke(1.dp, TingoTheme.colors.line), TingoDimens.cardShape)
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 5.dp),
                            ) {
                                screens.forEach { screen ->
                                    val selected = screen == selectedScreen
                                    Row(
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                            .clickable(role = Role.Button, onClick = { selectedScreen = screen; menuExpanded = false })
                                            .padding(horizontal = 15.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(screen.label(variant), modifier = Modifier.weight(1f), color = if (selected) TingoTheme.colors.blue else TingoTheme.colors.text, style = MaterialTheme.typography.bodyLarge)
                                        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = TingoTheme.colors.blue, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                GalleryToggle("中", selected = !english) { english = false }
                GalleryToggle("EN", selected = english) { english = true }
                GalleryToggle(variant.text("亮", "Light"), selected = !dark) { dark = false }
                GalleryToggle(variant.text("暗", "Dark"), selected = dark) { dark = true }
            }
            HorizontalDivider(color = TingoTheme.colors.line)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                selectedScreen.content(variant)
            }
        }
    }
}

private enum class PreviewScreen(
    private val chineseLabel: String,
    private val englishLabel: String,
    val content: @Composable (TingoPreviewVariant) -> Unit,
) {
    Onboarding("新手引导", "Onboarding", { TingoOnboardingPreview(it) }),
    NewMeeting("新会议", "New meeting", { TingoRecordHomePreview(it) }),
    MicrophonePermission("麦克风权限", "Mic permission", { TingoMicPermissionPreview(it) }),
    Recording("录音中", "Recording", { TingoLiveRecordingPreview(it) }),
    EmptyLibrary("空列表", "Empty list", { TingoEmptyLibraryPreview(it) }),
    Library("会议列表", "Meetings", { TingoLibraryPreview(it) }),
    LibrarySelection("批量选择", "Selection", { TingoLibrarySelectionPreview(it) }),
    Minutes("纪要详情", "Minutes", { TingoMeetingMinutesPreview(it) }),
    Transcript("转写详情", "Transcript", { TingoMeetingTranscriptPreview(it) }),
    MinutesSetup("纪要未配置", "Minutes setup", { TingoUnconfiguredMinutesPreview(it) }),
    Settings("设置", "Settings", { TingoSettingsPreview(it) });

    fun label(variant: TingoPreviewVariant): String = variant.text(chineseLabel, englishLabel)
}

private tailrec fun Context.activityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activityOrNull()
    else -> null
}

@Composable
private fun GalleryToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = TingoTheme.colors
    Text(
        text = label,
        modifier = Modifier
            .clip(TingoDimens.pillShape)
            .background(if (selected) colors.blueSoft else colors.card)
            .border(BorderStroke(1.dp, if (selected) colors.blue.copy(alpha = 0.28f) else colors.line), TingoDimens.pillShape)
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = if (selected) colors.blue else colors.text2,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
    )
}

private enum class PreviewTab(
    private val chineseLabel: String,
    private val englishLabel: String,
) {
    Record("新会议", "New"),
    Meetings("会议记录", "Meetings"),
    Settings("设置", "Settings");

    fun label(variant: TingoPreviewVariant): String = variant.text(chineseLabel, englishLabel)
}

@Composable
private fun PreviewTab.Icon(selected: Boolean) {
    val colors = TingoTheme.colors
    val tint = if (selected) colors.blue else colors.muted
    when (this) {
        PreviewTab.Record -> MicGlyph(tint, Modifier.size(22.dp))
        PreviewTab.Meetings -> NotesGlyph(tint, Modifier.size(22.dp))
        PreviewTab.Settings -> SlidersGlyph(tint, Modifier.size(22.dp))
    }
}

@Composable
private fun PreviewWindow(
    variant: TingoPreviewVariant,
    selectedTab: PreviewTab,
    showBottomBar: Boolean,
    content: @Composable () -> Unit,
) {
    TingoTheme(darkTheme = variant.dark) {
        Column(Modifier.fillMaxSize().background(TingoTheme.colors.bg)) {
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            if (showBottomBar) PreviewBottomBar(variant, selectedTab)
        }
    }
}

@Composable
private fun PreviewBottomBar(variant: TingoPreviewVariant, selected: PreviewTab) {
    val colors = TingoTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.card)) {
        HorizontalDivider(color = colors.line)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            PreviewNavItem(variant, PreviewTab.Record, selected == PreviewTab.Record)
            PreviewNavItem(variant, PreviewTab.Meetings, selected == PreviewTab.Meetings)
            PreviewNavItem(variant, PreviewTab.Settings, selected == PreviewTab.Settings)
        }
    }
}

@Composable
private fun PreviewNavItem(
    variant: TingoPreviewVariant,
    tab: PreviewTab,
    selected: Boolean,
) {
    val colors = TingoTheme.colors
    Column(
        modifier = Modifier.widthIn(min = 84.dp).heightIn(min = 50.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Tab, onClick = {})
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tab.Icon(selected)
        Text(tab.label(variant), color = if (selected) colors.blue else colors.muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ScreenScroll(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = TingoDimens.gutter, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
private fun PageHeading(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.displayLarge, color = TingoTheme.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TingoTheme.colors.muted)
        }
        trailing?.invoke()
    }
}

@Composable
private fun DesignCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = TingoTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(TingoDimens.cardShape)
            .background(colors.card)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
private fun PrimaryAction(
    label: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = TingoTheme.colors
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(TingoDimens.pillShape)
            .background(colors.black)
            .clickable(role = Role.Button, onClick = {})
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = colors.onBlack, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun OutlineAction(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = TingoTheme.colors
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(TingoDimens.pillShape)
            .background(colors.card)
            .border(BorderStroke(1.dp, colors.line), TingoDimens.pillShape)
            .clickable(role = Role.Button, onClick = {})
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(7.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = colors.muted, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(label, color = colors.text2, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun SoftBadge(label: String, color: Color = TingoTheme.colors.blue) {
    Text(
        label,
        modifier = Modifier.clip(TingoDimens.pillShape).background(color.copy(alpha = 0.11f)).padding(horizontal = 9.dp, vertical = 5.dp),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun TingoMark(modifier: Modifier = Modifier.size(60.dp)) {
    val colors = TingoTheme.colors
    Box(
        modifier = modifier.clip(RoundedCornerShape(17.dp)).background(colors.card)
            .border(BorderStroke(1.dp, colors.line), RoundedCornerShape(17.dp)).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.09f
            val center = size.width / 2f
            val heights = listOf(0.36f, 0.56f, 0.84f, 0.58f, 0.38f)
            val gap = size.width * 0.12f
            heights.forEachIndexed { index, fraction ->
                val x = center + (index - 2) * gap
                val height = size.height * fraction
                drawLine(colors.blue, Offset(x, center - height / 2f), Offset(x, center + height / 2f), stroke, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun MicGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.075f
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.37f, size.height * 0.06f),
            size = Size(size.width * 0.26f, size.height * 0.52f),
            cornerRadius = CornerRadius(size.width * 0.13f),
        )
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.2f, size.height * 0.2f),
            size = Size(size.width * 0.6f, size.height * 0.55f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(tint, Offset(size.width * 0.5f, size.height * 0.75f), Offset(size.width * 0.5f, size.height * 0.9f), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(size.width * 0.35f, size.height * 0.9f), Offset(size.width * 0.65f, size.height * 0.9f), stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun NotesGlyph(tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.09f
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.18f, size.height * 0.1f),
            size = Size(size.width * 0.64f, size.height * 0.8f),
            cornerRadius = CornerRadius(size.minDimension * 0.12f),
            style = Stroke(width = stroke),
        )
        for (index in 0..2) {
            val y = size.height * (0.35f + index * 0.16f)
            drawLine(tint, Offset(size.width * 0.34f, y), Offset(size.width * 0.68f, y), stroke, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun SlidersGlyph(tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    val background = TingoTheme.colors.card
    Canvas(modifier) {
        val stroke = size.minDimension * 0.09f
        val left = size.width * 0.14f
        val right = size.width * 0.86f
        val y1 = size.height * 0.34f
        val y2 = size.height * 0.68f
        drawLine(tint, Offset(left, y1), Offset(right, y1), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(left, y2), Offset(right, y2), stroke, cap = StrokeCap.Round)
        drawCircle(background, radius = size.minDimension * 0.15f, center = Offset(size.width * 0.66f, y1))
        drawCircle(tint, radius = size.minDimension * 0.15f, center = Offset(size.width * 0.66f, y1), style = Stroke(width = stroke))
        drawCircle(background, radius = size.minDimension * 0.15f, center = Offset(size.width * 0.38f, y2))
        drawCircle(tint, radius = size.minDimension * 0.15f, center = Offset(size.width * 0.38f, y2), style = Stroke(width = stroke))
    }
}

@Composable
private fun PauseGlyph(tint: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier) {
        val barWidth = size.width * 0.22f
        val top = size.height * 0.12f
        val barHeight = size.height * 0.76f
        drawRoundRect(tint, Offset(size.width * 0.19f, top), Size(barWidth, barHeight), CornerRadius(barWidth / 2))
        drawRoundRect(tint, Offset(size.width * 0.59f, top), Size(barWidth, barHeight), CornerRadius(barWidth / 2))
    }
}

@Composable
private fun StopGlyph(tint: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Box(
        modifier = modifier.padding(3.dp).clip(RoundedCornerShape(3.dp)).background(tint),
    )
}

@Composable
private fun SectionHeading(title: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = TingoTheme.colors.text, fontWeight = FontWeight.SemiBold)
        if (trailing != null) Text(trailing, color = TingoTheme.colors.muted, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun OnboardingBody(variant: TingoPreviewVariant) {
    val text = variant::text
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = TingoDimens.gutter, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text("认识 Tingo", "Meet Tingo"), modifier = Modifier.weight(1f), color = TingoTheme.colors.text2, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(text("第 1 步，共 5 步", "Step 1 of 5"), color = TingoTheme.colors.blue, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(5) { index ->
                    Box(
                        Modifier.weight(1f).height(3.dp).clip(CircleShape)
                            .background(if (index == 0) TingoTheme.colors.blue else TingoTheme.colors.line),
                    )
                }
            }
            DesignCard {
                TingoMark()
                Text(
                    text("开过的会，都听过。", "Every meeting, heard and remembered."),
                    style = MaterialTheme.typography.displayLarge,
                    color = TingoTheme.colors.text,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text(
                        "Tingo 在这台手机上录会议、转文字、分清谁说了什么，再按你的选择整理成纪要。",
                        "Tingo records meetings on this phone, transcribes speakers, and turns your notes into a summary.",
                    ),
                    color = TingoTheme.colors.muted,
                    style = MaterialTheme.typography.bodyLarge,
                )
                BenefitRow(text("录音不出手机", "Audio stays on this phone"), text("识别和说话人区分在本地完成", "Transcription and speakers run locally"))
                BenefitRow(text("锁屏也能录", "Record with the screen locked"), text("录音由前台服务保持运行", "A foreground service keeps recording"))
                BenefitRow(text("结论有出处", "Every conclusion has a source"), text("点纪要时间戳回听原话", "Tap a timestamp to hear the quote"))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = TingoDimens.gutter, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {}) { Text(text("跳过引导", "Skip"), color = TingoTheme.colors.muted) }
            Spacer(Modifier.weight(1f))
            PrimaryAction(text("下一步", "Next"))
        }
    }
}

@Composable
private fun BenefitRow(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = TingoTheme.colors.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(body, color = TingoTheme.colors.muted, style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider(color = TingoTheme.colors.lineSoft)
    }
}

@Composable
private fun RecordHomeBody(variant: TingoPreviewVariant, microphoneDenied: Boolean = false) {
    val text = variant::text
    ScreenScroll {
        PageHeading(
            title = text("新会议", "New meeting"),
            subtitle = text("录下会议，之后随时能搜到原话。", "Record now. Find every quote later."),
        )
        if (microphoneDenied) {
            NoticeCard(
                title = text("麦克风权限已关闭", "Microphone access is off"),
                body = text("允许麦克风后才能开始录音。", "Allow microphone access to start recording."),
                action = text("打开系统设置", "Open settings"),
                error = true,
            )
        }
        if (!microphoneDenied) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TingoMark(Modifier.size(68.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text("准备开始了吗？", "Ready when you are"), color = TingoTheme.colors.text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(text("录音和转写保存在这台设备", "Audio and transcripts stay on this device"), color = TingoTheme.colors.muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            PrimaryAction(
                label = text("开始录音", "Start recording"),
                modifier = Modifier.fillMaxWidth(),
                leading = { MicGlyph(TingoTheme.colors.onBlack, Modifier.size(19.dp)) },
            )
        } else {
            OutlineAction(text("仍可查看会议记录", "Browse existing meetings"), modifier = Modifier.fillMaxWidth())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text("会议软件声音", "Meeting app audio"), color = TingoTheme.colors.text2, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(text("可选；Android 会在开始时询问授权", "Optional; Android asks when recording starts"), color = TingoTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = false, onCheckedChange = {})
        }
        HorizontalDivider(color = TingoTheme.colors.line)
        SectionHeading(text("最近会议", "Recent meetings"), text("全部", "View all"))
        MeetingPreviewRow(variant, "产品周会第 12 周", "10:24", text("54 分钟", "54 min"), text("已完成", "Ready"), text("产品", "Product"))
        MeetingPreviewRow(variant, "增长复盘", text("昨天 09:10", "Yesterday 09:10"), text("38 分钟", "38 min"), text("正在生成纪要", "Writing minutes"), text("增长", "Growth"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftBadge(text("识别模型已就绪", "Recognition ready"), TingoTheme.colors.green)
            SoftBadge(text("纪要服务未配置", "Minutes not configured"), TingoTheme.colors.amber)
        }
    }
}

@Composable
private fun NoticeCard(title: String, body: String, action: String, error: Boolean = false) {
    val colors = TingoTheme.colors
    val tint = if (error) colors.red else colors.amber
    Column(
        modifier = Modifier.fillMaxWidth().clip(TingoDimens.controlShape).background(colors.hover).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = tint, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        Text(body, color = colors.text2, style = MaterialTheme.typography.bodyMedium)
        Text(action, color = colors.blue, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun MeetingPreviewRow(
    variant: TingoPreviewVariant,
    title: String,
    time: String,
    duration: String,
    status: String,
    tag: String,
    selected: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = {}).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (selected) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(TingoTheme.colors.blue), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = TingoTheme.colors.onBlack, modifier = Modifier.size(14.dp))
                }
            }
            Text(title, modifier = Modifier.weight(1f), color = TingoTheme.colors.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            SoftBadge(status, if (status.contains("生成") || status.contains("Writing")) TingoTheme.colors.amber else TingoTheme.colors.green)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(start = if (selected) 32.dp else 0.dp)) {
            Text(time, color = TingoTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
            Text(duration, color = TingoTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
            Text(tag, color = TingoTheme.colors.blue, style = MaterialTheme.typography.bodySmall)
        }
        Text(variant.text("查看纪要与转写", "View minutes and transcript"), color = TingoTheme.colors.faint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = if (selected) 32.dp else 0.dp))
        HorizontalDivider(color = TingoTheme.colors.lineSoft)
    }
}

@Composable
private fun LiveRecordingBody(variant: TingoPreviewVariant) {
    val text = variant::text
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = TingoDimens.gutter, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PageHeading(text("正在录音", "Recording"), text("麦克风录音", "Microphone audio"))
                }
                SoftBadge(text("录音中", "Recording"), TingoTheme.colors.red)
            }
            Text("00:12:45", modifier = Modifier.fillMaxWidth(), color = TingoTheme.colors.text, fontSize = 42.sp, fontWeight = FontWeight.SemiBold)
            Waveform()
            NoticeCard(
                title = text("没捕获到会议软件声音", "Meeting app audio was not captured"),
                body = text("当前仍在录麦克风；对方声音可能不在这份录音里。", "Microphone recording continues; remote speakers may be missing."),
                action = text("查看录音来源", "Review audio sources"),
            )
            SectionHeading(text("实时转写", "Live transcript"), text("麦克风", "Microphone"))
            TranscriptLine(variant, "说话人 1", "00:01:12", "今天先对齐一下新版本的上线时间。", "Let us align on the launch date for the new version.")
            HorizontalDivider(color = TingoTheme.colors.lineSoft)
            TranscriptLine(variant, "说话人 2", "00:01:28", "我建议先把录音权限流程做完，再接纪要服务。", "I suggest we finish recording permissions before the minutes service.")
            HorizontalDivider(color = TingoTheme.colors.lineSoft)
            TranscriptLine(variant, "说话人 1", "00:01:46", "好，我来整理风险，周五之前发出来。", "Sounds good. I will write up the risks by Friday.")
        }
        Row(
            modifier = Modifier.fillMaxWidth().background(TingoTheme.colors.card)
                .border(BorderStroke(1.dp, TingoTheme.colors.lineSoft), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlineAction(text("标记", "Mark"), modifier = Modifier.weight(1f), icon = Icons.Default.Star)
            OutlineAction(text("暂停", "Pause"), modifier = Modifier.weight(1f), leading = { PauseGlyph(TingoTheme.colors.muted) })
            PrimaryAction(text("结束", "Stop"), modifier = Modifier.weight(1f), leading = { StopGlyph(TingoTheme.colors.onBlack) })
        }
    }
}

@Composable
private fun Waveform() {
    val heights = listOf(10, 18, 24, 13, 28, 35, 19, 14, 32, 22, 38, 17, 25, 34, 12, 28, 18, 37, 23, 14, 30, 21, 35, 17)
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        heights.forEachIndexed { index, height ->
            Box(
                Modifier.weight(1f).height(height.dp).clip(CircleShape)
                    .background(if (index < 6) TingoTheme.colors.blue else TingoTheme.colors.line),
            )
        }
    }
}

@Composable
private fun TranscriptLine(variant: TingoPreviewVariant, speaker: String, time: String, chinese: String, english: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(time, color = TingoTheme.colors.blue, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(54.dp).padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(variant.text(speaker, if (speaker.contains("1")) "Speaker 1" else "Speaker 2"), color = TingoTheme.colors.muted, style = MaterialTheme.typography.labelMedium)
            Text(variant.text(chinese, english), color = TingoTheme.colors.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun LibraryBody(variant: TingoPreviewVariant, empty: Boolean, selecting: Boolean = false) {
    val text = variant::text
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = TingoDimens.gutter, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PageHeading(text("会议记录", "Meetings"))
            SearchField(variant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChoice(text("全部", "All"), selected = true)
                FilterChoice(text("已归档", "Archived"), selected = false)
                Spacer(Modifier.weight(1f))
                OutlineAction(if (selecting) text("取消", "Done") else text("选择", "Select"), modifier = Modifier.widthIn(min = 82.dp))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp).clickable(role = Role.Button, onClick = {}),
            ) {
                Text(text("最近删除", "Recently deleted"), modifier = Modifier.weight(1f), color = TingoTheme.colors.muted, style = MaterialTheme.typography.bodyMedium)
                Text("›", color = TingoTheme.colors.muted, style = MaterialTheme.typography.titleLarge)
            }
            if (empty) {
                Box(Modifier.fillMaxWidth().heightIn(min = 360.dp), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TingoMark()
                        Text(text("还没有会议", "No meetings yet"), color = TingoTheme.colors.text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(text("开始录音，或导入已有的录音文件。", "Start recording or import an existing audio file."), color = TingoTheme.colors.muted, style = MaterialTheme.typography.bodyMedium)
                        PrimaryAction(text("开始录音", "Start recording"), modifier = Modifier.padding(top = 3.dp), leading = { MicGlyph(TingoTheme.colors.onBlack, Modifier.size(18.dp)) })
                        TextButton(onClick = {}) { Text(text("导入录音", "Import audio"), color = TingoTheme.colors.blue) }
                    }
                }
            } else {
                SectionHeading(text("今天", "Today"), "2")
                MeetingPreviewRow(variant, "产品周会第 12 周", "10:24", text("54 分钟", "54 min"), text("已完成", "Ready"), text("产品", "Product"), selected = selecting)
                MeetingPreviewRow(variant, "增长复盘", "09:10", text("38 分钟", "38 min"), text("纪要生成中", "Writing minutes"), text("增长", "Growth"), selected = selecting)
                SectionHeading(text("昨天", "Yesterday"), "1")
                MeetingPreviewRow(variant, "移动端体验评审", "16:40", text("42 分钟", "42 min"), text("已完成", "Ready"), text("设计", "Design"), selected = false)
            }
        }
        if (selecting && !empty) {
            Column(Modifier.fillMaxWidth().background(TingoTheme.colors.card)) {
                HorizontalDivider(color = TingoTheme.colors.line)
                Text(text("已选择 2 场会议", "2 meetings selected"), color = TingoTheme.colors.text2, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 18.dp, top = 10.dp, bottom = 6.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineAction(text("归档", "Archive"), modifier = Modifier.weight(1f))
                    OutlineAction(text("加标签", "Add tag"), modifier = Modifier.weight(1f))
                    OutlineAction(text("删除", "Delete"), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SearchField(variant: TingoPreviewVariant) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(TingoDimens.controlShape)
            .background(TingoTheme.colors.card).border(BorderStroke(1.dp, TingoTheme.colors.line), TingoDimens.pillShape)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = TingoTheme.colors.faint, modifier = Modifier.size(20.dp))
        Text(variant.text("搜索会议里说过的话", "Search across meetings"), color = TingoTheme.colors.faint, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FilterChoice(label: String, selected: Boolean) {
    val colors = TingoTheme.colors
    Box(
        modifier = Modifier.clip(TingoDimens.pillShape)
            .background(if (selected) colors.blueSoft else colors.seg)
            .clickable {}.padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) colors.blue else colors.muted, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun MeetingDetailBody(variant: TingoPreviewVariant, transcript: Boolean, minutesServiceMissing: Boolean = false) {
    val text = variant::text
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = text("返回", "Back"), tint = TingoTheme.colors.text) }
            Column(Modifier.weight(1f)) {
                Text(text("产品周会第 12 周", "Product weekly, week 12"), color = TingoTheme.colors.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text("今天 10:24，54 分钟", "Today 10:24, 54 min"), color = TingoTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, contentDescription = text("更多操作", "More actions"), tint = TingoTheme.colors.muted) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = TingoDimens.gutter, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlineAction(text("编辑", "Edit"), icon = Icons.Default.Edit, modifier = Modifier.weight(1f))
            OutlineAction(text("重新生成", "Regenerate"), modifier = Modifier.weight(1f))
            OutlineAction(text("导出", "Export"), icon = Icons.Default.Share, modifier = Modifier.weight(1f))
        }
        PillTabs(
            tabs = listOf(text("纪要", "Minutes"), text("转写", "Transcript")),
            selected = if (transcript) 1 else 0,
            onSelect = {},
            modifier = Modifier.padding(horizontal = TingoDimens.gutter, vertical = 6.dp),
        )
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = TingoDimens.gutter, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (transcript) {
                TranscriptLine(variant, "说话人 1", "00:01:12", "今天先对齐一下新版本的上线时间。", "Let us align on the launch date for the new version.")
                HorizontalDivider(color = TingoTheme.colors.lineSoft)
                TranscriptLine(variant, "说话人 2", "00:01:28", "我建议先把录音权限流程做完，再接纪要服务。", "I suggest we finish recording permissions before the minutes service.")
                HorizontalDivider(color = TingoTheme.colors.lineSoft)
                TranscriptLine(variant, "说话人 1", "00:01:46", "好，我来整理风险，周五之前发出来。", "Sounds good. I will write up the risks by Friday.")
                if (minutesServiceMissing) MissingMinutesCard(variant)
            } else if (minutesServiceMissing) {
                MissingMinutesCard(variant)
                SectionHeading(text("转写已完成", "Transcript is ready"))
                TranscriptLine(variant, "说话人 1", "00:01:12", "今天先对齐一下新版本的上线时间。", "Let us align on the launch date for the new version.")
            } else {
                Text(text("一句话总结", "Summary"), color = TingoTheme.colors.blue, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text("本周优先完成安卓录音体验，纪要服务改为可选配置。", "Prioritize Android recording this week and keep minutes service optional."),
                    color = TingoTheme.colors.text,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(text("来源 00:01:28", "Source 00:01:28"), color = TingoTheme.colors.blue, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(color = TingoTheme.colors.line)
                SectionHeading(text("议题与决定", "Topics and decisions"))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text("录音流程", "Recording flow"), color = TingoTheme.colors.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(text("系统声音作为可选来源；授权失败时继续录麦克风。", "System audio stays optional; continue with mic if permission is denied."), color = TingoTheme.colors.text2, style = MaterialTheme.typography.bodyLarge)
                    Text(text("来源：说话人 1，00:01:28", "Source: Speaker 1, 00:01:28"), color = TingoTheme.colors.blue, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(color = TingoTheme.colors.lineSoft)
                SectionHeading(text("待办", "Action items"), "2")
                TaskRow(text("完成录音权限流程", "Finish the recording permission flow"), text("林舟，周五", "Lin Zhou, Friday"))
                TaskRow(text("整理系统内录限制说明", "Document system audio limitations"), text("陈默，周一", "Chen Mo, Monday"))
                HorizontalDivider(color = TingoTheme.colors.line)
                SectionHeading(text("原因分析", "Root cause"), text("查看原话", "View source"))
                Text(text("每条结论都能回到对应原话核对。", "Each conclusion links back to its supporting quote."), color = TingoTheme.colors.muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        AudioMiniPlayer(variant)
    }
}

@Composable
private fun MissingMinutesCard(variant: TingoPreviewVariant) {
    val text = variant::text
    NoticeCard(
        title = text("纪要服务还没配置", "Minutes service is not configured"),
        body = text("录音和转写已经保存在本机。配置模型服务后即可生成纪要。", "Audio and transcript are saved locally. Configure a model service to generate minutes."),
        action = text("去设置模型服务", "Configure model service"),
    )
}

@Composable
private fun TaskRow(title: String, owner: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).border(1.dp, TingoTheme.colors.faint, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = TingoTheme.colors.text, style = MaterialTheme.typography.bodyLarge)
            Text(owner, color = TingoTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AudioMiniPlayer(variant: TingoPreviewVariant) {
    val colors = TingoTheme.colors
    Column(
        Modifier.fillMaxWidth()
            .background(colors.hover)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        HorizontalDivider(color = colors.line)
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = {}) {
                Icon(Icons.Default.PlayArrow, contentDescription = variant.text("播放", "Play"), tint = colors.blue, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(variant.text("录音 54:28", "Recording 54:28"), color = colors.text2, style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = 0.23f,
                    onValueChange = {},
                    modifier = Modifier.height(22.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = colors.blue,
                        activeTrackColor = colors.blue,
                        inactiveTrackColor = colors.line,
                        activeTickColor = colors.blue,
                        inactiveTickColor = colors.line,
                    ),
                )
            }
            Text("12:42", color = colors.muted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SettingsBody(variant: TingoPreviewVariant) {
    val text = variant::text
    ScreenScroll {
        PageHeading(text("设置", "Settings"), text("保存在这台设备", "Stored on this device"))
        DesignCard {
            SettingsGroup(text("模型与识别", "Models and recognition")) {
                SettingPreviewRow(text("纪要生成", "Minutes service"), "DeepSeek Chat", text("已连接", "Connected")) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = TingoTheme.colors.muted, modifier = Modifier.size(20.dp))
                }
                SettingPreviewRow(text("识别模型", "Recognition model"), "Paraformer-large", text("本地运行", "On-device")) {
                    MicGlyph(TingoTheme.colors.muted, Modifier.size(20.dp))
                }
                SettingPreviewRow(text("录音", "Recording"), text("10 分钟静音后自动结束", "Stop after 10 min silence"), null) {
                    MicGlyph(TingoTheme.colors.muted, Modifier.size(20.dp))
                }
            }
            HorizontalDivider(color = TingoTheme.colors.line)
            SettingsGroup(text("会议与数据", "Meetings and data")) {
                SettingPreviewRow(text("术语与热词", "Terms and hotwords"), text("管理已添加内容", "Manage saved terms"), null) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = TingoTheme.colors.muted, modifier = Modifier.size(20.dp))
                }
                SettingPreviewRow(text("备份与恢复", "Backup and restore"), text("导出或导入会议库", "Export or restore the library"), null) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = TingoTheme.colors.muted, modifier = Modifier.size(20.dp))
                }
                SettingPreviewRow(text("存储空间", "Storage"), text("查看设备占用", "View device storage"), null) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = TingoTheme.colors.muted, modifier = Modifier.size(20.dp))
                }
            }
            HorizontalDivider(color = TingoTheme.colors.line)
            SettingsGroup(text("偏好", "Preferences")) {
                SettingPreviewRow(text("界面语言", "Interface language"), text("跟随系统", "Follow system"), null) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = TingoTheme.colors.muted, modifier = Modifier.size(20.dp))
                }
                SettingPreviewRow(text("外观", "Appearance"), text("跟随系统主题", "Follow system theme"), null) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = TingoTheme.colors.muted, modifier = Modifier.size(20.dp))
                }
                SettingPreviewRow(text("新手引导", "Getting started"), text("重新查看", "Replay guide"), null) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = TingoTheme.colors.muted, modifier = Modifier.size(20.dp))
                }
            }
        }
        Text(
            text("录音和识别在本机完成；生成纪要时，转写文字会发送到你配置的模型服务。", "Recording and transcription run locally. Transcript text is sent to your selected model service to generate minutes."),
            color = TingoTheme.colors.muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = TingoTheme.colors.muted, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun SettingPreviewRow(title: String, value: String, status: String?, icon: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 66.dp).clickable(role = Role.Button, onClick = {}).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = TingoTheme.colors.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(value, color = TingoTheme.colors.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (status != null) Text(status, color = TingoTheme.colors.green, style = MaterialTheme.typography.bodySmall)
        Text("›", color = TingoTheme.colors.muted, style = MaterialTheme.typography.titleLarge)
    }
    HorizontalDivider(color = TingoTheme.colors.lineSoft, modifier = Modifier.padding(start = 36.dp))
}
