package io.github.milahajek.hayek

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity

/**
 * V telefonu se změnily otisky prstů (někdo mohl přidat vlastní – stačí mu k tomu znát PIN).
 * Než peněženka otisku znovu uvěří, musí uživatel prokázat, že zná svá slova.
 */
@Composable
fun FingerprintCheckDialog(vm: WalletViewModel, act: FragmentActivity) {
    val words = vm.seedWords?.split(" ")
    val idx = remember { (words?.indices?.shuffled()?.take(3)?.sorted()) ?: emptyList() }
    val answers = remember { mutableStateMapOf<Int, String>() }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { vm.fingerprintCheck = null },
        icon = { Icon(Icons.Default.Fingerprint, null) },
        title = { Text("V telefonu se změnily otisky prstů") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Od posledního použití někdo přidal nebo odebral otisk prstu. Kdo zná váš PIN, mohl by si tak přidat vlastní otisk a odesílat vaše peníze.", fontSize = 14.sp)
                if (words != null) {
                    Text("Pokud jste otisk měnil vy, potvrďte to zadáním těchto slov ze své zálohy:", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    IncognitoKeyboard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            idx.forEach { i ->
                                OutlinedTextField(
                                    value = answers[i] ?: "", onValueChange = { answers[i] = it.trim().lowercase(); wrong = false },
                                    label = { Text("${i + 1}. slovo") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    if (wrong) Text("Slova nesouhlasí.", color = MaterialTheme.colorScheme.error)
                } else {
                    Text("Sledovací peněženka nemá slova. Pokud jste otisk měnil vy, potvrďte to PINem telefonu.", fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (words != null) {
                    if (!vm.confirmFingerprintCheck(idx.associateWith { answers[it] ?: "" })) wrong = true
                } else {
                    authenticate(act, "Potvrdit změnu otisků", strong = false, onOk = {
                        vm.store.resetBioCanary(); val next = vm.fingerprintCheck; vm.fingerprintCheck = null; next?.invoke()
                    }, onFail = { vm.message = "Ověření se nezdařilo: $it" })
                }
            }, enabled = words == null || idx.all { !answers[it].isNullOrBlank() }) { Text("Potvrdit") }
        },
        dismissButton = { TextButton(onClick = { vm.fingerprintCheck = null }) { Text("Zrušit") } }
    )
}
