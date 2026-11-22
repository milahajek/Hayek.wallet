package io.github.milahajek.hayek

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
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
 * BDK neumí certifikát připnout sama, proto mezi ni a uzel vkládáme malou místní TLS proxy:
 * BDK mluví nešifrovaně s 127.0.0.1 (uvnitř telefonu) a proxy vede šifrované spojení k uzlu
 * s naší kontrolou certifikátu.
 */
interface PinStore {
    fun get(hostPort: String): String?
    fun set(hostPort: String, fingerprint: String)
}

class CertMismatchException(val hostPort: String, val expected: String, val actual: String) :
    Exception("CERT_MISMATCH")

fun sha256Fingerprint(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

/** Kontrolor certifikátu: první otisk uloží, další musí sedět. */
private class PinningTrustManager(
    private val hostPort: String,
    private val store: PinStore,
    private val onMismatch: (CertMismatchException) -> Unit,
    private val onNewPin: (String) -> Unit,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("not a server")

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
        if (chain.isEmpty()) throw CertificateException("Server neposlal certifikát")
        val fp = sha256Fingerprint(chain[0])
        val pinned = store.get(hostPort)
        when {
            pinned == null -> { store.set(hostPort, fp); onNewPin(fp) }
            pinned != fp -> {
                val e = CertMismatchException(hostPort, pinned, fp)
                onMismatch(e)
                throw CertificateException("CERT_MISMATCH")
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Místní proxy: 127.0.0.1:<port>  →  TLS s připnutým certifikátem  →  uzel. */
class PinnedTlsProxy(private val host: String, private val port: Int, private val store: PinStore) {
    val hostPort = "$host:$port"
    @Volatile var lastMismatch: CertMismatchException? = null
    @Volatile var newPin: String? = null

    private val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    val localPort: Int get() = server.localPort

    private val ssl: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(PinningTrustManager(hostPort, store, { lastMismatch = it }, { newPin = it })), null)
    }

    init {
        Thread({
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                Thread({ handle(client) }, "pin-proxy-conn").apply { isDaemon = true }.start()
            }
        }, "pin-proxy").apply { isDaemon = true }.start()
    }

    private fun handle(client: Socket) {
        val remote: SSLSocket
        try {
            val raw = Socket()
            raw.connect(InetSocketAddress(host, port), 10_000)
            remote = ssl.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            remote.startHandshake()
        } catch (e: Exception) {
            runCatching { client.close() }
            return
        }
        pipe(client.getInputStream(), remote.outputStream, client, remote)
        pipe(remote.inputStream, client.getOutputStream(), client, remote)
    }

    private fun pipe(input: InputStream, output: OutputStream, a: Socket, b: Socket) {
        Thread({
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n); output.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { a.close() }; runCatching { b.close() }
            }
        }, "pin-proxy-pipe").apply { isDaemon = true }.start()
    }

    fun close() = runCatching { server.close() }

    companion object {
        private val proxies = ConcurrentHashMap<String, PinnedTlsProxy>()

        /** Jedna proxy pro každý server (vytvoří se při prvním použití). */
        fun forServer(host: String, port: Int, store: PinStore): PinnedTlsProxy =
            proxies.getOrPut("$host:$port") { PinnedTlsProxy(host, port, store) }
    }
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
