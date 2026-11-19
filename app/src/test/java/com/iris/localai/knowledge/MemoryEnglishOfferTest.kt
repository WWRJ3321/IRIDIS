package com.iris.localai.knowledge

import com.iris.localai.memory.ItemsEdit
import com.iris.localai.memory.MemoryCommand
import com.iris.localai.memory.MemoryItem
import com.iris.localai.memory.MemorySuggest
import com.iris.localai.memory.MemoryTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提议记住：英文句子与本地化提示（2.7.0-beta3）。 */
class MemoryEnglishOfferTest {
    private fun kind(s: String) = MemorySuggest.detect(s).singleOrNull()?.kind

    @Test fun englishFactsAndPreferences() {
        assertEquals(MemoryItem.Kind.ABOUT, kind("My birthday is March 6."))
        assertEquals(MemoryItem.Kind.PREF, kind("I prefer short answers."))
        assertEquals(MemoryItem.Kind.PREF, kind("From now on answer in English"))
        assertEquals(MemoryItem.Kind.ABOUT, kind("I live in Hong Kong"))
        assertEquals(MemoryItem.Kind.PREF, kind("I don't like spicy food"))
        assertEquals("I prefer short answers", MemorySuggest.detect("I prefer short answers.").single().text)
    }

    @Test fun englishNonFactsAreSkipped() {
        listOf(
            "What is my name?", "Can you help me write an email", "If I like cats, what should I get",
            "I like cats, help me draw one", "I have a question about Kotlin", "My friend said I like tea",
            "Never mind that", "How do I like this post",
        ).forEach { assertTrue(it, MemorySuggest.detect(it).isEmpty()) }
    }

    @Test fun secondEnglishSentenceIsFound() {
        assertEquals(MemoryItem.Kind.PREF, kind("Thanks. I prefer dark mode."))
    }

    @Test fun chineseUnchanged() {
        assertEquals(MemoryItem.Kind.PREF, kind("我喜欢简洁的回答"))
        assertTrue(MemorySuggest.detect("我喜欢猫，帮我画一只").isEmpty())
    }

    @Test fun messagesComeFromStrings() {
        // JVM tests read res/values (简中)
        assertTrue(MemoryCommand.userMessage(ItemsEdit.Ok(emptyList(), "added"), "x", MemoryTarget.USER).contains("关于你"))
        assertTrue(MemoryCommand.DISABLED.isNotBlank())
        assertEquals("关于你", MemoryCommand.targetLabel(MemoryTarget.USER))
    }
}
