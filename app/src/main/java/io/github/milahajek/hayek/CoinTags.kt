package io.github.milahajek.hayek

import androidx.compose.ui.graphics.Color

/**
 * Značky mincí: odkud bitcoiny pocházejí. Jen v telefonu, nikam se neposílají.
 * Bez KYC = koupené bez ověření totožnosti (Bisq, osobně…), KYC = z burzy s ověřením totožnosti.
 * Smícháním obou v jedné transakci by se „čisté“ mince natrvalo spojily s vaší identitou.
 */
enum class CoinTag(val key: String, private val cs: String, private val en: String, val color: Color) {
    NOKYC("nokyc", "Bez KYC", "No KYC", Color(0xFF2E9E5B)),
    KYC("kyc", "KYC", "KYC", Color(0xFFD9534F));

    val label: String get() = L(cs, en)

    companion object {
        const val NONE = "none"
        val UNTAGGED_COLOR = Color(0xFF8A8F98)
        fun of(key: String?): CoinTag? = entries.find { it.key == key }
        fun keyOf(t: CoinTag?): String = t?.key ?: NONE
        fun labelOf(key: String): String = of(key)?.label ?: L("Neoznačené", "Untagged")
        fun colorOf(key: String): Color = of(key)?.color ?: UNTAGGED_COLOR
    }
}

/** Značka drobných z transakce: KYC „nakazí“ vše, bez KYC zůstane jen když byly všechny vstupy bez KYC. */
fun combineTags(tags: List<CoinTag?>): CoinTag? = when {
    tags.isEmpty() -> null
    CoinTag.KYC in tags -> CoinTag.KYC
    tags.all { it == CoinTag.NOKYC } -> CoinTag.NOKYC
    else -> null
}

/** Míchají se mince bez KYC s jinými (KYC nebo neoznačenými)? */
fun mixesNoKyc(tags: List<CoinTag?>): Boolean = CoinTag.NOKYC in tags && tags.any { it != CoinTag.NOKYC }

/**
 * Výsledná značka mince podle uložených záznamů (pořadí: ručně u mince → u adresy → zděděná z transakce u drobných).
 * Hodnota „none“ u mince znamená „ručně neoznačeno“ a zastaví dědění.
 */
fun resolveTag(coin: CoinItem, lookup: (String) -> String?): CoinTag? {
    lookup("c:${coin.id}")?.let { return CoinTag.of(it) }
    lookup("a:${coin.address}")?.let { return CoinTag.of(it) }
    if (coin.change) lookup("t:${coin.txid}")?.let { return CoinTag.of(it) }
    return null
}
