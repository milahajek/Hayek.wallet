package io.github.milahajek.hayek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChainMathTest {
    @Test fun difficultyFromBits() {
        assertEquals(1.0, ChainMath.difficulty(0x1d00ffff), 1e-12)
        // Příklad z Bitcoin wiki.
        assertEquals(16307.420938523983, ChainMath.difficulty(0x1b0404cb), 1e-6)
    }

    @Test fun hashrate() {
        // Obtížnost 1 a blok za 600 s ≈ 7,16 MH/s.
        assertEquals(4294967296.0 / 600, ChainMath.hashrate(1.0, 600.0), 1e-6)
        assertEquals(0.0, ChainMath.hashrate(1.0, 0.0), 0.0)
    }

    @Test fun subsidyAndSupply() {
        assertEquals(5_000_000_000L, ChainMath.subsidy(0))
        assertEquals(312_500_000L, ChainMath.subsidy(840_000))
        assertEquals(156_250_000L, ChainMath.subsidy(1_050_000))
        assertEquals(5_000_000_000L, ChainMath.supply(0))
        assertEquals(10_500_000L * 100_000_000, ChainMath.supply(209_999))
        assertEquals(19_687_500L * 100_000_000, ChainMath.supply(839_999))
        // Konečná nabídka je o něco menší než 21 milionů kvůli zaokrouhlování odměn.
        assertEquals(2_099_999_997_690_000L, ChainMath.supply(10_000_000))
    }

    @Test fun halvingAndRetarget() {
        val h = ChainMath.halving(900_000)
        assertEquals(1_050_000L, h.nextHeight); assertEquals(150_000L, h.blocksLeft)
        assertEquals(312_500_000L, h.subsidyNow); assertEquals(156_250_000L, h.subsidyNext)

        val tip = 2016L * 450 + 1008
        val r = ChainMath.retarget(tip, 1_000_000 + 1008L * 540, 1_000_000)
        assertEquals(1008L, r.blocksDone); assertEquals(1008L, r.blocksLeft)
        assertEquals(600.0 / 540 - 1, r.change!!, 1e-9)
        // Na začátku období se neodhaduje.
        assertNull(ChainMath.retarget(2016L * 450 + 2, 1_000_000 + 1200, 1_000_000).change)
        // Strop 4× (−75 % / +300 %).
        assertEquals(3.0, ChainMath.retarget(tip, 1_000_000 + 1008L, 1_000_000).change!!, 1e-9)
    }

    @Test fun statsJsonRoundTrip() {
        val s = NetworkStats(1L, "Fulcrum 1.12.0", "1.4", null, 42, 925_900, listOf(10, 9, 8, 7, 6, 5, 4), 1, 2, 0x17023a04, 12, 6, 2, 1.0)
        assertEquals(s, NetworkStats.fromJson(s.toJson()))
        assertNull(NetworkStats.fromJson("nesmysl"))
        assertTrue(NetFmt.dur(9 * 60 + 41).startsWith("9 min"))
    }
}
