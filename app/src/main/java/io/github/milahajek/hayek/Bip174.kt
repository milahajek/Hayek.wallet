package io.github.milahajek.hayek

import org.bitcoindevkit.Transaction

/** Rozpoznání formátu, ve kterém vrací podepsanou transakci hardwarová peněženka (Coldcard, Sparrow…). */
object Bip174 {
    private val MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())   // "psbt" 0xff

    enum class Kind { PSBT_BINARY, PSBT_BASE64, PSBT_HEX, TX_BINARY, TX_HEX }

    fun kind(data: ByteArray): Kind {
        if (data.size >= 5 && data.copyOfRange(0, 5).contentEquals(MAGIC)) return Kind.PSBT_BINARY
        val text = String(data, Charsets.US_ASCII).trim()
        return when {
            text.startsWith("cHNidP") -> Kind.PSBT_BASE64
            text.startsWith("70736274ff", ignoreCase = true) -> Kind.PSBT_HEX
            text.isNotEmpty() && text.length % 2 == 0 && text.all { it in "0123456789abcdefABCDEF" } -> Kind.TX_HEX
            else -> Kind.TX_BINARY
        }
    }

    fun hex(s: String): ByteArray = s.trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Base64 podoba PSBT (pro BDK), nebo null, když jde o hotovou transakci. */
    fun psbtBase64(data: ByteArray): String? = when (kind(data)) {
        Kind.PSBT_BINARY -> java.util.Base64.getEncoder().encodeToString(data)
        Kind.PSBT_BASE64 -> String(data, Charsets.US_ASCII).trim()
        Kind.PSBT_HEX -> java.util.Base64.getEncoder().encodeToString(hex(String(data, Charsets.US_ASCII)))
        else -> null
    }

    /** Hotová transakce (bajty), nebo null, když jde o PSBT. */
    fun txBytes(data: ByteArray): ByteArray? = when (kind(data)) {
        Kind.TX_BINARY -> data
        Kind.TX_HEX -> hex(String(data, Charsets.US_ASCII))
        else -> null
    }

    fun parseSigned(data: ByteArray, finalizePsbt: (String) -> Transaction): Transaction {
        psbtBase64(data)?.let { return finalizePsbt(it) }
        return Transaction(txBytes(data)!!)
    }
}
