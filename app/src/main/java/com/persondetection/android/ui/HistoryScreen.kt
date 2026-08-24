package com.persondetection.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.persondetection.android.analytics.AnalyticsEngine
import com.persondetection.android.data.DetectionEvent
import com.persondetection.android.data.SessionSummary
import java.util.Locale
import kotlin.math.roundToInt

// ── Colour palette ────────────────────────────────────────────────────────────

private val BgDark       = Color(0xFF0A0E1A)
private val CardBg       = Color(0xFF141828)
private val Cyan         = Color(0xFF00E5FF)
private val Orange       = Color(0xFFFF6D00)
private val Red          = Color(0xFFFF1744)
private val Green        = Color(0xFF69F0AE)
private val Purple       = Color(0xFFD500F9)
private val TextPrimary  = Color(0xFFEEEEEE)
private val TextSecondary= Color(0xFF9E9E9E)

// ── Screen entry point ────────────────────────────────────────────────────────

/**
 * Full-screen analytics dashboard.
 *
 * @param events         All stored [DetectionEvent] records
 * @param sessions       Pre-computed [SessionSummary] list (recent first)
 * @param onBack         Called when the user presses the back arrow
 */
@Composable
fun HistoryScreen(
    events: List<DetectionEvent>,
    sessions: List<SessionSummary>,
    onBack: () -> Unit,
    onClearHistory: () -> Unit = {},
    locationTaggingEnabled: Boolean = false,
    onEnableLocation: () -> Unit = {},
    onDisableLocation: () -> Unit = {},
    onShowLicenses: () -> Unit = {}
) {
    val stats   = remember(events) { AnalyticsEngine.overallStats(events) }
    val hourly  = remember(events) { AnalyticsEngine.warningsByHour(events) }
    val threats = remember(events) { AnalyticsEngine.threatBreakdown(events) }
    val spots   = remember(events) { AnalyticsEngine.hotspots(events) }
    val alert   = remember(events) { AnalyticsEngine.alertStats(events) }
    val closest = remember(events) { AnalyticsEngine.closestCallDistance(events) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ── Header ─────────────────────────────────────────
            item {
                Header(onBack = onBack)
            }

            // ── Controls: location opt-in + clear history ──────
            item {
                HistoryControls(
                    locationEnabled = locationTaggingEnabled,
                    onEnableLocation = onEnableLocation,
                    onDisableLocation = onDisableLocation,
                    onClearHistory = onClearHistory
                )
            }

            // ── Key stat cards ─────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                StatCardRow(stats = stats, closest = closest)
            }

            // ── Alert severity breakdown ───────────────────────
            item {
                Spacer(Modifier.height(12.dp))
                SeverityBar(alert = alert)
            }

            // ── Hourly activity chart ──────────────────────────
            item {
                Spacer(Modifier.height(12.dp))
                SectionTitle("⏰  WARNINGS BY HOUR OF DAY")
                HourlyChart(hourly = hourly, peakHour = stats.peakHour)
            }

            // ── Threat breakdown ───────────────────────────────
            if (threats.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(12.dp))
                    SectionTitle("🎯  WHAT'S BEEN DETECTED")
                    ThreatBreakdown(threats = threats)
                }
            }

            // ── Hotspot mini-map ───────────────────────────────
            if (spots.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(12.dp))
                    SectionTitle("📍  DANGER HOTSPOTS")
                    HotspotMap(hotspots = spots)
                }
            }

            // ── Session list ───────────────────────────────────
            if (sessions.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(12.dp))
                    SectionTitle("🗓️  RECENT SESSIONS")
                }
                items(sessions) { session ->
                    SessionRow(session = session)
                }
            }

            // ── Empty state ────────────────────────────────────
            if (events.isEmpty()) {
                item {
                    EmptyState()
                }
            }

            // ── Footer: open-source licenses / source (AGPL §13) ──
            item {
                Spacer(Modifier.height(24.dp))
                TextButton(
                    onClick = onShowLicenses,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Open-source licenses & source code",
                        color = Cyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color.Black, BgDark))
            )
            .padding(top = 44.dp, start = 8.dp, end = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Cyan)
        }
        Spacer(Modifier.width(4.dp))
        Column {
            Text(
                "DETECTION HISTORY",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp
            )
            Text(
                "GPS-tagged activity & insights",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

// ── Controls (location opt-in + clear history) ──────────────────────────────────

@Composable
private fun HistoryControls(
    locationEnabled: Boolean,
    onEnableLocation: () -> Unit,
    onDisableLocation: () -> Unit,
    onClearHistory: () -> Unit
) {
    var confirmClear by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Location tagging is OFF by default; the user opts in here, in context.
        OutlinedButton(
            onClick = { if (locationEnabled) onDisableLocation() else onEnableLocation() },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (locationEnabled) Green else TextSecondary
            )
        ) {
            Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (locationEnabled) "Location ON" else "Tag location", fontSize = 12.sp)
        }
        OutlinedButton(
            onClick = { confirmClear = true },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Red)
        ) {
            Text("Clear history", fontSize = 12.sp)
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClearHistory() }) {
                    Text("Delete all", color = Red)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
            title = { Text("Clear all history?") },
            text = { Text("This permanently deletes every stored detection event from this device.") }
        )
    }
}

// ── Stat cards ────────────────────────────────────────────────────────────────

@Composable
private fun StatCardRow(
    stats: AnalyticsEngine.OverallStats,
    closest: Float?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard(
            modifier = Modifier.weight(1f),
            label = "TOTAL\nALERTS",
            value = stats.totalEvents.toString(),
            color = Cyan
        )
        StatCard(
            modifier = Modifier.weight(1f),
            label = "CLOSE\nCALLS",
            value = stats.closeCalls.toString(),
            color = Red
        )
        StatCard(
            modifier = Modifier.weight(1f),
            label = "SESSIONS",
            value = stats.totalSessions.toString(),
            color = Green
        )
        StatCard(
            modifier = Modifier.weight(1f),
            label = "NEAREST\nOBJECT",
            value = closest?.let { String.format(Locale.US, "%.1fm", it) } ?: "—",
            color = Orange
        )
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    color: Color
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(vertical = 14.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            color = color,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            textAlign = TextAlign.Center,
            lineHeight = 12.sp
        )
    }
}

// ── Severity bar ──────────────────────────────────────────────────────────────

@Composable
private fun SeverityBar(alert: AnalyticsEngine.AlertStats) {
    val total = alert.total.coerceAtLeast(1)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("ALERT SEVERITY", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(12.dp))
            // Segmented bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.White.copy(alpha = 0.07f))
            ) {
                Row(Modifier.fillMaxSize()) {
                    val highW  = alert.high   / total.toFloat()
                    val medW   = alert.medium / total.toFloat()
                    val lowW   = alert.low    / total.toFloat()
                    if (highW > 0) Box(Modifier.fillMaxHeight().weight(highW).background(Red))
                    if (medW  > 0) Box(Modifier.fillMaxHeight().weight(medW ).background(Orange))
                    if (lowW  > 0) Box(Modifier.fillMaxHeight().weight(lowW ).background(Green))
                    if (highW + medW + lowW < 1f)
                        Box(Modifier.fillMaxHeight().weight(1f - highW - medW - lowW).background(Color.Transparent))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SeverityLabel("HIGH",   alert.high,   Red)
                SeverityLabel("MEDIUM", alert.medium, Orange)
                SeverityLabel("LOW",    alert.low,    Green)
            }
        }
    }
}

@Composable
private fun SeverityLabel(label: String, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text("$label ($count)", color = TextSecondary, fontSize = 11.sp)
    }
}

// ── Section title ─────────────────────────────────────────────────────────────

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        color = TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

// ── Hourly chart ──────────────────────────────────────────────────────────────

@Composable
private fun HourlyChart(hourly: IntArray, peakHour: Int) {
    val maxVal = hourly.max().coerceAtLeast(1).toFloat()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(Modifier.padding(16.dp)) {
            if (peakHour >= 0) {
                Text(
                    "Peak danger: ${AnalyticsEngine.formatHour(peakHour)}",
                    color = Orange,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            ) {
                val barWidth = size.width / 24f
                val gap = barWidth * 0.15f
                val chartHeight = size.height - 20.dp.toPx()

                for (hour in 0 until 24) {
                    val barH = (hourly[hour] / maxVal) * chartHeight
                    val x = hour * barWidth + gap / 2

                    val barColor = when {
                        hour == peakHour -> Red
                        hour in 6..9 || hour in 17..19 -> Orange   // rush hours
                        else -> Cyan.copy(alpha = 0.6f)
                    }

                    // Bar
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(x, chartHeight - barH),
                        size = Size(barWidth - gap, barH.coerceAtLeast(2f)),
                        cornerRadius = CornerRadius(3f, 3f)
                    )
                    // Bottom glow for non-zero bars
                    if (hourly[hour] > 0) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                listOf(barColor.copy(alpha = 0.25f), Color.Transparent),
                                startY = chartHeight - barH,
                                endY = chartHeight
                            ),
                            topLeft = Offset(x, chartHeight - barH),
                            size = Size(barWidth - gap, barH)
                        )
                    }
                }

                // X-axis line
                drawLine(
                    color = Color.White.copy(alpha = 0.12f),
                    start = Offset(0f, chartHeight),
                    end = Offset(size.width, chartHeight),
                    strokeWidth = 1f
                )
            }

            // Hour labels (every 4 hours)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf("12A", "4A", "8A", "12P", "4P", "8P", "12A").forEach { label ->
                    Text(label, color = TextSecondary, fontSize = 8.sp)
                }
            }
        }
    }
}

// ── Threat breakdown ──────────────────────────────────────────────────────────

@Composable
private fun ThreatBreakdown(threats: List<Pair<String, Int>>) {
    val totalThreats = threats.sumOf { it.second }.coerceAtLeast(1)
    val barColors = listOf(Cyan, Orange, Purple, Green, Red)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            threats.take(5).forEachIndexed { idx, (className, count) ->
                val pct = count / totalThreats.toFloat()
                val color = barColors[idx % barColors.size]
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            className.replaceFirstChar { it.uppercase() },
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "$count  (${(pct * 100).roundToInt()}%)",
                            color = color,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.07f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(pct)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(color, color.copy(alpha = 0.5f))
                                    )
                                )
                        )
                    }
                }
            }
        }
    }
}

// ── Hotspot mini-map ──────────────────────────────────────────────────────────

@Composable
private fun HotspotMap(hotspots: List<AnalyticsEngine.Hotspot>) {
    if (hotspots.isEmpty()) return

    // Compute bounding box of all hotspots
    val minLat = hotspots.minOf { it.latitude }
    val maxLat = hotspots.maxOf { it.latitude }
    val minLng = hotspots.minOf { it.longitude }
    val maxLng = hotspots.maxOf { it.longitude }
    val latRange = (maxLat - minLat).coerceAtLeast(0.001)
    val lngRange = (maxLng - minLng).coerceAtLeast(0.001)
    val maxCount = hotspots.maxOf { it.count }.coerceAtLeast(1)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = Red,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "${hotspots.size} location cluster${if (hotspots.size != 1) "s" else ""} found",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(12.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0D1117))
            ) {
                val padding = 24.dp.toPx()
                val mapW = size.width - padding * 2
                val mapH = size.height - padding * 2

                // Grid lines
                for (i in 0..4) {
                    val y = padding + (i / 4f) * mapH
                    drawLine(Color.White.copy(alpha = 0.05f), Offset(padding, y), Offset(padding + mapW, y))
                }
                for (j in 0..4) {
                    val x = padding + (j / 4f) * mapW
                    drawLine(Color.White.copy(alpha = 0.05f), Offset(x, padding), Offset(x, padding + mapH))
                }

                hotspots.forEach { spot ->
                    // Project lat/lng to canvas coords
                    // Note: latitude increases up so we invert Y
                    val relLng = ((spot.longitude - minLng) / lngRange).toFloat()
                    val relLat = (1f - ((spot.latitude  - minLat) / latRange)).toFloat()
                    val cx = padding + relLng * mapW
                    val cy = padding + relLat * mapH

                    val intensity = spot.count / maxCount.toFloat()
                    val radius = (8f + intensity * 28f)

                    // Outer glow
                    drawCircle(
                        color = Red.copy(alpha = intensity * 0.25f),
                        radius = radius * 2f,
                        center = Offset(cx, cy)
                    )
                    // Core dot
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Red, Orange.copy(alpha = 0.7f)),
                            center = Offset(cx, cy),
                            radius = radius
                        ),
                        radius = radius,
                        center = Offset(cx, cy)
                    )
                    // Ring
                    drawCircle(
                        color = Red.copy(alpha = 0.8f),
                        radius = radius,
                        center = Offset(cx, cy),
                        style = Stroke(2f)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Dot size = relative number of events at location",
                color = TextSecondary,
                fontSize = 10.sp
            )
        }
    }
}

// ── Session row ───────────────────────────────────────────────────────────────

@Composable
private fun SessionRow(session: SessionSummary) {
    val alertColor = when {
        session.highAlerts > 0 -> Red
        session.totalEvents > 5 -> Orange
        else -> Green
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Severity dot
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(alertColor)
            )
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    AnalyticsEngine.formatTimestamp(session.startTimestamp),
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    buildString {
                        append(AnalyticsEngine.formatDuration(session.durationMinutes))
                        append("  •  mostly ")
                        append(session.topThreat)
                        // Guard against null (GPS-less session). The old `!= 0.0` check let a
                        // null Double? through to String.format → "null, null" / crash (T-UI-CRASH).
                        val lat = session.startLatitude
                        val lng = session.startLongitude
                        if (lat != null && lng != null) {
                            append("  •  ")
                            append(String.format(Locale.US, "%.4f, %.4f", lat, lng))
                        }
                    },
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${session.totalEvents}",
                    color = alertColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    "alerts",
                    color = TextSecondary,
                    fontSize = 10.sp
                )
            }

            if (session.highAlerts > 0) {
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "High alerts",
                        tint = Red,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "${session.highAlerts}",
                        color = Red,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("📊", fontSize = 64.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            "No history yet",
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Warnings will appear here once the app\ndetects objects during your walks.",
            color = TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
    }
}
