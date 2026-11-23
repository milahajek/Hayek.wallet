package io.github.milahajek.hayek

import org.json.JSONObject
import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Záloha štítků podle BIP-329 (JSON Lines): poznámky k transakcím, značky KYC / bez KYC a zmrazení mincí.
 * Soubor neobsahuje klíče, ale prozrazuje historii plateb – proto volitelně šifrovaný heslem.
 */
object Bip329 {
    data class Output(val tag: CoinTag?, val spendable: Boolean?)

    data class Labels(
        val notes: Map<String, String> = emptyMap(),        // txid → poznámka
        val outputs: Map<String, Output> = emptyMap(),      // txid:vout → značka / zmrazení
        val addresses: Map<String, CoinTag> = emptyMap(),   // adresa → značka
        val contacts: Map<String, String> = emptyMap(),      // adresa příjemce → jméno (adresář; pole „hayek_contact“)
    ) {
        val size get() = notes.size + outputs.size + addresses.size + contacts.size
    }

    fun tagLabel(t: CoinTag) = t.label   // „Bez KYC“ / „KYC“ – čitelné i ve Sparrow

    /** Rozpozná značku v cizím štítku: „Bez KYC“, „nokyc“, „no-kyc“, „non-KYC“, „#kyc“… */
    fun tagFromLabel(label: String?): CoinTag? {
        val l = label?.lowercase() ?: return null
        if (Regex("""(bez[\s_-]*kyc|no[nt]?[\s_-]*kyc|kyc[\s_-]*free)""").containsMatchIn(l)) return CoinTag.NOKYC
        if (Regex("""(^|[^a-z])kyc([^a-z]|$)""").containsMatchIn(l)) return CoinTag.KYC
        return null
    }

    fun toJsonl(l: Labels): String = buildString {
        l.notes.toSortedMap().forEach { (txid, note) ->
            appendLine(JSONObject().put("type", "tx").put("ref", txid).put("label", note).toString())
        }
        l.addresses.toSortedMap().forEach { (a, t) ->
            appendLine(JSONObject().put("type", "addr").put("ref", a).put("label", tagLabel(t)).toString())
        }
        l.contacts.toSortedMap().forEach { (a, n) ->
            appendLine(JSONObject().put("type", "addr").put("ref", a).put("label", n).put("hayek_contact", true).toString())
        }
        l.outputs.toSortedMap().forEach { (id, o) ->
            val j = JSONObject().put("type", "output").put("ref", id)
            o.tag?.let { j.put("label", tagLabel(it)) }
            o.spendable?.let { j.put("spendable", it) }
            appendLine(j.toString())
        }
    }

    /** Načte JSON Lines; neznámé typy a vadné řádky přeskočí. */
    fun parse(text: String): Labels {
        val notes = mutableMapOf<String, String>()
        val outputs = mutableMapOf<String, Output>()
        val addrs = mutableMapOf<String, CoinTag>()
        val contacts = mutableMapOf<String, String>()
        text.lineSequence().map { it.trim() }.filter { it.startsWith("{") }.forEach { line ->
            val j = try { JSONObject(line) } catch (_: Exception) { return@forEach }
            val ref = j.optString("ref").trim().takeIf { it.isNotEmpty() && it.length <= 200 } ?: return@forEach
            val label = if (j.has("label") && !j.isNull("label")) j.optString("label").trim() else null
            when (j.optString("type")) {
                "tx" -> if (!label.isNullOrBlank() && Regex("[0-9a-fA-F]{64}").matches(ref)) notes[ref.lowercase()] = label.take(200)
                "addr" -> if (j.optBoolean("hayek_contact")) { if (!label.isNullOrBlank()) contacts[ref] = label.take(60) }
                    else tagFromLabel(label)?.let { addrs[ref] = it }
                "output" -> if (Regex("[0-9a-fA-F]{64}:\\d{1,6}").matches(ref)) {
                    val sp = if (j.has("spendable")) j.optBoolean("spendable", true) else null
                    val o = Output(tagFromLabel(label), sp)
                    if (o.tag != null || o.spendable != null) outputs[ref.lowercase()] = o
                }
            }
        }
        return Labels(notes, outputs, addrs, contacts)
    }

    // ---------- Šifrování heslem (formát Hayek, ne součást BIP-329) ----------
    private val MAGIC = "HAYEK-LABELS-1\n".toByteArray(UTF_8)
    private const val ITER = 310_000

    fun isEncrypted(data: ByteArray) = data.size > MAGIC.size && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    private fun key(pw: String, salt: ByteArray) = SecretKeySpec(
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pw.toCharArray(), salt, ITER, 256)).encoded, "AES")

    fun encrypt(text: String, password: String): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)) }
        return MAGIC + salt + iv + c.doFinal(text.toByteArray(UTF_8))
    }

    fun decrypt(data: ByteArray, password: String): String {
        require(isEncrypted(data) && data.size > MAGIC.size + 28) { L("Soubor není zašifrovaná záloha Hayek.", "The file is not an encrypted Hayek backup.") }
        var p = MAGIC.size
        val salt = data.copyOfRange(p, p + 16); p += 16
        val iv = data.copyOfRange(p, p + 12); p += 12
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)) }
        return try { String(c.doFinal(data.copyOfRange(p, data.size)), UTF_8) }
        catch (_: Exception) { throw IllegalArgumentException(L("Špatné heslo nebo poškozený soubor.", "Wrong password or damaged file.")) }
    }
}
