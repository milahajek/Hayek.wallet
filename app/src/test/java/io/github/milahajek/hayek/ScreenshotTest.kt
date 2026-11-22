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

    private fun shoot(name: String, setup: (WalletViewModel) -> Unit) {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val vm = ViewModelProvider(act)[WalletViewModel::class.java]
        setup(vm)
        repeat(5) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(50) }
        act.window.decorView.captureRoboImage("build/shots/$name.png")
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
    @Test fun s9_send_cc() = shoot("9_odeslat_coincontrol") { it.state = state; it.coins = coins; it.connected = true; it.selected = setOf(coins[0].id); it.feeRate = "4"; it.screen = Screen.Send }
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

    @Test fun s1_welcome() = shoot("1_uvod") { it.setThemeMode(0) }
    @Test fun s2_dice() = shoot("2_kostky") { it.screen = Screen.Dice; it.rolls = "35216614523362154126635214513624436521164352614253366" }
    @Test fun s3_seed() = shoot("3_slova") { it.screen = Screen.ShowSeed(fakeWords) }
    @Test fun s4_home() = shoot("4_penezenka") { it.setThemeMode(0); it.state = state; it.coins = coins; it.connected = true; it.screen = Screen.Home }
    @Test fun s5_receive() = shoot("5_prijmout") { it.state = state; it.connected = true; it.screen = Screen.Receive }
    @Test fun s6_send() = shoot("6_odeslat") { it.state = state; it.connected = true; it.feeRate = "4"; it.screen = Screen.Send }
    @Test fun s7_settings() = shoot("7_nastaveni") { it.connected = true; it.setServer("ssl://192.168.0.10:50002"); it.screen = Screen.Settings }
}
