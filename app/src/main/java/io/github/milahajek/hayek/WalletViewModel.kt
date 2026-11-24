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
    init { Lang.init(app) }


    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val store = SecureStore(app)
    private val lock = Mutex()
    /** Aktivní (zobrazená) peněženka. */
    private var core: WalletCore? = null

    // ---------- Více peněženek ----------
    /** Seznam peněženek. Stávající jediná peněženka se stane první („Kapesní“) a její data zůstanou, kde byla. */
    var wallets by mutableStateOf(WalletRegistry.loadOrMigrate(prefs.getString("wallets", null), store.hasSeed(), ""))
        private set
    var activeId by mutableStateOf(prefs.getString("active_wallet", null)?.takeIf { id -> wallets.any { it.id == id } }
        ?: wallets.firstOrNull()?.id ?: WalletRegistry.PRIMARY_ID)
        private set
    val activeWallet: WalletInfo? get() = wallets.find { it.id == activeId }
    /** Je v telefonu aspoň jedna peněženka (→ po spuštění zámek). */
    val hasWallet: Boolean get() = wallets.isNotEmpty() || store.hasSeed()
    /** Peněženky, které se po odemčení nepodařilo otevřít (lze je odebrat v Nastavení). */
    fun isBroken(id: String) = cores.isNotEmpty() && id !in cores
    /** Otevřené peněženky (jen v paměti, dokud je aplikace odemčená). */
    private val cores = mutableMapOf<String, WalletCore>()
    private val states = mutableMapOf<String, WalletState>()
    /** Zůstatky všech peněženek pro přehled (id → sat). */
    var balances by mutableStateOf<Map<String, Long>>(emptyMap())
        private set
    val totalAll: Long get() = wallets.sumOf { balances[it.id] ?: 0L }
    /** Peněženka, které patří zadaná adresa z tlačítka „Do mé peněženky“ (pro přenos značek). */
    var ownTarget by mutableStateOf<Pair<String, String>?>(null)
    /** true = zakládá se další peněženka (obrazovky založení mají cestu zpět do aplikace). */
    var adding by mutableStateOf(false)
        private set

    private fun dbFile(id: String) = File(getApplication<Application>().filesDir, WalletRegistry.dbName(id))
    private fun sp(name: String) = getApplication<Application>().getSharedPreferences(name, Context.MODE_PRIVATE)
    /** Nastavení konkrétní peněženky (prach, počítadlo štítků). U první peněženky „settings“ jako dřív. */
    private var wPrefs = sp(WalletRegistry.walletPrefs(activeId))

    fun walletName(w: WalletInfo): String = w.name.ifBlank {
        val same = wallets.filter { it.kind == w.kind }
        val base = if (w.isWatch) L("Trezor", "Vault") else L("Kapesní", "Pocket")
        if (same.size > 1 && same.indexOf(w) > 0) "$base ${same.indexOf(w) + 1}" else base
    }
    fun walletName(id: String): String = wallets.find { it.id == id }?.let { walletName(it) } ?: ""

    private fun saveWallets(list: List<WalletInfo>) {
        wallets = list
        // commit: seznam musí být na disku dřív, než se smaže nebo přidá tajemství
        prefs.edit().putString("wallets", WalletRegistry.toJson(list)).commit()
    }

    /** Jen pro náhledy obrazovek v testech. */
    internal fun previewWallets(list: List<WalletInfo>, bal: Map<String, Long>, active: String, addingMode: Boolean = false) {
        wallets = list; balances = bal; activeId = active; adding = addingMode
    }

    fun renameWallet(id: String, name: String) {
        saveWallets(wallets.map { if (it.id == id) it.copy(name = name.trim().take(30)) else it })
        refreshNotifyList()
    }

    /** Přepne zobrazenou peněženku. */
    fun switchWallet(id: String) {
        if (id == activeId || id !in cores) return
        // Během rozpracované operace (příprava platby, synchronizace…) nepřepínat – výsledek by se zapsal k jiné peněžence.
        if (busy) { message = L("Chvilku – dokončuji předchozí akci. Pak můžete přepnout.", "One moment – finishing the previous action. Then you can switch."); return }
        activate(id)
        screen = Screen.Home
        run { syncNow() }
    }

    private fun activate(id: String) {
        activeId = id
        prefs.edit().putString("active_wallet", id).apply()
        core = cores[id]
        state = states[id]
        coins = emptyList()
        notesPrefs = sp(WalletRegistry.notesPrefs(id)); notesVersion++
        tagPrefs = sp(WalletRegistry.tagPrefs(id)); tagsVersion++
        wPrefs = sp(WalletRegistry.walletPrefs(id))
        dustIds = wPrefs.getStringSet("dust_ids", emptySet())!!.toSet()
        labelChanges = wPrefs.getInt("label_changes", 0)
        selected = emptySet(); prepared = null; payFrom = null; ownTarget = null
        txDetail = null; bumpPrepared = null; revealSeed = false; offerLabelsImport = false
    }

    private fun remember(id: String, s: WalletState) {
        if (wallets.none { it.id == id }) return   // mezitím smazaná
        states[id] = s
        balances = balances + (id to s.totalSat)
    }

    /** Ostatní peněženky se načtou potichu na pozadí (kvůli celkovému přehledu). */
    private fun syncOthersQuietly() {
        val others = cores.filterKeys { it != activeId }
        if (others.isEmpty()) return
        viewModelScope.launch {
            others.forEach { (id, c) ->
                runCatching { lock.withLock { withContext(Dispatchers.IO) { c.sync(); remember(id, c.state()) } } }
            }
        }
    }

    /** Další přijímací adresa jiné vlastní peněženky (pro „Do mé peněženky“). */
    fun ownAddress(id: String): String? {
        val s = states[id] ?: return null
        ownTarget = id to s.address
        return s.address
    }

    /** Patří adresa některé z vašich peněženek? (vrací id) – podle adres, které už peněženky znají. */
    fun ownWalletOf(address: String): String? =
        ownTarget?.takeIf { it.second == address }?.first
            ?: states.entries.firstOrNull { (id, s) -> id != activeId && s.address == address }?.key

    var screen by mutableStateOf<Screen>(
        when {
            wallets.isNotEmpty() || store.hasSeed() -> Screen.Locked
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
    private var notesPrefs = sp(WalletRegistry.notesPrefs(activeId))
    var notesVersion by mutableStateOf(0)
        private set
    fun note(txid: String): String = notesPrefs.getString(txid, "").orEmpty()
    fun setNote(txid: String, text: String) {
        if (note(txid) == text.trim().take(200)) return
        notesPrefs.edit().apply { if (text.isBlank()) remove(txid) else putString(txid, text.trim().take(200)) }.apply()
        notesVersion++; labelChanged()
    }

    // ---------- Značky mincí KYC / bez KYC (jen v telefonu) ----------
    private var tagPrefs = sp(WalletRegistry.tagPrefs(activeId))
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

    // ---------- Upozornění na příchozí platby (na pozadí) ----------
    var notifyEnabled by mutableStateOf(IncomingNotifier.enabled(app))
        private set
    fun setNotify(on: Boolean) = run {
        val ctx = getApplication<Application>()
        if (on) {
            if (cores.isEmpty()) return@run
            withContext(Dispatchers.IO) { IncomingNotifier.enable(ctx, notifyEntries(), reset = true) }
            notifyEnabled = true
            message = L("Upozornění zapnuta ✔\nTelefon se zhruba každých 15 minut podívá na váš uzel. ", "Alerts on ✔\nAbout every 15 minutes the phone will check your node. ") +
                L("Mimo domácí síť to funguje jen se zapnutou VPN (Teleport).", "Away from your home network it only works with VPN (Teleport) on.")
        } else {
            withContext(Dispatchers.IO) { IncomingNotifier.disable(ctx) }
            notifyEnabled = false
        }
    }

    private fun notifyEntries(): List<IncomingNotifier.Entry> =
        wallets.mapNotNull { w -> cores[w.id]?.let { IncomingNotifier.Entry(w.id, walletName(w), it.publicDescriptor()) } }

    /** Po přidání / smazání / přejmenování peněženky aktualizuje hlídání na pozadí (bez ztráty už viděných plateb). */
    private fun refreshNotifyList() {
        if (!notifyEnabled || cores.isEmpty()) return
        val ctx = getApplication<Application>()
        val list = notifyEntries()
        viewModelScope.launch(Dispatchers.IO) { runCatching { IncomingNotifier.enable(ctx, list, reset = false) } }
    }

    // ---------- Adresář ----------
    var contacts by mutableStateOf(Contacts.fromJson(prefs.getString("contacts", null)))
        private set
    private fun saveContacts(list: List<Contact>) {
        contacts = list; prefs.edit().putString("contacts", Contacts.toJson(list)).apply(); labelChanged()
    }
    fun contactFor(address: String): Contact? = Contacts.normalize(address)?.let { a -> contacts.find { it.address == a } }
    /** Vrací chybovou hlášku, nebo null při úspěchu. */
    fun saveContact(name: String, address: String): String? {
        val a = Contacts.normalize(address) ?: return L("Neplatná bitcoinová adresa.", "Invalid bitcoin address.")
        val n = name.trim().takeIf { it.isNotEmpty() } ?: return L("Zadejte jméno.", "Enter a name.")
        if (state?.address == a || coins.any { it.address == a }) return L("Tohle je adresa vaší vlastní peněženky.", "This is an address of your own wallet.")
        saveContacts(Contacts.upsert(contacts, Contact(n.take(60), a))); return null
    }
    fun removeContact(c: Contact) = saveContacts(contacts - c)

    // ---------- Žádost o platbu ----------
    /** Popis z žádosti o platbu se po příchodu peněz sám zapíše jako poznámka k transakci. */
    fun rememberRequest(address: String, label: String) {
        val l = label.trim()
        prefs.edit().apply { if (l.isEmpty()) remove("rq:$address") else putString("rq:$address", l.take(200)) }.apply()
    }
    private fun applyRequestNotes() {
        coins.forEach { c ->
            val l = prefs.getString("rq:${c.address}", null) ?: return@forEach
            if (note(c.txid).isBlank()) setNote(c.txid, l)
        }
    }

    // ---------- Ochrana proti prachu (dust attack) ----------
    var dustThreshold by mutableStateOf(prefs.getLong("dust_threshold", Dust.DEFAULT_THRESHOLD))
        private set
    fun saveDustThreshold(v: Long) { dustThreshold = v; prefs.edit().putLong("dust_threshold", v).apply() }
    private var dustIds by mutableStateOf(wPrefs.getStringSet("dust_ids", emptySet())!!.toSet())
    fun isDust(c: CoinItem) = c.id in dustIds
    fun txHasDust(txid: String) = coins.any { it.txid == txid && it.id in dustIds }

    /** Volat uvnitř zámku po načtení mincí. Novou podezřelou minci jednou zmrazí; ručně rozmrazenou už nechá být. */
    private suspend fun guardDust(c: WalletCore) {
        val seen = wPrefs.getStringSet("dust_seen", emptySet())!!.toSet()
        val found = Dust.findNew(coins, dustThreshold, seen)
        if (found.isEmpty()) return
        val toFreeze = found.filter { !it.frozen }
        withContext(Dispatchers.IO) { toFreeze.forEach { c.setFrozen(it.txid, it.vout, true) } }
        dustIds = dustIds + found.map { it.id }
        wPrefs.edit().putStringSet("dust_seen", seen + found.map { it.id }).putStringSet("dust_ids", dustIds).apply()
        if (toFreeze.isNotEmpty()) {
            coins = withContext(Dispatchers.IO) { c.coins() }
            labelChanged()
            message = (if (toFreeze.size == 1) L("Přišla podezřelá drobná platba ${formatSat(toFreeze[0].valueSat)}.", "A suspicious tiny payment arrived: ${formatSat(toFreeze[0].valueSat)}.")
                else L("Přišlo ${toFreeze.size} podezřelých drobných plateb (celkem ${formatSat(toFreeze.sumOf { it.valueSat })}).", "${toFreeze.size} suspicious tiny payments arrived (total ${formatSat(toFreeze.sumOf { it.valueSat })}).")) +
                L("\n\nZmrazil jsem ji, aby se při placení nepropojila s vašimi ostatními mincemi (ochrana proti „dust attacku“). ", "\n\nI froze it so it won't get linked with your other coins when paying (protection against a „dust attack“). ") +
                L("Pokud jde o platbu, kterou čekáte, rozmrazíte ji v Mincích (❄).", "If it's a payment you're expecting, unfreeze it in Coins (❄).")
        }
    }

    // ---------- Záloha štítků (BIP-329) ----------
    /** Po obnově ze slov nabídnout načtení zálohy štítků. */
    var offerLabelsImport by mutableStateOf(false)
    /** Počet změn štítků od poslední zálohy (poznámky, značky, zmrazení). */
    var labelChanges by mutableStateOf(wPrefs.getInt("label_changes", 0))
        private set
    val lastLabelsBackup: Long get() { labelChanges; return wPrefs.getLong("labels_backup_at", 0) }
    private fun labelChanged() { labelChanges++; wPrefs.edit().putInt("label_changes", labelChanges).apply() }

    /** Sestaví zálohu: poznámky, značky adres, značky a zmrazení současných mincí. */
    fun labelsForExport(): Bip329.Labels {
        val notes = notesPrefs.all.mapNotNull { (k, v) -> (v as? String)?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
        val addrs = tagPrefs.all.mapNotNull { (k, v) -> if (k.startsWith("a:")) CoinTag.of(v as? String)?.let { k.removePrefix("a:") to it } else null }.toMap()
        val outs = coins.mapNotNull { c ->
            val t = coinTag(c)
            if (t == null && !c.frozen) null else c.id to Bip329.Output(t, if (c.frozen) false else null)
        }.toMap()
        return Bip329.Labels(notes, outs, addrs, contacts.associate { it.address to it.name })
    }

    /** Uloží zálohu do souboru (uri ze systémového dialogu). Prázdné heslo = nešifrovaně (čitelné i ve Sparrow). */
    fun exportLabels(uri: android.net.Uri, password: String) = run {
        val text = Bip329.toJsonl(labelsForExport())
        val bytes = withContext(Dispatchers.Default) { if (password.isEmpty()) text.toByteArray() else Bip329.encrypt(text, password) }
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        }
        labelChanges = 0
        wPrefs.edit().putInt("label_changes", 0).putLong("labels_backup_at", System.currentTimeMillis()).apply()
        message = L("Štítky uloženy ✔\n", "Labels saved ✔\n") + (if (password.isEmpty()) L("Soubor není šifrovaný – uložte ho na bezpečné místo.", "The file is not encrypted – keep it somewhere safe.") else L("Soubor je zašifrovaný heslem. Bez hesla ho nikdo nepřečte.", "The file is encrypted with a password. Without the password nobody can read it."))
    }

    var labelsImportNeedsPassword by mutableStateOf<android.net.Uri?>(null)

    /** Načte zálohu. U šifrovaného souboru bez hesla si o heslo řekne. */
    fun importLabels(uri: android.net.Uri, password: String? = null) = run {
        val data = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes() }.also { require(it.size < 5_000_000) { L("Soubor je příliš velký.", "The file is too large.") } }
        }
        val text = if (Bip329.isEncrypted(data)) {
            if (password == null) { labelsImportNeedsPassword = uri; return@run }
            withContext(Dispatchers.Default) { Bip329.decrypt(data, password) }
        } else String(data)
        labelsImportNeedsPassword = null; offerLabelsImport = false
        val l = Bip329.parse(text)
        require(l.size > 0) { L("V souboru nejsou žádné štítky, které by Hayek Wallet znala.", "The file contains no labels Hayek Wallet understands.") }
        notesPrefs.edit().apply { l.notes.forEach { (k, v) -> putString(k, v) } }.apply(); notesVersion++
        if (l.contacts.isNotEmpty()) {
            val merged = l.contacts.entries.fold(contacts) { acc, (a, n) -> Contacts.normalize(a)?.let { Contacts.upsert(acc, Contact(n.take(60), it)) } ?: acc }
            contacts = merged; prefs.edit().putString("contacts", Contacts.toJson(merged)).apply()
        }
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
        message = L("Štítky načteny ✔\nPoznámky: ${l.notes.size} · značky mincí: ${l.outputs.count { it.value.tag != null }} · značky adres: ${l.addresses.size} · kontakty: ${l.contacts.size}", "Labels imported ✔\nNotes: ${l.notes.size} · coin tags: ${l.outputs.count { it.value.tag != null }} · address tags: ${l.addresses.size} · contacts: ${l.contacts.size}") +
            if (frozen > 0) L(" · změněno zmrazení: $frozen", " · frozen state changed: $frozen") else ""
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
        val r = rate.trim().toLongOrNull() ?: throw IllegalArgumentException(L("Zadejte poplatek v sat/vB.", "Enter the fee in sat/vB."))
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
        success = Success(L("Poplatek zvýšen", "Fee raised"))
        message = L("Poplatek zvýšen ✔\nNová transakce nahradila původní.\nTXID: $newId", "Fee raised ✔\nThe new transaction replaced the original.\nTXID: $newId")
        syncNow()
    }
    var state by mutableStateOf<WalletState?>(null)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    /** Krátká animace úspěchu (zobrazí se před zprávou). */
    var success by mutableStateOf<Success?>(null)
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
    val seedPassphrase: String? get() = core?.passphrase
    /** Otisk zobrazené peněženky (u hesla se podle něj pozná, že je zadané správně). */
    val walletFingerprint: String? get() = Passphrase.fingerprint(runCatching { core?.publicDescriptor() }.getOrNull())
    /** Heslo navíc pro právě zakládanou peněženku z kostek (jen v paměti do uložení). */
    var newPassphrase by mutableStateOf<String?>(null)
    /** Peněženka, jejíž slova se ověřují po změně otisků: zobrazená, a když je jen sledovací, první kapesní. */
    val checkWalletId: String? get() = activeId.takeIf { cores[it]?.mnemonicWords != null }
        ?: wallets.firstOrNull { cores[it.id]?.mnemonicWords != null }?.id
    val checkWords: String? get() = checkWalletId?.let { cores[it]?.mnemonicWords }
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
            return L("⚠ CERTIFIKÁT UZLU SE ZMĚNIL – spojení odmítnuto.\n\n", "⚠ THE NODE CERTIFICATE CHANGED – connection refused.\n\n") +
                L("Server ${e.hostPort} se prokázal jiným certifikátem než minule. Může to znamenat, že se někdo v síti vydává za váš uzel.\n\n", "Server ${e.hostPort} presented a different certificate than last time. Someone on the network may be impersonating your node.\n\n") +
                L("Pokud jste na uzlu certifikát sami změnili (reinstalace, aktualizace Fulcrumu/RaspiBlitzu), můžete v ⚙ Nastavení zvolit „Zapomenout certifikát“. Jinak se nepřipojujte.\n\n", "If you changed the certificate on the node yourself (reinstall, Fulcrum/RaspiBlitz update), choose „Forget certificate“ in ⚙ Settings. Otherwise don't connect.\n\n") +
                L("Uložený: ${e.expected.take(23)}…\nNový:      ${e.actual.take(23)}…", "Saved: ${e.expected.take(23)}…\nNew:   ${e.actual.take(23)}…")
        }
        val m = e.message.orEmpty()
        val cls = e::class.simpleName.orEmpty()
        val tcpOnTls = serverUrl.trim().startsWith("tcp://") && m.contains("unexpected EOF", true)
        return when {
            tcpOnTls -> L("Spojení se hned přerušilo. Nejspíš jste zadali tcp:// na šifrovaný port.\n\n", "The connection dropped immediately. You probably entered tcp:// for an encrypted port.\n\n") +
                L("Na RaspiBlitzu: šifrovaně ssl://IP:50022 (Fulcrum) nebo ssl://IP:50002 (ElectRS), ", "On RaspiBlitz: encrypted ssl://IP:50022 (Fulcrum) or ssl://IP:50002 (ElectRS), ") +
                L("nešifrovaně tcp://IP:50021 (Fulcrum) nebo tcp://IP:50001 (ElectRS).\n\nDetail: $m", "unencrypted tcp://IP:50021 (Fulcrum) or tcp://IP:50001 (ElectRS).\n\nDetail: $m")
            m == NO_SERVER -> L("Peněženka ještě neví, ke kterému uzlu se připojit.\n\nV ⚙ Nastavení zadejte adresu svého Electrum serveru (Fulcrum nebo ElectRS), např. ssl://192.168.0.10:50002.", "The wallet doesn't know which node to connect to yet.\n\nIn ⚙ Settings enter your Electrum server address (Fulcrum or ElectRS), e.g. ssl://192.168.0.10:50002.")
            m.contains("Insufficient funds", true) -> L("Nedostatek prostředků (včetně poplatku).", "Insufficient funds (including the fee).")
            m.contains("dust", true) -> L("Částka je příliš malá (pod limitem sítě).", "The amount is too small (below the network limit).")
            cls.contains("Electrum") || m.contains("connection", true) || m.contains("timed out", true) ||
                m.contains("refused", true) || m.contains("unreachable", true) || m.isBlank() ->
                L("Nepodařilo se spojit s vaším uzlem ($serverUrl).\n\n", "Couldn't connect to your node ($serverUrl).\n\n") +
                    L("• Je telefon ve stejné Wi-Fi síti jako uzel (nebo připojený přes VPN domů)?\n", "• Is the phone on the same Wi-Fi as the node (or connected home via VPN)?\n") +
                    L("• Běží na RaspiBlitzu Electrum server (Electrs/Fulcrum)?\n\n", "• Is the Electrum server (Electrs/Fulcrum) running on RaspiBlitz?\n\n") +
                    "Detail: ${if (m.isBlank()) cls else m}"
            else -> m.ifBlank { cls }
        }
    }

    // ---------- Úložiště klíče (StrongBox / TEE) ----------
    private val hasStrongBox = app.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    var keyLevel by mutableStateOf<String?>(null)
        private set

    // ---------- Odemknutí a založení ----------

    /** Volat až po úspěšném ověření otiskem/PINem. */
    fun unlocked() = run {
        // Všechna tajemství se dešifrují hned v 60s okně po ověření; přepínání je pak okamžité.
        val secrets = withContext(Dispatchers.IO) {
            wallets.mapNotNull { w ->
                val sec = runCatching { if (w.slot == WalletRegistry.SLOT_SEED) store.loadSeed() else store.loadExtra(w.slot) }.getOrNull()
                sec?.let { w.id to it }
            }.toMap()
        }
        withContext(Dispatchers.IO) { store.ensureBioCanary() }
        // Jednorázově přesunout šifrovací klíč do StrongBoxu (jsme v 60s okně po ověření).
        val moved = withContext(Dispatchers.IO) { runCatching { store.migrateToStrongBox(hasStrongBox) }.getOrDefault(false) }
        keyLevel = withContext(Dispatchers.IO) { store.keyLevel() }
        if (moved) message = L("🔐 Šifrovací klíč peněženky je teď ve StrongBoxu – samostatném bezpečnostním čipu telefonu.",
            "🔐 The wallet's encryption key is now in StrongBox – the phone's separate security chip.")
        lnConfig = withContext(Dispatchers.IO) { runCatching { store.loadExtra(LND_KEY)?.let(::decodeLndSecret) }.getOrNull() }
        // Typ peněženky podle skutečného obsahu (u převedené staré instalace ho předem neznáme).
        val fixed = wallets.map { w -> secrets[w.id]?.let { sec -> w.copy(kind = if (sec.startsWith(WalletCore.WATCH_PREFIX)) "watch" else "hot") } ?: w }
        if (fixed != wallets || !prefs.contains("wallets")) saveWallets(fixed)
        for (w in wallets) {
            val sec = secrets[w.id] ?: continue
            val c = withContext(Dispatchers.IO) { runCatching { WalletCore(dbFile(w.id), sec, serverUrl, pins).also { it.state() } }.getOrNull() } ?: continue
            cores[w.id] = c
            remember(w.id, withContext(Dispatchers.IO) { c.state() })
        }
        if (cores.isEmpty()) { lnConfig = null; throw IllegalStateException(L("Peněženku se nepodařilo otevřít. Zkuste aplikaci zavřít a odemknout znovu.", "Couldn't open the wallet. Try closing the app and unlocking again.")) }
        if (cores.size < wallets.size) message = L("⚠ Peněženku „${wallets.filter { it.id !in cores }.joinToString { walletName(it) }}“ se nepodařilo otevřít. Ostatní fungují; nefunkční můžete odebrat v ⚙ Nastavení → Peněženky.",
            "⚠ Couldn't open wallet „${wallets.filter { it.id !in cores }.joinToString { walletName(it) }}“. The others work; you can remove it in ⚙ Settings → Wallets.")
        if (activeId !in cores) cores.keys.firstOrNull()?.let { activeId = it }
        activate(activeId)
        screen = Screen.Home
        if (lnConfig != null) viewModelScope.launch { lnRefreshQuiet() }
        try { syncNow() } finally { syncOthersQuietly() }
    }

    fun startDice() { rolls = ""; newPassphrase = null; screen = Screen.Dice }
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

    /** Začít zakládat další peněženku (zpět se jde do aplikace). */
    fun startAddWallet() { adding = true; screen = Screen.Welcome }
    fun cancelAddWallet() { adding = false; rolls = ""; newPassphrase = null; screen = if (isOpen) Screen.Home else Screen.Welcome }

    /** Volat až po ověření – uloží zašifrovanou frázi (nebo sledovací klíč) a otevře peněženku. */
    fun saveAndOpen(words: String, restored: Boolean = false) = run {
        val first = wallets.isEmpty()
        val id = if (first) WalletRegistry.PRIMARY_ID else WalletRegistry.newId(wallets)
        val kind = if (words.startsWith(WalletCore.WATCH_PREFIX)) "watch" else "hot"
        val slot = if (first) WalletRegistry.SLOT_SEED else "wallet_$id"
        val db = dbFile(id)
        val c = withContext(Dispatchers.IO) {
            listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).forEach { it.delete() }
            WalletCore(db, words, serverUrl, pins)
        }
        // Stejnou peněženku dvakrát nepřidáme (stejná slova nebo stejný klíč).
        val dup = withContext(Dispatchers.IO) {
            val mine = c.firstAddress()
            lock.withLock { cores.entries.find { (_, o) -> runCatching { o.firstAddress() == mine }.getOrDefault(false) }?.key }
        }
        if (dup != null) {
            withContext(Dispatchers.IO) { listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).forEach { it.delete() } }
            adding = false
            if (dup != activeId) activate(dup)
            screen = Screen.Home
            message = L("Tahle peněženka už v aplikaci je („${walletName(dup)}“).", "This wallet is already in the app („${walletName(dup)}“).")
            return@run
        }
        withContext(Dispatchers.IO) { if (first) store.saveSeed(words) else store.saveExtra(slot, words) }
        keyLevel = withContext(Dispatchers.IO) { store.keyLevel() }
        val base = if (kind == "watch") L("Trezor", "Vault") else L("Kapesní", "Pocket")
        val taken = wallets.map { walletName(it) }.toSet()
        val name = if (first) "" else generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in taken }
        // Nová peněženka začíná s čistými poznámkami a značkami.
        if (first) { sp(WalletRegistry.notesPrefs(id)).edit().clear().apply(); sp(WalletRegistry.tagPrefs(id)).edit().clear().apply() }
        else listOf(WalletRegistry.notesPrefs(id), WalletRegistry.tagPrefs(id), WalletRegistry.walletPrefs(id)).forEach { sp(it).edit().clear().apply() }
        saveWallets((if (first) emptyList() else wallets.filter { it.id != id }) + WalletInfo(id, name, slot, kind))
        cores[id] = c
        remember(id, withContext(Dispatchers.IO) { c.state() })
        adding = false
        activate(id)
        screen = Screen.Home
        if (restored) offerLabelsImport = true
        refreshNotifyList()
        newPassphrase = null
        if (Passphrase.of(words) != null) {
            val fp = Passphrase.fingerprint(withContext(Dispatchers.IO) { c.publicDescriptor() }) ?: "?"
            message = L("🔑 Peněženka s heslem (passphrase) je uložená.\n\nOtisk peněženky: $fp\nZapište si ho k heslu. Když později zadáte heslo s překlepem, uvidíte jiný otisk a prázdnou peněženku – to je znamení, že heslo nesedí.",
                "🔑 The wallet with a passphrase is saved.\n\nWallet fingerprint: $fp\nWrite it down next to the passphrase. If you later enter the passphrase with a typo, you'll see a different fingerprint and an empty wallet – that's the sign the passphrase is wrong.") +
                if (restored) L("\n\nPokud je obnovená peněženka prázdná, nejspíš nesedí heslo (velká/malá písmena, mezery). Smažte ji a obnovte znovu.", "\n\nIf the restored wallet is empty, the passphrase most likely doesn't match (upper/lower case, spaces). Delete it and restore again.") else ""
        } else if (!first) message = L("Peněženka „${walletName(id)}“ přidána ✔\nMezi peněženkami přepínáte nahoře na hlavní obrazovce. Přejmenovat ji můžete v ⚙ Nastavení.",
            "Wallet „${walletName(id)}“ added ✔\nSwitch between wallets at the top of the home screen. You can rename it in ⚙ Settings.")
        syncNow()
    }

    /** Ověří vstup pro sledovací peněženku; vrací tajemství k uložení, nebo null. */
    fun checkWatch(input: String): String? = try {
        val clean = input.trim()
        WatchOnly.validate(clean)
        WalletCore.WATCH_PREFIX + clean
    } catch (e: Exception) {
        message = e.message ?: L("Neplatný klíč", "Invalid key")
        null
    }

    fun checkRestore(input: String): String? = try {
        Seed.normalize(input)
    } catch (e: Exception) {
        message = L("Neplatná slova obnovovací fráze. Zkontrolujte pravopis, pořadí a počet slov.", "Invalid recovery phrase words. Check the spelling, order and number of words.")
        null
    }

    // ---------- Peněženka ----------

    private suspend fun syncNow() {
        val c = core ?: return
        val wid = activeId
        try {
            lock.withLock {
                withContext(Dispatchers.IO) { c.sync() }
                val s = withContext(Dispatchers.IO) { c.state() }
                val cs = withContext(Dispatchers.IO) { c.coins() }
                remember(wid, s)
                // Mezitím mohl uživatel přepnout na jinou peněženku – pak se zobrazení nemění.
                if (activeId == wid) {
                    state = s; coins = cs
                    guardDust(c)
                    applyRequestNotes()
                    selected = selected.filter { id -> coins.any { it.id == id && !it.frozen } }.toSet()
                }
            }
            connected = true
            pinVersion++
        } catch (e: Exception) {
            connected = false
            throw e
        }
    }

    fun sync() = run { try { syncNow() } finally { syncOthersQuietly() } }

    fun newAddress() = run {
        val c = core ?: return@run
        lock.withLock {
            withContext(Dispatchers.IO) { c.newAddress() }
            state = withContext(Dispatchers.IO) { c.state() }.also { remember(activeId, it) }
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
            ?: throw IllegalArgumentException(L("Zadejte částku v satoshi (celé číslo).", "Enter the amount in satoshi (a whole number)."))
        val rate = feeRate.trim().toLongOrNull()
            ?: throw IllegalArgumentException(L("Zadejte poplatek v sat/vB.", "Enter the fee in sat/vB."))
        val groups = spendableGroups()
        val avoid = if (selected.isEmpty() && groups.size > 1) {
            val from = payFrom?.takeIf { it in groups }
                ?: throw IllegalArgumentException(L("V peněžence máte mince s různými značkami. Vyberte nahoře, ze kterých chcete platit (např. jen Bez KYC).", "Your wallet has coins with different tags. Choose above which ones to pay from (e.g. only No KYC)."))
            coins.filter { !it.frozen && coinTagKey(it) != from }.map { it.id }.toSet()
        } else emptySet()
        prepared = lock.withLock { withContext(Dispatchers.IO) { c.prepare(to, sats, rate, sendAll, selected, avoid) } }
    }

    /** Volat až po ověření. */
    fun broadcast() = run {
        val c = core ?: return@run
        val p = prepared ?: return@run
        sendWith(p) { lock.withLock { withContext(Dispatchers.IO) { c.signAndBroadcast(p) } } }
    }

    /** Odeslání transakce podepsané hardwarovou peněženkou (Coldcard) – ověří se, že je to přesně připravená platba. */
    fun broadcastSigned(data: ByteArray) = run {
        val c = core ?: return@run
        val p = prepared ?: return@run
        sendWith(p) { lock.withLock { withContext(Dispatchers.IO) { c.broadcastSigned(p, data) } } }
    }

    private suspend fun sendWith(p: PreparedTx, send: suspend () -> String) {
        try {
            val txid = send()
            // Drobné zdědí značku použitých mincí.
            val tag = combineTags(inputTags(p))
            tag?.let { tagPrefs.edit().putString("t:$txid", it.key).apply(); tagsVersion++; labelChanged() }
            // Platba do vlastní peněženky: značka KYC / bez KYC a poznámka „odkud“ přejdou s penězi.
            ownWalletOf(p.to)?.let { to ->
                if (tag != null) sp(WalletRegistry.tagPrefs(to)).edit().putString("a:${p.to}", tag.key).apply()
                val from = walletName(activeId)
                sp(WalletRegistry.notesPrefs(to)).edit().putString(txid, L("Z peněženky $from", "From wallet $from")).apply()
                if (note(txid).isBlank()) setNote(txid, L("Do peněženky ${walletName(to)}", "To wallet ${walletName(to)}"))
                sp(WalletRegistry.walletPrefs(to)).let { wp -> wp.edit().putInt("label_changes", wp.getInt("label_changes", 0) + 1).apply() }
            }
            ownTarget = null
            selected = emptySet()
            screen = Screen.Home
            success = Success(L("Odesláno", "Sent"), formatSat(p.amountSat))
            message = L("Odesláno ✔\nTXID: $txid", "Sent ✔\nTXID: $txid")
        } finally {
            // Ať to dopadlo jakkoli, nepřipravená transakce se nesmí odeslat podruhé a stav se musí načíst z uzlu.
            prepared = null
            runCatching { syncNow() }
            syncOthersQuietly()
        }
    }

    /** Nepodepsaná transakce pro Coldcard (binární PSBT). */
    fun psbtForSigning(): ByteArray? = prepared?.let { p -> core?.psbtBytes(p) }

    /** Má sledovací peněženka „otisk“ hardwarové peněženky? Bez něj Coldcard transakci nepozná jako svou. */
    val hasKeyOrigin: Boolean get() = runCatching { core?.publicDescriptor()?.contains("[") }.getOrNull() == true

    fun savePsbtFile(uri: android.net.Uri) = run {
        val data = psbtForSigning() ?: return@run
        withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(data) } }
        message = L("PSBT uloženo ✔\nNa Coldcardu: Ready To Sign. Podepsaný soubor (.txn nebo -signed.psbt) pak načtěte tlačítkem „Načíst podepsaný soubor“.",
            "PSBT saved ✔\nOn the Coldcard: Ready To Sign. Then load the signed file (.txn or -signed.psbt) with „Load signed file“.")
    }

    fun loadSignedFile(uri: android.net.Uri) {
        viewModelScope.launch {
            val data = try {
                withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            } catch (e: Exception) { message = e.message; return@launch }
            broadcastSigned(data)
        }
    }

    /** Ověří slova po změně otisků; při úspěchu znovu „uvěří“ otiskům a pokračuje. */
    fun confirmFingerprintCheck(answers: Map<Int, String>): Boolean {
        val words = checkWords?.split(" ") ?: return false
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
        cores.values.forEach { it.serverUrl = serverUrl }
        core?.serverUrl = serverUrl
    }

    fun testServer(url: String) = run {
        message = withContext(Dispatchers.IO) {
            try {
                io.github.milahajek.hayek.testServer(normalizeServerUrl(url), pins).also { pinVersion++ }
            } catch (e: Exception) {
                friendly(e) + L("\n\n— Podrobná diagnostika —\n", "\n\n— Detailed diagnostics —\n") + diagnoseConnection(normalizeServerUrl(url), pins)
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
        cores.clear(); states.clear(); balances = emptyMap(); ownTarget = null
        if (adding) adding = false
        newPassphrase = null
        coins = emptyList(); selected = emptySet()
        state = null
        revealSeed = false
        prepared = null
        // Nic citlivého nesmí zůstat vidět nad zámkem ani se po odemčení vrátit.
        message = null; txDetail = null; bumpPrepared = null; rolls = ""; fingerprintCheck = null
        if (hasWallet) screen = Screen.Locked
    }

    /**
     * Smaže zobrazenou peněženku z telefonu (volat po ověření otiskem). Když jsou v aplikaci další peněženky,
     * ostatní zůstanou beze změny; smazáním poslední se aplikace vrátí do výchozího stavu.
     */
    fun deleteWallet() {
        if (wallets.size > 1) { removeWallet(activeId); return }
        deleteEverything()
    }

    /** Odebere jednu peněženku (i nefunkční). Volat po ověření otiskem. */
    fun removeWallet(id: String) {
        val w = wallets.find { it.id == id } ?: return
        val newList = wallets.filter { it.id != id }
        val name = walletName(w)
        if (newList.isEmpty()) { deleteEverything(); return }
        if (busy) { message = L("Chvilku – dokončuji předchozí akci.", "One moment – finishing the previous action."); return }
        // Nejdřív seznam (commit), pak tajemství: při pádu uprostřed zůstane nanejvýš nepoužitý šifrovaný záznam, nikdy záměna.
        saveWallets(newList)
        try { if (w.slot == WalletRegistry.SLOT_SEED) store.removeSeed() else store.removeExtra(w.slot) } catch (_: Exception) {}
        cores.remove(id); states.remove(id); balances = balances - id
        sp(WalletRegistry.notesPrefs(id)).edit().clear().apply()
        sp(WalletRegistry.tagPrefs(id)).edit().clear().apply()
        if (id == WalletRegistry.PRIMARY_ID) prefs.edit().remove("label_changes").remove("labels_backup_at").remove("dust_seen").remove("dust_ids").apply()
        else sp(WalletRegistry.walletPrefs(id)).edit().clear().apply()
        val db = dbFile(id)
        listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).forEach { it.delete() }
        if (notifyEnabled) {
            val ctx = getApplication<Application>()
            viewModelScope.launch(Dispatchers.IO) { runCatching { IncomingNotifier.removeWallet(ctx, id) } }
        }
        if (id == activeId || activeId !in cores) (newList.firstOrNull { it.id in cores } ?: newList.first()).let { activate(it.id) }
        refreshNotifyList()
        screen = Screen.Home
        message = L("Peněženka „$name“ smazána z telefonu. Ostatní peněženky zůstaly beze změny.", "Wallet „$name“ deleted from the phone. The other wallets are unchanged.")
        run { syncNow() }
    }

    private fun deleteEverything() {
        lnClear()
        IncomingNotifier.disable(getApplication()); notifyEnabled = false
        store.wipe()
        notesPrefs.edit().clear().apply(); notesVersion++
        tagPrefs.edit().clear().apply(); tagsVersion++; payFrom = null
        labelChanges = 0; offerLabelsImport = false; dustIds = emptySet()
        contacts = emptyList()
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith("rq:") }.forEach { remove(it) } }.apply()
        wallets.forEach { w ->
            val names = listOf(WalletRegistry.notesPrefs(w.id), WalletRegistry.tagPrefs(w.id)) +
                if (w.id == WalletRegistry.PRIMARY_ID) emptyList() else listOf(WalletRegistry.walletPrefs(w.id))
            names.forEach { sp(it).edit().clear().apply() }
            dbFile(w.id).let { db -> listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).forEach { it.delete() } }
        }
        wallets = emptyList(); cores.clear(); states.clear(); balances = emptyMap(); adding = false; activeId = WalletRegistry.PRIMARY_ID
        notesPrefs = sp("notes"); tagPrefs = sp("coin_tags"); wPrefs = prefs
        prefs.edit().remove("wallets").remove("active_wallet").remove("contacts").remove("ln_limit").remove("hide_balance").remove("label_changes").remove("labels_backup_at").remove("dust_seen").remove("dust_ids").apply()
        dbFile(WalletRegistry.PRIMARY_ID).let { db -> listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).forEach { it.delete() } }
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
        LndClient(cfg ?: throw IllegalStateException(L("Lightning není připojený. Nastavte ho v ⚙ Nastavení.", "Lightning is not connected. Set it up in ⚙ Settings.")), pins)

    fun lnPinned(): String? = lnConfig?.let { pins.get("ln:${it.hostPort}") }

    private fun lnFriendly(e: Exception): String {
        if (e is CertMismatchException) return L("⚠ CERTIFIKÁT LIGHTNING UZLU SE ZMĚNIL – spojení odmítnuto.\n\n", "⚠ THE LIGHTNING NODE CERTIFICATE CHANGED – connection refused.\n\n") +
            L("Uzel ${e.hostPort} se prokázal jiným certifikátem než minule. Může to znamenat, že se někdo v síti vydává za váš uzel.\n\n", "Node ${e.hostPort} presented a different certificate than last time. Someone on the network may be impersonating your node.\n\n") +
            L("Pokud se certifikát LND na RaspiBlitzu změnil (reset LND, aktualizace), v ⚙ Nastavení → Lightning zvolte „Odpojit“ a připojte se znovu.\n\n", "If the LND certificate on RaspiBlitz changed (LND reset, update), choose „Disconnect“ in ⚙ Settings → Lightning and connect again.\n\n") +
            L("Uložený: ${e.expected.take(23)}…\nNový:      ${e.actual.take(23)}…", "Saved: ${e.expected.take(23)}…\nNew:   ${e.actual.take(23)}…")
        if (e is LndException) return e.message.orEmpty()
        val m = e.message.orEmpty()
        return if (e is java.io.IOException || m.contains("timed out", true) || m.contains("refused", true) || m.contains("unreachable", true))
            L("Nepodařilo se spojit s Lightning uzlem (${lnConfig?.hostPort ?: ""}).\n\n• Je telefon ve stejné Wi-Fi jako uzel?\n• Běží na RaspiBlitzu LND (a je odemčené)?\n\nDetail: ${m.ifBlank { e::class.simpleName }}", "Couldn't connect to the Lightning node (${lnConfig?.hostPort ?: ""}).\n\n• Is the phone on the same Wi-Fi as the node?\n• Is LND running on RaspiBlitz (and unlocked)?\n\nDetail: ${m.ifBlank { e::class.simpleName }}")
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
        message = L("Záloha kanálů uložena ✔\nUzel ji ověřil (kanálů: ${chans.size}).\n\nObnova: na uzlu z 24 slov LND + tohoto souboru (RaspiBlitz ho chce pod názvem channel.backup).", "Channel backup saved ✔\nThe node verified it (channels: ${chans.size}).\n\nRestore: on the node from the 24 LND words + this file (RaspiBlitz wants it named channel.backup).")
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
                L("⚠ Certifikát serveru NESOUHLASÍ s otiskem z QR kódu – připojení zastaveno, klíč nebyl odeslán.\n\n", "⚠ The server certificate DOES NOT match the fingerprint in the QR code – connection stopped, the key was not sent.\n\n") +
                    L("Možná se někdo v síti vydává za váš uzel. Zkontrolujte, že jste ve své domácí síti, a zkuste to znovu.\n\n", "Someone on the network may be impersonating your node. Make sure you're on your home network and try again.\n\n") +
                    L("V QR: ${cfg.certFp.take(23)}…\nServer: ${fp.take(23)}…", "In QR: ${cfg.certFp.take(23)}…\nServer: ${fp.take(23)}…")
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
        message = L("Lightning připojen ✔", "Lightning connected ✔") + (lnInfo?.let { L("\nUzel: ${it.alias}, aktivní kanály: ${it.activeChannels}", "\nNode: ${it.alias}, active channels: ${it.activeChannels}") } ?: "")
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
        if (d.expired()) throw IllegalArgumentException(L("Faktura vypršela. Požádejte příjemce o novou.", "The invoice has expired. Ask the recipient for a new one."))
        lnDecoded = d
    }

    fun lnFeeLimit(amountSat: Long) = maxOf(10L, amountSat / 100)  // max 1 % (min. 10 sat)

    /** Volat až po ověření otiskem. */
    fun lnPay(amountOverride: Long?) = lnRun {
        val d = lnDecoded ?: return@lnRun
        val amount = if (d.amountSat > 0) d.amountSat else amountOverride ?: throw IllegalArgumentException(L("Zadejte částku.", "Enter an amount."))
        if (amount > lnLimit) throw IllegalArgumentException(L("Částka ${amount} sat je nad vaším limitem ${lnLimit} sat na jednu platbu (⚙ Nastavení → Lightning).", "Amount ${amount} sat is above your limit of ${lnLimit} sat per payment (⚙ Settings → Lightning)."))
        val r = withContext(Dispatchers.IO) { lnClient().pay(d.paymentRequest, lnFeeLimit(amount), amountOverride.takeIf { d.amountSat == 0L }) }
        if (r.ok) {
            lnDecoded = null
            screen = Screen.Lightning
            success = Success(L("Zaplaceno", "Paid"), formatSat(amount), lightning = true)
            message = L("Zaplaceno ⚡ ✔\n${amount} sat, poplatek ${r.feeSat} sat", "Paid ⚡ ✔\n${amount} sat, fee ${r.feeSat} sat")
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
                if (paid && lnInvoice == inv) { lnInvoicePaid = true; success = Success(L("Přijato", "Received"), inv.amountSat.takeIf { it > 0 }?.let { formatSat(it) }, lightning = true); lnRefreshQuiet(); break }
            }
        }
    }
}
