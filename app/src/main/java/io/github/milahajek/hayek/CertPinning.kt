package io.github.milahajek.hayek

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
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
            if (chain.isEmpty()) throw CertificateException(L("Server neposlal certifikát", "The server sent no certificate"))
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
    val fp = seen ?: throw CertificateException(L("Certifikát se nepodařilo přečíst", "Could not read the certificate"))
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
                throw IllegalArgumentException(L("Adresa obsahuje víc adres najednou – smažte pole (✕) a zadejte jednu", "The field contains several addresses – clear it (✕) and enter one"))
            val ssl = when {
                u.startsWith("ssl://") -> true
                u.startsWith("tcp://") -> false
                else -> throw IllegalArgumentException(L("Adresa musí začínat ssl:// nebo tcp://", "The address must start with ssl:// or tcp://"))
            }
            val rest = u.substringAfter("://").trimEnd('/')
            val host = rest.substringBeforeLast(":")
            val portText = rest.substringAfterLast(":", "")
            val port = portText.toIntOrNull()
                ?: throw IllegalArgumentException(
                    if (!rest.contains(':')) L("V adrese chybí dvojtečka a číslo portu (např. :50002)", "The address is missing a colon and port number (e.g. :50002)")
                    else L("Za poslední dvojtečkou musí být jen číslo portu, je tam „$portText“", "Only a port number may follow the last colon, found „$portText“")
                )
            require(host.isNotBlank()) { L("V adrese chybí IP nebo název uzlu", "The address is missing the node's IP or name") }
            return ServerAddr(ssl, host, port)
        }
    }
}

/** Přijme jakýkoli certifikát – POUZE pro krok „TLS (Java)“ v diagnostice, nikdy pro skutečnou práci s peněženkou. */
@android.annotation.SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
private object DiagnosticOnlyTrust : X509TrustManager {
    override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) = throw CertificateException("not a server")
    override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
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
    val addr = try { ServerAddr.parse(url) } catch (e: Exception) { return L("✘ Adresa: ${e.message}", "✘ Address: ${e.message}") }
    out.append(L("Diagnostika ${addr.hostPort} (${if (addr.ssl) "ssl" else "tcp"})\n", "Diagnostics ${addr.hostPort} (${if (addr.ssl) "ssl" else "tcp"})\n"))
    step(L("1 TCP spojení", "1 TCP connection")) {
        Socket().use { it.connect(InetSocketAddress(addr.host, addr.port), 8000) }; ""
    }
    if (addr.ssl) {
        step("2 TLS (Java)") {
            // Jen diagnostika: zjišťuje, jestli TLS vůbec funguje (i když certifikát nesedí – to ukáže krok 3).
            // Posílá se jen dotaz na verzi serveru, žádné adresy ani jiné údaje peněženky.
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(DiagnosticOnlyTrust), null) }
            Socket().use { raw ->
                raw.connect(InetSocketAddress(addr.host, addr.port), 8000); raw.soTimeout = 8000
                (ctx.socketFactory.createSocket(raw, addr.host, addr.port, false) as SSLSocket).use { s ->
                    s.startHandshake()
                    val sess = s.session
                    s.outputStream.write("{\"id\":1,\"method\":\"server.version\",\"params\":[\"diag\",\"1.4\"]}\n".toByteArray())
                    s.outputStream.flush()
                    val line = s.inputStream.bufferedReader().readLine() ?: L("(žádná odpověď)", "(no response)")
                    L("${sess.protocol} ${sess.cipherSuite}\n   odpověď: ${line.take(80)}", "${sess.protocol} ${sess.cipherSuite}\n   response: ${line.take(80)}")
                }
            }
        }
        step(L("3 Otisk certifikátu", "3 Certificate fingerprint")) {
            val fp = verifyServerCert(addr.host, addr.port, object : PinStore {
                override fun get(hostPort: String) = pins.get(hostPort)
                override fun set(hostPort: String, fingerprint: String) {}
            })
            val p = pins.get(addr.hostPort)
            "${fp.take(23)}… " + when (p) { null -> L("(zatím neuložen)", "(not saved yet)"); fp -> L("(sedí s uloženým)", "(matches the saved one)"); else -> L("(NESEDÍ s uloženým ${p.take(11)}…)", "(DOES NOT match the saved ${p.take(11)}…)") }
        }
    }
    step(L("4 BDK přímo", "4 BDK directly")) {
        val c = org.bitcoindevkit.ElectrumClient(url.trim(), null, 15.toUByte(), 0.toUByte(), false)
        val f = c.serverFeatures()
        L("${f.serverVersion}, blok ${c.blockHeadersSubscribe().height}", "${f.serverVersion}, block ${c.blockHeadersSubscribe().height}")
    }
    step(L("5 BDK podruhé", "5 BDK second attempt")) {
        val c = org.bitcoindevkit.ElectrumClient(url.trim(), null, 15.toUByte(), 0.toUByte(), false)
        c.serverFeatures().serverVersion
    }
    if (addr.ssl && !isPublicServer(url)) step(L("6 Ověřené spojení (přeposílač s kontrolou certifikátu)", "6 Verified connection (relay with certificate check)")) {
        val c = connect(url, pins)
        L("${c.serverFeatures().serverVersion}, blok ${c.blockHeadersSubscribe().height}", "${c.serverFeatures().serverVersion}, block ${c.blockHeadersSubscribe().height}")
    }
    if (!addr.ssl && out.contains("✘ 4") && out.contains("unexpected EOF"))
        out.append(L("\n→ Port nejspíš čeká šifrované spojení. Zkuste ssl://${addr.hostPort}", "\n→ The port probably expects an encrypted connection. Try ssl://${addr.hostPort}"))
    return out.toString().trimEnd()
}

/**
 * Přeposílač (relay) pro vlastní uzel: BDK se připojuje jen na 127.0.0.1 a každé jeho spojení
 * se tady přepošle do NOVÉHO TLS spojení k uzlu, u kterého se certifikát porovná s připnutým otiskem.
 * Tím platí připnutí pro všechna spojení BDK, i pro automatická znovupřipojení
 * (dřív se ověřovalo jen samostatné „zkušební“ spojení a BDK si pak otevřelo vlastní bez kontroly).
 * Naslouchá jen na loopbacku, z jiného zařízení se k němu nikdo nepřipojí.
 */
class PinnedRelay private constructor(private val host: String, private val port: Int, private val store: PinStore) {
    private val hostPort = "$host:$port"
    // Výslovně IPv4 127.0.0.1: na Androidu vrací getLoopbackAddress() IPv6 ::1 a BDK (tcp://127.0.0.1) by se netrefilo.
    private val server = ServerSocket(0, 16, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val localPort: Int get() = server.localPort
    /** Poslední zjištěná neshoda certifikátu (aby šla ukázat uživateli). */
    @Volatile var lastMismatch: CertMismatchException? = null
        private set

    init {
        Thread({
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: Exception) { break }
                Thread({ handle(client) }, "pin-relay-conn").apply { isDaemon = true }.start()
            }
        }, "pin-relay").apply { isDaemon = true }.start()
    }

    private fun pinnedContext(): SSLContext {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("not a server")
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
                if (chain.isEmpty()) throw CertificateException(L("Server neposlal certifikát", "The server sent no certificate"))
                val fp = sha256Fingerprint(chain[0])
                // Relay se spouští až po verifyServerCert, otisk tedy už musí být uložený. Bez něj nic nepustíme.
                val pinned = store.get(hostPort) ?: throw CertificateException(L("Certifikát uzlu není připnutý", "The node certificate is not pinned"))
                if (!pinned.equals(fp, ignoreCase = true)) {
                    lastMismatch = CertMismatchException(hostPort, pinned, fp)
                    throw CertificateException(L("Certifikát uzlu nesouhlasí s připnutým otiskem", "The node certificate does not match the pinned fingerprint"))
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
    }

    private fun handle(client: Socket) {
        val upstream: SSLSocket = try {
            val raw = Socket()
            raw.connect(InetSocketAddress(host, port), 10_000)
            val ssl = pinnedContext().socketFactory.createSocket(raw, host, port, true) as SSLSocket
            ssl.startHandshake()   // tady se ověří otisk; při neshodě výjimka a spojení se zavře
            ssl
        } catch (e: Exception) {
            runCatching { client.close() }
            return
        }
        client.tcpNoDelay = true
        // Electrum je dotaz–odpověď: když jedna strana skončí, ukončíme obě (BDK se případně připojí znovu – a znovu se ověří certifikát).
        val closeBoth = { runCatching { client.close() }; runCatching { upstream.close() }; Unit }
        pipe(client.getInputStream(), upstream.getOutputStream(), "c2s", closeBoth)
        pipe(upstream.getInputStream(), client.getOutputStream(), "s2c", closeBoth)
    }

    private fun pipe(input: InputStream, output: OutputStream, name: String, onEnd: () -> Unit) {
        Thread({
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                onEnd()
            }
        }, "pin-relay-$name").apply { isDaemon = true }.start()
    }

    fun close() { runCatching { server.close() } }

    companion object {
        private val relays = ConcurrentHashMap<String, PinnedRelay>()

        /**
         * Jeden relay na uzel po dobu běhu aplikace – i když o něj žádá někdo jiný (kontrola plateb na pozadí).
         * Relay z úložiště otisků jen čte a všechna úložiště v aplikaci čtou stejná data, takže ho není proč měnit.
         * Kdyby se zavíral a otevíral, uvolněný místní port by mezitím mohla obsadit jiná aplikace v telefonu.
         */
        /** Zavře relay k uzlu (když uživatel jeho certifikát odmítl). */
        fun forget(hostPort: String) { relays.remove(hostPort)?.close() }

        fun forServer(host: String, port: Int, store: PinStore): PinnedRelay =
            relays.compute("$host:$port") { _, old ->
                if (old != null && !old.server.isClosed) old else PinnedRelay(host, port, store)
            }!!
    }
}
