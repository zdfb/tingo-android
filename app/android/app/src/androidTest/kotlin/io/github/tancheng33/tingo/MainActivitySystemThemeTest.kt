package io.github.tancheng33.tingo

import android.content.res.Configuration
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test

class MainActivitySystemThemeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun changingSystemNightModeRecreatesTheActivityToApplyTheTheme() {
        val currentMode = compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val originalNight = currentMode == Configuration.UI_MODE_NIGHT_YES
        val activityBeforeChange = compose.activity

        try {
            setSystemNightMode(enabled = !originalNight)
            compose.waitUntil(timeoutMillis = 10_000) {
                val newMode = compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                newMode != currentMode
            }

            assertNotSame("System theme changes should refresh the activity theme", activityBeforeChange, compose.activity)
        } finally {
            setSystemNightMode(enabled = originalNight)
        }
    }

    private fun setSystemNightMode(enabled: Boolean) {
        val mode = if (enabled) "yes" else "no"
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cmd uimode night $mode")
        descriptor.use {
            FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
        }
    }
}
