package io.github.tancheng33.tingo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnbundledModelInstrumentedTest {
    @Test
    fun speechModelsAreDownloadedInsteadOfBundled() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        assertEquals(0, assets.list("models")?.size ?: 0)
    }
}
