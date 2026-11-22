package io.github.milahajek.hayek

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private data class IntroPage(val icon: ImageVector, val title: String, val text: String, val points: List<String> = emptyList())

private val pages = listOf(
    IntroPage(
        Icons.Default.AccountBalanceWallet, "Vítejte v Hayek Wallet",
        "Bitcoinová peněženka, ve které peníze opravdu patří jen vám. Žádný účet, žádná firma, žádný server, který by je mohl zablokovat.",
        listOf(
            "Klíče k penězům vznikají a zůstávají jen ve vašem telefonu.",
            "Nikdo – ani autor aplikace – k nim nemá přístup.",
            "Za to ale nesete odpovědnost: když o klíče přijdete, nikdo vám je neobnoví.",
        )
    ),
    IntroPage(
        Icons.Default.Casino, "Proč házet kostkou?",
        "Celá bezpečnost peněženky stojí na jednom náhodném čísle. Kdo by ho uhodl, má vaše peníze.",
        listOf(
            "Generátor náhody v telefonu nevidíte. Chyba v něm (už se to v minulosti stalo) nebo škodlivý software by mohly čísla oslabit – a nikdy byste se to nedozvěděli.",
            "Kostka je fyzická náhoda, kterou máte pod kontrolou. Nikdo ji nemůže na dálku ovlivnit.",
            "99 hodů = 256 bitů náhody. To je víc možností, než je atomů ve viditelném vesmíru – uhodnout je nelze.",
            "Stejný postup používají hardwarové peněženky Coldcard a SeedSigner, takže výsledek si můžete ověřit i jinde.",
        )
    ),
    IntroPage(
        Icons.Default.EditNote, "24 slov = vaše peníze",
        "Z hodů vznikne 24 slov (záloha, „seed“). Kdokoli je zná, může vaše bitcoiny utratit – odkudkoli na světě.",
        listOf(
            "Opište je ručně na papír. Nikdy je nefoťte, neukládejte do počítače, cloudu ani nikomu neposílejte.",
            "Papír schovejte na bezpečné místo (ideálně dvě kopie na různých místech).",
            "Ztratíte-li telefon, ze slov peněženku obnovíte v jakékoli peněžence (BIP39).",
            "Nikdo seriózní se vás na ně nikdy nezeptá. Kdo o ně žádá, je podvodník.",
        )
    ),
    IntroPage(
        Icons.Default.Dns, "Váš vlastní uzel",
        "Peněženka potřebuje zjistit, co se děje na blockchainu. Ptá se jen vašeho vlastního uzlu (např. RaspiBlitz s Fulcrumem).",
        listOf(
            "Cizí server by viděl všechny vaše adresy a zůstatek – a mohl by vám lhát.",
            "Váš uzel si vše ověřuje sám: „Don't trust, verify.“",
            "Kdo uzel nemá, může použít veřejný server – s vědomím, že ten vidí jeho adresy.",
            "Volitelně: Lightning přes LND na stejném uzlu pro rychlé a levné platby.",
        )
    ),
    IntroPage(
        Icons.Default.Shield, "Začněte s malými částkami",
        "Hayek Wallet je experimentální software s otevřeným kódem, bez bezpečnostního auditu.",
        listOf(
            "Používejte ji na drobné částky, na úspory patří hardwarová peněženka.",
            "Platby v bitcoinu nejde vrátit – vždy zkontrolujte adresu a částku.",
            "Telefon chraňte zámkem obrazovky; peněženka navíc vyžaduje otisk prstu.",
        )
    ),
)

@Composable
fun IntroScreen(vm: WalletViewModel, back: Screen) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        HorizontalPager(pager, Modifier.weight(1f)) { i ->
            val p = pages[i]
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(84.dp).clip(CircleShape).background(HayekOrange.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(p.icon, null, tint = HayekOrange, modifier = Modifier.size(46.dp))
                }
                Spacer(Modifier.height(16.dp))
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
            repeat(pages.size) { i ->
                Box(Modifier.padding(4.dp).size(if (i == pager.currentPage) 10.dp else 8.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) HayekOrange else MaterialTheme.colorScheme.outlineVariant))
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!last) TextButton(onClick = { vm.finishIntro(back) }) { Text("Přeskočit") }
            Spacer(Modifier.weight(1f))
            HayekButton(onClick = {
                if (last) vm.finishIntro(back) else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            }, Modifier.height(52.dp)) {
                Text(if (last) "Rozumím, pojďme na to" else "Další")
                if (!last) { Spacer(Modifier.width(6.dp)); Icon(Icons.Default.ChevronRight, null) }
            }
        }
    }
}
