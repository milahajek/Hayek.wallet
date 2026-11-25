package io.github.milahajek.hayek

import org.junit.Assert.*
import org.junit.Test

class BackupCheckTest {
    @Test fun prefixRules() {
        assertNull(wordMatches("", "abandon")); assertNull(wordMatches("aba", "abandon"))
        assertEquals(true, wordMatches("aban", "abandon")); assertEquals(true, wordMatches("abandon", "abandon")); assertEquals(true, wordMatches(" Abandon ", "abandon"))
        assertEquals(false, wordMatches("abo", "abandon")); assertEquals(false, wordMatches("abandonn", "abandon")); assertEquals(false, wordMatches("abanx", "abandon"))
        // krátká slova celá; delší slovo se stejným začátkem se nepotvrdí předčasně
        assertEquals(true, wordMatches("art", "art")); assertNull(wordMatches("art", "artist")); assertEquals(false, wordMatches("arti", "art"))
    }
}

class Bip39WordsTest {
    private val file = java.io.File("src/main/assets/bip39_english.txt")
    private val words = file.readLines().filter { it.isNotBlank() }

    @Test fun officialWordlist() {
        assertEquals(2048, words.size); assertEquals(words.sorted(), words)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(Bip39Words.SHA256, sha)
        // první 4 písmena jsou jednoznačná – na tom stojí wordMatches()
        assertEquals(2048, words.map { it.take(4) }.toSet().size)
    }

    @Test fun suggestions() {
        assertTrue(Bip39Words.suggest(words, "a").isEmpty())
        assertEquals(listOf("abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract"), Bip39Words.suggest(words, "ab"))
        assertEquals(listOf("abandon"), Bip39Words.suggest(words, " ABAN "))
        assertTrue(Bip39Words.suggest(words, "zzz").isEmpty())
    }
}
