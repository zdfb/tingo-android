package io.github.tancheng33.tingo.minutes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词裁剪：预算按 token 给，裁剪结果必须真的装得下，且明确告知模型"后面没了"。
 *
 * 这里用一个"每 2 个字符算 1 个 token"的假分词器，逻辑与真实分词器无关，
 * 只验证二分裁剪与标记拼接本身。
 */
class PromptBudgetTest {

    private fun fakeCounter(text: String): Int = text.length / 2

    private fun prompt(lines: Int): String {
        val body = (1..lines).joinToString("\n") { "[00:0$it] 说话人1：第 $it 句内容，讨论注册页改版" }
        return "请整理成会议纪要。\n\n转写：\n$body\n"
    }

    @Test
    fun shortPromptIsReturnedUnchanged() {
        val p = prompt(3)
        assertEquals(p, MinutesPrompt.trimToTokenBudget(p, budgetTokens = 10_000, ::fakeCounter))
    }

    @Test
    fun longPromptIsTrimmedToBudgetAndSaysSo() {
        val p = prompt(400)
        val budget = 200
        val trimmed = MinutesPrompt.trimToTokenBudget(p, budgetTokens = budget, ::fakeCounter)

        assertTrue("裁剪后必须装进预算：${fakeCounter(trimmed)} > $budget", fakeCounter(trimmed) <= budget)
        assertTrue("必须明确告诉模型后面被省略了", trimmed.contains("已省略"))
        assertTrue("指令部分必须原样保留", trimmed.startsWith("请整理成会议纪要。"))
        assertTrue("裁剪后应仍有内容", trimmed.contains("[00:01]"))
    }

    @Test
    fun zeroBudgetDisablesTrimming() {
        val p = prompt(400)
        assertEquals(p, MinutesPrompt.trimToTokenBudget(p, budgetTokens = 0, ::fakeCounter))
    }
}
