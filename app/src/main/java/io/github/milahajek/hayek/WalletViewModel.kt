package io.github.milahajek.hayek

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Locked : Screen
    data object Welcome : Screen
    /** Úvodní průvodce; [back] = kam se vrátit. */
    data class Intro(val back: Screen) : Screen
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
    data object Lightning : Screen
    data object LnPay : Screen
    data object LnReceive : Screen
    data object LnSetup : Screen
}

const val LND_KEY = "lnd"
fun encodeLndSecret(c: LndConfig) = c.hostPort + "|" + c.macaroonHex
fun decodeLndSecret(s: String) = LndConfig(s.substringBefore("|"), s.substringAfter("|"))

class WalletViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val store = SecureStore(app)
    private val dbFile = File(app.filesDir, "wallet.sqlite3")
    private val lock = Mutex()
    private var core: WalletCore? = null

    var screen by mutableStateOf<Screen>(
        when {
            store.hasSeed() -> Screen.Locked
            !prefs.getBoolean("intro_seen", false) -> Screen.Intro(Screen.Welcome)
            else -> Screen.Welcome
        }
    )

    fun finishIntro(back: Screen) { prefs.edit().putBoolean("intro_seen", true).apply(); screen = back }

    // ---------- Skrytí zůstatku ----------
    var hideBalance by mutableStateOf(prefs.getBoolean("hide_balance", false))
        private set
    fun toggleHideBalance() { hideBalance = !hideBalance; prefs.edit().putBoolean("hide_balance", hideBalance).apply() }
    /** Částka, nebo hvězdičky, pokud je zůstatek skrytý. */
    fun amount(sat: Long, sign: Boolean = false): String =
        if (hideBalance) "••••• sat" else (if (sign && sat >= 0) "+" else "") + formatSat(sat)

    // ---------- Poznámky k transakcím (jen v telefonu) ----------
    private val notesPrefs = app.getSharedPreferences("notes", Context.MODE_PRIVATE)
    var notesVersion by mutableStateOf(0)
        private set
    fun note(txid: String): String = notesPrefs.getString(txid, "").orEmpty()
    fun setNote(txid: String, text: String) {
        if (note(txid) == text.trim().take(200)) return
        notesPrefs.edit().apply { if (text.isBlank()) remove(txid) else putString(txid, text.trim().take(200)) }.apply()
        notesVersion++; labelChanged()
    }

    // ---------- Značky mincí KYC / bez KYC (jen v telefonu) ----------
    private val tagPrefs = app.getSharedPreferences("coin_tags", Context.MODE_PRIVATE)
    var tagsVersion by mutableStateOf(0)
        private set
    private fun tagRaw(k: String): String? = tagPrefs.getString(k, null)
    fun coinTag(c: CoinItem): CoinTag? { tagsVersion; return resolveTag(c, ::tagRaw) }
    fun coinTagKey(c: CoinItem): String = CoinTag.keyOf(coinTag(c))
    fun addressTag(address: String): CoinTag? { tagsVersion; return CoinTag.of(tagRaw("a:$address")) }
    fun setCoinTag(ids: Collection<String>, tag: CoinTag?) {
        tagPrefs.edit().apply { ids.forEach { putString("c:$it", CoinTag.keyOf(tag)) } }.apply()
        tagsVersion++; prepared = null; labelChanged()
    }
    fun setAddressTag(address: String, tag: CoinTag?) {
        if (addressTag(address) == tag) return
        tagPrefs.edit().apply { if (tag == null) remove("a:$address") else putString("a:$address", tag.key) }.apply()
        tagsVersion++; labelChanged()
    }
    /** Ze které skupiny platit, když jsou v peněžence mince s různými značkami (klíč CoinTag nebo „none“). */
    var payFrom by mutableStateOf<String?>(null)
    /** Skupiny nezmrazených mincí: klíč → součet. */
    fun spendableGroups(): Map<String, Long> =
        coins.filter { !it.frozen }.groupBy { coinTagKey(it) }.mapValues { (_, v) -> v.sumOf { it.valueSat } }
    fun inputTags(p: PreparedTx): List<CoinTag?> = p.inputIds.map { id -> coins.find { it.id == id }?.let { coinTag(it) } }

    // ---------- Záloha štítků (BIP-329) ----------
    /** Po obnově ze slov nabídnout načtení zálohy štítků. */
    var offerLabelsImport by mutableStateOf(false)
    /** Počet změn štítků od poslední zálohy (poznámky, značky, zmrazení). */
    var labelChanges by mutableStateOf(prefs.getInt("label_changes", 0))
        private set
    val lastLabelsBackup: Long get() = prefs.getLong("labels_backup_at", 0)
    private fun labelChanged() { labelChanges++; prefs.edit().putInt("label_changes", labelChanges).apply() }

    /** Sestaví zálohu: poznámky, značky adres, značky a zmrazení současných mincí. */
    fun labelsForExport(): Bip329.Labels {
        val notes = notesPrefs.all.mapNotNull { (k, v) -> (v as? String)?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
        val addrs = tagPrefs.all.mapNotNull { (k, v) -> if (k.startsWith("a:")) CoinTag.of(v as? String)?.let { k.removePrefix("a:") to it } else null }.toMap()
        val outs = coins.mapNotNull { c ->
            val t = coinTag(c)
            if (t == null && !c.frozen) null else c.id to Bip329.Output(t, if (c.frozen) false else null)
        }.toMap()
        return Bip329.Labels(notes, outs, addrs)
    }

    /** Uloží zálohu do souboru (uri ze systémového dialogu). Prázdné heslo = nešifrovaně (čitelné i ve Sparrow). */
    fun exportLabels(uri: android.net.Uri, password: String) = run {
        val text = Bip329.toJsonl(labelsForExport())
        val bytes = withContext(Dispatchers.Default) { if (password.isEmpty()) text.toByteArray() else Bip329.encrypt(text, password) }
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        }
        labelChanges = 0
        prefs.edit().putInt("label_changes", 0).putLong("labels_backup_at", System.currentTimeMillis()).apply()
        message = "Štítky uloženy ✔\n" + (if (password.isEmpty()) "Soubor není šifrovaný – uložte ho na bezpečné místo." else "Soubor je zašifrovaný heslem. Bez hesla ho nikdo nepřečte.")
    }

    var labelsImportNeedsPassword by mutableStateOf<android.net.Uri?>(null)

    /** Načte zálohu. U šifrovaného souboru bez hesla si o heslo řekne. */
    fun importLabels(uri: android.net.Uri, password: String? = null) = run {
        val data = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes() }.also { require(it.size < 5_000_000) { "Soubor je příliš velký." } }
        }
        val text = if (Bip329.isEncrypted(data)) {
            if (password == null) { labelsImportNeedsPassword = uri; return@run }
            withContext(Dispatchers.Default) { Bip329.decrypt(data, password) }
        } else String(data)
        labelsImportNeedsPassword = null; offerLabelsImport = false
        val l = Bip329.parse(text)
        require(l.size > 0) { "V souboru nejsou žádné štítky, které by Hayek Wallet znala." }
        notesPrefs.edit().apply { l.notes.forEach { (k, v) -> putString(k, v) } }.apply(); notesVersion++
        tagPrefs.edit().apply {
            l.addresses.forEach { (a, t) -> putString("a:$a", t.key) }
            l.outputs.forEach { (id, o) -> o.tag?.let { putString("c:$id", it.key) } }
        }.apply(); tagsVersion++
        var frozen = 0
        val c = core
        if (c != null) l.outputs.filter { it.value.spendable != null }.forEach { (id, o) ->
            val coin = coins.find { it.id == id } ?: return@forEach
            if (coin.frozen != (o.spendable == false)) {
                lock.withLock { withContext(Dispatchers.IO) { c.setFrozen(coin.txid, coin.vout, o.spendable == false) } }
                frozen++
            }
        }
        if (frozen > 0) coins = lock.withLock { withContext(Dispatchers.IO) { c!!.coins() } }
        prepared = null
        message = "Štítky načteny ✔\nPoznámky: ${l.notes.size} · značky mincí: ${l.outputs.count { it.value.tag != null }} · značky adres: ${l.addresses.size}" +
            if (frozen > 0) " · změněno zmrazení: $frozen" else ""
    }

    // ---------- Detail transakce a urychlení (RBF) ----------
    var txDetail by mutableStateOf<TxItem?>(null)
    var bumpPrepared by mutableStateOf<PreparedBump?>(null)
    var bumpSuggested by mutableStateOf<Long?>(null)

    fun openTxDetail(tx: TxItem) {
        txDetail = tx; bumpPrepared = null; bumpSuggested = null
        val c = core ?: return
        if (tx.canBump) viewModelScope.launch {
            val e = loadFeeEstimates()
            feeEstimates = e
            bumpSuggested = maxOf(e?.fast ?: 2L, (tx.feeRate ?: 1) + 1)
        }
    }

    fun prepareBump(rate: String) = run {
        val c = core ?: return@run
        val tx = txDetail ?: return@run
        val r = rate.trim().toLongOrNull() ?: throw IllegalArgumentException("Zadejte poplatek v sat/vB.")
        bumpPrepared = lock.withLock { withContext(Dispatchers.IO) { c.prepareBump(tx.txid, r) } }
    }

    /** Volat až po ověření otiskem. */
    fun broadcastBump() = run {
        val c = core ?: return@run
        val b = bumpPrepared ?: return@run
        val newId = lock.withLock { withContext(Dispatchers.IO) { c.signAndBroadcastBump(b) } }
        note(b.oldTxid).takeIf { it.isNotBlank() }?.let { setNote(newId, it) }
        tagRaw("t:${b.oldTxid}")?.let { tagPrefs.edit().putString("t:$newId", it).apply(); tagsVersion++; labelChanged() }
        bumpPrepared = null; txDetail = null
        message = "Poplatek zvýšen ✔\nNová transakce nahradila původní.\nTXID: $newId"
        syncNow()
    }
    var state by mutableStateOf<WalletState?>(null)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var prepared by mutableStateOf<PreparedTx?>(null)
    var feeRate by mutableStateOf("2")
    var feeEstimates by mutableStateOf<FeeEstimates?>(null)
    var feeChoice by mutableStateOf(FeeChoice.NORMAL)

    fun chooseFee(choice: FeeChoice) {
        feeChoice = choice
        feeEstimates?.rate(choice)?.let { feeRate = it.toString() }
        prepared = null
    }

    private suspend fun loadFeeEstimates(): FeeEstimates? {
        val c = core ?: return null
        return try { lock.withLock { withContext(Dispatchers.IO) { c.feeEstimates() } } } catch (_: Exception) { null }
    }
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

    /** Adresa, kterou se má předvyplnit obrazovka Odeslat (např. dar). */
    var sendPrefill: String = ""

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
        withContext(Dispatchers.IO) { store.ensureBioCanary() }
        lnConfig = withContext(Dispatchers.IO) { runCatching { store.loadExtra(LND_KEY)?.let(::decodeLndSecret) }.getOrNull() }
        open(secret)
        if (lnConfig != null) viewModelScope.launch { lnRefreshQuiet() }
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
    fun saveAndOpen(words: String, restored: Boolean = false) = run {
        withContext(Dispatchers.IO) {
            dbFile.delete()
            store.saveSeed(words)
        }
        open(words)
        if (restored) offerLabelsImport = true
    }

    /** Ověří vstup pro sledovací peněženku; vrací tajemství k uložení, nebo null. */
    fun checkWatch(input: String): String? = try {
        val clean = input.trim()
        WatchOnly.validate(clean)
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
        labelChanged()
        if (frozen) selected = selected - coin.id
        prepared = null
    }

    fun openSend() {
        prepared = null
        payFrom = null
        screen = Screen.Send
        val c = core ?: return
        viewModelScope.launch {
            val e = loadFeeEstimates()
            feeEstimates = e
            if (e == null) { feeChoice = FeeChoice.CUSTOM; feeRate = "2" }
            else { if (feeChoice == FeeChoice.CUSTOM) feeChoice = FeeChoice.NORMAL; feeRate = e.rate(feeChoice).toString() }
        }
    }

    fun prepare(to: String, amount: String, sendAll: Boolean) = run {
        val c = core ?: return@run
        val sats = if (sendAll) 0L else amount.trim().toLongOrNull()
            ?: throw IllegalArgumentException("Zadejte částku v satoshi (celé číslo).")
        val rate = feeRate.trim().toLongOrNull()
            ?: throw IllegalArgumentException("Zadejte poplatek v sat/vB.")
        val groups = spendableGroups()
        val avoid = if (selected.isEmpty() && groups.size > 1) {
            val from = payFrom?.takeIf { it in groups }
                ?: throw IllegalArgumentException("V peněžence máte mince s různými značkami. Vyberte nahoře, ze kterých chcete platit (např. jen Bez KYC).")
            coins.filter { !it.frozen && coinTagKey(it) != from }.map { it.id }.toSet()
        } else emptySet()
        prepared = lock.withLock { withContext(Dispatchers.IO) { c.prepare(to, sats, rate, sendAll, selected, avoid) } }
    }

    /** Volat až po ověření. */
    fun broadcast() = run {
        val c = core ?: return@run
        val p = prepared ?: return@run
        try {
            val txid = lock.withLock { withContext(Dispatchers.IO) { c.signAndBroadcast(p) } }
            // Drobné zdědí značku použitých mincí.
            combineTags(inputTags(p))?.let { tagPrefs.edit().putString("t:$txid", it.key).apply(); tagsVersion++; labelChanged() }
            selected = emptySet()
            screen = Screen.Home
            message = "Odesláno ✔\nTXID: $txid"
        } finally {
            // Ať to dopadlo jakkoli, nepřipravená transakce se nesmí odeslat podruhé a stav se musí načíst z uzlu.
            prepared = null
            runCatching { syncNow() }
        }
    }

    /** Ověří slova po změně otisků; při úspěchu znovu „uvěří“ otiskům a pokračuje. */
    fun confirmFingerprintCheck(answers: Map<Int, String>): Boolean {
        val words = seedWords?.split(" ") ?: return false
        val ok = answers.all { (i, w) -> words.getOrNull(i)?.equals(w.trim(), ignoreCase = true) == true }
        if (ok) {
            store.resetBioCanary()
            val next = fingerprintCheck
            fingerprintCheck = null
            next?.invoke()
        }
        return ok
    }

    // ---------- Nastavení ----------

    fun setServer(url: String) {
        serverUrl = normalizeServerUrl(url)
        prefs.edit().putString("server", serverUrl).apply()
        core?.serverUrl = serverUrl
    }

    fun testServer(url: String) = run {
        message = withContext(Dispatchers.IO) {
            try {
                io.github.milahajek.hayek.testServer(normalizeServerUrl(url), pins).also { pinVersion++ }
            } catch (e: Exception) {
                friendly(e) + "\n\n— Podrobná diagnostika —\n" + diagnoseConnection(normalizeServerUrl(url), pins)
            }
        }
    }

    /** Kdy aplikace odešla na pozadí (pro zámek po 2 minutách). Ve ViewModelu, aby přežil otočení displeje. */
    var backgroundSince = 0L

    /** Akce čekající na ověření slov poté, co se v telefonu změnily otisky prstů. */
    var fingerprintCheck by mutableStateOf<(() -> Unit)?>(null)

    fun lockApp() {
        lnClear()
        core = null
        coins = emptyList(); selected = emptySet()
        state = null
        revealSeed = false
        prepared = null
        // Nic citlivého nesmí zůstat vidět nad zámkem ani se po odemčení vrátit.
        message = null; txDetail = null; bumpPrepared = null; rolls = ""; fingerprintCheck = null
        if (store.hasSeed()) screen = Screen.Locked
    }

    fun deleteWallet() {
        lnClear()
        store.wipe()
        notesPrefs.edit().clear().apply(); notesVersion++
        tagPrefs.edit().clear().apply(); tagsVersion++; payFrom = null
        labelChanges = 0; offerLabelsImport = false
        prefs.edit().remove("ln_limit").remove("hide_balance").remove("label_changes").remove("labels_backup_at").apply()
        dbFile.delete()
        File(dbFile.path + "-wal").delete(); File(dbFile.path + "-shm").delete()
        core = null
        state = null
        coins = emptyList(); selected = emptySet()
        screen = Screen.Welcome
    }

    // ---------- Lightning (vlastní LND uzel) ----------

    /** Připojení k LND – jen v paměti, dokud je peněženka odemčená. */
    var lnConfig by mutableStateOf<LndConfig?>(null)
        internal set
    val lnConfigured get() = store.hasExtra(LND_KEY)
    var lnInfo by mutableStateOf<LnInfo?>(null)
    var lnBalance by mutableStateOf<LnBalance?>(null)
    var lnHistory by mutableStateOf<List<LnHistoryItem>>(emptyList())
    var lnChannels by mutableStateOf<List<LnChannel>?>(null)
    var lnError by mutableStateOf<String?>(null)
    var lnDecoded by mutableStateOf<LnDecoded?>(null)
    var lnInvoice by mutableStateOf<LnInvoice?>(null)
    var lnInvoicePaid by mutableStateOf(false)
    var lnPrefill: String = ""
    var lnLimit by mutableStateOf(prefs.getLong("ln_limit", 100_000L))
        private set
    private var pollJob: Job? = null

    fun saveLnLimit(sat: Long) { lnLimit = sat; prefs.edit().putLong("ln_limit", sat).apply() }

    private fun lnClient(cfg: LndConfig? = lnConfig) =
        LndClient(cfg ?: throw IllegalStateException("Lightning není připojený. Nastavte ho v ⚙ Nastavení."), pins)

    fun lnPinned(): String? = lnConfig?.let { pins.get("ln:${it.hostPort}") }

    private fun lnFriendly(e: Exception): String {
        if (e is CertMismatchException) return "⚠ CERTIFIKÁT LIGHTNING UZLU SE ZMĚNIL – spojení odmítnuto.\n\n" +
            "Uzel ${e.hostPort} se prokázal jiným certifikátem než minule. Může to znamenat, že se někdo v síti vydává za váš uzel.\n\n" +
            "Pokud se certifikát LND na RaspiBlitzu změnil (reset LND, aktualizace), v ⚙ Nastavení → Lightning zvolte „Odpojit“ a připojte se znovu.\n\n" +
            "Uložený: ${e.expected.take(23)}…\nNový:      ${e.actual.take(23)}…"
        if (e is LndException) return e.message.orEmpty()
        val m = e.message.orEmpty()
        return if (e is java.io.IOException || m.contains("timed out", true) || m.contains("refused", true) || m.contains("unreachable", true))
            "Nepodařilo se spojit s Lightning uzlem (${lnConfig?.hostPort ?: ""}).\n\n• Je telefon ve stejné Wi-Fi jako uzel?\n• Běží na RaspiBlitzu LND (a je odemčené)?\n\nDetail: ${m.ifBlank { e::class.simpleName }}"
        else m.ifBlank { e::class.simpleName.orEmpty() }
    }

    private fun lnRun(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            try { block() } catch (e: Exception) { message = lnFriendly(e) } finally { busy = false }
        }
    }

    // ---------- Záloha Lightning kanálů ----------
    private fun channelSetKey(ch: List<LnChannel>) = ch.map { it.channelPoint }.filter { it.isNotBlank() }.sorted().joinToString(",")
    /** Kanály se od poslední zálohy změnily (otevření / zavření) – nebo ještě nikdy nebyly zálohované. */
    val lnBackupNeeded: Boolean get() {
        val ch = lnChannels ?: return false
        val saved = prefs.getString("ln_backup_set", null)
        return if (saved == null) ch.isNotEmpty() else saved != channelSetKey(ch)
    }
    val lnLastBackup: Long get() = prefs.getLong("ln_backup_at", 0)
    var lnBackupVersion by mutableStateOf(0)
        private set

    /** Stáhne zálohu kanálů z uzlu, nechá ji uzlem ověřit a uloží do souboru. */
    fun lnExportBackup(uri: android.net.Uri) = lnRun {
        val cfg = lnConfig ?: return@lnRun
        val c = lnClient(cfg)
        val (data, chans) = withContext(Dispatchers.IO) {
            val ch = c.channels()
            val d = c.channelBackup()
            c.verifyChannelBackup(d)
            d to ch
        }
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(data) }
        }
        lnChannels = chans
        prefs.edit().putString("ln_backup_set", channelSetKey(chans)).putLong("ln_backup_at", System.currentTimeMillis()).apply()
        lnBackupVersion++
        message = "Záloha kanálů uložena ✔\nUzel ji ověřil (kanálů: ${chans.size}).\n\nObnova: na uzlu z 24 slov LND + tohoto souboru (RaspiBlitz ho chce pod názvem channel.backup)."
    }

    private suspend fun lnRefreshQuiet() {
        val cfg = lnConfig ?: return
        try {
            val c = lnClient(cfg)
            val (i, b, h) = withContext(Dispatchers.IO) { Triple(c.info(), c.balance(), runCatching { c.history() }.getOrDefault(emptyList())) }
            lnInfo = i; lnBalance = b; lnHistory = h; lnError = null
            lnChannels = withContext(Dispatchers.IO) { runCatching { c.channels() }.getOrNull() } ?: lnChannels
        } catch (e: Exception) {
            lnError = lnFriendly(e)
        }
    }

    fun lnRefresh() = lnRun { lnRefreshQuiet(); lnError?.let { message = it } }

    fun openLightning() { screen = Screen.Lightning; viewModelScope.launch { lnRefreshQuiet() } }

    /**
     * Krok 1 nastavení: zjistí otisk certifikátu uzlu BEZ odeslání klíče.
     * Když QR obsahoval otisk (certfp) a souhlasí, rovnou ho připne a otestuje spojení.
     * @param onNeedConfirm otisk z QR chybí – uživatel ho musí potvrdit (porovnat s uzlem).
     */
    fun lnProbe(cfg: LndConfig, onNeedConfirm: (String) -> Unit, onOk: (LnInfo) -> Unit) = lnRun {
        val fp = withContext(Dispatchers.IO) { LndClient.probeFingerprint(cfg.hostPort) }
        when {
            cfg.certFp == null -> onNeedConfirm(fp)
            cfg.certFp.equals(fp, ignoreCase = true) -> {
                pins.set("ln:${cfg.hostPort}", fp); pinVersion++
                onOk(withContext(Dispatchers.IO) { lnClient(cfg).info() })
            }
            else -> throw IllegalStateException(
                "⚠ Certifikát serveru NESOUHLASÍ s otiskem z QR kódu – připojení zastaveno, klíč nebyl odeslán.\n\n" +
                    "Možná se někdo v síti vydává za váš uzel. Zkontrolujte, že jste ve své domácí síti, a zkuste to znovu.\n\n" +
                    "V QR: ${cfg.certFp.take(23)}…\nServer: ${fp.take(23)}…"
            )
        }
    }

    /** Krok 2 (bez otisku v QR): uživatel potvrdil otisk – připnout a otestovat. */
    fun lnTrustAndTest(cfg: LndConfig, fp: String, onOk: (LnInfo) -> Unit) = lnRun {
        pins.set("ln:${cfg.hostPort}", fp); pinVersion++
        onOk(withContext(Dispatchers.IO) { lnClient(cfg).info() })
    }

    /** Volat až po ověření (otisk) – uloží šifrovaně přístupový klíč. */
    fun lnSave(cfg: LndConfig) = lnRun {
        withContext(Dispatchers.IO) { store.saveExtra(LND_KEY, encodeLndSecret(cfg)) }
        lnConfig = cfg
        lnRefreshQuiet()
        screen = Screen.Lightning
        message = "Lightning připojen ✔" + (lnInfo?.let { "\nUzel: ${it.alias}, aktivní kanály: ${it.activeChannels}" } ?: "")
    }

    fun lnRemove() {
        lnConfig?.let { prefs.edit().remove("pin:ln:${it.hostPort}").apply() }
        store.removeExtra(LND_KEY)
        lnClear()
        pinVersion++
    }

    private fun lnClear() {
        pollJob?.cancel(); pollJob = null
        lnConfig = null; lnInfo = null; lnBalance = null; lnHistory = emptyList(); lnChannels = null
        prefs.edit().remove("ln_backup_set").remove("ln_backup_at").apply()
        lnDecoded = null; lnInvoice = null; lnInvoicePaid = false; lnError = null
    }

    fun openLnPay(prefill: String = "") { lnPrefill = prefill; lnDecoded = null; screen = Screen.LnPay }

    fun lnDecode(raw: String) = lnRun {
        lnDecoded = null
        val d = withContext(Dispatchers.IO) { lnClient().decode(raw) }
        if (d.expired()) throw IllegalArgumentException("Faktura vypršela. Požádejte příjemce o novou.")
        lnDecoded = d
    }

    fun lnFeeLimit(amountSat: Long) = maxOf(10L, amountSat / 100)  // max 1 % (min. 10 sat)

    /** Volat až po ověření otiskem. */
    fun lnPay(amountOverride: Long?) = lnRun {
        val d = lnDecoded ?: return@lnRun
        val amount = if (d.amountSat > 0) d.amountSat else amountOverride ?: throw IllegalArgumentException("Zadejte částku.")
        if (amount > lnLimit) throw IllegalArgumentException("Částka ${amount} sat je nad vaším limitem ${lnLimit} sat na jednu platbu (⚙ Nastavení → Lightning).")
        val r = withContext(Dispatchers.IO) { lnClient().pay(d.paymentRequest, lnFeeLimit(amount), amountOverride.takeIf { d.amountSat == 0L }) }
        if (r.ok) {
            lnDecoded = null
            screen = Screen.Lightning
            message = "Zaplaceno ⚡ ✔\n${amount} sat, poplatek ${r.feeSat} sat"
        } else message = r.failure
        lnRefreshQuiet()
    }

    fun openLnReceive() { pollJob?.cancel(); lnInvoice = null; lnInvoicePaid = false; screen = Screen.LnReceive }

    fun lnCreateInvoice(amountSat: Long, memo: String) = lnRun {
        val inv = withContext(Dispatchers.IO) { lnClient().addInvoice(amountSat, memo) }
        lnInvoice = inv; lnInvoicePaid = false
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            val until = System.currentTimeMillis() + 3_600_000
            while (System.currentTimeMillis() < until && lnInvoice == inv && screen == Screen.LnReceive) {
                delay(2_500)
                val paid = try { withContext(Dispatchers.IO) { lnClient().isSettled(inv.rHashHex) } } catch (_: Exception) { false }
                if (paid && lnInvoice == inv) { lnInvoicePaid = true; lnRefreshQuiet(); break }
            }
        }
    }
}
