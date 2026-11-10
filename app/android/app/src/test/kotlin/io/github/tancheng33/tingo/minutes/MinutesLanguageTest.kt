package io.github.tancheng33.tingo.minutes

import io.github.tancheng33.tingo.transcription.Transcript
import io.github.tancheng33.tingo.transcription.TranscriptSentence
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纪要语言：设置怎么解析、英文提示词与英文校验是不是真的成对出现。
 *
 * 这条链最怕的是"提示词换了、校验没换"：模型老老实实输出英文章节，
 * 校验却按中文标题去比对，于是每次都被判不合格，白烧两次 token。
 */
class MinutesLanguageTest {

    private fun transcript(vararg texts: String) = Transcript(
        source = "录音.wav",
        durationMs = texts.size * 1_000L,
        sentences = texts.mapIndexed { i, t -> TranscriptSentence(0, i * 1_000L, i * 1_000L + 900, t) },
    )

    @Test
    fun autoFollowsTheSpokenLanguage() {
        val zh = transcript("下周五交付 API 网关。", "好，Kafka 那边我来跟。")
        val en = transcript(
            "Let's ship the API gateway next Friday.",
            "I'll take the Kafka migration.",
        )

        assertEquals(MinutesLanguage.ZH, MinutesLanguage.resolve(MinutesLanguage.AUTO, zh.sentences.map { it.text }))
        assertEquals(MinutesLanguage.EN, MinutesLanguage.resolve(MinutesLanguage.AUTO, en.sentences.map { it.text }))
    }

    @Test
    fun explicitSettingWins() {
        val zh = transcript("下周五交付 API 网关。")

        assertEquals(MinutesLanguage.EN, MinutesLanguage.resolve(MinutesLanguage.EN, zh.sentences.map { it.text }))
        assertEquals(MinutesLanguage.ZH, MinutesLanguage.resolve(MinutesLanguage.ZH, zh.sentences.map { it.text }))
    }

    @Test
    fun speakerNamesDoNotFoolTheDetection() {
        // 英文会议的说话人叫"说话人1"（转写里的默认标签），不能因此判成中文
        val en = transcript(
            "Quick sync on the release.",
            "The build is green.",
            "Let's cut the release tomorrow.",
        )

        assertEquals(MinutesLanguage.EN, MinutesLanguage.detect(en.sentences.map { it.text }))
    }

    @Test
    fun emptyTranscriptFallsBackToChinese() {
        assertEquals(MinutesLanguage.ZH, MinutesLanguage.detect(emptyList()))
    }

    @Test
    fun englishPromptAsksForEnglishSections() {
        val prompt = MinutesPrompt.build(transcript("Ship it."), language = MinutesLanguage.EN)

        assertTrue("英文纪要必须要求英文章节", prompt.contains("## Summary"))
        assertTrue(prompt.contains("## Action Items"))
        assertFalse("不该还要求中文章节", prompt.contains("## 一句话总结"))
    }

    @Test
    fun chinesePromptIsUnchanged() {
        val prompt = MinutesPrompt.build(transcript("今天复盘注册转化。"))

        assertTrue(prompt.contains("## 一句话总结"))
        assertFalse(prompt.contains("## Summary"))
    }

    @Test
    fun englishOutputValidatesAgainstEnglishSections() {
        val minutes = """
            # Release sync

            ## Summary
            We ship on Friday.

            ## Discussion
            ### 1. Release
            - Build is green ([00:00])

            ## Decisions
            - Ship on Friday ([00:00])

            ## Action Items
            | Item | Owner | Due | Source |
            |---|---|---|---|
            | Cut the release | Lin | Friday | [00:00] |

            ## Open Questions & Risks
            - None
        """.trimIndent()

        assertTrue(
            "英文章节不该被判缺章节：${MinutesPrompt.validate(minutes, MinutesLanguage.EN)}",
            MinutesPrompt.validate(minutes, MinutesLanguage.EN).isEmpty(),
        )
    }

    @Test
    fun englishRetryPromptIsInEnglish() {
        val retry = MinutesPrompt.retryPrompt("BASE", listOf("missing section: ## Summary"), MinutesLanguage.EN)

        assertTrue(retry.contains("did not meet the requirements"))
        assertFalse("中文的补救指令会被英文模型当成内容", retry.contains("上一次的输出"))
    }

    @Test
    fun theServicePassesTheResolvedLanguageToBothPromptAndGlossary() = runTest {
        val dir = Files.createTempDirectory("minutes-lang").toFile()
        val seenPrompts = mutableListOf<String>()
        val seenGlossaryLanguages = mutableListOf<String>()
        val service = MinutesService(
            generator = object : MinutesGenerator {
                override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): String {
                    seenPrompts += prompt
                    return """
                        # Release sync

                        ## Summary
                        Ship Friday.

                        ## Discussion
                        ### 1. Release
                        - Green ([00:00])

                        ## Decisions
                        - Ship ([00:00])

                        ## Action Items
                        | Item | Owner | Due | Source |
                        |---|---|---|---|
                        | Cut | Lin | Fri | [00:00] |

                        ## Open Questions & Risks
                        - None
                    """.trimIndent()
                }
            },
            glossary = { lang ->
                seenGlossaryLanguages += lang
                ""
            },
            languageSetting = { MinutesLanguage.AUTO },
        )

        // 英文会议 + auto → 英文提示词、英文术语表
        service.generate(transcript("Let's ship the build on Friday.", "The tests are green."), dir)

        assertEquals(listOf(MinutesLanguage.EN), seenGlossaryLanguages)
        assertTrue(seenPrompts.first().contains("## Summary"))
        // 术语表段拼进了英文模板（这里为空，所以模板里的占位行消失）
        assertFalse(seenPrompts.first().contains("{glossary}"))
    }

    @Test
    fun aFileWrittenByTheServiceIsTheReturnedMinutesFile() = runTest {
        val dir = Files.createTempDirectory("minutes-lang-file").toFile()
        val service = MinutesService(
            generator = object : MinutesGenerator {
                override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): String = """
                    # Release sync

                    ## Summary
                    Ship Friday.

                    ## Discussion
                    ### 1. Release
                    - Green ([00:00])

                    ## Decisions
                    - Ship ([00:00])

                    ## Action Items
                    | Item | Owner | Due | Source |
                    |---|---|---|---|
                    | Cut | Lin | Fri | [00:00] |

                    ## Open Questions & Risks
                    - None
                """.trimIndent()
            },
            languageSetting = { MinutesLanguage.EN },
        )

        val file = service.generate(transcript("Ship it."), dir)

        assertEquals(io.github.tancheng33.tingo.minutes.MinutesService.FILE_NAME, File(file.parentFile, file.name).name)
        assertTrue(file.readText().contains("## Summary"))
    }
}
