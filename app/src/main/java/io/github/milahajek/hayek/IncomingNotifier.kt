package io.github.milahajek.hayek

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Upozornění na příchozí platby, i když je aplikace zavřená.
 * Každých ~15 minut se na pozadí podívá na váš uzel přes sledovací (veřejnou) kopii peněženky –
 * bez soukromých klíčů a bez odemčení. Vše jde jen na váš vlastní uzel s ověřeným certifikátem.
 */
object IncomingNotifier {
    private const val WORK = "incoming_payments"
    private const val CHANNEL = "incoming"
    private const val DESC = "watch_descriptor"        // starší verze: jediná peněženka
    private const val LIST = "watch_list"              // id, jméno a veřejný deskriptor každé peněženky

    /** Hlídaná peněženka – jen veřejné údaje (adresy), žádné klíče. */
    data class Entry(val id: String, val name: String, val descriptor: String)

    fun dbFile(ctx: Context, id: String = WalletRegistry.PRIMARY_ID) =
        File(ctx.filesDir, if (id == WalletRegistry.PRIMARY_ID) "notify.sqlite3" else "notify_$id.sqlite3")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("notify", Context.MODE_PRIVATE)
    private fun deleteDb(ctx: Context, id: String) = dbFile(ctx, id).let { listOf(it, File(it.path + "-wal"), File(it.path + "-shm")).forEach(File::delete) }

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)

    fun toJson(list: List<Entry>): String = org.json.JSONArray().apply {
        list.forEach { put(org.json.JSONObject().put("id", it.id).put("name", it.name).put("d", it.descriptor)) }
    }.toString()
    fun fromJson(text: String): List<Entry> = runCatching {
        val a = org.json.JSONArray(text)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Entry(o.getString("id"), o.optString("name"), o.getString("d")) } }
    }.getOrDefault(emptyList())

    /**
     * Zapne hlídání pro zadané peněženky. reset = true při zapnutí uživatelem (vše od nuly);
     * false při změně seznamu peněženek – už viděné platby se znovu nehlásí a nově přidaná peněženka
     * se při první kontrole jen „naučí“ svůj stav.
     */
    fun enable(ctx: Context, list: List<Entry>, reset: Boolean) {
        val ss = SecureStore(ctx)
        ss.saveBg(LIST, toJson(list)); ss.removeBg(DESC)
        val e = prefs(ctx).edit().putBoolean("enabled", true)
        if (reset) {
            prefs(ctx).all.keys.filter { it == "seen" || it.startsWith("init") }.forEach { e.remove(it) }
            list.forEach { deleteDb(ctx, it.id) }
        }
        e.apply()
        val req = PeriodicWorkRequestBuilder<IncomingWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun removeWallet(ctx: Context, id: String) {
        prefs(ctx).edit().remove(initKey(id)).apply()
        deleteDb(ctx, id)
    }

    fun disable(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        val ss = SecureStore(ctx)
        val ids = ss.loadBg(LIST)?.let { fromJson(it).map { e -> e.id } }.orEmpty() + WalletRegistry.PRIMARY_ID
        ss.removeBg(DESC); ss.removeBg(LIST)
        prefs(ctx).edit().clear().apply()
        ids.distinct().forEach { deleteDb(ctx, it) }
        // i kopie, které už v seznamu nejsou
        ctx.filesDir.listFiles { f -> f.name.startsWith("notify_") && f.name.contains(".sqlite3") }?.forEach { it.delete() }
    }

    /** Starší verze měla jediný klíč „init“ pro první peněženku. */
    private fun initKey(id: String) = if (id == WalletRegistry.PRIMARY_ID) "init" else "init_$id"

    fun entries(ctx: Context): List<Entry> {
        val ss = SecureStore(ctx)
        ss.loadBg(LIST)?.let { return fromJson(it) }
        return ss.loadBg(DESC)?.let { listOf(Entry(WalletRegistry.PRIMARY_ID, "", it)) }.orEmpty()
    }

    /** Jedna kontrola všech hlídaných peněženek: vrací nově viděné příchozí platby (jméno peněženky, platba). */
    fun check(ctx: Context): List<Pair<String, TxItem>> {
        val p = prefs(ctx)
        if (!p.getBoolean("enabled", false)) return emptyList()
        val list = entries(ctx)
        val settings = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val server = settings.getString("server", DEFAULT_SERVER).orEmpty()
        if (server.isBlank() || list.isEmpty()) return emptyList()
        val pins = object : PinStore {
            override fun get(hostPort: String) = settings.getString("pin:$hostPort", null)
            // Na pozadí nikdy nedůvěřujeme novému certifikátu – jen už ověřenému.
            override fun set(hostPort: String, fingerprint: String) {}
        }
        val out = mutableListOf<Pair<String, TxItem>>()
        for (e in list) {
            // Chyba jedné peněženky (např. poškozená kopie) nesmí zastavit hlídání ostatních.
            // Kopii, kterou nejde otevřít, smažeme (příště se vytvoří znovu); už viděné platby se kvůli „seen“ znovu nehlásí.
            val core = runCatching { WalletCore(dbFile(ctx, e.id), WalletCore.WATCH_PREFIX + e.descriptor, server, pins) }
                .getOrElse { deleteDb(ctx, e.id); null } ?: continue
            // Uzel nedostupný → nic se neoznačí jako viděné, zkusí se za 15 minut.
            val incoming = runCatching { core.sync(); core.state().txs.filter { it.deltaSat > 0 } }.getOrNull() ?: continue
            val seen = p.getStringSet("seen", emptySet())!!
            val fresh = freshIncoming(incoming, seen, p.getBoolean(initKey(e.id), false))
            p.edit().putStringSet("seen", seen + incoming.map { it.txid }).putBoolean(initKey(e.id), true).apply()
            out += fresh.map { (if (list.size > 1) e.name else "") to it }
        }
        return out
    }

    /** Při první kontrole nic nehlásí (jen si zapamatuje stav), pak jen dosud neviděné příchozí platby. */
    fun freshIncoming(incoming: List<TxItem>, seen: Set<String>, initialized: Boolean): List<TxItem> =
        if (!initialized) emptyList() else incoming.filter { it.txid !in seen }

    fun notify(ctx: Context, found: List<Pair<String, TxItem>>) {
        if (found.isEmpty()) return
        val txs = found.map { it.second }
        val names = found.map { it.first }.filter { it.isNotBlank() }.distinct()
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, L("Příchozí platby", "Incoming payments"), NotificationManager.IMPORTANCE_DEFAULT))
        val hide = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("hide_balance", false)
        val sum = txs.sumOf { it.deltaSat }
        val title = (if (txs.size == 1) L("Přišla platba", "Payment received") else L("Přišly ${txs.size} platby", "${txs.size} payments received")) +
            if (names.isNotEmpty()) " · " + names.joinToString(", ") else ""
        val text = (if (hide) "" else "+" + formatSat(sum) + " · ") +
            if (txs.all { it.confirmed }) L("potvrzeno", "confirmed") else L("čeká na potvrzení", "waiting for confirmation")
        val open = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_incoming).setColor(0xFFF7931A.toInt())
            .setContentTitle(title).setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open).setAutoCancel(true).build()
        NotificationManagerCompat.from(ctx).notify(txs.first().txid.hashCode(), n)
    }
}

class IncomingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        Lang.init(applicationContext)
        IncomingNotifier.notify(applicationContext, IncomingNotifier.check(applicationContext))
        Result.success()
    } catch (_: Exception) {
        Result.success()   // uzel nedostupný (např. mimo domov bez VPN) – zkusí se za 15 minut
    }
}
