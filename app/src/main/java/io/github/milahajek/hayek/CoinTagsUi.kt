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
            Icon(Icons.Default.Sell, L("Označit původ mince", "Tag coin origin"), tint = current?.color ?: MaterialTheme.colorScheme.outline)
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
        // Poměr skupin ukazují samotné dlaždice; samostatný pruh byl navíc.
        @Suppress("UNUSED_VARIABLE") val t = total
        Row(Modifier.fillMaxWidth().height(homeTileHeight()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BalanceTile(CoinTag.NOKYC, no, vm, Modifier.weight(1f).fillMaxHeight()) { vm.openHistory(CoinTag.NOKYC.key) }
            BalanceTile(CoinTag.KYC, kyc, vm, Modifier.weight(1f).fillMaxHeight()) { vm.openHistory(CoinTag.KYC.key) }
        }
        if (none > 0) Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).clickable { vm.openCoins(Screen.Home) }.padding(4.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(CoinTag.UNTAGGED_COLOR))
            Spacer(Modifier.width(6.dp))
            Text(L("Neoznačené: ${vm.amount(none)} · označit", "Untagged: ${vm.amount(none)} · tag"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BalanceTile(tag: CoinTag, sat: Long, vm: WalletViewModel, modifier: Modifier, onClick: () -> Unit) {
    RaisedTile(tag.color, onClick, modifier, verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(tag.color))
            Spacer(Modifier.width(6.dp))
            Text(tag.label + " ›", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tag.color)
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
            Text(L("Platit z mincí", "Pay from coins"), fontWeight = FontWeight.SemiBold)
            Text(L("Máte mince s různým původem. Platba použije jen zvolenou skupinu, takže se nesmíchají.", "You have coins of different origin. The payment will only use the chosen group, so they don't get mixed."),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(CoinTag.NOKYC.key, CoinTag.KYC.key, CoinTag.NONE).filter { it in groups }.forEach { k ->
                    TagPill(k, "${CoinTag.labelOf(k)} · ${formatSat(groups.getValue(k))}", selected = vm.payFrom == k) {
                        vm.payFrom = k; vm.prepared = null
                    }
                }
            }
            if (vm.payFrom == null) Text(L("Vyberte skupinu.", "Choose a group."), color = HayekOrange, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
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
            Text(L("Původ platby na tuto adresu", "Origin of payments to this address"), fontWeight = FontWeight.SemiBold)
            Text(L("Mince, které sem přijdou, se samy označí. Např. výběr z burzy = KYC, nákup na Bisq nebo osobně = Bez KYC.", "Coins arriving here get tagged automatically. E.g. exchange withdrawal = KYC, Bisq or in-person purchase = No KYC."),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (CoinTag.entries.map { it as CoinTag? } + null).forEach { t ->
                    TagPill(CoinTag.keyOf(t), if (t == null) L("Neoznačovat", "Don't tag") else t.label, selected = cur == t) { vm.setAddressTag(address, t) }
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
            Text(L("Mince: ", "Coins: "), fontSize = 14.sp)
            keys.forEach { TagPill(it); Spacer(Modifier.width(4.dp)) }
        }
    }
    if (!mixesNoKyc(tags)) return
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.errorContainer).padding(12.dp)
    ) {
        Text(L("⚠ Mícháte mince Bez KYC s ostatními", "⚠ You are mixing No-KYC coins with others"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
        Text(L("Po odeslání bude z blockchainu vidět, že patří stejnému majiteli – mince bez KYC se tím natrvalo propojí s vaší identitou. Vrátit to nejde.", "After sending, the blockchain will show they belong to the same owner – your No-KYC coins get permanently linked to your identity. This cannot be undone."),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
        Row(Modifier.clickable { onAck(!ack) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = ack, onCheckedChange = onAck)
            Text(L("Rozumím, přesto je chci použít společně", "I understand, use them together anyway"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
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
            tags.size > 1 -> TagPill(CoinTag.NONE, L("Různé značky ▾", "Mixed tags ▾"), selected = false, compact = true) { open = true }
            tag == null -> TagPill(CoinTag.NONE, L("Označit původ ▾", "Tag origin ▾"), selected = false, compact = true) { open = true }
            else -> TagPill(tag.key, "${tag.label} ▾", compact = true) { open = true }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (CoinTag.entries.map { it as CoinTag? } + null).forEach { t ->
                DropdownMenuItem(
                    text = { TagPill(CoinTag.keyOf(t), if (t == null) L("Neoznačeno", "Untagged") else t.label, selected = tags.size == 1 && t == tag) },
                    onClick = { open = false; vm.setCoinTag(own.map { it.id }, t) },
                )
            }
        }
    }
}

/** Štítek „⚠ prach“ u podezřelé drobné mince. */
@Composable
fun DustPill(frozen: Boolean = true) {
    val c = HayekOrange
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.14f)).border(1.dp, c, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(if (frozen) L("⚠ prach – zmrazeno", "⚠ dust – frozen") else L("⚠ prach", "⚠ dust"), fontSize = 12.sp, fontWeight = FontWeight.Medium) }
}

/** Nastavení ochrany proti prachu. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DustSection(vm: WalletViewModel) {
    Text(L("Ochrana proti prachu", "Dust protection"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text(L("Útočníci posílají na cizí adresy drobné částky („dust attack“). Kdo by je utratil s ostatními mincemi, prozradí, že patří jednomu majiteli. ", "Attackers send tiny amounts to other people's addresses („dust attack“). Spending them together with other coins reveals they belong to one owner. ") +
        L("Příchozí mince pod zvolenou hranicí se proto samy zmrazí. Vlastní drobné z plateb se to netýká.", "So incoming coins below the chosen threshold are frozen automatically. Your own change is not affected."),
        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Dust.CHOICES.forEach { v ->
            FilterChip(
                selected = vm.dustThreshold == v, onClick = { vm.saveDustThreshold(v) },
                label = { Text(if (v == 0L) L("Vypnuto", "Off") else L("pod ${formatSat(v)}", "below ${formatSat(v)}")) },
                leadingIcon = if (vm.dustThreshold == v) { { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) } } else null,
            )
        }
    }
}
