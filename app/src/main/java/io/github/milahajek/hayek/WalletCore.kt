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
import org.bitcoindevkit.Wallet
import java.io.File
import java.security.MessageDigest

/** SKUTEČNÝ Bitcoin (mainnet). */
val NETWORK = Network.BITCOIN
/** Žádný výchozí server – každý uživatel zadá svůj vlastní uzel. */
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
)

data class WalletState(
    val confirmedSat: Long,
    val pendingSat: Long,
    val totalSat: Long,
    val address: String,
    val txs: List<TxItem>,
)

data class PreparedTx(val psbt: Psbt, val amountSat: Long, val feeSat: Long, val to: String, val feeRate: Long, val inputs: Int)

data class CoinItem(
    val txid: String,
    val vout: Int,
    val valueSat: Long,
    val address: String,
    val confirmed: Boolean,
    val height: Int?,
    val frozen: Boolean,
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
            require(i >= 0) { "Neplatný znak v klíči" }
            n = n.multiply(java.math.BigInteger.valueOf(58)).add(java.math.BigInteger.valueOf(i.toLong()))
        }
        val raw = n.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        val zeros = s.takeWhile { it == '1' }.length
        val full = ByteArray(zeros) + raw
        val data = full.copyOfRange(0, full.size - 4)
        val check = sha256(sha256(data)).copyOfRange(0, 4)
        require(check.contentEquals(full.copyOfRange(full.size - 4, full.size))) { "Klíč má chybný kontrolní součet – je opsaný správně?" }
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
        require(d.size == 78) { "Neplatný rozšířený veřejný klíč" }
        val xpubVersion = byteArrayOf(0x04, 0x88.toByte(), 0xB2.toByte(), 0x1E)
        return b58encode(xpubVersion + d.copyOfRange(4, 78))
    }

    fun descriptors(input: String): Pair<String, String> {
        val t = input.trim().replace(Regex("\\s+"), "")
        require(!t.contains("prv")) { "Toto je SOUKROMÝ klíč. Pro sledování použijte veřejný klíč (zpub/xpub)." }
        require(!Regex("(^|[^a-z])[tuv]pub").containsMatchIn(t)) { "Toto je klíč pro testnet, peněženka je pro mainnet." }
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
                    else -> throw IllegalArgumentException("Deskriptor musí obsahovat /0/* nebo <0;1>")
                }
            }
            else -> throw IllegalArgumentException("Vložte zpub, ypub, xpub nebo deskriptor (wpkh(...), tr(...))")
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
        require(rolls.all { it in '1'..'6' }) { "Hody smí obsahovat jen čísla 1–6" }
        require(rolls.length >= MIN_ROLLS) { "Potřeba alespoň $MIN_ROLLS hodů" }
        val entropy = MessageDigest.getInstance("SHA-256").digest(rolls.toByteArray(Charsets.US_ASCII))
        return Mnemonic.fromEntropy(entropy).toString()
    }

    fun normalize(words: String): String {
        val clean = words.trim().lowercase().split(Regex("\\s+")).joinToString(" ")
        Mnemonic.fromString(clean)
        return clean
    }
}

/** Připojení k vlastnímu Electrum serveru (Electrs / Fulcrum na RaspiBlitzu). */
fun connect(url: String): ElectrumClient {
    val u = url.trim()
    if (u.isBlank()) throw IllegalStateException(NO_SERVER)
    // Uzel doma používá certifikát podepsaný sám sebou, proto bez ověření domény.
    val client = ElectrumClient(u, null, 20.toUByte(), 2.toUByte(), false)
    val genesis = client.serverFeatures().genesisHash.toString()
    check(genesis == MAINNET_GENESIS) { "Server není Bitcoin mainnet – odmítám se připojit." }
    return client
}

/** Ověří spojení a vrátí výšku posledního bloku. */
fun testServer(url: String): String {
    val c = connect(url)
    val tip = c.blockHeadersSubscribe().height
    val ver = c.serverFeatures().serverVersion
    return "Spojeno ✔\n$ver\nPoslední blok: $tip"
}

/**
 * Veškerá práce s BDK. Nevolat z hlavního vlákna (síť i disk).
 */
class WalletCore(private val dbFile: File, val secret: String, var serverUrl: String) {

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
            check(!external.toStringWithSecret().contains("prv")) { "Deskriptor obsahuje soukromý klíč" }
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
        val n = connect(serverUrl)
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
                )
                is ChainPosition.Unconfirmed -> TxItem(
                    txid.toString(), d.balanceDelta, d.fee?.toSat()?.toLong(), false,
                    null, pos.timestamp?.toLong(),
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
     */
    fun prepare(to: String, amountSat: Long, feeRateSatVb: Long, sendAll: Boolean, selected: Set<String> = emptySet()): PreparedTx {
        check(!watchOnly) { "Sledovací peněženka nemůže odesílat." }
        val address = try {
            Address(to.trim(), NETWORK)
        } catch (e: Exception) {
            throw IllegalArgumentException("Neplatná bitcoinová adresa")
        }
        require(feeRateSatVb in 1..500) { "Poplatek musí být 1–500 sat/vB" }
        var builder = TxBuilder().feeRate(FeeRate.fromSatPerVb(feeRateSatVb.toULong()))
        val available = coins().filter { !it.frozen }
        val chosen = if (selected.isNotEmpty()) available.filter { it.id in selected } else emptyList()
        require(selected.isEmpty() || chosen.size == selected.size) { "Některá vybraná mince už není k dispozici nebo je zmrazená." }
        if (chosen.isNotEmpty()) {
            builder = builder.addUtxos(chosen.map { OutPoint(Txid.fromString(it.txid), it.vout.toUInt()) }).manuallySelectedOnly()
        }
        builder = if (sendAll) {
            val spend = chosen.ifEmpty { available }
            require(spend.isNotEmpty()) { "Nemáte žádné nezmrazené mince." }
            if (chosen.isEmpty()) builder = builder.addUtxos(spend.map { OutPoint(Txid.fromString(it.txid), it.vout.toUInt()) }).manuallySelectedOnly()
            builder.drainTo(address.scriptPubkey())
        } else {
            require(amountSat > 0) { "Zadejte částku" }
            builder.addRecipient(address.scriptPubkey(), Amount.fromSat(amountSat.toULong()))
        }
        val psbt = builder.finish(wallet)
        val fee = psbt.fee().toLong()
        val tx = psbt.extractTxUncheckedFeeRate()
        val target = address.scriptPubkey().toBytes()
        val amount = tx.output()
            .filter { it.scriptPubkey.toBytes().contentEquals(target) }
            .sumOf { it.value.toSat().toLong() }
        return PreparedTx(psbt, amount, fee, to.trim(), feeRateSatVb, tx.input().size)
    }

    fun signAndBroadcast(p: PreparedTx): String {
        check(!watchOnly) { "Sledovací peněženka nemůže podepisovat." }
        check(wallet.sign(p.psbt)) { "Transakci se nepodařilo podepsat" }
        val tx = p.psbt.extractTx()
        return withClient { it.transactionBroadcast(tx) }.toString()
    }

    /** Doporučený poplatek z vlastního uzlu (sat/vB) pro potvrzení do ~6 bloků. */
    fun suggestedFeeRate(): Long = try {
        val btcPerKb = withClient { it.estimateFee(6uL) }
        if (btcPerKb <= 0) 2 else kotlin.math.ceil(btcPerKb * 100_000_000 / 1000).toLong().coerceAtLeast(1)
    } catch (e: Exception) {
        2
    }
}
