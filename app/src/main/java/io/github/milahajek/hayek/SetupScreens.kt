package io.github.milahajek.hayek

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity

// ---------------- Zámek ----------------

@Composable
fun LockedScreen(vm: WalletViewModel, act: FragmentActivity) {
    LaunchedEffect(Unit) { act.auth(vm, L("Odemknout peněženku", "Unlock wallet")) { vm.unlocked() } }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(painterResource(R.drawable.ic_logo), null, Modifier.size(96.dp))
        Spacer(Modifier.height(16.dp))
        Text(L("Peněženka je zamčená", "The wallet is locked"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        HayekButton(onClick = { act.auth(vm, L("Odemknout peněženku", "Unlock wallet")) { vm.unlocked() } }, Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text(L("Odemknout", "Unlock"))
        }
    }
}

// ---------------- Založení ----------------

@Composable
fun WelcomeScreen(vm: WalletViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(painterResource(R.drawable.ic_logo), null, Modifier.size(112.dp))
        Spacer(Modifier.height(12.dp))
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = HayekOrange, fontWeight = FontWeight.ExtraBold)) { append("Hayek") }
            withStyle(SpanStyle(fontWeight = FontWeight.Light)) { append(" Wallet") }
        }, fontSize = 30.sp)
        Spacer(Modifier.height(8.dp))
        if (vm.adding) {
            Text(L("Přidat další peněženku", "Add another wallet"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                L("Kapesní peněženka: nová z kostek nebo obnova ze slov – klíče budou v telefonu.\nChlaďák (úspory na Coldcardu): „Jen sledovat“ a načtěte veřejný klíč z Coldcardu přes NFC – klíče zůstanou na Coldcardu, telefon jen připravuje platby k podpisu.",
                    "Pocket wallet: new from dice or restore from words – the keys will be on the phone.\nCold storage (savings on a Coldcard): „Watch-only“ and load the public key from your Coldcard via NFC – the keys stay on the Coldcard, the phone only prepares payments for signing."),
                textAlign = TextAlign.Center, fontSize = 14.sp
            )
        } else Text(
            L("Pracuje se skutečnými bitcoiny. Připojuje se jen tam, kam ji sami pošlete – k vašemu uzlu, nebo k veřejnému serveru, který si vyberete. Používejte ji jen na malé částky.", "It works with real bitcoin. It connects only where you send it – to your own node, or to a public server you choose. Use it only for small amounts."),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        HayekButton(onClick = { vm.startDice() }, Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Default.Casino, null); Spacer(Modifier.width(8.dp)); Text(L("Nová peněženka z hodů kostkou", "New wallet from dice rolls"))
        }
        Spacer(Modifier.height(12.dp))
        GlassButton(onClick = { vm.screen = Screen.Restore }, Modifier.fillMaxWidth().height(52.dp)) {
            Text(L("Obnovit z 24 (12) slov", "Restore from 24 (12) words"))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { vm.screen = Screen.WatchImport }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Visibility, null); Spacer(Modifier.width(6.dp)); Text(L("Jen sledovat peněženku (zpub / deskriptor)", "Watch-only wallet (zpub / descriptor)"))
        }
        if (vm.adding) TextButton(onClick = { vm.cancelAddWallet() }, Modifier.fillMaxWidth()) { Text(L("Zpět do peněženky", "Back to wallet")) }
        else TextButton(onClick = { vm.screen = Screen.Intro(Screen.Welcome) }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.School, null); Spacer(Modifier.width(6.dp)); Text(L("Průvodce – proč kostky a jak na to", "Guide – why dice and how it works"))
        }
        Spacer(Modifier.height(12.dp))
        if (!vm.adding) {
            if (vm.serverUrl.isBlank()) TextButton(onClick = { vm.screen = Screen.ServerSetup }) { Text(L("Server zatím není nastaven – nastavit", "No server set yet – set it up")) }
            else Text(L("Server: ${vm.serverUrl}", "Server: ${vm.serverUrl}") + if (isPublicServer(vm.serverUrl)) L(" (veřejný)", " (public)") else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DiceFace(n: Int, onClick: () -> Unit, modifier: Modifier) {
    val dots = when (n) {
        1 -> listOf(4); 2 -> listOf(0, 8); 3 -> listOf(0, 4, 8)
        4 -> listOf(0, 2, 6, 8); 5 -> listOf(0, 2, 4, 6, 8); else -> listOf(0, 2, 3, 5, 6, 8)
    }
    Box(
        modifier.aspectRatio(1f)
            .shadow(8.dp, RoundedCornerShape(18.dp), ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Color.White, Color(0xFFF1F1F1), Color(0xFFD9D9D9))))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White, Color.Black.copy(alpha = 0.15f))), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(14.dp)
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            for (r in 0..2) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (c in 0..2) Box(
                    Modifier.size(14.dp).background(
                        if (r * 3 + c in dots) Brush.radialGradient(listOf(Color(0xFF555555), Color(0xFF111111)))
                        else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)), CircleShape)
                )
            }
        }
    }
}

@Composable
fun DiceScreen(vm: WalletViewModel) {
    val count = vm.rolls.length
    val done = count >= MIN_ROLLS
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(L("Házení kostkou", "Dice rolls"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            L("Házejte skutečnou šestistěnnou kostkou (nejlépe víc kostek najednou, na rovné ploše) a po každém hodu klepněte na číslo, které padlo. Nic si nevymýšlejte – lidé nejsou dobrý zdroj náhody.", "Roll a real six-sided die (ideally several at once, on a flat surface) and after each roll tap the number that came up. Don't make anything up – humans are a poor source of randomness."),
            fontSize = 14.sp
        )
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { (count / MIN_ROLLS.toFloat()).coerceAtMost(1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp)
        )
        Text(
            if (done) L("$count hodů – hotovo (můžete přidat další)", "$count rolls – done (you can add more)") else L("$count / $MIN_ROLLS hodů", "$count / $MIN_ROLLS rolls"),
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp)
        )
        for (row in listOf(listOf(1, 2, 3), listOf(4, 5, 6))) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { n -> DiceFace(n, { vm.addRoll(n) }, Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton(onClick = { vm.undoRoll() }, enabled = count > 0, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.Backspace, null); Spacer(Modifier.width(6.dp)); Text(L("Smazat poslední", "Delete last"))
            }
            HayekButton(onClick = { vm.finishDice() }, enabled = done, modifier = Modifier.weight(1f)) { Text(L("Vytvořit slova", "Create words")) }
        }
        Spacer(Modifier.height(12.dp))
        if (count > 0) {
            Text(L("Zadané hody (pro kontrolu):", "Rolls entered (for checking):"), fontSize = 13.sp)
            Text(
                vm.rolls.chunked(5).joinToString(" "),
                fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp)
            )
        }
    }
}

@Composable
fun SeedGrid(words: String) {
    val list = words.split(" ")
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(10.dp)) {
        list.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEachIndexed { col, w ->
                    Text("${row * 2 + col + 1}. $w", Modifier.weight(1f).padding(5.dp), fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
fun ShowSeedScreen(words: String, vm: WalletViewModel) {
    var saved by remember { mutableStateOf(false) }
    var pp by remember { mutableStateOf<String?>("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(L("Vaše obnovovací fráze", "Your recovery phrase"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            L("Opište si 24 slov ve správném pořadí na papír (ne do mobilu, ne fotit). Kdo tato slova zná, může vzít všechny bitcoiny. Když ztratíte telefon, obnovíte jimi peněženku – i v jiné aplikaci (standard BIP39/BIP84).", "Write the 24 words down on paper in the correct order (not into the phone, no photos). Whoever knows these words can take all the bitcoin. If you lose your phone, you'll restore the wallet with them – even in another app (BIP39/BIP84 standard)."),
        )
        Spacer(Modifier.height(16.dp))
        SeedGrid(words)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().clickable { saved = !saved }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = saved, onCheckedChange = { saved = it })
            Text(L("Všech 24 slov mám zapsaných na papíře", "I have written all 24 words on paper"))
        }
        Spacer(Modifier.height(8.dp))
        PassphraseSection(confirm = true) { pp = it }
        Spacer(Modifier.height(12.dp))
        HayekButton(onClick = { vm.newPassphrase = pp?.ifEmpty { null }; vm.goVerify(words) }, enabled = saved && pp != null, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(L("Pokračovat ke kontrole", "Continue to check"))
        }
    }
}

@Composable
fun VerifyScreen(s: Screen.Verify, vm: WalletViewModel, act: FragmentActivity) {
    val list = s.words.split(" ")
    var idx by remember(s) { mutableIntStateOf(0) }
    var input by remember(s) { mutableStateOf("") }
    val pass = vm.newPassphrase
    var passAgain by remember(s) { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    val done = idx >= list.size
    var wrong by remember(s) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // Slovo se potvrzuje mezerou nebo klávesou Další: teprve pak se porovná (nic se nepotvrdí předčasně ani „nepřeteče“ do dalšího pole).
    fun confirm() {
        if (idx >= list.size || input.isBlank()) return
        if (wordMatches(input, list[idx]) == true) { idx++; input = ""; wrong = false } else wrong = true
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(L("Kontrola zálohy", "Backup check"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(L("Opište ze svého papíru všech ${list.size} slov, jedno po druhém. Každé potvrďte mezerou. Stačí i první 4 písmena.",
            "Type all ${list.size} words from your paper, one by one. Confirm each with a space. The first 4 letters are enough."))
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(progress = { idx / list.size.toFloat() }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        if (!done) {
            Text(L("Slovo ${idx + 1} z ${list.size}", "Word ${idx + 1} of ${list.size}"), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { v ->
                        val ended = v.isNotEmpty() && v.last().isWhitespace()
                        input = v.trim().lowercase(); wrong = false
                        if (ended) confirm()
                    },
                    label = { Text(L("Slovo č. ${idx + 1}", "Word #${idx + 1}")) }, singleLine = true, isError = wrong,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onNext = { confirm() }, onDone = { confirm() })
                )
                Spacer(Modifier.width(8.dp))
                HayekButton(onClick = { confirm() }, enabled = input.isNotBlank()) { Text(L("Další", "Next")) }
            }
            LaunchedEffect(idx) { runCatching { focus.requestFocus() } }
            if (wrong) {
                Spacer(Modifier.height(6.dp))
                Text(L("Tohle nesedí se slovem č. ${idx + 1}. Porovnejte ho se svým papírem – chyba v záloze by se jinak ukázala až při obnově.",
                    "This doesn't match word #${idx + 1}. Compare it with your paper – a mistake in the backup would otherwise show up only when restoring."),
                    color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                TextButton(onClick = { vm.screen = Screen.ShowSeed(s.words) }) { Text(L("Zobrazit slova znovu", "Show the words again")) }
            }
        } else {
            Text(L("✔ Všech ${list.size} slov sedí.", "✔ All ${list.size} words match."), fontWeight = FontWeight.SemiBold, color = Color(0xFF2E9E5B))
            Spacer(Modifier.height(12.dp))
            if (pass != null) {
                Text(L("A heslo navíc (passphrase) podle svého papíru:", "And the passphrase from your paper:"), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = passAgain, onValueChange = { passAgain = it }, label = { Text(L("Heslo", "Passphrase")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton(onClick = { showPass = !showPass }) { Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } }
                )
                Spacer(Modifier.height(8.dp))
            }
            HayekButton(onClick = {
                if (pass != null && passAgain != pass) vm.message = L("Heslo nesedí s tím, které jste zadali. Zkontrolujte zápis včetně velkých písmen a mezer.", "The passphrase doesn't match the one you entered. Check your note including capitals and spaces.")
                else act.auth(vm, L("Uložit peněženku", "Save wallet")) { vm.saveAndOpen(Passphrase.pack(s.words, pass)) }
            }, enabled = (pass == null || passAgain.isNotEmpty()) && !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(L("Uložit peněženku", "Save wallet"))
            }
        }
    }
}

@Composable
fun RestoreScreen(vm: WalletViewModel, act: FragmentActivity) {
    var text by remember { mutableStateOf("") }
    var pp by remember { mutableStateOf<String?>("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(L("Obnovit peněženku", "Restore wallet"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(L("Napište 24 (nebo 12) slov oddělených mezerou.", "Type the 24 (or 12) words separated by spaces."))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it }, label = { Text(L("Obnovovací fráze", "Recovery phrase")) },
            modifier = Modifier.fillMaxWidth().height(180.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
        )
        Spacer(Modifier.height(12.dp))
        Text(L("Měli jste u peněženky heslo navíc? Zadejte ho přesně (velká/malá písmena, mezery). Kdo ho neměl, nechá vypnuté.",
            "Did the wallet have a passphrase? Enter it exactly (upper/lower case, spaces). If it didn't, leave this off."), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        PassphraseSection(confirm = false) { pp = it }
        Spacer(Modifier.height(16.dp))
        HayekButton(onClick = {
            vm.checkRestore(text)?.let { w -> act.auth(vm, L("Uložit peněženku", "Save wallet")) { vm.saveAndOpen(Passphrase.pack(w, pp), restored = true) } }
        }, enabled = text.isNotBlank() && pp != null && !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(L("Obnovit", "Restore")) }
    }
}


// ---------------- Sledování, mince, export ----------------

@Composable
fun WatchImportScreen(vm: WalletViewModel, act: FragmentActivity) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var nfcImport by remember { mutableStateOf(false) }
    if (nfcImport) ColdcardImportDialog(onDismiss = { nfcImport = false }) { text = it; nfcImport = false }
    if (scanning) QrScanDialog(L("Načíst veřejný klíč", "Scan public key"), onDismiss = { scanning = false }, onResult = { raw ->
        scanning = false
        if (raw.trim().lowercase().startsWith("ur:")) vm.message =
            L("Tohle je animovaný QR kód (formát UR), ten zatím neumím. V druhé peněžence zvolte export jako text / xpub, nebo klíč zkopírujte.", "This is an animated QR code (UR format), which isn't supported yet. In the other wallet choose export as text / xpub, or copy the key.")
        else text = raw.trim()
    })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(L("Jen sledovat peněženku", "Watch a wallet"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            L("Vložte veřejný klíč (zpub, ypub, xpub) nebo deskriptor z jiné peněženky – třeba z hardwarové peněženky nebo ze Sparrow. ", "Paste a public key (zpub, ypub, xpub) or a descriptor from another wallet – e.g. a hardware wallet or Sparrow. ") +
                L("Uvidíte zůstatek, transakce a mince a můžete vytvářet přijímací adresy. V telefonu nebudou žádné soukromé klíče – platby podepíšete Coldcardem.", "You'll see the balance, transactions and coins and can create receive addresses. The phone holds no private keys – you sign payments with the Coldcard."),
            fontSize = 14.sp
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it }, label = { Text(L("zpub… nebo wpkh([…]xpub…/0/*)", "zpub… or wpkh([…]xpub…/0/*)")) },
            modifier = Modifier.fillMaxWidth().height(170.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            trailingIcon = {
                Row {
                    IconButton(onClick = { scanning = true }) { Icon(Icons.Default.QrCodeScanner, L("Načíst QR kód", "Scan QR code")) }
                    IconButton(onClick = { paste(ctx)?.let { text = it.trim() } }) { Icon(Icons.Default.ContentPaste, L("Vložit", "Paste")) }
                }
            }
        )
        GlassButton(onClick = { nfcImport = true }, Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Icon(Icons.Default.Contactless, null); Spacer(Modifier.width(6.dp)); Text(L("Načíst z Coldcardu (NFC)", "Import from Coldcard (NFC)"))
        }
        Text(L("Pro podepisování Coldcardem použijte deskriptor z Coldcardu (obsahuje otisk [xxxxxxxx/84h/0h/0h]), ne samotný zpub.",
            "For signing with a Coldcard use the descriptor from the Coldcard (it includes the fingerprint [xxxxxxxx/84h/0h/0h]), not just the zpub."),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(16.dp))
        HayekButton(onClick = {
            vm.checkWatch(text)?.let { secret -> act.auth(vm, L("Uložit sledovací peněženku", "Save watch-only wallet")) { vm.saveAndOpen(secret) } }
        }, enabled = text.isNotBlank() && !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(L("Sledovat", "Watch")) }
    }
}
