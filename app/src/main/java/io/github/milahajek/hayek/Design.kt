package io.github.milahajek.hayek

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.CardColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/* ---------- Barvy aplikace ---------- */
val HayekOrange = Color(0xFFF7931A)
val HayekOrangeLight = Color(0xFFFFB55C)
val HayekOrangeDeep = Color(0xFFD6700A)
val HayekInk = Color(0xFF1C1206)

/** Je aktuální téma tmavé? (podle barvy pozadí, takže respektuje i ruční přepínač) */
@Composable
fun isDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/* ---------- Pozadí: jemný přechod + teplá záře nahoře ---------- */
@Composable
fun HayekBackground(content: @Composable BoxScope.() -> Unit) {
    val dark = isDark()
    val base = if (dark) listOf(Color(0xFF0D0F14), Color(0xFF151821), Color(0xFF0D0F14))
    else listOf(Color(0xFFFCF8F3), Color(0xFFF3EDE5), Color(0xFFEDE6DC))
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(base))
            .drawWithContent {
                drawRect(
                    Brush.radialGradient(
                        listOf(HayekOrange.copy(alpha = if (dark) 0.16f else 0.13f), Color.Transparent),
                        center = Offset(size.width * 0.5f, -size.width * 0.15f),
                        radius = size.width * 1.1f,
                    )
                )
                drawContent()
            },
    ) {
        // Text bez vlastní barvy bere barvu podle tématu (bílá v tmavém, tmavá ve světlém)
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) { content() }
    }
}

/* ---------- Sklo: poloprůhledná plocha s jemným okrajem ---------- */
@Composable
private fun glassFill(dark: Boolean) = if (dark)
    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.04f)))
else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.60f)))

@Composable
private fun glassEdge(dark: Boolean) = if (dark)
    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.05f)))
else Brush.verticalGradient(listOf(Color.White, Color.Black.copy(alpha = 0.08f)))

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    colors: CardColors? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = isDark()
    val shape = RoundedCornerShape(24.dp)
    val tint = colors?.containerColor
    Column(
        modifier
            .shadow(if (dark) 14.dp else 10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(shape)
            .background(glassFill(dark))
            .then(if (tint != null) Modifier.background(tint.copy(alpha = 0.35f)) else Modifier)
            .border(1.dp, glassEdge(dark), shape),
        content = content,
    )
}

/* ---------- Primární tlačítko: vystouplé, oranžové, s odleskem ---------- */
@Composable
fun HayekButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val p = pressAnim(src, enabled)
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .pressed3D(p, enabled)
            .shadow(if (!enabled) 0.dp else 10.dp * (1 - p) + 1.dp, shape, ambientColor = HayekOrangeDeep, spotColor = HayekOrangeDeep)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(HayekOrangeLight, HayekOrange, HayekOrangeDeep)))
            .drawWithContent {
                // Lesk nahoře = vystouplý povrch; při stisku zmizí a dole přibude stín = tlačítko „zapadne“.
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.28f * (1 - p)), 0.5f to Color.Transparent))
                drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.18f * p), 0.4f to Color.Transparent))
                drawContent()
            }
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.12f))), shape)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(src, ripple(color = Color.White), enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides HayekInk) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)) { content() }
        }
    }
}

/* ---------- Vedlejší tlačítko: skleněné ---------- */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val dark = isDark()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "s")
    val shape = RoundedCornerShape(18.dp)
    val fg = colors?.contentColor ?: MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .scale(scale)
            .shadow(if (enabled && !pressed) 5.dp else 0.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
            .clip(shape)
            .background(glassFill(dark))
            .border(1.dp, glassEdge(dark), shape)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(src, ripple(color = HayekOrange), enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)) { content() }
        }
    }
}

/**
 * Fyzický stisk: 0 = puštěno, 1 = zmáčknuto. Při stisku krátká haptika,
 * animace pružinou (tlačítko se po puštění lehce „vrátí“). Respektuje vypnuté animace.
 */
@Composable
fun pressAnim(src: MutableInteractionSource, enabled: Boolean = true): Float {
    val pressed by src.collectIsPressedAsState()
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(pressed) { if (pressed && enabled) view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY) }
    val motion = motionEnabled()
    val p by animateFloatAsState(
        if (pressed && enabled) 1f else 0f,
        if (motion) androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 900f) else androidx.compose.animation.core.snap(),
        label = "press",
    )
    return p
}

/** Při stisku se prvek zmenší a posune o kousek dolů – jako by se zamáčkl. */
fun Modifier.pressed3D(p: Float, enabled: Boolean = true): Modifier = if (!enabled) this else this.graphicsLayer {
    val s = 1f - 0.05f * p
    scaleX = s; scaleY = s
    translationY = 2.5f * density * p
}

/**
 * Vystouplá dlaždice v barvě [tint] (Bez KYC / KYC…): přechod světlejší nahoře, lesklá horní hrana,
 * barevný stín a fyzický stisk.
 */
@Composable
fun RaisedTile(
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val p = pressAnim(src)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .pressed3D(p)
            .shadow(8.dp * (1 - p) + 1.dp, shape, ambientColor = tint, spotColor = tint)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = if (dark) 0.34f else 0.22f), tint.copy(alpha = if (dark) 0.14f else 0.08f))))
            .drawWithContent {
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = (if (dark) 0.14f else 0.45f) * (1 - p)), 0.45f to Color.Transparent))
                drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.16f * p), 0.4f to Color.Transparent))
                drawContent()
            }
            .border(1.2.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.35f else 0.8f), tint.copy(alpha = 0.55f))), shape)
            .clickable(src, ripple(color = tint), onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 12.dp),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = verticalArrangement,
        content = content,
    )
}
