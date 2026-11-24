package io.github.milahajek.hayek

import org.junit.Assert.*
import org.junit.Test

class ColdcardTest {
    private val xpub = "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V"

    @Test fun descriptorFromColdcardExport() {
        val d = "wpkh([0f056943/84h/0h/0h]$xpub/<0;1>/*)#7e5uhuge"
        assertEquals(d, ColdcardNfc.watchSource("# Coldcard descriptor export\n$d\n"))
        val (e, i) = WatchOnly.descriptors(ColdcardNfc.watchSource(d)!!)
        assertEquals("wpkh([0f056943/84h/0h/0h]$xpub/0/*)", e)
        assertEquals("wpkh([0f056943/84h/0h/0h]$xpub/1/*)", i)
    }

    @Test fun genericJson() {
        val j = """{"chain":"BTC","xfp":"0F056943","bip84":{"xpub":"$xpub","_pub":"zpub...","deriv":"m/84'/0'/0'","name":"p2wpkh"}}"""
        assertEquals("wpkh([0f056943/84h/0h/0h]$xpub/<0;1>/*)", ColdcardNfc.watchSource(j))
    }

    @Test fun signedRecordSelection() {
        val written = byteArrayOf(1, 2, 3)
        assertNull(ColdcardNfc.signedFrom(listOf("bitcoin.org:psbt" to written), written))   // náš vlastní, nepodepsaný
        assertArrayEquals(byteArrayOf(9), ColdcardNfc.signedFrom(listOf("text" to byteArrayOf(0), "bitcoin.org:txn" to byteArrayOf(9)), written))
        assertArrayEquals(byteArrayOf(4), ColdcardNfc.signedFrom(listOf("bitcoin.org:psbt" to byteArrayOf(4)), written))
    }

    @Test fun textRecord() {
        val p = byteArrayOf(2) + "en".toByteArray() + "Ahoj".toByteArray()
        assertEquals("Ahoj", ColdcardNfc.textPayload(p))
    }

    @Test fun formatDetection() {
        assertEquals(Bip174.Kind.PSBT_BINARY, Bip174.kind(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte(), 1)))
        assertEquals(Bip174.Kind.PSBT_BASE64, Bip174.kind("cHNidP8BAH0CAAAA\n".toByteArray()))
        assertEquals(Bip174.Kind.PSBT_HEX, Bip174.kind("70736274ff0100".toByteArray()))
        assertEquals(Bip174.Kind.TX_HEX, Bip174.kind("02000000000101".toByteArray()))
        assertEquals(Bip174.Kind.TX_BINARY, Bip174.kind(byteArrayOf(2, 0, 0, 0, 0, 1, 1)))
        assertNull(Bip174.txBytes(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())))
    }
}
