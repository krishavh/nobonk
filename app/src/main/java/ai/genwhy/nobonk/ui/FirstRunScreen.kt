package ai.genwhy.nobonk.ui

import ai.genwhy.nobonk.ui.components.SectionLabel
import ai.genwhy.nobonk.ui.theme.NB
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * First-run: what NoBonk does, exactly what it will and won't do with the camera, and the
 * safety notice — before any permission prompt. Plain language for a 12-year-old and a parent.
 */
@Composable
fun FirstRunScreen(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B1220), NB.Night)))
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 22.dp)
    ) {
        Spacer(Modifier.height(36.dp))
        Wordmark(size = 60)
        Spacer(Modifier.height(26.dp))
        Text("Your phone can see\nwhat you're about to walk into.", color = NB.Ink, fontSize = 28.sp, lineHeight = 33.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
        Spacer(Modifier.height(10.dp))
        Text("NoBonk watches the path ahead through the back camera and taps you on the shoulder — vibration, sound, and a big LOOK UP — before you bump into someone.", color = NB.Sub, fontSize = 16.sp, lineHeight = 23.sp)
        Spacer(Modifier.height(26.dp))
        SectionLabel("How it works")
        Spacer(Modifier.height(10.dp))
        ValueCard("👀", "Sees people, bikes, cars and pets", "An on-device vision model (YOLO26) looks at every frame and estimates how close things are and whether they're closing in.", NB.Accent)
        ValueCard("🔒", "Nothing leaves the phone", "No photos, no video, no uploads, no internet permission. Frames live in memory for a few milliseconds and are gone.", NB.Safe)
        ValueCard("🔔", "Works over other apps", "With the overlay permission, the LOOK UP warning appears on top of whatever you're reading. A notification shows it's running.", NB.Accent2)
        ValueCard("🎧", "Hear which side", "Alert sounds are panned toward the hazard — with earbuds, left means left. Vibration, sound and an optional spoken warning can each be switched off.", NB.Watch)
        ValueCard("📍", "Location is optional, off by default", "Turn it on later if you want a map of your close calls. Coarse only, stays on the device, deletable any time.", NB.Sub)
        Spacer(Modifier.height(14.dp))
        Column(
            modifier = Modifier.fillMaxWidth().clip(NB.CardShape).background(NB.Watch.copy(alpha = 0.10f)).border(1.5.dp, NB.Watch.copy(alpha = 0.7f), NB.CardShape).padding(16.dp)
                .semantics { contentDescription = "Safety notice: NoBonk is an assistive backup, not a certified safety device. It can miss hazards. Keep looking up and stay aware of your surroundings." }
        ) {
            Text("⚠️  NOT A SAFETY DEVICE", color = NB.Watch, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
            Spacer(Modifier.height(8.dp))
            Text("NoBonk is a backup, not a guarantee. It can miss people, cars, glass, poles, curbs and drop-offs, and it works worse in the dark or at a bad angle. Keep looking up.", color = NB.Ink, fontSize = 15.sp, lineHeight = 21.sp)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onContinue, modifier = Modifier.fillMaxWidth().height(56.dp), shape = NB.ChipShape,
            colors = ButtonDefaults.buttonColors(containerColor = NB.Safe, contentColor = Color(0xFF04140D))
        ) { Text("I understand — let's go", fontSize = 17.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(10.dp))
        Text("You'll be asked for camera access next.", color = NB.Dim, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ValueCard(emoji: String, title: String, body: String, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(NB.CardShape).background(NB.Surface).border(1.dp, NB.Line, NB.CardShape).padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.size(40.dp).clip(NB.ChipShape).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 20.sp) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = NB.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(body, color = NB.Sub, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}
