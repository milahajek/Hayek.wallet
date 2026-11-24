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

    override fun onCreate(savedInstanceState: Bundle?) {
        Lang.init(this)
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
        // Čas uložený ve ViewModelu přežije i otočení telefonu / změnu tmavého režimu (nové Activity).
        if (!isChangingConfigurations) vm.backgroundSince = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        // Po 2 minutách na pozadí se peněženka zamkne.
        val since = vm.backgroundSince
        if (since != 0L && SystemClock.elapsedRealtime() - since > 120_000 && vm.isOpen) vm.lockApp()
        vm.backgroundSince = 0L
    }
}

fun FragmentActivity.auth(vm: WalletViewModel, title: String, strong: Boolean = false, ok: () -> Unit) {
    if (!canAuthenticate(this)) {
        vm.message = L("Nejdřív si v telefonu nastavte zámek obrazovky (PIN nebo otisk prstu). Bez něj peněženka nemůže bezpečně uložit klíče.", "First set a screen lock on your phone (PIN or fingerprint). Without it the wallet can't store the keys securely.")
        return
    }
    if (strong && hasStrongBiometric(this) && vm.store.fingerprintsChanged()) {
        // Někdo mohl přidat vlastní otisk (stačí k tomu znát PIN). Než otisku znovu uvěříme, ověříme slova.
        vm.fingerprintCheck = { authenticate(this, title, strong, ok) { err -> vm.message = L("Ověření se nezdařilo: $err", "Verification failed: $err") } }
        return
    }
    authenticate(this, title, strong, ok) { err -> vm.message = L("Ověření se nezdařilo: $err", "Verification failed: $err") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: WalletViewModel, act: FragmentActivity) {
    val ctx = LocalContext.current
    val screen = vm.screen
    val inWallet = listOf(Screen.Receive, Screen.Send, Screen.Seed, Screen.Settings, Screen.Coins, Screen.Export,
        Screen.Lightning, Screen.LnPay, Screen.LnReceive, Screen.LnSetup)
    val setup = screen == Screen.Dice || screen == Screen.Restore || screen == Screen.WatchImport || screen is Screen.ShowSeed || screen is Screen.Verify

    fun back() {
        when {
            screen is Screen.Intro -> vm.finishIntro(screen.back)
            screen == Screen.Coins -> vm.screen = vm.coinsReturn
            screen == Screen.Export || screen == Screen.Seed || screen == Screen.LnSetup -> { vm.revealSeed = false; vm.screen = Screen.Settings }
            screen == Screen.LnPay || screen == Screen.LnReceive -> vm.openLightning()
            screen in inWallet -> { vm.revealSeed = false; vm.screen = if (vm.isOpen) Screen.Home else if (vm.store.hasSeed()) Screen.Locked else Screen.Welcome }
            screen is Screen.Verify -> vm.screen = Screen.ShowSeed(screen.words)
            setup -> vm.screen = Screen.Welcome
        }
    }
    if (screen in inWallet || setup || screen is Screen.Intro) BackHandler { back() }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                title = {
                    // Klepnutí na název aplikace otevře průvodce „proč a jak“ (návrat na stejné místo).
                    Column(
                        Modifier.clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = screen !is Screen.Intro, onClickLabel = L("O aplikaci", "About the app")) { vm.screen = Screen.Intro(screen) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Logo a název v jedné rovině, stav připojení vycentrovaný pod nimi.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Kde je velké logo uprostřed obrazovky (průvodce, úvod, zámek), malé v liště nedáváme.
                            if (screen !is Screen.Intro && screen != Screen.Welcome && screen != Screen.Locked) {
                                Image(painterResource(R.drawable.ic_logo), "Logo Hayek Wallet", Modifier.size(24.dp))
                                Spacer(Modifier.width(7.dp))
                            }
                            Text(buildAnnotatedString {
                                withStyle(SpanStyle(color = Orange, fontWeight = FontWeight.ExtraBold)) { append("Hayek") }
                                withStyle(SpanStyle(fontWeight = FontWeight.Light)) { append(" Wallet") }
                            })
                        }
                        val sub = when (vm.connected) {
                            true -> L("● připojeno k vašemu uzlu", "● connected to your node")
                            false -> L("● uzel nedostupný", "● node unreachable")
                            null -> L("Bitcoin mainnet · skutečné peníze", "Bitcoin mainnet · real money")
                        } + if (vm.watchOnly) L(" · jen sledování", " · watch-only") else ""
                        Text(sub, fontSize = 12.sp, color = when (vm.connected) {
                            true -> Color(0xFF2E9E4F); false -> MaterialTheme.colorScheme.error; null -> Orange
                        })
                    }
                },
                navigationIcon = {
                    if (screen in inWallet || setup) IconButton(onClick = { back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, L("Zpět", "Back"))
                    }
                },
                actions = {
                    if (screen == Screen.Home) {
                        IconButton(onClick = { vm.sync() }, enabled = !vm.busy) { Icon(Icons.Default.Refresh, L("Obnovit", "Refresh")) }
                        IconButton(onClick = { vm.screen = Screen.Settings }) {
                            BadgedBox(badge = { if (vm.labelChanges > 0) Badge(containerColor = Orange) }) {
                                Icon(Icons.Default.Settings, if (vm.labelChanges > 0) L("Nastavení – štítky nejsou zálohované", "Settings – labels not backed up") else L("Nastavení", "Settings"))
                            }
                        }
                    }
                    if (screen == Screen.Welcome) {
                        IconButton(onClick = { vm.screen = Screen.Settings }) { Icon(Icons.Default.Settings, L("Nastavení", "Settings")) }
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            ScreenTransition(screen) { s ->
            when (s) {
                Screen.Locked -> LockedScreen(vm, act)
                Screen.Welcome -> WelcomeScreen(vm)
                is Screen.Intro -> IntroScreen(vm, s.back)
                Screen.Dice -> DiceScreen(vm)
                is Screen.ShowSeed -> ShowSeedScreen(s.words, vm)
                is Screen.Verify -> IncognitoKeyboard { VerifyScreen(s, vm, act) }
                Screen.Restore -> IncognitoKeyboard { RestoreScreen(vm, act) }
                Screen.WatchImport -> IncognitoKeyboard { WatchImportScreen(vm, act) }
                Screen.Coins -> CoinsScreen(vm)
                Screen.Export -> ExportScreen(vm)
                Screen.Home -> HomeScreen(vm, act)
                Screen.Receive -> ReceiveScreen(vm)
                Screen.Send -> SendScreen(vm, act)
                Screen.Settings -> SettingsScreen(vm, act)
                Screen.Lightning -> LightningScreen(vm)
                Screen.LnPay -> LnPayScreen(vm, act)
                Screen.LnReceive -> LnReceiveScreen(vm)
                Screen.LnSetup -> IncognitoKeyboard { LnSetupScreen(vm, act) }
                Screen.Seed -> SeedScreen(vm, act)
            }
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }

    vm.fingerprintCheck?.let { FingerprintCheckDialog(vm, act) }

    vm.success?.let { SuccessOverlay(it) { vm.success = null } }

    if (vm.success == null) vm.message?.let { msg ->
        val txid = msg.substringAfter("TXID: ", "").trim()
        AlertDialog(
            onDismissRequest = { vm.message = null },
            confirmButton = { TextButton(onClick = { vm.message = null }) { Text("OK") } },
            dismissButton = when {
                txid.isNotEmpty() -> { { TextButton(onClick = { openTx(ctx, vm, vm.txLink(txid)) }) { Text(L("Zobrazit v Mempoolu", "Show in Mempool")) } } }
                (msg.contains("Diagnostika") || msg.contains("Diagnostics")) -> { { TextButton(onClick = { copy(ctx, msg) }) { Text(L("Kopírovat", "Copy")) } } }
                else -> null
            },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                        Text(msg, fontSize = if (msg.contains("Diagnostika") || msg.contains("Diagnostics")) 13.sp else 14.sp)
                    }
                }
            }
        )
    }
}

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
            withStyle(SpanStyle(color = Orange, fontWeight = FontWeight.ExtraBold)) { append("Hayek") }
            withStyle(SpanStyle(fontWeight = FontWeight.Light)) { append(" Wallet") }
        }, fontSize = 30.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            L("Pracuje se skutečnými bitcoiny a připojuje se jen k vašemu vlastnímu uzlu. Používejte ji jen na malé částky.", "It works with real bitcoin and connects only to your own node. Use it only for small amounts."),
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
        TextButton(onClick = { vm.screen = Screen.Intro(Screen.Welcome) }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.School, null); Spacer(Modifier.width(6.dp)); Text(L("Průvodce – proč kostky a jak na to", "Guide – why dice and how it works"))
        }
        Spacer(Modifier.height(12.dp))
        Text(L("Server: ${vm.serverUrl.ifBlank { "zatím nenastaven (zadáte v ⚙ Nastavení)" }}", "Server: ${vm.serverUrl.ifBlank { "not set yet (enter it in ⚙ Settings)" }}"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Icon(Icons.Default.Backspace, null); Spacer(Modifier.width(6.dp)); Text(L("Smazat poslední", "Delete last"))
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
        HayekButton(onClick = { vm.goVerify(words) }, enabled = saved, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(L("Pokračovat ke kontrole", "Continue to check"))
        }
    }
}

@Composable
fun VerifyScreen(s: Screen.Verify, vm: WalletViewModel, act: FragmentActivity) {
    val list = s.words.split(" ")
    val answers = remember(s) { mutableStateListOf("", "", "") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(L("Kontrola zálohy", "Backup check"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(L("Podle svého papíru napište tato slova:", "Using your paper, type these words:"))
        Spacer(Modifier.height(12.dp))
        s.positions.forEachIndexed { i, p ->
            OutlinedTextField(
                value = answers[i], onValueChange = { answers[i] = it.trim().lowercase() },
                label = { Text(L("Slovo č. ${p + 1}", "Word #${p + 1}")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
            )
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        HayekButton(onClick = {
            val ok = s.positions.indices.all { answers[it] == list[s.positions[it]] }
            if (!ok) vm.message = L("Některé slovo nesedí. Zkontrolujte svůj zápis (vraťte se zpět a porovnejte).", "A word doesn't match. Check what you wrote (go back and compare).")
            else act.auth(vm, L("Uložit peněženku", "Save wallet")) { vm.saveAndOpen(s.words) }
        }, enabled = answers.all { it.isNotBlank() } && !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(L("Zkontrolovat a uložit", "Check and save"))
        }
    }
}

@Composable
fun RestoreScreen(vm: WalletViewModel, act: FragmentActivity) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(L("Obnovit peněženku", "Restore wallet"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(L("Napište 24 (nebo 12) slov oddělených mezerou.", "Type the 24 (or 12) words separated by spaces."))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it }, label = { Text(L("Obnovovací fráze", "Recovery phrase")) },
            modifier = Modifier.fillMaxWidth().height(180.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
        )
        Spacer(Modifier.height(16.dp))
        HayekButton(onClick = {
            vm.checkRestore(text)?.let { w -> act.auth(vm, L("Uložit peněženku", "Save wallet")) { vm.saveAndOpen(w, restored = true) } }
        }, enabled = text.isNotBlank() && !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(L("Obnovit", "Refresh")) }
    }
}

// ---------------- Peněženka ----------------

fun formatSat(sat: Long): String = String.format(if (Lang.english) Locale.US else Locale.forLanguageTag("cs"), "%,d sat", sat)
fun formatBtc(sat: Long): String = String.format(Locale.US, "%.8f BTC", sat / 100_000_000.0)

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
        GlassCard(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp).fillMaxWidth().clickable { vm.toggleHideBalance() }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(L("Zůstatek", "Balance"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(vm.amount(st?.totalSat ?: 0), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Text(if (vm.hideBalance) L("klepnutím zobrazíte", "tap to show") else formatBtc(st?.totalSat ?: 0), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if ((st?.pendingSat ?: 0) > 0 && !vm.hideBalance) {
                        Text(L("z toho čeká na potvrzení: ${formatSat(st!!.pendingSat)}", "of which unconfirmed: ${formatSat(st!!.pendingSat)}"), color = Orange, fontSize = 13.sp)
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
                Icon(Icons.Default.CallReceived, null); Spacer(Modifier.width(6.dp)); Text(L("Přijmout", "Receive"))
            }
            HayekButton(onClick = { vm.openSend() }, Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Default.CallMade, null); Spacer(Modifier.width(6.dp)); Text(L("Odeslat", "Send"))
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
    @Suppress("UNUSED_VARIABLE") val nv = vm.notesVersion
    val note = vm.note(tx.txid)
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(if (incoming) Icons.Default.CallReceived else Icons.Default.CallMade, null,
                tint = if (incoming) Color(0xFF2E9E4F) else Orange, modifier = Modifier.pendingPulse(!tx.confirmed))
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

fun qrBitmap(text: String, size: Int = 640): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp
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
                Icon(Icons.Default.Toll, null, tint = Orange)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(L("Mince k utracení (coin control)", "Coins to spend (coin control)"), fontWeight = FontWeight.SemiBold)
                    Text(
                        if (sel.isEmpty()) L("Automaticky (zmrazené se nepoužijí)", "Automatic (frozen coins are not used)")
                        else L("Ručně: ${sel.size} ${if (sel.size == 1) "mince" else "mincí"} · ${formatSat(sel.sumOf { it.valueSat })}", "Manual: ${sel.size} ${if (sel.size == 1) "coin" else "coins"} · ${formatSat(sel.sumOf { it.valueSat })}"),
                        fontSize = 13.sp
                    )
                }
                Text(L("Vybrat", "Choose"), color = Orange)
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
fun SettingsScreen(vm: WalletViewModel, act: FragmentActivity) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(vm.serverUrl) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(L("Váš uzel (Electrum server)", "Your node (Electrum server)"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
            isPublicServer(url) -> GlassCard(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Orange)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Public, null, tint = Orange)
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
            var understood by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { pickPublic = false },
                title = { Text(L("Veřejný server", "Public server")) },
                text = {
                    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                        Text(
                            L("Server cizího provozovatele uvidí:\n• všechny adresy této peněženky a že patří k sobě,\n• zůstatek a celou historii plateb,\n• vaši IP adresu.\n\n", "The operator of a foreign server will see:\n• all addresses of this wallet and that they belong together,\n• the balance and full payment history,\n• your IP address.\n\n") +
                                L("Nedostane se ke klíčům ani ke slovům a nemůže nic utratit. Mohl by ale zatajit transakci nebo ukázat nesprávný zůstatek.\n\n", "They can't reach the keys or words and can't spend anything. But they could hide a transaction or show a wrong balance.\n\n") +
                                L("Seznam serverů je převzatý z peněženky Sparrow.", "The server list is taken from Sparrow wallet."),
                            fontSize = 14.sp
                        )
                        Row(Modifier.fillMaxWidth().clickable { understood = !understood }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = understood, onCheckedChange = { understood = it })
                            Text(L("Rozumím a přesto chci veřejný server", "I understand and still want a public server"), fontSize = 14.sp)
                        }
                        if (understood) {
                            Spacer(Modifier.height(4.dp))
                            Text(L("„Vyzkoušet“ server jen otestuje (neposílá mu vaše adresy). „Použít“ ho uloží.", "„Test“ only tests the server (doesn't send it your addresses). „Use“ saves it."),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            PUBLIC_SERVERS.forEach { (name, u) ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                        Text(u.removePrefix("ssl://"), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    }
                                    TextButton(onClick = { pickPublic = false; vm.testServer(u) }) { Text(L("Vyzkoušet", "Test")) }
                                    TextButton(onClick = {
                                        url = u; pickPublic = false; if (vm.isOpen) act.auth(vm, L("Změnit server", "Change server")) { vm.setServer(u) } else vm.setServer(u)
                                        vm.message = L("Peněženka teď používá veřejný server $name.\nNa svůj uzel se vrátíte zadáním jeho adresy a Uložit.", "The wallet now uses the public server $name.\nTo return to your node, enter its address and Save.")
                                    }) { Text(L("Použít", "Use")) }
                                }
                            }
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
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(L("Smazat peněženku z telefonu", "Delete wallet from phone")) }
        }

        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        GlassButton(onClick = { vm.screen = Screen.Intro(Screen.Settings) }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.School, null); Spacer(Modifier.width(6.dp)); Text(L("Průvodce: proč kostky, slova a vlastní uzel", "Guide: why dice, words and your own node"))
        }

        // Podpora vývoje – adresa pro dary
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
        Spacer(Modifier.height(24.dp))
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(L("Smazat peněženku?", "Delete wallet?")) },
            text = { Text(L("Peněženka zmizí z telefonu. Bitcoiny zůstanou na blockchainu, ale dostanete se k nim JEN pomocí zapsaných slov. Máte je opravdu zapsaná?", "The wallet will disappear from the phone. The bitcoin stays on the blockchain, but you can reach it ONLY with the written words. Do you really have them written down?")) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; act.auth(vm, L("Smazat peněženku", "Delete wallet"), strong = true) { vm.deleteWallet() } }) { Text(L("Smazat", "Delete")) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(L("Zrušit", "Cancel")) } }
        )
    }
}

@Composable
fun SeedScreen(vm: WalletViewModel, act: FragmentActivity) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(L("Obnovovací fráze", "Recovery phrase"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(L("Nikomu ji neukazujte. Nikdo (ani podpora) ji po vás nikdy nepotřebuje.", "Don't show it to anyone. Nobody (not even support) will ever need it from you."))
        Spacer(Modifier.height(12.dp))
        if (vm.revealSeed) SeedGrid(vm.seedWords ?: "")
        else HayekButton(onClick = { act.auth(vm, L("Zobrazit obnovovací frázi", "Show recovery phrase"), strong = true) { vm.revealSeed = true } }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(6.dp)); Text(L("Zobrazit slova", "Show words"))
        }
    }
}

fun openTx(ctx: Context, vm: WalletViewModel, link: String?) {
    if (link == null) vm.message = L("Odkazy na transakce jsou vypnuté. Adresu Mempoolu nastavíte v ⚙ Nastavení.", "Transaction links are off. Set the Mempool address in ⚙ Settings.")
    else openUrl(ctx, link)
}

fun openUrl(ctx: Context, url: String) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
}

/**
 * @param sensitive true = schránka obsah neukáže v náhledu klávesnice/systému a po 60 s ho smaže.
 */
fun copy(ctx: Context, text: String, sensitive: Boolean = false) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Hayek Wallet", text)
    if (sensitive) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    cm.setPrimaryClip(clip)
    if (sensitive) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        runCatching { if (cm.primaryClip?.getItemAt(0)?.text?.toString() == text) cm.clearPrimaryClip() }
    }, 60_000)
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
                                Icon(Icons.Default.OpenInNew, "Detail", Modifier.size(20.dp))
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
