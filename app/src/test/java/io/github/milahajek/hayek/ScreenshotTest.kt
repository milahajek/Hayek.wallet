package io.github.milahajek.hayek

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    private val fakeWords = "duck soft salt virtual tattoo actor swallow cradle brother arch adjust icon degree canal ugly liquid churn obtain link fun sight obscure survey fossil"

    private fun shoot(name: String, screen: Boolean = false, setup: (WalletViewModel) -> Unit) {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val vm = ViewModelProvider(act)[WalletViewModel::class.java]
        setup(vm)
        repeat(5) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(50) }
        if (screen) com.github.takahirom.roborazzi.captureScreenRoboImage("build/shots/$name.png")
        else act.window.decorView.captureRoboImage("build/shots/$name.png")
    }

    private val state = WalletState(
        confirmedSat = 184_250, pendingSat = 25_000, totalSat = 209_250,
        address = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
        txs = listOf(
            TxItem("a1b2c3d4e5f60718293a4b5c6d7e8f9012345678", 25_000, 141, false, null, null),
            TxItem("9f8e7d6c5b4a39281706f5e4d3c2b1a098765432", -40_750, 750, true, 925_431, 1795700000),
            TxItem("0a1b2c3d4e5f60718293a4b5c6d7e8f901234567", 225_000, 520, true, 925_102, 1795600000),
        )
    )

    private val coins = listOf(
        CoinItem("0a1b2c3d4e5f60718293a4b5c6d7e8f901234567", 0, 120_000, "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", true, 925_102, false),
        CoinItem("0a1b2c3d4e5f60718293a4b5c6d7e8f901234567", 1, 64_250, "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g", true, 925_102, true),
        CoinItem("a1b2c3d4e5f60718293a4b5c6d7e8f9012345678", 0, 25_000, "bc1qp59yckz4ae5c4efgw2s5wfyvrz0ala7rgvuz8z", false, null, false),
    )

    @Test fun s8_coins() = shoot("8_mince") { it.state = state; it.coins = coins; it.connected = true; it.selected = setOf(coins[0].id); it.screen = Screen.Coins }
    @Config(qualifiers = "w411dp-h1000dp-xxhdpi")
    @Test fun s9_send_cc() = shoot("9_odeslat_coincontrol") { it.setThemeMode(2); it.state = state; it.coins = coins; it.connected = true; it.selected = setOf(coins[0].id); it.feeEstimates = FeeEstimates(12, 6, 2); it.chooseFee(FeeChoice.CUSTOM); it.feeRate = "1"; it.screen = Screen.Send }
    @Test fun s10_dark_home() = shoot("10_tmavy_rezim") { it.setThemeMode(2); it.state = state; it.coins = coins; it.connected = true; it.screen = Screen.Home }
    @Test fun s11_watch() = shoot("11_sledovani") { it.setThemeMode(0); it.screen = Screen.WatchImport }
    @Test fun s12_settings_dark() = shoot("12_nastaveni_tmave") { it.setThemeMode(2); it.connected = true; it.screen = Screen.Settings }

    @Test fun s13_settings_mempool() = shoot("13_nastaveni_mempool") { it.setThemeMode(0); it.connected = true; it.screen = Screen.Settings }

    @Test fun s15_icon() {
        val act = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().get()
        act.setContent {
            androidx.compose.foundation.layout.Row(
                androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color(0xFF2B3340)).padding(24.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp)
            ) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_logo), null, androidx.compose.ui.Modifier.size(160.dp))
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_logo), null, androidx.compose.ui.Modifier.size(64.dp))
            }
        }
        repeat(3) { shadowOf(Looper.getMainLooper()).idle() }
        act.window.decorView.captureRoboImage("build/shots/15_ikona.png")
    }

    @Test fun s16_pin() = shoot("16_certifikat") {
        it.setThemeMode(2); it.connected = true; it.setServer("ssl://192.168.0.10:50002")
        it.pins.set("192.168.0.10:50002", "20:52:19:23:B7:D9:4A:C6:E3:55:41:CC:7B:AB:F1:4C:4D:F3:57:95:1E:FD:F0:17:EF:A9:B5:12:BB:E9:74:F9")
        it.screen = Screen.Settings
    }

    @Test fun s17_public() = shoot("17_verejny_server") {
        it.setThemeMode(2); it.connected = true; it.setServer(PUBLIC_SERVERS[0].second); it.screen = Screen.Settings
    }

    @Config(qualifiers = "w411dp-h1800dp-xxhdpi")
    @Test fun s18_donate() = shoot("18_dary") { it.setThemeMode(2); it.connected = true; it.setServer("ssl://192.168.0.10:50002"); it.screen = Screen.Settings }

    @Test fun s1_welcome() = shoot("1_uvod") { it.setThemeMode(0); it.finishIntro(Screen.Welcome) }
    @Test fun s2_dice() = shoot("2_kostky") { it.screen = Screen.Dice; it.rolls = "35216614523362154126635214513624436521164352614253366" }
    @Test fun s2b_dice_bad() = shoot("2b_kostky_vzorec") { it.screen = Screen.Dice; it.rolls = "123456".repeat(9) }
    @Test fun s4b_home_public() = shoot("4b_penezenka_verejny") { it.setThemeMode(0); it.state = state; it.coins = coins; it.connected = true; it.setServer(PUBLIC_SERVERS.first().second); it.screen = Screen.Home }
    @Test fun s4c_home_own() = shoot("4c_penezenka_uzel") { it.setThemeMode(0); it.state = state; it.coins = coins; it.connected = true; it.setServer("ssl://192.168.0.10:50002"); it.screen = Screen.Home }
    @Test fun s3_seed() = shoot("3_slova") { it.screen = Screen.ShowSeed(fakeWords) }
    @Test fun s4_home() = shoot("4_penezenka") { it.setThemeMode(0); it.state = state; it.coins = coins; it.connected = true; it.screen = Screen.Home }
    @Test fun s5_receive() = shoot("5_prijmout") { it.state = state; it.connected = true; it.screen = Screen.Receive }
    @Config(qualifiers = "w411dp-h1000dp-xxhdpi")
    @Test fun s6_send() = shoot("6_odeslat") { it.state = state; it.connected = true; it.feeEstimates = FeeEstimates(12, 6, 2); it.chooseFee(FeeChoice.NORMAL); it.screen = Screen.Send }
    @Test fun s7_settings() = shoot("7_nastaveni") { it.connected = true; it.setServer("ssl://192.168.0.10:50002"); it.screen = Screen.Settings }

    private val lnCfg = LndConfig("192.168.0.10:8080", "02".repeat(80))
    private fun lnState(vm: WalletViewModel) {
        vm.lnConfig = lnCfg; vm.connected = true
        vm.lnInfo = LnInfo("Můj uzel", "03e4ba21", 1, true, "0.19.3-beta")
        vm.lnBalance = LnBalance(318_420, 176_300, 0)
        vm.lnChannels = listOf(
            LnChannel("03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f", "ACINQ", "#49daaa",
                500_000, 489_420, 6_300, 5_000, true, false, false, true, "f64b15b83b790824081e6bace83776adeb233395e4b294b03134c6a0dc9c3a31:1",
                sentSat = 6_300, receivedSat = 0),
        )
        vm.lnHistory = listOf(
            LnHistoryItem(true, 21_000, 0, 1795710000, "Oběd", "i:3"),
            LnHistoryItem(false, 4_200, 1, 1795700000, "", "p:a"),
            LnHistoryItem(false, 2_100, 0, 1795600000, "", "p:b"),
        )
    }
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    @Test fun s19_lightning() = shoot("19_lightning") { it.setThemeMode(0); lnState(it); it.screen = Screen.Lightning }
    @Test fun s20_ln_pay() = shoot("20_ln_zaplatit") {
        it.setThemeMode(0); lnState(it)
        it.lnDecoded = LnDecoded("lnbcrt15u1p4sxfugpp5tw9k9s4ed0zhen3njkse77d8lc0zmj4k2rqkla8unfzfrf0zg00qdqsw3jhxapqd0p6zanpcqzzsxqrrsssp5hn3lruytv9eesuawuwgpfz09wvnndpl2zumzkjn8mzcnpc4smqtq9qxpqysgq7de3qwfpnvjgyyv4v6kge6jcx4ef92j02lgwg8gml2yg80wngl6sdftq328uu0nhm8t96qk5vd37s06lr5w6xxsn374fwufuuuw6zrcpmyr9r0", 4_200, "Káva u Hayeka", "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f", "ab", System.currentTimeMillis() / 1000, 3600)
        it.screen = Screen.LnPay
    }
    @Test fun s21_ln_receive() = shoot("21_ln_prijmout") {
        it.setThemeMode(2); lnState(it)
        it.lnInvoice = LnInvoice("lnbcrt15u1p4sxfugpp5tw9k9s4ed0zhen3njkse77d8lc0zmj4k2rqkla8unfzfrf0zg00qdqsw3jhxapqd0p6zanpcqzzsxqrrsssp5hn3lruytv9eesuawuwgpfz09wvnndpl2zumzkjn8mzcnpc4smqtq9qxpqysgq7de3qwfpnvjgyyv4v6kge6jcx4ef92j02lgwg8gml2yg80wngl6sdftq328uu0nhm8t96qk5vd37s06lr5w6xxsn374fwufuuuw6zrcpmyr9r0", "5b8b", 21_000, "Oběd")
        it.screen = Screen.LnReceive
    }
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    @Test fun s22_ln_setup() = shoot("22_ln_pripojit") { it.setThemeMode(0); it.connected = true; it.setServer("ssl://192.168.0.10:50022"); it.screen = Screen.LnSetup }
    @Test fun s23_home_ln() = shoot("23_penezenka_lightning") { it.setThemeMode(0); it.state = state; it.coins = coins; lnState(it); it.screen = Screen.Home }

    @Test fun s24_intro() = shoot("24_pruvodce") { it.setThemeMode(0) }
    @Test fun s25_intro_dice() = shoot("25_pruvodce_tmavy") { it.setThemeMode(2); it.screen = Screen.Intro(Screen.Settings) }
    @Test fun s26_hidden() = shoot("26_skryty_zustatek") { it.setThemeMode(0); it.state = state; it.coins = coins; it.connected = true; if (!it.hideBalance) it.toggleHideBalance(); it.screen = Screen.Home }
    private val pendingOut = TxItem("9f8e7d6c5b4a39281706f5e4d3c2b1a098765433", -40_281, 281, false, null, 1795700000, feeRate = 2, canBump = true)
    @Test fun s27_txdetail() = shoot("27_detail_transakce", screen = true) {
        it.setThemeMode(0); if (it.hideBalance) it.toggleHideBalance()
        it.state = state.copy(txs = listOf(pendingOut) + state.txs); it.connected = true
        it.setNote(pendingOut.txid, "Nájem – listopad")
        it.screen = Screen.Home; it.txDetail = pendingOut
    }

    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    @Test fun s29_channels_new() = shoot("29_kanal_novy") {
        it.setThemeMode(2); lnState(it); it.lnHistory = emptyList()
        it.lnBalance = LnBalance(495_000, 0, 0)
        it.lnChannels = listOf(
            LnChannel("03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f", "ACINQ", "#49daaa",
                500_000, 495_000, 0, 5_000, true, false, false, true, "f64b15b83b790824081e6bace83776adeb233395e4b294b03134c6a0dc9c3a31:1"),
            LnChannel("02d96eadea3d780104449aca5c93461ce67c1564e2e1d73225fa67dd3b997a6018", "Bitrefill", "#ff7a00",
                300_000, 300_000, 0, 0, false, true, false, true, "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678a1b2c3d4e5f6071829304a5b:0"),
            LnChannel("0298f6074a454a1f5345cb2a7c6f9fce206cd0bf675d177cdbf0ca7508dd28852f", null, null,
                200_000, 120_000, 80_000, 2_000, false, false, true, false, "b1b2c3d4e5f60718293a4b5c6d7e8f9012345678a1b2c3d4e5f6071829304a5b:1"),
        )
        it.screen = Screen.Lightning
    }
}
