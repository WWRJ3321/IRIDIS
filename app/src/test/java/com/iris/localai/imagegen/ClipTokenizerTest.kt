package com.iris.localai.imagegen

import com.iris.localai.imagegen.ClipTokenizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipTokenizerTest {
    private val vocabJson = """{"h":1,"e":2,"l":3,"o</w>":4,"he":5,"ll":6,"llo</w>":7,"hello</w>":10,
        "w":11,"o":12,"r":13,"d</w>":15,"!</w>":16,"\u0120":17}"""
    private val merges = "#version: 0.2\nh e\nl l\nll o</w>\nhe llo</w>\n"
    private val tok = ClipTokenizer.fromText(vocabJson, merges)

    @Test fun byteEncoderMapsSpaceToGDot() {
        assertEquals('\u0120', ClipTokenizer.BYTE_ENCODER[' '.code])
        assertEquals('a', ClipTokenizer.BYTE_ENCODER['a'.code])
        assertEquals(256, ClipTokenizer.BYTE_ENCODER.toSet().size)
    }

    @Test fun bpeMergesInRankOrder() {
        assertEquals(listOf("hello</w>"), tok.bpe("hello"))
        assertEquals(listOf("w", "o", "r", "l", "d</w>"), tok.bpe("world"))
    }

    @Test fun encodeCleansLowercasesAndPads() {
        val ids = tok.encode("  HELLO \n\t world! ")
        assertEquals(77, ids.size)
        val head = intArrayOf(49406, 10, 11, 12, 13, 3, 15, 16, 49407)
        assertArrayEquals(head, ids.copyOfRange(0, head.size))
        for (i in head.size until 77) assertEquals(49407, ids[i])
    }

    @Test fun truncationKeepsEos() {
        val ids = tok.encode(List(200) { "!" }.joinToString(" "))
        assertEquals(77, ids.size)
        assertEquals(49406, ids[0])
        assertEquals(49407, ids[76])
        for (i in 1..75) assertEquals(16, ids[i])
    }

    @Test fun customMaxLenAndSpecialTokens() {
        val ids = tok.encode("hello", maxLen = 4)
        assertArrayEquals(intArrayOf(49406, 10, 49407, 49407), ids)
        assertEquals(listOf(49406, 10, 49407), tok.tokenize("<|startoftext|>hello<|endoftext|>"))
    }
}
