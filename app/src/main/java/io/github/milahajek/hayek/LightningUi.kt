package io.github.milahajek.hayek

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bolt = Color(0xFFF7B500)
private val Green = Color(0xFF2E9E4F)

/** Karta na hlavní obrazovce. */
@Composable
fun LightningHomeCard(vm: WalletViewModel) {
    val b = vm.lnBalance
    GlassCard(Modifier.fillMaxWidth().clickable { vm.openLightning() }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Bolt, null, tint = Bolt, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Lightning", fontWeight = FontWeight.Bold)
                Text(
                    when {
                        b != null -> L("Můžete poslat ${vm.amount(b.canSendSat)}", "You can send ${vm.amount(b.canSendSat)}")
                        vm.lnError != null -> L("Uzel nedostupný – klepněte pro detail", "Node unreachable – tap for details")
                        else -> L("Načítám z vašeho uzlu…", "Loading from your node…")
                    },
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                @Suppress("UNUSED_VARIABLE") val bv = vm.lnBackupVersion
                if (vm.lnBackupNeeded) Text(L("● Kanály nejsou zálohované", "● Channels are not backed up"), fontSize = 13.sp, color = HayekOrange, fontWeight = FontWeight.Medium)
            }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

@Composable
fun LightningScreen(vm: WalletViewModel) {
    val ctx = LocalContext.current
    val b = vm.lnBalance
    val i = vm.lnInfo
    var help by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bolt, null, tint = Bolt)
                        Text("Lightning · ${i?.alias ?: vm.lnConfig?.hostPort ?: ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(vm.amount(b?.canSendSat ?: 0), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(L("můžete poslat", "you can send"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(L("Můžete přijmout: ${vm.amount(b?.canReceiveSat ?: 0)}", "You can receive: ${vm.amount(b?.canReceiveSat ?: 0)}"), fontSize = 13.sp)
                }
            }
            vm.lnError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HayekButton(onClick = { vm.openLnReceive() }, Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Default.CallReceived, null); Spacer(Modifier.width(6.dp)); Text(L("Přijmout", "Receive"))
                }
                HayekButton(onClick = { vm.openLnPay() }, Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Default.Bolt, null); Spacer(Modifier.width(6.dp)); Text(L("Zaplatit", "Pay"))
                }
            }
            LnBackupCard(vm)
            val chans = vm.lnChannels
            Row(Modifier.padding(top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(L("Kanály", "Channels") + (chans?.let { " (${it.size})" } ?: ""), fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { help = true }) { Icon(Icons.Default.HelpOutline, L("Co je kanál?", "What is a channel?"), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = { vm.lnRefresh() }, enabled = !vm.busy) { Icon(Icons.Default.Refresh, L("Obnovit", "Refresh")) }
            }
            when {
                chans == null -> Text(if (vm.busy) L("Načítám kanály…", "Loading channels…") else L("Kanály se nepodařilo načíst.", "Couldn't load the channels."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                chans.isEmpty() -> GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(L("Uzel zatím nemá žádný kanál", "The node has no channel yet"), fontWeight = FontWeight.SemiBold)
                        Text(L("Bez kanálu nejde přes Lightning platit. Kanál se otevírá na RaspiBlitzu (lncli openchannel …).", "Without a channel you can't pay over Lightning. Channels are opened on RaspiBlitz (lncli openchannel …)."), fontSize = 13.sp)
                    }
                }
                else -> chans.forEach { ChannelCard(it, vm) { url -> openTx(ctx, vm, url) }; Spacer(Modifier.height(10.dp)) }
            }
            Text(L("Historie", "History"), fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            if (vm.lnHistory.isEmpty()) Text(if (vm.busy) L("Načítám z vašeho uzlu…", "Loading from your node…") else L("Zatím žádné Lightning platby.", "No Lightning payments yet."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(vm.lnHistory, key = { it.id }) { h ->
            val date = fmtDate(h.time * 1000, time = true)
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = {
                    Icon(if (h.incoming) Icons.Default.CallReceived else Icons.Default.Bolt, null, tint = if (h.incoming) Green else Bolt)
                },
                headlineContent = { Text(if (vm.hideBalance) "••••• sat" else (if (h.incoming) "+" else "−") + formatSat(h.amountSat), fontWeight = FontWeight.SemiBold) },
                supportingContent = {
                    Text(date + (if (h.feeSat > 0) L(" · poplatek ${h.feeSat} sat", " · fee ${h.feeSat} sat") else "") + (if (h.memo.isNotBlank()) " · ${h.memo}" else ""), fontSize = 12.sp)
                }
            )
            HorizontalDivider()
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (help) AlertDialog(
        onDismissRequest = { help = false },
        confirmButton = { TextButton(onClick = { help = false }) { Text(L("Rozumím", "Got it")) } },
        title = { Text(L("Jak fungují kanály", "How channels work")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(L("Kanál je společná „kasička“ vašeho uzlu a jednoho protějšku, zajištěná transakcí na blockchainu. Platby se pak jen přesouvají mezi oběma stranami – okamžitě a skoro zdarma.", "A channel is a shared „piggy bank“ of your node and one peer, secured by a transaction on the blockchain. Payments then just move between the two sides – instantly and almost for free."))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(LocalSide)); Spacer(Modifier.width(8.dp))
                    Text(L("Vaše strana – to můžete poslat.", "Your side – this much you can send."), fontWeight = FontWeight.SemiBold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(RemoteSide)); Spacer(Modifier.width(8.dp))
                    Text(L("Strana protějšku – tolik můžete přijmout.", "The peer's side – this much you can receive."), fontWeight = FontWeight.SemiBold)
                }
                Text(L("Když platíte, posouvá se hranice doleva (vaše strana ubývá, protějšku přibývá). Když přijímáte, posouvá se doprava. Velikost kanálu zůstává stejná.", "When you pay, the boundary moves left (your side shrinks, the peer's grows). When you receive, it moves right. The channel size stays the same."))
                Text(L("Kanál, který otevřete vy, je zezačátku celý na vaší straně – proto zatím nemůžete přijímat. Místo pro příjem vznikne, jakmile něco zaplatíte.", "A channel you open is entirely on your side at first – that's why you can't receive yet. Room to receive appears once you pay something."))
                Text(L("Malá část (rezerva, obvykle 1 %) musí na vaší straně zůstat jako pojistka a nejde utratit. Peníze v kanálu jsou pořád vaše – zavřením kanálu se vrátí na uzel.", "A small part (the reserve, usually 1 %) must stay on your side as a safeguard and can't be spent. The money in the channel is still yours – closing the channel returns it to the node."), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

private val LocalSide = Color(0xFFF7931A)
private val RemoteSide = Color(0xFF5B8DEF)

private fun parseColor(hex: String?): Color? = runCatching {
    val h = hex!!.removePrefix("#"); require(h.length == 6); Color(0xFF000000 or h.toLong(16))
}.getOrNull()

@Composable
fun ChannelCard(ch: LnChannel, vm: WalletViewModel, openUrl: (String?) -> Unit) {
    val ctx = LocalContext.current
    var expanded by remember(ch.channelPoint) { mutableStateOf(false) }
    val (statusText, statusColor) = when {
        ch.pending -> L("Otevírá se", "Opening") to HayekOrange
        ch.active -> L("Aktivní · ", "Active · ") + (if (ch.private) L("soukromý", "private") else L("veřejný", "public")) to Green
        else -> "Offline" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val usable = ch.active && !ch.pending
    val dim = if (usable) 1f else 0.4f
    GlassCard(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val avatar = parseColor(ch.peerColor)?.takeIf { it != Color.Black } ?: RemoteSide
                Box(Modifier.size(40.dp).clip(CircleShape).background(avatar.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    if (ch.peerAlias.isNullOrBlank()) Icon(Icons.Default.Bolt, null, tint = avatar)
                    else Text(ch.title.take(1).uppercase(), fontWeight = FontWeight.Bold, color = avatar, fontSize = 18.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ch.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor)); Spacer(Modifier.width(6.dp))
                        Text(statusText, fontSize = 12.sp, color = statusColor, maxLines = 1)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(vm.amount(ch.capacitySat), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(L("velikost", "capacity"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(14.dp))
            // Pruh: vaše strana vs. strana protějšku
            val total = (ch.localSat + ch.remoteSat).coerceAtLeast(1)
            val localFrac = (ch.localSat.toFloat() / total).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(RemoteSide.copy(alpha = (if (ch.remoteSat > 0) 0.85f else 0.18f) * dim))) {
                if (localFrac > 0f) Box(
                    Modifier.fillMaxHeight().fillMaxWidth(localFrac).clip(RoundedCornerShape(7.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFFFFB347).copy(alpha = dim), LocalSide.copy(alpha = dim))))
                )
            }
            Spacer(Modifier.height(6.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    Text(if (ch.pending) L("Vaše strana", "Your side") else L("↑ Můžete poslat", "↑ You can send"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(vm.amount(if (ch.pending) ch.localSat else ch.canSendSat), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LocalSide.copy(alpha = maxOf(dim, 0.7f)))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (ch.pending) L("Strana protějšku", "Peer's side") else L("Můžete přijmout ↓", "You can receive ↓"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(vm.amount(ch.canReceiveSat), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = RemoteSide.copy(alpha = maxOf(dim, 0.7f)))
                }
            }
            val hint = when {
                ch.pending -> L("Kanál čeká na potvrzení v blockchainu (obvykle 3–6 bloků, do hodiny). Pak se zapne sám.", "The channel is waiting for confirmation on the blockchain (usually 3–6 blocks, within an hour). Then it activates by itself.")
                !ch.active -> L("Protějšek je teď nedostupný, kanálem nejde platit. Peníze jsou v bezpečí – spojení se obvykle obnoví samo.", "The peer is unreachable right now, so the channel can't be used. Your money is safe – the connection usually comes back by itself.")
                ch.canReceiveSat == 0L -> L("Na příjem zatím není místo. Uvolní se, jakmile přes tento kanál něco zaplatíte.", "No room to receive yet. It frees up once you pay something through this channel.")
                else -> null
            }
            if (hint != null) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                Icon(if (ch.pending) Icons.Default.HourglassTop else Icons.Default.Info, null, Modifier.size(16.dp),
                    tint = if (ch.pending) HayekOrange else MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(6.dp))
                Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 12.dp)) {
                    HorizontalDivider(); Spacer(Modifier.height(10.dp))
                    DetailRow(L("Otevřel", "Opened by"), if (ch.initiator) "vy" else L("protějšek", "peer"))
                    if (!ch.pending) DetailRow(L("Rezerva (nelze utratit)", "Reserve (can't be spent)"), vm.amount(ch.reserveSat))
                    if (ch.sentSat > 0 || ch.receivedSat > 0) {
                        DetailRow(L("Odesláno celkem", "Sent in total"), vm.amount(ch.sentSat))
                        DetailRow(L("Přijato celkem", "Received in total"), vm.amount(ch.receivedSat))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(L("Protějšek (veřejný klíč uzlu)", "Peer (node public key)"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(ch.peerPubkey.chunked(22).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton(onClick = { copy(ctx, ch.peerPubkey); vm.message = L("Klíč protějšku zkopírován.", "Peer key copied.") }, Modifier.weight(1f)) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(L("Klíč", "Key"), fontSize = 13.sp)
                        }
                        GlassButton(onClick = { openUrl(vm.txLink(ch.fundingTxid)) }, Modifier.weight(1f)) {
                            Icon(Icons.Default.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(L("Transakce", "Transactions"), fontSize = 13.sp)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) L("Méně", "Less") else L("Podrobnosti", "Details"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun LnPayScreen(vm: WalletViewModel, act: FragmentActivity) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(vm.lnPrefill.also { vm.lnPrefill = "" }) }
    var amount by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    val d = vm.lnDecoded
    LaunchedEffect(Unit) { if (text.isNotBlank() && d == null) vm.lnDecode(text) }
    if (scanning) QrScanDialog(L("Načíst Lightning fakturu", "Scan Lightning invoice"), onDismiss = { scanning = false }, onResult = { raw ->
        scanning = false
        if (LndClient.looksLikeInvoice(raw)) { text = raw; vm.lnDecode(raw) }
        else vm.message = L("QR kód neobsahuje Lightning fakturu (lnbc…).\n\n${raw.take(200)}", "The QR code doesn't contain a Lightning invoice (lnbc…).\n\n${raw.take(200)}")
    })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(L("Zaplatit přes Lightning", "Pay over Lightning"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(L("Platí váš uzel ze svého kanálu. Limit na jednu platbu: ${formatSat(vm.lnLimit)}.", "Your node pays from its channel. Limit per payment: ${formatSat(vm.lnLimit)}."), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it.trim(); vm.lnDecoded = null },
            label = { Text(L("Faktura (lnbc…)", "Invoice (lnbc…)")) }, modifier = Modifier.fillMaxWidth(), maxLines = 4,
            trailingIcon = {
                Row {
                    IconButton(onClick = { scanning = true }) { Icon(Icons.Default.QrCodeScanner, L("Načíst QR kód", "Scan QR code")) }
                    IconButton(onClick = { paste(ctx)?.let { text = it.trim(); vm.lnDecode(text) } }) { Icon(Icons.Default.ContentPaste, L("Vložit", "Paste")) }
                }
            }
        )
        Spacer(Modifier.height(12.dp))
        if (d == null) {
            HayekButton(onClick = { vm.lnDecode(text) }, enabled = text.isNotBlank() && !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(L("Načíst fakturu", "Load invoice"))
            }
        } else {
            val needAmount = d.amountSat == 0L
            val amt = if (needAmount) amount.toLongOrNull() ?: 0 else d.amountSat
            if (needAmount) {
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter(Char::isDigit) },
                    label = { Text(L("Částka v satoshi (faktura ji neobsahuje)", "Amount in satoshi (the invoice has none)")) }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(Modifier.height(8.dp))
            }
            GlassCard(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(L("Zkontrolujte platbu – nejde vrátit!", "Check the payment – it can't be reversed!"), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(L("Částka: ${formatSat(amt)}", "Amount: ${formatSat(amt)}"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (d.description.isNotBlank()) Text(L("Popis: ${d.description}", "Description: ${d.description}"))
                    Text(L("Max. poplatek: ${formatSat(vm.lnFeeLimit(amt))}", "Max. fee: ${formatSat(vm.lnFeeLimit(amt))}"), fontSize = 13.sp)
                    val mins = (d.expiresAt - System.currentTimeMillis() / 1000) / 60
                    Text(L("Platnost faktury: ještě ${mins} min", "Invoice valid for another ${mins} min"), fontSize = 13.sp)
                    Text(L("Příjemce: ${d.destination.take(12)}…${d.destination.takeLast(6)}", "Recipient: ${d.destination.take(12)}…${d.destination.takeLast(6)}"), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    if (amt > vm.lnLimit) Text(L("⚠ Nad vaším limitem ${formatSat(vm.lnLimit)} – změníte ho v Nastavení.", "⚠ Above your limit of ${formatSat(vm.lnLimit)} – change it in Settings."), color = MaterialTheme.colorScheme.error)
                    vm.lnBalance?.let { if (amt > it.canSendSat) Text(L("⚠ Na kanálu je jen ${formatSat(it.canSendSat)}.", "⚠ The channel only has ${formatSat(it.canSendSat)}."), color = MaterialTheme.colorScheme.error) }
                }
            }
            Spacer(Modifier.height(12.dp))
            HayekButton(
                onClick = { act.auth(vm, L("Zaplatit ${formatSat(amt)} přes Lightning", "Pay ${formatSat(amt)} over Lightning"), strong = true) { vm.lnPay(if (needAmount) amt else null) } },
                enabled = !vm.busy && amt > 0 && amt <= vm.lnLimit, modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(6.dp)); Text(if (vm.busy) L("Platím…", "Paying…") else L("Zaplatit", "Pay"))
            }
            TextButton(onClick = { vm.lnDecoded = null; text = "" }, Modifier.fillMaxWidth()) { Text(L("Zrušit", "Cancel")) }
        }
    }
}

@Composable
fun LnReceiveScreen(vm: WalletViewModel) {
    val ctx = LocalContext.current
    var amount by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    val inv = vm.lnInvoice
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(L("Přijmout přes Lightning", "Receive over Lightning"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (inv == null) {
            val canReceive = vm.lnBalance?.canReceiveSat
            OutlinedTextField(
                value = amount, onValueChange = { amount = it.filter(Char::isDigit) },
                label = { Text(L("Částka v satoshi", "Amount in satoshi")) }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = memo, onValueChange = { memo = it.take(100) }, label = { Text(L("Popis (nepovinné)", "Description (optional)")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(8.dp))
            if (canReceive != null) {
                val a = amount.toLongOrNull() ?: 0
                Text(
                    if (canReceive == 0L) L("⚠ Váš kanál zatím neumí přijímat – nejdřív je potřeba přes Lightning něco zaplatit (uvolní se tím místo pro příjem).", "⚠ Your channel can't receive yet – first pay something over Lightning (that frees up room to receive).")
                    else L("Kanál teď umí přijmout až ${formatSat(canReceive)}.", "The channel can now receive up to ${formatSat(canReceive)}."),
                    fontSize = 13.sp, textAlign = TextAlign.Center,
                    color = if (canReceive == 0L || a > canReceive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            HayekButton(onClick = { vm.lnCreateInvoice(amount.toLong(), memo.trim()) },
                enabled = (amount.toLongOrNull() ?: 0) > 0 && !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(L("Vytvořit fakturu", "Create invoice")) }
        } else if (vm.lnInvoicePaid) {
            Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(96.dp))
            Text(L("Přijato ⚡ ${formatSat(inv.amountSat)}", "Received ⚡ ${formatSat(inv.amountSat)}"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            HayekButton(onClick = { vm.openLightning() }, Modifier.fillMaxWidth()) { Text(L("Hotovo", "Done")) }
        } else {
            val bmp = remember(inv) { qrBitmap("lightning:" + inv.paymentRequest.uppercase(), 720) }
            Image(bmp.asImageBitmap(), L("QR kód faktury", "Invoice QR code"), Modifier.size(280.dp).background(Color.White).padding(8.dp))
            Spacer(Modifier.height(8.dp))
            Text(formatSat(inv.amountSat), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (inv.memo.isNotBlank()) Text(inv.memo)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.HourglassTop, null, Modifier.size(18.dp), tint = HayekOrange)
                Spacer(Modifier.width(8.dp))
                Text(L("Čekám na zaplacení… (platnost 1 hodina)", "Waiting for payment… (valid for 1 hour)"), fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton(onClick = { copy(ctx, inv.paymentRequest); vm.message = L("Faktura zkopírována.", "Invoice copied.") }, Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text(L("Kopírovat", "Copy"))
                }
                GlassButton(onClick = {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, inv.paymentRequest), L("Sdílet fakturu", "Share invoice")))
                }, Modifier.weight(1f)) { Text(L("Sdílet", "Share")) }
            }
            TextButton(onClick = { vm.openLnReceive() }) { Text(L("Nová faktura", "New invoice")) }
        }
    }
}

@Composable
fun LnSetupScreen(vm: WalletViewModel, act: FragmentActivity) {
    val ctx = LocalContext.current
    val defaultHost = remember {
        runCatching { ServerAddr.parse(vm.serverUrl).host }.getOrNull()?.takeIf { !isPublicServer(vm.serverUrl) }?.let { "$it:8080" } ?: ""
    }
    var host by remember { mutableStateOf(defaultHost) }
    var mac by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var tested by remember { mutableStateOf<Pair<LndConfig, LnInfo>?>(null) }
    var confirmFp by remember { mutableStateOf<Pair<LndConfig, String>?>(null) }
    fun tryConnect(raw: String, h: String) {
        val cfg = try { parseLndConnect(raw, h) } catch (e: Exception) { vm.message = e.message; return }
        host = cfg.hostPort; tested = null; confirmFp = null
        vm.lnProbe(cfg, onNeedConfirm = { fp -> confirmFp = cfg to fp }, onOk = { info -> tested = cfg to info })
    }
    if (scanning) QrScanDialog(L("Načíst QR z RaspiBlitzu", "Scan QR from RaspiBlitz"), onDismiss = { scanning = false }, onResult = { raw ->
        scanning = false
        if (raw.trim().startsWith("lndconnect://", true)) { mac = raw.trim(); tryConnect(raw, host) }
        else vm.message = L("Tohle není QR kód „lndconnect“. Zkontrolujte příkaz na uzlu.", "This is not an „lndconnect“ QR code. Check the command on the node.")
    })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(L("Připojit Lightning uzel", "Connect Lightning node"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            L("Peněženka bude ovládat LND na vašem RaspiBlitzu. Telefon dostane omezený klíč: umí platit z kanálů a vytvářet faktury, ", "The wallet will control LND on your RaspiBlitz. The phone gets a restricted key: it can pay from channels and create invoices, ") +
                L("ale nemůže poslat bitcoiny z uzlu na blockchain, otevírat ani zavírat kanály. Prostředky v kanálech utratit umí – proto je tu limit na jednu platbu.", "but it can't send bitcoin from the node on-chain, or open or close channels. It can spend the funds in the channels – hence the per-payment limit."),
            fontSize = 14.sp
        )
        Spacer(Modifier.height(12.dp))
        Text(L("1. Na RaspiBlitzu (SSH, mimo menu) spusťte:", "1. On RaspiBlitz (SSH, outside the menu) run:"), fontWeight = FontWeight.SemiBold)
        val cmd = lndSetupCommand(host.ifBlank { "IP-UZLU:8080" })
        GlassCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(12.dp))
            }
        }
        TextButton(onClick = { copy(ctx, cmd); vm.message = L("Příkaz zkopírován.", "Command copied.") }) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text(L("Kopírovat příkaz", "Copy command")) }
        Text(L("2. Naskenujte QR kód, který se objeví:", "2. Scan the QR code that appears:"), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        HayekButton(onClick = { scanning = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(6.dp)); Text(L("Naskenovat QR z uzlu", "Scan QR from the node"))
        }
        Spacer(Modifier.height(12.dp))
        Text(L("…nebo zadejte ručně:", "…or enter manually:"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = host, onValueChange = { host = it.trim(); tested = null }, label = { Text(L("Adresa uzlu", "Node address")) },
            placeholder = { Text("192.168.1.10:8080") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
        OutlinedTextField(value = mac, onValueChange = { mac = it.trim(); tested = null }, label = { Text(L("Macaroon (hex) nebo lndconnect://…", "Macaroon (hex) or lndconnect://…")) },
            modifier = Modifier.fillMaxWidth(), maxLines = 3,
            trailingIcon = { IconButton(onClick = { paste(ctx)?.let { mac = it.trim(); tested = null } }) { Icon(Icons.Default.ContentPaste, L("Vložit", "Paste")) } })
        Spacer(Modifier.height(8.dp))
        GlassButton(onClick = { tryConnect(mac, host) }, enabled = mac.isNotBlank() && !vm.busy, modifier = Modifier.fillMaxWidth()) { Text(L("Vyzkoušet spojení", "Test connection")) }

        confirmFp?.let { (cfg, fp) ->
            Spacer(Modifier.height(12.dp))
            GlassCard(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(L("Ověřte certifikát uzlu", "Verify the node certificate"), fontWeight = FontWeight.Bold)
                    Text(L("QR kód neobsahoval otisk certifikátu. Přístupový klíč zatím nebyl odeslán. Porovnejte otisk se svým uzlem:", "The QR code didn't include the certificate fingerprint. The access key hasn't been sent yet. Compare the fingerprint with your node:"), fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(fp.chunked(24).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(L("Na RaspiBlitzu: openssl x509 -in ~/.lnd/tls.cert -noout -fingerprint -sha256", "On RaspiBlitz: openssl x509 -in ~/.lnd/tls.cert -noout -fingerprint -sha256"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    HayekButton(onClick = { confirmFp = null; vm.lnTrustAndTest(cfg, fp) { info -> tested = cfg to info } }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(L("Otisk souhlasí – připojit", "Fingerprint matches – connect"))
                    }
                    TextButton(onClick = { confirmFp = null }, Modifier.fillMaxWidth()) { Text(L("Nesouhlasí – zrušit", "Doesn't match – cancel")) }
                }
            }
        }

        tested?.let { (cfg, info) ->
            Spacer(Modifier.height(12.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VerifiedUser, null, tint = Green); Spacer(Modifier.width(8.dp))
                        Text(L("Spojeno s uzlem „${info.alias}“", "Connected to node „${info.alias}“"), fontWeight = FontWeight.SemiBold)
                    }
                    Text(L("LND ${info.version} · aktivní kanály: ${info.activeChannels}", "LND ${info.version} · active channels: ${info.activeChannels}") + if (!info.syncedToChain) L(" · ⚠ nesynchronizováno", " · ⚠ not synced") else "", fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    @Suppress("UNUSED_VARIABLE") val pv = vm.pinVersion
                    Text(L("Certifikát uzlu (SHA-256) – připnutý:", "Node certificate (SHA-256) – pinned:"), fontSize = 12.sp)
                    Text((vm.pins.get("ln:${cfg.hostPort}") ?: "").chunked(24).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text(L("Pro kontrolu na uzlu: openssl x509 -in /mnt/hdd/lnd/tls.cert -noout -fingerprint -sha256", "To check on the node: openssl x509 -in /mnt/hdd/lnd/tls.cert -noout -fingerprint -sha256"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    HayekButton(onClick = { act.auth(vm, L("Uložit připojení k Lightning uzlu", "Save the Lightning node connection")) { vm.lnSave(cfg) } }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(6.dp)); Text(L("Uložit a používat", "Save and use"))
                    }
                }
            }
        }
    }
}

/** Sekce v Nastavení. */
@Composable
fun LnSettingsSection(vm: WalletViewModel, act: FragmentActivity) {
    var confirm by remember { mutableStateOf(false) }
    var limit by remember { mutableStateOf(vm.lnLimit.toString()) }
    Text(L("⚡ Lightning (váš LND uzel)", "⚡ Lightning (your LND node)"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    if (!vm.lnConfigured) {
        Text(L("Platby přes Lightning z vašeho RaspiBlitzu. Telefon dostane omezený klíč – nemůže poslat bitcoiny z uzlu na blockchain ani zavírat kanály.", "Lightning payments from your RaspiBlitz. The phone gets a restricted key – it can't send bitcoin from the node on-chain or close channels."), fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        HayekButton(onClick = { vm.screen = Screen.LnSetup }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Bolt, null); Spacer(Modifier.width(6.dp)); Text(L("Připojit Lightning", "Connect Lightning")) }
        return
    }
    Text(L("Připojeno: ${vm.lnConfig?.hostPort ?: "(odemkněte peněženku)"}", "Connected: ${vm.lnConfig?.hostPort ?: "(unlock the wallet)"}") + (vm.lnInfo?.let { " · ${it.alias}" } ?: ""), fontSize = 14.sp)
    vm.lnPinned()?.let { Text(L("Certifikát: ${it.take(23)}…", "Certificate: ${it.take(23)}…"), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(value = limit, onValueChange = { limit = it.filter(Char::isDigit) }, label = { Text(L("Limit na jednu platbu (sat)", "Limit per payment (sat)")) },
        singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = { Text(L("Pojistka pro případ, že by vám někdo vzal odemčený telefon. Klíč v telefonu umí utratit vše, co je v kanálech – limit hlídá aplikace. Zvýšení limitu vyžaduje otisk prstu.", "A safeguard in case someone takes your unlocked phone. The key in the phone can spend everything in the channels – the app enforces the limit. Raising it requires your fingerprint.")) })
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassButton(onClick = { confirm = true }, Modifier.weight(1f)) { Text(L("Odpojit", "Disconnect")) }
        HayekButton(onClick = {
            limit.toLongOrNull()?.let { l ->
                val save = { vm.saveLnLimit(l); vm.message = L("Limit uložen: ${formatSat(l)}.", "Limit saved: ${formatSat(l)}.") }
                if (l > vm.lnLimit) act.auth(vm, L("Zvýšit limit Lightning plateb", "Raise the Lightning payment limit"), strong = true) { save() } else save()
            }
        },
            enabled = limit.toLongOrNull() != null, modifier = Modifier.weight(1f)) { Text(L("Uložit limit", "Save limit")) }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(L("Odpojit Lightning?", "Disconnect Lightning?")) },
        text = { Text(L("Telefon zapomene přístupový klíč a certifikát uzlu. Peníze v kanálech zůstávají na uzlu. Klíč úplně zneplatníte na uzlu příkazem: lncli deletemacaroonid $LND_ROOT_KEY_ID", "The phone forgets the access key and the node certificate. The money in the channels stays on the node. To fully revoke the key, run on the node: lncli deletemacaroonid $LND_ROOT_KEY_ID")) },
        confirmButton = { TextButton(onClick = { confirm = false; act.auth(vm, L("Odpojit Lightning", "Disconnect Lightning")) { vm.lnRemove() } }) { Text(L("Odpojit", "Disconnect")) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(L("Zrušit", "Cancel")) } }
    )
}

/** Záloha kanálů (Static Channel Backup) – stav a tlačítko. */
@Composable
fun LnBackupCard(vm: WalletViewModel) {
    @Suppress("UNUSED_VARIABLE") val bv = vm.lnBackupVersion
    var info by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.lnExportBackup(uri)
    }
    val need = vm.lnBackupNeeded
    val last = vm.lnLastBackup
    GlassCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (need) Icons.Default.Warning else Icons.Default.Shield, null, tint = if (need) HayekOrange else Color(0xFF2E9E5B))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).clickable { info = true }) {
                Text(L("Záloha kanálů", "Channel backup"), fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        need && last == 0L -> L("Zatím nezálohováno", "Not backed up yet")
                        need -> L("Kanály se od poslední zálohy změnily", "Channels changed since the last backup")
                        last > 0 -> L("Aktuální · ", "Up to date · ") + fmtDate(last)
                        else -> L("Bez kanálů – není co zálohovat", "No channels – nothing to back up")
                    },
                    fontSize = 13.sp, color = if (need) HayekOrange else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = {
                val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                launcher.launch("channel-backup-$day.backup")
            }, enabled = !vm.busy && vm.lnChannels != null) { Text(L("Zálohovat", "Back up")) }
        }
    }
    if (info) AlertDialog(
        onDismissRequest = { info = false },
        title = { Text(L("Záloha kanálů", "Channel backup")) },
        text = {
            Text(
                L("Peníze v Lightning kanálech jsou na vašem uzlu. Kdyby uzel odešel (vadný disk), obnovíte je z 24 slov LND a tohoto souboru: ", "The money in your Lightning channels is on your node. If the node died (bad disk), you'd restore it from the 24 LND words and this file: ") +
                    L("uzel požádá protistrany o zavření kanálů a peníze se vrátí jako běžné bitcoiny.\n\n", "the node asks the peers to close the channels and the money comes back as regular bitcoin.\n\n") +
                    L("• Zálohujte po každém otevření nebo zavření kanálu – aplikace vám to připomene.\n", "• Back up after every channel open or close – the app will remind you.\n") +
                    L("• Soubor je zašifrovaný slovy LND, bez nich je k ničemu.\n", "• The file is encrypted with the LND words, useless without them.\n") +
                    L("• Nikdy neobnovujte uzel ze staré kopie celé databáze LND – můžete přijít o peníze v kanálech.", "• Never restore the node from an old copy of the whole LND database – you could lose the money in your channels."),
                fontSize = 14.sp
            )
        },
        confirmButton = { TextButton(onClick = { info = false }) { Text(L("Rozumím", "Got it")) } },
    )
}
