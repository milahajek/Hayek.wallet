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
        title = { Text(if (onPick != null) L("Vybrat příjemce", "Choose recipient") else L("Adresář", "Address book")) },
        text = {
            if (vm.contacts.isEmpty()) Text(
                L("Adresář je prázdný. Adresu uložíte při odesílání tlačítkem „Uložit do adresáře“, nebo ji přidejte tady.", "The address book is empty. Save an address when sending with „Save to address book“, or add it here."), fontSize = 14.sp)
            else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(vm.contacts, key = { it.address }) { c ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.clickable { if (onPick != null) onPick(c) else edit = c },
                        headlineContent = { Text(c.name, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text(short(c.address), fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                        trailingContent = if (onPick == null) { { IconButton(onClick = { confirmDelete = c }) { Icon(Icons.Default.Delete, L("Smazat", "Delete")) } } } else null,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(L("Zavřít", "Close")) } },
        dismissButton = { TextButton(onClick = { adding = true }) { Text(L("Přidat kontakt", "Add contact")) } },
    )
    if (adding || edit != null) SaveContactDialog(vm, edit?.address ?: "", edit?.name ?: "", editing = edit != null) { adding = false; edit = null }
    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(L("Smazat kontakt?", "Delete contact?")) },
            text = { Text("${c.name}\n${short(c.address)}") },
            confirmButton = { TextButton(onClick = { vm.removeContact(c); confirmDelete = null }) { Text(L("Smazat", "Delete")) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(L("Zrušit", "Cancel")) } },
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
        title = { Text(if (editing) L("Upravit kontakt", "Edit contact") else L("Uložit do adresáře", "Save to address book")) },
        text = {
            Column {
                OutlinedTextField(n, { n = it.take(60); err = null }, label = { Text(L("Jméno", "Name")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(a, { a = it.trim(); err = null }, label = { Text(L("Bitcoinová adresa", "Bitcoin address")) }, enabled = !editing,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp), modifier = Modifier.fillMaxWidth(),
                    trailingIcon = if (!editing) { { IconButton(onClick = { paste(ctx)?.let { p -> a = parsePaymentQr(p)?.address ?: p.trim() } }) { Icon(Icons.Default.ContentPaste, L("Vložit", "Paste")) } } } else null)
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                if (!editing) Text(L("Adresu si před uložením ověřte s příjemcem – adresář ji pak bude nabízet při každé platbě.", "Verify the address with the recipient before saving – the address book will offer it for every payment."),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { err = vm.saveContact(n, a); if (err == null) onDone() }) { Text(L("Uložit", "Save")) } },
        dismissButton = { TextButton(onClick = onDone) { Text(L("Zrušit", "Cancel")) } },
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
        Text(L("  · z adresáře", "  · from address book"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else TextButton(onClick = { save = true }) { Text(L("+ Uložit do adresáře", "+ Save to address book")) }
    if (save) SaveContactDialog(vm, address) { save = false }
}
