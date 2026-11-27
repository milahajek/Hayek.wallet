package io.github.milahajek.hayek

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * Widget na plochu: veřejný stav sítě z uzlu nastaveného v Hayeku.
 * Záměrně neukazuje nic o peněžence (zůstatek, adresy, platby) – widget vidí každý, kdo vezme telefon do ruky.
 * Klepnutí otevře aplikaci (s běžným odemčením), ⟳ obnoví data.
 */
class HayekWidget : GlanceAppWidget() {
    companion object {
        val SMALL = DpSize(110.dp, 48.dp)
        val MEDIUM = DpSize(220.dp, 100.dp)
        val LARGE = DpSize(220.dp, 190.dp)

        suspend fun updateAll(ctx: Context) {
            runCatching {
                if (GlanceAppWidgetManager(ctx).getGlanceIds(HayekWidget::class.java).isNotEmpty()) HayekWidget().updateAll(ctx)
            }
        }
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Lang.init(context)
        val stats = NetworkCache.load(context)
        val kind = NetworkCache.kind(context)
        val ok = NetworkCache.lastAttemptOk(context)
        provideContent { WidgetBody(stats, kind, ok, System.currentTimeMillis()) }
    }
}

class HayekWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HayekWidget()
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        NetworkWorker.schedule(context)
        NetworkWorker.refreshNow(context)
    }
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        NetworkWorker.cancel(context)
    }
}

class WidgetRefresh : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        NetworkWorker.refreshNow(context)
    }
}

private val BG = Color(0xFF17140F)
private val CELL = Color(0xFF26211A)
private val ORANGE = Color(0xFFF7931A)
private val WHITE = Color(0xFFF4EFE8)
private val GREY = Color(0xFFA39A8E)
private val GREEN = Color(0xFF3CC46B)
private val RED = Color(0xFFE5534B)

@Composable
private fun T(text: String, size: TextUnit = 12.sp, color: Color = WHITE, bold: Boolean = false, modifier: GlanceModifier = GlanceModifier) =
    Text(text, modifier, TextStyle(color = ColorProvider(color), fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal), maxLines = 1)

@Composable
fun WidgetBody(s: NetworkStats?, kind: String?, ok: Boolean, now: Long) {
    val size = LocalSize.current
    val dot = when { !ok -> RED; kind == "public" -> ORANGE; else -> GREEN }
    Box(GlanceModifier.fillMaxSize().background(BG).cornerRadius(22.dp).clickable(actionStartActivity<MainActivity>()).padding(12.dp)) {
        if (s == null) {
            Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
                T("Hayek", 16.sp, ORANGE, true)
                T(L("Otevřete Stav sítě v aplikaci", "Open Network status in the app"), 11.sp, GREY)
            }
            return@Box
        }
        Column(GlanceModifier.fillMaxSize()) {
            // Hlavička: logo, výška, stáří bloku, stav připojení, obnovit.
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(R.drawable.ic_logo), "Hayek", GlanceModifier.size(18.dp))
                Spacer(GlanceModifier.width(6.dp))
                T(NetFmt.num(s.tipHeight), 18.sp, WHITE, true)
                Spacer(GlanceModifier.defaultWeight())
                T("●", 11.sp, dot)
                if (size.width >= HayekWidget.MEDIUM.width)
                    T("⟳", 17.sp, GREY, true, GlanceModifier.padding(start = 8.dp).clickable(actionRunCallback<WidgetRefresh>()))
            }
            T(L("blok ", "block ") + NetFmt.ago((now / 1000 - s.tipTime).coerceAtLeast(0)), 11.sp, GREY)
            if (size.height >= HayekWidget.MEDIUM.height && size.width >= HayekWidget.MEDIUM.width) {
                Spacer(GlanceModifier.height(8.dp))
                Row(GlanceModifier.fillMaxWidth()) {
                    FeeCell(L("rychle", "fast"), s.fastFee, true, GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(5.dp))
                    FeeCell(L("běžně", "normal"), s.normalFee, false, GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(5.dp))
                    FeeCell(L("pomalu", "slow"), s.slowFee, false, GlanceModifier.defaultWeight())
                }
            }
            if (size.height >= HayekWidget.LARGE.height && size.width >= HayekWidget.MEDIUM.width) {
                val r = ChainMath.retarget(s.tipHeight, s.tipTime, s.epochStartTime)
                val h = ChainMath.halving(s.tipHeight)
                Spacer(GlanceModifier.height(10.dp))
                Bar(L("Obtížnost ", "Difficulty ") + (r.change?.let { NetFmt.signedPct(it) } ?: "—"), L("za ${NetFmt.num(r.blocksLeft)} bl.", "in ${NetFmt.num(r.blocksLeft)} bl."), r.blocksDone.toFloat() / ChainMath.RETARGET)
                Spacer(GlanceModifier.height(6.dp))
                Bar(L("Halving", "Halving"), L("za ${NetFmt.num(h.blocksLeft)} bl.", "in ${NetFmt.num(h.blocksLeft)} bl."), h.progress)
            }
            Spacer(GlanceModifier.defaultWeight())
            if (size.height >= HayekWidget.MEDIUM.height)
                T(if (ok) L("aktualizováno ", "updated ") + NetFmt.ago((now - s.fetchedAt) / 1000) else L("uzel nedostupný · data ", "node unreachable · data ") + NetFmt.ago((now - s.fetchedAt) / 1000),
                    10.sp, if (ok) GREY else RED)
        }
    }
}

@Composable
private fun FeeCell(label: String, rate: Long, accent: Boolean, modifier: GlanceModifier) {
    Column(modifier.background(CELL).cornerRadius(10.dp).padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        T("$rate", 16.sp, if (accent) ORANGE else WHITE, true)
        T("$label sat/vB", 9.sp, GREY)
    }
}

@Composable
private fun Bar(title: String, right: String, progress: Float) {
    Column(GlanceModifier.fillMaxWidth()) {
        Row(GlanceModifier.fillMaxWidth()) {
            T(title, 11.sp, WHITE, true)
            Spacer(GlanceModifier.defaultWeight())
            T(right, 10.sp, GREY)
        }
        Spacer(GlanceModifier.height(3.dp))
        LinearProgressIndicator(progress.coerceIn(0f, 1f), GlanceModifier.fillMaxWidth().height(6.dp).cornerRadius(3.dp),
            color = ColorProvider(ORANGE), backgroundColor = ColorProvider(CELL))
    }
}
