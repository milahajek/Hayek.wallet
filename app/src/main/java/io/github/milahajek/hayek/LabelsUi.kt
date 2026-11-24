package io.github.milahajek.hayek

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Nastavení → záloha poznámek, značek KYC a zmrazení mincí (BIP-329). */
@Composable
fun LabelsSection(vm: WalletViewModel) {
    var askExport by remember { mutableStateOf(false) }
    var exportPw by remember { mutableStateOf("") }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.exportLabels(uri, exportPw)
        exportPw = ""
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importLabels(uri)
    }

    Text(L("Štítky a poznámky", "Labels and notes"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text(L("Poznámky k transakcím, značky Bez KYC / KYC a zmrazené mince jsou jen v tomto telefonu – obnovovací slova je neobsahují. ", "Transaction notes, No-KYC / KYC tags and frozen coins exist only on this phone – the recovery words don't contain them. ") +
        L("Zálohujte je do souboru (standard BIP-329, umí ho i Sparrow Wallet).", "Back them up to a file (BIP-329 standard, Sparrow Wallet supports it too)."),
        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (vm.wallets.size > 1) Text(L("Platí pro zobrazenou peněženku „${vm.walletName(vm.activeId)}“ – každá peněženka se zálohuje zvlášť.", "Applies to the shown wallet „${vm.walletName(vm.activeId)}“ – each wallet is backed up separately."),
        fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
    val last = vm.lastLabelsBackup
    val status = when {
        vm.labelChanges > 0 -> L("● Od poslední zálohy: ${vm.labelChanges} ${changesWord(vm.labelChanges)}", "● Since last backup: ${vm.labelChanges} ${changesWord(vm.labelChanges)}") + if (last == 0L) L(" (zatím nezálohováno)", " (not backed up yet)") else ""
        last > 0 -> L("✔ Zálohováno ", "✔ Backed up ") + fmtDate(last)
        else -> null
    }
    status?.let {
        Text(it, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp),
            color = if (vm.labelChanges > 0) HayekOrange else androidx.compose.ui.graphics.Color(0xFF2E9E5B))
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassButton(onClick = { askExport = true }, Modifier.weight(1f), enabled = !vm.busy) {
            Icon(Icons.Default.Upload, null); Spacer(Modifier.width(6.dp)); Text(L("Zálohovat", "Back up"))
        }
        GlassButton(onClick = { openLauncher.launch(arrayOf("*/*")) }, Modifier.weight(1f), enabled = !vm.busy) {
            Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text(L("Načíst", "Import"))
        }
    }

    if (askExport) ExportLabelsDialog(
        count = vm.labelsForExport().size,
        onDismiss = { askExport = false },
        onConfirm = { pw ->
            askExport = false; exportPw = pw
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            saveLauncher.launch((if (vm.wallets.size > 1) "hayek-stitky-${fileSafe(vm.walletName(vm.activeId))}-$day" else "hayek-stitky-$day") + if (pw.isEmpty()) ".jsonl" else ".hayek")
        },
    )
    vm.labelsImportNeedsPassword?.let { uri ->
        PasswordDialog(
            title = L("Zašifrovaná záloha", "Encrypted backup"),
            text = L("Zadejte heslo, kterým jste zálohu zašifroval.", "Enter the password you encrypted the backup with."),
            confirm = L("Načíst", "Import"),
            onDismiss = { vm.labelsImportNeedsPassword = null },
            onConfirm = { vm.importLabels(uri, it) },
        )
    }
}

@Composable
internal fun ExportLabelsDialog(count: Int, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var encrypt by remember { mutableStateOf(true) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val pwOk = !encrypt || (pw.length >= 8 && pw == pw2)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L("Zálohovat štítky", "Back up labels")) },
        text = {
            Column {
                Text(L("Položek k uložení: $count. Soubor neobsahuje klíče, ale prozrazuje historii vašich plateb.", "Items to save: $count. The file contains no keys, but reveals your payment history."), fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.clickable { encrypt = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(encrypt, { encrypt = true }); Text(L("Zašifrovat heslem (doporučeno)", "Encrypt with a password (recommended)"), fontSize = 14.sp)
                }
                Row(Modifier.clickable { encrypt = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(!encrypt, { encrypt = false }); Text(L("Nešifrovaně – pro Sparrow a jiné peněženky", "Unencrypted – for Sparrow and other wallets"), fontSize = 14.sp)
                }
                if (encrypt) {
                    OutlinedTextField(pw, { pw = it }, label = { Text(L("Heslo (aspoň 8 znaků)", "Password (at least 8 characters)")) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pw2, { pw2 = it }, label = { Text(L("Heslo znovu", "Password again")) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = pw2.isNotEmpty() && pw != pw2, modifier = Modifier.fillMaxWidth())
                    Text(L("Heslo si zapamatujte – bez něj zálohu nikdo neotevře, ani vy.", "Remember the password – without it nobody can open the backup, not even you."), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(if (encrypt) pw else "") }, enabled = pwOk && count > 0) { Text(L("Vybrat místo uložení", "Choose where to save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Zrušit", "Cancel")) } },
    )
}

@Composable
private fun PasswordDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(text, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(pw, { pw = it }, label = { Text(L("Heslo", "Password")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pw) }, enabled = pw.isNotEmpty()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Zrušit", "Cancel")) } },
    )
}

fun changesWord(n: Int) = when { n == 1 -> L("změna", "change"); n in 2..4 -> L("změny", "changes"); else -> L("změn", "changes") }

/** Po obnově ze slov: nabídka načíst zálohu štítků. */
@Composable
fun OfferLabelsImportDialog(vm: WalletViewModel) {
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importLabels(uri) else vm.offerLabelsImport = false
    }
    if (vm.labelsImportNeedsPassword == null) AlertDialog(
        onDismissRequest = { vm.offerLabelsImport = false },
        icon = { Icon(Icons.Default.Download, null) },
        title = { Text(L("Peněženka obnovena ✔", "Wallet restored ✔")) },
        text = {
            Text(L("Máte zálohu štítků (poznámky, značky Bez KYC / KYC, zmrazené mince)? Načtěte ji teď. ", "Do you have a label backup (notes, No-KYC / KYC tags, frozen coins)? Import it now. ") +
                L("Později to jde i v Nastavení → Štítky a poznámky.", "You can also do it later in Settings → Labels and notes."), fontSize = 14.sp)
        },
        confirmButton = { TextButton(onClick = { openLauncher.launch(arrayOf("*/*")) }) { Text(L("Načíst zálohu", "Import backup")) } },
        dismissButton = { TextButton(onClick = { vm.offerLabelsImport = false }) { Text(L("Nemám / teď ne", "I don't have one / not now")) } },
    )
    vm.labelsImportNeedsPassword?.let { uri ->
        PasswordDialog(L("Zašifrovaná záloha", "Encrypted backup"), L("Zadejte heslo, kterým jste zálohu zašifroval.", "Enter the password you encrypted the backup with."), L("Načíst", "Import"),
            onDismiss = { vm.labelsImportNeedsPassword = null }, onConfirm = { vm.importLabels(uri, it) })
    }
}

/** Nastavení → upozornění na příchozí platby. */
@Composable
fun NotifySection(vm: WalletViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setNotify(true)
        else vm.message = L("Bez povolení oznámení nemůže aplikace upozorňovat. Povolíte ho v Nastavení telefonu → Aplikace → Hayek Wallet → Oznámení.", "Without notification permission the app can't alert you. Allow it in phone Settings → Apps → Hayek Wallet → Notifications.")
    }
    Text(L("Upozornění na platby", "Payment alerts"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(L("Dát vědět, když přijdou bitcoiny – i když je aplikace zavřená. Telefon se asi každých 15 minut podívá na váš uzel ", "Let me know when bitcoin arrives – even when the app is closed. About every 15 minutes the phone checks your node ") +
            L("(jen veřejná data, bez klíčů). Mimo domov jen se zapnutou VPN.", "(public data only, no keys). Away from home only with VPN on."),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = vm.notifyEnabled, enabled = !vm.busy, onCheckedChange = { on ->
            if (on && android.os.Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            else vm.setNotify(on)
        })
    }
}

/** Název peněženky do jména souboru (bez diakritiky a zvláštních znaků). */
fun fileSafe(name: String): String =
    java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "penezenka" }
