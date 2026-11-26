package io.github.milahajek.hayek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import kotlin.random.Random

class DiceCheckTest {
    private fun random(n: Int, rnd: Random) = (1..n).map { '0' + rnd.nextInt(1, 7) }.joinToString("")

    @Test fun rejectsObviousFakes() {
        for (bad in listOf(
            "1".repeat(99), "6".repeat(120), "12".repeat(50), "123456".repeat(17), "654321".repeat(17),
            "112233445566".repeat(9), "135246".repeat(17), "1234565432".repeat(10),
            "16".repeat(30) + random(39, Random(1)),          // vzorec na začátku
        )) assertNotNull(bad.take(30), DiceCheck.problem(bad))
    }

    @Test fun rejectsLongRunAndSkew() {
        val base = random(99, Random(7))
        assertNotNull(DiceCheck.problem(base.substring(0, 40) + "55555555" + base.substring(48)))
        // Samé jedničky a dvojky střídané nepravidelně: jen dvě hodnoty.
        assertNotNull(DiceCheck.problem(random(99, Random(3)).map { if (it <= '3') '1' else '2' }.joinToString("")))
        // Čtyřikrát víc šestek, než by mělo být.
        val skewed = random(99, Random(5)).mapIndexed { i, c -> if (i % 2 == 0) '6' else c }.joinToString("")
        assertTrue(DiceCheck.stats(skewed).chi2 > DiceCheck.CHI2_LIMIT)
        assertNotNull(DiceCheck.problem(skewed))
    }

    @Test fun acceptsAlmostAllHonestRolls() {
        val rnd = SecureRandom().let { s -> Random(s.nextLong()) }
        val rejected = (1..5000).count { DiceCheck.problem(random(99, rnd)) != null }
        // Očekávání kolem 0,2 %; tolerance na náhodu.
        assertTrue("odmítnuto $rejected z 5000", rejected < 40)
    }

    @Test fun shortInputIsNotJudged() {
        assertNull(DiceCheck.problem("11111111111"))
        assertEquals(8, DiceCheck.stats("12" + "3".repeat(8) + "45").longestRun)
    }
}
