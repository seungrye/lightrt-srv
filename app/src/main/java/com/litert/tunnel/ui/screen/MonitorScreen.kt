package com.litert.tunnel.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.litert.tunnel.engine.EngineMetrics
import com.litert.tunnel.engine.EngineSettings
import com.litert.tunnel.ui.strings.AppLanguage
import com.litert.tunnel.ui.strings.LocalStrings
import com.litert.tunnel.ui.theme.OnSurfaceMuted
import com.litert.tunnel.ui.theme.Primary
import com.litert.tunnel.ui.theme.Surface

private val GaugeGreen  = Color(0xFF4CAF50)
private val GaugeYellow = Color(0xFFFFC107)
private val GaugeRed    = Color(0xFFF44336)
private val GaugeBg     = Color(0xFF2A2A2A)

@Composable
fun MonitorScreen(
    metrics: EngineMetrics,
    settings: EngineSettings,
    onSettingsChange: (EngineSettings) -> Unit,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    val s = LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(s.monitorTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        KvCacheCard(metrics)
        ObservationSizeCard(metrics, settings)
        StatsCard(metrics)
        SettingsCard(settings, onSettingsChange, language, onLanguageChange)
    }
}

// ── KV Cache line chart ────────────────────────────────────────────────────

@Composable
private fun KvCacheCard(metrics: EngineMetrics) {
    val s = LocalStrings.current
    val pressure = metrics.cachePressure
    val gaugeColor = when {
        pressure < 0.5f  -> GaugeGreen
        pressure < 0.75f -> GaugeYellow
        else             -> GaugeRed
    }

    SectionCard(title = s.kvCacheTitle(metrics.maxConversationTurns)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                s.turnLabel(metrics.conversationTurns, metrics.maxConversationTurns),
                color = gaugeColor, fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp, fontFamily = FontFamily.Monospace,
            )
            if (metrics.autoResetCount > 0) {
                Text(s.autoResetBadge(metrics.autoResetCount), color = GaugeRed, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (metrics.turnsHistory.isEmpty()) {
            EmptyChartHint()
        } else {
            LineChart(
                data = metrics.turnsHistory,
                refValue = metrics.maxConversationTurns,
                refLabel = s.limitLabel,
                lineColor = gaugeColor,
                refColor = GaugeRed,
                oldest = s.oldest,
                latestDot = s.latestDot,
            )
        }
    }
}

// ── Observation size line chart ────────────────────────────────────────────

@Composable
private fun ObservationSizeCard(metrics: EngineMetrics, settings: EngineSettings) {
    val s = LocalStrings.current
    SectionCard(title = s.obsCardTitle(metrics.inputSizeHistory.size)) {
        if (metrics.inputSizeHistory.isEmpty()) {
            EmptyChartHint()
        } else {
            val latest = metrics.inputSizeHistory.last()
            val color = when {
                latest < 256  -> GaugeGreen
                latest < 1024 -> GaugeYellow
                else          -> GaugeRed
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(s.latestCharsLabel(latest), color = color,
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                Text("${s.limitLabel} ${settings.maxInputChars}", color = OnSurfaceMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            LineChart(
                data = metrics.inputSizeHistory,
                refValue = settings.maxInputChars,
                refLabel = s.trim,
                lineColor = GaugeYellow,
                refColor = GaugeRed,
                oldest = s.oldest,
                latestDot = s.latestDot,
            )
        }
    }
}

// ── Reusable line chart ────────────────────────────────────────────────────

@Composable
private fun LineChart(
    data: List<Int>,
    refValue: Int,
    refLabel: String,
    lineColor: Color,
    refColor: Color,
    oldest: String,
    latestDot: String,
) {
    val count = data.size
    val yMax  = maxOf(data.max(), refValue, 1)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier.width(44.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            Text("$yMax", color = OnSurfaceMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(50.dp))
            Text("0", color = OnSurfaceMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.width(4.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(GaugeBg),
        ) {
            val w = size.width
            val h = size.height
            fun xOf(i: Int) = if (count > 1) i * w / (count - 1) else w / 2f
            fun yOf(v: Int) = h - v.toFloat() / yMax * h

            // Reference line
            val refY = yOf(refValue)
            drawLine(
                color = refColor.copy(alpha = 0.55f),
                start = Offset(0f, refY), end = Offset(w, refY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f)),
            )

            if (count >= 2) {
                // Fill
                val fillPath = Path().apply {
                    moveTo(xOf(0), h)
                    data.forEachIndexed { i, v -> lineTo(xOf(i), yOf(v)) }
                    lineTo(xOf(count - 1), h)
                    close()
                }
                drawPath(fillPath, lineColor.copy(alpha = 0.12f))

                // Line
                val linePath = Path().apply {
                    data.forEachIndexed { i, v ->
                        if (i == 0) moveTo(xOf(i), yOf(v)) else lineTo(xOf(i), yOf(v))
                    }
                }
                drawPath(linePath, lineColor, style = Stroke(
                    width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                ))
            }

            // Dots when sparse
            if (count <= 40) {
                data.forEachIndexed { i, v ->
                    drawCircle(lineColor, radius = 2.5.dp.toPx(), center = Offset(xOf(i), yOf(v)))
                }
            }

            // Latest dot always
            if (count >= 1) {
                drawCircle(Color.White, radius = 3.dp.toPx(), center = Offset(xOf(count - 1), yOf(data.last())))
            }
        }
    }

    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(oldest, color = OnSurfaceMuted, fontSize = 10.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            repeat(3) {
                Box(Modifier.size(width = 5.dp, height = 1.dp).background(refColor.copy(alpha = 0.55f)))
                Spacer(Modifier.width(3.dp))
            }
            Spacer(Modifier.width(2.dp))
            Text(refLabel, color = OnSurfaceMuted, fontSize = 10.sp)
        }
        Text(latestDot, color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
    }
}

// ── Stats card ─────────────────────────────────────────────────────────────

@Composable
private fun StatsCard(metrics: EngineMetrics) {
    val s = LocalStrings.current
    SectionCard(title = s.statsTitle) {
        StatRow(s.statCurrentTurn,  "${metrics.conversationTurns}")
        StatRow(s.statMaxTurns,     "${metrics.maxConversationTurns}")
        StatRow(s.statAutoResets,   "${metrics.autoResetCount}")
        StatRow(s.statTurnsLogged,  "${metrics.turnsHistory.size}")
        if (metrics.inputSizeHistory.isNotEmpty()) {
            StatRow(s.statLastInput, "${metrics.inputSizeHistory.last()}${s.charsSuffix}")
            StatRow(s.statAvgInput,  "${metrics.inputSizeHistory.average().toInt()}${s.charsSuffix}")
            StatRow(s.statPeakInput, "${metrics.inputSizeHistory.max()}${s.charsSuffix}")
        }
    }
}

// ── Settings card ──────────────────────────────────────────────────────────

@Composable
private fun SettingsCard(
    settings: EngineSettings,
    onSettingsChange: (EngineSettings) -> Unit,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    val s = LocalStrings.current
    SectionCard(title = s.settingsTitle) {

        // ── Language toggle ────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(s.languageLabel, color = Color.White, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LanguageChip("English", language == AppLanguage.EN) { onLanguageChange(AppLanguage.EN) }
                LanguageChip("한국어",  language == AppLanguage.KO) { onLanguageChange(AppLanguage.KO) }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Max KV cache turns ─────────────────────────────────────────
        var turnsSlider by remember(settings.maxConversationTurns) {
            mutableFloatStateOf(settings.maxConversationTurns.toFloat())
        }
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(s.maxKvTurns, color = Color.White, fontSize = 13.sp)
            Text("${turnsSlider.toInt()}", color = Primary, fontSize = 13.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Text(s.kvTurnsDesc(EngineSettings.MIN_TURNS, EngineSettings.MAX_TURNS),
            color = OnSurfaceMuted, fontSize = 11.sp)
        Slider(
            value = turnsSlider, onValueChange = { turnsSlider = it },
            onValueChangeFinished = { onSettingsChange(settings.copy(maxConversationTurns = turnsSlider.toInt())) },
            valueRange = EngineSettings.MIN_TURNS.toFloat()..EngineSettings.MAX_TURNS.toFloat(),
            steps = EngineSettings.MAX_TURNS - EngineSettings.MIN_TURNS - 1,
            colors = sliderColors(), modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        // ── Context replay turns ───────────────────────────────────────
        var replaySlider by remember(settings.contextReplayTurns) {
            mutableFloatStateOf(settings.contextReplayTurns.toFloat())
        }
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(s.contextReplay, color = Color.White, fontSize = 13.sp)
            Text(if (replaySlider.toInt() == 0) s.off else "${replaySlider.toInt()}",
                color = Primary, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Text(s.contextReplayDesc(EngineSettings.MAX_REPLAY_TURNS), color = OnSurfaceMuted, fontSize = 11.sp)
        Slider(
            value = replaySlider, onValueChange = { replaySlider = it },
            onValueChangeFinished = { onSettingsChange(settings.copy(contextReplayTurns = replaySlider.toInt())) },
            valueRange = EngineSettings.MIN_REPLAY_TURNS.toFloat()..EngineSettings.MAX_REPLAY_TURNS.toFloat(),
            steps = EngineSettings.MAX_REPLAY_TURNS - EngineSettings.MIN_REPLAY_TURNS - 1,
            colors = sliderColors(), modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        // ── Max observation chars ──────────────────────────────────────
        var charsSlider by remember(settings.maxInputChars) {
            mutableFloatStateOf(settings.maxInputChars.toFloat())
        }
        val charsSnapped = (charsSlider / 256).toInt() * 256
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(s.maxObsChars, color = Color.White, fontSize = 13.sp)
            Text("$charsSnapped", color = Primary, fontSize = 13.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Text(s.obsCharsDesc(EngineSettings.MIN_INPUT_CHARS, EngineSettings.MAX_INPUT_CHARS),
            color = OnSurfaceMuted, fontSize = 11.sp)
        Slider(
            value = charsSlider, onValueChange = { charsSlider = it },
            onValueChangeFinished = { onSettingsChange(settings.copy(maxInputChars = (charsSlider / 256).toInt() * 256)) },
            valueRange = EngineSettings.MIN_INPUT_CHARS.toFloat()..EngineSettings.MAX_INPUT_CHARS.toFloat(),
            steps = (EngineSettings.MAX_INPUT_CHARS - EngineSettings.MIN_INPUT_CHARS) / 256 - 1,
            colors = sliderColors(), modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── Shared helpers ─────────────────────────────────────────────────────────

@Composable
private fun LanguageChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg     = if (selected) Primary else Color.Transparent
    val fg     = if (selected) Color.Black else OnSurfaceMuted
    val border = if (selected) Primary else OnSurfaceMuted.copy(alpha = 0.4f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun EmptyChartHint() {
    val s = LocalStrings.current
    Text(s.noDataYet, color = OnSurfaceMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Primary, activeTrackColor = Primary, inactiveTrackColor = GaugeBg,
)

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Surface)
            .padding(12.dp),
    ) {
        Text(title, color = OnSurfaceMuted, fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.08f))
        content()
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = OnSurfaceMuted, fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}
