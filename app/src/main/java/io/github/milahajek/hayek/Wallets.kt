package io.github.milahajek.hayek

import org.json.JSONArray
import org.json.JSONObject

/**
 * Jedna peněženka v aplikaci. Tajemství (slova nebo veřejný deskriptor) je zašifrované v SecureStore:
 * slot „seed“ = hlavní místo (původní jediná peněženka), jinak doplňkový záznam „wallet_<id>“.
 * kind: "hot" = klíče v telefonu, "watch" = jen sledování (podpis Coldcardem).
 */
data class WalletInfo(val id: String, val name: String, val slot: String, val kind: String) {
    val isWatch get() = kind == "watch"
}

object WalletRegistry {
    const val PRIMARY_ID = "w1"
    const val SLOT_SEED = "seed"

    fun toJson(list: List<WalletInfo>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("slot", it.slot).put("kind", it.kind)) }
    }.toString()

    fun fromJson(text: String?): List<WalletInfo> = try {
        val a = JSONArray(text ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val id = o.optString("id").takeIf { Regex("w[0-9a-z]{1,20}").matches(it) } ?: return@mapNotNull null
            WalletInfo(id, o.optString("name").take(30), o.optString("slot").ifBlank { "wallet_$id" }, o.optString("kind", "hot"))
        }.distinctBy { it.id }
    } catch (_: Exception) { emptyList() }

    /**
     * Seznam peněženek; pro starší instalaci (jen „seed“, žádný seznam) vytvoří záznam pro stávající peněženku
     * – její soubory a nastavení zůstávají přesně tam, kde byly.
     */
    fun loadOrMigrate(json: String?, hasSeed: Boolean, defaultName: String): List<WalletInfo> {
        val list = fromJson(json)
        if (list.isNotEmpty() || !hasSeed) return list
        return listOf(WalletInfo(PRIMARY_ID, defaultName, SLOT_SEED, "hot"))
    }

    /** @param lastUsed nejvyšší číslo, které už kdy bylo přiděleno (i smazané peněžence). */
    fun newId(existing: List<WalletInfo>, lastUsed: Int = 1): String {
        var n = maxOf(existing.size, lastUsed) + 1
        while (existing.any { it.id == "w$n" }) n++
        return "w$n"
    }

    /** Názvy souborů a nastavení konkrétní peněženky (w1 = původní názvy, aby se nic nemuselo přesouvat). */
    fun dbName(id: String) = if (id == PRIMARY_ID) "wallet.sqlite3" else "wallet_$id.sqlite3"
    fun notesPrefs(id: String) = if (id == PRIMARY_ID) "notes" else "notes_$id"
    fun tagPrefs(id: String) = if (id == PRIMARY_ID) "coin_tags" else "coin_tags_$id"
    fun walletPrefs(id: String) = if (id == PRIMARY_ID) "settings" else "w_$id"
}
