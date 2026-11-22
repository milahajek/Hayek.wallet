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

/** Rozloží „ssl://host:port“ / „tcp://host:port“. */
data class ServerAddr(val ssl: Boolean, val host: String, val port: Int) {
    val hostPort get() = "$host:$port"

    companion object {
        fun parse(url: String): ServerAddr {
            val u = url.trim()
            val ssl = when {
                u.startsWith("ssl://") -> true
                u.startsWith("tcp://") -> false
                else -> throw IllegalArgumentException("Adresa musí začínat ssl:// nebo tcp://")
            }
            val rest = u.substringAfter("://").trimEnd('/')
            val host = rest.substringBeforeLast(":")
            val port = rest.substringAfterLast(":").toIntOrNull()
                ?: throw IllegalArgumentException("V adrese chybí číslo portu (např. :50002)")
            require(host.isNotBlank()) { "V adrese chybí IP nebo název uzlu" }
            return ServerAddr(ssl, host, port)
        }
    }
}
