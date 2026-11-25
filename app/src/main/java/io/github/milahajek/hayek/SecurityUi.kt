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
                Text(L("Od posledního použití někdo přidal nebo odebral otisk prstu. Kdo zná váš PIN, mohl by si tak přidat vlastní otisk (nebo otisky smazat) a odesílat vaše peníze.", "Since the last use someone added or removed a fingerprint. Whoever knows your PIN could add their own fingerprint this way (or delete the fingerprints) and send your money."), fontSize = 14.sp)
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

/**
 * První spojení s uzlem: jeho certifikátu aplikace uvěřila, aniž ho měla s čím porovnat (od teď přijme jen tento).
 * Ukážeme otisk, aby šel porovnat s uzlem – kdo by byl v síti právě při prvním spojení, mohl by se za uzel vydávat.
 */
@Composable
fun PinNoticeDialog(vm: WalletViewModel, hostPort: String, fingerprint: String) {
    AlertDialog(
        onDismissRequest = { vm.pinNotice = null },
        title = { Text(L("Certifikát uzlu uložen", "Node certificate saved")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L("První spojení s uzlem $hostPort. Peněženka si zapamatovala jeho certifikát a od teď se připojí jen k němu.",
                    "First connection to the node $hostPort. The wallet remembered its certificate and from now on connects only to it."), fontSize = 14.sp)
                Text(L("Otisk (SHA-256):", "Fingerprint (SHA-256):"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(fingerprint.chunked(24).joinToString("\n"), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp)
                }
                Text(L("Jste doma ve vlastní síti? Pak je vše v pořádku. Pro úplnou jistotu můžete otisk porovnat s uzlem – přes SSH na něm spusťte:",
                    "Are you at home on your own network? Then all is fine. To be completely sure, compare the fingerprint with the node – run this on it over SSH:"), fontSize = 13.sp)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text("openssl s_client -connect localhost:${hostPort.substringAfterLast(':')} </dev/null 2>/dev/null | openssl x509 -noout -fingerprint -sha256",
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.pinNotice = null }) { Text(L("Rozumím", "Got it")) } },
        dismissButton = { TextButton(onClick = { vm.rejectPin(hostPort) }) { Text(L("Nesedí – zapomenout", "Doesn't match – forget")) } }
    )
}
