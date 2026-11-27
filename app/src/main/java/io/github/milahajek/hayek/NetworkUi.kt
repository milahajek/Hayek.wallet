package io.github.milahajek.hayek

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/** Formátování pro Stav sítě i widget. */
object NetFmt {
    private val loc get() = if (Lang.english) Locale.US else Locale.forLanguageTag("cs")
    fun num(n: Long) = String.format(loc, "%,d", n)
    fun dec(d: Double, digits: Int) = String.format(loc, "%,.${digits}f", d)

    /** „před 4 min“, „před 1 h 12 min“. */
    fun ago(sec: Long): String = L("před ", "") + dur(sec.coerceAtLeast(0), short = true) + L("", " ago")

    /** Doba: „9 min 41 s“, „2 h 10 min“, „5 d 3 h“. */
    fun dur(sec: Long, short: Boolean = false): String {
        val d = sec / 86_400; val h = sec % 86_400 / 3600; val m = sec % 3600 / 60; val s = sec % 60
        return when {
            d > 0 -> "$d d" + if (h > 0) " $h h" else ""
            h > 0 -> "$h h" + if (m > 0) " $m min" else ""
            m > 0 -> "$m min" + if (!short && s > 0) " $s s" else ""
            else -> "$s s"
        }
    }

    fun hashrate(hs: Double): String = when {
        hs >= 1e21 -> dec(hs / 1e21, 2) + " ZH/s"
        hs >= 1e18 -> dec(hs / 1e18, 0) + " EH/s"
        else -> dec(hs / 1e15, 0) + " PH/s"
    }

    fun difficulty(d: Double): String = dec(d / 1e12, 2) + L(" bil.", " T")

    fun btc(sat: Long, digits: Int = 2) = dec(sat / 1e8, digits) + " BTC"

    fun signedPct(x: Double) = (if (x >= 0) "+" else "−") + dec(kotlin.math.abs(x) * 100, 1) + L(" %", "%")

    /** Přibližné datum: „květen 2028“ / „May 2028“. */
    fun monthYear(epochMs: Long): String = SimpleDateFormat("LLLL yyyy", loc).format(Date(epochMs))
    fun date(epochMs: Long): String = SimpleDateFormat(if (Lang.english) "d MMM yyyy" else "d. M. yyyy", loc).format(Date(epochMs))
}

private val Green = Color(0xFF2E9E5B)

@Composable
fun NetworkScreen(vm: WalletViewModel) {
    val s = vm.netStats
    val public = isPublicServer(vm.serverUrl)
    // Živé hodiny pro „před X min“ a automatická obnova každou minutu, dokud je obrazovka otevřená.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    LaunchedEffect(Unit) { while (true) { delay(60_000); vm.refreshNetwork() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(L("Stav sítě", "Network status"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        vm.netLoading && s == null -> L("Načítám z vašeho uzlu…", "Loading from your node…")
                        s != null -> L("Aktualizováno ", "Updated ") + NetFmt.ago((now - s.fetchedAt) / 1000)
                        else -> ""
                    },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (vm.netLoading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = HayekOrange)
            else IconButton(onClick = { vm.refreshNetwork() }) { Icon(Icons.Default.Refresh, L("Obnovit", "Refresh"), tint = HayekOrange) }
        }
        vm.netError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp)) }
        if (s == null) return@Column
        Spacer(Modifier.height(8.dp))

        ConnectionCard(vm, s, public)
        BlocksCard(s, now)
        MiningCard(s)
        RetargetCard(s, now)
        HalvingCard(s, now)
        SupplyCard(s)
        FeesCard(s)

        Text(
            L("Všechno na této stránce pochází z ${if (public) "veřejného serveru" else "vašeho uzlu"} a jsou to veřejné údaje o síti. Aplikace kvůli nim nikam jinam nechodí a nic o vaší peněžence neprozrazuje.",
                "Everything on this page comes from ${if (public) "the public server" else "your node"} and is public network data. The app contacts nothing else for it and reveals nothing about your wallet."),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)
        )
    }
}

@Composable
private fun StatCard(title: String, accent: Color = HayekOrange, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = valueColor)
    }
}

@Composable
private fun Big(value: String, unit: String = "", color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = color)
        if (unit.isNotEmpty()) Text(" $unit", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
    }
}

@Composable
private fun Progress(fraction: Float, color: Color, label: String? = null) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "progress")
    Column(Modifier.padding(vertical = 6.dp)) {
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(color.copy(alpha = 0.15f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(5.dp)).background(color))
        }
        label?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp)) }
    }
}

@Composable
private fun ConnectionCard(vm: WalletViewModel, s: NetworkStats, public: Boolean) {
    val addr = runCatching { ServerAddr.parse(vm.serverUrl) }.getOrNull()
    val color = if (public) HayekOrange else Green
    StatCard(L("Připojení", "Connection"), color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (vm.connected == false) MaterialTheme.colorScheme.error else color))
            Spacer(Modifier.width(8.dp))
            Text(if (public) L("Veřejný server", "Public server") else L("Váš vlastní uzel", "Your own node"), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        InfoRow(L("Software", "Software"), s.serverVersion)
        InfoRow(L("Protokol Electrum", "Electrum protocol"), s.protocol)
        InfoRow(L("Šifrování", "Encryption"), when {
            addr?.ssl != true -> L("žádné (tcp)", "none (tcp)")
            public -> L("TLS · ověřeno autoritou", "TLS · verified by CA")
            else -> L("TLS · otisk certifikátu ověřen", "TLS · certificate pinned")
        }, if (addr?.ssl == true) Green else MaterialTheme.colorScheme.error)
        InfoRow(L("Odezva", "Latency"), "${s.latencyMs} ms", when { s.latencyMs < 150 -> Green; s.latencyMs < 600 -> HayekOrange; else -> MaterialTheme.colorScheme.error })
        s.pruning?.let { InfoRow(L("Prořezaný uzel", "Pruned node"), L("drží posledních ${NetFmt.num(it)} bloků", "keeps last ${NetFmt.num(it)} blocks")) }
        if (public) Text(
            L("Veřejný server vidí adresy vaší peněženky, zůstatek a IP adresu. Pro plné soukromí si připojte vlastní uzel.", "A public server sees your wallet's addresses, balance and IP address. Connect your own node for full privacy."),
            fontSize = 12.sp, color = HayekOrange, modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun BlocksCard(s: NetworkStats, now: Long) {
    val since = (now / 1000 - s.tipTime).coerceAtLeast(0)
    val avg = (s.tipTime - s.dayAgoTime) / 144.0
    StatCard(L("Bloky", "Blocks")) {
        Big(NetFmt.num(s.tipHeight))
        Text(L("Poslední blok ", "Latest block ") + NetFmt.ago(since), fontSize = 13.sp,
            color = if (since > 3600) HayekOrange else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        // Mezery mezi posledními bloky jako sloupce; poslední sloupec „teď“ roste v reálném čase.
        val gaps = s.recentTimes.zipWithNext { a, b -> (a - b).coerceAtLeast(0) }.reversed() + since
        val labels = (s.recentTimes.size - 1 downTo 1).map { "${s.tipHeight - it + 1}".takeLast(3) } + L("teď", "now")
        val max = maxOf(1200L, gaps.maxOrNull() ?: 0)
        Row(Modifier.fillMaxWidth().height(86.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            gaps.forEachIndexed { i, g ->
                val live = i == gaps.lastIndex
                val c = when { live -> HayekOrange.copy(alpha = 0.45f); g <= 900 -> Green; g <= 1800 -> HayekOrange; else -> MaterialTheme.colorScheme.error }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${(g / 60.0).roundToLong()}′", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val h by animateFloatAsState((g.toFloat() / max).coerceIn(0.04f, 1f), label = "bar")
                    Box(Modifier.fillMaxWidth().fillMaxHeight(h * 0.78f).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(c))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEach { Text(it, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        Spacer(Modifier.height(8.dp))
        InfoRow(L("Průměrná doba bloku (24 h)", "Average block time (24 h)"), NetFmt.dur(avg.roundToLong()))
        InfoRow(L("Posledních 144 bloků trvalo", "The last 144 blocks took"), NetFmt.dur(s.tipTime - s.dayAgoTime, short = true) + L(" (cíl 24 h)", " (target 24 h)"))
    }
}

@Composable
private fun MiningCard(s: NetworkStats) {
    val diff = ChainMath.difficulty(s.bits)
    val avg = (s.tipTime - s.dayAgoTime) / 144.0
    StatCard(L("Těžba", "Mining")) {
        Text(L("Výkon sítě (odhad za 24 h)", "Network hashrate (24 h estimate)"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Big(NetFmt.hashrate(ChainMath.hashrate(diff, avg)))
        InfoRow(L("Obtížnost", "Difficulty"), NetFmt.difficulty(diff))
        InfoRow(L("Odměna za blok", "Block subsidy"), NetFmt.btc(ChainMath.subsidy(s.tipHeight), 3))
        Text(L("Výkon se odhaduje z obtížnosti a skutečných časů bloků – žádný server ho přímo nezná.", "Hashrate is estimated from difficulty and actual block times – no server knows it directly."),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun RetargetCard(s: NetworkStats, now: Long) {
    val r = ChainMath.retarget(s.tipHeight, s.tipTime, s.epochStartTime)
    StatCard(L("Úprava obtížnosti", "Difficulty adjustment")) {
        Row(verticalAlignment = Alignment.Bottom) {
            r.change?.let { Big(NetFmt.signedPct(it), color = if (it >= 0) HayekOrange else Green) } ?: Big("—")
            Spacer(Modifier.width(8.dp))
            Text(L("odhad", "estimate"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        Progress(r.blocksDone.toFloat() / ChainMath.RETARGET, HayekOrange,
            L("${NetFmt.num(r.blocksDone)} z 2 016 bloků · zbývá ${NetFmt.num(r.blocksLeft)}", "${NetFmt.num(r.blocksDone)} of 2,016 blocks · ${NetFmt.num(r.blocksLeft)} left"))
        InfoRow(L("Přepočet přibližně", "Expected around"), NetFmt.date(now + r.etaSec * 1000) + L(" (za ", " (in ") + NetFmt.dur(r.etaSec, short = true) + ")")
        r.avgSec?.let { InfoRow(L("Průměrný blok v tomto období", "Average block this period"), NetFmt.dur(it.roundToLong())) }
        Text(L("Každých 2 016 bloků síť upraví obtížnost tak, aby blok vznikal v průměru za 10 minut.", "Every 2,016 blocks the network adjusts difficulty so a block comes every 10 minutes on average."),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun HalvingCard(s: NetworkStats, now: Long) {
    val h = ChainMath.halving(s.tipHeight)
    StatCard(L("Halving", "Halving")) {
        Big(NetFmt.num(h.blocksLeft), L("bloků", "blocks"))
        Progress(h.progress, HayekOrange, L("blok ${NetFmt.num(h.nextHeight)} · přibližně ${NetFmt.monthYear(now + h.etaSec * 1000)}", "block ${NetFmt.num(h.nextHeight)} · around ${NetFmt.monthYear(now + h.etaSec * 1000)}"))
        InfoRow(L("Odměna teď → potom", "Subsidy now → after"), NetFmt.btc(h.subsidyNow, 4) + " → " + NetFmt.btc(h.subsidyNext, 5))
    }
}

@Composable
private fun SupplyCard(s: NetworkStats) {
    val mined = ChainMath.supply(s.tipHeight)
    val frac = mined.toDouble() / ChainMath.MAX_SUPPLY_SAT
    StatCard(L("Vytěženo", "Mined so far")) {
        Big(NetFmt.dec(mined / 1e8, 0), "BTC")
        Progress(frac.toFloat(), HayekOrange, NetFmt.dec(frac * 100, 3) + L(" % z 21 000 000 BTC", "% of 21,000,000 BTC"))
        InfoRow(L("Zbývá vytěžit", "Left to mine"), NetFmt.dec((ChainMath.MAX_SUPPLY_SAT - mined) / 1e8, 0) + " BTC")
        Text(L("Spočítáno přesně z výšky bloku. Část mincí je navždy ztracená, skutečně dostupných je méně.", "Calculated exactly from block height. Some coins are lost forever, so fewer are actually available."),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun FeesCard(s: NetworkStats) {
    StatCard(L("Poplatky", "Fees")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple(L("Rychle", "Fast"), "~20 min", s.fastFee), Triple(L("Běžně", "Normal"), L("~1 h", "~1 h"), s.normalFee), Triple(L("Pomalu", "Slow"), L("~1 den", "~1 day"), s.slowFee))
                .forEachIndexed { i, (name, eta, rate) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(HayekOrange.copy(alpha = if (i == 0) 0.16f else 0.07f)).padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$rate", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (i == 0) HayekOrange else MaterialTheme.colorScheme.onSurface)
                        Text("sat/vB", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$name · $eta", fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                        Text("≈ " + formatSat(rate * ChainMath.TYPICAL_TX_VB), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
        }
        Spacer(Modifier.height(6.dp))
        InfoRow(L("Nejnižší poplatek, který uzel přijme", "Lowest fee the node accepts"), NetFmt.dec(s.relayFee, if (s.relayFee < 1) 2 else 1) + " sat/vB")
        Text(L("Částka „≈“ je cena běžné platby (1 vstup, 2 výstupy, 141 vB).", "The „≈“ amount is the cost of a typical payment (1 input, 2 outputs, 141 vB)."),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
