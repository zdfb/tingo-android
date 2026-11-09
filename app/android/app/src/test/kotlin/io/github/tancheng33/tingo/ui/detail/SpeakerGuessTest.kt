package io.github.tancheng33.tingo.ui.detail

import io.github.tancheng33.tingo.engine.SpeakerInfo
import io.github.tancheng33.tingo.engine.TingoEnginePort
import io.github.tancheng33.tingo.minutes.remote.HttpJsonClient
import io.github.tancheng33.tingo.minutes.remote.LlmChat
import io.github.tancheng33.tingo.settings.LlmConfig
import io.github.tancheng33.tingo.settings.LlmProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 说话人改名与 AI 猜名字。
 *
 * 引擎接口是"整表覆盖"，最容易出的事故是改一个人把其他人的名字清空；
 * 另一个是把 AI 的建议直接写进会议记录——猜错了转写稿就花了。
 */
class SpeakerGuessTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val config = LlmConfig(
        baseUrl = "https://api.example.com",
        apiKey = "sk-test",
        model = "m",
        protocol = LlmProtocol.OPENAI,
    )

    private class FakeClient(private val reply: () -> String) : HttpJsonClient {
        var lastBody: String? = null

        override fun post(url: String, headers: Map<String, String>, body: String): String {
            lastBody = body
            return reply()
        }

        override fun get(url: String, headers: Map<String, String>): String =
            throw AssertionError("猜名字只用 POST")
    }

    private fun engine(speakers: List<SpeakerInfo>) = FakeSpeakerEngine(speakers)

    /** 只关心说话人相关的几个方法，其余用委托的默认实现。 */
    private class FakeSpeakerEngine(
        private val speakers: List<SpeakerInfo>,
    ) : TingoEnginePort by io.github.tancheng33.tingo.testing.FakeEnginePort(
        onRead = { id ->
            io.github.tancheng33.tingo.engine.MeetingDetail(
                summary = io.github.tancheng33.tingo.engine.MeetingSummary(
                    id = id,
                    title = "周会",
                    date = "2026-11-02",
                    time = "10:00",
                    oneLiner = "",
                    hasMinutes = false,
                    hasTranscript = true,
                    unreadable = false,
                    hasAudio = false,
                    duration = "00:20",
                    speakers = emptyList(),
                    tags = emptyList(),
                    archived = false,
                    sizeBytes = 0uL,
                ),
                transcript = null,
                minutesText = null,
                fishbone = null,
            )
        },
    ) {
        var written: List<String>? = null
        var parsed: String? = null

        override fun speakers(id: String): List<SpeakerInfo> = speakers

        override fun setSpeakerNames(id: String, names: List<String>) {
            written = names
        }

        override fun speakerPrompt(id: String, language: String) =
            io.github.tancheng33.tingo.engine.SpeakerPrompt(
                system = "s",
                prompt = "p",
                speakerCount = speakers.size.toUInt(),
            )

        override fun parseSpeakerSuggestions(id: String, text: String): List<SpeakerInfo> {
            parsed = text
            return speakers.map { it.copy(suggested = "林舟") }
        }
    }

    @Test
    fun renamingOneSpeakerKeepsTheOthers() = runTest(dispatcher) {
        val engine = engine(
            listOf(
                SpeakerInfo(0u, "说话人1", "", ""),
                SpeakerInfo(1u, "李四", "", ""),
            ),
        )
        val vm = DetailViewModel(engine = engine, meetingId = "m", io = dispatcher)
        advanceUntilIdle()

        vm.renameSpeaker(0, "林舟")
        advanceUntilIdle()

        // 没动过的李四要原样带上；默认别名"说话人1"不能作为用户名字写回去
        assertEquals(listOf("林舟", "李四"), engine.written)
    }

    @Test
    fun renamingDoesNotPersistDefaultAliases() = runTest(dispatcher) {
        val engine = engine(listOf(SpeakerInfo(0u, "说话人1", "", ""), SpeakerInfo(1u, "说话人2", "", "")))
        val vm = DetailViewModel(engine = engine, meetingId = "m", io = dispatcher)
        advanceUntilIdle()

        vm.renameSpeaker(0, "林舟")
        advanceUntilIdle()

        // 空串 = 引擎跳过 = 仍然是默认别名；把"说话人2"发上去就等于给它起了个假名字
        assertEquals(listOf("林舟", ""), engine.written)
    }

    @Test
    fun emptySuggestionsReadAsNoClueInsteadOfSilentSuccess() = runTest(dispatcher) {
        // 模型答"没把握"：解析结果里一条建议都没有
        val engine = object : TingoEnginePort by engine(
            listOf(SpeakerInfo(0u, "说话人1", "", ""), SpeakerInfo(1u, "说话人2", "", "")),
        ) {
            override fun parseSpeakerSuggestions(id: String, text: String): List<SpeakerInfo> =
                listOf(SpeakerInfo(0u, "说话人1", "", ""), SpeakerInfo(1u, "说话人2", "", ""))
        }
        val vm = DetailViewModel(
            engine = engine,
            meetingId = "m",
            chat = LlmChat({ config }, FakeClient { """{"choices":[{"message":{"content":"{}"}}]}""" }),
            io = dispatcher,
        )
        advanceUntilIdle()

        vm.guessSpeakers("zh")
        advanceUntilIdle()

        // 猜完什么都没找到时界面要说一声，否则用户以为按钮没反应
        assertEquals(GuessNote.NoClue, vm.state.value.guessNote)
    }

    @Test
    fun unreadableReplyIsNotReportedAsRejected() = runTest(dispatcher) {
        val engine = object : TingoEnginePort by engine(listOf(SpeakerInfo(0u, "说话人1", "", ""))) {
            override fun parseSpeakerSuggestions(id: String, text: String): List<SpeakerInfo> =
                throw io.github.tancheng33.tingo.engine.TingoFailure(
                    io.github.tancheng33.tingo.engine.FailureKind.MALFORMED,
                    "模型输出里没有可解析的 suggestions",
                )
        }
        val vm = DetailViewModel(
            engine = engine,
            meetingId = "m",
            chat = LlmChat({ config }, FakeClient { """{"choices":[{"message":{"content":"抱歉"}}]}""" }),
            io = dispatcher,
        )
        advanceUntilIdle()

        vm.guessSpeakers("zh")
        advanceUntilIdle()

        // "没读懂回复"和"服务端拒绝"要分开：用户要做的事完全不同
        assertEquals(
            io.github.tancheng33.tingo.R.string.err_llm_unreadable,
            vm.state.value.guessFailure?.reasonRes,
        )
    }

    @Test
    fun guessSendsPromptAndShowsSuggestionWithoutWritingIt() = runTest(dispatcher) {
        val engine = engine(listOf(SpeakerInfo(0u, "说话人1", "", ""), SpeakerInfo(1u, "说话人2", "", "")))
        val client = FakeClient { """{"choices":[{"message":{"content":"{\"suggestions\":[]}"}}]}""" }
        val vm = DetailViewModel(
            engine = engine,
            meetingId = "m",
            chat = LlmChat({ config }, client),
            io = dispatcher,
        )
        advanceUntilIdle()

        vm.guessSpeakers("zh")
        advanceUntilIdle()

        // 建议只是建议：没有写回转写
        assertNull("猜名字不该自动写进会议记录", engine.written)
        assertEquals("林舟", vm.state.value.speakers.first().suggested)
        assertTrue("请求里要带上 system 指令：${client.lastBody}", client.lastBody!!.contains("\"role\":\"system\""))
    }

    @Test
    fun applyingAGuessWritesThatSpeakerOnly() = runTest(dispatcher) {
        val engine = engine(listOf(SpeakerInfo(0u, "说话人1", "", ""), SpeakerInfo(1u, "李四", "", "")))
        val vm = DetailViewModel(
            engine = engine,
            meetingId = "m",
            chat = LlmChat({ config }, FakeClient { """{"choices":[{"message":{"content":"{}"}}]}""" }),
            io = dispatcher,
        )
        advanceUntilIdle()
        vm.guessSpeakers("zh")
        advanceUntilIdle()

        vm.applyGuess(0)
        advanceUntilIdle()

        assertEquals(listOf("林舟", "李四"), engine.written)
    }

    @Test
    fun withoutConfiguredServiceGuessExplainsWhy() = runTest(dispatcher) {
        val vm = DetailViewModel(engine = engine(listOf(SpeakerInfo(0u, "说话人1", "", ""))), meetingId = "m", io = dispatcher)
        advanceUntilIdle()

        vm.guessSpeakers("zh")
        advanceUntilIdle()

        val failure = vm.state.value.guessFailure
        assertEquals(io.github.tancheng33.tingo.R.string.err_llm_not_configured, failure?.reasonRes)
    }
}
