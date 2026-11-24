package io.github.milahajek.hayek

/**
 * Heslo navíc k obnovovací frázi (BIP39 passphrase, „25. slovo“).
 * V zašifrovaném úložišti je uložené spolu se slovy: „slova\nheslo“ (slova nikdy nový řádek neobsahují).
 * Heslo se NIKDY neupravuje (mezery, velká/malá písmena) – každý znak mění peněženku.
 */
object Passphrase {
    const val MAX = 100   // stejný limit jako Coldcard

    fun pack(words: String, passphrase: String?): String =
        if (passphrase.isNullOrEmpty()) words else "$words\n$passphrase"

    fun words(secret: String): String = secret.substringBefore("\n")
    fun of(secret: String): String? = secret.substringAfter("\n", "").ifEmpty { null }

    /** Chybová hláška, nebo null, když je heslo v pořádku. */
    fun problem(p: String): String? = when {
        p.isEmpty() -> L("Zadejte heslo.", "Enter the passphrase.")
        p.length > MAX -> L("Heslo může mít nejvýš $MAX znaků.", "The passphrase can have at most $MAX characters.")
        p.any { it == '\n' || it == '\r' } -> L("Heslo nesmí obsahovat nový řádek.", "The passphrase can't contain a line break.")
        else -> null
    }

    /** Upozornění (heslo jde použít, ale je dobré o tom vědět). */
    fun warning(p: String): String? = when {
        p.any { it.code > 126 } -> L("Heslo obsahuje diakritiku nebo zvláštní znaky. Některé jiné peněženky je mohou zpracovat jinak – bezpečnější jsou jen písmena bez háčků a čárek, číslice a běžné znaky.", "The passphrase contains accents or special characters. Some other wallets may handle them differently – plain letters without accents, digits and common symbols are safer.")
        p != p.trim() -> L("Pozor: heslo začíná nebo končí mezerou. I ta je jeho součástí.", "Careful: the passphrase starts or ends with a space. It is part of the passphrase too.")
        p.length < 6 -> L("Krátké heslo jde snadno uhodnout. Doporučujeme aspoň 12 znaků.", "A short passphrase is easy to guess. We recommend at least 12 characters.")
        else -> null
    }

    /** Otisk peněženky (master fingerprint) z veřejného deskriptoru, např. 73c5da0a. */
    fun fingerprint(publicDescriptor: String?): String? =
        publicDescriptor?.let { Regex("\\[([0-9a-fA-F]{8})").find(it)?.groupValues?.get(1)?.lowercase() }
}
