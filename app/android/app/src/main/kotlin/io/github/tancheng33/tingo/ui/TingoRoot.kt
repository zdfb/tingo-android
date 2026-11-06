package io.github.tancheng33.tingo.ui

import java.io.File

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tancheng33.tingo.ui.detail.MinutesUiState
import io.github.tancheng33.tingo.ui.detail.TranscriptionUiState
import io.github.tancheng33.tingo.data.MeetingExporter
import io.github.tancheng33.tingo.AppContainer
import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.data.MeetingRepository
import io.github.tancheng33.tingo.settings.AppLanguage
import io.github.tancheng33.tingo.minutes.LlamaMinutesGenerator
import io.github.tancheng33.tingo.minutes.MinutesService
import io.github.tancheng33.tingo.transcription.TranscriptionPipeline
import io.github.tancheng33.tingo.ui.detail.DetailScreen
import io.github.tancheng33.tingo.ui.detail.MinutesViewModel
import io.github.tancheng33.tingo.ui.detail.TranscriptionViewModel
import io.github.tancheng33.tingo.ui.detail.DetailViewModel
import io.github.tancheng33.tingo.ui.library.LibraryScreen
import io.github.tancheng33.tingo.ui.library.LibraryUiState
import io.github.tancheng33.tingo.ui.library.LibraryViewModel
import io.github.tancheng33.tingo.recording.MicRecorder
import io.github.tancheng33.tingo.recording.RecordingKeepAlive
import io.github.tancheng33.tingo.recording.RecordingService
import io.github.tancheng33.tingo.recording.requiresForegroundService
import io.github.tancheng33.tingo.ui.record.RecordScreen
import io.github.tancheng33.tingo.ui.record.RecordViewModel
import io.github.tancheng33.tingo.ui.settings.SettingsScreen

private const val DETAIL_ROUTE = "detail"

/** 顶部级目的地：窄屏是底部导航，宽屏是侧边导航。 */
private enum class TopDestination(
    val route: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val labelRes: Int,
) {
    RECORD("record", Icons.Filled.PlayArrow, R.string.nav_record),
    MEETINGS("meetings", Icons.AutoMirrored.Filled.List, R.string.nav_meetings),
    SETTINGS("settings", Icons.Filled.Settings, R.string.nav_settings),
}

/**
 * 应用外壳：导航骨架 + 单栏/双栏自适应。
 *
 * - 窄屏（手机竖屏）：底部导航 + 详情全屏压栈，返回即回到列表
 * - 宽屏（平板、折叠屏展开、横屏）：侧边导航 + 列表与详情并排
 *
 * 会议库的 ViewModel 提升到外壳层持有：它比详情页活得久，返回时列表的滚动位置
 * 与筛选条件因此自然保持。
 */
@Composable
fun TingoRoot(
    container: AppContainer,
    wide: Boolean,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val libraryViewModel: LibraryViewModel = viewModel(
        factory = viewModelFactory {
            initializer { LibraryViewModel(MeetingRepository(container.engine)) }
        },
    )
    val libraryState by libraryViewModel.state.collectAsStateWithLifecycle()
    val libraryError by libraryViewModel.lastError.collectAsStateWithLifecycle()

    /** 宽屏时右侧详情显示哪一场；窄屏走导航压栈。 */
    var selectedMeetingId by rememberSaveable { mutableStateOf<String?>(null) }

    /** 点了"不可读"标记时告诉用户具体是哪个目录（排查只能靠路径）。 */
    var unreadablePath by rememberSaveable { mutableStateOf<String?>(null) }

    if (unreadablePath != null) {
        AlertDialog(
            onDismissRequest = { unreadablePath = null },
            title = { Text(stringResource(R.string.library_unreadable)) },
            text = { Text(unreadablePath.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { unreadablePath = null }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopDestination.entries.forEach { destination ->
                val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                item(
                    selected = selected,
                    onClick = {
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(destination.icon, contentDescription = null) },
                    label = { Text(stringResource(destination.labelRes)) },
                )
            }
        },
    ) {
        val onOpenMeeting: (String) -> Unit = { id ->
            if (wide) selectedMeetingId = id else navController.navigate("$DETAIL_ROUTE/$id")
        }

        NavHost(
            navController = navController,
            startDestination = TopDestination.MEETINGS.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(TopDestination.RECORD.route) {
                RecordRoute(
                    container = container,
                    onOpenMeeting = { id ->
                        if (wide) {
                            selectedMeetingId = id
                            navController.navigate(TopDestination.MEETINGS.route) { launchSingleTop = true }
                        } else {
                            navController.navigate("$DETAIL_ROUTE/$id")
                        }
                        libraryViewModel.refresh()
                    },
                )
            }

            composable(TopDestination.MEETINGS.route) {
                val library = @Composable {
                    LibraryScreen(
                        state = libraryState,
                        onOpenMeeting = onOpenMeeting,
                        onRefresh = libraryViewModel::refresh,
                        onQueryChange = libraryViewModel::onQueryChange,
                        onArchivedOnlyChange = libraryViewModel::onArchivedOnlyChange,
                        onUnreadableClick = { card ->
                            unreadablePath = container.meetingDir(card.id).absolutePath
                        },
                        onSetArchived = { card, archived -> libraryViewModel.setArchived(card.id, archived) },
                        onDelete = { card -> libraryViewModel.delete(card.id) },
                        actionError = libraryError,
                    )
                }
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(0.9f).fillMaxHeight()) { library() }
                        VerticalDivider()
                        Box(Modifier.weight(1.2f).fillMaxHeight()) {
                            selectedMeetingId?.let { id ->
                                DetailRoute(container, id, wide = true, onBack = { selectedMeetingId = null })
                            } ?: EmptyDetailHint()
                        }
                    }
                } else {
                    library()
                }
            }

            composable("$DETAIL_ROUTE/{id}") { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                DetailRoute(container, id, wide = false, onBack = { navController.popBackStack() })
            }

            composable(TopDestination.SETTINGS.route) {
                SettingsScreen(
                    language = container.settings.language,
                    engineVersion = container.engine.engineVersion(),
                    dataRoot = container.dataRoot.absolutePath,
                    onLanguageChange = onLanguageChange,
                )
            }
        }
    }
}

/** 用系统分享面板把导出的文件交给别的应用；FileProvider 只授权 cache/exports。 */
private fun shareFile(context: android.content.Context, file: File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        android.content.Intent.createChooser(intent, context.getString(R.string.detail_export)),
    )
}

@Composable
private fun RecordRoute(container: AppContainer, onOpenMeeting: (String) -> Unit) {
    val viewModel: RecordViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                RecordViewModel(
                    capture = MicRecorder(),
                    meetingsRoot = File(container.dataRoot, "meetings"),
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 录音期间挂前台服务：状态变化时同步给服务，服务被结束时把录音判为中断
    val serviceContext = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        RecordingKeepAlive.onServiceDestroyed = { viewModel.onInterrupted() }
        onDispose { RecordingKeepAlive.onServiceDestroyed = {} }
    }
    androidx.compose.runtime.LaunchedEffect(state) {
        RecordingKeepAlive.publish(state)
        if (requiresForegroundService(state)) {
            RecordingService.start(serviceContext)
        } else {
            RecordingService.stop(serviceContext)
            RecordingKeepAlive.publish(null)
        }
    }

    RecordScreen(
        state = state,
        onStart = viewModel::start,
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onStop = viewModel::stop,
        onDiscard = viewModel::discard,
        onOpenMeeting = onOpenMeeting,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailRoute(container: AppContainer, meetingId: String, wide: Boolean, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val exportScope = androidx.compose.runtime.rememberCoroutineScope()
    val viewModel: DetailViewModel = viewModel(
        key = "detail-$meetingId",
        factory = viewModelFactory {
            initializer { DetailViewModel(container.engine, meetingId) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    val transcriptionViewModel: TranscriptionViewModel = viewModel(
        key = "transcribe-$meetingId",
        factory = viewModelFactory {
            initializer {
                TranscriptionViewModel(
                    transcriber = TranscriptionPipeline(container.models, container.catalog),
                    store = container.models,
                    catalog = container.catalog,
                    meetingDir = container.meetingDir(meetingId),
                )
            }
        },
    )
    val transcription by transcriptionViewModel.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(meetingId) { transcriptionViewModel.prepare() }

    val minutesViewModel: MinutesViewModel = viewModel(
        key = "minutes-$meetingId",
        factory = viewModelFactory {
            initializer {
                val meetingDir = container.meetingDir(meetingId)
                MinutesViewModel(
                    serviceFactory = {
                        MinutesService(LlamaMinutesGenerator(container.llmFile))
                    },
                    modelFile = container.llmFile,
                    meetingDir = meetingDir,
                )
            }
        },
    )
    val minutesState by minutesViewModel.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(meetingId) { minutesViewModel.prepare() }

    // 转写/纪要写进会议目录后重新读详情：不然界面会一直停在"暂无转写""暂无纪要"
    androidx.compose.runtime.LaunchedEffect(transcription) {
        if (transcription is TranscriptionUiState.Done) viewModel.load()
    }
    androidx.compose.runtime.LaunchedEffect(minutesState) {
        if (minutesState is MinutesUiState.Done) viewModel.load()
    }

    // 能力矩阵由引擎给出：没有纪要解析能力就不展示纪要栏
    val showMinutesTab = remember(container) { container.engine.capabilities().minutesParse }

    Column(Modifier.fillMaxSize()) {
        if (!wide) {
            TopAppBar(
                title = { Text(state.detail?.summary?.title ?: stringResource(R.string.nav_meetings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        }
        DetailScreen(
            state = state,
            onRetry = viewModel::load,
            // 播放器随录音链路一起接入：现在点击时间戳没有可跳转的目标，
            // 与其做一个按下去毫无反应的按钮，不如先留空回调。
            onSentenceClick = { },
            showMinutesTab = showMinutesTab,
            transcription = transcription,
            onTranscribe = transcriptionViewModel::start,
            onCancelTranscribe = transcriptionViewModel::cancel,
            minutesGeneration = minutesState,
            onGenerateMinutes = minutesViewModel::start,
            onCancelMinutes = minutesViewModel::cancel,
            onExport = {
                // 读纪要 + 解析整份 transcript 可能上百毫秒，别在主线程做
                val dir = container.meetingDir(meetingId)
                val title = state.detail?.summary?.title.orEmpty().ifBlank { meetingId }
                exportScope.launch {
                    val exported = withContext(Dispatchers.IO) {
                        MeetingExporter.export(dir, title, File(context.cacheDir, "exports"))
                    }
                    if (exported != null) shareFile(context, exported)
                }
            },
            exportEnabled = state.detail?.let { it.minutesText != null || it.transcript != null } == true,
        )
    }
}

@Composable
private fun EmptyDetailHint() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.detail_select_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
