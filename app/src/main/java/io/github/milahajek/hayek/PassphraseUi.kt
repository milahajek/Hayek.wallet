package io.github.milahajek.hayek

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Srozumitelné vysvětlení hesla navíc (passphrase). */
@Composable
fun PassphraseInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Key, null) },
        title = { Text(L("Co je heslo navíc (passphrase)?", "What is a passphrase?")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(L("Je to „25. slovo“, které si vymyslíte sami. Slova + heslo dohromady tvoří ÚPLNĚ JINOU peněženku než samotná slova.",
                    "It's a „25th word“ you make up yourself. Words + passphrase together make a COMPLETELY DIFFERENT wallet than the words alone."), fontWeight = FontWeight.SemiBold)
                Text(L("✔ K čemu je dobré", "✔ What it's good for"), fontWeight = FontWeight.Bold)
                Text(L("• Kdo najde nebo vyfotí váš papír se slovy, bez hesla nic nevezme.\n• „Návnada“: slova bez hesla otevřou jinou peněženku – můžete v ní nechat malou částku pro případ, že vás někdo donutí slova vydat.",
                    "• Whoever finds or photographs your paper with the words can't take anything without the passphrase.\n• „Decoy“: the words without the passphrase open a different wallet – you can keep a small amount there in case someone forces you to give up the words."), fontSize = 14.sp)
                Text(L("⚠ Riziko", "⚠ The risk"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                Text(L("• Když heslo zapomenete, peníze jsou NENÁVRATNĚ pryč. Nikdo je neobnoví – ani autor aplikace, ani nikdo jiný.\n• Záleží na každém znaku: velká/malá písmena, mezery, diakritika. Heslo s překlepem neukáže chybu – otevře jinou, prázdnou peněženku.",
                    "• If you forget the passphrase, the money is gone FOREVER. Nobody can recover it – not the app's author, not anyone.\n• Every character matters: upper/lower case, spaces, accents. A passphrase with a typo shows no error – it opens a different, empty wallet."), fontSize = 14.sp)
                Text(L("📝 Jak na to", "📝 How to do it"), fontWeight = FontWeight.Bold)
                Text(L("• Heslo si zapište a uložte JINDE než slova (jinak ztrácí smysl).\n• Zapište si i „otisk peněženky“ (8 znaků), který vám aplikace ukáže – podle něj při obnově poznáte, že heslo sedí.\n• V telefonu se heslo uloží zašifrované spolu se slovy, takže ho nebudete psát při každém otevření. Chrání hlavně papírovou zálohu, ne telefon.\n• Při obnově (i v jiné aplikaci – Sparrow, Coldcard…) zadáte stejná slova a stejné heslo.",
                    "• Write the passphrase down and keep it SOMEWHERE ELSE than the words (otherwise it's pointless).\n• Also write down the „wallet fingerprint“ (8 characters) the app shows you – it tells you the passphrase is right when restoring.\n• On the phone the passphrase is stored encrypted together with the words, so you won't type it every time. It mainly protects the paper backup, not the phone.\n• When restoring (also in another app – Sparrow, Coldcard…) enter the same words and the same passphrase."), fontSize = 14.sp)
                Text(L("Nejste si jistí? Heslo nepřidávejte – peněženka je bezpečná i bez něj.", "Not sure? Don't add a passphrase – the wallet is secure without it too."), fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(L("Rozumím", "Got it")) } }
    )
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String, show: Boolean, onToggle: () -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { if (it.length <= Passphrase.MAX + 5) onChange(it) }, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        trailingIcon = { IconButton(onClick = onToggle) { Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (show) L("Skrýt", "Hide") else L("Zobrazit", "Show")) } },
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
    )
}

/**
 * Volitelné heslo navíc. Vrací přes [onResult] platné heslo, prázdný řetězec = bez hesla, null = rozpracované/neplatné.
 * [confirm] = zadat dvakrát a potvrdit zápis (nová peněženka); při obnově stačí jednou.
 */
@Composable
fun PassphraseSection(confirm: Boolean, preview: String? = null, onResult: (String?) -> Unit) {
    var on by remember { mutableStateOf(preview != null) }
    var p1 by remember { mutableStateOf(preview ?: "") }
    var p2 by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var written by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    if (info) PassphraseInfoDialog { info = false }

    val problem = if (on) Passphrase.problem(p1) else null
    val mismatch = on && confirm && p2.isNotEmpty() && p1 != p2
    val result: String? = when {
        !on -> ""
        problem != null -> null
        confirm && (p1 != p2 || !written) -> null
        else -> p1
    }
    LaunchedEffect(result) { onResult(result) }

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth().clickable { on = !on; if (!on) { p1 = ""; p2 = ""; written = false } }, verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = on, onCheckedChange = { on = it; if (!it) { p1 = ""; p2 = ""; written = false } })
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(L("Heslo navíc (passphrase)", "Passphrase"), fontWeight = FontWeight.SemiBold)
                    Text(L("Volitelné, pro pokročilé", "Optional, for advanced users"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { info = true }) { Icon(Icons.Default.HelpOutline, L("Co to je?", "What is it?")) }
            }
            if (on) {
                Spacer(Modifier.height(6.dp))
                Text(L("Zapomenuté heslo = ztracené peníze. Překlep = jiná, prázdná peněženka.", "Forgotten passphrase = lost money. A typo = a different, empty wallet."),
                    color = MaterialTheme.colorScheme.error, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                SecretField(p1, { p1 = it }, L("Heslo", "Passphrase"), show) { show = !show }
                if (confirm) {
                    Spacer(Modifier.height(6.dp))
                    SecretField(p2, { p2 = it }, L("Heslo znovu", "Passphrase again"), show) { show = !show }
                }
                val note = when {
                    p1.isNotEmpty() && problem != null -> problem
                    mismatch -> L("Hesla se neshodují.", "The passphrases don't match.")
                    p1.isNotEmpty() -> Passphrase.warning(p1)
                    else -> null
                }
                note?.let { Text(it, fontSize = 13.sp, color = if (problem != null || mismatch) MaterialTheme.colorScheme.error else HayekOrange, modifier = Modifier.padding(top = 4.dp)) }
                if (confirm) Row(Modifier.fillMaxWidth().padding(top = 4.dp).clickable { written = !written }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(written, { written = it })
                    Text(L("Heslo mám zapsané na papíře, uložené jinde než slova", "I have the passphrase written on paper, stored apart from the words"), fontSize = 14.sp)
                }
            }
        }
    }
}
