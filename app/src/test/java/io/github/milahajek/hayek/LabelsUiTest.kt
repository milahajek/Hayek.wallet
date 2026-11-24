package io.github.milahajek.hayek

import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1100dp-xxhdpi")
class LabelsUiTest {
    @Test fun labelsSectionAndDialog() = shot(1, "labels")
    @Test fun paymentRequest() = shot(4, "request") { it.state = WalletState(0, 0, 0, "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", emptyList()) }
    @Test fun contacts() = shot(5, "contacts") {
        it.saveContact("Brácha", "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g"); it.saveContact("Petr – oběd", "bc1qp59yckz4ae5c4efgw2s5wfyvrz0ala7rgvuz8z")
        it.saveContact("Bisq – kauce", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")
    }
    @Test fun successLn() = shot(6, "success_ln")
    @Test fun successOnchain() = shot(7, "success")
    @Test fun passphraseInfo() = shot(8, "passphrase_info")
    @Test fun passphraseOn() = shot(9, "passphrase_on")
    @Test fun dustSection() = shot(3, "dust")
    @Test fun offerAfterRestore() = shot(2, "labels_offer")
    @Test fun changesBadge() = shot(0, "labels_changes") { vm ->
        vm.setNote("a".repeat(64), "test"); vm.setAddressTag("bc1qtest", CoinTag.NOKYC); vm.setNote("b".repeat(64), "x")
    }

    private fun shot(dialog: Int, name: String, prep: (WalletViewModel) -> Unit = {}) {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val vm = ViewModelProvider(act)[WalletViewModel::class.java]
        prep(vm)
        if (dialog == 2) vm.offerLabelsImport = true
        act.setContent {
            MaterialTheme(lightColorScheme(primary = Color(0xFFF7931A), background = Color(0xFFFCF8F3), surface = Color(0xFFFFFBF6))) {
                HayekBackground {
                    if (dialog == 9) Column(Modifier.padding(20.dp)) { PassphraseSection(confirm = true, preview = "Kocour Mour 7") {} }
                    else if (dialog == 8) PassphraseInfoDialog {}
                    else if (dialog == 6) SuccessOverlay(Success("Zaplaceno", "4 200 sat", lightning = true)) {}
                    else if (dialog == 7) SuccessOverlay(Success("Odesláno", "150 000 sat")) {}
                    else if (dialog == 5) { Column(Modifier.padding(20.dp)) { ContactHint(vm, "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g") }; ContactsDialog(vm, onDismiss = {}, onPick = {}) }
                    else if (dialog == 4) ReceiveScreen(vm, Triple(true, "150000", "Oběd s bráchou")) else Column(Modifier.padding(20.dp)) {
                        if (dialog == 3) { DustSection(vm); Spacer(Modifier.height(16.dp)); DustPill() } else LabelsSection(vm)
                    }
                    if (dialog == 1) ExportLabelsDialog(count = 12, onDismiss = {}, onConfirm = {})
                    if (dialog == 2) OfferLabelsImportDialog(vm)
                }
            }
        }
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)) }
        captureScreenRoboImage("build/promo/$name.png")
    }
}
