package ai.genwhy.nobonk.ui.theme

import ai.genwhy.nobonk.model.AlertLevel
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * NoBonk design tokens. One dark, high-contrast system used by every screen and by the
 * camera overlay, so alert colours mean the same thing everywhere:
 *   cyan = system / neutral, green = safe, amber = watch, rose = act now.
 */
object NB {
    val Night = Color(0xFF070B12)
    val Surface = Color(0xFF0F1622)
    val Surface2 = Color(0xFF151E2D)
    val Line = Color(0xFF22304A)
    val Ink = Color(0xFFEAF0FA)
    val Sub = Color(0xFF93A3BD)
    val Dim = Color(0xFF64748B)
    val Accent = Color(0xFF38BDF8)     // cyan
    val Accent2 = Color(0xFFA78BFA)    // violet
    val Safe = Color(0xFF34D399)       // green
    val Watch = Color(0xFFFBBF24)      // amber
    val Danger = Color(0xFFFB7185)     // rose
    val Glass = Color(0xCC0B1220)
    val GlassLine = Color(0x33FFFFFF)

    fun alert(level: AlertLevel): Color = when (level) {
        AlertLevel.NONE -> Accent
        AlertLevel.LOW -> Safe
        AlertLevel.MEDIUM -> Watch
        AlertLevel.HIGH -> Danger
    }

    val CardShape = RoundedCornerShape(20.dp)
    val ChipShape = RoundedCornerShape(12.dp)
    val PillShape = RoundedCornerShape(999.dp)
}

private val DarkColorScheme = darkColorScheme(
    primary = NB.Accent,
    onPrimary = Color(0xFF04121A),
    secondary = NB.Accent2,
    onSecondary = Color(0xFF0B0616),
    tertiary = NB.Safe,
    onTertiary = Color(0xFF04140D),
    error = NB.Danger,
    onError = Color(0xFF1A0508),
    background = NB.Night,
    onBackground = NB.Ink,
    surface = NB.Surface,
    onSurface = NB.Ink,
    surfaceVariant = NB.Surface2,
    onSurfaceVariant = NB.Sub,
    outline = NB.Line,
)

private val NoBonkTypography = Typography(
    displayLarge = TextStyle(fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp),
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp),
)

private val NoBonkShapes = Shapes(
    small = NB.ChipShape,
    medium = RoundedCornerShape(16.dp),
    large = NB.CardShape,
)

@Composable
fun PersonDetectionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColorScheme, typography = NoBonkTypography, shapes = NoBonkShapes, content = content)
}

/** Preferred name; [PersonDetectionTheme] is kept for existing call sites. */
@Composable
fun NoBonkTheme(content: @Composable () -> Unit) = PersonDetectionTheme(content)
