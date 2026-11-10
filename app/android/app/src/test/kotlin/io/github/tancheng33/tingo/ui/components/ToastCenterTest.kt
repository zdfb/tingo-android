package io.github.tancheng33.tingo.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 全局提示的调度。
 *
 * 出错的方式很安静：同样的文字连出两次却只计一次时，第二条会被上一条的计时提前掐掉；
 * 用户手动关掉一条时误伤刚进来的新提示，表现是"点了没反应"。
 */
class ToastCenterTest {

    @Test
    fun showsTheLatestMessage() {
        val center = ToastCenter()

        center.show("会议库写入失败")

        assertEquals("会议库写入失败", center.current.value?.text)
    }

    @Test
    fun repeatedTextGetsANewIdSoTheTimerRestarts() {
        val center = ToastCenter()

        center.show("同一条")
        val first = center.current.value?.id
        center.show("同一条")
        val second = center.current.value?.id

        assertNotEquals("同样的文字也要重新计时", first, second)
    }

    @Test
    fun blankMessagesAreIgnored() {
        val center = ToastCenter()

        center.show("  ")

        assertNull("空提示不该占住底部", center.current.value)
    }

    @Test
    fun dismissingAnOldMessageKeepsTheNewerOne() {
        val center = ToastCenter()
        center.show("旧的")
        val old = center.current.value!!.id
        center.show("新的")

        // 旧提示的计时器到点时，新的可能已经上屏了：不能把它一起清掉
        center.dismiss(old)

        assertEquals("新的", center.current.value?.text)

        center.dismiss(center.current.value!!.id)
        assertNull(center.current.value)
    }
}
