package io.github.milahajek.hayek

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Ukázková data pro propagační obrázky – nic skutečného. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1100dp-xxhdpi")
class PromoTest {
    private fun shoot(name: String, screen: Boolean = true, after: (WalletViewModel) -> Unit = {}, setup: (WalletViewModel) -> Unit) {
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val vm = ViewModelProvider(ctl.get())[WalletViewModel::class.java]
        Lang.set(ctl.get(), System.getenv("PROMO_LANG") ?: "cs")
        if (vm.hideBalance) vm.toggleHideBalance()
        setup(vm); androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); ctl.recreate(); val act = ctl.get(); after(vm)
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)) }
        if (screen) captureScreenRoboImage("build/promo/$name.png") else act.window.decorView.captureRoboImage("build/promo/$name.png")
    }
    private fun h(seed: Int) = (0 until 64).map { "0123456789abcdef"[(seed * 7 + it * 13 + it * it * seed) % 16] }.joinToString("")
    private val d = 86_400L
    private val now = 1_795_421_000L
    private val pendingOut = TxItem(h(1), -152_340, 846, false, null, now - 1_200, feeRate = 6, canBump = true)
    private val txs = listOf(
        pendingOut,
        TxItem(h(2), 1_000_000, 0, true, 925_890, now - d),
        TxItem(h(3), -86_500, 420, true, 925_610, now - 3 * d),
        TxItem(h(4), 250_000, 0, true, 925_330, now - 5 * d),
        TxItem(h(5), -502_100, 2_100, true, 924_980, now - 8 * d),
        TxItem(h(6), 75_000, 0, true, 924_600, now - 11 * d),
        TxItem(h(7), -33_210, 282, true, 924_120, now - 15 * d),
        TxItem(h(8), 2_400_000, 0, true, 923_700, now - 19 * d),
    )
    private val state = WalletState(2_951_195 - 152_340 - 846, 0, 2_797_009, "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", txs)

    private fun base(vm: WalletViewModel) {
        vm.setThemeMode(0); vm.setServer("ssl://10.0.0.1:50002"); vm.connected = true; vm.state = state
        vm.coins = listOf(CoinItem(h(2), 0, 1_000_000, "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g", true, 925_890, false), CoinItem(h(4), 1, 250_000, "bc1qp59yckz4ae5c4efgw2s5wfyvrz0ala7rgvuz8z", true, 925_330, false), CoinItem(h(6), 0, 75_000, "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", true, 924_600, false), CoinItem(h(8), 0, 1_547_009, "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", true, 923_700, false))
        vm.setNote(h(1), "Nájem – listopad"); vm.setNote(h(2), "Výplata – úspory")
        vm.setNote(h(3), "Bisq – kauce za obchod"); vm.setNote(h(4), "Bisq – nákup za CZK"); vm.setNote(h(6), "Bisq – vrácená kauce"); vm.setNote(h(7), "Bisq – prodej za EUR"); vm.setNote(h(5), "Otevření kanálu ACINQ")
        vm.setNote(h(8), "Bisq – nákup BTC (DCA)")
        vm.lnConfig = LndConfig("10.0.0.1:8080", "02".repeat(80)); vm.lnInfo = LnInfo("Můj uzel", "03e4ba21", 4, true, "0.19.3-beta")
        vm.lnBalance = LnBalance(1_163_920, 1_028_080, 0)
        vm.lnChannels = listOf(
            LnChannel("03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f", "ACINQ", "#49daaa",
                1_000_000, 612_400, 384_300, 10_000, true, false, false, true, h(11) + ":1", sentSat = 401_200, receivedSat = 13_600),
            LnChannel("033d8656219478701227199cbd6f670335c8d408a92ae88b962c49d4dc0e83e025", "Bitfinex", "#3399ff",
                750_000, 128_900, 618_300, 7_500, true, false, false, false, h(12) + ":0", sentSat = 0, receivedSat = 128_900),
            LnChannel("02d96eadea3d780104449aca5c93461ce67c1564e2e1d73225fa67dd3b997a6018", "Bitrefill", "#ff7a00",
                300_000, 274_620, 25_380, 3_000, true, false, false, true, h(13) + ":0", sentSat = 25_380, receivedSat = 0),
            LnChannel("0298f6074a454a1f5345cb2a7c6f9fce206cd0bf675d177cdbf0ca7508dd28852f", "Kolíbrík", "#c040ff",
                200_000, 170_000, 0, 2_000, false, true, false, true, h(14) + ":1"),
        )
        vm.lnHistory = listOf(
            LnHistoryItem(false, 4_200, 1, now - 3_600, "Káva", "p:1"),
            LnHistoryItem(true, 21_000, 0, now - 7_200, "Oběd od Petra", "i:1"),
            LnHistoryItem(false, 12_500, 3, now - d, "Dárková karta", "p:2"),
            LnHistoryItem(true, 100_000, 0, now - 2 * d, "Faktura – překlad", "i:2"),
            LnHistoryItem(false, 2_100, 0, now - 3 * d, "Podcast – value4value", "p:3"),
            LnHistoryItem(false, 8_800, 2, now - 4 * d, "Pizza", "p:4"),
        )
    }
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    @Test fun p1_home() = shoot("p1_home") { base(it); it.screen = Screen.Home }
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    @Test fun p2_ln() = shoot("p2_ln") { base(it); it.screen = Screen.Lightning }
    @Test fun p3_detail() = shoot("p3_detail", after = { it.txDetail = pendingOut }) { base(it); it.screen = Screen.Home }
    @Test fun p5_pay() = shoot("p5_pay") { base(it)
        it.lnDecoded = LnDecoded("lnbc42u1p4sxfugpp5tw9k9s4ed0zhen3njkse77d8lc0zmj4k2rqkla8unfzfrf0zg00qdqsw3jhxapqd0p6zanpcqzzsxqrrsssp5hn3lruytv9eesuawuwgpfz09wvnndpl2zumzkjn8mzcnpc4smqtq9qxpqysgq7de3qwfpnvjgyyv4v6kge6jcx4ef92j02lgwg8gml2yg80wngl6sdftq328uu0nhm8t96qk5vd37s06lr5w6xxsn374fwufuuuw6zrcpmyr9r0", 4_200, "Káva u Hayeka", "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f", "ab", System.currentTimeMillis() / 1000, 3600)
        it.screen = Screen.LnPay }
    private fun tags(vm: WalletViewModel) {
        vm.setCoinTag(listOf("${h(4)}:1", "${h(8)}:0"), CoinTag.NOKYC); vm.setCoinTag(listOf("${h(2)}:0"), CoinTag.KYC)
    }
    @Test fun p6_coins() = shoot("p6_coins") { base(it); tags(it); it.selected = setOf("${h(4)}:1", "${h(2)}:0"); it.screen = Screen.Coins }
    @Test fun p7_send_from() = shoot("p7_send_from") { base(it); tags(it); it.feeEstimates = FeeEstimates(14, 6, 2); it.screen = Screen.Send; it.payFrom = "nokyc" }
    @Test fun p8_receive() = shoot("p8_receive") { base(it); tags(it); it.setAddressTag(it.state!!.address, CoinTag.NOKYC); it.screen = Screen.Receive }
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    @Test fun p9_home_tags() = shoot("p9_home_tags") { base(it); tags(it); it.screen = Screen.Home }
    private val two = listOf(WalletInfo("w1", "", "seed", "hot"), WalletInfo("w2", "Trezor (Coldcard)", "wallet_w2", "watch"))
    private fun multi(vm: WalletViewModel, active: String = "w1") = vm.previewWallets(two, mapOf("w1" to 2_797_009L, "w2" to 41_250_000L), active)
    @Test fun p10_wallets_home() = shoot("p10_wallets_home") { base(it); tags(it); multi(it); it.screen = Screen.Home }
    @Test fun p11_wallets_vault() = shoot("p11_wallets_vault") { base(it); multi(it, "w2"); it.screen = Screen.Home }
    @Test fun p12_send_own() = shoot("p12_send_own") { base(it); multi(it); it.feeEstimates = FeeEstimates(14, 6, 2); it.screen = Screen.Send }
    @Test fun p13_add_wallet() = shoot("p13_add_wallet") { base(it); it.previewWallets(two.take(1), mapOf("w1" to 1L), "w1", addingMode = true); it.screen = Screen.Welcome }
    @Test fun p4_send() = shoot("p4_send") { base(it); it.feeEstimates = FeeEstimates(14, 6, 2); it.chooseFee(FeeChoice.NORMAL); it.screen = Screen.Send }
}
