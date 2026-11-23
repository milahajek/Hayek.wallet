package io.github.milahajek.hayek

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Úspěšná akce, kterou krátce oslavíme (odeslání, zaplacení, přijetí). */
data class Success(val title: String, val detail: String? = null, val lightning: Boolean = false)

private val isRobolectric = Build.FINGERPRINT == "robolectric"

/** Animace jen tehdy, když je uživatel nevypnul v systému (a ne v testech). */
@Composable
fun motionEnabled(): Boolean {
    val ctx = LocalContext.current
    return remember {
        !isRobolectric && Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
}

/** Pořadí obrazovek – podle něj se přechod posouvá „dopředu“ nebo „zpět“. */
fun screenDepth(s: Screen): Int = when (s) {
    Screen.Home, Screen.Welcome, Screen.Locked -> 0
    Screen.LnPay, Screen.LnReceive, Screen.LnSetup, Screen.Seed, Screen.Export -> 2
    is Screen.Verify -> 3
    is Screen.ShowSeed -> 2
    else -> 1
}

/** Jemný přechod mezi obrazovkami: krátké posunutí + prolnutí (220 ms). */
@Composable
fun ScreenTransition(screen: Screen, content: @Composable (Screen) -> Unit) {
    if (!motionEnabled()) { content(screen); return }
    AnimatedContent(
        targetState = screen,
        contentKey = { it::class },
        transitionSpec = {
            val forward = screenDepth(targetState) >= screenDepth(initialState)
            val dir = if (forward) 1 else -1
            val spec = tween<IntOffset>(220, easing = FastOutSlowInEasing)
            (slideInHorizontally(spec) { w -> dir * w / 10 } + fadeIn(tween(220))) togetherWith
                (slideOutHorizontally(spec) { w -> -dir * w / 10 } + fadeOut(tween(160)))
        },
        label = "screen",
    ) { s -> content(s) }
}

/** Jemné „dýchání“ – u platby, která čeká na potvrzení. */
@Composable
fun Modifier.pendingPulse(active: Boolean): Modifier {
    if (!active || !motionEnabled()) return this
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    return this.alpha(a)
}

/** Krátká oslava úspěchu: kruh, nakreslená fajfka a jemné zavibrování. Sama zmizí. */
@Composable
fun SuccessOverlay(s: Success, onDone: () -> Unit) {
    val view = LocalView.current
    val motion = motionEnabled()
    val circle = remember { Animatable(if (motion) 0f else 1f) }
    val check = remember { Animatable(if (motion) 0f else 1f) }
    val text = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(s) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        if (motion) {
            launch { circle.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
            delay(120)
            launch { text.animateTo(1f, tween(250)) }
            check.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        }
        delay(1300)
        onDone()
    }
    val color = if (s.lightning) Color(0xFFF5B301) else HayekOrange
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
            .clickable(MutableInteractionSource(), null) { onDone() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(120.dp).scale(0.6f + 0.4f * circle.value)) {
                val w = size.width
                drawCircle(color.copy(alpha = 0.15f * circle.value), radius = w / 2)
                drawCircle(color, radius = w / 2 - 4.dp.toPx(), style = Stroke(4.dp.toPx()), alpha = circle.value.coerceIn(0f, 1f))
                val path = Path().apply {
                    moveTo(w * 0.29f, w * 0.52f); lineTo(w * 0.44f, w * 0.66f); lineTo(w * 0.72f, w * 0.37f)
                }
                val pm = PathMeasure().apply { setPath(path, false) }
                val part = Path()
                pm.getSegment(0f, pm.length * check.value, part, true)
                drawPath(part, color, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            Spacer(Modifier.height(18.dp))
            Column(Modifier.alpha(text.value).offset(y = (8 * (1 - text.value)).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                s.detail?.let { Text(it, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

