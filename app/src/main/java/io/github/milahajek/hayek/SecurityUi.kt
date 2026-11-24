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
    val words = vm.checkWords?.split(" ")
    val idx = remember { (words?.indices?.shuffled()?.take(3)?.sorted()) ?: emptyList() }
    val answers = remember { mutableStateMapOf<Int, String>() }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { vm.fingerprintCheck = null },
        icon = { Icon(Icons.Default.Fingerprint, null) },
        title = { Text(L("V telefonu se změnily otisky prstů", "The fingerprints on this phone changed")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L("Od posledního použití někdo přidal nebo odebral otisk prstu. Kdo zná váš PIN, mohl by si tak přidat vlastní otisk a odesílat vaše peníze.", "Since the last use someone added or removed a fingerprint. Whoever knows your PIN could add their own fingerprint this way and send your money."), fontSize = 14.sp)
                if (words != null) {
                    Text(L("Pokud jste otisk měnil vy, potvrďte to zadáním těchto slov ze své zálohy:", "If you changed the fingerprint yourself, confirm it by entering these words from your backup:"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (vm.wallets.size > 1) vm.checkWalletId?.let { id -> Text(L("Slova peněženky „${vm.walletName(id)}“.", "Words of the wallet „${vm.walletName(id)}“."), fontSize = 13.sp) }
                    IncognitoKeyboard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            idx.forEach { i ->
                                OutlinedTextField(
                                    value = answers[i] ?: "", onValueChange = { answers[i] = it.trim().lowercase(); wrong = false },
                                    label = { Text(L("${i + 1}. slovo", "Word ${i + 1}")) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    if (wrong) Text(L("Slova nesouhlasí.", "The words don't match."), color = MaterialTheme.colorScheme.error)
                } else {
                    Text(L("Sledovací peněženka nemá slova. Pokud jste otisk měnil vy, potvrďte to PINem telefonu.", "A watch-only wallet has no words. If you changed the fingerprint yourself, confirm with the phone PIN."), fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (words != null) {
                    if (!vm.confirmFingerprintCheck(idx.associateWith { answers[it] ?: "" })) wrong = true
                } else {
                    authenticate(act, L("Potvrdit změnu otisků", "Confirm fingerprint change"), strong = false, onOk = {
                        vm.store.resetBioCanary(); val next = vm.fingerprintCheck; vm.fingerprintCheck = null; next?.invoke()
                    }, onFail = { vm.message = L("Ověření se nezdařilo: $it", "Verification failed: $it") })
                }
            }, enabled = words == null || idx.all { !answers[it].isNullOrBlank() }) { Text(L("Potvrdit", "Confirm")) }
        },
        dismissButton = { TextButton(onClick = { vm.fingerprintCheck = null }) { Text(L("Zrušit", "Cancel")) } }
    )
}
