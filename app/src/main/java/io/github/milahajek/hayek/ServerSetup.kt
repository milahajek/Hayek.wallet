package io.github.milahajek.hayek

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Výběr veřejného serveru: nejdřív jasně, co jeho provozovatel uvidí, a teprve po souhlasu seznam.
 * Společné pro Nastavení i první spuštění.
 */
@Composable
fun PublicServerPicker(vm: WalletViewModel, onUse: (name: String, url: String) -> Unit) {
    var understood by remember { mutableStateOf(false) }
    Text(
        L("Server cizího provozovatele uvidí:\n• všechny adresy této peněženky a že patří k sobě,\n• zůstatek a celou historii plateb,\n• vaši IP adresu.\n\n", "The operator of a foreign server will see:\n• all addresses of this wallet and that they belong together,\n• the balance and full payment history,\n• your IP address.\n\n") +
            L("Nedostane se ke klíčům ani ke slovům a nemůže nic utratit. Mohl by ale zatajit transakci nebo ukázat nesprávný zůstatek.\n\n", "They can't reach the keys or words and can't spend anything. But they could hide a transaction or show a wrong balance.\n\n") +
            L("Seznam serverů je převzatý z peněženky Sparrow.", "The server list is taken from Sparrow wallet."),
        fontSize = 14.sp
    )
    Row(Modifier.fillMaxWidth().clickable { understood = !understood }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = understood, onCheckedChange = { understood = it })
        Text(L("Rozumím a přesto chci veřejný server", "I understand and still want a public server"), fontSize = 14.sp)
    }
    if (understood) {
        Spacer(Modifier.height(4.dp))
        Text(L("„Vyzkoušet“ server jen otestuje (neposílá mu vaše adresy). „Použít“ ho uloží.", "„Test“ only tests the server (doesn't send it your addresses). „Use“ saves it."),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PUBLIC_SERVERS.forEach { (name, u) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(u.removePrefix("ssl://"), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                TextButton(onClick = { vm.testServer(u) }, enabled = !vm.busy) { Text(L("Vyzkoušet", "Test")) }
                TextButton(onClick = { onUse(name, u) }) { Text(L("Použít", "Use")) }
            }
        }
    }
}

/**
 * První spuštění: kam se má peněženka připojit. Vlastní uzel (doporučeno), nebo vědomě zvolený veřejný server –
 * aplikace se sama od sebe k žádnému cizímu serveru nepřipojí.
 */
@Composable
fun ServerSetupScreen(vm: WalletViewModel) {
    var mode by remember { mutableStateOf(0) }   // 0 = nevybráno, 1 = vlastní uzel, 2 = veřejný server
    var url by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(L("Kam se má peněženka připojit?", "Where should the wallet connect?"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(L("Peněženka potřebuje server, který jí řekne zůstatek a rozešle vaše platby do sítě. Klíče ani slova mu nikdy neposílá.",
            "The wallet needs a server that tells it the balance and relays your payments to the network. It never sends it your keys or words."))
        Spacer(Modifier.height(16.dp))

        GlassCard(Modifier.fillMaxWidth().clickable { mode = 1 }) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Home, null, tint = HayekOrange); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(L("Mám vlastní uzel", "I have my own node"), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(L("Doporučeno · nejvíc soukromí", "Recommended · most private"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    RadioButton(selected = mode == 1, onClick = { mode = 1 })
                }
                if (mode == 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(L("Zadejte adresu svého Electrum serveru. Na RaspiBlitzu: Fulcrum ssl://IP:50022, ElectRS ssl://IP:50002. Telefon musí být ve stejné síti jako uzel, nebo připojený domů přes VPN.",
                        "Enter your Electrum server address. On RaspiBlitz: Fulcrum ssl://IP:50022, ElectRS ssl://IP:50002. The phone must be on the same network as the node, or connected home via VPN."), fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = url, onValueChange = { url = it.trim() }, label = { Text(L("Adresa serveru", "Server address")) },
                        placeholder = { Text("ssl://IP-uzlu:50002") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlassButton(onClick = { vm.testServer(url) }, enabled = url.isNotBlank() && !vm.busy, modifier = Modifier.weight(1f)) { Text(L("Vyzkoušet", "Test")) }
                        HayekButton(onClick = { vm.chooseServer(url) }, enabled = url.isNotBlank(), modifier = Modifier.weight(1f)) { Text(L("Použít", "Use")) }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth().clickable { mode = 2 }) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Public, null, tint = HayekOrange); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(L("Nemám uzel – veřejný server", "No node – public server"), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(L("Funguje hned · méně soukromí · bez Lightningu", "Works right away · less private · no Lightning"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    RadioButton(selected = mode == 2, onClick = { mode = 2 })
                }
                if (mode == 2) {
                    Spacer(Modifier.height(8.dp))
                    PublicServerPicker(vm) { name, u ->
                        vm.chooseServer(u)
                        vm.message = L("Peněženka bude používat veřejný server $name.\nNa vlastní uzel můžete kdykoli přejít v ⚙ Nastavení.", "The wallet will use the public server $name.\nYou can switch to your own node anytime in ⚙ Settings.")
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { vm.skipServerSetup() }, Modifier.fillMaxWidth()) { Text(L("Nastavím později", "I'll set it up later")) }
    }
}
