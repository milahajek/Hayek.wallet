package io.github.milahajek.hayek

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity

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
                    primary = HayekOrange, onPrimary = Color.Black,
                    background = Color(0xFF0D0F14), surface = Color(0xFF171A21),
                    surfaceVariant = Color(0xFF232733), surfaceContainerHigh = Color(0xFF1E222B),
                )
            else lightColorScheme(
                primary = HayekOrange, onPrimary = Color.Black,
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
            // Platí i při zakládání první peněženky: hody a slova nesmí zůstat na obrazovce bez dozoru.
            // Opisování 24 slov trvá a obrazovky se při něm nikdo nedotýká, proto má zakládání delší lhůtu.
            val limit = if (vm.isOpen) IDLE_LOCK_MS else 3 * IDLE_LOCK_MS
            if ((vm.isOpen || vm.inSecretSetup) && !vm.busy && SystemClock.elapsedRealtime() - lastTouch > limit) vm.lockApp()
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
        val away = if (since != 0L) SystemClock.elapsedRealtime() - since else 0L
        if ((vm.isOpen && away > 120_000) || (!vm.isOpen && vm.inSecretSetup && away > 600_000)) vm.lockApp()
        vm.backgroundSince = 0L
    }
}

fun FragmentActivity.auth(vm: WalletViewModel, title: String, strong: Boolean = false, ok: () -> Unit) {
    if (!canAuthenticate(this)) {
        vm.message = L("Nejdřív si v telefonu nastavte zámek obrazovky (PIN nebo otisk prstu). Bez něj peněženka nemůže bezpečně uložit klíče.", "First set a screen lock on your phone (PIN or fingerprint). Without it the wallet can't store the keys securely.")
        return
    }
    // Otisky se změnily, nebo úplně zmizely (peněženka přitom otiskem chráněná byla): obojí jde udělat jen se znalostí PINu.
    val tampered = if (hasStrongBiometric(this)) vm.store.fingerprintsChanged() else vm.store.bioSeen()
    if (strong && tampered) {
        // Než otisku (nebo samotnému PINu) znovu uvěříme, ověříme slova ze zálohy.
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
            screen == Screen.Welcome && vm.adding -> vm.cancelAddWallet()
            screen == Screen.Coins -> vm.screen = vm.coinsReturn
            screen is Screen.History -> vm.screen = Screen.Home
            screen == Screen.Export || screen == Screen.Seed || screen == Screen.LnSetup -> { vm.revealSeed = false; vm.screen = Screen.Settings }
            screen == Screen.LnPay || screen == Screen.LnReceive -> vm.openLightning()
            screen in inWallet -> { vm.revealSeed = false; vm.screen = if (vm.isOpen) Screen.Home else if (vm.hasWallet) Screen.Locked else Screen.Welcome }
            screen is Screen.Verify -> vm.screen = Screen.ShowSeed(screen.words)
            screen == Screen.ServerSetup -> vm.skipServerSetup()
            setup -> vm.screen = Screen.Welcome
        }
    }
    if (screen in inWallet || screen is Screen.History || setup || screen is Screen.Intro || screen == Screen.ServerSetup || (screen == Screen.Welcome && vm.adding)) BackHandler { back() }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
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
                                withStyle(SpanStyle(color = HayekOrange, fontWeight = FontWeight.ExtraBold)) { append("Hayek") }
                                withStyle(SpanStyle(fontWeight = FontWeight.Light)) { append(" Wallet") }
                            })
                        }
                        val wName = if (vm.wallets.size > 1 && vm.isOpen && screen != Screen.Welcome) vm.activeWallet?.let { vm.walletName(it) + " · " } ?: "" else ""
                        // Veřejný server vidí adresy a zůstatek – proto se odlišuje od vlastního uzlu i barvou.
                        val public = isPublicServer(vm.serverUrl)
                        val sub = wName + when (vm.connected) {
                            true -> if (public) L("● připojeno k veřejnému serveru", "● connected to a public server") else L("● připojeno k vašemu uzlu", "● connected to your node")
                            false -> if (public) L("● veřejný server nedostupný", "● public server unreachable") else L("● uzel nedostupný", "● node unreachable")
                            null -> L("Bitcoin mainnet · skutečné peníze", "Bitcoin mainnet · real money")
                        } + if (vm.watchOnly && wName.isEmpty()) L(" · jen sledování", " · watch-only") else ""
                        Text(sub, fontSize = 12.sp, color = when (vm.connected) {
                            true -> if (public) HayekOrange else Color(0xFF2E9E4F); false -> MaterialTheme.colorScheme.error; null -> HayekOrange
                        })
                    }
                },
                navigationIcon = {
                    if (screen in inWallet || screen is Screen.History || setup) IconButton(onClick = { back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, L("Zpět", "Back"))
                    }
                },
                actions = {
                    if (screen == Screen.Home) {
                        IconButton(onClick = { vm.sync() }, enabled = !vm.busy) { Icon(Icons.Default.Refresh, L("Obnovit", "Refresh")) }
                        IconButton(onClick = { vm.screen = Screen.Settings }) {
                            BadgedBox(badge = { if (vm.labelChanges > 0) Badge(containerColor = HayekOrange) }) {
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
                Screen.ServerSetup -> ServerSetupScreen(vm)
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
                is Screen.History -> HistoryScreen(vm, act, s.filter)
            }
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }

    // Při opisování slov a házení kostkou nesmí displej zhasnout (a peněženka mezitím zahodit rozpracované založení).
    val view = androidx.compose.ui.platform.LocalView.current
    val keepOn = vm.inSecretSetup
    DisposableEffect(keepOn) { view.keepScreenOn = keepOn; onDispose { view.keepScreenOn = false } }

    vm.fingerprintCheck?.let { FingerprintCheckDialog(vm, act) }

    if (vm.message == null && vm.success == null && vm.fingerprintCheck == null) vm.pinNotice?.let { (hostPort, fp) -> PinNoticeDialog(vm, hostPort, fp) }

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
