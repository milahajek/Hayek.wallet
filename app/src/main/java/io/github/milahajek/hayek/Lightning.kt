package io.github.milahajek.hayek

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Lightning přes váš vlastní LND uzel (RaspiBlitz). Telefon je jen „dálkové ovládání“:
 * peníze jsou v kanálech na uzlu, telefon posílá uzlu příkazy přes jeho REST API (port 8080)
 * s omezeným přístupovým klíčem (macaroon), který umí jen Lightning platby a faktury –
 * nedovolí vybrat bitcoiny z uzlu, otevírat ani zavírat kanály.
 *
 * Certifikát uzlu se při prvním spojení připne (TOFU), stejně jako u Fulcrumu.
 */

/** Příkaz pro RaspiBlitz, který vytvoří omezený klíč a ukáže ho jako QR kód. */
const val LND_PERMISSIONS = "offchain:read offchain:write invoices:read invoices:write info:read"

/** Vlastní ID kořenového klíče – klíč telefonu jde zneplatnit samostatně (lncli deletemacaroonid 8211), admin klíč RaspiBlitzu zůstane. */
const val LND_ROOT_KEY_ID = 8211

fun lndSetupCommand(hostPort: String = "IP-UZLU:8080") =
    "qrencode -t ANSIUTF8 \"lndconnect://$hostPort?macaroon=\$(lncli bakemacaroon --root_key_id $LND_ROOT_KEY_ID $LND_PERMISSIONS)\""

data class LndConfig(val hostPort: String, val macaroonHex: String)

/**
 * Přečte připojení k LND z QR kódu / textu:
 *  - `lndconnect://host:port?macaroon=…` (macaroon hex nebo base64url, případný cert se ignoruje –
 *    certifikát si připneme sami při prvním spojení),
 *  - nebo jen samotný macaroon (hex), pokud je zadaná adresa zvlášť.
 */
fun parseLndConnect(raw: String, fallbackHost: String? = null): LndConfig {
    val text = raw.trim()
    if (text.startsWith("lndconnect://", ignoreCase = true)) {
        val rest = text.substring("lndconnect://".length)
        val hostPort = rest.substringBefore("?").substringBefore("/").trim()
        val params = rest.substringAfter("?", "").split("&").associate {
            it.substringBefore("=").lowercase() to java.net.URLDecoder.decode(it.substringAfter("=", ""), "UTF-8")
        }
        val mac = params["macaroon"] ?: throw IllegalArgumentException("V QR kódu chybí přístupový klíč (macaroon).")
        return LndConfig(normalizeLndHost(hostPort), macaroonToHex(mac))
    }
    val host = fallbackHost?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Zadejte adresu uzlu (např. 192.168.1.10:8080), nebo naskenujte QR kód lndconnect.")
    return LndConfig(normalizeLndHost(host), macaroonToHex(text))
}

fun normalizeLndHost(input: String): String {
    val s = input.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
    if (s.isEmpty()) throw IllegalArgumentException("Chybí adresa uzlu.")
    if (s.contains(".onion")) throw IllegalArgumentException("Adresy přes Tor (.onion) zatím nepodporuji. Použijte IP adresu uzlu v domácí síti, např. 192.168.1.10:8080.")
    val hasPort = s.substringAfterLast(":", "").toIntOrNull() != null && !s.endsWith("]")
    val hp = if (hasPort) s else "$s:8080"
    val port = hp.substringAfterLast(":").toInt()
    if (port !in 1..65535) throw IllegalArgumentException("Neplatný port.")
    return hp
}

fun macaroonToHex(mac: String): String {
    val m = mac.trim().replace("\\s".toRegex(), "")
    if (m.isEmpty()) throw IllegalArgumentException("Chybí přístupový klíč (macaroon).")
    val hex = if (m.length % 2 == 0 && m.all { it in "0123456789abcdefABCDEF" }) m.lowercase()
    else try {
        Base64.getUrlDecoder().decode(m.replace('+', '-').replace('/', '_').trimEnd('=')).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        throw IllegalArgumentException("Přístupový klíč (macaroon) není ve správném formátu.")
    }
    if (hex.length < 100) throw IllegalArgumentException("Přístupový klíč (macaroon) je neúplný.")
    return hex
}

class LndException(message: String, val code: Int = 0) : Exception(message)

data class LnInfo(val alias: String, val pubkey: String, val activeChannels: Int, val syncedToChain: Boolean, val version: String)
data class LnBalance(val canSendSat: Long, val canReceiveSat: Long, val pendingSat: Long)
data class LnDecoded(
    val paymentRequest: String, val amountSat: Long, val description: String, val destination: String,
    val paymentHash: String, val timestamp: Long, val expirySec: Long,
) {
    val expiresAt get() = timestamp + expirySec
    fun expired(nowSec: Long = System.currentTimeMillis() / 1000) = nowSec > expiresAt
}
data class LnPayResult(val ok: Boolean, val feeSat: Long, val preimage: String?, val failure: String?)
data class LnInvoice(val paymentRequest: String, val rHashHex: String, val amountSat: Long, val memo: String)
data class LnHistoryItem(val incoming: Boolean, val amountSat: Long, val feeSat: Long, val time: Long, val memo: String, val id: String)

class LndClient(val config: LndConfig, private val pins: PinStore) {
    private val pinKey = "ln:${config.hostPort}"

    /** Připnutí certifikátu: první spojení otisk uloží, další ho musí mít stejný. */
    private val trust = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("not a server")
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
            if (chain.isEmpty()) throw CertificateException("Uzel neposlal certifikát")
            val fp = sha256Fingerprint(chain[0])
            val pinned = pins.get(pinKey)
            when {
                pinned == null -> pins.set(pinKey, fp)
                !pinned.equals(fp, ignoreCase = true) -> throw CertificateException("PIN:" + pinned + "|" + fp)
            }
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    private val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }

    private fun request(method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 20_000): String {
        val conn = URL("https://${config.hostPort}$path").openConnection() as HttpsURLConnection
        conn.sslSocketFactory = ssl.socketFactory
        // Domácí uzel nemá certifikát na jméno – ověřujeme ho připnutým otiskem výše.
        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Grpc-Metadata-macaroon", config.macaroonHex)
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw LndException(errorMessage(text, code), code)
            return text
        } catch (e: javax.net.ssl.SSLException) {
            val pin = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.firstOrNull { it.startsWith("PIN:") }
            if (pin != null) {
                val (exp, act) = pin.removePrefix("PIN:").split("|")
                throw CertMismatchException(config.hostPort, exp, act)
            }
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(text: String, code: Int): String {
        val msg = runCatching {
            val j = JSONObject(text)
            (j.optJSONObject("error") ?: j).optString("message")
        }.getOrNull().orEmpty().ifBlank { text.take(200) }
        return when {
            code == 401 || code == 403 || msg.contains("permission denied") || msg.contains("verification failed") ||
                msg.contains("macaroon", true) || msg.contains("unmarshal", true) ->
                "Uzel odmítl přístupový klíč ($msg). Vytvořte na uzlu nový a připojte se znovu."
            msg.contains("wallet locked", true) || msg.contains("not yet ready", true) || msg.contains("unlock", true) ->
                "LND na uzlu je zamčené. Odemkněte ho na RaspiBlitzu (menu → LND → UNLOCK, heslo C)."
            else -> msg.ifBlank { "Chyba LND (HTTP $code)" }
        }
    }

    fun info(): LnInfo {
        val j = JSONObject(request("GET", "/v1/getinfo"))
        return LnInfo(
            alias = j.optString("alias"), pubkey = j.optString("identity_pubkey"),
            activeChannels = j.optInt("num_active_channels"), syncedToChain = j.optBoolean("synced_to_chain"),
            version = j.optString("version").substringBefore(" "),
        )
    }

    fun balance(): LnBalance {
        val j = JSONObject(request("GET", "/v1/balance/channels"))
        fun sat(k: String) = j.optJSONObject(k)?.optString("sat")?.toLongOrNull() ?: 0L
        return LnBalance(sat("local_balance"), sat("remote_balance"), sat("pending_open_local_balance"))
    }

    fun decode(paymentRequest: String): LnDecoded {
        val pr = cleanInvoice(paymentRequest)
        val j = JSONObject(request("GET", "/v1/payreq/" + URLEncoder.encode(pr, "UTF-8")))
        return LnDecoded(
            paymentRequest = pr,
            amountSat = j.optString("num_satoshis").toLongOrNull() ?: 0L,
            description = j.optString("description"),
            destination = j.optString("destination"),
            paymentHash = j.optString("payment_hash"),
            timestamp = j.optString("timestamp").toLongOrNull() ?: 0L,
            expirySec = j.optString("expiry").toLongOrNull() ?: 3600L,
        )
    }

    /**
     * Zaplatí fakturu. [amountSat] jen u faktur bez částky. Čeká na výsledek (max ~60 s).
     * [feeLimitSat] = nejvyšší poplatek, který jsme ochotni za doručení zaplatit.
     */
    fun pay(paymentRequest: String, feeLimitSat: Long, amountSat: Long? = null): LnPayResult {
        val body = JSONObject()
            .put("payment_request", cleanInvoice(paymentRequest))
            .put("timeout_seconds", 60)
            .put("fee_limit_sat", feeLimitSat.toString())
            .put("no_inflight_updates", true)
        if (amountSat != null && amountSat > 0) body.put("amt", amountSat.toString())
        val text = try {
            request("POST", "/v2/router/send", body, timeoutMs = 90_000)
        } catch (e: LndException) {
            // Odmítnutí před odesláním (už zaplaceno, vlastní faktura, vypršelo…) přijde jako HTTP chyba.
            if (e.message.orEmpty().startsWith("Uzel odmítl přístupový klíč") || e.message.orEmpty().startsWith("LND na uzlu je zamčené")) throw e
            return LnPayResult(false, 0, null, translateFailure(e.message.orEmpty()))
        }
        return parsePayStream(text)
    }

    fun addInvoice(amountSat: Long, memo: String, expirySec: Long = 3600): LnInvoice {
        val j = JSONObject(request("POST", "/v1/invoices", JSONObject()
            .put("value", amountSat.toString()).put("memo", memo).put("expiry", expirySec.toString()).put("private", true)))
        val rHash = Base64.getDecoder().decode(j.getString("r_hash")).joinToString("") { "%02x".format(it) }
        return LnInvoice(j.getString("payment_request"), rHash, amountSat, memo)
    }

    /** true = faktura je zaplacená. */
    fun isSettled(rHashHex: String): Boolean {
        val j = JSONObject(request("GET", "/v1/invoice/$rHashHex"))
        return j.optString("state") == "SETTLED" || j.optBoolean("settled")
    }

    fun history(limit: Int = 30): List<LnHistoryItem> {
        val out = mutableListOf<LnHistoryItem>()
        val pays = JSONObject(request("GET", "/v1/payments?reversed=true&max_payments=$limit")).optJSONArray("payments")
        for (i in 0 until (pays?.length() ?: 0)) {
            val p = pays!!.getJSONObject(i)
            if (p.optString("status") != "SUCCEEDED") continue
            val hash = p.optString("payment_hash")
            out += LnHistoryItem(false, p.optString("value_sat").toLongOrNull() ?: 0, p.optString("fee_sat").toLongOrNull() ?: 0,
                (p.optString("creation_time_ns").toLongOrNull() ?: 0) / 1_000_000_000, "", "p:$hash")
        }
        val invs = JSONObject(request("GET", "/v1/invoices?reversed=true&num_max_invoices=$limit")).optJSONArray("invoices")
        for (i in 0 until (invs?.length() ?: 0)) {
            val v = invs!!.getJSONObject(i)
            if (v.optString("state") != "SETTLED") continue
            out += LnHistoryItem(true, v.optString("amt_paid_sat").toLongOrNull() ?: v.optString("value").toLongOrNull() ?: 0, 0,
                v.optString("settle_date").toLongOrNull() ?: 0, v.optString("memo"), "i:${v.optString("add_index")}")
        }
        return out.sortedByDescending { it.time }.take(limit)
    }

    companion object {
        /** Odstraní předponu lightning: a mezery; QR kódy bývají velkými písmeny. */
        fun cleanInvoice(raw: String): String {
            var s = raw.trim()
            if (s.startsWith("lightning:", ignoreCase = true)) s = s.substring("lightning:".length)
            // bitcoin:…?lightning=lnbc… (sjednocený QR)
            if (s.startsWith("bitcoin:", ignoreCase = true) && s.contains("lightning=", ignoreCase = true))
                s = s.substring(s.indexOf("lightning=", ignoreCase = true) + "lightning=".length).substringBefore("&")
            return s.replace("\\s".toRegex(), "").lowercase()
        }

        fun looksLikeInvoice(raw: String): Boolean {
            val s = cleanInvoice(raw)
            return s.startsWith("lnbc") && s.length > 50
        }

        /** Výsledek /v2/router/send – po řádcích JSON, poslední obsahuje konečný stav. */
        fun parsePayStream(text: String): LnPayResult {
            val lines = text.lines().map { it.trim() }.filter { it.startsWith("{") }
            if (lines.isEmpty()) throw IOException("Uzel nevrátil výsledek platby.")
            val last = JSONObject(lines.last())
            last.optJSONObject("error")?.let { e ->
                val m = e.optString("message")
                return LnPayResult(false, 0, null, translateFailure(m))
            }
            val r = last.optJSONObject("result") ?: last
            return when (r.optString("status")) {
                "SUCCEEDED" -> LnPayResult(true, r.optString("fee_sat").toLongOrNull() ?: 0, r.optString("payment_preimage"), null)
                "FAILED" -> LnPayResult(false, 0, null, translateFailure(r.optString("failure_reason")))
                else -> LnPayResult(false, 0, null, "Platba zatím není dokončená (stav ${r.optString("status")}). Zkontrolujte ji za chvíli v historii.")
            }
        }

        fun translateFailure(reason: String): String = when {
            reason.contains("NO_ROUTE") -> "Nenašla se cesta k příjemci. Zkuste to za chvíli znovu, nebo zvyšte limit poplatku."
            reason.contains("INSUFFICIENT_BALANCE") -> "Na kanálu není dost prostředků (včetně poplatku)."
            reason.contains("TIMEOUT") -> "Platba nestihla doběhnout. Peníze neodešly, zkuste to znovu."
            reason.contains("INCORRECT_PAYMENT_DETAILS") -> "Příjemce platbu odmítl (faktura už je zaplacená, vypršela nebo nesedí částka)."
            reason.contains("already paid", true) || reason.contains("ALREADY_PAID", true) -> "Tahle faktura už je zaplacená."
            reason.contains("self-payments", true) -> "Nelze platit fakturu vlastního uzlu."
            reason.contains("invoice expired", true) -> "Faktura vypršela. Požádejte příjemce o novou."
            else -> "Platba se nezdařila: $reason"
        }
    }
}
