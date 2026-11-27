package io.github.milahajek.hayek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class MinersTest {
    // Zkrácené skutečné odpovědi (adresy a jména smyšlené).
    private val axeOs = """{"power":14.2,"voltage":5100,"temp":58.5,"vrTemp":49,"hashRate":1123.45,"bestDiff":"4.29G",
        "bestSessionDiff":"152M","sharesAccepted":12034,"sharesRejected":3,"uptimeSeconds":86400,"hostname":"bitaxe-obyvak",
        "ASICModel":"BM1370","stratumURL":"10.0.0.5","stratumUser":"bc1qexample"}"""
    private val nerdq = """{"hashRate":4950.1,"temp":61,"power":78.5,"bestDiff":"1.02T","sharesAccepted":5,"sharesRejected":0,
        "hostname":"NerdQAxe++","deviceModel":"NerdQAxe++"}"""
    private val cg = """{"STATUS":[{"STATUS":"S"}],"SUMMARY":[{"Elapsed":3600,"MHS av":3950000.0,"MHS 1m":4012000.5,"Accepted":42,"Rejected":1,"Best Share":987654321}],"id":1}""" + "\u0000"

    @Test fun parsers() {
        val a = MinerMath.fromAxeOs("10.0.0.21", axeOs)
        assertEquals("bitaxe-obyvak", a.name); assertEquals("BM1370", a.model)
        assertEquals(1123.45, a.hashrateGh, 1e-9); assertEquals(58.5, a.tempC!!, 1e-9); assertEquals(14.2, a.powerW!!, 1e-9)
        assertEquals(12034L, a.accepted); assertEquals(152e6, a.bestDiff, 1.0)
        val q = MinerMath.fromAxeOs("10.0.0.22", nerdq)
        assertEquals(1.02e12, q.bestDiff, 1e3); assertEquals("NerdQAxe++", q.model)
        val c = MinerMath.fromCgminer("10.0.0.23", cg)
        assertEquals(4012.0005, c.hashrateGh, 1e-6); assertEquals(987654321.0, c.bestDiff, 0.0); assertEquals(42L, c.accepted)
    }

    @Test fun diffAndHosts() {
        assertEquals(4.29e9, MinerMath.parseDiff("4.29G"), 1.0)
        assertEquals(987.0, MinerMath.parseDiff("987"), 0.0)
        assertEquals(1.5e3, MinerMath.parseDiff("1,5k"), 0.0)
        assertEquals(0.0, MinerMath.parseDiff("nesmysl"), 0.0)
        for (ok in listOf("192.168.1.50", "10.1.2.3", "172.20.0.9", "192.168.0.7:8080")) assertTrue(ok, MinerMath.isLanHost(ok))
        for (bad in listOf("8.8.8.8", "127.0.0.1", "172.32.0.1", "192.168.1", "192.168.1.300", "example.com", "192.168.1.5:99999", "")) assertFalse(bad, MinerMath.isLanHost(bad))
        // Mimo domácí síť se klient vůbec nepřipojí.
        assertFalse(MinerClient.fetch("1.1.1.1").online)
    }

    @Test fun odds() {
        // Obtížnost 1, výkon 2^32/600 H/s → přesně 144 bloků za den.
        assertEquals(144.0, MinerMath.blocksPerDay(4294967296.0 / 600 / 1e9, 1.0), 1e-9)
        val perDay = MinerMath.blocksPerDay(5000.0, 1.0e14)
        assertEquals(5e12 * 86400 / (1e14 * 4294967296.0), perDay, 1e-15)
        assertEquals(1 - Math.exp(-perDay * 365), MinerMath.chance(perDay, 365.0), 1e-15)
    }

    @Test fun httpAndChunked() {
        assertEquals("{\"a\":1}", MinerClient.dechunk("4\r\n{\"a\"\r\n3\r\n:1}\r\n0\r\n\r\n"))
        val server = ServerSocket(0)
        thread {
            server.accept().use { s ->
                val req = s.getInputStream().bufferedReader().readLine()
                assertTrue(req.startsWith("GET /api/system/info "))
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n$nerdq".toByteArray())
            }
        }
        val body = MinerClient.httpGet("127.0.0.1", server.localPort, "/api/system/info")
        assertEquals(4950.1, MinerMath.fromAxeOs("x", body).hashrateGh, 1e-9)
        server.close()
    }
}
