package io.github.tancheng33.tingo

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中英文资源必须成对。
 *
 * 这一层最容易被忽略的退化是"加了中文文案忘了英文"——运行时不会报错，
 * 只是英文用户看到中文。所以用测试把两份 key 集合钉在一起。
 */
class StringsParityTest {

    private fun keys(path: String): Set<String> =
        Regex("<string name=\"([^\"]+)\"")
            .findAll(File(path).readText())
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun chineseAndEnglishDeclareTheSameKeys() {
        val zh = keys("src/main/res/values/strings.xml")
        val en = keys("src/main/res/values-en/strings.xml")

        assertTrue(zh.isNotEmpty())
        assertEquals("只在中文里有的 key: ${zh - en}", emptySet<String>(), zh - en)
        assertEquals("只在英文里有的 key: ${en - zh}", emptySet<String>(), en - zh)
    }
}
