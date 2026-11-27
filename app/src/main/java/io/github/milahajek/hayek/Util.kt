package io.github.milahajek.hayek

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Locale

/** Částka v satoshi s oddělovači tisíců podle jazyka (1 234 567 sat / 1,234,567 sat). */
fun formatSat(sat: Long): String = String.format(if (Lang.english) Locale.US else Locale.forLanguageTag("cs"), "%,d sat", sat)
fun formatBtc(sat: Long): String = String.format(Locale.US, "%.8f BTC", sat / 100_000_000.0)

fun qrBitmap(text: String, size: Int = 640): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

fun openTx(ctx: Context, vm: WalletViewModel, link: String?) {
    if (link == null) vm.message = L("Odkazy na transakce jsou vypnuté. Adresu Mempoolu nastavíte v ⚙ Nastavení.", "Transaction links are off. Set the Mempool address in ⚙ Settings.")
    else openUrl(ctx, link)
}

fun openUrl(ctx: Context, url: String) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
}

/**
 * @param sensitive true = schránka obsah neukáže v náhledu klávesnice/systému a po 60 s ho smaže.
 */
fun copy(ctx: Context, text: String, sensitive: Boolean = false) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Hayek Wallet", text)
    if (sensitive) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    cm.setPrimaryClip(clip)
    // Krátké zavibrování jako potvrzení (bez oprávnění, přes okno aplikace).
    (ctx as? android.app.Activity ?: (ctx as? android.content.ContextWrapper)?.baseContext as? android.app.Activity)
        ?.window?.decorView?.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
    if (sensitive) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        runCatching { if (cm.primaryClip?.getItemAt(0)?.text?.toString() == text) cm.clearPrimaryClip() }
    }, 60_000)
}

fun paste(ctx: Context): String? {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
}
