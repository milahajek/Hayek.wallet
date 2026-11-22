package io.github.milahajek.hayek

import android.content.Intent
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
                        b != null -> "Můžete poslat ${vm.amount(b.canSendSat)}"
                        vm.lnError != null -> "Uzel nedostupný – klepněte pro detail"
                        else -> "Načítám z vašeho uzlu…"
                    },
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

@Composable
fun LightningScreen(vm: WalletViewModel) {
    val b = vm.lnBalance
    val i = vm.lnInfo
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bolt, null, tint = Bolt)
                    Text("Lightning · ${i?.alias ?: vm.lnConfig?.hostPort ?: ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(vm.amount(b?.canSendSat ?: 0), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("můžete poslat", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text("Můžete přijmout: ${vm.amount(b?.canReceiveSat ?: 0)}", fontSize = 13.sp)
                if ((b?.pendingSat ?: 0) > 0) Text("Otevírá se kanál: ${formatSat(b!!.pendingSat)}", fontSize = 13.sp, color = HayekOrange)
                if (i != null && i.activeChannels == 0) Text("Uzel nemá žádný aktivní kanál.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }
        vm.lnError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HayekButton(onClick = { vm.openLnReceive() }, Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Default.CallReceived, null); Spacer(Modifier.width(6.dp)); Text("Přijmout")
            }
            HayekButton(onClick = { vm.openLnPay() }, Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Default.Bolt, null); Spacer(Modifier.width(6.dp)); Text("Zaplatit")
            }
        }
        Row(Modifier.padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Historie", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.lnRefresh() }, enabled = !vm.busy) { Icon(Icons.Default.Refresh, "Obnovit") }
        }
        if (vm.lnHistory.isEmpty()) Text(if (vm.busy) "Načítám z vašeho uzlu…" else "Zatím žádné Lightning platby.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize()) {
            items(vm.lnHistory, key = { it.id }) { h ->
                val date = SimpleDateFormat("d. M. yyyy HH:mm", Locale.forLanguageTag("cs")).format(Date(h.time * 1000))
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Icon(if (h.incoming) Icons.Default.CallReceived else Icons.Default.Bolt, null, tint = if (h.incoming) Green else Bolt)
                    },
                    headlineContent = { Text(if (vm.hideBalance) "••••• sat" else (if (h.incoming) "+" else "−") + formatSat(h.amountSat), fontWeight = FontWeight.SemiBold) },
                    supportingContent = {
                        Text(date + (if (h.feeSat > 0) " · poplatek ${h.feeSat} sat" else "") + (if (h.memo.isNotBlank()) " · ${h.memo}" else ""), fontSize = 12.sp)
                    }
                )
                HorizontalDivider()
            }
        }
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
    if (scanning) QrScanDialog("Načíst Lightning fakturu", onDismiss = { scanning = false }, onResult = { raw ->
        scanning = false
        if (LndClient.looksLikeInvoice(raw)) { text = raw; vm.lnDecode(raw) }
        else vm.message = "QR kód neobsahuje Lightning fakturu (lnbc…).\n\n${raw.take(200)}"
    })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Zaplatit přes Lightning", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Platí váš uzel ze svého kanálu. Limit na jednu platbu: ${formatSat(vm.lnLimit)}.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it.trim(); vm.lnDecoded = null },
            label = { Text("Faktura (lnbc…)") }, modifier = Modifier.fillMaxWidth(), maxLines = 4,
            trailingIcon = {
                Row {
                    IconButton(onClick = { scanning = true }) { Icon(Icons.Default.QrCodeScanner, "Načíst QR kód") }
                    IconButton(onClick = { paste(ctx)?.let { text = it.trim(); vm.lnDecode(text) } }) { Icon(Icons.Default.ContentPaste, "Vložit") }
                }
            }
        )
        Spacer(Modifier.height(12.dp))
        if (d == null) {
            HayekButton(onClick = { vm.lnDecode(text) }, enabled = text.isNotBlank() && !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Načíst fakturu")
            }
        } else {
            val needAmount = d.amountSat == 0L
            val amt = if (needAmount) amount.toLongOrNull() ?: 0 else d.amountSat
            if (needAmount) {
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter(Char::isDigit) },
                    label = { Text("Částka v satoshi (faktura ji neobsahuje)") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(Modifier.height(8.dp))
            }
            GlassCard(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Zkontrolujte platbu – nejde vrátit!", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Částka: ${formatSat(amt)}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (d.description.isNotBlank()) Text("Popis: ${d.description}")
                    Text("Max. poplatek: ${formatSat(vm.lnFeeLimit(amt))}", fontSize = 13.sp)
                    val mins = (d.expiresAt - System.currentTimeMillis() / 1000) / 60
                    Text("Platnost faktury: ještě ${mins} min", fontSize = 13.sp)
                    Text("Příjemce: ${d.destination.take(12)}…${d.destination.takeLast(6)}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    if (amt > vm.lnLimit) Text("⚠ Nad vaším limitem ${formatSat(vm.lnLimit)} – změníte ho v Nastavení.", color = MaterialTheme.colorScheme.error)
                    vm.lnBalance?.let { if (amt > it.canSendSat) Text("⚠ Na kanálu je jen ${formatSat(it.canSendSat)}.", color = MaterialTheme.colorScheme.error) }
                }
            }
            Spacer(Modifier.height(12.dp))
            HayekButton(
                onClick = { act.auth(vm, "Zaplatit ${formatSat(amt)} přes Lightning", strong = true) { vm.lnPay(if (needAmount) amt else null) } },
                enabled = !vm.busy && amt > 0 && amt <= vm.lnLimit, modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(6.dp)); Text(if (vm.busy) "Platím…" else "Zaplatit")
            }
            TextButton(onClick = { vm.lnDecoded = null; text = "" }, Modifier.fillMaxWidth()) { Text("Zrušit") }
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
        Text("Přijmout přes Lightning", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (inv == null) {
            val canReceive = vm.lnBalance?.canReceiveSat
            OutlinedTextField(
                value = amount, onValueChange = { amount = it.filter(Char::isDigit) },
                label = { Text("Částka v satoshi") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = memo, onValueChange = { memo = it.take(100) }, label = { Text("Popis (nepovinné)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(8.dp))
            if (canReceive != null) {
                val a = amount.toLongOrNull() ?: 0
                Text(
                    if (canReceive == 0L) "⚠ Váš kanál zatím neumí přijímat – nejdřív je potřeba přes Lightning něco zaplatit (uvolní se tím místo pro příjem)."
                    else "Kanál teď umí přijmout až ${formatSat(canReceive)}.",
                    fontSize = 13.sp, textAlign = TextAlign.Center,
                    color = if (canReceive == 0L || a > canReceive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            HayekButton(onClick = { vm.lnCreateInvoice(amount.toLong(), memo.trim()) },
                enabled = (amount.toLongOrNull() ?: 0) > 0 && !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Vytvořit fakturu") }
        } else if (vm.lnInvoicePaid) {
            Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(96.dp))
            Text("Přijato ⚡ ${formatSat(inv.amountSat)}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            HayekButton(onClick = { vm.openLightning() }, Modifier.fillMaxWidth()) { Text("Hotovo") }
        } else {
            val bmp = remember(inv) { qrBitmap("lightning:" + inv.paymentRequest.uppercase(), 720) }
            Image(bmp.asImageBitmap(), "QR kód faktury", Modifier.size(280.dp).background(Color.White).padding(8.dp))
            Spacer(Modifier.height(8.dp))
            Text(formatSat(inv.amountSat), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (inv.memo.isNotBlank()) Text(inv.memo)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.HourglassTop, null, Modifier.size(18.dp), tint = HayekOrange)
                Spacer(Modifier.width(8.dp))
                Text("Čekám na zaplacení… (platnost 1 hodina)", fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton(onClick = { copy(ctx, inv.paymentRequest); vm.message = "Faktura zkopírována." }, Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Kopírovat")
                }
                GlassButton(onClick = {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, inv.paymentRequest), "Sdílet fakturu"))
                }, Modifier.weight(1f)) { Text("Sdílet") }
            }
            TextButton(onClick = { vm.openLnReceive() }) { Text("Nová faktura") }
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
    fun tryConnect(raw: String, h: String) {
        val cfg = try { parseLndConnect(raw, h) } catch (e: Exception) { vm.message = e.message; return }
        host = cfg.hostPort
        vm.lnTest(cfg) { info -> tested = cfg to info }
    }
    if (scanning) QrScanDialog("Načíst QR z RaspiBlitzu", onDismiss = { scanning = false }, onResult = { raw ->
        scanning = false
        if (raw.trim().startsWith("lndconnect://", true)) { mac = raw.trim(); tryConnect(raw, host) }
        else vm.message = "Tohle není QR kód „lndconnect“. Zkontrolujte příkaz na uzlu."
    })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Připojit Lightning uzel", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Peněženka bude ovládat LND na vašem RaspiBlitzu. Peníze zůstávají v kanálech na uzlu, telefon dostane jen omezený klíč: " +
                "umí platit a vytvářet faktury, ale nemůže vybrat bitcoiny z uzlu ani zavírat kanály.",
            fontSize = 14.sp
        )
        Spacer(Modifier.height(12.dp))
        Text("1. Na RaspiBlitzu (SSH, mimo menu) spusťte:", fontWeight = FontWeight.SemiBold)
        val cmd = lndSetupCommand(host.ifBlank { "IP-UZLU:8080" })
        GlassCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(12.dp))
            }
        }
        TextButton(onClick = { copy(ctx, cmd); vm.message = "Příkaz zkopírován." }) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Kopírovat příkaz") }
        Text("2. Naskenujte QR kód, který se objeví:", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        HayekButton(onClick = { scanning = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(6.dp)); Text("Naskenovat QR z uzlu")
        }
        Spacer(Modifier.height(12.dp))
        Text("…nebo zadejte ručně:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = host, onValueChange = { host = it.trim(); tested = null }, label = { Text("Adresa uzlu") },
            placeholder = { Text("192.168.1.10:8080") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
        OutlinedTextField(value = mac, onValueChange = { mac = it.trim(); tested = null }, label = { Text("Macaroon (hex) nebo lndconnect://…") },
            modifier = Modifier.fillMaxWidth(), maxLines = 3,
            trailingIcon = { IconButton(onClick = { paste(ctx)?.let { mac = it.trim(); tested = null } }) { Icon(Icons.Default.ContentPaste, "Vložit") } })
        Spacer(Modifier.height(8.dp))
        GlassButton(onClick = { tryConnect(mac, host) }, enabled = mac.isNotBlank() && !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Vyzkoušet spojení") }

        tested?.let { (cfg, info) ->
            Spacer(Modifier.height(12.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VerifiedUser, null, tint = Green); Spacer(Modifier.width(8.dp))
                        Text("Spojeno s uzlem „${info.alias}“", fontWeight = FontWeight.SemiBold)
                    }
                    Text("LND ${info.version} · aktivní kanály: ${info.activeChannels}" + if (!info.syncedToChain) " · ⚠ nesynchronizováno" else "", fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    @Suppress("UNUSED_VARIABLE") val pv = vm.pinVersion
                    Text("Certifikát uzlu (SHA-256) – připnutý:", fontSize = 12.sp)
                    Text((vm.pins.get("ln:${cfg.hostPort}") ?: "").chunked(24).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("Pro kontrolu na uzlu: openssl x509 -in /mnt/hdd/lnd/tls.cert -noout -fingerprint -sha256", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    HayekButton(onClick = { act.auth(vm, "Uložit připojení k Lightning uzlu") { vm.lnSave(cfg) } }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(6.dp)); Text("Uložit a používat")
                    }
                }
            }
        }
    }
}

/** Sekce v Nastavení. */
@Composable
fun LnSettingsSection(vm: WalletViewModel) {
    var confirm by remember { mutableStateOf(false) }
    var limit by remember { mutableStateOf(vm.lnLimit.toString()) }
    Text("⚡ Lightning (váš LND uzel)", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    if (!vm.lnConfigured) {
        Text("Platby přes Lightning z vašeho RaspiBlitzu. Telefon dostane jen omezený klíč – bitcoiny z uzlu vybrat nemůže.", fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        HayekButton(onClick = { vm.screen = Screen.LnSetup }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Bolt, null); Spacer(Modifier.width(6.dp)); Text("Připojit Lightning") }
        return
    }
    Text("Připojeno: ${vm.lnConfig?.hostPort ?: "(odemkněte peněženku)"}" + (vm.lnInfo?.let { " · ${it.alias}" } ?: ""), fontSize = 14.sp)
    vm.lnPinned()?.let { Text("Certifikát: ${it.take(23)}…", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(value = limit, onValueChange = { limit = it.filter(Char::isDigit) }, label = { Text("Limit na jednu platbu (sat)") },
        singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = { Text("Pojistka pro případ, že by vám někdo vzal odemčený telefon.") })
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassButton(onClick = { confirm = true }, Modifier.weight(1f)) { Text("Odpojit") }
        HayekButton(onClick = { limit.toLongOrNull()?.let { vm.saveLnLimit(it); vm.message = "Limit uložen: ${formatSat(it)}." } },
            enabled = limit.toLongOrNull() != null, modifier = Modifier.weight(1f)) { Text("Uložit limit") }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Odpojit Lightning?") },
        text = { Text("Telefon zapomene přístupový klíč a certifikát uzlu. Peníze v kanálech zůstávají na uzlu. Klíč úplně zneplatníte na uzlu příkazem: lncli deletemacaroonid $LND_ROOT_KEY_ID") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.lnRemove() }) { Text("Odpojit") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Zrušit") } }
    )
}
