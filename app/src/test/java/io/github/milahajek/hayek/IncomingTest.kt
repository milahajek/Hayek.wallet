package io.github.milahajek.hayek

import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingTest {
    private fun tx(id: String, d: Long) = TxItem(id, d, 0, true, 1, 0)

    @Test fun firstRunIsSilentThenOnlyNew() {
        val all = listOf(tx("a", 1000), tx("b", 2000))
        assertEquals(emptyList<TxItem>(), IncomingNotifier.freshIncoming(all, emptySet(), initialized = false))
        assertEquals(listOf("b"), IncomingNotifier.freshIncoming(all, setOf("a"), initialized = true).map { it.txid })
    }

    @Test fun publicDescriptorOfOwnWalletWorksAsWatchOnly() {
        val d = "wpkh([73c5da0a/84'/0'/0']xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V/0/*)#abcd1234"
        val (e, i) = WatchOnly.descriptors(d)
        assertEquals(d.substringBefore("#"), e)
        assertEquals(d.substringBefore("#").replace("/0/*)", "/1/*)"), i)
    }

    @org.junit.Test fun entriesRoundTrip() {
        val l = listOf(IncomingNotifier.Entry("w1", "Kapesní", "wpkh([73c5da0a/84h/0h/0h]xpub/0/*)"), IncomingNotifier.Entry("w2", "Trezor", "wpkh(xpub2/0/*)"))
        org.junit.Assert.assertEquals(l, IncomingNotifier.fromJson(IncomingNotifier.toJson(l)))
        org.junit.Assert.assertTrue(IncomingNotifier.fromJson("nesmysl").isEmpty())
    }
}
