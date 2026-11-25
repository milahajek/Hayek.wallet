package io.github.milahajek.hayek

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private data class IntroPage(val icon: ImageVector?, val title: String, val text: String, val points: List<String> = emptyList())

private fun pages() = listOf(
    IntroPage(
        null, L("Vítejte v Hayek Wallet", "Welcome to Hayek Wallet"),
        L("Bitcoinová peněženka, ve které peníze opravdu patří jen vám. Žádný účet, žádná firma, žádný server, který by je mohl zablokovat.", "A bitcoin wallet where the money truly belongs only to you. No account, no company, no server that could block it."),
        listOf(
            L("Klíče k penězům vznikají a zůstávají jen ve vašem telefonu.", "The keys to your money are created and stay only in your phone."),
            L("Nikdo – ani autor aplikace – k nim nemá přístup.", "Nobody – not even the app's author – has access to them."),
            L("Za to ale nesete odpovědnost: když o klíče přijdete, nikdo vám je neobnoví.", "But you carry the responsibility: if you lose the keys, nobody can restore them for you."),
        )
    ),
    IntroPage(
        Icons.Default.Casino, L("Proč házet kostkou?", "Why roll dice?"),
        L("Celá bezpečnost peněženky stojí na jednom náhodném čísle. Kdo by ho uhodl, má vaše peníze.", "The whole security of the wallet rests on one random number. Whoever guessed it would have your money."),
        listOf(
            L("Generátor náhody v telefonu nevidíte. Chyba v něm (už se to v minulosti stalo) nebo škodlivý software by mohly čísla oslabit – a nikdy byste se to nedozvěděli.", "You can't see the phone's random number generator. A bug in it (it has happened before) or malware could weaken the numbers – and you would never find out."),
            L("Kostka je fyzická náhoda, kterou máte pod kontrolou. Nikdo ji nemůže na dálku ovlivnit.", "A die is physical randomness under your control. Nobody can influence it remotely."),
            L("99 hodů = téměř 256 bitů náhody. To je víc možností, než je atomů ve viditelném vesmíru – uhodnout je nelze.", "99 rolls = almost 256 bits of randomness. That's more possibilities than atoms in the visible universe – impossible to guess."),
            L("Stejný postup používají hardwarové peněženky Coldcard a SeedSigner, takže výsledek si můžete ověřit i jinde.", "Coldcard and SeedSigner hardware wallets use the same method, so you can verify the result elsewhere too."),
        )
    ),
    IntroPage(
        Icons.Default.EditNote, L("24 slov = vaše peníze", "24 words = your money"),
        L("Z hodů vznikne 24 slov (záloha, „seed“). Kdokoli je zná, může vaše bitcoiny utratit – odkudkoli na světě.", "The rolls produce 24 words (backup, „seed“). Anyone who knows them can spend your bitcoin – from anywhere in the world."),
        listOf(
            L("Opište je ručně na papír. Nikdy je nefoťte, neukládejte do počítače, cloudu ani nikomu neposílejte.", "Write them down by hand on paper. Never photograph them, store them on a computer or in the cloud, or send them to anyone."),
            L("Papír schovejte na bezpečné místo (ideálně dvě kopie na různých místech).", "Keep the paper somewhere safe (ideally two copies in different places)."),
            L("Ztratíte-li telefon, ze slov peněženku obnovíte v jakékoli peněžence (BIP39).", "If you lose your phone, you can restore the wallet from the words in any wallet (BIP39)."),
            L("Nikdo seriózní se vás na ně nikdy nezeptá. Kdo o ně žádá, je podvodník.", "No legitimate person will ever ask you for them. Anyone who does is a scammer."),
        )
    ),
    IntroPage(
        Icons.Default.Dns, L("Váš vlastní uzel", "Your own node"),
        L("Peněženka potřebuje zjistit, co se děje na blockchainu. Ptá se jen vašeho vlastního uzlu (např. RaspiBlitz s Fulcrumem).", "The wallet needs to know what's happening on the blockchain. It asks only your own node (e.g. RaspiBlitz with Fulcrum)."),
        listOf(
            L("Cizí server by viděl všechny vaše adresy a zůstatek – a mohl by vám lhát.", "A foreign server would see all your addresses and balance – and could lie to you."),
            L("Váš uzel si vše ověřuje sám: „Don't trust, verify.“", "Your node verifies everything itself: „Don't trust, verify.“"),
            L("Kdo uzel nemá, může použít veřejný server – s vědomím, že ten vidí jeho adresy.", "Without a node you can use a public server – knowing that it sees your addresses."),
            L("Volitelně: Lightning přes LND na stejném uzlu pro rychlé a levné platby.", "Optional: Lightning via LND on the same node for fast, cheap payments."),
        )
    ),
    IntroPage(
        Icons.Default.Shield, L("Začněte s malými částkami", "Start with small amounts"),
        L("Hayek Wallet je experimentální software s otevřeným kódem, bez bezpečnostního auditu.", "Hayek Wallet is experimental open-source software without a security audit."),
        listOf(
            L("Používejte ji na drobné částky, na úspory patří hardwarová peněženka.", "Use it for small amounts; savings belong on a hardware wallet."),
            L("Platby v bitcoinu nejde vrátit – vždy zkontrolujte adresu a částku.", "Bitcoin payments can't be reversed – always check the address and amount."),
            L("Telefon chraňte zámkem obrazovky; peněženka navíc vyžaduje otisk prstu.", "Protect your phone with a screen lock; the wallet also requires your fingerprint."),
        )
    ),
)

@Composable
fun IntroScreen(vm: WalletViewModel, back: Screen) {
    val pager = rememberPagerState { pages().size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages().lastIndex
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Image(
            painterResource(R.drawable.ic_logo), "Logo Hayek Wallet",
            Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp, bottom = 4.dp).size(56.dp)
        )
        HorizontalPager(pager, Modifier.weight(1f)) { i ->
            val p = pages()[i]
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                p.icon?.let { icon ->
                    Box(Modifier.size(84.dp).clip(CircleShape).background(HayekOrange.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = HayekOrange, modifier = Modifier.size(46.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Text(p.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(p.text, fontSize = 16.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                if (p.points.isNotEmpty()) GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        p.points.forEach { t ->
                            Row {
                                Text("•", color = HayekOrange, fontWeight = FontWeight.Bold, modifier = Modifier.width(16.dp))
                                Text(t, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages().size) { i ->
                Box(Modifier.padding(4.dp).size(if (i == pager.currentPage) 10.dp else 8.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) HayekOrange else MaterialTheme.colorScheme.outlineVariant))
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!last) TextButton(onClick = { vm.finishIntro(back) }) { Text(L("Přeskočit", "Skip")) }
            Spacer(Modifier.weight(1f))
            HayekButton(onClick = {
                if (last) vm.finishIntro(back) else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            }, Modifier.height(52.dp)) {
                Text(if (last) L("Rozumím, pojďme na to", "Got it, let's go") else L("Další", "Next"))
                if (!last) { Spacer(Modifier.width(6.dp)); Icon(Icons.Default.ChevronRight, null) }
            }
        }
    }
}
