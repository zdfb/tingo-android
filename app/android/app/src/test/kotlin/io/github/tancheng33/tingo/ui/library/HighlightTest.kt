package io.github.tancheng33.tingo.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightTest {

    @Test
    fun marksEveryOccurrence() {
        val pieces = Highlight.split("周会复盘：周会纪要", "周会")

        assertEquals(
            listOf(
                Highlight.Piece("周会", true),
                Highlight.Piece("复盘：", false),
                Highlight.Piece("周会", true),
                Highlight.Piece("纪要", false),
            ),
            pieces,
        )
    }

    @Test
    fun matchingIsCaseInsensitiveForLatinText() {
        val pieces = Highlight.split("Q4 Review 与 q4 目标", "q4")

        assertEquals(2, pieces.count { it.matched })
        assertEquals("Q4", pieces.first { it.matched }.text)
    }

    @Test
    fun blankNeedleMeansNoHighlight() {
        assertEquals(listOf(Highlight.Piece("整段文字", false)), Highlight.split("整段文字", "   "))
    }

    @Test
    fun missingNeedleReturnsWholeText() {
        assertEquals(listOf(Highlight.Piece("无命中", false)), Highlight.split("无命中", "别的"))
    }

    @Test
    fun textIdenticalToNeedleIsOneMatchedPiece() {
        assertEquals(listOf(Highlight.Piece("周会", true)), Highlight.split("周会", "周会"))
    }

    @Test
    fun piecesReconstructTheOriginalText() {
        val text = "官网注册转化率下降，需要复盘官网落地页"
        val pieces = Highlight.split(text, "官网")

        assertEquals(text, pieces.joinToString("") { it.text })
        assertTrue(pieces.all { it.text.isNotEmpty() })
    }
}
