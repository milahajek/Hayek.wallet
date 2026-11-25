package io.github.milahajek.hayek

/**
 * Kontrola zálohy slovo po slovu. Ve slovníku BIP39 jsou slova jednoznačná už podle prvních 4 písmen,
 * takže stačí napsat je (kratší slova celá).
 *
 * @return true = sedí, false = nesedí, null = zatím nelze rozhodnout (uživatel ještě píše)
 */
fun wordMatches(input: String, word: String): Boolean? {
    val t = input.trim().lowercase()
    return when {
        t.isEmpty() -> null
        t == word -> true
        !word.startsWith(t) -> false
        t.length >= 4 -> true
        else -> null
    }
}

/**
 * Standardní anglický slovník BIP39 (2048 slov) pro našeptávání při kontrole zálohy.
 * Našeptává se jen podle napsaných písmen – nikdy podle správné odpovědi, takže nápověda nic neprozradí.
 */
object Bip39Words {
    /** SHA-256 souboru assets/bip39_english.txt – stejný jako u oficiálního seznamu v repozitáři bitcoin/bips. */
    const val SHA256 = "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda"

    @Volatile private var words: List<String> = emptyList()

    fun load(ctx: android.content.Context): List<String> {
        if (words.isEmpty()) words = runCatching {
            ctx.assets.open("bip39_english.txt").bufferedReader().useLines { l -> l.map { it.trim() }.filter { it.isNotEmpty() }.toList() }
        }.getOrDefault(emptyList())
        return words
    }

    /** Slova začínající na [prefix] (nejvýš [max]); prázdné, dokud nejsou napsaná aspoň 2 písmena. */
    fun suggest(all: List<String>, prefix: String, max: Int = 8): List<String> {
        val p = prefix.trim().lowercase()
        if (p.length < 2) return emptyList()
        return all.asSequence().filter { it.startsWith(p) }.take(max).toList()
    }
}
