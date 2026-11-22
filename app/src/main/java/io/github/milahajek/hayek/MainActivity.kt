package io.github.milahajek.hayek

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Orange = Color(0xFFF7931A)

const val IDLE_LOCK_MS = 5 * 60_000L

class MainActivity : FragmentActivity() {
    private val vm: WalletViewModel by viewModels()
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Žádné snímky obrazovky a žádný náhled v přepínači aplikací.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            val dark = when (vm.theme) { 1 -> false; 2 -> true; else -> androidx.compose.foundation.isSystemInDarkTheme() }
            val scheme = if (dark)
                darkColorScheme(
                    primary = Orange, onPrimary = Color.Black,
                    background = Color(0xFF0D0F14), surface = Color(0xFF171A21),
                    surfaceVariant = Color(0xFF232733), surfaceContainerHigh = Color(0xFF1E222B),
                )
            else lightColorScheme(
                primary = Orange, onPrimary = Color.Black,
                background = Color(0xFFFCF8F3), surface = Color(0xFFFFFBF6),
                surfaceVariant = Color(0xFFF1E9DF), surfaceContainerHigh = Color(0xFFFFF8F0),
            )
            MaterialTheme(colorScheme = scheme) {
                HayekBackground { App(vm, this@MainActivity) }
            }
        }
    }

    // Zamčení při nečinnosti: když se 5 minut nikdo nedotkne obrazovky, peněženka se zamkne.
    private var lastTouch = SystemClock.elapsedRealtime()
    private val idleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val idleCheck = object : Runnable {
        override fun run() {
            if (vm.isOpen && !vm.busy && SystemClock.elapsedRealtime() - lastTouch > IDLE_LOCK_MS) vm.lockApp()
            idleHandler.postDelayed(this, 15_000)
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        lastTouch = SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        lastTouch = SystemClock.elapsedRealtime()
        idleHandler.post(idleCheck)
    }

    override fun onPause() {
        super.onPause()
        idleHandler.removeCallbacks(idleCheck)
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        // Po 2 minutách na pozadí se peněženka zamkne.
        if (stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > 120_000 && vm.isOpen) vm.lockApp()
    }
}

fun FragmentActivity.auth(vm: WalletViewModel, title: String, strong: Boolean = false, ok: () -> Unit) {
    if (!canAuthenticate(this)) {
        vm.message = "Nejdřív si v telefonu nastavte zámek obrazovky (PIN nebo otisk prstu). Bez něj peněženka nemůže bezpečně uložit klíče."
        return
    }
    authenticate(this, title, strong, ok) { err -> vm.message = "Ověření se nezdařilo: $err" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: WalletViewModel, act: FragmentActivity) {
    val ctx = LocalContext.current
    val screen = vm.screen
    val inWallet = listOf(Screen.Receive, Screen.Send, Screen.Seed, Screen.Settings, Screen.Coins, Screen.Export)
    val setup = screen == Screen.Dice || screen == Screen.Restore || screen == Screen.WatchImport || screen is Screen.ShowSeed || screen is Screen.Verify

    fun back() {
        when {
            screen == Screen.Coins -> vm.screen = vm.coinsReturn
            screen == Screen.Export || screen == Screen.Seed -> { vm.revealSeed = false; vm.screen = Screen.Settings }
            screen in inWallet -> { vm.revealSeed = false; vm.screen = if (vm.isOpen) Screen.Home else if (vm.store.hasSeed()) Screen.Locked else Screen.Welcome }
            screen is Screen.Verify -> vm.screen = Screen.ShowSeed(screen.words)
            setup -> vm.screen = Screen.Welcome
        }
    }
    if (screen in inWallet || setup) BackHandler { back() }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    Column {
                        Text(buildAnnotatedString {
                            withStyle(SpanStyle(color = Orange, fontWeight = FontWeight.ExtraBold)) { append("Hayek") }
                            withStyle(SpanStyle(fontWeight = FontWeight.Light)) { append(" Wallet") }
                        })
                        val sub = when (vm.connected) {
                            true -> "● připojeno k vašemu uzlu"
                            false -> "● uzel nedostupný"
                            null -> "Bitcoin mainnet · skutečné peníze"
                        } + if (vm.watchOnly) " · jen sledování" else ""
                        Text(sub, fontSize = 12.sp, color = when (vm.connected) {
                            true -> Color(0xFF2E9E4F); false -> MaterialTheme.colorScheme.error; null -> Orange
                        })
                    }
                },
                navigationIcon = {
                    if (screen in inWallet || setup) IconButton(onClick = { back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zpět")
                    }
                },
                actions = {
                    if (screen == Screen.Home) {
                        IconButton(onClick = { vm.sync() }, enabled = !vm.busy) { Icon(Icons.Default.Refresh, "Obnovit") }
                        IconButton(onClick = { vm.screen = Screen.Settings }) { Icon(Icons.Default.Settings, "Nastavení") }
                    }
                    if (screen == Screen.Welcome) {
                        IconButton(onClick = { vm.screen = Screen.Settings }) { Icon(Icons.Default.Settings, "Nastavení") }
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (screen) {
                Screen.Locked -> LockedScreen(vm, act)
                Screen.Welcome -> WelcomeScreen(vm)
                Screen.Dice -> DiceScreen(vm)
                is Screen.ShowSeed -> ShowSeedScreen(screen.words, vm)
                is Screen.Verify -> IncognitoKeyboard { VerifyScreen(screen, vm, act) }
                Screen.Restore -> IncognitoKeyboard { RestoreScreen(vm, act) }
                Screen.WatchImport -> IncognitoKeyboard { WatchImportScreen(vm, act) }
                Screen.Coins -> CoinsScreen(vm)
                Screen.Export -> ExportScreen(vm)
                Screen.Home -> HomeScreen(vm)
                Screen.Receive -> ReceiveScreen(vm)
                Screen.Send -> SendScreen(vm, act)
                Screen.Settings -> SettingsScreen(vm, act)
                Screen.Seed -> SeedScreen(vm, act)
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }

    vm.message?.let { msg ->
        val txid = msg.substringAfter("TXID: ", "").trim()
        AlertDialog(
            onDismissRequest = { vm.message = null },
            confirmButton = { TextButton(onClick = { vm.message = null }) { Text("OK") } },
            dismissButton = if (txid.isNotEmpty()) {
                { TextButton(onClick = { openTx(ctx, vm, vm.txLink(txid)) }) { Text("Zobrazit v Mempoolu") } }
            } else null,
            text = { Text(msg) }
        )
    }
}

// ---------------- Zámek ----------------

@Composable
fun LockedScreen(vm: WalletViewModel, act: FragmentActivity) {
    LaunchedEffect(Unit) { act.auth(vm, "Odemknout peněženku") { vm.unlocked() } }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(painterResource(R.drawable.ic_logo), null, Modifier.size(96.dp))
        Spacer(Modifier.height(16.dp))
        Text("Peněženka je zamčená", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        HayekButton(onClick = { act.auth(vm, "Odemknout peněženku") { vm.unlocked() } }, Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Odemknout")
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
            withStyle(SpanStyle(color = Orange, fontWeight = FontWeight.ExtraBold)) { append("Hayek") }
            withStyle(SpanStyle(fontWeight = FontWeight.Light)) { append(" Wallet") }
        }, fontSize = 30.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Pracuje se skutečnými bitcoiny a připojuje se jen k vašemu vlastnímu uzlu. Používejte ji jen na malé částky.",
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        HayekButton(onClick = { vm.startDice() }, Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Default.Casino, null); Spacer(Modifier.width(8.dp)); Text("Nová peněženka z hodů kostkou")
        }
        Spacer(Modifier.height(12.dp))
        GlassButton(onClick = { vm.screen = Screen.Restore }, Modifier.fillMaxWidth().height(52.dp)) {
            Text("Obnovit z 24 (12) slov")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { vm.screen = Screen.WatchImport }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Visibility, null); Spacer(Modifier.width(6.dp)); Text("Jen sledovat peněženku (zpub / deskriptor)")
        }
        Spacer(Modifier.height(20.dp))
        Text("Server: ${vm.serverUrl.ifBlank { "zatím nenastaven (zadáte v ⚙ Nastavení)" }}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text("Házení kostkou", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Házejte skutečnou šestistěnnou kostkou (nejlépe víc kostek najednou, na rovné ploše) a po každém hodu klepněte na číslo, které padlo. Nic si nevymýšlejte – lidé nejsou dobrý zdroj náhody.",
            fontSize = 14.sp
        )
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { (count / MIN_ROLLS.toFloat()).coerceAtMost(1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp)
        )
        Text(
            if (done) "$count hodů – hotovo (můžete přidat další)" else "$count / $MIN_ROLLS hodů",
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
                Icon(Icons.Default.Backspace, null); Spacer(Modifier.width(6.dp)); Text("Smazat poslední")
            }
            HayekButton(onClick = { vm.finishDice() }, enabled = done, modifier = Modifier.weight(1f)) { Text("Vytvořit slova") }
        }
        Spacer(Modifier.height(12.dp))
        if (count > 0) {
            Text("Zadané hody (pro kontrolu):", fontSize = 13.sp)
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Vaše obnovovací fráze", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Opište si 24 slov ve správném pořadí na papír (ne do mobilu, ne fotit). Kdo tato slova zná, může vzít všechny bitcoiny. Když ztratíte telefon, obnovíte jimi peněženku – i v jiné aplikaci (standard BIP39/BIP84).",
        )
        Spacer(Modifier.height(16.dp))
        SeedGrid(words)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().clickable { saved = !saved }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = saved, onCheckedChange = { saved = it })
            Text("Všech 24 slov mám zapsaných na papíře")
        }
        HayekButton(onClick = { vm.goVerify(words) }, enabled = saved, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Pokračovat ke kontrole")
        }
    }
}

@Composable
fun VerifyScreen(s: Screen.Verify, vm: WalletViewModel, act: FragmentActivity) {
    val list = s.words.split(" ")
    val answers = remember(s) { mutableStateListOf("", "", "") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Kontrola zálohy", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Podle svého papíru napište tato slova:")
        Spacer(Modifier.height(12.dp))
        s.positions.forEachIndexed { i, p ->
            OutlinedTextField(
                value = answers[i], onValueChange = { answers[i] = it.trim().lowercase() },
                label = { Text("Slovo č. ${p + 1}") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
            )
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        HayekButton(onClick = {
            val ok = s.positions.indices.all { answers[it] == list[s.positions[it]] }
            if (!ok) vm.message = "Některé slovo nesedí. Zkontrolujte svůj zápis (vraťte se zpět a porovnejte)."
            else act.auth(vm, "Uložit peněženku") { vm.saveAndOpen(s.words) }
        }, enabled = answers.all { it.isNotBlank() } && !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Zkontrolovat a uložit")
        }
    }
}

@Composable
fun RestoreScreen(vm: WalletViewModel, act: FragmentActivity) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Obnovit peněženku", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Napište 24 (nebo 12) slov oddělených mezerou.")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it }, label = { Text("Obnovovací fráze") },
            modifier = Modifier.fillMaxWidth().height(180.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
        )
        Spacer(Modifier.height(16.dp))
        HayekButton(onClick = {
            vm.checkRestore(text)?.let { w -> act.auth(vm, "Uložit peněženku") { vm.saveAndOpen(w) } }
        }, enabled = text.isNotBlank() && !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Obnovit") }
    }
}

// ---------------- Peněženka ----------------

fun formatSat(sat: Long): String = String.format(Locale.forLanguageTag("cs"), "%,d sat", sat)
fun formatBtc(sat: Long): String = String.format(Locale.US, "%.8f BTC", sat / 100_000_000.0)

@Composable
fun HomeScreen(vm: WalletViewModel) {
    val ctx = LocalContext.current
    val st = vm.state
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        if (vm.serverUrl.isBlank()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Nastavte svůj uzel", fontWeight = FontWeight.Bold)
                    Text("Peněženka se připojuje jen k vašemu vlastnímu Electrum serveru. Zadejte jeho adresu v Nastavení.", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    HayekButton(onClick = { vm.screen = Screen.Settings }, Modifier.fillMaxWidth()) { Text("Otevřít Nastavení") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Zůstatek", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatSat(st?.totalSat ?: 0), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(formatBtc(st?.totalSat ?: 0), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if ((st?.pendingSat ?: 0) > 0) {
                    Text("z toho čeká na potvrzení: ${formatSat(st!!.pendingSat)}", color = Orange, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HayekButton(onClick = { vm.screen = Screen.Receive }, Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Default.CallReceived, null); Spacer(Modifier.width(6.dp)); Text("Přijmout")
            }
            if (!vm.watchOnly) HayekButton(onClick = { vm.openSend() }, Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Default.CallMade, null); Spacer(Modifier.width(6.dp)); Text("Odeslat")
            }
        }
        if (vm.watchOnly) Text(
            "Jen sledování: vidíte zůstatek a vytváříte adresy, ale odesílat nelze (telefon nemá soukromé klíče).",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
        )
        GlassButton(onClick = { vm.openCoins(Screen.Home) }, Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Icon(Icons.Default.Toll, null); Spacer(Modifier.width(6.dp))
            Text("Mince (UTXO): ${vm.coins.size}" + if (vm.coins.any { it.frozen }) " · zmrazené: ${vm.coins.count { it.frozen }}" else "")
        }
        Text("Transakce", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        val txs = st?.txs.orEmpty()
        if (txs.isEmpty()) Text(if (vm.busy) "Načítám z vašeho uzlu…" else "Zatím žádné transakce.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize()) {
            items(txs, key = { it.txid }) { tx -> TxRow(tx) { openTx(ctx, vm, vm.txLink(tx.txid)) } }
        }
    }
}

@Composable
fun TxRow(tx: TxItem, onClick: () -> Unit) {
    val incoming = tx.deltaSat >= 0
    val date = tx.time?.let { SimpleDateFormat("d. M. yyyy HH:mm", Locale.forLanguageTag("cs")).format(Date(it * 1000)) }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(if (incoming) Icons.Default.CallReceived else Icons.Default.CallMade, null,
                tint = if (incoming) Color(0xFF2E9E4F) else Orange)
        },
        headlineContent = { Text((if (incoming) "+" else "") + formatSat(tx.deltaSat), fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(if (tx.confirmed) "Potvrzeno${date?.let { " · $it" } ?: ""}" else "Čeká na potvrzení", fontSize = 13.sp) },
        trailingContent = { Text(tx.txid.take(8) + "…", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    )
    HorizontalDivider()
}

fun qrBitmap(text: String, size: Int = 640): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp
}

@Composable
fun ReceiveScreen(vm: WalletViewModel) {
    val ctx = LocalContext.current
    val addr = vm.state?.address ?: ""
    val bmp = remember(addr) { if (addr.isNotEmpty()) qrBitmap("bitcoin:$addr") else null }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Přijmout bitcoiny", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        bmp?.let { Image(it.asImageBitmap(), "QR kód adresy", Modifier.size(260.dp).background(Color.White).padding(8.dp)) }
        Spacer(Modifier.height(16.dp))
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(addr.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 16.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(16.dp))
        HayekButton(onClick = { copy(ctx, addr); vm.message = "Adresa zkopírována." }, Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Kopírovat adresu")
        }
        GlassButton(onClick = {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, addr), "Sdílet adresu"))
        }, Modifier.fillMaxWidth()) { Text("Sdílet") }
        TextButton(onClick = { vm.newAddress() }, enabled = !vm.busy) { Text("Vygenerovat novou adresu") }
        Text("Pro každou platbu je lepší nová adresa (soukromí). Staré adresy ale fungují dál.",
            fontSize = 13.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SendScreen(vm: WalletViewModel, act: FragmentActivity) {
    val ctx = LocalContext.current
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var all by remember { mutableStateOf(false) }
    val p = vm.prepared
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Odeslat", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("K dispozici: ${formatSat(vm.coins.filter { !it.frozen }.sumOf { it.valueSat })}" +
            if (vm.coins.any { it.frozen }) " (bez zmrazených)" else "", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = to, onValueChange = { to = it.trim().removePrefix("bitcoin:").substringBefore("?"); vm.prepared = null },
            label = { Text("Adresa příjemce (bc1… / 1… / 3…)") }, modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { paste(ctx)?.let { to = it.trim().removePrefix("bitcoin:").substringBefore("?"); vm.prepared = null } }) {
                    Icon(Icons.Default.ContentPaste, "Vložit")
                }
            }
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = amount, onValueChange = { amount = it.filter(Char::isDigit); vm.prepared = null },
            label = { Text("Částka v satoshi") }, enabled = !all, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text(if (amount.isNotEmpty()) formatBtc(amount.toLongOrNull() ?: 0) else "1 BTC = 100 000 000 sat") }
        )
        Row(Modifier.clickable { all = !all; vm.prepared = null }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = all, onCheckedChange = { all = it; vm.prepared = null }); Text("Poslat vše")
        }
        val sel = vm.coins.filter { it.id in vm.selected }
        GlassCard(Modifier.fillMaxWidth().clickable { vm.openCoins(Screen.Send) }) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Toll, null, tint = Orange)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Mince k utracení (coin control)", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (sel.isEmpty()) "Automaticky (zmrazené se nepoužijí)"
                        else "Ručně: ${sel.size} ${if (sel.size == 1) "mince" else "mincí"} · ${formatSat(sel.sumOf { it.valueSat })}",
                        fontSize = 13.sp
                    )
                }
                Text("Vybrat", color = Orange)
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = vm.feeRate, onValueChange = { vm.feeRate = it.filter(Char::isDigit); vm.prepared = null },
            label = { Text("Poplatek (sat/vB)") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text("Předvyplněno podle vašeho uzlu (potvrzení do ~1 hodiny)") }
        )
        Spacer(Modifier.height(16.dp))
        if (p == null) {
            HayekButton(onClick = { vm.prepare(to, amount, all) },
                enabled = to.isNotBlank() && (all || amount.isNotBlank()) && !vm.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Pokračovat") }
        } else {
            GlassCard(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Zkontrolujte platbu – nejde vrátit!", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Komu:"); Text(p.to.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Částka: ${formatSat(p.amountSat)}")
                    Text("Poplatek: ${formatSat(p.feeSat)} (${p.feeRate} sat/vB)")
                    Text("Použité mince: ${p.inputs}")
                    Text("Celkem: ${formatSat(p.amountSat + p.feeSat)}", fontWeight = FontWeight.Bold)
                    if (p.feeSat > p.amountSat / 10) Text("⚠ Poplatek je přes 10 % částky.", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(12.dp))
            HayekButton(onClick = { act.auth(vm, "Potvrdit platbu ${formatSat(p.amountSat)}", strong = true) { vm.broadcast() } },
                enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(6.dp)); Text("Podepsat a odeslat")
            }
            TextButton(onClick = { vm.prepared = null }, Modifier.fillMaxWidth()) { Text("Zrušit") }
        }
    }
}

@Composable
fun SettingsScreen(vm: WalletViewModel, act: FragmentActivity) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(vm.serverUrl) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Váš uzel (Electrum server)", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Na RaspiBlitzu: Fulcrum ssl://IP:50022 (tcp 50021), ElectRS ssl://IP:50002 (tcp 50001).", fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = url, onValueChange = { url = it.trim() }, label = { Text("Adresa serveru") },
            placeholder = { Text("ssl://IP-uzlu:50002") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton(onClick = { vm.testServer(url) }, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Vyzkoušet") }
            HayekButton(onClick = { vm.setServer(url); vm.message = "Uloženo." }, modifier = Modifier.weight(1f)) { Text("Uložit") }
        }
        Spacer(Modifier.height(8.dp))
        Text("Telefon musí být ve stejné síti jako uzel, nebo připojený domů přes VPN (např. WireGuard). Aplikace se nikdy nepřipojí k cizímu serveru.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Certifikát uzlu (připnutí)
        @Suppress("UNUSED_VARIABLE") val pv = vm.pinVersion
        val fp = vm.pinnedFingerprint(url)
        var confirmForget by remember { mutableStateOf(false) }
        Spacer(Modifier.height(10.dp))
        when {
            url.startsWith("tcp://") -> Text(
                "⚠ tcp:// je nešifrované spojení – kdokoli ve stejné síti vidí, na které adresy se peněženka ptá. Doporučujeme ssl://.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.error
            )
            fp != null -> GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VerifiedUser, null, tint = Color(0xFF2E9E4F))
                        Spacer(Modifier.width(8.dp))
                        Text("Certifikát uzlu je připnutý", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Peněženka se připojí jen k serveru s tímto certifikátem. Kdyby se někdo vydával za váš uzel, spojení odmítne.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("SHA-256: " + fp.chunked(24).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    GlassButton(onClick = { confirmForget = true }, Modifier.fillMaxWidth()) { Text("Zapomenout certifikát") }
                }
            }
            url.startsWith("ssl://") -> Text(
                "Certifikát uzlu se uloží (připne) při prvním spojení. Pak už peněženka jiný nepřijme.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (confirmForget) AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Zapomenout certifikát?") },
            text = { Text("Udělejte to jen tehdy, když jste certifikát na uzlu sami změnili (reinstalace, aktualizace). Při příštím spojení se uloží nový certifikát – ať je jakýkoli.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; vm.forgetPin(url) }) { Text("Zapomenout") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Zrušit") } }
        )

        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        Text("Prohlížeč bloků (váš Mempool)", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Klepnutí na transakci ji otevře v Mempoolu na vašem RaspiBlitzu – nikam jinam se nic neposílá. Prázdné pole = odkazy vypnuté.", fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        var exp by remember { mutableStateOf(vm.explorerUrl) }
        OutlinedTextField(value = exp, onValueChange = { exp = it.trim() }, label = { Text("Adresa Mempoolu") },
            placeholder = { Text("http://IP-uzlu:4080") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton(onClick = { if (exp.isNotBlank()) openUrl(ctx, exp) }, enabled = exp.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Otevřít") }
            HayekButton(onClick = { vm.setExplorer(exp); vm.message = if (exp.isBlank()) "Odkazy na transakce vypnuty." else "Uloženo." }, modifier = Modifier.weight(1f)) { Text("Uložit") }
        }
        Text("RaspiBlitz: http://IP:4080, nebo https://IP:4081 (prohlížeč upozorní na vlastní certifikát).",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        Text("Vzhled", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Podle telefonu", "Světlý", "Tmavý").forEachIndexed { i, label ->
                FilterChip(selected = vm.theme == i, onClick = { vm.setThemeMode(i) }, label = { Text(label) },
                    leadingIcon = if (vm.theme == i) { { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) } } else null)
            }
        }

        if (vm.isOpen) {
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            Text("Bezpečnost", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (!vm.watchOnly) GlassButton(onClick = { vm.screen = Screen.Seed }, Modifier.fillMaxWidth()) { Text("Zobrazit obnovovací frázi") }
            GlassButton(onClick = { vm.screen = Screen.Export }, Modifier.fillMaxWidth()) { Text("Veřejný klíč pro sledování (export)") }
            GlassButton(onClick = { vm.lockApp() }, Modifier.fillMaxWidth()) { Text("Zamknout teď") }
            Spacer(Modifier.height(20.dp))
            GlassButton(onClick = { confirmDelete = true }, Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Smazat peněženku z telefonu") }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Smazat peněženku?") },
            text = { Text("Peněženka zmizí z telefonu. Bitcoiny zůstanou na blockchainu, ale dostanete se k nim JEN pomocí zapsaných slov. Máte je opravdu zapsaná?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; act.auth(vm, "Smazat peněženku", strong = true) { vm.deleteWallet() } }) { Text("Smazat") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Zrušit") } }
        )
    }
}

@Composable
fun SeedScreen(vm: WalletViewModel, act: FragmentActivity) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Obnovovací fráze", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Nikomu ji neukazujte. Nikdo (ani podpora) ji po vás nikdy nepotřebuje.")
        Spacer(Modifier.height(12.dp))
        if (vm.revealSeed) SeedGrid(vm.seedWords ?: "")
        else HayekButton(onClick = { act.auth(vm, "Zobrazit obnovovací frázi", strong = true) { vm.revealSeed = true } }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(6.dp)); Text("Zobrazit slova")
        }
    }
}

fun openTx(ctx: Context, vm: WalletViewModel, link: String?) {
    if (link == null) vm.message = "Odkazy na transakce jsou vypnuté. Adresu Mempoolu nastavíte v ⚙ Nastavení."
    else openUrl(ctx, link)
}

fun openUrl(ctx: Context, url: String) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
}

fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("adresa", text))
}

fun paste(ctx: Context): String? {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
}


// ---------------- Sledování, mince, export ----------------

@Composable
fun WatchImportScreen(vm: WalletViewModel, act: FragmentActivity) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Jen sledovat peněženku", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Vložte veřejný klíč (zpub, ypub, xpub) nebo deskriptor z jiné peněženky – třeba z hardwarové peněženky nebo ze Sparrow. " +
                "Uvidíte zůstatek, transakce a mince a můžete vytvářet přijímací adresy. Utrácet nejde, v telefonu nebudou žádné soukromé klíče.",
            fontSize = 14.sp
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it }, label = { Text("zpub… nebo wpkh([…]xpub…/0/*)") },
            modifier = Modifier.fillMaxWidth().height(170.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            trailingIcon = { IconButton(onClick = { paste(ctx)?.let { text = it.trim() } }) { Icon(Icons.Default.ContentPaste, "Vložit") } }
        )
        Spacer(Modifier.height(16.dp))
        HayekButton(onClick = {
            vm.checkWatch(text)?.let { secret -> act.auth(vm, "Uložit sledovací peněženku") { vm.saveAndOpen(secret) } }
        }, enabled = text.isNotBlank() && !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Sledovat") }
    }
}

@Composable
fun CoinsScreen(vm: WalletViewModel) {
    val ctx = LocalContext.current
    val sel = vm.coins.filter { it.id in vm.selected }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Mince (UTXO)", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                if (vm.watchOnly) "Přehled nevyužitých výstupů. ❄ = zmrazená mince."
                else "Zaškrtněte mince, které se mají v příští platbě utratit. ❄ zmrazí minci – nikdy se nepoužije, dokud ji nerozmrazíte.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (vm.coins.isEmpty()) Text("Žádné mince.", Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(vm.coins, key = { it.id }) { c ->
                ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(enabled = !vm.watchOnly && !c.frozen) { vm.toggleSelect(c.id) },
                    leadingContent = {
                        if (!vm.watchOnly) Checkbox(checked = c.id in vm.selected, enabled = !c.frozen, onCheckedChange = { vm.toggleSelect(c.id) })
                    },
                    headlineContent = {
                        Text(formatSat(c.valueSat), fontWeight = FontWeight.SemiBold,
                            color = if (c.frozen) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    },
                    supportingContent = {
                        Column {
                            Text(c.address.take(14) + "…" + c.address.takeLast(6), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text(
                                (if (c.confirmed) "Potvrzeno v bloku ${c.height}" else "Čeká na potvrzení") + if (c.frozen) " · ZMRAZENO" else "",
                                fontSize = 12.sp, color = if (c.frozen) Color(0xFF3B8ED6) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { openTx(ctx, vm, vm.txLink(c.txid, c.vout)) }) {
                                Icon(Icons.Default.OpenInNew, "Detail", Modifier.size(20.dp))
                            }
                            IconButton(onClick = { vm.setFrozen(c, !c.frozen) }, enabled = !vm.busy) {
                                Icon(Icons.Default.AcUnit, if (c.frozen) "Rozmrazit" else "Zmrazit",
                                    tint = if (c.frozen) Color(0xFF3B8ED6) else MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                )
                HorizontalDivider()
            }
        }
        if (!vm.watchOnly) GlassCard(Modifier.padding(10.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    if (sel.isEmpty()) "Nic nevybráno – platba vybere mince automaticky."
                    else "Vybráno ${sel.size} · ${formatSat(sel.sumOf { it.valueSat })}",
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassButton(onClick = { vm.selected = emptySet(); vm.prepared = null }, enabled = sel.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Zrušit výběr") }
                    HayekButton(onClick = { vm.prepared = null; vm.screen = Screen.Send }, modifier = Modifier.weight(1f)) { Text("K platbě") }
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
        Text("Veřejný klíč pro sledování", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Tento deskriptor vložte do jiné aplikace (Sparrow, tato aplikace v režimu sledování…) a uvidíte tam zůstatek bez možnosti utrácet. " +
                "Bitcoiny s ním nikdo neukradne, ale kdo ho má, vidí všechny vaše adresy a transakce – sdílejte ho jen se svými zařízeními.",
            fontSize = 13.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        bmp?.let { Image(it.asImageBitmap(), "QR deskriptoru", Modifier.size(280.dp).background(Color.White).padding(8.dp)) }
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(desc, fontFamily = FontFamily.Monospace, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(12.dp))
        HayekButton(onClick = { copy(ctx, desc); vm.message = "Deskriptor zkopírován." }, Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Kopírovat")
        }
    }
}
