package io.github.milahajek.hayek

import org.junit.Assert.*
import org.junit.Test

class CoinTagsTest {
    private fun coin(id: Int, addr: String = "bc1q$id", change: Boolean = false) =
        CoinItem("t$id", 0, 1000, addr, true, 1, false, change)

    @Test fun combine() {
        assertEquals(CoinTag.NOKYC, combineTags(listOf(CoinTag.NOKYC, CoinTag.NOKYC)))
        assertEquals(CoinTag.KYC, combineTags(listOf(CoinTag.NOKYC, CoinTag.KYC)))
        assertEquals(CoinTag.KYC, combineTags(listOf(null, CoinTag.KYC)))
        assertNull(combineTags(listOf(CoinTag.NOKYC, null)))
        assertNull(combineTags(emptyList()))
    }

    @Test fun mixing() {
        assertTrue(mixesNoKyc(listOf(CoinTag.NOKYC, CoinTag.KYC)))
        assertTrue(mixesNoKyc(listOf(CoinTag.NOKYC, null)))
        assertFalse(mixesNoKyc(listOf(CoinTag.NOKYC, CoinTag.NOKYC)))
        assertFalse(mixesNoKyc(listOf(CoinTag.KYC, null)))
    }

    @Test fun resolveOrder() {
        val m = mutableMapOf<String, String>()
        val c = coin(1, "bc1qa", change = true)
        assertNull(resolveTag(c, m::get))
        m["t:t1"] = "nokyc"; assertEquals(CoinTag.NOKYC, resolveTag(c, m::get))          // drobné dědí z transakce
        m["a:bc1qa"] = "kyc"; assertEquals(CoinTag.KYC, resolveTag(c, m::get))          // adresa má přednost
        m["c:t1:0"] = "none"; assertNull(resolveTag(c, m::get))                          // ručně „neoznačeno“
        m["c:t1:0"] = "nokyc"; assertEquals(CoinTag.NOKYC, resolveTag(c, m::get))
        // přijaté mince (ne drobné) značku transakce nedědí
        assertNull(resolveTag(coin(2), mapOf("t:t2" to "kyc")::get))
    }
}
