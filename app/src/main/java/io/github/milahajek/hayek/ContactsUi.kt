package io.github.milahajek.hayek

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun short(a: String) = a.take(10) + "…" + a.takeLast(6)

/** Výběr příjemce z adresáře (onPick = null → jen správa). */
@Composable
fun ContactsDialog(vm: WalletViewModel, onDismiss: () -> Unit, onPick: ((Contact) -> Unit)? = null) {
    var edit by remember { mutableStateOf<Contact?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Contact?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Person, null) },
        title = { Text(if (onPick != null) "Vybrat příjemce" else "Adresář") },
        text = {
            if (vm.contacts.isEmpty()) Text(
                "Adresář je prázdný. Adresu uložíte při odesílání tlačítkem „Uložit do adresáře“, nebo ji přidejte tady.", fontSize = 14.sp)
            else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(vm.contacts, key = { it.address }) { c ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.clickable { if (onPick != null) onPick(c) else edit = c },
                        headlineContent = { Text(c.name, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text(short(c.address), fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                        trailingContent = if (onPick == null) { { IconButton(onClick = { confirmDelete = c }) { Icon(Icons.Default.Delete, "Smazat") } } } else null,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zavřít") } },
        dismissButton = { TextButton(onClick = { adding = true }) { Text("Přidat kontakt") } },
    )
    if (adding || edit != null) SaveContactDialog(vm, edit?.address ?: "", edit?.name ?: "", editing = edit != null) { adding = false; edit = null }
    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Smazat kontakt?") },
            text = { Text("${c.name}\n${short(c.address)}") },
            confirmButton = { TextButton(onClick = { vm.removeContact(c); confirmDelete = null }) { Text("Smazat") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Zrušit") } },
        )
    }
}

/** Uložení / přejmenování kontaktu. */
@Composable
fun SaveContactDialog(vm: WalletViewModel, address: String, name: String = "", editing: Boolean = false, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var n by remember { mutableStateOf(name) }
    var a by remember { mutableStateOf(address) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (editing) "Upravit kontakt" else "Uložit do adresáře") },
        text = {
            Column {
                OutlinedTextField(n, { n = it.take(60); err = null }, label = { Text("Jméno") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(a, { a = it.trim(); err = null }, label = { Text("Bitcoinová adresa") }, enabled = !editing,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp), modifier = Modifier.fillMaxWidth(),
                    trailingIcon = if (!editing) { { IconButton(onClick = { paste(ctx)?.let { p -> a = parsePaymentQr(p)?.address ?: p.trim() } }) { Icon(Icons.Default.ContentPaste, "Vložit") } } } else null)
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                if (!editing) Text("Adresu si před uložením ověřte s příjemcem – adresář ji pak bude nabízet při každé platbě.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { err = vm.saveContact(n, a); if (err == null) onDone() }) { Text("Uložit") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Zrušit") } },
    )
}

/** Jméno kontaktu pod adresou příjemce (nebo nabídka uložení). */
@Composable
fun ContactHint(vm: WalletViewModel, address: String) {
    if (Contacts.normalize(address) == null) return
    val c = vm.contactFor(address)
    var save by remember { mutableStateOf(false) }
    if (c != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Icon(Icons.Default.Person, null, Modifier.size(18.dp), tint = HayekOrange)
        Spacer(Modifier.width(6.dp))
        Text(c.name, fontWeight = FontWeight.SemiBold)
        Text("  · z adresáře", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else TextButton(onClick = { save = true }) { Text("+ Uložit do adresáře") }
    if (save) SaveContactDialog(vm, address) { save = false }
}
