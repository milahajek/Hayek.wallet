package io.github.milahajek.hayek

import org.bitcoindevkit.Address
import org.bitcoindevkit.Amount
import org.bitcoindevkit.ChainPosition
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.ElectrumClient
import org.bitcoindevkit.FeeRate
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import org.bitcoindevkit.NetworkKind
import org.bitcoindevkit.OutPoint
import org.bitcoindevkit.Txid
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Psbt
import org.bitcoindevkit.TxBuilder
import org.bitcoindevkit.UnconfirmedTx
import org.bitcoindevkit.CreateTxException
import org.bitcoindevkit.BumpFeeTxBuilder
import org.bitcoindevkit.Wallet
import java.io.File
import java.security.MessageDigest

/** SKUTEČNÝ Bitcoin (mainnet). */
val NETWORK = Network.BITCOIN
/**
 * Veřejné Electrum servery pro ty, kdo nemají vlastní uzel (seznam převzatý z peněženky Sparrow).
 * Volitelné – peněženka je nikdy nepoužije sama od sebe. Server vidí adresy, zůstatek a IP adresu.
 */
val PUBLIC_SERVERS = listOf(
    "Blockstream" to "ssl://electrum.blockstream.info:50002",
    "Blockstream (port 700)" to "ssl://blockstream.info:700",
    "Luke Childs" to "ssl://bitcoin.lu.ke:50002",
    "Emzy" to "ssl://electrum.emzy.de:50002",
    "Bitaroo" to "ssl://electrum.bitaroo.net:50002",
    "DIY Nodes" to "ssl://electrum.diynodes.com:50022",
    "Seth For Privacy" to "ssl://fulcrum.sethforprivacy.com:50002",
)

fun isPublicServer(url: String) = PUBLIC_SERVERS.any { it.second == normalizeServerUrl(url) }

/** Adresa pro dobrovolné příspěvky na vývoj (zobrazena v Nastavení). */
const val DONATION_ADDRESS = "bc1qwnqafccacrx0cnfuqdr5sr0scjzeacpw8srt8a"

/** Žádný výchozí server – každý uživatel zadá svůj vlastní uzel (nebo si vědomě vybere veřejný). */
const val DEFAULT_SERVER = ""
const val NO_SERVER = "NO_SERVER"
/** Mempool na vlastním RaspiBlitzu (HTTP 4080, HTTPS 4081). */
const val DEFAULT_EXPLORER = ""
private const val MAINNET_GENESIS = "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"

/** Kolik hodů šestistěnnou kostkou je potřeba na 256 bitů (log2(6) ≈ 2,585 bitu na hod). */
const val MIN_ROLLS = 99

data class TxItem(
    val txid: String,
    val deltaSat: Long,
    val feeSat: Long?,
    val confirmed: Boolean,
    val height: Int?,
    val time: Long?,
    /** Poplatek v sat/vB (pokud je známý). */
    val feeRate: Long? = null,
    /** Odeslaná nepotvrzená transakce s RBF – jde urychlit vyšším poplatkem. */
    val canBump: Boolean = false,
)

/** Doporučené poplatky v sat/vB. */
data class FeeEstimates(val fast: Long, val normal: Long, val slow: Long) {
    fun rate(choice: FeeChoice) = when (choice) { FeeChoice.FAST -> fast; FeeChoice.NORMAL -> normal; FeeChoice.SLOW -> slow; FeeChoice.CUSTOM -> null }
}

enum class FeeChoice(private val t: Pair<String, String>, private val e: Pair<String, String>) {
    FAST("Rychle" to "Fast", "~20 min" to "~20 min"), NORMAL("Normálně" to "Normal", "~1 hodina" to "~1 hour"),
    SLOW("Levně" to "Cheap", "~1 den" to "~1 day"), CUSTOM("Vlastní" to "Custom", "" to "");

    val title: String get() = L(t.first, t.second)
    val eta: String get() = L(e.first, e.second)
}

/** Velikost běžné platby (1 vstup, 2 výstupy, SegWit) ve vB – pro orientační odhad poplatku. */
const val TYPICAL_TX_VBYTES = 141

/** Připravené urychlení (RBF): nová transakce nahrazující původní. */
data class PreparedBump(val psbt: Psbt, val oldTxid: String, val oldFeeSat: Long, val newFeeSat: Long, val feeRate: Long, val recipientSat: Long)

data class WalletState(
    val confirmedSat: Long,
    val pendingSat: Long,
    val totalSat: Long,
    val address: String,
    val txs: List<TxItem>,
)

data class PreparedTx(
    val psbt: Psbt, val amountSat: Long, val feeSat: Long, val to: String, val feeRate: Long, val inputs: Int,
    /** Použité mince (txid:vout) – pro značky KYC / bez KYC. */
    val inputIds: List<String> = emptyList(),
)

data class CoinItem(
    val txid: String,
    val vout: Int,
    val valueSat: Long,
    val address: String,
    val confirmed: Boolean,
    val height: Int?,
    val frozen: Boolean,
    /** Drobné (vrácené zpět do peněženky z vlastní platby). */
    val change: Boolean = false,
) {
    val id get() = "$txid:$vout"
}

/**
 * Sledovací (watch-only) peněženka: z veřejného klíče zpub/ypub/xpub nebo z deskriptoru.
 * Vrací dvojici deskriptorů (přijímací, change). Soukromé klíče odmítá.
 */
object WatchOnly {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private fun b58decode(s: String): ByteArray {
        var n = java.math.BigInteger.ZERO
        for (c in s) {
            val i = ALPHABET.indexOf(c)
            require(i >= 0) { L("Neplatný znak v klíči", "Invalid character in the key") }
            n = n.multiply(java.math.BigInteger.valueOf(58)).add(java.math.BigInteger.valueOf(i.toLong()))
        }
        val raw = n.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        val zeros = s.takeWhile { it == '1' }.length
        val full = ByteArray(zeros) + raw
        val data = full.copyOfRange(0, full.size - 4)
        val check = sha256(sha256(data)).copyOfRange(0, 4)
        require(check.contentEquals(full.copyOfRange(full.size - 4, full.size))) { L("Klíč má chybný kontrolní součet – je opsaný správně?", "The key has a wrong checksum – is it copied correctly?") }
        return data
    }

    private fun b58encode(data: ByteArray): String {
        val full = data + sha256(sha256(data)).copyOfRange(0, 4)
        var n = java.math.BigInteger(1, full)
        val sb = StringBuilder()
        val b58 = java.math.BigInteger.valueOf(58)
        while (n > java.math.BigInteger.ZERO) {
            val qr = n.divideAndRemainder(b58)
            sb.append(ALPHABET[qr[1].toInt()]); n = qr[0]
        }
        repeat(full.takeWhile { it == 0.toByte() }.size) { sb.append('1') }
        return sb.reverse().toString()
    }

    /** zpub/ypub → xpub (jen přepis hlavičky, klíč se nemění). */
    fun toXpub(key: String): String {
        val d = b58decode(key)
        require(d.size == 78) { L("Neplatný rozšířený veřejný klíč", "Invalid extended public key") }
        val xpubVersion = byteArrayOf(0x04, 0x88.toByte(), 0xB2.toByte(), 0x1E)
        return b58encode(xpubVersion + d.copyOfRange(4, 78))
    }

    /** Deskriptor nesmí obsahovat žádný soukromý klíč (ani WIF uvnitř multisigu). */
    fun requirePublic(d: Descriptor) {
        require(d.toStringWithSecret() == d.toString()) { L("Deskriptor obsahuje SOUKROMÝ klíč. Pro sledování použijte jen veřejné klíče.", "The descriptor contains a PRIVATE key. For watching use only public keys.") }
    }

    /** Ověří vstup ještě před uložením (aby se soukromý klíč nikdy neuložil). */
    fun validate(input: String) {
        val (e, i) = descriptors(input)
        requirePublic(Descriptor(e, NetworkKind.MAIN)); requirePublic(Descriptor(i, NetworkKind.MAIN))
    }

    fun descriptors(input: String): Pair<String, String> {
        val t = input.trim().replace(Regex("\\s+"), "")
        require(!t.contains("prv")) { L("Toto je SOUKROMÝ klíč. Pro sledování použijte veřejný klíč (zpub/xpub).", "This is a PRIVATE key. For watching use a public key (zpub/xpub).") }
        require(!Regex("(^|[^a-z])[tuv]pub").containsMatchIn(t)) { L("Toto je klíč pro testnet, peněženka je pro mainnet.", "This is a testnet key, the wallet is for mainnet.") }
        return when {
            t.startsWith("zpub") || t.startsWith("xpub") -> {
                val x = toXpub(t); "wpkh($x/0/*)" to "wpkh($x/1/*)"
            }
            t.startsWith("ypub") -> {
                val x = toXpub(t); "sh(wpkh($x/0/*))" to "sh(wpkh($x/1/*))"
            }
            t.contains("(") -> {
                val d = t.substringBefore("#")
                when {
                    d.contains("<0;1>") -> d.replace("<0;1>", "0") to d.replace("<0;1>", "1")
                    d.contains("/0/*") -> d to d.replace("/0/*", "/1/*")
                    else -> throw IllegalArgumentException(L("Deskriptor musí obsahovat /0/* nebo <0;1>", "The descriptor must contain /0/* or <0;1>"))
                }
            }
            else -> throw IllegalArgumentException(L("Vložte zpub, ypub, xpub nebo deskriptor (wpkh(...), tr(...))", "Paste a zpub, ypub, xpub or a descriptor (wpkh(...), tr(...))"))
        }
    }
}

object Seed {
    /**
     * 24 slov z hodů kostkou. Stejná metoda jako Coldcard / SeedSigner:
     * entropie = SHA-256 z textu hodů (znaky '1'–'6'), z ní BIP39 24 slov.
     * Dá se tedy nezávisle ověřit na jiném zařízení.
     */
    fun fromDice(rolls: String): String {
        require(rolls.all { it in '1'..'6' }) { L("Hody smí obsahovat jen čísla 1–6", "Rolls may only contain the numbers 1–6") }
        require(rolls.length >= MIN_ROLLS) { L("Potřeba alespoň $MIN_ROLLS hodů", "At least $MIN_ROLLS rolls are needed") }
        val entropy = MessageDigest.getInstance("SHA-256").digest(rolls.toByteArray(Charsets.US_ASCII))
        return Mnemonic.fromEntropy(entropy).toString()
    }

    fun normalize(words: String): String {
        val clean = words.trim().lowercase().split(Regex("\\s+")).joinToString(" ")
        Mnemonic.fromString(clean)
        return clean
    }
}

/**
 * Připojení k vlastnímu Electrum serveru (Fulcrum / ElectRS).
 * U ssl:// se certifikát uzlu kontroluje proti uloženému otisku (viz CertPinning.kt).
 */
fun connect(url: String, pins: PinStore): ElectrumClient {
    if (url.isBlank()) throw IllegalStateException(NO_SERVER)
    val addr = ServerAddr.parse(url)
    val public = isPublicServer(url)
    // Vlastní uzel (certifikát podepsaný sám sebou): ověříme ho proti uloženému otisku.
    // Veřejný server: certifikát ověří certifikační autorita (připínání by se rozbíjelo při každé obnově).
    val client = if (addr.ssl && !public) {
        // 1) ověř / připni certifikát (při neshodě srozumitelná výjimka pro uživatele),
        // 2) BDK pusť jen přes relay, který certifikát kontroluje u KAŽDÉHO spojení.
        verifyServerCert(addr.host, addr.port, pins)
        val relay = PinnedRelay.forServer(addr.host, addr.port, pins)
        try {
            ElectrumClient("tcp://127.0.0.1:${relay.localPort}", null, 20.toUByte(), 2.toUByte(), false)
        } catch (e: Exception) {
            relay.lastMismatch?.let { throw it }
            throw e
        }
    } else {
        ElectrumClient(normalizeServerUrl(url), null, 20.toUByte(), 2.toUByte(), public)
    }
    val genesis = client.serverFeatures().genesisHash.toString()
    check(genesis == MAINNET_GENESIS) { L("Server není Bitcoin mainnet – odmítám se připojit.", "The server is not Bitcoin mainnet – refusing to connect.") }
    return client
}

/** Ověří spojení a vrátí popis pro uživatele. */
fun testServer(url: String, pins: PinStore): String {
    val c = connect(url, pins)
    val tip = c.blockHeadersSubscribe().height
    val ver = c.serverFeatures().serverVersion
    val addr = ServerAddr.parse(url)
    val sec = when {
        isPublicServer(url) -> L("Šifrováno, certifikát ověřen certifikační autoritou.\n⚠ Veřejný server vidí vaše adresy, zůstatek a IP adresu.", "Encrypted, certificate verified by a certificate authority.\n⚠ A public server sees your addresses, balance and IP address.")
        addr.ssl -> L("Šifrováno, certifikát ověřen:\n${pins.get(addr.hostPort)?.take(23)}…", "Encrypted, certificate verified:\n${pins.get(addr.hostPort)?.take(23)}…")
        else -> L("⚠ Nešifrované spojení (tcp). Doporučujeme ssl://.", "⚠ Unencrypted connection (tcp). We recommend ssl://.")
    }
    return L("Spojeno ✔\n$ver\nPoslední blok: $tip\n\n$sec", "Connected ✔\n$ver\nLatest block: $tip\n\n$sec")
}

/**
 * Veškerá práce s BDK. Nevolat z hlavního vlákna (síť i disk).
 */
class WalletCore(private val dbFile: File, val secret: String, var serverUrl: String, private val pins: PinStore) {

    companion object {
        const val WATCH_PREFIX = "watch:"
    }

    /** true = jen sledování, nemá soukromé klíče, nemůže podepisovat. */
    val watchOnly = secret.startsWith(WATCH_PREFIX)
    val mnemonicWords: String? = if (watchOnly) null else secret

    private val external: Descriptor
    private val internal: Descriptor

    init {
        if (watchOnly) {
            val (e, i) = WatchOnly.descriptors(secret.removePrefix(WATCH_PREFIX))
            external = Descriptor(e, NetworkKind.MAIN)
            internal = Descriptor(i, NetworkKind.MAIN)
            WatchOnly.requirePublic(external); WatchOnly.requirePublic(internal)
        } else {
            val secretKey = DescriptorSecretKey(NetworkKind.MAIN, Mnemonic.fromString(secret), null)
            external = Descriptor.newBip84(secretKey, KeychainKind.EXTERNAL, NetworkKind.MAIN)
            internal = Descriptor.newBip84(secretKey, KeychainKind.INTERNAL, NetworkKind.MAIN)
        }
    }
    private val persister = Persister.newSqlite(dbFile.absolutePath)
    private var client: ElectrumClient? = null
    private var clientUrl: String? = null

    private val wallet: Wallet = try {
        Wallet.load(external, internal, persister)
    } catch (e: Exception) {
        Wallet(external, internal, NETWORK, persister)
    }

    private fun client(): ElectrumClient {
        val c = client
        if (c != null && clientUrl == serverUrl) return c
        val n = connect(serverUrl, pins)
        client = n; clientUrl = serverUrl
        return n
    }

    private fun <T> withClient(block: (ElectrumClient) -> T): T = try {
        block(client())
    } catch (e: Exception) {
        // Spojení mohlo spadnout (změna Wi-Fi apod.) – jeden pokus znovu.
        client = null
        block(client())
    }

    fun sync() {
        val request = wallet.startFullScan().build()
        val update = withClient { it.fullScan(request, 20uL, 10uL, true) }
        wallet.applyUpdate(update)
        wallet.persist(persister)
    }

    /** Veřejný deskriptor – lze vložit do jiné peněženky (Sparrow…) nebo sem jako sledovací. */
    fun publicDescriptor(): String = wallet.publicDescriptor(KeychainKind.EXTERNAL)

    fun coins(): List<CoinItem> = wallet.listUnspent().map { u ->
        val pos = u.chainPosition
        CoinItem(
            txid = u.outpoint.txid.toString(),
            vout = u.outpoint.vout.toInt(),
            valueSat = u.txout.value.toSat().toLong(),
            address = try { Address.fromScript(u.txout.scriptPubkey, NETWORK).toString() } catch (e: Exception) { "?" },
            confirmed = pos is ChainPosition.Confirmed,
            height = (pos as? ChainPosition.Confirmed)?.confirmationBlockTime?.blockId?.height?.toInt(),
            frozen = wallet.isOutpointLocked(u.outpoint),
            change = u.keychain == KeychainKind.INTERNAL,
        )
    }.sortedByDescending { it.valueSat }

    fun setFrozen(txid: String, vout: Int, frozen: Boolean) {
        val op = OutPoint(Txid.fromString(txid), vout.toUInt())
        if (frozen) wallet.lockOutpoint(op) else wallet.unlockOutpoint(op)
        wallet.persist(persister)
    }

    fun state(): WalletState {
        val b = wallet.balance()
        val addr = wallet.nextUnusedAddress(KeychainKind.EXTERNAL).address.toString()
        wallet.persist(persister)
        val txs = wallet.transactions().mapNotNull { ctx ->
            val txid = ctx.transaction.computeTxid()
            val d = wallet.txDetails(txid) ?: return@mapNotNull null
            when (val pos = ctx.chainPosition) {
                is ChainPosition.Confirmed -> TxItem(
                    txid.toString(), d.balanceDelta, d.fee?.toSat()?.toLong(), true,
                    pos.confirmationBlockTime.blockId.height.toInt(),
                    pos.confirmationBlockTime.confirmationTime.toLong(),
                    feeRate = d.feeRate?.toSatPerVbCeil()?.toLong(),
                )
                is ChainPosition.Unconfirmed -> TxItem(
                    txid.toString(), d.balanceDelta, d.fee?.toSat()?.toLong(), false,
                    null, pos.timestamp?.toLong(),
                    feeRate = d.feeRate?.toSatPerVbCeil()?.toLong(),
                    canBump = !watchOnly && d.balanceDelta < 0 && d.fee != null && ctx.transaction.isExplicitlyRbf(),
                )
            }
        }.sortedWith(compareBy<TxItem> { it.confirmed }.thenByDescending { it.height ?: 0 })
        return WalletState(
            confirmedSat = b.confirmed.toSat().toLong(),
            pendingSat = (b.trustedPending.toSat() + b.untrustedPending.toSat()).toLong(),
            totalSat = b.total.toSat().toLong(),
            address = addr,
            txs = txs,
        )
    }

    fun firstAddress(): String = wallet.peekAddress(KeychainKind.EXTERNAL, 0u).address.toString()

    fun newAddress(): String {
        val a = wallet.revealNextAddress(KeychainKind.EXTERNAL).address.toString()
        wallet.persist(persister)
        return a
    }

    /**
     * @param selected vybrané mince (txid:vout). Prázdné = automatický výběr (bez zmrazených mincí).
     * @param avoid mince, které automatický výběr nesmí použít (jiná skupina KYC / bez KYC).
     */
    fun prepare(to: String, amountSat: Long, feeRateSatVb: Long, sendAll: Boolean, selected: Set<String> = emptySet(), avoid: Set<String> = emptySet()): PreparedTx {
        check(!watchOnly) { L("Sledovací peněženka nemůže odesílat.", "A watch-only wallet can't send.") }
        val address = try {
            Address(to.trim(), NETWORK)
        } catch (e: Exception) {
            throw IllegalArgumentException(L("Neplatná bitcoinová adresa", "Invalid bitcoin address"))
        }
        require(feeRateSatVb in 1..500) { L("Poplatek musí být 1–500 sat/vB", "The fee must be 1–500 sat/vB") }
        var builder = TxBuilder().feeRate(FeeRate.fromSatPerVb(feeRateSatVb.toULong()))
        val available = coins().filter { !it.frozen && (selected.isNotEmpty() || it.id !in avoid) }
        val chosen = if (selected.isNotEmpty()) available.filter { it.id in selected } else emptyList()
        require(selected.isEmpty() || chosen.size == selected.size) { L("Některá vybraná mince už není k dispozici nebo je zmrazená.", "A selected coin is no longer available or is frozen.") }
        if (chosen.isNotEmpty()) {
            builder = builder.addUtxos(chosen.map { OutPoint(Txid.fromString(it.txid), it.vout.toUInt()) }).manuallySelectedOnly()
        } else if (avoid.isNotEmpty()) {
            builder = builder.unspendable(avoid.map { id -> OutPoint(Txid.fromString(id.substringBefore(':')), id.substringAfter(':').toUInt()) })
        }
        builder = if (sendAll) {
            val spend = chosen.ifEmpty { available }
            require(spend.isNotEmpty()) { if (avoid.isNotEmpty()) L("Ve zvolené skupině nejsou žádné mince.", "There are no coins in the chosen group.") else L("Nemáte žádné nezmrazené mince.", "You have no unfrozen coins.") }
            if (chosen.isEmpty()) builder = builder.addUtxos(spend.map { OutPoint(Txid.fromString(it.txid), it.vout.toUInt()) }).manuallySelectedOnly()
            builder.drainTo(address.scriptPubkey())
        } else {
            require(amountSat > 0) { L("Zadejte částku", "Enter an amount") }
            builder.addRecipient(address.scriptPubkey(), Amount.fromSat(amountSat.toULong()))
        }
        val psbt = builder.finish(wallet)
        val fee = psbt.fee().toLong()
        val tx = psbt.extractTxUncheckedFeeRate()
        val target = address.scriptPubkey().toBytes()
        val amount = tx.output()
            .filter { it.scriptPubkey.toBytes().contentEquals(target) }
            .sumOf { it.value.toSat().toLong() }
        val inputIds = tx.input().map { "${it.previousOutput.txid}:${it.previousOutput.vout}" }
        return PreparedTx(psbt, amount, fee, to.trim(), feeRateSatVb, tx.input().size, inputIds)
    }

    fun signAndBroadcast(p: PreparedTx): String {
        check(!watchOnly) { L("Sledovací peněženka nemůže podepisovat.", "A watch-only wallet can't sign.") }
        check(wallet.sign(p.psbt)) { L("Transakci se nepodařilo podepsat", "Couldn't sign the transaction") }
        val tx = p.psbt.extractTx()
        return broadcastAndApply(tx)
    }

    /**
     * Odešle transakci a hned ji zapíše do peněženky jako nepotvrzenou (použité mince se už nenabídnou znovu).
     * Pokud uzel odpoví „už ji znám“ (první pokus prošel, jen se ztratila odpověď), bere se to jako úspěch.
     */
    private fun broadcastAndApply(tx: org.bitcoindevkit.Transaction): String {
        val txid = tx.computeTxid().toString()
        try {
            withClient { it.transactionBroadcast(tx) }
        } catch (e: Exception) {
            // Odpověď se mohla jen ztratit (nebo „už ji znám“): zeptáme se uzlu, jestli transakci má.
            if (!isOwnTxKnown(txid)) {
                throw IllegalStateException(
                    L("Transakci se nepodařilo odeslat, nebo to nejde ověřit. Nic znovu neposílejte, dokud se peněženka nenačte z uzlu.\n", "The transaction couldn't be sent, or it can't be verified. Don't send anything again until the wallet has reloaded from the node.\n") +
                        L("Zkontrolujte ji v Mempoolu.\nTXID: $txid\n\nDetail: ${e.message}", "Check it in Mempool.\nTXID: $txid\n\nDetail: ${e.message}")
                )
            }
        }
        wallet.applyUnconfirmedTxs(listOf(UnconfirmedTx(tx, (System.currentTimeMillis() / 1000).toULong())))
        wallet.persist(persister)
        return txid
    }

    private fun isOwnTxKnown(txid: String): Boolean = try {
        withClient { it.transactionGetRaw(Txid.fromString(txid)) }; true
    } catch (_: Exception) { false }

    /**
     * Urychlení nepotvrzené odeslané transakce (Replace-By-Fee): stejná platba, vyšší poplatek.
     * Příjemce dostane stejnou částku, poplatek navíc se vezme z drobných (u „poslat vše“ z odesílané částky).
     */
    fun prepareBump(txid: String, feeRateSatVb: Long): PreparedBump {
        check(!watchOnly) { L("Sledovací peněženka nemůže odesílat.", "A watch-only wallet can't send.") }
        require(feeRateSatVb in 1..500) { L("Poplatek musí být 1–500 sat/vB", "The fee must be 1–500 sat/vB") }
        val id = Txid.fromString(txid)
        val old = wallet.txDetails(id) ?: throw IllegalArgumentException(L("Transakce v peněžence není.", "The transaction is not in the wallet."))
        val psbt = try {
            BumpFeeTxBuilder(id, FeeRate.fromSatPerVb(feeRateSatVb.toULong())).finish(wallet)
        } catch (e: CreateTxException.FeeRateTooLow) {
            val req = Regex("\\d+").find(e.message.orEmpty())?.value?.toLongOrNull()
            val vb = req?.let { kotlin.math.ceil(it * 4 / 1000.0).toLong() }
            throw IllegalArgumentException(L("Nový poplatek je příliš nízký.", "The new fee is too low.") + (vb?.let { L(" Musí být aspoň $it sat/vB.", " It must be at least $it sat/vB.") } ?: ""))
        } catch (e: CreateTxException.FeeTooLow) {
            throw IllegalArgumentException(L("Nový poplatek je příliš nízký – zvyšte ho.", "The new fee is too low – raise it."))
        } catch (e: CreateTxException.CoinSelection) {
            throw IllegalArgumentException(L("Na vyšší poplatek nemáte v peněžence dost prostředků.", "There aren't enough funds in the wallet for a higher fee."))
        } catch (e: CreateTxException.InsufficientFunds) {
            throw IllegalArgumentException(L("Na vyšší poplatek nemáte v peněžence dost prostředků.", "There aren't enough funds in the wallet for a higher fee."))
        } catch (e: CreateTxException) {
            throw IllegalArgumentException(L("Transakci nelze urychlit (${e::class.simpleName}). Možná už je potvrzená – obnovte peněženku.", "The transaction can't be sped up (${e::class.simpleName}). Maybe it's already confirmed – refresh the wallet."))
        }
        val tx = psbt.extractTxUncheckedFeeRate()
        val recipient = tx.output().filter { !wallet.isMine(it.scriptPubkey) }.sumOf { it.value.toSat().toLong() }
        return PreparedBump(psbt, txid, old.fee?.toSat()?.toLong() ?: 0, psbt.fee().toLong(), feeRateSatVb, recipient)
    }

    fun signAndBroadcastBump(b: PreparedBump): String {
        check(!watchOnly) { L("Sledovací peněženka nemůže podepisovat.", "A watch-only wallet can't sign.") }
        check(wallet.sign(b.psbt)) { L("Transakci se nepodařilo podepsat", "Couldn't sign the transaction") }
        // Peněženka hned uvidí náhradu (původní transakce zmizí, nečeká se na další synchronizaci).
        return broadcastAndApply(b.psbt.extractTx())
    }

    /**
     * Odhady poplatků z vlastního uzlu (Bitcoin Core estimatesmartfee přes Electrum server).
     * Rychle = do 2 bloků (~20 min), Normálně = do 6 bloků (~1 h), Levně = do 144 bloků (~1 den).
     */
    fun feeEstimates(): FeeEstimates {
        fun est(blocks: Long): Long? = try {
            val btcPerKb = withClient { it.estimateFee(blocks.toULong()) }
            if (btcPerKb <= 0) null else kotlin.math.ceil(btcPerKb * 100_000_000 / 1000).toLong().coerceAtLeast(1)
        } catch (e: Exception) { null }
        val slow = est(144) ?: 1
        val normal = maxOf(est(6) ?: slow, slow)
        val fast = maxOf(est(2) ?: normal, normal)
        return FeeEstimates(fast, normal, slow)
    }

    /** Doporučený poplatek z vlastního uzlu (sat/vB) pro potvrzení do ~6 bloků. */
    fun suggestedFeeRate(): Long = try {
        val btcPerKb = withClient { it.estimateFee(6uL) }
        if (btcPerKb <= 0) 2 else kotlin.math.ceil(btcPerKb * 100_000_000 / 1000).toLong().coerceAtLeast(1)
    } catch (e: Exception) {
        2
    }
}
