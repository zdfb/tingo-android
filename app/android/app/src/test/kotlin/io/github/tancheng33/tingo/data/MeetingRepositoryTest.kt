package io.github.tancheng33.tingo.data

import io.github.tancheng33.tingo.engine.FailureKind
import io.github.tancheng33.tingo.engine.TingoFailure
import io.github.tancheng33.tingo.testing.FakeEnginePort
import io.github.tancheng33.tingo.testing.summary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeetingRepositoryTest {

    private val dispatcher = StandardTestDispatcher()

    @Test
    fun mapsEngineFieldsOntoCards() = runTest(dispatcher) {
        val engine = FakeEnginePort(
            onScan = {
                listOf(
                    summary(
                        id = "2026-03-05_1000_上线协调会",
                        title = "上线协调会",
                        date = "2026-03-05",
                        time = "10:00",
                        oneLiner = "定了下周一上线。",
                        tags = listOf("上线"),
                        hasMinutes = true,
                        duration = "01:02:05",
                    ),
                )
            },
        )
        val cards = MeetingRepository(engine, dispatcher).load().items

        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals("上线协调会", card.title)
        assertEquals("2026-03-05", card.date)
        assertEquals("10:00", card.time)
        assertEquals("01:02:05", card.duration)
        assertEquals("定了下周一上线。", card.oneLiner)
        assertEquals(listOf("上线"), card.tags)
        assertTrue(card.hasMinutes)
        assertTrue(card.hasAudio)
    }

    @Test
    fun keepsEngineOrder() = runTest(dispatcher) {
        // 排序是引擎的职责（目录命名规则只有它知道），仓储不能擅自重排
        val engine = FakeEnginePort(
            onScan = {
                listOf(
                    summary(id = "b", date = "2026-03-05"),
                    summary(id = "a", date = "2026-02-01"),
                )
            },
        )
        val ids = MeetingRepository(engine, dispatcher).load().items.map { it.id }
        assertEquals(listOf("b", "a"), ids)
    }

    @Test
    fun unreadableDirectoryIsKeptAndFlagged() = runTest(dispatcher) {
        val engine = FakeEnginePort(
            onScan = {
                listOf(
                    summary(id = "good"),
                    summary(id = "broken", unreadable = true, hasTranscript = false),
                )
            },
        )
        val cards = MeetingRepository(engine, dispatcher).load().items

        assertEquals(2, cards.size)
        assertTrue(cards.single { it.id == "broken" }.unreadable)
        assertTrue(!cards.single { it.id == "good" }.unreadable)
    }

    @Test
    fun scanFailurePropagatesAsTingoFailure() = runTest(dispatcher) {
        val engine = FakeEnginePort(onScan = { throw TingoFailure(FailureKind.PERMISSION_DENIED, "root not readable") })
        val error = runCatching { MeetingRepository(engine, dispatcher).load() }.exceptionOrNull()
        assertTrue(error is TingoFailure)
        assertEquals(FailureKind.PERMISSION_DENIED, (error as TingoFailure).kind)
    }
}
