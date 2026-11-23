package io.github.milahajek.hayek

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Bip329Test {
    private val tx = "a".repeat(64)
    private val tx2 = "b".repeat(64)

    @Test fun roundTrip() {
        val l = Bip329.Labels(
            notes = mapOf(tx to "Bisq – nákup za CZK \"uvozovky\""),
            outputs = mapOf("$tx:0" to Bip329.Output(CoinTag.NOKYC, null), "$tx2:1" to Bip329.Output(null, false), "$tx2:2" to Bip329.Output(CoinTag.KYC, false)),
            addresses = mapOf("bc1qabc" to CoinTag.KYC),
            contacts = mapOf("bc1qbracha" to "Brácha"),
        )
        val text = Bip329.toJsonl(l)
        assertEquals(6, text.trim().lines().size)
        assertEquals(l, Bip329.parse(text))
    }

    @Test fun sparrowStyle() {
        val text = """
            {"type":"tx","ref":"$tx","label":"Nákup","origin":"wpkh([d34db33f/84'/0'/0'])"}
            {"type":"output","ref":"$tx:3","label":"nokyc bisq","spendable":true}
            {"type":"output","ref":"$tx:4","label":"from exchange #kyc"}
            {"type":"output","ref":"$tx:5","label":"jen poznámka"}
            {"type":"addr","ref":"bc1qxyz","label":"non-KYC"}
            {"type":"xpub","ref":"xpub123","label":"ignorovat"}
            nesmysl
            {"type":"tx","ref":"kratke","label":"vadné txid"}
        """.trimIndent()
        val l = Bip329.parse(text)
        assertEquals(mapOf(tx to "Nákup"), l.notes)
        assertEquals(CoinTag.NOKYC, l.outputs["$tx:3"]?.tag)
        assertEquals(true, l.outputs["$tx:3"]?.spendable)
        assertEquals(CoinTag.KYC, l.outputs["$tx:4"]?.tag)
        assertNull(l.outputs["$tx:5"])
        assertEquals(mapOf("bc1qxyz" to CoinTag.NOKYC), l.addresses)
    }

    @Test fun tagRecognition() {
        listOf("Bez KYC", "nokyc", "no-kyc", "NON KYC", "no_kyc").forEach { assertEquals(it, CoinTag.NOKYC, Bip329.tagFromLabel(it)) }
        listOf("KYC", "#kyc", "Coinbase KYC").forEach { assertEquals(it, CoinTag.KYC, Bip329.tagFromLabel(it)) }
        listOf("kycnuto", "Bisq", "", null).forEach { assertNull(it, Bip329.tagFromLabel(it)) }
    }

    @Test fun encryption() {
        val text = Bip329.toJsonl(Bip329.Labels(notes = mapOf(tx to "tajné")))
        val enc = Bip329.encrypt(text, "heslo1234")
        assertTrue(Bip329.isEncrypted(enc))
        assertFalse(String(enc).contains("tajné"))
        assertEquals(text, Bip329.decrypt(enc, "heslo1234"))
        assertThrows(IllegalArgumentException::class.java) { Bip329.decrypt(enc, "spatne") }
        assertFalse(Bip329.isEncrypted(text.toByteArray()))
    }
}
