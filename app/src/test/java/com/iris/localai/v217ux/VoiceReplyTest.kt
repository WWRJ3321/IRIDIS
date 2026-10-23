package com.iris.localai.v217ux

import com.iris.localai.engine.PromptMessage
import com.iris.localai.voice.VoiceCues
import com.iris.localai.voice.VoiceRouting
import com.iris.localai.voice.VoiceSpeakable
import com.iris.localai.voice.VoiceSpeech
import com.iris.localai.voice.VoiceStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.1.7 #1: voice mode — tools, speakable text, less "AI味", KV-stable system prompt. */
class VoiceReplyTest {
    private fun src(p: String) = File("src/main/java/com/iris/localai/$p").let { if (it.exists()) it else File("app/" + it.path) }.readText()

    // ---------------------------------------------------------------- speakable cleaner

    @Test fun markdownBecomesPlainSentences() {
        val out = VoiceSpeakable.clean("## 今天的安排\n- **九点**开会\n- 十点写报告\n", max = 5)
        assertFalse(out, out.contains("#") || out.contains("*") || out.contains("- "))
        assertTrue(out, out.contains("今天的安排。") && out.contains("九点开会。") && out.contains("十点写报告"))
    }

    @Test fun codeBlockIsAnnouncedNotRead() {
        val out = VoiceSpeakable.clean("可以这样写。\n```kotlin\nval x = listOf(1, 2)\nprintln(x)\n```\n就这么简单。", max = 5)
        assertTrue(out, out.contains(VoiceSpeakable.CODE_SAID))
        assertFalse(out, out.contains("val x") || out.contains("println") || out.contains("```"))
        assertTrue(out, out.contains("就这么简单"))
        // Said once even with two blocks.
        val two = VoiceSpeakable.clean("看：\n```\na\n```\n还有：\n```\nb\n```\n", max = 9)
        assertEquals(1, Regex(VoiceSpeakable.CODE_SAID).findAll(two).count())
    }

    @Test fun urlsEmojiAndTablesAreNotSpoken() {
        val out = VoiceSpeakable.clean("详情见 https://example.com/a?b=1 这个页面😀。", max = 5)
        assertFalse(out, out.contains("http") || out.contains("example") || out.contains("😀"))
        assertTrue(out, out.contains("详情见"))
        val t = VoiceSpeakable.clean("| 城市 | 温度 |\n|---|---|\n| 北京 | 20 |\n", max = 5)
        assertFalse(t, t.contains("|") || t.contains("---"))
        assertTrue(t, t.contains("北京") && t.contains("20"))
        // Numbers and dates stay as they are.
        assertEquals("会议在 10月22日 15:30 开始。", VoiceSpeakable.clean("会议在 10月22日 15:30 开始。"))
    }

    @Test fun speechIsCappedAtThreeSentencesButStreamKeepsWorking() {
        val text = "第一句。第二句。第三句。第四句。第五句。"
        val out = VoiceSpeakable.clean(text)
        assertTrue(out, out.contains("第三句") && !out.contains("第四句"))
        assertTrue(out, out.endsWith(VoiceSpeakable.MORE_SAID))
        // Token by token gives the same speech.
        val v = VoiceSpeech()
        val streamed = (text.map { v.push(it.toString()) }.flatten() + v.flush()).joinToString("")
        assertEquals(out, streamed)
        // Short answers get no tail.
        assertEquals("好，明天七点叫你。", VoiceSpeakable.clean("好，明天七点叫你。"))
    }

    @Test fun cuesPassThroughUntouched() {
        assertEquals(listOf("我查一下。"), VoiceSpeech().cue("我查一下。"))
        assertEquals("我查一下。", VoiceCues.before("web_search", 3))
        assertTrue(VoiceCues.POOL.contains(VoiceCues.before("set_alarm", 1)))
    }

    // ---------------------------------------------------------------- boilerplate filter

    @Test fun boilerplateOpenersAndClosersAreRemoved() {
        assertEquals("明天北京晴，二十度左右。", VoiceStyle.polish("好的！明天北京晴，二十度左右。希望对你有帮助！"))
        assertEquals("可以先睡一会儿。", VoiceStyle.polish("当然可以！可以先睡一会儿。如果你还有其他问题，随时问我。"))
        assertEquals("我是本地模型。", VoiceStyle.polish("作为一个AI语言模型，我是本地模型。"))
        // Content is never touched; a reply that is only boilerplate stays as it was.
        assertEquals("好的。", VoiceStyle.polish("好的。"))
        assertEquals("我觉得好的方案是先走路。", VoiceStyle.polish("我觉得好的方案是先走路。"))
        // Streaming: opener dropped, closer and everything after it not spoken.
        val spoken = VoiceSpeakable.clean("当然！今天适合跑步。希望这些信息对你有帮助。祝你愉快！", max = 5)
        assertEquals("今天适合跑步。", spoken)
    }

    @Test fun voiceNoteIsShortAndHuman() {
        val n = VoiceStyle.NOTE
        assertTrue(n.length < 60)
        assertFalse(n.contains("Markdown"))
        assertTrue(n.contains("不列清单") && n.contains("不客套"))
    }

    // ---------------------------------------------------------------- routing

    @Test fun routingSendsTasksThroughTheAgent() {
        assertTrue(VoiceRouting.useAgent("今天好累啊", agentMode = true))
        assertFalse(VoiceRouting.useAgent("今天好累啊", agentMode = false))
        assertFalse(VoiceRouting.useAgent("你喜欢什么颜色", agentMode = false))
        assertTrue(VoiceRouting.useAgent("帮我查一下明天的天气", agentMode = false))
        assertTrue(VoiceRouting.useAgent("提醒我七点起床", agentMode = false))
        assertTrue(VoiceRouting.useAgent("搜一下最近的新闻", agentMode = false))
        val vm = src("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("Requester.VOICE -> com.iris.localai.voice.VoiceRouting.useAgent("))
        assertTrue(vm.contains("runAgentReply(bubble(), voiceOut ="))
        // Confirmation tools are declined in a call; the final answer is the only thing spoken.
        assertTrue(vm.contains("VoiceCues.CONFIRM_ON_SCREEN") && vm.contains("return com.iris.localai.agent.UserDecision.DENY"))
        // 2.1.9: the answer turn streams to TTS; the end of the run speaks only what is still unsaid.
        assertTrue(vm.contains("voiceStream?.finish(ThinkParser.parse(o.finalText"))
    }

    // ---------------------------------------------------------------- KV-stable prompt prefix

    /** 2.1.9: the note moved from the newest user turn to the end of the system prompt (see V219VoiceStreamTest). */
    @Test fun voiceNoteSitsInTheSystemPromptNotTheUserTurn() {
        val msgs = listOf(
            PromptMessage(PromptMessage.SYSTEM, "你是 IRIDIS。\n\n# 关于用户\n喜欢跑步"),
            PromptMessage(PromptMessage.USER, "早"),
            PromptMessage(PromptMessage.ASSISTANT, "早呀"),
            PromptMessage(PromptMessage.USER, "明天天气怎么样"),
        )
        val voiced = VoiceStyle.withVoiceSystem(msgs)
        assertEquals(msgs.size, voiced.size)
        assertTrue(voiced[0].content.startsWith(msgs[0].content) && voiced[0].content.endsWith(VoiceStyle.NOTE))
        assertEquals(msgs.subList(1, 4), voiced.subList(1, 4))
        val vm = src("ui/chat/ChatViewModel.kt")
        assertFalse(vm.contains("VOICE_HINT"))
        assertFalse(vm.contains("withVoiceNote"))
        val planFor = vm.substringAfter("private fun planFor(").substringBefore("return com.iris.localai.util.ContextPlanner.plan(")
        assertTrue(planFor.contains("val baseSystem = eff.systemPrompt.trim()"))
        assertFalse(planFor.contains("if (voice)"))
    }
}
