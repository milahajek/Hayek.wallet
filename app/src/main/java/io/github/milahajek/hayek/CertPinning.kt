package io.github.milahajek.hayek

import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Kontrola certifikátu vlastního uzlu („připnutí“, TOFU – trust on first use).
 *
 * Domácí uzly (RaspiBlitz, Umbrel…) používají certifikát podepsaný samy sebou, takže běžné ověření
 * přes certifikační autority nejde. Místo toho si při prvním spojení zapamatujeme otisk (SHA-256)
 * certifikátu a příště přijmeme jen ten. Kdyby se někdo v síti vydával za váš uzel, otisk nebude
 * sedět a spojení se odmítne.
 *
 * BDK neumí certifikát připnout sama, proto před každým připojením certifikát uzlu nejdřív
 * zkontrolujeme vlastním krátkým TLS spojením. Když nesedí, BDK se k uzlu vůbec nepřipojí.
 */
interface PinStore {
    fun get(hostPort: String): String?
    fun set(hostPort: String, fingerprint: String)
}

class CertMismatchException(val hostPort: String, val expected: String, val actual: String) :
    Exception("CERT_MISMATCH")

fun sha256Fingerprint(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

/**
 * Zjistí otisk certifikátu uzlu krátkým TLS spojením a porovná ho s uloženým.
 * Při prvním spojení otisk uloží (TOFU), při neshodě vyhodí CertMismatchException.
 * Volá se před každým připojením BDK k uzlu.
 */
fun verifyServerCert(host: String, port: Int, store: PinStore): String {
    val hostPort = "$host:$port"
    var seen: String? = null
    val capture = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("not a server")
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
            if (chain.isEmpty()) throw CertificateException("Server neposlal certifikát")
            seen = sha256Fingerprint(chain[0])
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capture), null) }
    Socket().use { raw ->
        raw.connect(InetSocketAddress(host, port), 10_000)
        raw.soTimeout = 10_000
        (ctx.socketFactory.createSocket(raw, host, port, false) as SSLSocket).use { it.startHandshake() }
    }
    val fp = seen ?: throw CertificateException("Certifikát se nepodařilo přečíst")
    val pinned = store.get(hostPort)
    when {
        pinned == null -> store.set(hostPort, fp)
        pinned != fp -> throw CertMismatchException(hostPort, pinned, fp)
    }
    return fp
}

/**
 * Očistí adresu serveru od neviditelných znaků a mezer, které umí vložit klávesnice,
 * sjednotí různé druhy dvojteček a malá písmena ve schématu.
 */
fun normalizeServerUrl(input: String): String {
    val cleaned = input
        .replace('：', ':').replace('꞉', ':').replace('∶', ':').replace('﹕', ':')
        .filter { c -> !c.isWhitespace() && Character.getType(c) != Character.FORMAT.toInt() && c != '\uFEFF' }
    val lower = cleaned.lowercase()
    return when {
        lower.startsWith("ssl://") -> "ssl://" + cleaned.substring(6)
        lower.startsWith("tcp://") -> "tcp://" + cleaned.substring(6)
        else -> cleaned
    }
}

/** Rozloží „ssl://host:port“ / „tcp://host:port“. */
data class ServerAddr(val ssl: Boolean, val host: String, val port: Int) {
    val hostPort get() = "$host:$port"

    companion object {
        fun parse(url: String): ServerAddr {
            val u = normalizeServerUrl(url)
            if (u.count { it == ':' } > 2 || u.indexOf("://", 6) >= 0)
                throw IllegalArgumentException("Adresa obsahuje víc adres najednou – smažte pole (✕) a zadejte jednu")
            val ssl = when {
                u.startsWith("ssl://") -> true
                u.startsWith("tcp://") -> false
                else -> throw IllegalArgumentException("Adresa musí začínat ssl:// nebo tcp://")
            }
            val rest = u.substringAfter("://").trimEnd('/')
            val host = rest.substringBeforeLast(":")
            val portText = rest.substringAfterLast(":", "")
            val port = portText.toIntOrNull()
                ?: throw IllegalArgumentException(
                    if (!rest.contains(':')) "V adrese chybí dvojtečka a číslo portu (např. :50002)"
                    else "Za poslední dvojtečkou musí být jen číslo portu, je tam „$portText“"
                )
            require(host.isNotBlank()) { "V adrese chybí IP nebo název uzlu" }
            return ServerAddr(ssl, host, port)
        }
    }
}

/**
 * Podrobná diagnostika spojení s uzlem – krok po kroku, každý krok samostatně.
 * Výsledek je text pro uživatele (aby šlo přesně zjistit, kde se spojení zastaví).
 */
fun diagnoseConnection(url: String, pins: PinStore): String {
    val out = StringBuilder()
    fun step(name: String, block: () -> String) {
        val t0 = System.currentTimeMillis()
        try {
            val r = block()
            out.append("✔ $name (${System.currentTimeMillis() - t0} ms)").append(if (r.isNotEmpty()) "\n   $r" else "").append('\n')
        } catch (e: Throwable) {
            val root = generateSequence(e) { it.cause }.last()
            out.append("✘ $name: ${e::class.simpleName}: ${e.message}")
            if (root !== e) out.append(" ← ${root::class.simpleName}: ${root.message}")
            out.append('\n')
        }
    }
    val addr = try { ServerAddr.parse(url) } catch (e: Exception) { return "✘ Adresa: ${e.message}" }
    out.append("Diagnostika ${addr.hostPort} (${if (addr.ssl) "ssl" else "tcp"})\n")
    step("1 TCP spojení") {
        Socket().use { it.connect(InetSocketAddress(addr.host, addr.port), 8000) }; ""
    }
    if (addr.ssl) {
        step("2 TLS (Java)") {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
            Socket().use { raw ->
                raw.connect(InetSocketAddress(addr.host, addr.port), 8000); raw.soTimeout = 8000
                (ctx.socketFactory.createSocket(raw, addr.host, addr.port, false) as SSLSocket).use { s ->
                    s.startHandshake()
                    val sess = s.session
                    s.outputStream.write("{\"id\":1,\"method\":\"server.version\",\"params\":[\"diag\",\"1.4\"]}\n".toByteArray())
                    s.outputStream.flush()
                    val line = s.inputStream.bufferedReader().readLine() ?: "(žádná odpověď)"
                    "${sess.protocol} ${sess.cipherSuite}\n   odpověď: ${line.take(80)}"
                }
            }
        }
        step("3 Otisk certifikátu") {
            val fp = verifyServerCert(addr.host, addr.port, object : PinStore {
                override fun get(hostPort: String) = pins.get(hostPort)
                override fun set(hostPort: String, fingerprint: String) {}
            })
            val p = pins.get(addr.hostPort)
            "${fp.take(23)}… " + when (p) { null -> "(zatím neuložen)"; fp -> "(sedí s uloženým)"; else -> "(NESEDÍ s uloženým ${p.take(11)}…)" }
        }
    }
    step("4 BDK přímo") {
        val c = org.bitcoindevkit.ElectrumClient(url.trim(), null, 15.toUByte(), 0.toUByte(), false)
        val f = c.serverFeatures()
        "${f.serverVersion}, blok ${c.blockHeadersSubscribe().height}"
    }
    step("5 BDK podruhé") {
        val c = org.bitcoindevkit.ElectrumClient(url.trim(), null, 15.toUByte(), 0.toUByte(), false)
        c.serverFeatures().serverVersion
    }
    if (!addr.ssl && out.contains("✘ 4") && out.contains("unexpected EOF"))
        out.append("\n→ Port nejspíš čeká šifrované spojení. Zkuste ssl://${addr.hostPort}")
    return out.toString().trimEnd()
}
