package io.github.milahajek.hayek

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity

@Composable
fun SettingsScreen(vm: WalletViewModel, act: FragmentActivity) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(vm.serverUrl) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(L("Server (váš uzel nebo veřejný)", "Server (your node or a public one)"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(L("Na RaspiBlitzu: Fulcrum ssl://IP:50022 (tcp 50021), ElectRS ssl://IP:50002 (tcp 50001).", "On RaspiBlitz: Fulcrum ssl://IP:50022 (tcp 50021), ElectRS ssl://IP:50002 (tcp 50001)."), fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = url, onValueChange = { url = it.trim() }, label = { Text(L("Adresa serveru", "Server address")) },
            placeholder = { Text("ssl://IP-uzlu:50002") },
            trailingIcon = { if (url.isNotEmpty()) IconButton(onClick = { url = "" }) { Icon(Icons.Default.Close, L("Vymazat", "Clear")) } },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton(onClick = { vm.testServer(url) }, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text(L("Vyzkoušet", "Test")) }
            HayekButton(onClick = { val save = { vm.setServer(url); vm.message = L("Uloženo.", "Saved.") }; if (vm.isOpen) act.auth(vm, L("Změnit server", "Change server")) { save() } else save() }, modifier = Modifier.weight(1f)) { Text(L("Uložit", "Save")) }
        }
        Spacer(Modifier.height(8.dp))
        if (!isPublicServer(url)) Text(L("Telefon musí být ve stejné síti jako uzel, nebo připojený domů přes VPN (např. WireGuard). Aplikace se sama od sebe nikdy nepřipojí k cizímu serveru.", "The phone must be on the same network as the node, or connected home via VPN (e.g. WireGuard). The app never connects to a foreign server on its own."),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Certifikát uzlu (připnutí)
        @Suppress("UNUSED_VARIABLE") val pv = vm.pinVersion
        val fp = vm.pinnedFingerprint(url)
        var confirmForget by remember { mutableStateOf(false) }
        Spacer(Modifier.height(10.dp))
        var pickPublic by remember { mutableStateOf(false) }
        when {
            isPublicServer(url) -> GlassCard(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = HayekOrange)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Public, null, tint = HayekOrange)
                        Spacer(Modifier.width(8.dp))
                        Text(L("Veřejný server", "Public server"), fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        L("Provozovatel serveru vidí všechny adresy této peněženky, její zůstatek, historii a vaši IP adresu. ", "The server operator sees all addresses of this wallet, its balance, history and your IP address. ") +
                            L("Ke klíčům se nedostane a nic utratit nemůže. Pro soukromí doporučujeme vlastní uzel.", "They can't reach the keys and can't spend anything. For privacy we recommend your own node."),
                        fontSize = 12.sp
                    )
                }
            }
            url.startsWith("tcp://") -> Text(
                L("⚠ tcp:// je nešifrované spojení – kdokoli ve stejné síti vidí, na které adresy se peněženka ptá. Doporučujeme ssl://.", "⚠ tcp:// is an unencrypted connection – anyone on the same network can see which addresses the wallet asks about. We recommend ssl://."),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.error
            )
            fp != null -> GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VerifiedUser, null, tint = Color(0xFF2E9E4F))
                        Spacer(Modifier.width(8.dp))
                        Text(L("Certifikát uzlu je připnutý", "The node certificate is pinned"), fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        L("Peněženka se připojí jen k serveru s tímto certifikátem. Kdyby se někdo vydával za váš uzel, spojení odmítne.", "The wallet only connects to a server with this certificate. If someone impersonated your node, the connection would be refused."),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("SHA-256: " + fp.chunked(24).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    GlassButton(onClick = { confirmForget = true }, Modifier.fillMaxWidth()) { Text(L("Zapomenout certifikát", "Forget certificate")) }
                }
            }
            url.startsWith("ssl://") -> Text(
                L("Certifikát uzlu se uloží (připne) při prvním spojení. Pak už peněženka jiný nepřijme.", "The node certificate is saved (pinned) on the first connection. After that the wallet won't accept another one."),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        GlassButton(onClick = { pickPublic = true }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Public, null); Spacer(Modifier.width(6.dp)); Text(L("Nemám vlastní uzel – veřejný server", "I have no node – public server"))
        }
        if (pickPublic) {
            AlertDialog(
                onDismissRequest = { pickPublic = false },
                title = { Text(L("Veřejný server", "Public server")) },
                text = {
                    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                        PublicServerPicker(vm) { name, u ->
                            url = u; pickPublic = false; if (vm.isOpen) act.auth(vm, L("Změnit server", "Change server")) { vm.setServer(u) } else vm.setServer(u)
                            vm.message = L("Peněženka teď používá veřejný server $name.\nNa svůj uzel se vrátíte zadáním jeho adresy a Uložit.", "The wallet now uses the public server $name.\nTo return to your node, enter its address and Save.")
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { pickPublic = false }) { Text(L("Zrušit", "Cancel")) } }
            )
        }
        if (confirmForget) AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(L("Zapomenout certifikát?", "Forget certificate?")) },
            text = { Text(L("Udělejte to jen tehdy, když jste certifikát na uzlu sami změnili (reinstalace, aktualizace). Při příštím spojení se uloží nový certifikát – ať je jakýkoli.", "Only do this if you changed the certificate on the node yourself (reinstall, update). On the next connection a new certificate will be saved – whatever it is.")) },
            confirmButton = { TextButton(onClick = { confirmForget = false; if (vm.isOpen) act.auth(vm, L("Zapomenout certifikát", "Forget certificate")) { vm.forgetPin(url) } else vm.forgetPin(url) }) { Text(L("Zapomenout", "Forget")) } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(L("Zrušit", "Cancel")) } }
        )

        if (vm.isOpen) {
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            LnSettingsSection(vm, act)
        }

        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        Text(L("Prohlížeč bloků (váš Mempool)", "Block explorer (your Mempool)"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(L("Klepnutí na transakci ji otevře v Mempoolu na vašem RaspiBlitzu – nikam jinam se nic neposílá. Prázdné pole = odkazy vypnuté.", "Tapping a transaction opens it in Mempool on your RaspiBlitz – nothing is sent anywhere else. Empty field = links off."), fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        var exp by remember { mutableStateOf(vm.explorerUrl) }
        OutlinedTextField(value = exp, onValueChange = { exp = it.trim() }, label = { Text(L("Adresa Mempoolu", "Mempool address")) },
            placeholder = { Text("http://IP-uzlu:4080") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton(onClick = { if (exp.isNotBlank()) openUrl(ctx, exp) }, enabled = exp.isNotBlank(), modifier = Modifier.weight(1f)) { Text(L("Otevřít", "Open")) }
            HayekButton(onClick = { vm.setExplorer(exp); vm.message = if (exp.isBlank()) L("Odkazy na transakce vypnuty.", "Transaction links off.") else L("Uloženo.", "Saved.") }, modifier = Modifier.weight(1f)) { Text(L("Uložit", "Save")) }
        }
        Text(L("RaspiBlitz: http://IP:4080, nebo https://IP:4081 (prohlížeč upozorní na vlastní certifikát).", "RaspiBlitz: http://IP:4080, or https://IP:4081 (the browser will warn about the self-signed certificate)."),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        Text(L("Vzhled", "Appearance"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(L("Podle telefonu", "System"), L("Světlý", "Light"), L("Tmavý", "Dark")).forEachIndexed { i, label ->
                FilterChip(selected = vm.theme == i, onClick = { vm.setThemeMode(i) }, label = { Text(label) },
                    leadingIcon = if (vm.theme == i) { { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) } } else null)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Jazyk / Language", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to L("Podle telefonu", "System"), "cs" to "Čeština", "en" to "English").forEach { (m, label) ->
                FilterChip(selected = Lang.mode == m, onClick = { Lang.set(ctx, m) }, label = { Text(label) },
                    leadingIcon = if (Lang.mode == m) { { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) } } else null)
            }
        }

        if (vm.isOpen) {
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            WalletsSection(vm, act)
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            var showContacts by remember { mutableStateOf(false) }
            GlassButton(onClick = { showContacts = true }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Person, null); Spacer(Modifier.width(6.dp)); Text(L("Adresář (${vm.contacts.size})", "Address book (${vm.contacts.size})"))
            }
            if (showContacts) ContactsDialog(vm, onDismiss = { showContacts = false })
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            LabelsSection(vm)
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            NotifySection(vm)
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            DustSection(vm)
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            Text(L("Bezpečnost", "Security"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            vm.keyLevel?.let { lvl ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Icon(Icons.Default.Shield, null, tint = if (lvl == "STRONGBOX") Color(0xFF2E9E5B) else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(when (lvl) {
                        "STRONGBOX" -> L("Klíč peněženky je ve StrongBoxu – samostatném bezpečnostním čipu telefonu.", "The wallet key is in StrongBox – the phone's separate security chip.")
                        "TEE" -> L("Klíč peněženky je v chráněné části procesoru (TEE). Telefon StrongBox nemá.", "The wallet key is in the processor's protected area (TEE). This phone has no StrongBox.")
                        else -> L("Klíč peněženky je chráněn jen softwarově.", "The wallet key is protected by software only.")
                    }, fontSize = 13.sp)
                }
            }
            if (!vm.watchOnly) GlassButton(onClick = { vm.screen = Screen.Seed }, Modifier.fillMaxWidth()) { Text(L("Zobrazit obnovovací frázi", "Show recovery phrase")) }
            GlassButton(onClick = { vm.screen = Screen.Export }, Modifier.fillMaxWidth()) { Text(L("Veřejný klíč pro sledování (export)", "Public key for watching (export)")) }
            GlassButton(onClick = { vm.lockApp() }, Modifier.fillMaxWidth()) { Text(L("Zamknout teď", "Lock now")) }
            Spacer(Modifier.height(20.dp))
            GlassButton(onClick = { confirmDelete = true }, Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(if (vm.wallets.size > 1) L("Smazat peněženku „${vm.walletName(vm.activeId)}“ z telefonu", "Delete wallet „${vm.walletName(vm.activeId)}“ from phone") else L("Smazat peněženku z telefonu", "Delete wallet from phone"))
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        GlassButton(onClick = { vm.screen = Screen.Intro(Screen.Settings) }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.School, null); Spacer(Modifier.width(6.dp)); Text(L("Průvodce: proč kostky, slova a vlastní uzel", "Guide: why dice, words and your own node"))
        }

        // Podpora vývoje – adresa pro dary
        if (!BuildConfig.PLAY) {
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            Text(L("Podpořit vývoj", "Support development"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                L("Hayek Wallet je zdarma a s otevřeným kódem. Pokud vám je užitečná, můžete přispět libovolnou částkou v bitcoinech.", "Hayek Wallet is free and open source. If you find it useful, you can contribute any amount in bitcoin."),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            val donateQr = remember { qrBitmap("bitcoin:$DONATION_ADDRESS?label=Hayek%20Wallet") }
            Image(donateQr.asImageBitmap(), L("QR kód adresy pro dary", "Donation address QR code"),
                Modifier.size(200.dp).align(Alignment.CenterHorizontally).background(Color.White).padding(6.dp))
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.text.selection.SelectionContainer(Modifier.align(Alignment.CenterHorizontally)) {
                Text(DONATION_ADDRESS.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton(onClick = { copy(ctx, DONATION_ADDRESS); vm.message = L("Adresa pro dary zkopírována. Děkujeme!", "Donation address copied. Thank you!") }, Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text(L("Kopírovat", "Copy"))
                }
                if (vm.isOpen && !vm.watchOnly) HayekButton(onClick = { vm.sendPrefill = DONATION_ADDRESS; vm.openSend() }, Modifier.weight(1f)) {
                    Icon(Icons.Default.Favorite, null); Spacer(Modifier.width(6.dp)); Text(L("Přispět", "Donate"))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (vm.wallets.size > 1) L("Smazat peněženku „${vm.walletName(vm.activeId)}“?", "Delete wallet „${vm.walletName(vm.activeId)}“?") else L("Smazat peněženku?", "Delete wallet?")) },
            text = { Text(
                (if (vm.watchOnly) L("Sledovací peněženka zmizí z telefonu. Klíče zůstávají na hardwarové peněžence (Coldcardu), sledování obnovíte novým načtením veřejného klíče.", "The watch-only wallet will disappear from the phone. The keys stay on the hardware wallet (Coldcard); you can watch it again by loading the public key.")
                else L("Peněženka zmizí z telefonu. Bitcoiny zůstanou na blockchainu, ale dostanete se k nim JEN pomocí zapsaných slov. Máte je opravdu zapsaná?", "The wallet will disappear from the phone. The bitcoin stays on the blockchain, but you can reach it ONLY with the written words. Do you really have them written down?")) +
                if (vm.wallets.size > 1) L("\n\nOstatní peněženky zůstanou beze změny.", "\n\nThe other wallets stay unchanged.") else ""
            ) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; act.auth(vm, L("Smazat peněženku", "Delete wallet"), strong = true) { vm.deleteWallet() } }) { Text(L("Smazat", "Delete")) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(L("Zrušit", "Cancel")) } }
        )
    }
}
