package io.github.milahajek.hayek

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Kolik stojí utratit minci: každá mince je ve transakci jeden vstup.
 * Peněženka používá adresy P2WPKH (bc1q…), jejichž vstup má 67,75 vB → počítáme 68 vB.
 * Cena = 68 × sazba (sat/vB). Mince, u které cena dosáhne hodnoty, se utratit nevyplatí.
 */
object SpendCost {
    const val INPUT_VB = 68L

    enum class Level(private val cs: String, private val en: String, val color: Color) {
        CHEAP("Levná", "Cheap", Color(0xFF2E9E5B)),
        FAIR("Dražší", "Pricier", Color(0xFFE0A100)),
        COSTLY("Drahá", "Costly", Color(0xFFE0612B)),
        DUST("Nevyplatí se", "Not worth it", Color(0xFFB03A3A));
        val label get() = L(cs, en)
    }

    fun cost(rate: Long) = INPUT_VB * rate
    /** Podíl poplatku na hodnotě mince (1.0 = poplatek sní celou minci). */
    fun share(valueSat: Long, rate: Long) = if (valueSat <= 0) Double.POSITIVE_INFINITY else cost(rate).toDouble() / valueSat
    /** Sazba, od které poplatek za vstup sní celou minci. */
    fun breakEven(valueSat: Long) = valueSat / INPUT_VB
    fun level(valueSat: Long, rate: Long): Level = share(valueSat, rate).let {
        when { it < 0.01 -> Level.CHEAP; it < 0.10 -> Level.FAIR; it < 1.0 -> Level.COSTLY; else -> Level.DUST }
    }

    fun pct(share: Double): String = when {
        share.isInfinite() || share >= 10 -> ">999 %"
        share < 0.001 -> L("<0,1 %", "<0.1%")
        share < 0.1 -> String.format(Locale.US, "%.1f", share * 100).let { L("${it.replace('.', ',')} %", "$it%") }
        else -> "${(share * 100).roundToLong()}" + L(" %", "%")
    }
}

/** Sazba, se kterou počítá obrazovka Mince: vlastní volba, jinak běžný odhad, jinak 5 sat/vB. */
fun WalletViewModel.spendRate(): Long = spendRateChoice ?: feeEstimates?.normal ?: 5L

private fun fmtNum(n: Long) = String.format(if (Lang.english) Locale.US else Locale.forLanguageTag("cs"), "%,d", n)

private fun coinsWord(n: Int) = when {
    Lang.english -> if (n == 1) "coin" else "coins"
    n == 1 -> "mince"; n in 2..4 -> "mince"; else -> "mincí"
}

/** Karta nad seznamem mincí: volba sazby, souhrn a rozložení mincí podle ceny utracení. */
@Composable
fun SpendCostCard(vm: WalletViewModel) {
    val coins = vm.coins.filter { !it.frozen }
    if (coins.isEmpty()) return
    val rate = vm.spendRate()
    val est = vm.feeEstimates
    val levels = coins.groupingBy { SpendCost.level(it.valueSat, rate) }.eachCount()
    val total = coins.sumOf { it.valueSat }
    val allCost = SpendCost.cost(rate) * coins.size
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L("Cena utracení", "Spending cost"), fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text("$rate sat/vB", fontWeight = FontWeight.Bold, color = HayekOrange)
            }
            Text(L("Každá mince stojí při platbě ${SpendCost.INPUT_VB} vB poplatku navíc.", "Each coin adds ${SpendCost.INPUT_VB} vB of fee to a payment."),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))

            // Rychlá volba podle aktuálních odhadů + posuvník „co kdyby“.
            if (est != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(L("Pomalu", "Slow") to est.slow, L("Běžně", "Normal") to est.normal, L("Rychle", "Fast") to est.fast).forEach { (name, r) ->
                    FilterChip(selected = rate == r, onClick = { vm.spendRateChoice = r },
                        label = { Text("$name · $r", fontSize = 12.sp) }, modifier = Modifier.weight(1f))
                }
            }
            Slider(
                value = rateToSlider(rate), onValueChange = { vm.spendRateChoice = sliderToRate(it) },
                colors = SliderDefaults.colors(thumbColor = HayekOrange, activeTrackColor = HayekOrange),
            )
            Text(L("Posuňte a zjistěte, co by mince stály při vysokých poplatcích (1–500 sat/vB).", "Slide to see what your coins would cost at high fees (1–500 sat/vB)."),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))

            // Rozložení mincí: barevný pruh + legenda.
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                SpendCost.Level.entries.forEach { l ->
                    val n = levels[l] ?: 0
                    if (n > 0) Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(l.color))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SpendCost.Level.entries.forEach { l ->
                    val n = levels[l] ?: 0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (n > 0) l.color else l.color.copy(alpha = 0.3f)))
                        Spacer(Modifier.width(4.dp))
                        Text("${l.label} $n", fontSize = 11.sp, color = if (n > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                L("Utratit všechny ${coins.size} ${coinsWord(coins.size)} najednou: ${formatSat(allCost)} (${SpendCost.pct(allCost.toDouble() / total)} hodnoty)",
                    "Spending all ${coins.size} ${coinsWord(coins.size)} at once: ${formatSat(allCost)} (${SpendCost.pct(allCost.toDouble() / total)} of value)"),
                fontSize = 13.sp, fontWeight = FontWeight.Medium
            )
            val dust = levels[SpendCost.Level.DUST] ?: 0
            if (dust > 0) Text(
                L("$dust ${coinsWord(dust)} by při této sazbě ${when (dust) { 1 -> "stála"; in 2..4 -> "stály"; else -> "stálo" }} víc, než je ${if (dust == 1) "její" else "jejich"} hodnota.",
                    "$dust ${coinsWord(dust)} would cost more than ${if (dust == 1) "its" else "their"} value at this rate."),
                fontSize = 12.sp, color = SpendCost.Level.DUST.color
            )
            // Tip na slučování: jen když jsou poplatky opravdu nízko a je co slučovat.
            val small = coins.count { SpendCost.share(it.valueSat, est?.normal ?: rate) >= 0.01 }
            if (est != null && est.normal <= 3 && small >= 2) {
                Spacer(Modifier.height(8.dp))
                Text(
                    L("💡 Poplatky jsou teď nízko – dobrá chvíle sloučit $small ${coinsWord(small)}, které jsou za běžných poplatků drahé. Vyberte je (vždy jen ze stejné skupiny KYC) a pošlete na vlastní adresu.",
                        "💡 Fees are low right now – a good moment to consolidate $small ${coinsWord(small)} that are costly at normal fees. Select them (only from the same KYC group) and send them to your own address."),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Řádek u mince: tenký pruh podílu poplatku a jedna věta. */
@Composable
fun CoinSpendCost(valueSat: Long, rate: Long) {
    val share = SpendCost.share(valueSat, rate)
    val level = SpendCost.level(valueSat, rate)
    val color by animateColorAsState(level.color, label = "spendColor")
    // Odmocnina, aby byl rozdíl mezi 0,1 % a 5 % na pruhu vidět.
    val fill by animateFloatAsState(kotlin.math.sqrt(share.coerceIn(0.0, 1.0)).toFloat(), label = "spendFill")
    Column(Modifier.padding(top = 4.dp)) {
        Box(Modifier.width(140.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color.copy(alpha = 0.18f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fill.coerceAtLeast(0.03f)).background(color))
        }
        Text(
            if (level == SpendCost.Level.DUST) L("Utracení stojí ${formatSat(SpendCost.cost(rate))} – víc než mince", "Spending costs ${formatSat(SpendCost.cost(rate))} – more than the coin")
            else L("Poplatek ${formatSat(SpendCost.cost(rate))} · ${SpendCost.pct(share)}", "Fee ${formatSat(SpendCost.cost(rate))} · ${SpendCost.pct(share)}") +
                // Hranice má smysl jen u dražších mincí; u velkých by to bylo nesmyslně vysoké číslo.
                if (level != SpendCost.Level.CHEAP) L(" · nevyplatí se nad ${fmtNum(SpendCost.breakEven(valueSat))} sat/vB", " · not worth it above ${fmtNum(SpendCost.breakEven(valueSat))} sat/vB") else "",
            fontSize = 11.sp, color = color
        )
    }
}

// Posuvník je logaritmický: jemně v nízkých sazbách, které jsou běžné, a pořád dosáhne na 500 sat/vB.
private const val MAX_RATE = 500.0
internal fun rateToSlider(rate: Long) = (kotlin.math.ln(rate.coerceIn(1, 500).toDouble()) / kotlin.math.ln(MAX_RATE)).toFloat()
internal fun sliderToRate(v: Float) = Math.exp(v.coerceIn(0f, 1f) * kotlin.math.ln(MAX_RATE)).roundToLong().coerceIn(1, 500)
