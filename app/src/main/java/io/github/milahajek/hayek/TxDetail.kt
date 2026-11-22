package io.github.milahajek.hayek

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.FragmentActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TxDetailDialog(tx: TxItem, vm: WalletViewModel, act: FragmentActivity) {
    val ctx = LocalContext.current
    var note by remember(tx.txid) { mutableStateOf(vm.note(tx.txid)) }
    var bumping by remember(tx.txid) { mutableStateOf(false) }
    var rate by remember(tx.txid) { mutableStateOf("") }
    LaunchedEffect(vm.bumpSuggested) { if (rate.isBlank()) vm.bumpSuggested?.let { rate = it.toString() } }
    val incoming = tx.deltaSat >= 0
    val b = vm.bumpPrepared
    fun close() { vm.setNote(tx.txid, note); vm.txDetail = null; vm.bumpPrepared = null }

    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(Modifier.fillMaxWidth(0.94f).padding(vertical = 24.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(if (incoming) "Přijatá platba" else "Odeslaná platba", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(vm.amount(tx.deltaSat, sign = true), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                val date = tx.time?.let { SimpleDateFormat("d. M. yyyy HH:mm", Locale.forLanguageTag("cs")).format(Date(it * 1000)) }
                Text(
                    if (tx.confirmed) "Potvrzeno v bloku ${tx.height}" + (date?.let { " · $it" } ?: "")
                    else "Čeká na potvrzení" + (date?.let { " · odesláno $it" } ?: ""),
                    fontSize = 14.sp, color = if (tx.confirmed) MaterialTheme.colorScheme.onSurface else HayekOrange
                )
                if (tx.feeSat != null && !incoming) Text("Poplatek: ${formatSat(tx.feeSat)}" + (tx.feeRate?.let { " ($it sat/vB)" } ?: ""), fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                SelectionContainer { Text(tx.txid, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note, onValueChange = { note = it.take(200) },
                    label = { Text("Poznámka (jen v tomto telefonu)") }, placeholder = { Text("např. nájem, od bráchy…") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3
                )

                if (tx.canBump && !bumping && b == null) {
                    Spacer(Modifier.height(12.dp))
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Visí transakce dlouho?", fontWeight = FontWeight.SemiBold)
                            Text("Můžete ji urychlit vyšším poplatkem. Příjemce dostane stejnou částku, původní transakce se nahradí novou.", fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            HayekButton(onClick = { bumping = true }, Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Speed, null); Spacer(Modifier.width(6.dp)); Text("Urychlit (zvýšit poplatek)")
                            }
                        }
                    }
                }
                if (bumping && b == null) {
                    Spacer(Modifier.height(12.dp))
                    Text("Nový poplatek", fontWeight = FontWeight.SemiBold)
                    Text("Teď: ${tx.feeRate ?: "?"} sat/vB" + (vm.bumpSuggested?.let { " · doporučeno vaším uzlem: $it sat/vB" } ?: ""), fontSize = 13.sp)
                    OutlinedTextField(
                        value = rate, onValueChange = { rate = it.filter(Char::isDigit) }, label = { Text("sat/vB") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Spacer(Modifier.height(8.dp))
                    HayekButton(onClick = { vm.prepareBump(rate) }, enabled = rate.isNotBlank() && !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Spočítat") }
                }
                if (b != null) {
                    Spacer(Modifier.height(12.dp))
                    GlassCard(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Zkontrolujte urychlení", fontWeight = FontWeight.Bold)
                            Text("Příjemce dostane: ${formatSat(b.recipientSat)}")
                            Text("Poplatek: ${formatSat(b.oldFeeSat)} → ${formatSat(b.newFeeSat)} (${b.feeRate} sat/vB)")
                            Text("Zaplatíte navíc: ${formatSat(b.newFeeSat - b.oldFeeSat)}", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    HayekButton(
                        onClick = { vm.setNote(tx.txid, note); act.auth(vm, "Urychlit transakci", strong = true) { vm.broadcastBump() } },
                        enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(6.dp)); Text("Podepsat a odeslat") }
                    TextButton(onClick = { vm.bumpPrepared = null }, Modifier.fillMaxWidth()) { Text("Změnit poplatek") }
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(onClick = { openTx(ctx, vm, vm.txLink(tx.txid)) }, Modifier.weight(1f)) {
                        Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text("Mempool")
                    }
                    HayekButton(onClick = { close() }, Modifier.weight(1f)) { Text("Hotovo") }
                }
            }
        }
    }
}
