package io.github.tancheng33.tingo.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进行中任务登记表。
 *
 * 出错的方式都很安静：同一条活登记两次会变成两行；干完不撤会一直挂在首页上；
 * 会议删了还挂着，点进去是一场不存在的会议。
 */
class TaskCenterTest {

    @Test
    fun registersATaskWithProgress() {
        val center = TaskCenter()

        center.start("t:a", meetingId = "a", title = "项目A 周会", kind = TaskKind.TRANSCRIBE)
        center.progress("t:a", 0.4f)

        val task = center.tasks.value.single()
        assertEquals("项目A 周会", task.title)
        assertEquals(TaskKind.TRANSCRIBE, task.kind)
        assertEquals(0.4f, task.fraction)
    }

    @Test
    fun registeringTheSameTaskTwiceUpdatesInsteadOfDuplicating() {
        val center = TaskCenter()

        center.start("t:a", "a", "项目A", TaskKind.TRANSCRIBE)
        center.start("t:a", "a", "项目A", TaskKind.TRANSCRIBE, fraction = 0.2f)

        assertEquals("同一件活不该出现两行", 1, center.tasks.value.size)
        assertEquals(0.2f, center.tasks.value.single().fraction)
    }

    @Test
    fun twoKindsOnOneMeetingAreTwoRows() {
        val center = TaskCenter()

        center.start(RunningTask.keyOf(TaskKind.TRANSCRIBE, "a"), "a", "项目A", TaskKind.TRANSCRIBE)
        center.start(RunningTask.keyOf(TaskKind.MINUTES, "a"), "a", "项目A", TaskKind.MINUTES)

        assertEquals(2, center.tasks.value.size)
    }

    @Test
    fun finishedTasksDisappear() {
        val center = TaskCenter()
        center.start("t:a", "a", "项目A", TaskKind.TRANSCRIBE)

        center.finish("t:a")

        assertTrue("干完就该从首页消失", center.tasks.value.isEmpty())
    }

    @Test
    fun forgettingAMeetingDropsAllItsTasks() {
        val center = TaskCenter()
        center.start(RunningTask.keyOf(TaskKind.TRANSCRIBE, "a"), "a", "项目A", TaskKind.TRANSCRIBE)
        center.start(RunningTask.keyOf(TaskKind.MINUTES, "a"), "a", "项目A", TaskKind.MINUTES)
        center.start(RunningTask.keyOf(TaskKind.TRANSCRIBE, "b"), "b", "项目B", TaskKind.TRANSCRIBE)

        center.forget("a")

        assertEquals(listOf("b"), center.tasks.value.map { it.meetingId })
    }

    @Test
    fun progressOnAnUnknownKeyChangesNothing() {
        val center = TaskCenter()
        center.start("t:a", "a", "项目A", TaskKind.TRANSCRIBE, fraction = 0.1f)

        // 任务刚被注销、进度回调后到：不能凭空造出一行，也不能把已有的改坏
        center.progress("t:ghost", 0.9f)

        assertEquals(1, center.tasks.value.size)
        assertEquals(0.1f, center.tasks.value.single().fraction)
    }
}

/**
 * 目录名 → 标题。
 *
 * 用"取最后一个下划线之后"会在带下划线的标题上翻车：英文示例会议的名字里
 * 就有好几个下划线，那样只会剩下最后一个词。
 */
class MeetingTitleTest {

    @Test
    fun stripsTheTimestampPrefix() {
        assertEquals("项目A", meetingTitleOf("2026-10-27_0930_项目A"))
    }

    @Test
    fun keepsUnderscoresInsideTheTitle() {
        assertEquals(
            "Sample__Signup_conversion_review",
            meetingTitleOf("2026-10-28_1400_Sample__Signup_conversion_review"),
        )
    }

    @Test
    fun keepsTheDuplicateSuffix() {
        // 同一分钟录两次时引擎会加 _2 后缀：那是会议名的一部分，不该被当成时间前缀吃掉
        assertEquals("项目A_2", meetingTitleOf("2026-10-27_0930_项目A_2"))
    }

    @Test
    fun fallsBackToTheRawNameWhenThereIsNoPrefix() {
        assertEquals("随手录的一段", meetingTitleOf("随手录的一段"))
    }
}
