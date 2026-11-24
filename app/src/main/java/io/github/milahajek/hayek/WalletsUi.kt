package io.github.milahajek.hayek

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Barva a ikona druhu peněženky: kapesní (klíče v telefonu) = oranžová, trezor (jen sledování) = modrá se zámkem. */
val VaultBlue = Color(0xFF3B7DDD)
fun walletColor(w: WalletInfo?): Color = if (w?.isWatch == true) VaultBlue else HayekOrange
fun walletIcon(w: WalletInfo?): ImageVector = if (w?.isWatch == true) Icons.Default.Lock else Icons.Default.PhoneAndroid
fun walletKindText(w: WalletInfo): String =
    if (w.isWatch) L("jen sledování · podpis Coldcardem", "watch-only · signed by Coldcard") else L("klíče v telefonu", "keys on this phone")

/** Přepínač peněženek nahoře na hlavní obrazovce (jen když jich je víc). */
@Composable
fun WalletSwitcher(vm: WalletViewModel) {
    val list = vm.wallets
    if (list.size < 2) return
    val many = list.size > 3
    Row(
        Modifier.fillMaxWidth().then(if (many) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        list.forEach { w ->
            val active = w.id == vm.activeId
            val c = walletColor(w)
            val shape = RoundedCornerShape(18.dp)
            Column(
                (if (many) Modifier.width(150.dp) else Modifier.weight(1f))
                    .clip(shape)
                    .background(if (active) c.copy(alpha = 0.16f) else Color.Transparent)
                    .border(if (active) 2.dp else 1.dp, if (active) c else MaterialTheme.colorScheme.outlineVariant, shape)
                    .clickable(role = Role.Tab) { vm.switchWallet(w.id) }
                    .semantics { selected = active; contentDescription = vm.walletName(w) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(walletIcon(w), null, Modifier.size(16.dp), tint = c)
                    Spacer(Modifier.width(6.dp))
                    Text(vm.walletName(w), fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(vm.balances[w.id]?.let { vm.amount(it) } ?: if (vm.isBroken(w.id)) L("nejde otevřít", "can't open") else "…", fontSize = 13.sp,
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
    Text(L("Celkem ve všech peněženkách: ", "Total in all wallets: ") + vm.amount(vm.totalAll),
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
}

/** Malý štítek „ve které peněžence jsem“ – na obrazovkách Přijmout / Odeslat. */
@Composable
fun WalletBadge(vm: WalletViewModel) {
    if (vm.wallets.size < 2) return
    val w = vm.activeWallet ?: return
    val c = walletColor(w)
    Row(
        Modifier.padding(top = 4.dp).clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.14f))
            .border(1.dp, c, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(walletIcon(w), null, Modifier.size(14.dp), tint = c)
        Spacer(Modifier.width(6.dp))
        Text(vm.walletName(w), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Odeslat: rychlá volba „do mé jiné peněženky“ (vyplní její další volnou adresu). */
@Composable
fun OwnWalletChips(vm: WalletViewModel, onPick: (String) -> Unit) {
    val others = vm.wallets.filter { it.id != vm.activeId && vm.balances.containsKey(it.id) }
    if (others.isEmpty()) return
    Text(L("Do mé peněženky:", "To my wallet:"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        others.forEach { w ->
            val c = walletColor(w)
            AssistChip(
                onClick = { vm.ownAddress(w.id)?.let(onPick) },
                label = { Text(vm.walletName(w)) },
                leadingIcon = { Icon(walletIcon(w), null, Modifier.size(16.dp), tint = c) },
                border = AssistChipDefaults.assistChipBorder(true, borderColor = c)
            )
        }
    }
}

/** Upozornění pod adresou, když jde platba do vlastní peněženky. */
@Composable
fun OwnWalletHint(vm: WalletViewModel, address: String) {
    val id = vm.ownWalletOf(address) ?: return
    val w = vm.wallets.find { it.id == id } ?: return
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(walletColor(w)))
        Spacer(Modifier.width(6.dp))
        Text(L("Vaše peněženka „${vm.walletName(w)}“ – značka KYC / bez KYC i poznámka se přenesou.",
            "Your wallet „${vm.walletName(w)}“ – the KYC / no-KYC tag and note will carry over."), fontSize = 13.sp)
    }
}

/** Nastavení → Peněženky: seznam, přejmenování, přidání. */
@Composable
fun WalletsSection(vm: WalletViewModel, act: androidx.fragment.app.FragmentActivity) {
    var rename by remember { mutableStateOf<WalletInfo?>(null) }
    var remove by remember { mutableStateOf<WalletInfo?>(null) }
    Text(L("Peněženky", "Wallets"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text(
        L("Můžete mít víc peněženek – např. Kapesní na běžné placení a Trezor jen pro sledování úspor na Coldcardu. Každá má svá slova, adresy, poznámky i značky.",
            "You can have several wallets – e.g. Pocket for everyday spending and a Vault that only watches savings on your Coldcard. Each has its own words, addresses, notes and tags."),
        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    vm.wallets.forEach { w ->
        val active = w.id == vm.activeId
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .clickable(enabled = !active) { vm.switchWallet(w.id) }
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(walletIcon(w), null, tint = walletColor(w))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(vm.walletName(w) + if (active && vm.wallets.size > 1) L(" · zobrazená", " · shown") else "", fontWeight = FontWeight.SemiBold)
                if (vm.isBroken(w.id)) Text(L("⚠ nejde otevřít", "⚠ can't be opened"), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                else Text(walletKindText(w) + (vm.balances[w.id]?.let { " · " + vm.amount(it) } ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (vm.isBroken(w.id)) IconButton(onClick = { remove = w }) { Icon(Icons.Default.Delete, L("Odebrat", "Remove"), tint = MaterialTheme.colorScheme.error) }
            else IconButton(onClick = { rename = w }) { Icon(Icons.Default.Edit, L("Přejmenovat", "Rename")) }
        }
    }
    GlassButton(onClick = { vm.startAddWallet() }, Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(L("Přidat peněženku", "Add wallet"))
    }
    remove?.let { w ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text(L("Odebrat „${vm.walletName(w)}“?", "Remove „${vm.walletName(w)}“?")) },
            text = { Text(L("Peněženku nejde otevřít, a tak ji z telefonu odeberu. Peníze na ní zůstanou – obnovíte ji ze svých slov (u Trezoru novým načtením z Coldcardu).",
                "The wallet can't be opened, so it will be removed from the phone. The funds stay – restore it from your words (for a Vault, load it again from the Coldcard).")) },
            confirmButton = { TextButton(onClick = { remove = null; act.auth(vm, L("Odebrat peněženku", "Remove wallet"), strong = true) { vm.removeWallet(w.id) } }) { Text(L("Odebrat", "Remove")) } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text(L("Zrušit", "Cancel")) } }
        )
    }
    rename?.let { w ->
        var name by remember(w.id) { mutableStateOf(vm.walletName(w)) }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text(L("Název peněženky", "Wallet name")) },
            text = { OutlinedTextField(name, { name = it.take(30) }, singleLine = true, label = { Text(L("Název", "Name")) }) },
            confirmButton = { TextButton(onClick = { vm.renameWallet(w.id, name); rename = null }, enabled = name.isNotBlank()) { Text(L("Uložit", "Save")) } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text(L("Zrušit", "Cancel")) } }
        )
    }
}
