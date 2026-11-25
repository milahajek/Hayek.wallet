package io.github.milahajek.hayek

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
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
    var bumpChoice by remember(tx.txid) { mutableStateOf(FeeChoice.FAST) }
    LaunchedEffect(vm.bumpSuggested) {
        val sug = vm.bumpSuggested ?: return@LaunchedEffect
        if (rate.isBlank()) {
            val fast = vm.feeEstimates?.fast
            if (fast != null && fast >= sug) { bumpChoice = FeeChoice.FAST; rate = fast.toString() }
            else { bumpChoice = FeeChoice.CUSTOM; rate = sug.toString() }
        }
    }
    val incoming = tx.deltaSat >= 0
    val b = vm.bumpPrepared
    fun close() { vm.setNote(tx.txid, note); vm.txDetail = null; vm.bumpPrepared = null }

    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(Modifier.fillMaxWidth(0.94f).padding(vertical = 24.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(if (incoming) L("Přijatá platba", "Received payment") else L("Odeslaná platba", "Sent payment"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(vm.amount(tx.deltaSat, sign = true), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                val date = tx.time?.let { fmtDate(it * 1000, time = true) }
                Text(
                    if (tx.confirmed) L("Potvrzeno v bloku ${tx.height}", "Confirmed in block ${tx.height}") + (date?.let { " · $it" } ?: "")
                    else L("Čeká na potvrzení", "Waiting for confirmation") + (date?.let { L(" · odesláno $it", " · sent $it") } ?: ""),
                    fontSize = 14.sp, color = if (tx.confirmed) MaterialTheme.colorScheme.onSurface else HayekOrange
                )
                if (tx.feeSat != null && !incoming) Text(L("Poplatek: ${formatSat(tx.feeSat)}", "Fee: ${formatSat(tx.feeSat)}") + (tx.feeRate?.let { " ($it sat/vB)" } ?: ""), fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                SelectionContainer { Text(tx.txid, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note, onValueChange = { note = it.take(200) },
                    label = { Text(L("Poznámka (jen v tomto telefonu)", "Note (only on this phone)")) }, placeholder = { Text(L("např. nájem, od bráchy…", "e.g. rent, from my brother…")) },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3
                )

                if (tx.canBump && !bumping && b == null) {
                    Spacer(Modifier.height(12.dp))
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(L("Visí transakce dlouho?", "Transaction stuck for long?"), fontWeight = FontWeight.SemiBold)
                            Text(L("Můžete ji urychlit vyšším poplatkem. Příjemce dostane stejnou částku, původní transakce se nahradí novou.", "You can speed it up with a higher fee. The recipient gets the same amount, the original transaction is replaced by a new one."), fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            HayekButton(onClick = { bumping = true }, Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Speed, null); Spacer(Modifier.width(6.dp)); Text(L("Urychlit (zvýšit poplatek)", "Speed up (raise fee)"))
                            }
                        }
                    }
                }
                if (bumping && b == null) {
                    Spacer(Modifier.height(12.dp))
                    Text(L("Teď zaplaceno: ${tx.feeRate ?: "?"} sat/vB. Nový poplatek musí být vyšší.", "Paid now: ${tx.feeRate ?: "?"} sat/vB. The new fee must be higher."), fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    val minRate = (tx.feeRate ?: 1) + 1
                    FeePicker(
                        estimates = vm.feeEstimates, choice = bumpChoice, rate = rate, minRate = minRate,
                        onChoice = { c -> bumpChoice = c; vm.feeEstimates?.rate(c)?.let { rate = it.toString() } },
                        onCustomRate = { rate = it },
                        loading = vm.bumpSuggested == null
                    )
                    Spacer(Modifier.height(8.dp))
                    HayekButton(onClick = { vm.prepareBump(rate) }, enabled = rate.isNotBlank() && !vm.busy, modifier = Modifier.fillMaxWidth()) { Text(L("Spočítat", "Calculate")) }
                }
                if (b != null) {
                    Spacer(Modifier.height(12.dp))
                    GlassCard(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(L("Zkontrolujte urychlení", "Review the speed-up"), fontWeight = FontWeight.Bold)
                            Text(L("Příjemce dostane: ${formatSat(b.recipientSat)}", "Recipient gets: ${formatSat(b.recipientSat)}"))
                            Text(L("Poplatek: ${formatSat(b.oldFeeSat)} → ${formatSat(b.newFeeSat)} (${b.feeRate} sat/vB)", "Fee: ${formatSat(b.oldFeeSat)} → ${formatSat(b.newFeeSat)} (${b.feeRate} sat/vB)"))
                            Text(L("Zaplatíte navíc: ${formatSat(b.newFeeSat - b.oldFeeSat)}", "You pay extra: ${formatSat(b.newFeeSat - b.oldFeeSat)}"), fontWeight = FontWeight.SemiBold)
                            if (b.mixedInputIds.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(L("⚠ Na vyšší poplatek nestačí drobné z této platby ani mince stejné skupiny. Přibere se ${b.mixedInputIds.size}× mince z JINÉ skupiny (KYC / bez KYC) – tím se obě skupiny na blockchainu propojí. Když to nechcete, zvolte nižší poplatek nebo urychlení zrušte.",
                                    "⚠ The change from this payment and coins of the same group aren't enough for the higher fee. ${b.mixedInputIds.size} coin(s) from a DIFFERENT group (KYC / no KYC) will be added – this links both groups on the blockchain. If you don't want that, choose a lower fee or cancel the speed-up."),
                                    color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    HayekButton(
                        onClick = { vm.setNote(tx.txid, note); act.auth(vm, L("Urychlit transakci", "Speed up transaction"), strong = true) { vm.broadcastBump() } },
                        enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(6.dp)); Text(L("Podepsat a odeslat", "Sign and send")) }
                    TextButton(onClick = { vm.bumpPrepared = null }, Modifier.fillMaxWidth()) { Text(L("Změnit poplatek", "Change fee")) }
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(onClick = { openTx(ctx, vm, vm.txLink(tx.txid)) }, Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text("Mempool")
                    }
                    HayekButton(onClick = { close() }, Modifier.weight(1f)) { Text(L("Hotovo", "Done")) }
                }
            }
        }
    }
}
