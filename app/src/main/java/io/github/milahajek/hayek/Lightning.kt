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

/**
 * Příkaz pro RaspiBlitz: omezený klíč + otisk certifikátu LND (certfp) v jednom QR kódu.
 * Díky otisku z QR aplikace pozná podvržený uzel ještě PŘED odesláním klíče.
 */
fun lndSetupCommand(hostPort: String = "IP-UZLU:8080"): String {
    val hp = runCatching { normalizeLndHost(hostPort) }.getOrDefault("IP-UZLU:8080")
    return "qrencode -t ANSIUTF8 \"lndconnect://$hp?macaroon=\$(lncli bakemacaroon --root_key_id $LND_ROOT_KEY_ID $LND_PERMISSIONS)" +
        "&certfp=\$(openssl x509 -in ~/.lnd/tls.cert -noout -fingerprint -sha256 | cut -d= -f2)\""
}

/** @param certFp otisk certifikátu LND z QR kódu (pokud byl v QR) – neukládá se, slouží jen k prvnímu ověření. */
data class LndConfig(val hostPort: String, val macaroonHex: String, val certFp: String? = null)

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
        val mac = params["macaroon"] ?: throw IllegalArgumentException(L("V QR kódu chybí přístupový klíč (macaroon).", "The QR code is missing the access key (macaroon)."))
        val fp = params["certfp"]?.trim()?.uppercase()?.takeIf { it.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")) }
            ?: params["cert"]?.takeIf { it.isNotBlank() }?.let { certFingerprintFromBase64(it) }
        return LndConfig(normalizeLndHost(hostPort), macaroonToHex(mac), fp)
    }
    val host = fallbackHost?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException(L("Zadejte adresu uzlu (např. 192.168.1.10:8080), nebo naskenujte QR kód lndconnect.", "Enter the node address (e.g. 192.168.1.10:8080), or scan an lndconnect QR code."))
    return LndConfig(normalizeLndHost(host), macaroonToHex(text))
}

fun normalizeLndHost(input: String): String {
    val s = input.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
    if (s.isEmpty()) throw IllegalArgumentException(L("Chybí adresa uzlu.", "The node address is missing."))
    if (s.contains(".onion")) throw IllegalArgumentException(L("Adresy přes Tor (.onion) zatím nepodporuji. Použijte IP adresu uzlu v domácí síti, např. 192.168.1.10:8080.", "Tor addresses (.onion) aren't supported yet. Use the node's IP address in your home network, e.g. 192.168.1.10:8080."))
    val hasPort = s.substringAfterLast(":", "").toIntOrNull() != null && !s.endsWith("]")
    val hp = if (hasPort) s else "$s:8080"
    val host = hp.substringBeforeLast(":")
    val port = hp.substringAfterLast(":").toIntOrNull() ?: throw IllegalArgumentException(L("Neplatný port.", "Invalid port."))
    // Jen IP adresa nebo běžné jméno – žádné „@“, lomítka, uvozovky ani znaky pro příkazovou řádku.
    val okHost = host.matches(Regex("[A-Za-z0-9]([A-Za-z0-9.-]{0,250}[A-Za-z0-9])?")) || host.matches(Regex("\\[[0-9A-Fa-f:.]+]"))
    if (!okHost) throw IllegalArgumentException(L("Neplatná adresa uzlu: $host", "Invalid node address: $host"))
    if (port !in 1..65535) throw IllegalArgumentException(L("Neplatný port.", "Invalid port."))
    return "$host:$port"
}

/** SHA-256 otisk certifikátu předaného v lndconnect jako base64url (DER). */
fun certFingerprintFromBase64(b64: String): String? = runCatching {
    val der = Base64.getUrlDecoder().decode(b64.replace('+', '-').replace('/', '_').trimEnd('='))
    java.security.MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }
}.getOrNull()

fun macaroonToHex(mac: String): String {
    val m = mac.trim().replace("\\s".toRegex(), "")
    if (m.isEmpty()) throw IllegalArgumentException(L("Chybí přístupový klíč (macaroon).", "The access key (macaroon) is missing."))
    val hex = if (m.length % 2 == 0 && m.all { it in "0123456789abcdefABCDEF" }) m.lowercase()
    else try {
        Base64.getUrlDecoder().decode(m.replace('+', '-').replace('/', '_').trimEnd('=')).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        throw IllegalArgumentException(L("Přístupový klíč (macaroon) není ve správném formátu.", "The access key (macaroon) has the wrong format."))
    }
    if (hex.length < 100) throw IllegalArgumentException(L("Přístupový klíč (macaroon) je neúplný.", "The access key (macaroon) is incomplete."))
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
/** Jeden kanál Lightning uzlu, jak ho vidí uživatel. */
data class LnChannel(
    val peerPubkey: String,
    val peerAlias: String?,
    val peerColor: String?,
    val capacitySat: Long,
    val localSat: Long,
    val remoteSat: Long,
    /** Rezerva, kterou protokol drží na vaší straně – nejde utratit. */
    val reserveSat: Long,
    val active: Boolean,
    val pending: Boolean,
    val private: Boolean,
    val initiator: Boolean,
    val channelPoint: String,
    val sentSat: Long = 0,
    val receivedSat: Long = 0,
) {
    val fundingTxid get() = channelPoint.substringBefore(":")
    val canSendSat get() = maxOf(0L, localSat - reserveSat)
    val canReceiveSat get() = remoteSat
    val title get() = peerAlias?.takeIf { it.isNotBlank() && it != peerPubkey.take(20) } ?: (peerPubkey.take(10) + "…")
}

data class LnHistoryItem(val incoming: Boolean, val amountSat: Long, val feeSat: Long, val time: Long, val memo: String, val id: String)

class LndClient(val config: LndConfig, private val pins: PinStore) {
    private val pinKey = "ln:${config.hostPort}"

    /**
     * Certifikát musí odpovídat připnutému otisku. Otisk se připíná VÝSLOVNĚ při nastavení
     * (z QR kódu, nebo po potvrzení uživatelem) – přístupový klíč se nikdy nepošle neověřenému serveru.
     */
    private val trust = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("not a server")
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
            if (chain.isEmpty()) throw CertificateException(L("Uzel neposlal certifikát", "The node sent no certificate"))
            val fp = sha256Fingerprint(chain[0])
            val pinned = pins.get(pinKey) ?: throw CertificateException(L("Certifikát Lightning uzlu není ověřený", "The Lightning node certificate is not verified"))
            if (!pinned.equals(fp, ignoreCase = true)) throw CertificateException("PIN:" + pinned + "|" + fp)
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
                L("Uzel odmítl přístupový klíč ($msg). Vytvořte na uzlu nový a připojte se znovu.", "The node rejected the access key ($msg). Create a new one on the node and connect again.")
            msg.contains("wallet locked", true) || msg.contains("not yet ready", true) || msg.contains("unlock", true) ->
                L("LND na uzlu je zamčené. Odemkněte ho na RaspiBlitzu (menu → LND → UNLOCK, heslo C).", "LND on the node is locked. Unlock it on RaspiBlitz (menu → LND → UNLOCK, password C).")
            else -> msg.ifBlank { L("Chyba LND (HTTP $code)", "LND error (HTTP $code)") }
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

    /** Jméno a barva uzlu z mapy sítě (null, pokud ho uzel ještě nezná). */
    fun nodeAlias(pubkey: String): Pair<String?, String?> = aliasCache[pubkey] ?: try {
        val n = JSONObject(request("GET", "/v1/graph/node/$pubkey?include_channels=false")).optJSONObject("node")
        ((n?.optString("alias")?.takeIf { it.isNotBlank() }) to n?.optString("color")).also { if (it.first != null) aliasCache[pubkey] = it }
    } catch (e: LndException) {
        null to null
    }

    /**
     * Záloha kanálů (Static Channel Backup) – stejný obsah jako soubor channel.backup na uzlu.
     * Je zašifrovaná slovy LND, bez nich je k ničemu. Stačí oprávnění offchain:read.
     */
    fun channelBackup(): ByteArray {
        val j = JSONObject(request("GET", "/v1/channels/backup"))
        val b64 = j.optJSONObject("multi_chan_backup")?.optString("multi_chan_backup").orEmpty()
        require(b64.isNotBlank()) { L("Uzel neposlal zálohu kanálů.", "The node sent no channel backup.") }
        return java.util.Base64.getDecoder().decode(b64)
    }

    /** Nechá uzel ověřit, že záloha je platná (dá se z ní obnovit). */
    fun verifyChannelBackup(data: ByteArray) {
        val body = JSONObject().put("multi_chan_backup", JSONObject().put("multi_chan_backup", java.util.Base64.getEncoder().encodeToString(data)))
        request("POST", "/v1/channels/backup/verify", body)
    }

    /** Otevřené i otevírající se kanály. */
    fun channels(): List<LnChannel> {
        val out = mutableListOf<LnChannel>()
        val open = JSONObject(request("GET", "/v1/channels")).optJSONArray("channels")
        for (i in 0 until (open?.length() ?: 0)) {
            val c = open!!.getJSONObject(i)
            val pk = c.optString("remote_pubkey")
            val (alias, color) = nodeAlias(pk)
            out += LnChannel(
                peerPubkey = pk, peerAlias = alias, peerColor = color,
                capacitySat = c.optString("capacity").toLongOrNull() ?: 0,
                localSat = c.optString("local_balance").toLongOrNull() ?: 0,
                remoteSat = c.optString("remote_balance").toLongOrNull() ?: 0,
                reserveSat = c.optJSONObject("local_constraints")?.optString("chan_reserve_sat")?.toLongOrNull() ?: 0,
                active = c.optBoolean("active"), pending = false,
                private = c.optBoolean("private"), initiator = c.optBoolean("initiator"),
                channelPoint = c.optString("channel_point"),
                sentSat = c.optString("total_satoshis_sent").toLongOrNull() ?: 0,
                receivedSat = c.optString("total_satoshis_received").toLongOrNull() ?: 0,
            )
        }
        val pend = JSONObject(request("GET", "/v1/channels/pending")).optJSONArray("pending_open_channels")
        for (i in 0 until (pend?.length() ?: 0)) {
            val c = pend!!.getJSONObject(i).optJSONObject("channel") ?: continue
            val pk = c.optString("remote_node_pub")
            val (alias, color) = nodeAlias(pk)
            out += LnChannel(
                peerPubkey = pk, peerAlias = alias, peerColor = color,
                capacitySat = c.optString("capacity").toLongOrNull() ?: 0,
                localSat = c.optString("local_balance").toLongOrNull() ?: 0,
                remoteSat = c.optString("remote_balance").toLongOrNull() ?: 0,
                reserveSat = 0, active = false, pending = true,
                private = c.optBoolean("private"), initiator = c.optString("initiator") == "INITIATOR_LOCAL",
                channelPoint = c.optString("channel_point"),
            )
        }
        return out.sortedWith(compareBy({ it.pending }, { !it.active }, { -it.capacitySat }))
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
            if (e.message.orEmpty().startsWith(L("Uzel odmítl přístupový klíč", "The node rejected the access key")) || e.message.orEmpty().startsWith(L("LND na uzlu je zamčené", "LND on the node is locked"))) throw e
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
        /** Jen zjistí otisk certifikátu uzlu (TLS handshake) – nic se neposílá, žádný klíč. */
        fun probeFingerprint(hostPort: String): String {
            var seen: String? = null
            val capture = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("not a server")
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
                    if (chain.isEmpty()) throw CertificateException(L("Uzel neposlal certifikát", "The node sent no certificate"))
                    seen = sha256Fingerprint(chain[0])
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capture), null) }
            val host = hostPort.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
            val port = hostPort.substringAfterLast(":").toInt()
            java.net.Socket().use { raw ->
                raw.connect(java.net.InetSocketAddress(host, port), 10_000)
                raw.soTimeout = 10_000
                (ctx.socketFactory.createSocket(raw, host, port, false) as javax.net.ssl.SSLSocket).use { it.startHandshake() }
            }
            return seen ?: throw IOException(L("Certifikát uzlu se nepodařilo přečíst", "Could not read the node certificate"))
        }

        /** Jména uzlů se mění zřídka – pamatujeme si je po dobu běhu aplikace. */
        private val aliasCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String?, String?>>()

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
            if (lines.isEmpty()) throw IOException(L("Uzel nevrátil výsledek platby.", "The node returned no payment result."))
            val last = JSONObject(lines.last())
            last.optJSONObject("error")?.let { e ->
                val m = e.optString("message")
                return LnPayResult(false, 0, null, translateFailure(m))
            }
            val r = last.optJSONObject("result") ?: last
            return when (r.optString("status")) {
                "SUCCEEDED" -> LnPayResult(true, r.optString("fee_sat").toLongOrNull() ?: 0, r.optString("payment_preimage"), null)
                "FAILED" -> LnPayResult(false, 0, null, translateFailure(r.optString("failure_reason")))
                else -> LnPayResult(false, 0, null, L("Platba zatím není dokončená (stav ${r.optString("status")}). Zkontrolujte ji za chvíli v historii.", "The payment isn't finished yet (status ${r.optString("status")}). Check it in the history in a moment."))
            }
        }

        fun translateFailure(reason: String): String = when {
            reason.contains("NO_ROUTE") -> L("Nenašla se cesta k příjemci. Zkuste to za chvíli znovu, nebo zvyšte limit poplatku.", "No route to the recipient found. Try again in a moment, or raise the fee limit.")
            reason.contains("INSUFFICIENT_BALANCE") -> L("Na kanálu není dost prostředků (včetně poplatku).", "Not enough funds in the channel (including the fee).")
            reason.contains("TIMEOUT") -> L("Platba nestihla doběhnout. Peníze neodešly, zkuste to znovu.", "The payment timed out. No money was sent, try again.")
            reason.contains("INCORRECT_PAYMENT_DETAILS") -> L("Příjemce platbu odmítl (faktura už je zaplacená, vypršela nebo nesedí částka).", "The recipient rejected the payment (invoice already paid, expired or the amount doesn't match).")
            reason.contains("already paid", true) || reason.contains("ALREADY_PAID", true) -> L("Tahle faktura už je zaplacená.", "This invoice is already paid.")
            reason.contains("self-payments", true) -> L("Nelze platit fakturu vlastního uzlu.", "You can't pay your own node's invoice.")
            reason.contains("invoice expired", true) -> L("Faktura vypršela. Požádejte příjemce o novou.", "The invoice has expired. Ask the recipient for a new one.")
            else -> L("Platba se nezdařila: $reason", "The payment failed: $reason")
        }
    }
}
