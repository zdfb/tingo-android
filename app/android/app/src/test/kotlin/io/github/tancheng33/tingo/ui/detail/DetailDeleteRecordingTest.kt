package io.github.tancheng33.tingo.ui.detail

import io.github.tancheng33.tingo.engine.MeetingDetail
import io.github.tancheng33.tingo.engine.MeetingSummary
import io.github.tancheng33.tingo.testing.FakeEnginePort
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 详情页「只删录音」：录音交给引擎处理，回来之后播放条必须消失。
 *
 * 这条最容易错的地方是"删了文件但仍留着播放器"：界面上播放条还在，点下去没声音，
 * 用户会以为播放器坏了。
 */
class DetailDeleteRecordingTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun meetingDir(withAudio: Boolean): File {
        val dir = Files.createTempDirectory("tingo-del-audio").toFile()
        if (withAudio) File(dir, "录音.wav").writeBytes(ByteArray(160_000))
        return dir
    }

    private val detail = MeetingDetail(
        summary = MeetingSummary(
            id = "2026-11-01_0900_周会",
            title = "周会",
            date = "2026-11-01",
            time = "09:00",
            oneLiner = "",
            hasMinutes = false,
            hasTranscript = false,
            unreadable = false,
            hasAudio = true,
            duration = "1 分钟",
            speakers = listOf("说话人1"),
            tags = emptyList(),
            archived = false,
            sizeBytes = 1uL,
        ),
        transcript = null,
        minutesText = null,
        fishbone = null,
    )

    private fun viewModel(dir: File, engine: FakeEnginePort): DetailViewModel {
        val audio = File(dir, "录音.wav")
        val withDetail = object : FakeEnginePort(onRead = { detail }) {
            override fun deleteAudio(id_: String, busyIds: List<String>): ULong =
                engine.deleteAudio(id_, busyIds)
        }
        return DetailViewModel(
            engine = withDetail,
            meetingId = "2026-11-01_0900_周会",
            audioFile = audio,
            io = dispatcher,
            language = { "zh" },
        )
    }

    @Test
    fun deletingTheRecordingCallsTheEngineAndDropsPlayback() = runTest(dispatcher) {
        val engine = FakeEnginePort()
        val vm = viewModel(meetingDir(withAudio = true), engine)
        advanceUntilIdle()
        assertTrue("有录音时播放条要在", vm.state.value.hasAudio)

        var freed = 0uL
        vm.deleteRecording(busyIds = listOf("别的会议")) { freed = it }
        advanceUntilIdle()

        assertEquals(listOf("2026-11-01_0900_周会"), engine.deletedAudio)
        assertEquals("忙碌列表要原样交给引擎", listOf("别的会议"), engine.lastDeleteAudioBusyIds)
        assertEquals("腾出的字节数要交给界面做提示", 2_048uL, freed)
        assertFalse("删完录音播放条必须消失", vm.state.value.hasAudio)
    }

    @Test
    fun aFailedDeleteKeepsTheRecordingUsable() = runTest(dispatcher) {
        val engine = object : FakeEnginePort() {
            override fun deleteAudio(id: String, busyIds: List<String>): ULong =
                throw io.github.tancheng33.tingo.engine.TingoFailure(
                    io.github.tancheng33.tingo.engine.FailureKind.INVALID_INPUT,
                    "这场会议还在处理中",
                )
        }
        // 注意：这里的 engine 只用来记录"抛错"，readMeeting 由 viewModel() 里的替身提供
        val vm = viewModel(meetingDir(withAudio = true), engine)
        advanceUntilIdle()

        vm.deleteRecording(emptyList()) { error("失败时不该报腾出空间") }
        advanceUntilIdle()

        assertEquals(
            io.github.tancheng33.tingo.engine.FailureKind.INVALID_INPUT,
            vm.state.value.failure,
        )
        assertTrue("失败之后录音还在，播放条不该消失", vm.state.value.hasAudio)
    }
}
