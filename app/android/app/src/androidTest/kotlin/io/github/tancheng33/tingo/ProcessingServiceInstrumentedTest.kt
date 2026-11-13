package io.github.tancheng33.tingo

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tancheng33.tingo.recording.ProcessingService
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProcessingServiceInstrumentedTest {
    @Test
    fun foregroundProcessingShowsNotification() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
        }
        try {
            ProcessingService.start(context)
            val deadline = System.currentTimeMillis() + 10_000
            while (manager.activeNotifications.none { it.id == 112 } && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertTrue("processing service should publish a notification", manager.activeNotifications.any { it.id == 112 })
        } finally {
            ProcessingService.stop(context)
        }
    }
}
