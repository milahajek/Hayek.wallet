package io.github.milahajek.hayek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpendCostTest {
    @Test fun costAndLevels() {
        assertEquals(680, SpendCost.cost(10))
        assertEquals(SpendCost.Level.CHEAP, SpendCost.level(100_000, 10))   // 0,68 %
        assertEquals(SpendCost.Level.FAIR, SpendCost.level(10_000, 10))     // 6,8 %
        assertEquals(SpendCost.Level.COSTLY, SpendCost.level(1_000, 10))    // 68 %
        assertEquals(SpendCost.Level.DUST, SpendCost.level(680, 10))        // 100 %
        assertEquals(SpendCost.Level.DUST, SpendCost.level(546, 10))
        assertEquals(1_470, SpendCost.breakEven(100_000))
    }

    @Test fun percentText() {
        // Desetinná čárka/tečka podle jazyka – kontrolujeme číslice a hranice.
        assertTrue(SpendCost.pct(0.0005).startsWith("<0"))
        assertTrue(SpendCost.pct(0.068).let { it.startsWith("6") && it.contains("8") })
        assertTrue(SpendCost.pct(0.68).startsWith("68"))
        assertEquals(">999 %", SpendCost.pct(Double.POSITIVE_INFINITY))
    }

    @Test fun sliderRoundTrip() {
        for (r in listOf(1L, 2L, 5L, 20L, 100L, 500L)) assertEquals(r, sliderToRate(rateToSlider(r)))
        assertEquals(1L, sliderToRate(0f)); assertEquals(500L, sliderToRate(1f))
        assertTrue(rateToSlider(10) < rateToSlider(11))
    }
}
