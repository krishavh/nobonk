package ai.genwhy.nobonk.ui.components

import ai.genwhy.nobonk.ui.theme.NB
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Translucent dark card used for every HUD element over the camera. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .clip(NB.CardShape)
            .background(Brush.verticalGradient(listOf(Color(0xE60B1220), Color(0xCC0B1220))))
            .border(1.dp, accent?.copy(alpha = 0.45f) ?: NB.GlassLine, NB.CardShape)
            .padding(14.dp),
        content = content
    )
}

@Composable
fun SectionLabel(text: String, color: Color = NB.Sub, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp, modifier = modifier)
}

/** A selectable chip in a segmented group. */
@Composable
fun SegChip(label: String, selected: Boolean, color: Color = NB.Accent, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(NB.ChipShape)
            .background(if (selected) color.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
            .border(1.dp, if (selected) color else Color.Transparent, NB.ChipShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) color else NB.Sub, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** Small status pill: "● NOBONK", "NPU", "84%". */
@Composable
fun Pill(text: String, color: Color = NB.Sub, filled: Boolean = false, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(NB.PillShape)
            .background(if (filled) color.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, color.copy(alpha = if (filled) 0.7f else 0.35f), NB.PillShape)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) { Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp) }
}

/** Breathing dot for "live" states. */
@Composable
fun PulseDot(color: Color, size: Int = 8, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    Box(modifier = modifier.size(size.dp).alpha(a).background(color, CircleShape))
}

/** One-line status banner used for angle / low-light / obstacle notices. */
@Composable
fun NoticeBanner(icon: String, title: String, subtitle: String? = null, color: Color, modifier: Modifier = Modifier, description: String = title) {
    Row(
        modifier = modifier
            .clip(NB.CardShape)
            .background(Color(0xE60B1220))
            .border(1.dp, color.copy(alpha = 0.6f), NB.CardShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(icon, fontSize = 18.sp)
        Column {
            Text(title, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            if (subtitle != null) Text(subtitle, color = NB.Sub, fontSize = 12.sp)
        }
    }
}
