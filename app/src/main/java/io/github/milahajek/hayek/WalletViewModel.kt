package io.github.milahajek.hayek

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Locked : Screen
    data object Welcome : Screen
    data object Dice : Screen
    data class ShowSeed(val words: String) : Screen
    data class Verify(val words: String, val positions: List<Int>) : Screen
    data object Restore : Screen
    data object WatchImport : Screen
    data object Coins : Screen
    data object Export : Screen
    data object Home : Screen
    data object Receive : Screen
    data object Send : Screen
    data object Settings : Screen
    data object Seed : Screen
}

class WalletViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val store = SecureStore(app)
    private val dbFile = File(app.filesDir, "wallet.sqlite3")
    private val lock = Mutex()
    private var core: WalletCore? = null

    var screen by mutableStateOf<Screen>(if (store.hasSeed()) Screen.Locked else Screen.Welcome)
    var state by mutableStateOf<WalletState?>(null)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var prepared by mutableStateOf<PreparedTx?>(null)
    var feeRate by mutableStateOf("2")
    var rolls by mutableStateOf("")
    var revealSeed by mutableStateOf(false)
    var connected by mutableStateOf<Boolean?>(null)
    /** 0 = podle telefonu, 1 = světlý, 2 = tmavý */
    var theme by mutableStateOf(prefs.getInt("theme", 0))
        private set
    var coins by mutableStateOf<List<CoinItem>>(emptyList())
    var selected by mutableStateOf<Set<String>>(emptySet())
    var explorerUrl by mutableStateOf(prefs.getString("explorer", DEFAULT_EXPLORER) ?: DEFAULT_EXPLORER)
        private set

    fun setExplorer(url: String) {
        explorerUrl = url.trim().trimEnd('/')
        prefs.edit().putString("explorer", explorerUrl).apply()
    }

    /** Adresa transakce ve vlastním Mempoolu, nebo null, když není nastaven. */
    fun txLink(txid: String, vout: Int? = null): String? =
        explorerUrl.takeIf { it.isNotBlank() }?.let { "$it/tx/$txid" + (vout?.let { v -> "#vout=$v" } ?: "") }

    /** Kam se vrátit z obrazovky mincí. */
    var coinsReturn: Screen = Screen.Home
    var serverUrl by mutableStateOf(prefs.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER)
        private set

    /** Uložené otisky certifikátů uzlů (veřejné údaje, nejsou tajné). */
    val pins = object : PinStore {
        override fun get(hostPort: String) = prefs.getString("pin:$hostPort", null)
        override fun set(hostPort: String, fingerprint: String) {
            prefs.edit().putString("pin:$hostPort", fingerprint).apply()
        }
    }

    /** Otisk certifikátu uloženého pro aktuální server (null = zatím žádný nebo tcp). */
    fun pinnedFingerprint(url: String = serverUrl): String? =
        runCatching { ServerAddr.parse(url) }.getOrNull()?.takeIf { it.ssl }?.let { pins.get(it.hostPort) }

    /** Zapomene uložený certifikát – při příštím spojení se uloží nový. */
    fun forgetPin(url: String = serverUrl) {
        runCatching { ServerAddr.parse(url) }.getOrNull()?.let { prefs.edit().remove("pin:${it.hostPort}").apply() }
        pinVersion++
    }
    var pinVersion by mutableStateOf(0)

    val seedWords: String? get() = core?.mnemonicWords
    val isOpen get() = core != null
    val watchOnly get() = core?.watchOnly == true
    val publicDescriptor: String? get() = core?.publicDescriptor()

    fun setThemeMode(t: Int) { theme = t; prefs.edit().putInt("theme", t).apply() }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            try {
                block()
            } catch (e: Exception) {
                message = friendly(e)
            } finally {
                busy = false
            }
        }
    }

    private fun friendly(e: Exception): String {
        if (e is CertMismatchException) {
            return "⚠ CERTIFIKÁT UZLU SE ZMĚNIL – spojení odmítnuto.\n\n" +
                "Server ${e.hostPort} se prokázal jiným certifikátem než minule. Může to znamenat, že se někdo v síti vydává za váš uzel.\n\n" +
                "Pokud jste na uzlu certifikát sami změnili (reinstalace, aktualizace Fulcrumu/RaspiBlitzu), můžete v ⚙ Nastavení zvolit „Zapomenout certifikát“. Jinak se nepřipojujte.\n\n" +
                "Uložený: ${e.expected.take(23)}…\nNový:      ${e.actual.take(23)}…"
        }
        val m = e.message.orEmpty()
        val cls = e::class.simpleName.orEmpty()
        val tcpOnTls = serverUrl.trim().startsWith("tcp://") && m.contains("unexpected EOF", true)
        return when {
            tcpOnTls -> "Spojení se hned přerušilo. Nejspíš jste zadali tcp:// na šifrovaný port.\n\n" +
                "Na RaspiBlitzu: šifrovaně ssl://IP:50022 (Fulcrum) nebo ssl://IP:50002 (ElectRS), " +
                "nešifrovaně tcp://IP:50021 (Fulcrum) nebo tcp://IP:50001 (ElectRS).\n\nDetail: $m"
            m == NO_SERVER -> "Peněženka ještě neví, ke kterému uzlu se připojit.\n\nV ⚙ Nastavení zadejte adresu svého Electrum serveru (Fulcrum nebo ElectRS), např. ssl://192.168.0.10:50002."
            m.contains("Insufficient funds", true) -> "Nedostatek prostředků (včetně poplatku)."
            m.contains("dust", true) -> "Částka je příliš malá (pod limitem sítě)."
            cls.contains("Electrum") || m.contains("connection", true) || m.contains("timed out", true) ||
                m.contains("refused", true) || m.contains("unreachable", true) || m.isBlank() ->
                "Nepodařilo se spojit s vaším uzlem ($serverUrl).\n\n" +
                    "• Je telefon ve stejné Wi-Fi síti jako uzel (nebo připojený přes VPN domů)?\n" +
                    "• Běží na RaspiBlitzu Electrum server (Electrs/Fulcrum)?\n\n" +
                    "Detail: ${if (m.isBlank()) cls else m}"
            else -> m.ifBlank { cls }
        }
    }

    // ---------- Odemknutí a založení ----------

    /** Volat až po úspěšném ověření otiskem/PINem. */
    fun unlocked() = run {
        val secret = withContext(Dispatchers.IO) { store.loadSeed() }
        open(secret)
    }

    private suspend fun open(words: String) {
        val c = withContext(Dispatchers.IO) { WalletCore(dbFile, words, serverUrl, pins) }
        core = c
        screen = Screen.Home
        state = withContext(Dispatchers.IO) { c.state() }
        syncNow()
    }

    fun startDice() { rolls = ""; screen = Screen.Dice }
    fun addRoll(n: Int) { rolls += n.toString() }
    fun undoRoll() { rolls = rolls.dropLast(1) }

    fun finishDice() {
        try {
            val words = Seed.fromDice(rolls)
            rolls = ""
            screen = Screen.ShowSeed(words)
        } catch (e: Exception) {
            message = e.message
        }
    }

    fun goVerify(words: String) {
        val pos = (0 until 24).shuffled().take(3).sorted()
        screen = Screen.Verify(words, pos)
    }

    /** Volat až po ověření – uloží zašifrovanou frázi a otevře peněženku. */
    fun saveAndOpen(words: String) = run {
        withContext(Dispatchers.IO) {
            dbFile.delete()
            store.saveSeed(words)
        }
        open(words)
    }

    /** Ověří vstup pro sledovací peněženku; vrací tajemství k uložení, nebo null. */
    fun checkWatch(input: String): String? = try {
        val clean = input.trim()
        WatchOnly.descriptors(clean)
        WalletCore.WATCH_PREFIX + clean
    } catch (e: Exception) {
        message = e.message ?: "Neplatný klíč"
        null
    }

    fun checkRestore(input: String): String? = try {
        Seed.normalize(input)
    } catch (e: Exception) {
        message = "Neplatná slova obnovovací fráze. Zkontrolujte pravopis, pořadí a počet slov."
        null
    }

    // ---------- Peněženka ----------

    private suspend fun syncNow() {
        val c = core ?: return
        try {
            lock.withLock {
                withContext(Dispatchers.IO) { c.sync() }
                state = withContext(Dispatchers.IO) { c.state() }
                coins = withContext(Dispatchers.IO) { c.coins() }
                selected = selected.filter { id -> coins.any { it.id == id && !it.frozen } }.toSet()
            }
            connected = true
            pinVersion++
        } catch (e: Exception) {
            connected = false
            throw e
        }
    }

    fun sync() = run { syncNow() }

    fun newAddress() = run {
        val c = core ?: return@run
        lock.withLock {
            withContext(Dispatchers.IO) { c.newAddress() }
            state = withContext(Dispatchers.IO) { c.state() }
        }
    }

    fun openCoins(from: Screen) = run {
        coinsReturn = from
        screen = Screen.Coins
        val c = core ?: return@run
        coins = lock.withLock { withContext(Dispatchers.IO) { c.coins() } }
    }

    fun toggleSelect(id: String) {
        prepared = null
        selected = if (id in selected) selected - id else selected + id
    }

    fun setFrozen(coin: CoinItem, frozen: Boolean) = run {
        val c = core ?: return@run
        lock.withLock {
            withContext(Dispatchers.IO) { c.setFrozen(coin.txid, coin.vout, frozen) }
            coins = withContext(Dispatchers.IO) { c.coins() }
        }
        if (frozen) selected = selected - coin.id
        prepared = null
    }

    fun openSend() {
        prepared = null
        screen = Screen.Send
        val c = core ?: return
        viewModelScope.launch {
            val r = try { lock.withLock { withContext(Dispatchers.IO) { c.suggestedFeeRate() } } } catch (_: Exception) { 2L }
            feeRate = r.toString()
        }
    }

    fun prepare(to: String, amount: String, sendAll: Boolean) = run {
        val c = core ?: return@run
        val sats = if (sendAll) 0L else amount.trim().toLongOrNull()
            ?: throw IllegalArgumentException("Zadejte částku v satoshi (celé číslo).")
        val rate = feeRate.trim().toLongOrNull()
            ?: throw IllegalArgumentException("Zadejte poplatek v sat/vB.")
        prepared = lock.withLock { withContext(Dispatchers.IO) { c.prepare(to, sats, rate, sendAll, selected) } }
    }

    /** Volat až po ověření. */
    fun broadcast() = run {
        val c = core ?: return@run
        val p = prepared ?: return@run
        val txid = lock.withLock { withContext(Dispatchers.IO) { c.signAndBroadcast(p) } }
        prepared = null
        selected = emptySet()
        screen = Screen.Home
        message = "Odesláno ✔\nTXID: $txid"
        syncNow()
    }

    // ---------- Nastavení ----------

    fun setServer(url: String) {
        serverUrl = url.trim()
        prefs.edit().putString("server", serverUrl).apply()
        core?.serverUrl = serverUrl
    }

    fun testServer(url: String) = run {
        message = withContext(Dispatchers.IO) {
            try {
                io.github.milahajek.hayek.testServer(url.trim(), pins).also { pinVersion++ }
            } catch (e: Exception) {
                friendly(e) + "\n\n— Podrobná diagnostika —\n" + diagnoseConnection(url.trim(), pins)
            }
        }
    }

    fun lockApp() {
        core = null
        coins = emptyList(); selected = emptySet()
        state = null
        revealSeed = false
        prepared = null
        if (store.hasSeed()) screen = Screen.Locked
    }

    fun deleteWallet() {
        store.wipe()
        dbFile.delete()
        File(dbFile.path + "-wal").delete(); File(dbFile.path + "-shm").delete()
        core = null
        state = null
        coins = emptyList(); selected = emptySet()
        screen = Screen.Welcome
    }
}
