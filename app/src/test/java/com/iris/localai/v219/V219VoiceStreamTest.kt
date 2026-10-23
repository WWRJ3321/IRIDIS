package com.iris.localai.v219

import com.iris.localai.engine.HistoryMatch
import com.iris.localai.engine.PromptMessage
import com.iris.localai.voice.AgentVoiceStream
import com.iris.localai.voice.VoiceSpeech
import com.iris.localai.voice.VoiceStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.1.9 voice speed: 智能体 answers spoken while they stream; voice note in a fixed (system) place. */
class V219VoiceStreamTest {
    private fun src(p: String) = File("src/main/java/com/iris/localai/$p").let { if (it.exists()) it else File("app/" + it.path) }.readText()

    /** Feeds [text] one char at a time as a growing answer; returns what was released and when (char count). */
    private fun feed(s: AgentVoiceStream, text: String, log: MutableList<Pair<Int, String>>? = null, out: MutableList<String>? = null) {
        s.startTurn()
        for (n in 1..text.length) {
            val before = out?.size ?: 0
            s.update(text.substring(0, n))
            if (out != null && log != null) for (k in before until out.size) log += n to out[k]
        }
    }

    // ---------------------------------------------------------------- sentence splitter

    @Test fun sentenceEndsCoverChineseAndEnglishPunctuationAndNewlines() {
        assertEquals(listOf(3, 8, 12, 15, 21), AgentVoiceStream.sentenceEnds("你好。今天晴！！好吗？”ok. 3.5度\n"))
        // Decimals, list numbers and a dot inside a word are not sentence ends.
        assertEquals(emptyList<Int>(), AgentVoiceStream.sentenceEnds("气温 3.5 度，v2.1.9 版"))
        assertEquals(listOf(2), AgentVoiceStream.sentenceEnds("对；没错吗"))
        assertEquals(listOf(4), AgentVoiceStream.sentenceEnds("等等……然后"))
    }

    @Test fun aSentenceIsReleasedOnlyWithLookaheadAfterIt() {
        val t = "今天北京晴，二十度左右。适合"
        assertEquals(0, AgentVoiceStream.releasable(t, 0))
        assertEquals(12, AgentVoiceStream.releasable(t + "跑步", 0))
        // A tail that may still become an LFM `tool_call:` waits.
        assertEquals(0, AgentVoiceStream.releasable("我查一下。tool_", 0))
        assertEquals(0, AgentVoiceStream.releasable("我查一下。\n  ab", 0))
    }

    // ---------------------------------------------------------------- streaming answer

    @Test fun finalAnswerIsSpokenWhileItStreamsAndExactlyOnce() {
        val answer = "今天北京晴，二十度左右。适合跑步，记得带水。晚上会降温。"
        val out = ArrayList<String>()
        val log = ArrayList<Pair<Int, String>>()
        val s = AgentVoiceStream({ out += it })
        feed(s, answer, log, out)
        // The first sentence went out long before the turn ended.
        assertEquals("今天北京晴，二十度左右。", log.first().second)
        assertTrue(log.first().first < answer.length - 10)
        s.finish(answer)
        assertEquals(answer, out.joinToString(""))
    }

    @Test fun toolCallTurnsAreNeverSpoken() {
        val calls = listOf(
            "好的，我帮你查一下。\n<tool_call>\n{\"name\": \"web_search\", \"arguments\": {\"query\": \"北京天气\"}}\n</tool_call>",
            "我查一下天气。\n```json\n{\"tool\": \"web_search\", \"args\": {\"query\": \"北京天气\"}}\n```",
            "我查一下。tool_call:web_search{\"query\": \"北京天气\"}",
            "稍等。<|tool_call>call:web_search{query:<|\"|>北京<|\"|>}<tool_call|>",
            "我看看。\n{\"tool\": \"datetime\"}",
            "〔本轮已执行的工具〕\n- web_search 北京天气",
        )
        for (c in calls) {
            val out = ArrayList<String>()
            val s = AgentVoiceStream({ out += it })
            feed(s, c)
            assertTrue("$c -> $out", out.isEmpty())
        }
    }

    @Test fun multiTurnRunSpeaksOnlyTheFinalTurn() {
        val out = ArrayList<String>()
        val s = AgentVoiceStream({ out += it })
        feed(s, "我查一下。\n<tool_call>{\"name\": \"web_search\", \"arguments\": {\"query\": \"x\"}}</tool_call>")
        feed(s, "") // empty turn (ANSWER_NOW nudge follows)
        val answer = "明天北京晴。最高二十度，最低十一度。"
        feed(s, answer)
        assertTrue(out.isNotEmpty())
        s.finish(answer)
        assertEquals(answer, out.joinToString(""))
    }

    @Test fun heldFinalTurnIsSpokenWholeAtTheEnd() {
        // A code block (backticks) holds the turn; the end of the run still speaks it, once.
        val answer = "可以这样写。\n```kotlin\nval x = 1\n```\n就这样。"
        val out = ArrayList<String>()
        val s = AgentVoiceStream({ out += it })
        feed(s, answer)
        s.finish(answer)
        assertEquals(answer, out.joinToString(""))
        // Fallback answer (the last turn said nothing): spoken whole.
        val out2 = ArrayList<String>()
        val s2 = AgentVoiceStream({ out2 += it })
        feed(s2, "")
        s2.finish("我找到了这些结果。")
        assertEquals(listOf("我找到了这些结果。"), out2)
        // A final answer that differs from what was spoken is not repeated.
        val out3 = ArrayList<String>()
        val s3 = AgentVoiceStream({ out3 += it })
        feed(s3, "第一句话说完了。第二句话")
        s3.finish("完全不同的回答。")
        assertEquals(listOf("第一句话说完了。"), out3)
    }

    @Test fun streamedDeltasGoThroughTheSpeakableFilterLikeThePlainPath() {
        val speech = VoiceSpeech()
        val spoken = ArrayList<String>()
        val s = AgentVoiceStream({ d -> spoken += speech.push(d) })
        val answer = "好的！**明天**北京晴。最高二十度。希望对你有帮助！"
        feed(s, answer)
        val beforeEnd = spoken.toList()
        s.finish(answer)
        spoken += speech.flush()
        assertTrue(beforeEnd.toString(), beforeEnd.isNotEmpty())
        assertEquals("明天北京晴。最高二十度。", spoken.joinToString(""))
    }

    @Test fun viewModelStreamsTheAgentAnswerTurn() {
        val vm = src("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("com.iris.localai.voice.AgentVoiceStream(it)"))
        assertTrue(vm.contains("voiceStream?.startTurn()"))
        assertTrue(vm.contains("voiceStream?.update(ThinkParser.parse(prefix + sb"))
        assertTrue(vm.contains("voiceStream?.finish(ThinkParser.parse(o.finalText"))
        assertFalse(vm.contains("voiceOut?.invoke(ThinkParser.parse(o.finalText"))
    }

    // ---------------------------------------------------------------- KV prefix

    @Test fun voiceHistoryPrefixMatchesAcrossTurns() {
        val sys = PromptMessage(PromptMessage.SYSTEM, "你是 IRIDIS。")
        val u1 = PromptMessage(PromptMessage.USER, "明天天气怎么样")
        val a1 = PromptMessage(PromptMessage.ASSISTANT, "明天晴。")
        val u2 = PromptMessage(PromptMessage.USER, "那后天呢")
        for (base in listOf(listOf(sys), emptyList())) {
            val sent1 = VoiceStyle.withVoiceSystem(base + u1)
            val engineSeen = sent1 + a1
            val sent2 = VoiceStyle.withVoiceSystem(base + listOf(u1, a1, u2))
            assertTrue(HistoryMatch.same(engineSeen, sent2.dropLast(1)))
            // Every user turn goes out exactly as stored; the system prompt is the same each turn.
            assertEquals(u2, sent2.last())
            assertEquals(sent1.first(), sent2.first())
            assertTrue(sent2.first().content.endsWith(VoiceStyle.NOTE))
            assertEquals(1, sent2.count { it.content.contains(VoiceStyle.NOTE) })
        }
        // Applying it to an empty system prompt does not leave a blank lead.
        assertEquals(VoiceStyle.NOTE, VoiceStyle.withVoiceSystem(listOf(PromptMessage(PromptMessage.SYSTEM, ""), u1)).first().content)
    }

    @Test fun bothVoicePathsUseTheSystemPlacement() {
        val vm = src("ui/chat/ChatViewModel.kt")
        assertEquals(3, Regex("VoiceStyle\\.withVoiceSystem\\(").findAll(vm).count())
        assertFalse(src("voice/VoiceReply.kt").contains("fun withVoiceNote"))
    }
}
