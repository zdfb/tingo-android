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
        val result = MinutesPrompt.trimToTokenBudget(p, budgetTokens = 10_000, ::fakeCounter)
        assertEquals(p, result.prompt)
        assertTrue("没有裁剪时不该报裁剪", !result.trimmed)
    }

    @Test
    fun longPromptIsTrimmedToBudgetAndSaysSo() {
        val p = prompt(400)
        val budget = 200
        val result = MinutesPrompt.trimToTokenBudget(p, budgetTokens = budget, ::fakeCounter)
        val trimmed = result.prompt!!
        assertTrue("必须报告发生了裁剪", result.trimmed && result.droppedLines > 0)
        assertTrue("裁剪后必须装进预算：${fakeCounter(trimmed)} > $budget", fakeCounter(trimmed) <= budget)
        assertTrue("必须明确告诉模型后面被省略了", trimmed.contains("已省略"))
        assertTrue("指令部分必须原样保留", trimmed.startsWith("请整理成会议纪要。"))
        assertTrue("裁剪后应仍有内容", trimmed.contains("[00:01]"))
    }

    @Test
    fun zeroBudgetDisablesTrimming() {
        val p = prompt(400)
        assertEquals(p, MinutesPrompt.trimToTokenBudget(p, budgetTokens = 0, ::fakeCounter).prompt)
    }

    @Test
    fun retryInstructionSurvivesTrimming() {
        // 长转写 + 重试指令：删掉尾巴等于拿一模一样的提示词再跑一次
        val long = MinutesPrompt.retryPrompt(prompt(400), listOf("缺少章节：## 决议"))
        val trimmed = MinutesPrompt.trimToTokenBudget(long, budgetTokens = 200, ::fakeCounter).prompt!!
        assertTrue("重试指令必须保留", trimmed.contains("上一次的输出不符合要求"))
        assertTrue("省略标记不能被重复添加", Regex("已省略").findAll(trimmed).count() == 1)
    }

    @Test
    fun impossibleBudgetReportsFailureInsteadOfOverBudgetPrompt() {
        // 连头部 + 省略标记都装不下：必须返回 null，让调用方如实报错
        val result = MinutesPrompt.trimToTokenBudget(prompt(400), budgetTokens = 1, ::fakeCounter)
        assertTrue("装不下时应返回 null", result.prompt == null)
        assertTrue("应报告丢弃了多少行", result.droppedLines > 0)
    }
}
