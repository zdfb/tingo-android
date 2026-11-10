package io.github.tancheng33.tingo

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 品牌图与桌面图标的资源类型。
 *
 * 这类错误不会在编译期暴露，也不会被 JVM 单测发现：`painterResource(@mipmap/ic_launcher)`
 * 在 API 26+ 拿到的是 adaptive-icon 的 XML，Compose 只认 VectorDrawable 与位图，
 * 一进会议库就崩。曾经真的这样崩过一次，所以这里把"类型"本身钉住。
 */
class BrandAssetsInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun brandMarkIsARasterImageComposeCanDraw() {
        val drawable: Drawable = context.getDrawable(R.drawable.tingo_mark)
            ?: throw AssertionError("找不到品牌图 drawable")

        assertTrue(
            "品牌图必须是位图（adaptive-icon 的 XML 会让 painterResource 直接抛异常）",
            drawable is BitmapDrawable,
        )
    }

    @Test
    fun launcherIconIsOurOwnResource() {
        // 只看"能不能拿到图标"没有意义：没有 android:icon 时系统也会给一个默认图标。
        // 这里比的是资源 id —— 声明成 @mipmap/ic_launcher 才会等于我们这张
        val declared = context.applicationInfo.icon

        assertEquals(
            "应用图标不是我们声明的 @mipmap/ic_launcher（manifest 漏了 android:icon？）",
            R.mipmap.ic_launcher,
            declared,
        )
        // 图标资源本身必须能真的解出来（XML 引错时这里会抛 Resources.NotFoundException）
        assertNotNull(
            "@mipmap/ic_launcher 解不出来",
            context.getDrawable(R.mipmap.ic_launcher),
        )
    }
}
