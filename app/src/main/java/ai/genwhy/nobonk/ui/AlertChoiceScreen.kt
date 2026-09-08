package ai.genwhy.nobonk.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Explicit cue choice before the first camera session, also shown once after upgrading. */
@Composable
fun AlertChoiceScreen(onChoose: (Boolean, Boolean) -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("How should NoBonk alert you?", style = MaterialTheme.typography.headlineMedium)
        Text("Choose what works for you. You can change sound, vibration and voice in the scan controls anytime.")
        Button(onClick = { onChoose(true, false) }, modifier = Modifier.fillMaxWidth()) { Text("Audible alerts") }
        OutlinedButton(onClick = { onChoose(false, true) }, modifier = Modifier.fillMaxWidth()) { Text("Haptics · vibration only") }
        OutlinedButton(onClick = { onChoose(true, true) }, modifier = Modifier.fillMaxWidth()) { Text("Sound and haptics") }
        Text("Keep looking around you. Alerts can be missed; phone volume, vibration settings and Do Not Disturb may affect them.", style = MaterialTheme.typography.bodyMedium)
    }
}
