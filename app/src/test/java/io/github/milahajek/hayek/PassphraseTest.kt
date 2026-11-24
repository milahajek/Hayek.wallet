package io.github.milahajek.hayek

import org.junit.Assert.*
import org.junit.Test

class PassphraseTest {
    private val w = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test fun packUnpack() {
        assertEquals(w, Passphrase.pack(w, null)); assertEquals(w, Passphrase.pack(w, ""))
        val s = Passphrase.pack(w, " Moje Heslo 1 ")
        assertEquals(w, Passphrase.words(s)); assertEquals(" Moje Heslo 1 ", Passphrase.of(s))   // mezery se zachovají
        assertNull(Passphrase.of(w)); assertEquals(w, Passphrase.words(w))
        assertEquals("ž🔑", Passphrase.of(Passphrase.pack(w, "ž🔑")))
    }

    @Test fun validation() {
        assertNotNull(Passphrase.problem("")); assertNotNull(Passphrase.problem("a\nb")); assertNotNull(Passphrase.problem("x".repeat(101)))
        assertNull(Passphrase.problem("dlouhé heslo 123"))
        assertNotNull(Passphrase.warning(" heslo-s-mezerou")); assertNotNull(Passphrase.warning("abc"))
        assertNull(Passphrase.warning("dostatecne dlouhe"))
        assertNotNull(Passphrase.warning("dostatečně dlouhé"))
    }

    @Test fun fingerprint() {
        assertEquals("73c5da0a", Passphrase.fingerprint("wpkh([73C5DA0A/84'/0'/0']xpub6CatW/0/*)#abc"))
        assertNull(Passphrase.fingerprint("wpkh(xpub6CatW/0/*)")); assertNull(Passphrase.fingerprint(null))
    }
}
