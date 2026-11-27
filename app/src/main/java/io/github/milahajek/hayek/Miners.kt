package io.github.milahajek.hayek

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.exp
import kotlin.math.pow

/**
 * Domácí těžaři (AxeOS / NerdQAxe / Bitaxe, Avalon přes cgminer API).
 *
 * Zásady:
 *  - jen čtení: Hayek se ptá na stav, nikdy nic neposílá ani nemění,
 *  - jen adresy v domácí síti (10/8, 172.16/12, 192.168/16) – nic nejde do internetu,
 *  - z odpovědi si bere jen čísla (výkon, teplota, podíly); výplatní adresu ani pool neukládá.
 */
data class MinerStats(
    val host: String,
    val online: Boolean,
    val name: String = "",
    val model: String = "",
    /** Výkon v GH/s. */
    val hashrateGh: Double = 0.0,
    val tempC: Double? = null,
    val powerW: Double? = null,
    val accepted: Long = 0,
    val rejected: Long = 0,
    /** Nejlepší podíl od zapnutí těžaře (obtížnost). */
    val bestDiff: Double = 0.0,
    val uptimeSec: Long? = null,
)

object MinerMath {
    /** Očekávaný počet nalezených bloků za den při výkonu [hashrateGh] a obtížnosti sítě [difficulty]. */
    fun blocksPerDay(hashrateGh: Double, difficulty: Double): Double =
        if (difficulty <= 0) 0.0 else hashrateGh * 1e9 * 86_400 / (difficulty * 2.0.pow(32))

    /** Pravděpodobnost aspoň jednoho bloku za [days] dní (Poissonův proces). */
    fun chance(perDay: Double, days: Double) = 1 - exp(-perDay * days)

    /** „4.29G“, „123.4M“, „987k“ i čisté číslo → obtížnost. */
    fun parseDiff(v: Any?): Double {
        if (v is Number) return v.toDouble()
        val s = (v as? String)?.trim()?.replace(",", ".") ?: return 0.0
        if (s.isEmpty()) return 0.0
        val mult = when (s.last().uppercaseChar()) {
            'K' -> 1e3; 'M' -> 1e6; 'G' -> 1e9; 'T' -> 1e12; 'P' -> 1e15; 'E' -> 1e18; else -> 1.0
        }
        val num = if (mult == 1.0) s else s.dropLast(1)
        return (num.trim().toDoubleOrNull() ?: 0.0) * mult
    }

    /** Je to adresa v domácí síti (volitelně s portem)? */
    fun isLanHost(input: String): Boolean {
        val host = input.trim().substringBefore(':')
        val port = input.trim().substringAfter(':', "80")
        val p = host.split('.').map { it.toIntOrNull() ?: return false }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        if ((port.toIntOrNull() ?: return false) !in 1..65_535) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    /** Výkon těžaře: „650 GH/s“, „4,21 TH/s“. */
    fun rate(gh: Double): String = when {
        gh >= 1000 -> NetFmt.dec(gh / 1000, if (gh >= 100_000) 0 else 2) + " TH/s"
        else -> NetFmt.dec(gh, 0) + " GH/s"
    }

    /** Obtížnost lidsky: „4,29 G“. */
    fun diff(d: Double): String = when {
        d >= 1e15 -> NetFmt.dec(d / 1e15, 2) + " P"
        d >= 1e12 -> NetFmt.dec(d / 1e12, 2) + " T"
        d >= 1e9 -> NetFmt.dec(d / 1e9, 2) + " G"
        d >= 1e6 -> NetFmt.dec(d / 1e6, 2) + " M"
        d >= 1e3 -> NetFmt.dec(d / 1e3, 1) + " k"
        else -> NetFmt.dec(d, 0)
    }

    /** Odpověď AxeOS /api/system/info (Bitaxe, NerdAxe, NerdQAxe…). */
    fun fromAxeOs(host: String, json: String): MinerStats {
        val o = JSONObject(json)
        fun d(vararg keys: String) = keys.firstNotNullOfOrNull { k -> if (o.has(k) && !o.isNull(k)) o.optDouble(k).takeIf { !it.isNaN() } else null }
        val best = listOf("bestSessionDiff", "bestDiff").firstNotNullOfOrNull { k -> o.opt(k)?.let { parseDiff(it) }?.takeIf { it > 0 } } ?: 0.0
        return MinerStats(
            host = host, online = true,
            name = o.optString("hostname").ifBlank { host },
            model = o.optString("deviceModel").ifBlank { o.optString("ASICModel") },
            hashrateGh = d("hashRate", "hashRate_1m", "hashRate_10m") ?: 0.0,
            tempC = d("temp", "asicTemp", "chipTemp")?.takeIf { it > 0 },
            powerW = d("power")?.takeIf { it > 0 },
            accepted = o.optLong("sharesAccepted"),
            rejected = o.optLong("sharesRejected"),
            bestDiff = best,
            uptimeSec = if (o.has("uptimeSeconds")) o.optLong("uptimeSeconds") else null,
        )
    }

    /** Odpověď cgminer API příkazu „summary“ (Avalon Nano a podobné). */
    fun fromCgminer(host: String, json: String): MinerStats {
        val s = JSONObject(json.trimEnd('\u0000')).getJSONArray("SUMMARY").getJSONObject(0)
        val mhs = listOf("MHS 1m", "MHS 5m", "MHS 5s", "MHS av").firstNotNullOfOrNull { k -> s.optDouble(k).takeIf { !it.isNaN() && it > 0 } } ?: 0.0
        return MinerStats(
            host = host, online = true, name = "Avalon", model = "cgminer",
            hashrateGh = mhs / 1000,
            accepted = s.optLong("Accepted"), rejected = s.optLong("Rejected"),
            bestDiff = parseDiff(s.opt("Best Share")),
            uptimeSec = if (s.has("Elapsed")) s.optLong("Elapsed") else null,
        )
    }
}

/** Čtení stavu těžařů z domácí sítě. Jen GET / „summary“, krátký timeout, žádné přesměrování. */
object MinerClient {
    private const val TIMEOUT_MS = 2_500
    private const val MAX_BYTES = 256 * 1024

    suspend fun fetchAll(hosts: List<String>): List<MinerStats> = coroutineScope {
        hosts.map { h -> async(Dispatchers.IO) { fetch(h) } }.awaitAll()
    }

    fun fetch(input: String): MinerStats {
        val host = input.trim()
        if (!MinerMath.isLanHost(host)) return MinerStats(host, online = false)
        val ip = host.substringBefore(':')
        val port = host.substringAfter(':', "80").toInt()
        runCatching { return MinerMath.fromAxeOs(host, httpGet(ip, port, "/api/system/info")) }
        // Bez webového rozhraní na portu 80 zkusíme cgminer API (Avalon).
        runCatching { return MinerMath.fromCgminer(host, cgminer(ip, 4028, "summary")) }
        return MinerStats(host, online = false)
    }

    /**
     * Minimální HTTP/1.0 GET přes socket. Záměrně ne HttpURLConnection: ta by na nešifrovaném
     * spojení vyžadovala povolit cleartext pro celou aplikaci. Tady jde jen o adresy v domácí síti.
     */
    internal fun httpGet(ip: String, port: Int, path: String): String {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), TIMEOUT_MS)
            s.soTimeout = TIMEOUT_MS
            s.getOutputStream().apply {
                write("GET $path HTTP/1.0\r\nHost: $ip\r\nAccept: application/json\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            val raw = readAll(s).toString(Charsets.UTF_8)
            val head = raw.substringBefore("\r\n\r\n")
            require(head.startsWith("HTTP/") && head.substringAfter(' ').startsWith("200")) { "HTTP ${head.lineSequence().first()}" }
            val body = raw.substringAfter("\r\n\r\n")
            return if (head.contains("transfer-encoding: chunked", ignoreCase = true)) dechunk(body) else body
        }
    }

    internal fun cgminer(ip: String, port: Int, command: String): String {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), TIMEOUT_MS)
            s.soTimeout = TIMEOUT_MS
            s.getOutputStream().apply { write(JSONObject().put("command", command).toString().toByteArray()); flush() }
            return readAll(s).toString(Charsets.UTF_8)
        }
    }

    private fun readAll(s: Socket): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        val input = s.getInputStream()
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            require(out.size() <= MAX_BYTES) { "too large" }
        }
        return out.toByteArray()
    }

    internal fun dechunk(body: String): String {
        val sb = StringBuilder()
        var rest = body
        while (rest.isNotEmpty()) {
            val line = rest.substringBefore("\r\n")
            val size = line.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            rest = rest.substringAfter("\r\n")
            sb.append(rest.take(size))
            rest = rest.drop(size).removePrefix("\r\n")
        }
        return sb.toString()
    }
}

/** Seznam těžařů (adresy v domácí síti) a poslední souhrn pro widget. */
object MinerStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun hosts(ctx: Context): List<String> = prefs(ctx).getString("miners", "").orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    fun setHosts(ctx: Context, hosts: List<String>) = prefs(ctx).edit().putString("miners", hosts.distinct().joinToString("\n")).apply()

    /** Souhrn pro widget: celkový výkon, kolik těží / kolik celkem, čas. */
    data class Summary(val totalGh: Double, val online: Int, val total: Int, val at: Long)

    fun saveSummary(ctx: Context, stats: List<MinerStats>) {
        val o = JSONObject().put("gh", stats.filter { it.online }.sumOf { it.hashrateGh })
            .put("on", stats.count { it.online }).put("n", stats.size).put("at", System.currentTimeMillis())
        ctx.getSharedPreferences("network", Context.MODE_PRIVATE).edit().putString("miners", o.toString()).apply()
    }

    fun summary(ctx: Context): Summary? = try {
        ctx.getSharedPreferences("network", Context.MODE_PRIVATE).getString("miners", null)?.let {
            val o = JSONObject(it); Summary(o.getDouble("gh"), o.getInt("on"), o.getInt("n"), o.getLong("at"))
        }
    } catch (_: Exception) { null }

    suspend fun refresh(ctx: Context): List<MinerStats>? {
        val hosts = hosts(ctx)
        if (hosts.isEmpty()) {
            ctx.getSharedPreferences("network", Context.MODE_PRIVATE).edit().remove("miners").apply()
            return null
        }
        val stats = withContext(Dispatchers.IO) { MinerClient.fetchAll(hosts) }
        saveSummary(ctx, stats)
        return stats
    }

}
