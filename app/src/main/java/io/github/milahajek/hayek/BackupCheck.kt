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
