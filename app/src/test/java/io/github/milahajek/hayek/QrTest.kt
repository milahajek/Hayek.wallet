package io.github.milahajek.hayek

import com.google.zxing.BarcodeFormat
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrTest {
    private val addr = "bc1qwnqafccacrx0cnfuqdr5sr0scjzeacpw8srt8a"

    @Test fun plainAddress() = assertEquals(PaymentRequest(addr), parsePaymentQr(addr))

    @Test fun uppercaseUri() = assertEquals(PaymentRequest(addr), parsePaymentQr("BITCOIN:" + addr.uppercase()))

    @Test fun uriWithAmountAndLabel() = assertEquals(
        PaymentRequest(addr, 150_000L, "Hayek Wallet"),
        parsePaymentQr("bitcoin:$addr?amount=0.0015&label=Hayek%20Wallet")
    )

    @Test fun lightningParamIgnored() = assertEquals(
        PaymentRequest(addr, 1_000L), parsePaymentQr("bitcoin:$addr?amount=0.00001&lightning=lnbc10n1xyz")
    )

    @Test fun legacyAndP2sh() {
        assertEquals("1BoatSLRHtKNngkdXEeobR76b53LETtpyT", parsePaymentQr("bitcoin:1BoatSLRHtKNngkdXEeobR76b53LETtpyT")?.address)
        assertEquals("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy", parsePaymentQr("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy")?.address)
    }

    @Test fun rejectsGarbage() {
        assertNull(parsePaymentQr("https://example.com"))
        assertNull(parsePaymentQr("lnbc10n1pj0abcdefghijklmnopqrstuvwxyz0123456789"))
        assertNull(parsePaymentQr("bitcoin:$addr?req-unknown=1"))
        assertNull(parsePaymentQr(""))
    }

    @Test fun badAmountsIgnored() {
        assertNull(btcToSat("-1")); assertNull(btcToSat("0.000000001")); assertNull(btcToSat("abc")); assertNull(btcToSat("22000000"))
        assertEquals(100_000_000L, btcToSat("1")); assertEquals(1L, btcToSat("0.00000001"))
    }

    /** Vykreslí QR kód do „snímku z kamery“ (šedé pixely) a ověří, že ho dekodér přečte – i inverzní. */
    @Test fun decoderRoundTrip() {
        for (invert in listOf(false, true)) {
            val text = "bitcoin:$addr?amount=0.0015"
            val size = 480
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 300, 300)
            val y = ByteArray(size * size) { 200.toByte() }
            for (yy in 0 until 300) for (xx in 0 until 300) {
                val black = m[xx, yy] != invert
                y[(yy + 90) * size + xx + 90] = (if (black) 20 else 230).toByte()
            }
            val src = PlanarYUVLuminanceSource(y, size, size, 0, 0, size, size, false)
            assertEquals(text, QrDecoder().decode(src))
        }
    }

    @Test fun buildRequestRoundTrip() {
        assertEquals("bitcoin:$addr", buildPaymentUri(addr))
        val uri = buildPaymentUri(addr, 150_000, "Oběd & káva")
        assertEquals("bitcoin:$addr?amount=0.0015&label=Ob%C4%9Bd%20%26%20k%C3%A1va", uri)
        assertEquals(PaymentRequest(addr, 150_000, "Oběd & káva"), parsePaymentQr(uri))
        assertEquals("1", satToBtcString(100_000_000)); assertEquals("0.00000001", satToBtcString(1))
    }
}
