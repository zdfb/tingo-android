package io.github.tancheng33.tingo.ui

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Build
import io.github.tancheng33.tingo.ui.record.HomeStatus
import io.github.tancheng33.tingo.ui.record.StatusLevel
import io.github.tancheng33.tingo.ui.record.RecordUiState
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.tancheng33.tingo.ui.theme.TingoDimens
import io.github.tancheng33.tingo.ui.theme.TingoTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
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
import io.github.tancheng33.tingo.BuildConfig
import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.data.MeetingCard
import io.github.tancheng33.tingo.data.MeetingRepository
import io.github.tancheng33.tingo.settings.AppLanguage
import io.github.tancheng33.tingo.minutes.remote.LlmChat
import io.github.tancheng33.tingo.minutes.remote.RemoteMinutesGenerator
import io.github.tancheng33.tingo.minutes.remote.UrlHttpJsonClient
import io.github.tancheng33.tingo.minutes.MinutesService
import io.github.tancheng33.tingo.transcription.TranscriptionPipeline
import io.github.tancheng33.tingo.ui.detail.DetailScreen
import io.github.tancheng33.tingo.ui.detail.MinutesViewModel
import io.github.tancheng33.tingo.ui.detail.TranscriptionViewModel
import io.github.tancheng33.tingo.ui.detail.DetailViewModel
import io.github.tancheng33.tingo.ui.library.LibraryManageActions
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
    val trashItems by libraryViewModel.trash.collectAsStateWithLifecycle()
    val batchFailures by libraryViewModel.lastBatchFailures.collectAsStateWithLifecycle()
    // 回收站视图与"列表/详情"共用同一页：用状态切换而不是再加一条导航路由
    val trashVisible by libraryViewModel.trashVisible.collectAsStateWithLifecycle()
    // 搜索命中要跳到具体毫秒：详情页的播放器接这个值
    var pendingSeekMs by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Long?>(null) }

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


    // 录音状态机与它的守护副作用都放在外壳层：录音必须在用户切到别的页时照样进行，
    // 通知栏计时继续走、通知栏"结束录音"继续有效。放到录音页的组合里，切走就全断了。
    val recordContext = androidx.compose.ui.platform.LocalContext.current
    val recordViewModel: RecordViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                RecordViewModel(
                    capture = MicRecorder(),
                    meetingsRoot = File(container.dataRoot, "meetings"),
                    stopService = { RecordingService.stop(recordContext) },
                )
            }
        },
    )
    val recordState by recordViewModel.state.collectAsStateWithLifecycle()
    val recordSystemAudioStatus by recordViewModel.systemAudioStatus.collectAsStateWithLifecycle()
    var systemAudioEnabled by remember { mutableStateOf(false) }
    var systemAudioDenied by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        RecordingKeepAlive.onServiceDestroyed = { recordViewModel.onInterrupted() }
        RecordingKeepAlive.onStopRequested = { recordViewModel.stop() }
        RecordingKeepAlive.onSystemAudioCapture = { capture ->
            if (capture != null) recordViewModel.attachSystemAudio(capture) else recordViewModel.systemAudioFailed()
        }
        onDispose {
            // 组合没了，但服务可能还在跑：这里不能把回调清成空动作，
            // 否则通知栏"结束录音"变成点了没反应、迟到的投影也没人停
            RecordingKeepAlive.onServiceDestroyed = {}
            RecordingKeepAlive.onStopRequested = {}
            RecordingKeepAlive.onSystemAudioCapture = { capture -> runCatching { capture?.stop() } }
        }
    }
    // 每个 tick 只更新通知内容；服务本身只在"需不需要"变化时启停
    LaunchedEffect(recordState) { RecordingKeepAlive.publish(recordState) }
    val needsService = requiresForegroundService(recordState)
    LaunchedEffect(needsService, systemAudioEnabled) {
        if (needsService) {
            RecordingService.start(recordContext, systemAudio = systemAudioEnabled)
        } else {
            RecordingService.stop(recordContext)
            RecordingKeepAlive.publish(null)
        }
    }

    // 录系统声音要先拿到屏幕录制授权（Android 10+）：授权结果交给服务去取投影
    val projectionLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            systemAudioDenied = false
            recordViewModel.start()
            RecordingService.start(recordContext, systemAudio = true, projection = data)
        } else {
            systemAudioDenied = true
            recordViewModel.start()
        }
    }
    // 录音权限原来是在录音页里申请的。现在状态机在外壳层，权限也得跟着上来，
    // 否则切页之后点"开始录音"会直接撞上没授权（采集抛异常，用户只看到"麦克风不可用"）
    val micPermissionLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { /* 授权结果不在这里处理：startRecording 会重新判定一次权限 */ }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { /* 没有通知权限也能录音，只是状态栏看不到：界面自己会提示 */ }

    val startRecording: () -> Unit = {
        val micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            recordContext,
            android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    recordContext,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            if (systemAudioEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val manager = recordContext.getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(manager.createScreenCaptureIntent())
            } else {
                recordViewModel.start()
            }
        }
    }

    androidx.compose.material3.Scaffold(
        containerColor = TingoTheme.colors.bg,
        bottomBar = {
            TingoBottomBar(
                destinations = TopDestination.entries,
                isSelected = { destination ->
                    currentDestination?.hierarchy?.any { it.route == destination.route } == true
                },
                onSelect = { destination ->
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
    ) { innerPadding ->
        val onOpenMeeting: (String) -> Unit = { id ->
            if (wide) selectedMeetingId = id else navController.navigate("$DETAIL_ROUTE/$id")
        }

        NavHost(
            navController = navController,
            startDestination = TopDestination.MEETINGS.route,
            // 必须吃掉 Scaffold 给的底部导航高度：不要它会画到底部导航下面（实机上一眼可见）
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            composable(TopDestination.RECORD.route) {
                RecordRoute(
                    recent = (libraryState as? LibraryUiState.Content)
                        ?.items
                        ?.filter { !it.archived }
                        ?.take(3)
                        .orEmpty(),
                    statusRows = homeStatusRows(container),
                    state = recordState,
                    systemAudioStatus = recordSystemAudioStatus,
                    systemAudioEnabled = systemAudioEnabled,
                    systemAudioDenied = systemAudioDenied,
                    onSystemAudioChange = { enabled ->
                        systemAudioEnabled = enabled
                        if (!enabled) systemAudioDenied = false
                    },
                    onStart = startRecording,
                    onPause = recordViewModel::pause,
                    onResume = recordViewModel::resume,
                    onStop = recordViewModel::stop,
                    onDiscard = recordViewModel::discard,
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
                        onOpenAtHit = { id, ms ->
                            onOpenMeeting(id)
                            // 跳到命中位置由详情页的播放器接手：先记录待跳转毫秒
                            pendingSeekMs = ms.toLong()
                        },
                        manage = LibraryManageActions(
                            onToggleSelection = libraryViewModel::toggleSelection,
                            onSelectAll = libraryViewModel::selectAll,
                            onClearSelection = libraryViewModel::clearSelection,
                            onBatch = { action, tag -> libraryViewModel.runBatch(action, tag) },
                            onDeleteToTrash = libraryViewModel::deleteToTrash,
                            onShowTrash = libraryViewModel::showTrash,
                            onShowMeetings = libraryViewModel::showMeetings,
                            onRestore = libraryViewModel::restoreFromTrash,
                            onPurge = libraryViewModel::purgeFromTrash,
                            onEmptyTrash = { libraryViewModel.purgeAllFromTrash() },
                        ),
                        trash = trashItems,
                        trashVisible = trashVisible,
                        batchFailures = batchFailures,
                        actionError = libraryError,
                    )
                }
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(0.9f).fillMaxHeight()) { library() }
                        VerticalDivider()
                        Box(Modifier.weight(1.2f).fillMaxHeight()) {
                            selectedMeetingId?.let { id ->
                                DetailRoute(
                        container,
                        id,
                        wide = true,
                        onBack = { selectedMeetingId = null },
                        onOpenSettings = {
                            runCatching { navController.navigate(TopDestination.SETTINGS.route) { launchSingleTop = true } }
                        },
                        onChanged = libraryViewModel::refresh,
                    )
                            } ?: EmptyDetailHint()
                        }
                    }
                } else {
                    library()
                }
            }

            composable("$DETAIL_ROUTE/{id}") { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                val seek = pendingSeekMs
                DetailRoute(
                    container,
                    id,
                    wide = false,
                    onBack = {
                        // 用户在详情页改了名/标签，返回列表时要看到新值
                        libraryViewModel.refresh()
                        navController.popBackStack()
                    },
                    onOpenSettings = { navController.navigate(TopDestination.SETTINGS.route) { launchSingleTop = true } },
                    seekMs = seek,
                    onChanged = libraryViewModel::refresh,
                )
                // 跳一次就够：返回后再进来不该又跳回同一句
                androidx.compose.runtime.LaunchedEffect(seek) { pendingSeekMs = null }
            }

            composable(TopDestination.SETTINGS.route) {
                val settingsViewModel: io.github.tancheng33.tingo.ui.settings.SettingsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            io.github.tancheng33.tingo.ui.settings.SettingsViewModel(
                                store = container.models,
                                catalog = container.catalog,
                                settings = container.settings,
                                storageStats = { container.engine.storageStats() },
                                meetingsRoot = File(container.dataRoot, "meetings"),
                                installProgress = container.installProgress,
                            )
                        }
                    },
                )
                val models by settingsViewModel.state.collectAsStateWithLifecycle()
                val settingsContext = androidx.compose.ui.platform.LocalContext.current

                // 备份：用户选落点，zip 直接写进 SAF 给的流（没有临时文件、没有清理负担）
                val backupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
                ) { uri ->
                    if (uri != null) {
                        settingsViewModel.backupTo {
                            settingsContext.contentResolver.openOutputStream(uri)
                                ?: error("无法写入所选位置")
                        }
                    }
                }
                val restoreLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) {
                        settingsViewModel.restoreFrom {
                            settingsContext.contentResolver.openInputStream(uri)
                                ?: error("无法读取所选文件")
                        }
                    }
                }

                SettingsScreen(
                    language = container.settings.language,
                    engineVersion = container.engine.engineVersion(),
                    dataRoot = container.dataRoot.absolutePath,
                    onLanguageChange = onLanguageChange,
                    models = models,
                    onDeleteAsrModels = settingsViewModel::deleteAsr,
                    onSaveLlmConfig = settingsViewModel::saveLlmConfig,
                    onCleanupLeftovers = if ((models.storage?.let { it.leftoverBytes } ?: 0uL) > 0uL) {
                        {
                            runCatching { container.engine.cleanupLeftovers() }
                            settingsViewModel.refresh()
                        }
                    } else {
                        null
                    },
                    libraryBusy = models.libraryBusy,
                    libraryResult = models.libraryResult,
                    onBackupLibrary = { backupLauncher.launch("tingo-会议库备份.zip") },
                    onRestoreLibrary = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                )
            }
        }
    }
}

/** 用系统分享面板把导出的文件交给别的应用；FileProvider 只授权 cache/exports。 */
private fun shareFile(context: android.content.Context, file: File, mime: String = "text/markdown") {
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mime
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        android.content.Intent.createChooser(intent, context.getString(R.string.detail_export)),
    )
}

/** 首页底部的状态行：识别模型、纪要服务、版本（对应桌面侧栏的环境块）。 */
private fun homeStatusRows(container: AppContainer): List<HomeStatus> {
    val modelsReady = container.catalog.files.all { container.models.isReady(it) }
    val llm = container.settings.llm
    return listOf(
        HomeStatus(
            label = if (modelsReady) {
                "识别模型已就绪"
            } else {
                "识别模型未就位"
            },
            level = if (modelsReady) StatusLevel.None else StatusLevel.Warn,
        ),
        HomeStatus(
            label = if (llm.isConfigured) llm.model else "未配置纪要服务",
            level = if (llm.isConfigured) StatusLevel.None else StatusLevel.Bad,
        ),
        HomeStatus(label = "v${BuildConfig.VERSION_NAME}"),
    )
}

@Composable
private fun RecordRoute(
    recent: List<MeetingCard>,
    statusRows: List<HomeStatus>,
    onOpenMeeting: (String) -> Unit,
    state: RecordUiState,
    systemAudioStatus: RecordViewModel.SystemAudioStatus,
    systemAudioEnabled: Boolean,
    systemAudioDenied: Boolean,
    onSystemAudioChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
) {
    RecordScreen(
        state = state,
        recent = recent,
        statusRows = statusRows,
        onOpenRecent = onOpenMeeting,
        onStart = onStart,
        systemAudioEnabled = systemAudioEnabled,
        onSystemAudioChange = onSystemAudioChange,
        systemAudioMissing = systemAudioStatus == RecordViewModel.SystemAudioStatus.MISSING,
        systemAudioDenied = systemAudioDenied,
        onPause = onPause,
        onResume = onResume,
        onStop = onStop,
        onDiscard = onDiscard,
        onOpenMeeting = onOpenMeeting,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailRoute(
    container: AppContainer,
    meetingId: String,
    wide: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    /** 从搜索命中进来时要跳到这一句 */
    seekMs: Long? = null,
    /** 改名/标签等写操作后通知调用方刷新列表 */
    onChanged: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val exportScope = androidx.compose.runtime.rememberCoroutineScope()
    // 界面语言：转写里的"说话人N/Speaker N"、SRT 字幕都要跟界面一致。
    // 用应用设置里的语言，而不是系统语言——两者可以不同（设置页能单独切）。
    // 引擎只认 "zh"/"en"，设置里存的是 "zh-CN"/"en"
    val uiLang = if (container.settings.language == io.github.tancheng33.tingo.settings.AppLanguage.EN) "en" else "zh"
    // 归档 zip 的标题在"点导出"那一刻才知道（标题来自纪要），用持有者跨过 launcher 的声明顺序
    val pendingBundleTitle = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    // 归档导出走 SAF：用户自己挑落点（下载目录、U 盘、网盘），我们只负责把 zip 写进去
    val bundleLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val dir = container.meetingDir(meetingId)
        exportScope.launch {
            withContext(Dispatchers.IO) {
                val zip = MeetingExporter.exportBundle(
                    dir,
                    pendingBundleTitle.value,
                    File(context.cacheDir, "exports"),
                    emptyTitle = context.getString(R.string.detail_tab_minutes),
                )
                if (zip != null) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            zip.inputStream().use { it.copyTo(out) }
                        }
                    }
                }
            }
        }
    }
    val viewModel: DetailViewModel = viewModel(
        key = "detail-$meetingId",
        factory = viewModelFactory {
            initializer {
                DetailViewModel(
                    engine = container.engine,
                    // 猜说话人名字要问同一个模型服务：没配置就是 null，界面会提示去配置
                    chat = if (container.settings.llm.isConfigured) {
                        LlmChat(configProvider = { container.settings.llm }, client = UrlHttpJsonClient())
                    } else {
                        null
                    },
                    meetingId = meetingId,
                    audioFile = io.github.tancheng33.tingo.recording.RecordingTracks
                        .find(container.meetingDir(meetingId)),
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 播放中每 500 ms 拉一次进度；没有播放器时这个循环什么也不做
    androidx.compose.runtime.LaunchedEffect(state.playback.playing) {
        while (state.playback.playing) {
            kotlinx.coroutines.delay(500)
            viewModel.refreshPlayback()
        }
    }
    val meetingTitle = state.detail?.summary?.title.orEmpty().ifBlank { meetingId }

    val transcriptionViewModel: TranscriptionViewModel = viewModel(
        key = "transcribe-$meetingId",
        factory = viewModelFactory {
            initializer {
                TranscriptionViewModel(
                    ensureBundled = container::installBundledModels,
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
                        MinutesService(
                            RemoteMinutesGenerator(
                                configProvider = { container.settings.llm },
                                client = UrlHttpJsonClient(),
                            ),
                        )
                    },
                    isConfigured = { container.settings.llm.isConfigured },
                    meetingDir = meetingDir,
                    // 重新生成也要把上一版归档：与手工编辑走同一条保存路径
                    saveMinutes = { text -> container.engine.saveMinutes(meetingId, text) },
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
            // 点一句就跳到那一句开始播；没有录音时 ViewModel 里是空操作
            onSentenceClick = { startMs -> viewModel.playFrom(startMs.toLong()) },
            showMinutesTab = showMinutesTab,
            transcription = transcription,
            onTranscribe = transcriptionViewModel::start,
            onCancelTranscribe = transcriptionViewModel::cancel,
            minutesGeneration = minutesState,
            onGenerateMinutes = minutesViewModel::start,
            onCancelMinutes = minutesViewModel::cancel,
            onOpenSettings = onOpenSettings,
            initialSeekMs = seekMs,
            onPlayFrom = viewModel::playFrom,
            speakers = state.speakers,
            guessing = state.guessing,
            guessFailure = state.guessFailure,
            guessNote = state.guessNote,
            onRenameSpeaker = viewModel::renameSpeaker,
            onApplyGuess = viewModel::applyGuess,
            // 界面语言跟着系统走：提示词里的"说话人N"要和界面一致
            onGuessSpeakers = { viewModel.guessSpeakers(uiLang) },
            onRename = { title ->
                viewModel.rename(title)
                onChanged()
            },
            onSetTags = { tags ->
                viewModel.setTags(tags)
                onChanged()
            },
            history = state.history,
            preview = state.preview,
            onClosePreview = viewModel::closePreview,
            onSaveMinutes = viewModel::saveMinutes,
            onOpenVersion = viewModel::openVersion,
            onRestoreVersion = viewModel::restoreVersion,
            onDeleteVersion = viewModel::deleteVersion,
            onExportSrt = {
                exportScope.launch {
                    // 导出是引擎写的文件（命名与桌面一致），这里只负责把文件分享出去
                    val path = viewModel.exportSrt(uiLang)
                    if (path != null) shareFile(context, File(path))
                }
            },
            onExportBundle = {
                pendingBundleTitle.value = meetingTitle
                bundleLauncher.launch(
                    MeetingExporter.fileStem(meetingTitle, context.getString(R.string.detail_tab_minutes)) + ".zip",
                )
            },
            onExport = {
                // 读纪要 + 解析整份 transcript 可能上百毫秒，别在主线程做
                val dir = container.meetingDir(meetingId)
                exportScope.launch {
                    val exported = withContext(Dispatchers.IO) {
                        MeetingExporter.export(
                            dir,
                            meetingTitle,
                            File(context.cacheDir, "exports"),
                            transcriptTitle = context.getString(R.string.export_transcript_title),
                            emptyTitle = context.getString(R.string.detail_tab_minutes),
                        )
                    }
                    if (exported != null) shareFile(context, exported)
                }
            },
            exportEnabled = state.detail?.let { it.minutesText != null || it.transcript != null } == true,
            playback = state.playback,
            onTogglePlay = viewModel::togglePlayPause,
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

/**
 * 底部导航：桌面版侧栏的三项在手机上落成一条。
 *
 * 选中项不是"变个颜色"，而是换成一块细描边的白底卡片——桌面版侧栏就是这个语言，
 * 一眼能看出"我在哪"。
 */
@Composable
private fun TingoBottomBar(
    destinations: List<TopDestination>,
    isSelected: (TopDestination) -> Boolean,
    onSelect: (TopDestination) -> Unit,
) {
    val colors = TingoTheme.colors
    Column {
        androidx.compose.material3.HorizontalDivider(
            thickness = TingoDimens.hairline,
            color = colors.line,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bg)
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.navigationBars)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            destinations.forEach { destination ->
                val selected = isSelected(destination)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier
                        .weight(1f)
                        .background(if (selected) colors.card else colors.bg, TingoDimens.controlShape)
                        .then(
                            if (selected) {
                                Modifier.border(BorderStroke(TingoDimens.hairline, colors.line), TingoDimens.controlShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onSelect(destination) }
                        .semantics {
                            role = androidx.compose.ui.semantics.Role.Tab
                            this.selected = selected
                        }
                        .padding(vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = null,
                        tint = if (selected) colors.text else colors.muted,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(destination.labelRes),
                        style = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
                        color = if (selected) colors.text else colors.muted,
                    )
                }
            }
        }
    }
}
