package io.github.milahajek.hayek

import org.bitcoindevkit.Address
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.DerivationPath
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import org.bitcoindevkit.NetworkKind
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Psbt
import org.bitcoindevkit.Transaction
import org.bitcoindevkit.Wallet
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

/**
 * Cesta peněz se skutečnou knihovnou BDK, bez uzlu: mince se do peněženky vloží uměle (jako nepotvrzené),
 * podepisuje samostatná „podpisová“ peněženka (zastupuje Coldcard). Odeslání pak vždy selže na chybějícím uzlu –
 * důležité je, JAK selže: neshoda s připravenou platbou, nebo až samotné odeslání.
 */
class SendCheckTest {
    @get:Rule val tmp = TemporaryFolder()

    private val noPins = object : PinStore {
        override fun get(hostPort: String): String? = null
        override fun set(hostPort: String, fingerprint: String) {}
    }
    private val words = "few educate sugar bless boring random strategy waste mutual cargo type hawk prefer denial scan abstract filter extend dignity balcony dust unusual correct bubble"
    private val dest = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
    private val key get() = DescriptorSecretKey(NetworkKind.MAIN, Mnemonic.fromString(words), null)

    private fun le(v: Long, n: Int) = ByteArray(n) { i -> (v shr (8 * i)).toByte() }

    /** Umělá transakce, která posílá zadané částky na zadanou adresu (vstup je vymyšlený). */
    private fun funding(address: String, amounts: List<Long>, salt: Int): ByteArray {
        val script = Address(address, Network.BITCOIN).scriptPubkey().toBytes()
        return ByteArrayOutputStream().apply {
            write(le(2, 4)); write(1)
            write(ByteArray(32) { (it + salt).toByte() }); write(le(0, 4)); write(0); write(le(0xFFFFFFFDL, 4))
            write(amounts.size)
            amounts.forEach { write(le(it, 8)); write(script.size); write(script) }
            write(le(0, 4))
        }.toByteArray()
    }

    private fun txid(raw: ByteArray) = Transaction(raw).computeTxid().toString()

    /** Podepíše KOPII připraveného PSBT a vrátí hotovou transakci – tak, jak ji vrací Coldcard. */
    private fun sign(signer: Wallet, psbt: Psbt): ByteArray {
        val copy = Psbt(psbt.serialize())
        assertTrue(signer.sign(copy))
        return copy.extractTx().serialize()
    }

    private fun mismatch(e: Throwable) = e.message.orEmpty().let { it.contains("NEODPOVÍDÁ") || it.contains("DOES NOT match") }
    private fun inputs(p: Psbt) = p.extractTxUncheckedFeeRate().input().map { "${it.previousOutput.txid}:${it.previousOutput.vout}" }

    @Test fun signedTransactionMustBeExactlyThePreparedOne() {
        val w = WalletCore(tmp.newFile(), words, "", noPins)
        w.injectUnconfirmed(funding(w.state().address, listOf(100_000, 60_000), 1))
        val signer = Wallet(Descriptor.newBip84(key, KeychainKind.EXTERNAL, NetworkKind.MAIN), Descriptor.newBip84(key, KeychainKind.INTERNAL, NetworkKind.MAIN), Network.BITCOIN, Persister.newInMemory())

        val p = w.prepare(dest, 30_000, 2, false)
        assertEquals(30_000, p.amountSat)
        // správně podepsaná: projde kontrolou a selže až na odeslání (uzel není)
        val ok = assertThrows(Exception::class.java) { w.broadcastSigned(p, sign(signer, p.psbt)) }
        assertFalse(ok.message, mismatch(ok)); assertTrue(ok.message, ok.message.orEmpty().contains("TXID"))
        // jiná částka, jiný příjemce, jiný poplatek: odmítnuto jako neshoda
        for (other in listOf(w.prepare(dest, 30_001, 2, false), w.prepare(w.state().address, 30_000, 2, false), w.prepare(dest, 30_000, 3, false))) {
            val bad = assertThrows(Exception::class.java) { w.broadcastSigned(p, sign(signer, other.psbt)) }
            assertTrue(bad.message, mismatch(bad))
        }
        // nepodepsaná se neodešle
        assertThrows(Exception::class.java) { w.broadcastSigned(p, w.psbtBytes(p)) }
    }

    @Test fun wrappedSegwitWatchWalletCanSendWhatColdcardSigns() {
        // ypub / adresy začínající 3: podpis mění TXID, proto se neporovnává TXID, ale obsah transakce
        val acct = key.derive(DerivationPath("m/49h/0h/0h"))
        val pub = acct.asPublic().toString().removeSuffix("/*"); val prv = acct.toString().removeSuffix("/*")
        val w = WalletCore(tmp.newFile(), WalletCore.WATCH_PREFIX + "sh(wpkh($pub/0/*))", "", noPins)
        val addr = w.state().address
        assertTrue(addr, addr.startsWith("3"))
        w.injectUnconfirmed(funding(addr, listOf(100_000), 2))
        val signer = Wallet(Descriptor("sh(wpkh($prv/0/*))", NetworkKind.MAIN), Descriptor("sh(wpkh($prv/1/*))", NetworkKind.MAIN), Network.BITCOIN, Persister.newInMemory())

        val p = w.prepare(dest, 30_000, 2, false)
        val signed = sign(signer, p.psbt)
        assertNotEquals(p.psbt.extractTxUncheckedFeeRate().computeTxid().toString(), txid(signed))
        val ok = assertThrows(Exception::class.java) { w.broadcastSigned(p, signed) }
        assertFalse(ok.message, mismatch(ok))
        val bad = assertThrows(Exception::class.java) { w.broadcastSigned(p, sign(signer, w.prepare(dest, 30_001, 2, false).psbt)) }
        assertTrue(bad.message, mismatch(bad))
    }

    @Test fun speedUpWithCoinGroupsLeavesNoTrace() {
        // Pozn.: BDK k urychlení přibírá jen POTVRZENÉ mince a ty bez uzlu vyrobit nejde – přibrání mince
        // z povolené skupiny proto tady ověřit nelze. Ověřuje se vše ostatní kolem dočasného zmrazení.
        val w = WalletCore(tmp.newFile(), words, "", noPins)
        val fund = funding(w.state().address, listOf(40_000, 50_000, 60_000), 3)
        val f = txid(fund)
        w.injectUnconfirmed(fund)
        val signer = Wallet(Descriptor.newBip84(key, KeychainKind.EXTERNAL, NetworkKind.MAIN), Descriptor.newBip84(key, KeychainKind.INTERNAL, NetworkKind.MAIN), Network.BITCOIN, Persister.newInMemory())
        // platba z mince 0, zbydou jen malé drobné
        val p = w.prepare(dest, 39_000, 1, false, selected = setOf("$f:0"))
        assertEquals(listOf("$f:0"), p.inputIds)
        val sent = sign(signer, p.psbt)
        w.injectUnconfirmed(sent)
        val avoid = setOf("$f:1", "$f:2")

        // malé zvýšení zaplatí drobné: žádná další mince, příjemce dostane stejně
        val small = w.prepareBump(txid(sent), 3, avoid)
        assertEquals(listOf("$f:0"), inputs(small.psbt)); assertTrue(small.mixedInputIds.isEmpty())
        assertEquals(39_000, small.recipientSat); assertTrue(small.newFeeSat > small.oldFeeSat)
        // velké zvýšení drobné nezaplatí a potvrzené mince nejsou: srozumitelná chyba, ne pád
        assertThrows(IllegalArgumentException::class.java) { w.prepareBump(txid(sent), 50, avoid) }
        assertThrows(IllegalArgumentException::class.java) { w.prepareBump(txid(sent), 50) }
        // dočasné zmrazení po sobě nic nenechá – ani po úspěchu, ani po chybě
        assertTrue(w.coins().none { it.frozen })
        // a trvalé zmrazení uživatele zůstane, jak bylo
        w.setFrozen(f, 1, true)
        w.prepareBump(txid(sent), 3, avoid)
        assertThrows(IllegalArgumentException::class.java) { w.prepareBump(txid(sent), 50, avoid) }
        assertEquals(listOf("$f:1"), w.coins().filter { it.frozen }.map { it.id })
    }
}
