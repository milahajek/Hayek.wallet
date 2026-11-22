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
    val pressed by src.collectIsPressedAsState()
    val elevation by animateDpAsState(if (!enabled) 0.dp else if (pressed) 2.dp else 10.dp, label = "e")
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "s")
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .scale(scale)
            .shadow(elevation, shape, ambientColor = HayekOrangeDeep, spotColor = HayekOrangeDeep)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(HayekOrangeLight, HayekOrange, HayekOrangeDeep)))
            .drawWithContent {
                // lesk v horní polovině – dává dojem vystouplého povrchu
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.28f), 0.5f to Color.Transparent))
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
