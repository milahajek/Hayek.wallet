package io.github.milahajek.hayek

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Zapne čtení NFC (reader mode), dokud je composable na obrazovce. onTag běží mimo hlavní vlákno. */
@Composable
fun NfcReader(enabled: Boolean, onTag: (Tag) -> Unit) {
    val act = androidx.activity.compose.LocalActivity.current ?: return
    val adapter = remember { NfcAdapter.getDefaultAdapter(act) } ?: return
    val cb by rememberUpdatedState(onTag)
    DisposableEffect(enabled) {
        if (enabled) adapter.enableReaderMode(act, { t -> cb(t) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, null)
        onDispose { runCatching { adapter.disableReaderMode(act) } }
    }
}

@Composable
fun nfcState(): String {   // "ok", "off", "none"
    val ctx = LocalContext.current
    val a = remember { NfcAdapter.getDefaultAdapter(ctx) }
    return if (a == null) "none" else if (a.isEnabled) "ok" else "off"
}

/** Podepsání připravené platby Coldcardem: NFC (přiložit, potvrdit, přiložit) nebo paměťová karta. */
@Composable
fun ColdcardSignDialog(vm: WalletViewModel, onDismiss: () -> Unit) {
    val psbt = remember(vm.prepared) { runCatching { vm.psbtForSigning() }.getOrNull() }
    var step by remember { mutableIntStateOf(1) }       // 1 = zapsat PSBT, 2 = načíst podpis, 3 = hotovo
    var signed by remember { mutableStateOf<ByteArray?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    // NFC běží mimo hlavní vlákno – pojistka proti dvěma současným přiložením.
    val working = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val nfc = nfcState()
    val tooBig = (psbt?.size ?: 0) > ColdcardNfc.MAX_NFC

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.savePsbtFile(uri)
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { onDismiss(); vm.loadSignedFile(uri) }
    }

    NfcReader(enabled = nfc == "ok" && psbt != null && !tooBig && step < 3) { tag ->
        if (!working.compareAndSet(false, true)) return@NfcReader
        try {
            if (step == 1) {
                ColdcardNfc.writePsbt(tag, psbt!!)
                main.post { err = null; step = 2 }
            } else {
                val got = ColdcardNfc.signedFrom(ColdcardNfc.readRecords(tag), psbt)
                main.post { if (got != null) { signed = got; err = null; step = 3 } }
            }
        } catch (e: Exception) {
            main.post { err = e.message ?: e::class.simpleName }
        } finally { working.set(false) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (step == 3) Icons.Default.CheckCircle else Icons.Default.Contactless, null, tint = HayekOrange) },
        title = { Text(when (step) { 1 -> L("Podepsat Coldcardem", "Sign with Coldcard"); 2 -> L("Potvrďte na Coldcardu", "Confirm on the Coldcard"); else -> L("Podepsáno ✔", "Signed ✔") }) },
        text = {
            Column {
                when {
                    psbt == null -> Text(L("Platba není připravená.", "The payment isn't prepared."))
                    nfc == "none" -> Text(L("Telefon nemá NFC – použijte paměťovou kartu.", "This phone has no NFC – use the memory card."), fontSize = 14.sp)
                    nfc == "off" -> Text(L("Zapněte NFC v nastavení telefonu, nebo použijte paměťovou kartu.", "Turn on NFC in the phone settings, or use the memory card."), fontSize = 14.sp)
                    tooBig -> Text(L("Transakce je na NFC moc velká – použijte paměťovou kartu.", "The transaction is too large for NFC – use the memory card."), fontSize = 14.sp)
                    step == 1 -> Text(L(
                        "1. Na Coldcardu zvolte Advanced/Tools → NFC Tools → Sign PSBT.\n2. Přiložte telefon zády ke Coldcardu a chvíli držte.",
                        "1. On the Coldcard choose Advanced/Tools → NFC Tools → Sign PSBT.\n2. Hold the back of the phone to the Coldcard for a moment."), fontSize = 14.sp)
                    step == 2 -> Text(L(
                        "Na displeji Coldcardu zkontrolujte adresu příjemce a částku – to je ta pravá kontrola. Pak potvrďte.\n\nColdcard pak nabídne podepsanou transakci přes NFC: přiložte telefon znovu.",
                        "Check the recipient address and amount on the Coldcard's screen – that's the real check. Then approve.\n\nThe Coldcard then offers the signed transaction via NFC: hold the phone to it again."), fontSize = 14.sp)
                    else -> Text(L(
                        "Transakce je podepsaná. Před odesláním aplikace ověří, že je přesně ta, kterou jste připravili.",
                        "The transaction is signed. Before sending, the app verifies it's exactly the one you prepared."), fontSize = 14.sp)
                }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                if (step < 3) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text(L("Paměťová karta", "Memory card"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { saveFile.launch("hayek-${System.currentTimeMillis() / 1000}.psbt") }, enabled = psbt != null) { Text(L("Uložit PSBT", "Save PSBT")) }
                        TextButton(onClick = { openFile.launch(arrayOf("*/*")) }) { Text(L("Načíst podepsaný", "Load signed")) }
                    }
                }
            }
        },
        confirmButton = {
            if (step == 3) TextButton(onClick = { val s = signed; onDismiss(); if (s != null) vm.broadcastSigned(s) }) { Text(L("Odeslat přes můj uzel", "Send via my node")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Zrušit", "Cancel")) } },
    )
}

/** Načtení veřejného popisu peněženky z Coldcardu přes NFC (export deskriptoru). */
@Composable
fun ColdcardImportDialog(onDismiss: () -> Unit, onResult: (String) -> Unit) {
    var err by remember { mutableStateOf<String?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val nfc = nfcState()
    NfcReader(enabled = nfc == "ok") { tag ->
        try {
            val recs = ColdcardNfc.readRecords(tag)
            val texts = recs.map { (t, p) -> if (t == "text") ColdcardNfc.textPayload(p) else String(p, Charsets.UTF_8) }
            val src = texts.firstNotNullOfOrNull { ColdcardNfc.watchSource(it) }
            main.post { if (src != null) onResult(src) else err = L("Ve štítku není popis peněženky. Na Coldcardu zvolte export deskriptoru přes NFC.", "No wallet descriptor on the tag. On the Coldcard export the descriptor via NFC.") }
        } catch (e: Exception) { main.post { err = e.message } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Contactless, null, tint = HayekOrange) },
        title = { Text(L("Načíst z Coldcardu", "Import from Coldcard")) },
        text = {
            Column {
                Text(when (nfc) {
                    "none" -> L("Telefon nemá NFC.", "This phone has no NFC.")
                    "off" -> L("Zapněte NFC v nastavení telefonu.", "Turn on NFC in the phone settings.")
                    else -> L(
                        "1. Na Coldcardu: Advanced/Tools → Export Wallet → Descriptor (segwit), a sdílejte přes NFC (klávesa 3).\n2. Přiložte telefon ke Coldcardu.\n\nPřenáší se jen veřejný klíč – utrácet z telefonu nepůjde bez Coldcardu.",
                        "1. On the Coldcard: Advanced/Tools → Export Wallet → Descriptor (segwit), and share via NFC (key 3).\n2. Hold the phone to the Coldcard.\n\nOnly the public key is transferred – the phone can't spend without the Coldcard.")
                }, fontSize = 14.sp)
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(L("Zavřít", "Close")) } },
    )
}
