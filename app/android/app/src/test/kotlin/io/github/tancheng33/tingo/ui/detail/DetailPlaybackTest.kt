package io.github.tancheng33.tingo.ui.detail

import io.github.tancheng33.tingo.audio.AudioPlayer
import io.github.tancheng33.tingo.engine.AudioCleanup
import io.github.tancheng33.tingo.engine.Capabilities
import io.github.tancheng33.tingo.engine.MeetingDetail
import io.github.tancheng33.tingo.engine.MeetingSummary
import io.github.tancheng33.tingo.engine.TingoEnginePort
import io.github.tancheng33.tingo.transcription.Transcript
import io.github.tancheng33.tingo.transcription.TranscriptSentence
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

/** 播放：点击某一句要跳到那一句、要真的开始播、没有录音时不要出现播放条。 */
class DetailPlaybackTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakePlayer : AudioPlayer {
        var preparedWith: File? = null
        var durationMs = 0L
        var playing = false
        var seeks = mutableListOf<Long>()
        var releases = 0

        override fun prepare(file: File, onReady: (Long) -> Unit) {
            preparedWith = file
            onReady(durationMs)
        }

        override fun play() { playing = true }

        override fun pause() { playing = false }

        override fun seekTo(positionMs: Long) { seeks += positionMs }

        override val positionMs: Long
            get() = seeks.lastOrNull() ?: 0L

        override val isPlaying: Boolean
            get() = playing

        override fun release() { releases++ }
    }

    private fun meetingDir(withAudio: Boolean): File {
        val dir = Files.createTempDirectory("tingo-playback").toFile()
        if (withAudio) File(dir, "录音.wav").writeBytes(ByteArray(1_000) { 1 })
        return dir
    }

    private fun engine(): TingoEnginePort = object : TingoEnginePort {
        override fun engineVersion(): String = "test"
        override fun capabilities(): Capabilities = Capabilities(
            meetingStorage = true,
            transcriptParse = true,
            minutesParse = true,
            fishboneParse = true,
            speakerNaming = true,
            termsExtract = true,
        )
        override fun meetingsRoot(): String = "/tmp"
        override fun scanLibrary(): List<MeetingSummary> = emptyList()
        override fun readMeeting(id: String, language: String) = MeetingDetail(
            summary = MeetingSummary(
                id = id,
                title = "周会",
                date = "2026-11-01",
                time = "09:00",
                duration = "01:00",
                oneLiner = "",
                tags = emptyList(),
                archived = false,
                hasTranscript = true,
                hasAudio = true,
                hasMinutes = false,
                unreadable = false,
                speakers = listOf("说话人1"),
                sizeBytes = 1uL,
            ),
            transcript = null,
            minutesText = null,
            fishbone = null,
        )
        override fun parseMinutes(text: String) = error("不涉及")
        override fun parseFishbone(text: String) = null
        override fun setArchived(id: String, archived: Boolean) = Unit
        override fun deleteMeeting(id: String): ULong = 0uL
        override fun renameMeeting(id: String, title: String) = Unit
        override fun setTags(id: String, tags: List<String>): List<String> = tags
        override fun batch(ids: List<String>, action: String, tag: String): List<io.github.tancheng33.tingo.engine.BatchOutcome> =
            ids.map { io.github.tancheng33.tingo.engine.BatchOutcome(it, true, "") }
        override fun search(query: String, language: String) =
            io.github.tancheng33.tingo.engine.SearchResults(query, emptyList(), emptyList())
        override fun storageStats() = io.github.tancheng33.tingo.engine.StorageStats(0u, 0uL, 0uL, 0uL, emptyList())
        override fun deleteToTrash(id: String): ULong = 0uL
        override fun restoreFromTrash(id: String) = Unit
        override fun listTrash(): List<io.github.tancheng33.tingo.engine.TrashItem> = emptyList()
        override fun purgeTrash(id: String): ULong = 0uL
        override fun cleanupLeftovers(): ULong = 0uL
        override fun cleanupAudio(olderThanDays: UInt, archivedOnly: Boolean, busyIds: List<String>): AudioCleanup =
            AudioCleanup(freedBytes = 0uL, meetings = 0u, failed = 0u)
        override fun speakers(id: String, language: String) =
            emptyList<io.github.tancheng33.tingo.engine.SpeakerInfo>()
        override fun setSpeakerNames(id: String, names: List<String>) = Unit
        override fun speakerPrompt(id: String, language: String) =
            io.github.tancheng33.tingo.engine.SpeakerPrompt("", "", 0u)
        override fun parseSpeakerSuggestions(id: String, text: String) =
            emptyList<io.github.tancheng33.tingo.engine.SpeakerInfo>()
        override fun glossary() = emptyList<io.github.tancheng33.tingo.engine.GlossaryTerm>()
        override fun minutesHistory(id: String) =
            emptyList<io.github.tancheng33.tingo.engine.MinutesVersionInfo>()
        override fun minutesVersion(id: String, versionId: String) = ""
        override fun restoreMinutesVersion(id: String, versionId: String) = Unit
        override fun deleteMinutesVersion(id: String, versionId: String) = Unit
        override fun saveMinutes(id: String, text: String) = Unit
        override fun exportSrt(id: String, language: String) = ""
        override fun exportExcalidraw(id: String) = emptyList<String>()
        override fun importAudio(wavPath: String, title: String, modifiedMs: Long) =
            io.github.tancheng33.tingo.engine.ImportedMeeting("id", "/tmp/id")
        override fun installDemoMeeting(sourceDir: String, english: Boolean) =
            io.github.tancheng33.tingo.engine.ImportedMeeting("demo", "/tmp/demo")
        override fun hotwords() = emptyList<String>()
        override fun setHotwords(words: List<String>) = words
        override fun minutesGlossary(language: String) = ""
    }

    private fun viewModel(dir: File, player: FakePlayer) = DetailViewModel(
        engine = engine(),
        meetingId = "2026-11-01_0900_周会",
        audioFile = File(dir, "录音.wav"),
        playerFactory = { player },
        io = dispatcher,
        language = { "zh" },
    )

    @Test
    fun playbackBecomesAvailableWithRecording() = runTest(dispatcher) {
        val player = FakePlayer().apply { durationMs = 60_000 }
        val vm = viewModel(meetingDir(withAudio = true), player)
        advanceUntilIdle()

        val playback = vm.state.value.playback
        assertTrue("有录音就该能播", playback.available)
        assertEquals(60_000L, playback.durationMs)
        assertFalse(playback.playing)
    }

    @Test
    fun noRecordingMeansNoPlayer() = runTest(dispatcher) {
        val player = FakePlayer().apply { durationMs = 60_000 }
        val vm = viewModel(meetingDir(withAudio = false), player)
        advanceUntilIdle()

        assertFalse("没有录音不该出现播放条", vm.state.value.playback.available)
        assertEquals(null, player.preparedWith)

        vm.playFrom(1_000)
        assertTrue("没有播放器时点击是空操作", player.seeks.isEmpty())
    }

    @Test
    fun headerOnlyWavCountsAsNoRecording() = runTest(dispatcher) {
        val dir = Files.createTempDirectory("tingo-playback").toFile()
        File(dir, "录音.wav").writeBytes(ByteArray(44))
        val player = FakePlayer()
        val vm = DetailViewModel(
            engine = engine(),
            meetingId = "id",
            audioFile = File(dir, "录音.wav"),
            playerFactory = { player },
            io = dispatcher,
            language = { "zh" },
        )
        advanceUntilIdle()

        assertFalse(vm.state.value.playback.available)
    }

    @Test
    fun clickingSentenceSeeksAndStartsPlaying() = runTest(dispatcher) {
        val player = FakePlayer().apply { durationMs = 60_000 }
        val vm = viewModel(meetingDir(withAudio = true), player)
        advanceUntilIdle()

        vm.playFrom(12_000)

        assertEquals(listOf(12_000L), player.seeks)
        assertTrue(player.playing)
        assertTrue(vm.state.value.playback.playing)
        assertEquals(12_000L, vm.state.value.playback.positionMs)
    }

    @Test
    fun togglePausesAndResumes() = runTest(dispatcher) {
        val player = FakePlayer().apply { durationMs = 60_000 }
        val vm = viewModel(meetingDir(withAudio = true), player)
        advanceUntilIdle()

        vm.togglePlayPause()
        assertTrue(player.playing)

        vm.togglePlayPause()
        assertFalse(player.playing)
        assertFalse(vm.state.value.playback.playing)
    }

    @Test
    fun progressIsDerivedFromPosition() = runTest(dispatcher) {
        val player = FakePlayer().apply { durationMs = 60_000 }
        val vm = viewModel(meetingDir(withAudio = true), player)
        advanceUntilIdle()

        vm.playFrom(15_000)

        assertEquals(0.25f, vm.state.value.playback.progress, 0.001f)
    }

    @Test
    fun playerIsReleasedWhenViewModelClears() = runTest(dispatcher) {
        val player = FakePlayer().apply { durationMs = 60_000 }
        val vm = viewModel(meetingDir(withAudio = true), player)
        advanceUntilIdle()

        vm.releasePlayerNow()

        assertEquals(1, player.releases)
    }
}
