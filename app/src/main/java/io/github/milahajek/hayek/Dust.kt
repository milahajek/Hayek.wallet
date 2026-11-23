package io.github.milahajek.hayek

/**
 * Ochrana proti „dust attacku“: drobné příchozí mince (prach) se samy zmrazí,
 * aby se při placení nepropojily s ostatními mincemi.
 */
object Dust {
    const val DEFAULT_THRESHOLD = 1_000L
    val CHOICES = listOf(0L, 1_000L, 5_000L, 10_000L)   // 0 = vypnuto

    /** Nové podezřelé mince: příchozí (ne vlastní drobné), pod hranicí a ještě nezpracované. */
    fun findNew(coins: List<CoinItem>, threshold: Long, seen: Set<String>): List<CoinItem> =
        if (threshold <= 0) emptyList()
        else coins.filter { !it.change && it.valueSat < threshold && it.id !in seen }
}
