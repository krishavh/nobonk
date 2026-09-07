package ai.genwhy.nobonk.ui

import ai.genwhy.nobonk.safety.SafetyNotice
import ai.genwhy.nobonk.ui.components.SectionLabel
import ai.genwhy.nobonk.ui.theme.NB
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full safety notice with explicit acknowledgment. Shown before any camera permission
 * request or camera start until the current notice version is acknowledged.
 */
@Composable
fun SafetyNoticeScreen(onAccept: () -> Unit, onNotNow: () -> Unit) {
    var checked by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1220), NB.Night))).statusBarsPadding().navigationBarsPadding()
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(28.dp))
            Wordmark(size = 56)
            Spacer(Modifier.height(20.dp))
            Text("A little more awareness for the path ahead.", color = NB.Ink, fontSize = 26.sp, lineHeight = 31.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(12.dp))
            Text(SafetyNotice.INTRO, color = NB.Sub, fontSize = 16.sp, lineHeight = 24.sp)
            Spacer(Modifier.height(22.dp))
            SectionLabel("What it does")
            Spacer(Modifier.height(10.dp))
            ValueCard("👀", "Tries to spot people, bikes, cars and pets", "An on-device vision model looks at each frame and estimates how close things are and whether they are closing in. It will miss some.", NB.Accent)
            ValueCard("🔒", "Nothing leaves the phone", "No photos, no video, no uploads, no internet permission. Frames live in memory for a few milliseconds and are gone.", NB.Safe)
            ValueCard("🎧", "Cues you can hear and feel", "Vibration, a short sound panned toward the hazard and, if you turn it on, a spoken warning. Each can be switched off.", NB.Watch)
            Spacer(Modifier.height(18.dp))
            SectionLabel("Please read before you start")
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.fillMaxWidth().clip(NB.CardShape).background(NB.Watch.copy(alpha = 0.10f)).border(1.5.dp, NB.Watch.copy(alpha = 0.7f), NB.CardShape).padding(16.dp)
                    .semantics { contentDescription = "Safety notice. " + SafetyNotice.MAIN_TEXT }
            ) {
                Text("⚠️  NOT A SAFETY DEVICE", color = NB.Watch, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
                Spacer(Modifier.height(8.dp))
                Text(SafetyNotice.MAIN_TEXT, color = NB.Ink, fontSize = 15.sp, lineHeight = 23.sp)
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(NB.CardShape).background(Color.White.copy(alpha = 0.05f))
                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = { checked = it })
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = NB.Safe, uncheckedColor = NB.Sub))
                Spacer(Modifier.width(6.dp))
                Text(SafetyNotice.CHECKBOX_TEXT, color = NB.Ink, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, NB.Night))).padding(horizontal = 22.dp).padding(top = 8.dp, bottom = 16.dp)) {
            Button(
                onClick = onAccept, enabled = SafetyNotice.canContinue(checked),
                modifier = Modifier.fillMaxWidth().height(56.dp), shape = NB.ChipShape,
                colors = ButtonDefaults.buttonColors(containerColor = NB.Safe, contentColor = Color(0xFF04140D), disabledContainerColor = Color.White.copy(alpha = 0.10f), disabledContentColor = NB.Dim)
            ) { Text(SafetyNotice.ACCEPT_LABEL, fontSize = 17.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center) }
            TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(SafetyNotice.DECLINE_LABEL, color = NB.Sub, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            Text(if (checked) "You'll be asked for camera access next." else "Tick the box above to continue.", color = NB.Dim, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** Mandatory reminder on every genuine cold launch after acknowledgment: blocks until "OK — continue" is pressed; never shown mid-session. */
@Composable
fun StayAwareReminder(onContinue: () -> Unit, onReadFull: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(NB.Night).statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).clip(NB.CardShape).background(NB.Watch.copy(alpha = 0.10f)).border(1.5.dp, NB.Watch.copy(alpha = 0.7f), NB.CardShape).padding(20.dp)
            .semantics { contentDescription = SafetyNotice.REMINDER_TITLE + ". " + SafetyNotice.REMINDER_TEXT }) {
            Text("⚠️  " + SafetyNotice.REMINDER_TITLE.uppercase(), color = NB.Watch, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
            Spacer(Modifier.height(10.dp))
            Text(SafetyNotice.REMINDER_TEXT, color = NB.Ink, fontSize = 17.sp, lineHeight = 25.sp)
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(56.dp), shape = NB.ChipShape,
            colors = ButtonDefaults.buttonColors(containerColor = NB.Safe, contentColor = Color(0xFF04140D))) { Text(SafetyNotice.REMINDER_OK_LABEL, fontSize = 17.sp, fontWeight = FontWeight.Black) }
        TextButton(onClick = onReadFull, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Read the full safety notice", color = NB.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun ValueCard(emoji: String, title: String, body: String, accent: Color) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(NB.CardShape).background(Color.White.copy(alpha = 0.04f)).border(1.dp, Color.White.copy(alpha = 0.08f), NB.CardShape).padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.size(44.dp).clip(NB.ChipShape).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 22.sp) }
        Spacer(Modifier.width(12.dp))
        Column { Text(title, color = NB.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(3.dp)); Text(body, color = NB.Sub, fontSize = 13.sp, lineHeight = 19.sp) }
    }
}
