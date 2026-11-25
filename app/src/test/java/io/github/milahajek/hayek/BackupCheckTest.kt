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
