package io.github.tancheng33.tingo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockTest {

    @Test
    fun zeroIsDoubleZero() {
        assertEquals("00:00", clockOf(0u))
    }

    @Test
    fun lessThanAnHourUsesMinutesAndSeconds() {
        assertEquals("01:05", clockOf(65_000u))
    }

    @Test
    fun hoursAppearWhenDurationIsLongEnough() {
        // 桌面端转写 76 分钟时显示 1:16:xx，这里保持同样的写法
        assertEquals("1:02:05", clockOf(3_725_000u))
    }

    @Test
    fun subSecondProgressIsTruncated() {
        assertEquals("00:01", clockOf(1_999u))
    }
}
