package ai.genwhy.nobonk.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.genwhy.nobonk.ui.theme.NB

/** Explicit cue choice before the first camera session, also shown once after upgrading. */
@Composable
fun AlertChoiceScreen(onChoose: (Boolean, Boolean) -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Wordmark(size = 40)
        Spacer(Modifier.height(8.dp))
        Text("Your heads-up.\nYour choice.", style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() })
        Text("How should NoBonk alert you? Pick one to continue. On-screen warnings stay on with every choice.", color = NB.Sub)
        AlertChoice("Audible alerts", "Short tones. With stereo earbuds, the sound points toward the detected object.", NB.Accent) { onChoose(true, false) }
        AlertChoice("Haptics", "Vibration only. A quieter nudge, without alert sounds.", NB.Accent2) { onChoose(false, true) }
        AlertChoice("Sound and haptics", "Hear the alert and feel the vibration.", NB.Safe) { onChoose(true, true) }
        Text("You can change these in the scan controls anytime. Spoken alerts are off until you choose to enable them.", color = NB.Sub, style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider(color = NB.Line)
        Text("Keep looking around you. Alerts can be missed; volume, vibration settings and Do Not Disturb may affect them.", color = NB.Sub, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AlertChoice(title: String, description: String, tint: Color, onChoose: () -> Unit) {
    OutlinedCard(onClick = onChoose, modifier = Modifier.fillMaxWidth(),
        shape = NB.CardShape, border = BorderStroke(1.dp, tint.copy(alpha = 0.5f)),
        colors = CardDefaults.outlinedCardColors(containerColor = tint.copy(alpha = 0.06f))) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, color = tint, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(description, color = NB.Ink, style = MaterialTheme.typography.bodyMedium)
            }
            Text("›", color = tint, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 14.dp))
        }
    }
}
