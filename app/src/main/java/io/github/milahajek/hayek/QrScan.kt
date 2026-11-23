package io.github.milahajek.hayek

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Dekodér QR kódu z jasové (šedé) složky obrazu. Sdílený s testy. */
class QrDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        ))
    }

    fun decode(source: LuminanceSource): String? {
        // Zkusíme normální i inverzní obraz (světlý QR na tmavém pozadí, např. tmavý režim jiné peněženky).
        for (src in listOf(source, source.invert())) {
            try {
                return reader.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text
            } catch (_: NotFoundException) {
            } catch (_: Exception) {
            } finally {
                reader.reset()
            }
        }
        return null
    }

    fun decode(frame: ImageProxy): String? {
        val plane = frame.planes[0]
        val buf = plane.buffer.duplicate().also { it.rewind() }
        val stride = plane.rowStride
        val w = frame.width
        val h = frame.height
        val data = ByteArray(stride * h)
        buf.get(data, 0, minOf(data.size, buf.remaining()))
        return decode(PlanarYUVLuminanceSource(data, stride, h, 0, 0, w, h, false))
    }
}

/**
 * Celoobrazovkové okno s fotoaparátem. Po prvním přečteném QR kódu zavolá [onResult] a zavře se.
 * Obraz se zpracovává jen v telefonu, nikam se neposílá ani neukládá.
 */
@Composable
fun QrScanDialog(title: String, onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok; denied = !ok
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) CameraPreview(onResult)
            else Text(
                if (denied) L("Bez povolení fotoaparátu nelze QR kód načíst. Povolte ho v Nastavení telefonu → Aplikace → Hayek Wallet → Oprávnění.", "Without camera permission the QR code can't be scanned. Allow it in phone Settings → Apps → Hayek Wallet → Permissions.")
                else L("Čekám na povolení fotoaparátu…", "Waiting for camera permission…"),
                color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp)
            )
            // Rámeček pro zaměření
            if (granted) Box(
                Modifier.align(Alignment.Center).size(260.dp)
                    .border(3.dp, Color(0xFFF7931A), RoundedCornerShape(20.dp))
            )
            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0x99000000))
                    .statusBarsPadding().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(L("Namiřte fotoaparát na QR kód", "Point the camera at a QR code"), color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalIconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(32.dp).size(64.dp)
            ) { Icon(Icons.Default.Close, L("Zavřít", "Close")) }
        }
    }
}

@Composable
private fun CameraPreview(onResult: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val done = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val decoder = remember { QrDecoder() }
    val latest by rememberUpdatedState(onResult)
    val providerFuture = remember { ProcessCameraProvider.getInstance(ctx) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            val view = PreviewView(c).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { frame ->
                    frame.use {
                        if (done.get()) return@use
                        val text = decoder.decode(it) ?: return@use
                        if (done.compareAndSet(false, true)) {
                            ContextCompat.getMainExecutor(c).execute { latest(text) }
                        }
                    }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(c))
            view
        }
    )
}
