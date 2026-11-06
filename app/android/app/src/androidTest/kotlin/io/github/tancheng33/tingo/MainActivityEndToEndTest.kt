package io.github.tancheng33.tingo

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/**
 * 端到端纵切：真实 Activity → ViewModel → 仓储 → 原生引擎 → 磁盘上的会议目录。
 *
 * 夹具是桌面版 v0.5.2 的真实产出，所以这条测试同时证明了"手机能直接打开电脑录的会议"。
 * 夹具由测试自己以应用 uid 写入会议库——用 adb push 写进去的文件属主是 shell，应用读不到。
 */
class MainActivityEndToEndTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun libraryListsMeetingsFromDisk() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("官网注册转化复盘").fetchSemanticsNodes().isNotEmpty()
        }
        // 文案要按"应用当前语言"取：MainActivity 会把语言注入 Context（默认中文），
        // 而 instrumentation 的 targetContext 是系统语言，两者不一定一致。
        val appText = compose.activity.getString(R.string.library_count).format(2)

        // 两场会议都进了列表
        compose.onNodeWithText(appText).assertIsDisplayed()

        // 最新一场在第一行
        compose.onNodeWithText("官网注册转化复盘").assertIsDisplayed()

        // 第二场要滚动后才会被 LazyColumn 组合出来
        // 第二份夹具没有 meta.json，标题取自纪要 H1；日期一定在卡片上，用它判定更稳
        compose.onNode(hasScrollAction()).performScrollToIndex(1)
        compose.onNodeWithText("2026-10-27", substring = true).assertIsDisplayed()
    }

    companion object {
        private const val FIXTURE = "2026-10-28_1430_官网注册转化复盘"

        @BeforeClass
        @JvmStatic
        fun seedLibrary() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val target = instrumentation.targetContext
            val assets = instrumentation.context.assets
            val meetings = File(File(target.getExternalFilesDir(null), "tingo"), "meetings")
            // 其他测试类会往同一个会议库里写用例目录（比如损坏的目录），先清空保证这场断言看到的就是两份夹具
            meetings.deleteRecursively()
            meetings.mkdirs()

            for (name in assets.list("fixtures").orEmpty()) {
                val dir = File(meetings, name).apply { mkdirs() }
                for (file in assets.list("fixtures/$name").orEmpty()) {
                    assets.open("fixtures/$name/$file").use { input ->
                        File(dir, file).outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            check(File(meetings, FIXTURE).isDirectory) { "fixture seeding failed" }
        }
    }
}
