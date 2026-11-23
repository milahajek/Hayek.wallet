package io.github.milahajek.hayek

import org.junit.Assert.assertEquals
import org.junit.Test

class DustTest {
    private fun c(id: Int, sat: Long, change: Boolean = false) = CoinItem("t$id", 0, sat, "bc1q$id", true, 1, false, change)

    @Test fun findsOnlyNewIncomingDust() {
        val coins = listOf(c(1, 546), c(2, 999), c(3, 1_000), c(4, 300, change = true), c(5, 700))
        assertEquals(listOf("t1:0", "t2:0", "t5:0"), Dust.findNew(coins, 1_000, emptySet()).map { it.id })
        // už zpracované (např. ručně rozmrazené) znovu ne
        assertEquals(listOf("t2:0"), Dust.findNew(coins, 1_000, setOf("t1:0", "t5:0")).map { it.id })
        // vypnuto
        assertEquals(emptyList<CoinItem>(), Dust.findNew(coins, 0, emptySet()))
    }
}
