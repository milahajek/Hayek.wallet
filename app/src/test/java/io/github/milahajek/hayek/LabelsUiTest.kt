package io.github.milahajek.hayek

import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LabelsUiTest {
    @Test fun labelsSectionAndDialog() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val vm = ViewModelProvider(act)[WalletViewModel::class.java]
        act.setContent {
            MaterialTheme(lightColorScheme(primary = Color(0xFFF7931A), background = Color(0xFFFCF8F3), surface = Color(0xFFFFFBF6))) {
                HayekBackground {
                    Column(Modifier.padding(20.dp)) { LabelsSection(vm) }
                    ExportLabelsDialog(count = 12, onDismiss = {}, onConfirm = {})
                }
            }
        }
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)) }
        captureScreenRoboImage("build/promo/labels.png")
    }
}
