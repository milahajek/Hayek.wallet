package io.github.milahajek.hayek

import java.math.BigDecimal

/** Výsledek načtení platebního QR kódu / odkazu bitcoin:… (BIP21). */
data class PaymentRequest(val address: String, val amountSat: Long? = null, val label: String? = null)

/**
 * Rozebere obsah QR kódu na adresu a případnou částku.
 * Podporuje holou adresu i odkaz `bitcoin:ADRESA?amount=0.001&label=…` (BIP21),
 * velká písmena (QR kódy často používají `BITCOIN:BC1Q…`) a mezery.
 * Vrací null, pokud to adresa být nemůže (třeba odkaz na web).
 */
fun parsePaymentQr(raw: String): PaymentRequest? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val hasScheme = text.startsWith("bitcoin:", ignoreCase = true)
    val body = if (hasScheme) text.substring("bitcoin:".length).removePrefix("//") else text
    val address = body.substringBefore("?").trim()
    if (!looksLikeAddress(address)) return null
    // Bech32 adresy jsou v QR často velkými písmeny, peněženky je chtějí malými.
    val addr = if (address.startsWith("BC1", ignoreCase = true) || address.startsWith("TB1", ignoreCase = true))
        address.lowercase() else address
    if (!hasScheme || !body.contains("?")) return PaymentRequest(addr)

    var amountSat: Long? = null
    var label: String? = null
    for (pair in body.substringAfter("?").split("&")) {
        val key = pair.substringBefore("=").lowercase()
        val value = java.net.URLDecoder.decode(pair.substringAfter("=", ""), "UTF-8")
        when (key) {
            "amount" -> amountSat = btcToSat(value)
            "label", "message" -> if (label == null && value.isNotBlank()) label = value
            // Povinné neznámé parametry (req-…) neumíme splnit – takovou žádost odmítneme.
            else -> if (key.startsWith("req-")) return null
        }
    }
    return PaymentRequest(addr, amountSat, label)
}

/** „0.0015“ BTC → 150 000 sat. Neplatné nebo záporné částky ignoruje (vrátí null). */
fun btcToSat(btc: String): Long? = try {
    val v = BigDecimal(btc.trim())
    if (v.signum() <= 0 || v.scale() > 8) null
    else v.movePointRight(8).longValueExact().takeIf { it <= 21_000_000L * 100_000_000L }
} catch (e: Exception) { null }

private fun looksLikeAddress(a: String): Boolean {
    if (a.length !in 26..90) return false
    val lower = a.lowercase()
    return when {
        lower.startsWith("bc1") || lower.startsWith("tb1") || lower.startsWith("bcrt1") -> lower.all { it.isLetterOrDigit() }
        a[0] in "13mn2" -> a.all { it.isLetterOrDigit() }
        else -> false
    }
}

/** „150000“ sat → „0.0015“ BTC (bez zbytečných nul) – pro odkaz BIP21. */
fun satToBtcString(sat: Long): String = BigDecimal(sat).movePointLeft(8).stripTrailingZeros().toPlainString()

/** Sestaví žádost o platbu `bitcoin:ADRESA?amount=…&label=…` (BIP21). Bez částky a popisu vrátí jen odkaz s adresou. */
fun buildPaymentUri(address: String, amountSat: Long? = null, label: String? = null): String {
    val params = buildList {
        if (amountSat != null && amountSat > 0) add("amount=" + satToBtcString(amountSat))
        label?.trim()?.takeIf { it.isNotEmpty() }?.let { add("label=" + java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")) }
    }
    return "bitcoin:$address" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
}
