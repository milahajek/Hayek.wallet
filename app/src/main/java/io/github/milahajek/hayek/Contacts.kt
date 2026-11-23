package io.github.milahajek.hayek

import org.json.JSONArray
import org.json.JSONObject

/** Kontakt v adresáři: jméno a bitcoinová adresa příjemce. Jen v telefonu. */
data class Contact(val name: String, val address: String)

object Contacts {
    fun normalize(address: String): String? = parsePaymentQr(address.trim())?.address

    fun toJson(list: List<Contact>): String =
        JSONArray().apply { list.forEach { put(JSONObject().put("name", it.name).put("address", it.address)) } }.toString()

    fun fromJson(text: String?): List<Contact> = try {
        val a = JSONArray(text ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val addr = normalize(o.optString("address")) ?: return@mapNotNull null
            o.optString("name").trim().takeIf { it.isNotEmpty() }?.let { Contact(it.take(60), addr) }
        }
    } catch (_: Exception) { emptyList() }

    /** Přidá nebo přejmenuje (podle adresy), seřadí podle jména. */
    fun upsert(list: List<Contact>, c: Contact): List<Contact> =
        (list.filter { it.address != c.address } + c).sortedBy { it.name.lowercase() }
}
