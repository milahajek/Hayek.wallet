package io.github.milahajek.hayek

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import org.json.JSONObject
import java.io.IOException

/**
 * Spolupráce s hardwarovou peněženkou Coldcard (Mk4 / Mk5 / Q) přes NFC.
 * Coldcard se při „NFC Tools → Sign PSBT“ chová jako NFC štítek (NDEF, typ 5): telefon na něj zapíše PSBT,
 * po podpisu Coldcard nabídne štítek s hotovou transakcí (záznam bitcoin.org:txn).
 */
object ColdcardNfc {
    const val MAX_NFC = 7_800   // Coldcard sdílí přes NFC max. ~8 000 B

    private fun <T> withNdef(tag: Tag, block: (Ndef) -> T): T {
        val ndef = Ndef.get(tag) ?: throw IOException(L("Zařízení nepodporuje NDEF – je to Coldcard s zapnutým NFC?", "The device doesn't support NDEF – is it a Coldcard with NFC on?"))
        ndef.connect()
        try { return block(ndef) } finally { runCatching { ndef.close() } }
    }

    /** Zapíše nepodepsanou transakci (PSBT, binárně) do Coldcardu. */
    fun writePsbt(tag: Tag, psbt: ByteArray) = withNdef(tag) { ndef ->
        val msg = NdefMessage(arrayOf(NdefRecord.createExternal("bitcoin.org", "psbt", psbt)))
        if (msg.byteArrayLength > ndef.maxSize) throw IOException(
            L("Transakce je na NFC moc velká (${msg.byteArrayLength} B). Použijte paměťovou kartu.", "The transaction is too large for NFC (${msg.byteArrayLength} B). Use the memory card."))
        if (!ndef.isWritable) throw IOException(L("Coldcard teď nepřijímá data. Na Coldcardu zvolte Advanced/Tools → NFC Tools → Sign PSBT.", "The Coldcard isn't accepting data right now. On the Coldcard choose Advanced/Tools → NFC Tools → Sign PSBT."))
        ndef.writeNdefMessage(msg)
    }

    /** Všechny záznamy ze štítku jako (typ, obsah). */
    fun readRecords(tag: Tag): List<Pair<String, ByteArray>> = withNdef(tag) { ndef ->
        val msg = ndef.ndefMessage ?: ndef.getNdefMessage() ?: return@withNdef emptyList()
        msg.records.map { r -> recordType(r) to r.payload }
    }

    fun recordType(r: NdefRecord): String = when (r.tnf) {
        NdefRecord.TNF_EXTERNAL_TYPE -> String(r.type, Charsets.US_ASCII).lowercase()
        NdefRecord.TNF_MIME_MEDIA -> "mime:" + String(r.type, Charsets.US_ASCII).lowercase()
        NdefRecord.TNF_WELL_KNOWN -> if (r.type.contentEquals(NdefRecord.RTD_TEXT)) "text" else if (r.type.contentEquals(NdefRecord.RTD_URI)) "uri" else "wk"
        else -> "other"
    }

    /** Text z NDEF textového záznamu (bez jazykového kódu). */
    fun textPayload(p: ByteArray): String {
        if (p.isEmpty()) return ""
        val langLen = p[0].toInt() and 0x3F
        return String(p, 1 + langLen, p.size - 1 - langLen, Charsets.UTF_8)
    }

    /**
     * Podepsaná transakce ze záznamů Coldcardu: hotová transakce (bitcoin.org:txn) nebo podepsané PSBT.
     * PSBT shodné s tím, co jsme sami zapsali (Coldcard ještě nepodepsal), se ignoruje.
     */
    fun signedFrom(records: List<Pair<String, ByteArray>>, written: ByteArray?): ByteArray? {
        records.firstOrNull { it.first == "bitcoin.org:txn" }?.let { return it.second }
        return records.firstOrNull { it.first == "bitcoin.org:psbt" && !(written != null && it.second.contentEquals(written)) }?.second
    }

    /** Veřejný popis peněženky z exportu Coldcardu (deskriptor, nebo „Generic JSON“), případně zpub/xpub. */
    fun watchSource(text: String): String? {
        Regex("""(wpkh|tr|sh\(wpkh)\(\[[0-9a-fA-F]{8}[^\]]*\][a-zA-Z0-9]+/[^\s"]*\*\)+(#[a-z0-9]{8})?""").find(text)?.let { return it.value }
        try {
            val j = JSONObject(text.trim())
            val b = j.optJSONObject("bip84") ?: return@watchSource null
            val xfp = (b.optString("xfp").ifBlank { j.optString("xfp") }).uppercase()
            val xpub = b.optString("xpub")
            val deriv = b.optString("deriv", "m/84'/0'/0'").removePrefix("m/").replace("'", "h")
            if (xfp.length == 8 && xpub.startsWith("xpub")) return "wpkh([${xfp.lowercase()}/$deriv]$xpub/<0;1>/*)"
        } catch (_: Exception) {}
        return Regex("""[xyz]pub[1-9A-HJ-NP-Za-km-z]{100,}""").find(text)?.value
    }
}
