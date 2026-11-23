package io.github.milahajek.hayek

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Barevný štítek skupiny (Bez KYC / KYC / Neoznačené). */
@Composable
fun TagPill(key: String, text: String = CoinTag.labelOf(key), selected: Boolean = true, compact: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = CoinTag.colorOf(key)
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape)
            .background(if (selected) c.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (selected) c else MaterialTheme.colorScheme.outlineVariant, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = if (onClick != null && !compact) 8.dp else if (compact) 4.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected && onClick != null && !compact) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = c)
        else Box(Modifier.size(8.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = if (onClick != null && !compact) 14.sp else 12.sp, fontWeight = FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Tlačítko se štítkem a nabídkou pro označení mince. */
@Composable
fun TagMenu(current: CoinTag?, enabled: Boolean = true, onPick: (CoinTag?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Default.Sell, "Označit původ mince", tint = current?.color ?: MaterialTheme.colorScheme.outline)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (CoinTag.entries.map { it as CoinTag? } + null).forEach { t ->
                DropdownMenuItem(
                    text = { TagPill(CoinTag.keyOf(t), selected = t == current) },
                    onClick = { open = false; onPick(t) },
                )
            }
        }
    }
}

/** Rozdělení zůstatku na Bez KYC / KYC (+ neoznačené) – jen pokud je něco označené. */
@Composable
fun TagBalances(vm: WalletViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.tagsVersion
    val groups = vm.coins.groupBy { vm.coinTagKey(it) }.mapValues { (_, l) -> l.sumOf { it.valueSat } }
    if (groups.keys.all { it == CoinTag.NONE }) return
    val no = groups[CoinTag.NOKYC.key] ?: 0L
    val kyc = groups[CoinTag.KYC.key] ?: 0L
    val none = groups[CoinTag.NONE] ?: 0L
    val total = (no + kyc + none).coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        // poměr skupin
        if (!vm.hideBalance) Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
            listOf(no to CoinTag.NOKYC.color, kyc to CoinTag.KYC.color, none to CoinTag.UNTAGGED_COLOR).filter { it.first > 0 }.forEach { (v, c) ->
                Box(Modifier.weight(v.toFloat() / total).fillMaxHeight().background(c))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BalanceTile(CoinTag.NOKYC, no, vm, Modifier.weight(1f))
            BalanceTile(CoinTag.KYC, kyc, vm, Modifier.weight(1f))
        }
        if (none > 0) Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).clickable { vm.openCoins(Screen.Home) }.padding(4.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(CoinTag.UNTAGGED_COLOR))
            Spacer(Modifier.width(6.dp))
            Text("Neoznačené: ${vm.amount(none)} · označit", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BalanceTile(tag: CoinTag, sat: Long, vm: WalletViewModel, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(tag.color.copy(alpha = 0.10f))
            .border(1.dp, tag.color.copy(alpha = 0.45f), RoundedCornerShape(16.dp)).padding(vertical = 10.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(tag.color))
            Spacer(Modifier.width(6.dp))
            Text(tag.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tag.color)
        }
        Text(vm.amount(sat), fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Volba skupiny, ze které se platí – nutná, když jsou v peněžence různé značky (aby se nesmíchaly). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PayFromPicker(vm: WalletViewModel) {
    @Suppress("UNUSED_VARIABLE") val v = vm.tagsVersion
    val groups = vm.spendableGroups()
    if (vm.selected.isNotEmpty() || groups.size < 2) return
    GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("Platit z mincí", fontWeight = FontWeight.SemiBold)
            Text("Máte mince s různým původem. Platba použije jen zvolenou skupinu, takže se nesmíchají.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(CoinTag.NOKYC.key, CoinTag.KYC.key, CoinTag.NONE).filter { it in groups }.forEach { k ->
                    TagPill(k, "${CoinTag.labelOf(k)} · ${formatSat(groups.getValue(k))}", selected = vm.payFrom == k) {
                        vm.payFrom = k; vm.prepared = null
                    }
                }
            }
            if (vm.payFrom == null) Text("Vyberte skupinu.", color = HayekOrange, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Označení přijímací adresy – mince, které na ni přijdou, dostanou značku samy. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReceiveTagPicker(vm: WalletViewModel, address: String) {
    if (address.isEmpty()) return
    val cur = vm.addressTag(address)
    GlassCard(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("Původ platby na tuto adresu", fontWeight = FontWeight.SemiBold)
            Text("Mince, které sem přijdou, se samy označí. Např. výběr z burzy = KYC, nákup na Bisq nebo osobně = Bez KYC.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (CoinTag.entries.map { it as CoinTag? } + null).forEach { t ->
                    TagPill(CoinTag.keyOf(t), if (t == null) "Neoznačovat" else t.label, selected = cur == t) { vm.setAddressTag(address, t) }
                }
            }
        }
    }
}

/** Varování před smícháním mincí bez KYC s ostatními. Vrací true, pokud je potřeba potvrzení. */
@Composable
fun MixWarning(tags: List<CoinTag?>, ack: Boolean, onAck: (Boolean) -> Unit) {
    val keys = tags.map { CoinTag.keyOf(it) }.distinct()
    if (keys.size > 1 || keys.firstOrNull() != CoinTag.NONE) {
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Mince: ", fontSize = 14.sp)
            keys.forEach { TagPill(it); Spacer(Modifier.width(4.dp)) }
        }
    }
    if (!mixesNoKyc(tags)) return
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.errorContainer).padding(12.dp)
    ) {
        Text("⚠ Mícháte mince Bez KYC s ostatními", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
        Text("Po odeslání bude z blockchainu vidět, že patří stejnému majiteli – mince bez KYC se tím natrvalo propojí s vaší identitou. Vrátit to nejde.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
        Row(Modifier.clickable { onAck(!ack) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = ack, onCheckedChange = onAck)
            Text("Rozumím, přesto je chci použít společně", fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

/**
 * Štítek u transakce na hlavní obrazovce: ukazuje a mění značku nevyužitých mincí z této transakce.
 * U nové neoznačené platby nabídne „Označit původ“.
 */
@Composable
fun TxTagChip(vm: WalletViewModel, txid: String, incoming: Boolean) {
    @Suppress("UNUSED_VARIABLE") val v = vm.tagsVersion
    val own = vm.coins.filter { it.txid == txid }
    if (own.isEmpty()) return
    val tags = own.map { vm.coinTag(it) }.distinct()
    val tag = tags.singleOrNull()
    if (tag == null && !incoming) return   // drobné bez značky nemá smysl zvýrazňovat
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(top = 4.dp)) {
        when {
            tags.size > 1 -> TagPill(CoinTag.NONE, "Různé značky ▾", selected = false, compact = true) { open = true }
            tag == null -> TagPill(CoinTag.NONE, "Označit původ ▾", selected = false, compact = true) { open = true }
            else -> TagPill(tag.key, "${tag.label} ▾", compact = true) { open = true }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (CoinTag.entries.map { it as CoinTag? } + null).forEach { t ->
                DropdownMenuItem(
                    text = { TagPill(CoinTag.keyOf(t), if (t == null) "Neoznačeno" else t.label, selected = tags.size == 1 && t == tag) },
                    onClick = { open = false; vm.setCoinTag(own.map { it.id }, t) },
                )
            }
        }
    }
}
