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
    private const val DESC = "watch_descriptor"

    fun dbFile(ctx: Context) = File(ctx.filesDir, "notify.sqlite3")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("notify", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)

    fun enable(ctx: Context, publicDescriptor: String) {
        SecureStore(ctx).saveBg(DESC, publicDescriptor)
        prefs(ctx).edit().putBoolean("enabled", true).remove("seen").remove("init").apply()
        val req = PeriodicWorkRequestBuilder<IncomingWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun disable(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        SecureStore(ctx).removeBg(DESC)
        prefs(ctx).edit().clear().apply()
        dbFile(ctx).delete(); File(dbFile(ctx).path + "-wal").delete(); File(dbFile(ctx).path + "-shm").delete()
    }

    /** Jedna kontrola: vrací nově viděné příchozí transakce (při první kontrole jen zapamatuje stav). */
    fun check(ctx: Context): List<TxItem> {
        val p = prefs(ctx)
        if (!p.getBoolean("enabled", false)) return emptyList()
        val desc = SecureStore(ctx).loadBg(DESC) ?: return emptyList()
        val settings = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val server = settings.getString("server", DEFAULT_SERVER).orEmpty()
        if (server.isBlank()) return emptyList()
        val pins = object : PinStore {
            override fun get(hostPort: String) = settings.getString("pin:$hostPort", null)
            // Na pozadí nikdy nedůvěřujeme novému certifikátu – jen už ověřenému.
            override fun set(hostPort: String, fingerprint: String) {}
        }
        val core = WalletCore(dbFile(ctx), WalletCore.WATCH_PREFIX + desc, server, pins)
        core.sync()
        val incoming = core.state().txs.filter { it.deltaSat > 0 }
        val seen = p.getStringSet("seen", emptySet())!!
        val fresh = freshIncoming(incoming, seen, p.getBoolean("init", false))
        p.edit().putStringSet("seen", seen + incoming.map { it.txid }).putBoolean("init", true).apply()
        return fresh
    }

    /** Při první kontrole nic nehlásí (jen si zapamatuje stav), pak jen dosud neviděné příchozí platby. */
    fun freshIncoming(incoming: List<TxItem>, seen: Set<String>, initialized: Boolean): List<TxItem> =
        if (!initialized) emptyList() else incoming.filter { it.txid !in seen }

    fun notify(ctx: Context, txs: List<TxItem>) {
        if (txs.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, L("Příchozí platby", "Incoming payments"), NotificationManager.IMPORTANCE_DEFAULT))
        val hide = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("hide_balance", false)
        val sum = txs.sumOf { it.deltaSat }
        val title = if (txs.size == 1) L("Přišla platba", "Payment received") else L("Přišly ${txs.size} platby", "${txs.size} payments received")
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
