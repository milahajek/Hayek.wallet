package io.github.milahajek.hayek

/**
 * Kontrola, jestli hody vypadají jako skutečná kostka.
 *
 * Slova vznikají ze SHA-256 textu hodů, takže i „1111…“ dá platně vypadající slova –
 * jenže taková peněženka je pro útočníka snadno uhodnutelná. Proto před vytvořením slov
 * odmítneme hody, které náhodné být téměř jistě nemohou:
 *  - padla jen 1–2 různá čísla,
 *  - stejné číslo padlo 8× a víckrát za sebou (u 99 poctivých hodů zhruba 1 : 3000),
 *  - hody se opakují ve vzorci (1212…, 123456123456…, 112233…), perioda do 12,
 *  - rozložení čísel neodpovídá férové kostce (chí-kvadrát, 5 stupňů volnosti, p < 0,001).
 *
 * Poctivé hody tím neprojdou zhruba v 1 případě z 500; pak stačí hodit znovu.
 */
object DiceCheck {
    const val MAX_RUN = 8
    const val MAX_PERIOD = 12
    /** Podíl shod s hodem o periodu dřív, od kterého jde o vzorec (náhodně je to kolem 1/6). */
    const val PATTERN_RATIO = 0.6
    /** Kritická hodnota chí-kvadrátu pro 5 stupňů volnosti a p = 0,001. */
    const val CHI2_LIMIT = 20.515

    data class Stats(val counts: IntArray, val longestRun: Int, val runFace: Int, val period: Int?, val chi2: Double)

    fun stats(rolls: String): Stats {
        val counts = IntArray(6)
        rolls.forEach { c -> if (c in '1'..'6') counts[c - '1']++ }
        var longest = 0; var face = 0; var cur = 0
        for (i in rolls.indices) {
            cur = if (i > 0 && rolls[i] == rolls[i - 1]) cur + 1 else 1
            if (cur > longest) { longest = cur; face = rolls[i] - '0' }
        }
        var period: Int? = null
        for (p in 1..MAX_PERIOD) {
            val n = rolls.length - p
            if (n < 2 * p || n < 12) break
            val same = (p until rolls.length).count { rolls[it] == rolls[it - p] }
            if (same >= PATTERN_RATIO * n) { period = p; break }
        }
        val expected = rolls.length / 6.0
        val chi2 = if (rolls.isEmpty()) 0.0 else counts.sumOf { (it - expected) * (it - expected) / expected }
        return Stats(counts, longest, face, period, chi2)
    }

    /** Vrací srozumitelný popis problému, nebo null, když hody vypadají v pořádku. */
    fun problem(rolls: String): String? {
        if (rolls.length < 12) return null
        val s = stats(rolls)
        val distinct = s.counts.count { it > 0 }
        return when {
            distinct <= 2 -> L(
                "Padla jen ${if (distinct == 1) "jedna hodnota" else "dvě různé hodnoty"}. Tohle skutečná kostka neudělá.",
                "Only ${if (distinct == 1) "one value" else "two different values"} came up. A real die doesn't do that."
            )
            s.period != null -> L(
                "Hody se opakují ve vzorci (každý ${s.period}. hod je skoro vždy stejný). Házejte opravdu kostkou, nevymýšlejte si.",
                "The rolls repeat in a pattern (every ${s.period}th roll is almost always the same). Really roll a die, don't make the numbers up."
            )
            s.longestRun >= MAX_RUN -> L(
                "Číslo ${s.runFace} padlo ${s.longestRun}× za sebou. U skutečné kostky je to krajně nepravděpodobné.",
                "The number ${s.runFace} came up ${s.longestRun} times in a row. With a real die that is extremely unlikely."
            )
            rolls.length >= MIN_ROLLS && s.chi2 > CHI2_LIMIT -> L(
                "Některá čísla padají výrazně častěji než jiná (${countsText(s.counts)}). Kostka je možná nevyvážená, nebo hody nejsou náhodné.",
                "Some numbers come up much more often than others (${countsText(s.counts)}). The die may be unbalanced, or the rolls aren't random."
            )
            else -> null
        }
    }

    private fun countsText(c: IntArray) = c.withIndex().joinToString(", ") { (i, n) -> "${i + 1}: $n×" }
}
