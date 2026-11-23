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

    Text("Štítky a poznámky", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text("Poznámky k transakcím, značky Bez KYC / KYC a zmrazené mince jsou jen v tomto telefonu – obnovovací slova je neobsahují. " +
        "Zálohujte je do souboru (standard BIP-329, umí ho i Sparrow Wallet).",
        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassButton(onClick = { askExport = true }, Modifier.weight(1f), enabled = !vm.busy) {
            Icon(Icons.Default.Upload, null); Spacer(Modifier.width(6.dp)); Text("Zálohovat")
        }
        GlassButton(onClick = { openLauncher.launch(arrayOf("*/*")) }, Modifier.weight(1f), enabled = !vm.busy) {
            Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("Načíst")
        }
    }

    if (askExport) ExportLabelsDialog(
        count = vm.labelsForExport().size,
        onDismiss = { askExport = false },
        onConfirm = { pw ->
            askExport = false; exportPw = pw
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            saveLauncher.launch(if (pw.isEmpty()) "hayek-stitky-$day.jsonl" else "hayek-stitky-$day.hayek")
        },
    )
    vm.labelsImportNeedsPassword?.let { uri ->
        PasswordDialog(
            title = "Zašifrovaná záloha",
            text = "Zadejte heslo, kterým jste zálohu zašifroval.",
            confirm = "Načíst",
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
        title = { Text("Zálohovat štítky") },
        text = {
            Column {
                Text("Položek k uložení: $count. Soubor neobsahuje klíče, ale prozrazuje historii vašich plateb.", fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.clickable { encrypt = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(encrypt, { encrypt = true }); Text("Zašifrovat heslem (doporučeno)", fontSize = 14.sp)
                }
                Row(Modifier.clickable { encrypt = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(!encrypt, { encrypt = false }); Text("Nešifrovaně – pro Sparrow a jiné peněženky", fontSize = 14.sp)
                }
                if (encrypt) {
                    OutlinedTextField(pw, { pw = it }, label = { Text("Heslo (aspoň 8 znaků)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pw2, { pw2 = it }, label = { Text("Heslo znovu") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = pw2.isNotEmpty() && pw != pw2, modifier = Modifier.fillMaxWidth())
                    Text("Heslo si zapamatujte – bez něj zálohu nikdo neotevře, ani vy.", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(if (encrypt) pw else "") }, enabled = pwOk && count > 0) { Text("Vybrat místo uložení") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
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
                OutlinedTextField(pw, { pw = it }, label = { Text("Heslo") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pw) }, enabled = pw.isNotEmpty()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}
