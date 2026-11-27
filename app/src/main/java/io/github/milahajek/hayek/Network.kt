package io.github.milahajek.hayek

import android.content.Context
import androidx.work.*
import org.bitcoindevkit.ElectrumClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.pow

/**
 * Veřejné údaje o síti Bitcoin, které umí prozradit Electrum server (Fulcrum / ElectRS).
 * Nic z toho se netýká peněženky: žádné adresy, zůstatky ani transakce uživatele.
 * Dotazy jdou jen na server nastavený v aplikaci (stejný, se kterým se peněženka synchronizuje).
 */
data class NetworkStats(
    val fetchedAt: Long,
    val serverVersion: String,
    val protocol: String,
    val pruning: Long?,
    val latencyMs: Long,
    val tipHeight: Long,
    /** Časy posledních bloků (sekundy), od nejnovějšího: index 0 = blok tipHeight. */
    val recentTimes: List<Long>,
    /** Čas bloku o den (144 bloků) starší než tip. */
    val dayAgoTime: Long,
    /** Čas prvního bloku aktuálního období obtížnosti (výška tip − tip % 2016). */
    val epochStartTime: Long,
    val bits: Long,
    val fastFee: Long,
    val normalFee: Long,
    val slowFee: Long,
    /** Minimální poplatek, který uzel přijme (sat/vB). */
    val relayFee: Double,
) {
    val tipTime get() = recentTimes.first()

    fun toJson(): String = JSONObject().apply {
        put("at", fetchedAt); put("ver", serverVersion); put("proto", protocol); pruning?.let { put("prune", it) }
        put("ping", latencyMs); put("tip", tipHeight); put("recent", JSONArray(recentTimes)); put("day", dayAgoTime)
        put("epoch", epochStartTime); put("bits", bits); put("fast", fastFee); put("normal", normalFee); put("slow", slowFee)
        put("relay", relayFee)
    }.toString()

    companion object {
        fun fromJson(s: String?): NetworkStats? = try {
            s?.let {
                val o = JSONObject(it)
                val r = o.getJSONArray("recent")
                NetworkStats(
                    o.getLong("at"), o.getString("ver"), o.getString("proto"), if (o.has("prune")) o.getLong("prune") else null,
                    o.getLong("ping"), o.getLong("tip"), List(r.length()) { i -> r.getLong(i) }, o.getLong("day"),
                    o.getLong("epoch"), o.getLong("bits"), o.getLong("fast"), o.getLong("normal"), o.getLong("slow"),
                    o.getDouble("relay"),
                )
            }
        } catch (_: Exception) { null }
    }
}

/** Čistě matematické odvození z hlaviček bloků – bez sítě, snadno testovatelné. */
object ChainMath {
    const val RETARGET = 2016L
    const val HALVING = 210_000L
    const val TARGET_SPACING = 600.0
    const val MAX_SUPPLY_SAT = 2_100_000_000_000_000L
    /** Běžná platba: 1 vstup P2WPKH, 2 výstupy (příjemce + drobné) ≈ 141 vB. */
    const val TYPICAL_TX_VB = 141L

    /** Obtížnost z kompaktního cíle „bits“ v hlavičce (vzhledem k obtížnosti 1 = 0x1d00ffff). */
    fun difficulty(bits: Long): Double {
        val exp = ((bits ushr 24) and 0xff).toInt()
        val mant = (bits and 0xffffff).toDouble()
        if (mant == 0.0) return 0.0
        return (0xffff / mant) * 2.0.pow(8 * (0x1d - exp))
    }

    /** Odhad výkonu sítě (H/s) z obtížnosti a průměrné doby bloku. */
    fun hashrate(difficulty: Double, avgBlockSec: Double): Double =
        if (avgBlockSec <= 0) 0.0 else difficulty * 2.0.pow(32) / avgBlockSec

    /** Odměna za blok v dané výšce (sat). */
    fun subsidy(height: Long): Long {
        val era = height / HALVING
        return if (era >= 64) 0 else 5_000_000_000L shr era.toInt()
    }

    /** Kolik satoshi vzniklo v blocích 0..height (bez ohledu na ztracené mince). */
    fun supply(height: Long): Long {
        var left = height + 1
        var total = 0L
        var era = 0
        while (left > 0 && era < 64) {
            val n = minOf(left, HALVING)
            total += n * (5_000_000_000L shr era)
            left -= n; era++
        }
        return total
    }

    data class Retarget(val blocksDone: Long, val blocksLeft: Long, val avgSec: Double?, val change: Double?, val etaSec: Long)

    /** Stav období obtížnosti; [epochStartTime] = čas bloku tip − tip % 2016. */
    fun retarget(tip: Long, tipTime: Long, epochStartTime: Long): Retarget {
        val done = tip % RETARGET
        val left = RETARGET - done
        // Z pár bloků nejde rozumně odhadovat (jeden rychlý blok by ukázal desítky procent).
        val avg = if (done >= 6) (tipTime - epochStartTime).toDouble() / done else null
        // Protokol mění obtížnost nejvýš 4× oběma směry.
        val change = avg?.takeIf { it > 0 }?.let { (TARGET_SPACING / it).coerceIn(0.25, 4.0) - 1 }
        return Retarget(done, left, avg, change, (left * (avg ?: TARGET_SPACING)).toLong())
    }

    data class Halving(val nextHeight: Long, val blocksLeft: Long, val subsidyNow: Long, val subsidyNext: Long, val progress: Float, val etaSec: Long)

    fun halving(tip: Long): Halving {
        val next = (tip / HALVING + 1) * HALVING
        val left = next - tip
        return Halving(next, left, subsidy(tip), subsidy(next), (tip % HALVING).toFloat() / HALVING, (left * TARGET_SPACING).toLong())
    }
}

/**
 * Načte statistiky z už připojeného klienta (cca 15 malých dotazů).
 * Ptá se jen na hlavičky bloků, poplatky a vlastnosti serveru – nic, co by prozradilo peněženku.
 */
fun readNetworkStats(c: ElectrumClient, fees: FeeEstimates? = null): NetworkStats {
    val t0 = System.nanoTime()
    c.ping()
    val latency = (System.nanoTime() - t0) / 1_000_000
    val features = c.serverFeatures()
    val tip = c.blockHeadersSubscribe()
    val h = tip.height.toLong()
    fun time(height: Long) = c.blockHeader(height.toULong()).time.toLong()
    val recent = listOf(tip.header.time.toLong()) + (1L..6L).map { time(h - it) }
    val day = time(h - 144)
    val epochStart = h - h % ChainMath.RETARGET
    val epoch = if (epochStart == h) recent.first() else time(epochStart)
    fun est(blocks: Long): Long? = runCatching { c.estimateFee(blocks.toULong()) }.getOrNull()
        ?.takeIf { it > 0 }?.let { kotlin.math.ceil(it * 100_000_000 / 1000).toLong().coerceAtLeast(1) }
    val slow = fees?.slow ?: est(144) ?: 1
    val normal = fees?.normal ?: maxOf(est(6) ?: slow, slow)
    val fast = fees?.fast ?: maxOf(est(2) ?: normal, normal)
    val relay = runCatching { c.relayFee() * 100_000_000 / 1000 }.getOrDefault(1.0)
    return NetworkStats(
        System.currentTimeMillis(), features.serverVersion, features.protocolMax, features.pruning,
        latency, h, recent, day, epoch, tip.header.bits.toLong() and 0xffffffffL, fast, normal, slow, relay,
    )
}

/** Mezipaměť statistik pro obrazovku Stav sítě a widget (jen veřejná data, bez adresy serveru). */
object NetworkCache {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("network", Context.MODE_PRIVATE)
    fun load(ctx: Context): NetworkStats? = NetworkStats.fromJson(prefs(ctx).getString("stats", null))
    /** Druh serveru při posledním úspěšném načtení: "own" / "public". */
    fun kind(ctx: Context): String? = prefs(ctx).getString("kind", null)
    fun lastAttemptOk(ctx: Context) = prefs(ctx).getBoolean("ok", true)
    fun save(ctx: Context, s: NetworkStats, public: Boolean) {
        prefs(ctx).edit().putString("stats", s.toJson()).putString("kind", if (public) "public" else "own").putBoolean("ok", true).apply()
    }
    fun failed(ctx: Context) { prefs(ctx).edit().putBoolean("ok", false).apply() }
}

/**
 * Obnova widgetu na pozadí. Běží jen, když je widget na ploše (zapíná / vypíná ho receiver widgetu).
 * Připojuje se jen k serveru z nastavení a jen k už ověřenému certifikátu – nový certifikát na pozadí nepřijme.
 */
class NetworkWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Lang.init(ctx)
        val settings = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val server = settings.getString("server", DEFAULT_SERVER).orEmpty()
        if (server.isBlank()) return Result.success()
        val pins = object : PinStore {
            override fun get(hostPort: String) = settings.getString("pin:$hostPort", null)
            override fun set(hostPort: String, fingerprint: String) =
                throw IllegalStateException("Certifikát ještě nebyl ověřen v aplikaci – na pozadí ho nepřijmu.")
        }
        runCatching {
            val c = connect(server, pins)
            try { readNetworkStats(c) } finally { runCatching { c.close() } }
        }.onSuccess { NetworkCache.save(ctx, it, isPublicServer(server)) }
            .onFailure { NetworkCache.failed(ctx) }
        HayekWidget.updateAll(ctx)
        return Result.success()
    }

    companion object {
        private const val WORK = "network_widget"
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<NetworkWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
        fun refreshNow(ctx: Context) {
            WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<NetworkWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
        fun cancel(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
    }
}
