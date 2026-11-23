package io.github.milahajek.hayek

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Volba poplatku: Rychle / Normálně / Levně podle vašeho uzlu, nebo vlastní hodnota.
 * U každé volby je vidět sat/vB a přibližná cena běžné platby v satech.
 *
 * @param minRate nejnižší povolená sazba (u urychlení musí být vyšší než původní) – nižší volby jsou zašedlé.
 */
@Composable
fun FeePicker(
    estimates: FeeEstimates?,
    choice: FeeChoice,
    rate: String,
    onChoice: (FeeChoice) -> Unit,
    onCustomRate: (String) -> Unit,
    minRate: Long = 1,
    loading: Boolean = estimates == null,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(L("Poplatek", "Fee"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                if (estimates != null) L("podle vašeho uzlu", "from your node") else if (loading) L("zjišťuji u vašeho uzlu…", "asking your node…") else L("odhad není k dispozici", "estimate not available"),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in listOf(FeeChoice.SLOW, FeeChoice.NORMAL, FeeChoice.FAST)) {
                val r = estimates?.rate(c)
                val enabled = r != null && r >= minRate
                FeeOption(
                    title = c.title, eta = c.eta, rate = r, selected = choice == c, enabled = enabled,
                    modifier = Modifier.weight(1f), onClick = { onChoice(c) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChoice(FeeChoice.CUSTOM) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = choice == FeeChoice.CUSTOM, onClick = { onChoice(FeeChoice.CUSTOM) })
            Text(L("Vlastní poplatek", "Custom fee"), fontSize = 14.sp)
        }
        if (choice == FeeChoice.CUSTOM) {
            OutlinedTextField(
                value = rate, onValueChange = { onCustomRate(it.filter(Char::isDigit).take(3)) },
                label = { Text("sat/vB") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    val v = rate.toLongOrNull()
                    Text(
                        when {
                            v == null -> L("Zadejte celé číslo (1–500).", "Enter a whole number (1–500).")
                            v < minRate -> L("Musí být aspoň $minRate sat/vB.", "Must be at least $minRate sat/vB.")
                            else -> L("Běžná platba ≈ ${formatSat(v * TYPICAL_TX_VBYTES)}", "Typical payment ≈ ${formatSat(v * TYPICAL_TX_VBYTES)}") +
                                (estimates?.let { e -> " · " + when { v >= e.fast -> L("rychlé potvrzení", "fast confirmation"); v >= e.normal -> L("asi do hodiny", "within about an hour"); v >= e.slow -> L("do dne", "within a day"); else -> L("může trvat i dny", "may take days") } } ?: "")
                        }
                    )
                }
            )
        } else {
            Text(
                L("sat/vB = satoshi za virtuální bajt velikosti transakce. Běžná platba má asi $TYPICAL_TX_VBYTES vB; přesný poplatek uvidíte v souhrnu před odesláním.", "sat/vB = satoshi per virtual byte of transaction size. A typical payment is about $TYPICAL_TX_VBYTES vB; you'll see the exact fee in the summary before sending."),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FeeOption(title: String, eta: String, rate: Long?, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val border = if (selected) BorderStroke(2.dp, HayekOrange) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val alpha = if (enabled) 1f else 0.4f
    Surface(
        modifier = modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(14.dp), border = border,
        color = if (selected) HayekOrange.copy(alpha = 0.12f) else Color.Transparent,
    ) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 6.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            Text(eta, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            Spacer(Modifier.height(4.dp))
            Text(rate?.let { "$it sat/vB" } ?: "–", fontWeight = FontWeight.Bold, fontSize = 15.sp,
                color = (if (selected) HayekOrange else MaterialTheme.colorScheme.onSurface).copy(alpha = alpha), textAlign = TextAlign.Center)
            Text(rate?.let { "≈ ${formatSat(it * TYPICAL_TX_VBYTES)}" } ?: "", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        }
    }
}
