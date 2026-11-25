package io.github.milahajek.hayek

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pojistka proti chybě typu „Coldcard 2026“ (seed vznikl z méně náhody, než měl, a nikdo si toho nevšiml):
 * skutečná knihovna BDK musí z daných hodů kostkou vyrobit přesně tato slova a z nich přesně tyto adresy.
 * Očekávané hodnoty jsou spočítané nezávisle (čistý Python, knihovna embit a bezpečnostní audit z 25. 11. 2026);
 * první vektor je z dokumentace Coldcardu. Kdyby se cokoli v cestě hody → slova → klíče změnilo, testy selžou.
 */
class SeedVectorsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val noPins = object : PinStore {
        override fun get(hostPort: String): String? = null
        override fun set(hostPort: String, fingerprint: String) {}
    }
    private val mixed = "123456".repeat(16) + "123"   // 99 hodů
    private val mixedWords = "few educate sugar bless boring random strategy waste mutual cargo type hawk prefer denial scan abstract filter extend dignity balcony dust unusual correct bubble"

    @Test fun coldcardDocumentedVector() = assertEquals(
        "mirror reject rookie talk pudding throw happy era myth already payment own sentence push head sting video explain letter bomb casual hotel rather garment",
        Seed.wordsFromRolls("123456"))

    @Test fun diceToWords() {
        assertEquals("wheel erase puppy pistol chapter accuse carpet drop quote final attend near scrap satisfy limit style crunch person south inspire lunch meadow enact tattoo", Seed.fromDice("1".repeat(99)))
        assertEquals("leave symbol nuclear chase dentist letter tape program illegal vague suggest wife copy source soup jacket fee eager loyal zoo keep brisk dynamic cradle", Seed.fromDice("6".repeat(99)))
        assertEquals(mixedWords, Seed.fromDice(mixed))
    }

    @Test fun everyRollCounts() {
        // Žádné zkracování ani zahazování hodů: změna kteréhokoli jediného hodu (i posledního, i nad 99) dá jiná slova.
        val base = "3".repeat(120)
        val seen = mutableSetOf(Seed.fromDice(base))
        for (i in listOf(0, 1, 50, 98, 99, 119)) assertTrue("hod $i", seen.add(Seed.fromDice(base.substring(0, i) + "4" + base.substring(i + 1))))
        assertTrue(seen.add(Seed.fromDice(base + "3")))
    }

    @Test fun rejectsBadInput() {
        assertThrows(IllegalArgumentException::class.java) { Seed.fromDice("1".repeat(98)) }
        assertThrows(IllegalArgumentException::class.java) { Seed.fromDice("1".repeat(98) + "7") }
        assertThrows(IllegalArgumentException::class.java) { Seed.fromDice("1".repeat(98) + "0") }
    }

    private fun core(secret: String) = WalletCore(tmp.newFile(), secret, "", noPins)

    @Test fun wordsToAddresses() {
        val bip84 = core("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", bip84.firstAddress())
        assertEquals("73c5da0a", Passphrase.fingerprint(bip84.publicDescriptor()))

        val plain = core(mixedWords)
        assertEquals("bc1qvjc0ndgk8ap8uy495enag3qtcl4lua6nn3uyys", plain.firstAddress())
        assertEquals("5545de0e", Passphrase.fingerprint(plain.publicDescriptor()))
    }

    @Test fun passphraseChangesWallet() {
        val w = core(Passphrase.pack(mixedWords, "TREZOR"))
        assertEquals("bc1q6v9r3zv2n8s0443va0t73xr7ad42059d003csa", w.firstAddress())
        assertEquals("a3a7677f", Passphrase.fingerprint(w.publicDescriptor()))
        // velikost písmen i mezery jsou součást hesla
        assertNotEquals(w.firstAddress(), core(Passphrase.pack(mixedWords, "trezor")).firstAddress())
        assertNotEquals(w.firstAddress(), core(Passphrase.pack(mixedWords, "TREZOR ")).firstAddress())
    }

    @Test fun damagedDatabaseIsRebuilt() {
        val f = tmp.newFile()
        f.writeText("tohle není databáze")
        val w = WalletCore(f, mixedWords, "", noPins)
        assertTrue(w.dbReset)
        assertEquals("bc1qvjc0ndgk8ap8uy495enag3qtcl4lua6nn3uyys", w.firstAddress())
        // databáze jiné peněženky se nepoužije
        val other = tmp.newFile().also { it.delete() }
        core2(other, mixedWords); assertTrue(WalletCore(other, Passphrase.pack(mixedWords, "x"), "", noPins).dbReset)
        assertFalse(WalletCore(other, Passphrase.pack(mixedWords, "x"), "", noPins).dbReset)
    }
    private fun core2(f: java.io.File, secret: String) = WalletCore(f, secret, "", noPins).also { it.state() }

    @Test fun addressGap() {
        val w = core(mixedWords)
        w.state()   // vydá první adresu
        repeat(ADDRESS_GAP - 1) { w.newAddress() }
        assertThrows(IllegalStateException::class.java) { w.newAddress() }
    }
}
