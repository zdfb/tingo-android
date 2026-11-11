package io.github.tancheng33.tingo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.engine.FailureKind
import io.github.tancheng33.tingo.engine.Fishbone
import io.github.tancheng33.tingo.engine.FishboneBranch
import io.github.tancheng33.tingo.engine.MeetingDetail
import io.github.tancheng33.tingo.engine.MeetingSummary
import io.github.tancheng33.tingo.engine.MinutesDoc
import io.github.tancheng33.tingo.engine.MinutesSection
import io.github.tancheng33.tingo.engine.SectionKind
import io.github.tancheng33.tingo.engine.Sentence
import io.github.tancheng33.tingo.engine.SpeakerAlias
import io.github.tancheng33.tingo.engine.Transcript
import io.github.tancheng33.tingo.engine.Turn
import io.github.tancheng33.tingo.audio.PlaybackState
import io.github.tancheng33.tingo.ui.detail.DetailScreen
import io.github.tancheng33.tingo.ui.detail.DetailUiState
import io.github.tancheng33.tingo.ui.theme.TingoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

/** 详情页界面契约：章节化展示、鱼骨图、说话人别名与降级。 */
class DetailScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int): String = context.getString(id)

    private fun summary() = MeetingSummary(
        id = "2026-10-28_1430_官网注册转化复盘",
        title = "官网注册转化复盘",
        date = "2026-10-28",
        time = "14:30",
        oneLiner = "转化率掉了三成。",
        hasMinutes = true,
        hasTranscript = true,
        unreadable = false,
        hasAudio = true,
        duration = "02:00",
        speakers = listOf("林舟"),
        tags = emptyList(),
        archived = false,
        sizeBytes = 1024uL,
    )

    private fun detail(
        transcript: Transcript? = null,
        fishbone: Fishbone? = null,
    ) = MeetingDetail(
        summary = summary(),
        transcript = transcript,
        minutesText = "## 一句话总结\n转化率掉了三成。",
        fishbone = fishbone,
    )

    private fun render(state: DetailUiState, tab: Int = 0) {
        compose.setContent {
            TingoTheme {
                DetailScreen(state = state, onRetry = {}, onSentenceClick = {}, initialTab = tab)
            }
        }
    }

    @Test
    fun rendersMinutesSections() {
        val doc = MinutesDoc(
            title = "官网注册转化复盘",
            sections = listOf(
                MinutesSection(kind = SectionKind.ONE_LINER, heading = "一句话总结", body = "转化率掉了三成。"),
                MinutesSection(kind = SectionKind.DECISIONS, heading = "决议", body = "- 先修注册页"),
            ),
        )
        render(DetailUiState(loading = false, detail = detail(), minutes = doc))

        compose.onNodeWithText("一句话总结").assertIsDisplayed()
        compose.onNodeWithText("决议").assertIsDisplayed()
        compose.onNodeWithText("先修注册页", substring = true).assertIsDisplayed()
    }

    @Test
    fun meetingTitleIsAHeadingAndTabsKeepPlaybackActions() {
        val doc = MinutesDoc(
            title = "官网注册转化复盘",
            sections = listOf(MinutesSection(SectionKind.ONE_LINER, "一句话总结", "转化率掉了三成。")),
        )
        val transcript = Transcript(
            source = "录音.wav",
            durationMs = 120_000uL,
            aliases = listOf(SpeakerAlias(speaker = 0u, name = "林舟")),
            turns = listOf(
                Turn(
                    speakerName = "林舟",
                    startMs = 5_000uL,
                    endMs = 9_000uL,
                    text = "我们先看注册页。",
                    sentences = listOf(Sentence(0u, 5_000uL, 9_000uL, "我们先看注册页。")),
                ),
            ),
        )
        var playCount = 0
        compose.setContent {
            TingoTheme {
                DetailScreen(
                    state = DetailUiState(loading = false, detail = detail(transcript), minutes = doc),
                    onRetry = {},
                    onSentenceClick = {},
                    playback = PlaybackState(available = true, durationMs = 120_000),
                    onTogglePlay = { playCount++ },
                )
            }
        }

        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit)
                .and(androidx.compose.ui.test.hasText("官网注册转化复盘")),
        ).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.player_play)).performClick()
        compose.onNodeWithText(text(R.string.detail_tab_transcript)).performClick()
        compose.onNodeWithText("我们先看注册页。", substring = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.detail_tab_minutes)).performClick()
        compose.onNodeWithText("一句话总结").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, playCount) }
    }

    @Test
    fun loadingStateShowsAnAccessiblePageTitle() {
        render(DetailUiState(loading = true))

        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit)
                .and(androidx.compose.ui.test.hasText(text(R.string.nav_meetings))),
        ).assertIsDisplayed()
    }

    @Test
    fun rendersFishboneBranches() {
        val doc = MinutesDoc(
            title = "复盘",
            sections = listOf(MinutesSection(SectionKind.ROOT_CAUSE, "原因分析", "```fishbone\n{}\n```")),
        )
        val fishbone = Fishbone(
            problem = "为什么转化掉了",
            branches = listOf(FishboneBranch(title = "注册页太慢", detail = "首屏 4 秒", refs = listOf("00:42"))),
        )
        render(DetailUiState(loading = false, detail = detail(fishbone = fishbone), minutes = doc))

        compose.onNodeWithText("为什么转化掉了").assertIsDisplayed()
        compose.onNodeWithText("注册页太慢").assertIsDisplayed()
        compose.onNodeWithText("00:42").assertIsDisplayed()
    }

    @Test
    fun showsTranscriptSpeakerAlias() {
        val transcript = Transcript(
            source = "录音.m4a",
            durationMs = 120_055uL,
            aliases = listOf(SpeakerAlias(speaker = 0u, name = "林舟")),
            turns = listOf(
                Turn(
                    speakerName = "林舟",
                    startMs = 5_000uL,
                    endMs = 9_000uL,
                    text = "我们先看注册页。",
                    sentences = listOf(Sentence(0u, 5_000uL, 9_000uL, "我们先看注册页。")),
                ),
            ),
        )
        // 直接以转写栏打开（与其依赖点击注入，不如把初始栏位做成参数——将来"从搜索跳转"也要用）
        render(DetailUiState(loading = false, detail = detail(transcript = transcript), minutes = null), tab = 1)
        compose.onNodeWithText("林舟").assertIsDisplayed()
        compose.onNodeWithText("00:05").assertIsDisplayed()
        compose.onNodeWithText("我们先看注册页。").assertIsDisplayed()
    }

    @Test
    fun minutesParseFailureDegradesOnlyThatPane() {
        render(
            DetailUiState(
                loading = false,
                detail = detail(),
                minutesFailure = FailureKind.MALFORMED,
            ),
        )
        compose.onNodeWithText(text(R.string.detail_minutes_parse_failed).format(text(R.string.error_malformed)), substring = true).assertIsDisplayed()
    }

    @Test
    fun unreadableMeetingShowsErrorWithRetry() {
        render(DetailUiState(loading = false, failure = FailureKind.MALFORMED))
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit)
                .and(androidx.compose.ui.test.hasText(text(R.string.nav_meetings))),
        ).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.error_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_refresh)).assertIsDisplayed()
    }
}
