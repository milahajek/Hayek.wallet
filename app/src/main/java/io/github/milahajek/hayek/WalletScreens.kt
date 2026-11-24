package io.github.milahajek.hayek

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

// ---------------- Peněženka ----------------

@Composable
fun HomeScreen(vm: WalletViewModel, act: FragmentActivity) {
    val ctx = LocalContext.current
    val st = vm.state
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        if (vm.serverUrl.isBlank()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(L("Nastavte svůj uzel", "Set up your node"), fontWeight = FontWeight.Bold)
                    Text(L("Zadejte v Nastavení adresu svého Electrum serveru (Fulcrum, ElectRS). Kdo vlastní uzel nemá, může si tam vybrat veřejný server.", "Enter your Electrum server address (Fulcrum, ElectRS) in Settings. Without your own node you can choose a public server there."), fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    HayekButton(onClick = { vm.screen = Screen.Settings }, Modifier.fillMaxWidth()) { Text(L("Otevřít Nastavení", "Open Settings")) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (vm.wallets.size > 1) { WalletSwitcher(vm); Spacer(Modifier.height(10.dp)) }
        GlassCard(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp).fillMaxWidth().clickable { vm.toggleHideBalance() }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(L("Zůstatek", "Balance"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(vm.amount(st?.totalSat ?: 0), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Text(if (vm.hideBalance) L("klepnutím zobrazíte", "tap to show") else formatBtc(st?.totalSat ?: 0), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val pending = st?.pendingSat ?: 0
                    if (pending > 0 && !vm.hideBalance) {
                        Text(L("z toho čeká na potvrzení: ${formatSat(pending)}", "of which unconfirmed: ${formatSat(pending)}"), color = HayekOrange, fontSize = 13.sp)
                    }
                    TagBalances(vm)
                }
                IconButton(onClick = { vm.toggleHideBalance() }, Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    Icon(if (vm.hideBalance) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        if (vm.hideBalance) L("Zobrazit zůstatek", "Show balance") else L("Skrýt zůstatek", "Hide balance"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HayekButton(onClick = { vm.screen = Screen.Receive }, Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.AutoMirrored.Filled.CallReceived, null); Spacer(Modifier.width(6.dp)); Text(L("Přijmout", "Receive"))
            }
            HayekButton(onClick = { vm.openSend() }, Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.AutoMirrored.Filled.CallMade, null); Spacer(Modifier.width(6.dp)); Text(L("Odeslat", "Send"))
            }
        }
        if (vm.watchOnly) Text(
            L("Jen sledování: telefon nemá soukromé klíče. Platby připraví a podepíšete je hardwarovou peněženkou (Coldcard přes NFC nebo kartu).", "Watch-only: the phone holds no private keys. It prepares payments and you sign them with a hardware wallet (Coldcard via NFC or memory card)."),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
        )
        GlassButton(onClick = { vm.openCoins(Screen.Home) }, Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Icon(Icons.Default.Toll, null); Spacer(Modifier.width(6.dp))
            Text(L("Mince (UTXO): ${vm.coins.size}", "Coins (UTXO): ${vm.coins.size}") + if (vm.coins.any { it.frozen }) L(" · zmrazené: ${vm.coins.count { it.frozen }}", " · frozen: ${vm.coins.count { it.frozen }}") else "")
        }
        if (vm.lnConfig != null) { Spacer(Modifier.height(8.dp)); LightningHomeCard(vm) }
        Text(L("Transakce", "Transactions"), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        val txs = st?.txs.orEmpty()
        if (txs.isEmpty()) Text(if (vm.busy) L("Načítám z vašeho uzlu…", "Loading from your node…") else L("Zatím žádné transakce.", "No transactions yet."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize()) {
            items(txs, key = { it.txid }) { tx -> TxRow(tx, vm) { vm.openTxDetail(tx) } }
        }
    }
    vm.txDetail?.let { TxDetailDialog(it, vm, act) }
    if (vm.offerLabelsImport) OfferLabelsImportDialog(vm)
}

@Composable
fun TxRow(tx: TxItem, vm: WalletViewModel, onClick: () -> Unit) {
    val incoming = tx.deltaSat >= 0
    val date = tx.time?.let { fmtDate(it * 1000, time = true) }
    vm.notesVersion   // čtení verze: po změně poznámky se řádek překreslí
    val note = vm.note(tx.txid)
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(if (incoming) Icons.AutoMirrored.Filled.CallReceived else Icons.AutoMirrored.Filled.CallMade, null,
                tint = if (incoming) Color(0xFF2E9E4F) else HayekOrange, modifier = Modifier.pendingPulse(!tx.confirmed))
        },
        headlineContent = { Text(vm.amount(tx.deltaSat, sign = true), fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            Column {
                if (note.isNotBlank()) Text(note, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(if (tx.confirmed) L("Potvrzeno${date?.let { " · $it" } ?: ""}", "Confirmed${date?.let { " · $it" } ?: ""}") else L("Čeká na potvrzení", "Waiting for confirmation") + if (tx.canBump) L(" · lze urychlit", " · can be sped up") else "", fontSize = 13.sp)
                if (vm.txHasDust(tx.txid)) Box(Modifier.padding(top = 4.dp)) { DustPill(vm.coins.any { it.txid == tx.txid && it.frozen }) }
                else TxTagChip(vm, tx.txid, incoming)
            }
        },
        trailingContent = { Text(tx.txid.take(8) + "…", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    )
    HorizontalDivider()
}

@Composable
fun ReceiveScreen(vm: WalletViewModel, preview: Triple<Boolean, String, String>? = null) {
    val ctx = LocalContext.current
    val addr = vm.state?.address ?: ""
    var withAmount by remember(addr) { mutableStateOf(preview?.first ?: false) }
    var amount by remember(addr) { mutableStateOf(preview?.second ?: "") }
    var label by remember(addr) { mutableStateOf(preview?.third ?: "") }
    val sat = amount.toLongOrNull()?.takeIf { withAmount && it > 0 }
    val lbl = label.trim().takeIf { withAmount && it.isNotEmpty() }
    val uri = buildPaymentUri(addr, sat, lbl)
    val isRequest = sat != null || lbl != null
    val bmp = remember(uri) { if (addr.isNotEmpty()) qrBitmap(uri) else null }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(L("Přijmout bitcoiny", "Receive bitcoin"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        WalletBadge(vm)
        Spacer(Modifier.height(16.dp))
        bmp?.let { Image(it.asImageBitmap(), L("QR kód adresy", "Address QR code"), Modifier.size(260.dp).background(Color.White).padding(8.dp)) }
        if (isRequest) {
            Spacer(Modifier.height(10.dp))
            Text(listOfNotNull(sat?.let { formatSat(it) }, lbl).joinToString(" · "), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(addr.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 16.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth().clickable { withAmount = !withAmount }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(L("Požadovat částku", "Request an amount"), fontWeight = FontWeight.SemiBold)
                        Text(L("QR kód bude obsahovat částku a popis – plátce je nemusí přepisovat.", "The QR code will include the amount and description – the payer doesn't have to type them."), fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = withAmount, onCheckedChange = { withAmount = it })
                }
                if (withAmount) {
                    OutlinedTextField(
                        value = amount, onValueChange = { amount = it.filter(Char::isDigit).take(16) },
                        label = { Text(L("Částka v satoshi", "Amount in satoshi")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { Text(if (sat != null) formatBtc(sat) else L("1 BTC = 100 000 000 sat", "1 BTC = 100,000,000 sat")) }
                    )
                    OutlinedTextField(
                        value = label, onValueChange = { label = it.take(100) },
                        label = { Text(L("Popis (nepovinný)", "Description (optional)")) }, placeholder = { Text(L("např. Oběd s bráchou", "e.g. Lunch with my brother")) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        supportingText = { Text(L("Po přijetí se popis sám uloží jako poznámka k platbě.", "Once received, the description is saved as the payment's note automatically.")) }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val shareText = if (isRequest) uri else addr
        HayekButton(onClick = {
            if (lbl != null) vm.rememberRequest(addr, lbl)
            copy(ctx, shareText); vm.message = if (isRequest) L("Žádost o platbu zkopírována.", "Payment request copied.") else L("Adresa zkopírována.", "Address copied.")
        }, Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text(if (isRequest) L("Kopírovat žádost", "Copy request") else L("Kopírovat adresu", "Copy address"))
        }
        GlassButton(onClick = {
            if (lbl != null) vm.rememberRequest(addr, lbl)
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText),
                if (isRequest) L("Sdílet žádost o platbu", "Share payment request") else L("Sdílet adresu", "Share address")))
        }, Modifier.fillMaxWidth()) { Text(L("Sdílet", "Share")) }
        if (lbl != null) LaunchedEffect(lbl, addr) { kotlinx.coroutines.delay(800); vm.rememberRequest(addr, lbl) }
        TextButton(onClick = { vm.newAddress() }, enabled = !vm.busy) { Text(L("Vygenerovat novou adresu", "Generate a new address")) }
        Text(L("Pro každou platbu je lepší nová adresa (soukromí). Staré adresy ale fungují dál.", "A new address for each payment is better (privacy). Old addresses still work."),
            fontSize = 13.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ReceiveTagPicker(vm, addr)
    }
}

@Composable
fun SendScreen(vm: WalletViewModel, act: FragmentActivity) {
    val ctx = LocalContext.current
    var to by remember { mutableStateOf(vm.sendPrefill.also { vm.sendPrefill = "" }) }
    var amount by remember { mutableStateOf("") }
    var all by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var pickContact by remember { mutableStateOf(false) }
    val p = vm.prepared
    if (pickContact) ContactsDialog(vm, onDismiss = { pickContact = false }, onPick = { c -> to = c.address; note = null; vm.prepared = null; pickContact = false })
    if (scanning) QrScanDialog(L("Načíst adresu příjemce", "Scan recipient address"), onDismiss = { scanning = false }, onResult = { raw ->
        scanning = false
        val req = parsePaymentQr(raw)
        if (req == null) {
            if (LndClient.looksLikeInvoice(raw) && vm.lnConfig != null) vm.openLnPay(raw)
            else vm.message = if (raw.trim().lowercase().let { it.startsWith("lightning:") || it.startsWith("lnbc") || it.startsWith("lnurl") })
                L("Tohle je Lightning platba. Připojte v ⚙ Nastavení svůj Lightning uzel (LND) a pak ji zaplatíte přes ⚡ Lightning.", "This is a Lightning payment. Connect your Lightning node (LND) in ⚙ Settings and then pay it via ⚡ Lightning.")
            else L("QR kód neobsahuje bitcoinovou adresu:\n\n${raw.take(200)}", "The QR code doesn't contain a bitcoin address:\n\n${raw.take(200)}")
        } else {
            to = req.address
            req.amountSat?.let { amount = it.toString(); all = false }
            note = listOfNotNull(
                req.label?.let { L("Popis: $it", "Description: $it") },
                req.amountSat?.let { L("Částka převzata z QR kódu – zkontrolujte ji.", "Amount taken from the QR code – please check it.") }
            ).joinToString("\n").ifEmpty { null }
            vm.prepared = null
        }
    })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(L("Odeslat", "Send"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        WalletBadge(vm)
        Text(L("K dispozici: ${formatSat(vm.coins.filter { !it.frozen }.sumOf { it.valueSat })}", "Available: ${formatSat(vm.coins.filter { !it.frozen }.sumOf { it.valueSat })}") +
            if (vm.coins.any { it.frozen }) L(" (bez zmrazených)", " (excluding frozen)") else "", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = to, onValueChange = { to = it.trim().removePrefix("bitcoin:").substringBefore("?"); note = null; vm.prepared = null },
            label = { Text(L("Adresa příjemce", "Recipient address")) }, placeholder = { Text("bc1… / 1… / 3…") }, modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                Row {
                    IconButton(onClick = { pickContact = true }) { Icon(Icons.Default.Person, L("Adresář", "Address book")) }
                    IconButton(onClick = { scanning = true }) { Icon(Icons.Default.QrCodeScanner, L("Načíst QR kód", "Scan QR code")) }
                    IconButton(onClick = {
                        paste(ctx)?.let { raw ->
                            val req = parsePaymentQr(raw)
                            to = req?.address ?: raw.trim().removePrefix("bitcoin:").substringBefore("?")
                            req?.amountSat?.let { amount = it.toString(); all = false }
                            note = null; vm.prepared = null
                        }
                    }) { Icon(Icons.Default.ContentPaste, L("Vložit", "Paste")) }
                }
            },
            supportingText = note?.let { n -> { Text(n) } }
        )
        ContactHint(vm, to)
        OwnWalletHint(vm, to)
        if (to.isEmpty()) OwnWalletChips(vm) { a -> to = a; note = null; vm.prepared = null }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = amount, onValueChange = { amount = it.filter(Char::isDigit); vm.prepared = null },
            label = { Text(L("Částka v satoshi", "Amount in satoshi")) }, enabled = !all, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text(if (amount.isNotEmpty()) formatBtc(amount.toLongOrNull() ?: 0) else L("1 BTC = 100 000 000 sat", "1 BTC = 100,000,000 sat")) }
        )
        Row(Modifier.clickable { all = !all; vm.prepared = null }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = all, onCheckedChange = { all = it; vm.prepared = null }); Text(L("Poslat vše", "Send all"))
        }
        val sel = vm.coins.filter { it.id in vm.selected }
        GlassCard(Modifier.fillMaxWidth().clickable { vm.openCoins(Screen.Send) }) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Toll, null, tint = HayekOrange)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(L("Mince k utracení (coin control)", "Coins to spend (coin control)"), fontWeight = FontWeight.SemiBold)
                    Text(
                        if (sel.isEmpty()) L("Automaticky (zmrazené se nepoužijí)", "Automatic (frozen coins are not used)")
                        else L("Ručně: ${sel.size} ${if (sel.size == 1) "mince" else "mincí"} · ${formatSat(sel.sumOf { it.valueSat })}", "Manual: ${sel.size} ${if (sel.size == 1) "coin" else "coins"} · ${formatSat(sel.sumOf { it.valueSat })}"),
                        fontSize = 13.sp
                    )
                }
                Text(L("Vybrat", "Choose"), color = HayekOrange)
            }
        }
        PayFromPicker(vm)
        Spacer(Modifier.height(8.dp))
        FeePicker(
            estimates = vm.feeEstimates, choice = vm.feeChoice, rate = vm.feeRate,
            onChoice = { vm.chooseFee(it) }, onCustomRate = { vm.feeRate = it; vm.prepared = null },
            loading = vm.feeEstimates == null && vm.connected != false
        )
        Spacer(Modifier.height(16.dp))
        if (p == null) {
            HayekButton(onClick = { vm.prepare(to, amount, all) },
                enabled = to.isNotBlank() && (all || amount.isNotBlank()) && !vm.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(L("Pokračovat", "Continue")) }
        } else {
            @Suppress("UNUSED_VARIABLE") val tv = vm.tagsVersion
            val inputTags = remember(p, vm.tagsVersion) { vm.inputTags(p) }
            var mixAck by remember(p) { mutableStateOf(false) }
            GlassCard(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(L("Zkontrolujte platbu – nejde vrátit!", "Check the payment – it can't be reversed!"), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(L("Komu:", "To:") + (vm.contactFor(p.to)?.let { " 👤 ${it.name}" } ?: ""), fontWeight = if (vm.contactFor(p.to) != null) FontWeight.SemiBold else null)
                    Text(p.to.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(L("Částka: ${formatSat(p.amountSat)}", "Amount: ${formatSat(p.amountSat)}"))
                    Text(L("Poplatek: ${formatSat(p.feeSat)} (${p.feeRate} sat/vB)", "Fee: ${formatSat(p.feeSat)} (${p.feeRate} sat/vB)"))
                    Text(L("Použité mince: ${p.inputs}", "Coins used: ${p.inputs}"))
                    MixWarning(inputTags, mixAck) { mixAck = it }
                    Text(L("Celkem: ${formatSat(p.amountSat + p.feeSat)}", "Total: ${formatSat(p.amountSat + p.feeSat)}"), fontWeight = FontWeight.Bold)
                    if (p.feeSat > p.amountSat / 10) Text(L("⚠ Poplatek je přes 10 % částky.", "⚠ The fee is over 10 % of the amount."), color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(12.dp))
            var coldcard by remember { mutableStateOf(false) }
            if (coldcard) ColdcardSignDialog(vm) { coldcard = false }
            if (vm.watchOnly) {
                if (!vm.hasKeyOrigin) Text(L("⚠ Sledovací peněženka byla načtena bez otisku hardwarové peněženky (jen zpub). Coldcard takovou transakci nemusí přijmout – načtěte raději deskriptor z Coldcardu.",
                    "⚠ This watch-only wallet was imported without the hardware wallet fingerprint (zpub only). The Coldcard may reject the transaction – import the descriptor from the Coldcard instead."),
                    color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                HayekButton(onClick = { coldcard = true },
                    enabled = !vm.busy && (!mixesNoKyc(inputTags) || mixAck), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Default.Contactless, null); Spacer(Modifier.width(6.dp)); Text(L("Podepsat Coldcardem", "Sign with Coldcard"))
                }
            } else HayekButton(onClick = { act.auth(vm, L("Potvrdit platbu ${formatSat(p.amountSat)}", "Confirm payment ${formatSat(p.amountSat)}"), strong = true) { vm.broadcast() } },
                enabled = !vm.busy && (!mixesNoKyc(inputTags) || mixAck), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(6.dp)); Text(L("Podepsat a odeslat", "Sign and send"))
            }
            TextButton(onClick = { vm.prepared = null }, Modifier.fillMaxWidth()) { Text(L("Zrušit", "Cancel")) }
        }
    }
}

@Composable
fun SeedScreen(vm: WalletViewModel, act: FragmentActivity) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(L("Obnovovací fráze", "Recovery phrase"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        WalletBadge(vm)
        Text(L("Nikomu ji neukazujte. Nikdo (ani podpora) ji po vás nikdy nepotřebuje.", "Don't show it to anyone. Nobody (not even support) will ever need it from you."))
        Spacer(Modifier.height(12.dp))
        if (vm.revealSeed) {
            SeedGrid(vm.seedWords ?: "")
            vm.seedPassphrase?.let { p ->
                var showP by remember { mutableStateOf(false) }
                Spacer(Modifier.height(12.dp))
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, null, tint = HayekOrange); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(L("Heslo navíc (passphrase)", "Passphrase"), fontWeight = FontWeight.SemiBold)
                            Text(if (showP) "„$p“" else "•".repeat(minOf(p.length, 16)), fontFamily = FontFamily.Monospace)
                        }
                        IconButton(onClick = { showP = !showP }) { Icon(if (showP) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) }
                    }
                }
            }
            vm.walletFingerprint?.let { fp ->
                Spacer(Modifier.height(8.dp))
                Text(L("Otisk peněženky: $fp", "Wallet fingerprint: $fp") + if (vm.seedPassphrase != null) L(" – při obnově se slovy a heslem musí vyjít stejný.", " – restoring with the words and passphrase must give the same one.") else "",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        else HayekButton(onClick = { act.auth(vm, L("Zobrazit obnovovací frázi", "Show recovery phrase"), strong = true) { vm.revealSeed = true } }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(6.dp)); Text(L("Zobrazit slova", "Show words"))
        }
    }
}

@Composable
fun CoinsScreen(vm: WalletViewModel) {
    val ctx = LocalContext.current
    val sel = vm.coins.filter { it.id in vm.selected }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(L("Mince (UTXO)", "Coins (UTXO)"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                (if (false) ""
                else L("Zaškrtněte mince, které se mají v příští platbě utratit. ❄ zmrazí minci – nikdy se nepoužije, dokud ji nerozmrazíte.", "Tick the coins to spend in the next payment. ❄ freezes a coin – it's never used until you unfreeze it.")) +
                    L(" Štítkem označíte původ mince (Bez KYC / KYC), aby se omylem nesmíchaly.", " The tag marks a coin's origin (No KYC / KYC) so they don't get mixed by accident."),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (vm.coins.isEmpty()) Text(L("Žádné mince.", "No coins."), Modifier.padding(16.dp))
        @Suppress("UNUSED_VARIABLE") val tv = vm.tagsVersion
        LazyColumn(Modifier.weight(1f)) {
            items(vm.coins, key = { it.id }) { c ->
                val tag = vm.coinTag(c)
                ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(enabled = !c.frozen) { vm.toggleSelect(c.id) },
                    leadingContent = {
                        Checkbox(checked = c.id in vm.selected, enabled = !c.frozen, onCheckedChange = { vm.toggleSelect(c.id) })
                    },
                    headlineContent = {
                        Text(formatSat(c.valueSat), fontWeight = FontWeight.SemiBold,
                            color = if (c.frozen) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    },
                    supportingContent = {
                        Column {
                            Text(c.address.take(14) + "…" + c.address.takeLast(6), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text(
                                (if (c.confirmed) L("Potvrzeno v bloku ${c.height}", "Confirmed in block ${c.height}") else L("Čeká na potvrzení", "Waiting for confirmation")) + (if (c.change) L(" · drobné", " · change") else "") + if (c.frozen) L(" · ZMRAZENO", " · FROZEN") else "",
                                fontSize = 12.sp, color = if (c.frozen) Color(0xFF3B8ED6) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (tag != null || vm.isDust(c)) Row(Modifier.padding(top = 4.dp)) {
                                tag?.let { TagPill(it.key); Spacer(Modifier.width(6.dp)) }
                                if (vm.isDust(c)) DustPill(c.frozen)
                            }
                        }
                    },
                    trailingContent = {
                        Row {
                            TagMenu(tag) { vm.setCoinTag(listOf(c.id), it) }
                            IconButton(onClick = { openTx(ctx, vm, vm.txLink(c.txid, c.vout)) }) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, "Detail", Modifier.size(20.dp))
                            }
                            IconButton(onClick = { vm.setFrozen(c, !c.frozen) }, enabled = !vm.busy) {
                                Icon(Icons.Default.AcUnit, if (c.frozen) L("Rozmrazit", "Unfreeze") else L("Zmrazit", "Freeze"),
                                    tint = if (c.frozen) Color(0xFF3B8ED6) else MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                )
                HorizontalDivider()
            }
        }
        GlassCard(Modifier.padding(10.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    if (sel.isEmpty()) L("Nic nevybráno – platba vybere mince automaticky.", "Nothing selected – the payment picks coins automatically.")
                    else L("Vybráno ${sel.size} · ${formatSat(sel.sumOf { it.valueSat })}", "Selected ${sel.size} · ${formatSat(sel.sumOf { it.valueSat })}"),
                    fontWeight = FontWeight.SemiBold
                )
                if (sel.isNotEmpty()) {
                    val selTags = sel.map { vm.coinTag(it) }
                    if (mixesNoKyc(selTags)) Text(L("⚠ Výběr míchá mince Bez KYC s ostatními.", "⚠ The selection mixes No-KYC coins with others."), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(L("Označit vybrané:", "Tag selected:"), fontSize = 13.sp)
                        Spacer(Modifier.width(8.dp))
                        TagPill(CoinTag.NOKYC.key, selected = selTags.all { it == CoinTag.NOKYC }) { vm.setCoinTag(sel.map { it.id }, CoinTag.NOKYC) }
                        Spacer(Modifier.width(6.dp))
                        TagPill(CoinTag.KYC.key, selected = selTags.all { it == CoinTag.KYC }) { vm.setCoinTag(sel.map { it.id }, CoinTag.KYC) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassButton(onClick = { vm.selected = emptySet(); vm.prepared = null }, enabled = sel.isNotEmpty(), modifier = Modifier.weight(1f)) { Text(L("Zrušit výběr", "Clear selection")) }
                    HayekButton(onClick = { vm.prepared = null; vm.screen = Screen.Send }, modifier = Modifier.weight(1f)) { Text(L("K platbě", "To payment")) }
                }
            }
        }
    }
}

@Composable
fun ExportScreen(vm: WalletViewModel) {
    val ctx = LocalContext.current
    val desc = vm.publicDescriptor ?: ""
    val bmp = remember(desc) { if (desc.isNotEmpty()) qrBitmap(desc, 800) else null }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(L("Veřejný klíč pro sledování", "Public key for watching"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        WalletBadge(vm)
        Spacer(Modifier.height(6.dp))
        Text(
            L("Tento deskriptor vložte do jiné aplikace (Sparrow, tato aplikace v režimu sledování…) a uvidíte tam zůstatek bez možnosti utrácet. ", "Paste this descriptor into another app (Sparrow, this app in watch mode…) to see the balance there without being able to spend. ") +
                L("Bitcoiny s ním nikdo neukradne, ale kdo ho má, vidí všechny vaše adresy a transakce – sdílejte ho jen se svými zařízeními.", "Nobody can steal bitcoin with it, but whoever has it sees all your addresses and transactions – share it only with your own devices."),
            fontSize = 13.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        bmp?.let { Image(it.asImageBitmap(), L("QR deskriptoru", "Descriptor QR"), Modifier.size(280.dp).background(Color.White).padding(8.dp)) }
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(desc, fontFamily = FontFamily.Monospace, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(12.dp))
        HayekButton(onClick = { copy(ctx, desc, sensitive = true); vm.message = L("Deskriptor zkopírován (ze schránky se za minutu smaže).", "Descriptor copied (it will be cleared from the clipboard in a minute).") }, Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text(L("Kopírovat", "Copy"))
        }
    }
}
