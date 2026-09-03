package ai.genwhy.nobonk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * First-run permission rationale + safety disclaimer (PRODUCT/UX, T-REL recommended).
 *
 * Shown BEFORE any camera / overlay / notification permission is requested, so the user
 * understands why the back camera runs continuously and that nothing is recorded — and
 * sees the "assistive backup, not a certified safety device" disclaimer at runtime, not
 * just in the store listing. Tapping "I understand — continue" dismisses it and triggers
 * the permission flow.
 */

private val FrBg    = Color(0xFF0A0E1A)
private val FrCard  = Color(0xFF141828)
private val FrGreen = Color(0xFF69F0AE)
private val FrAmber = Color(0xFFFFB300)
private val FrText  = Color(0xFFEEEEEE)
private val FrSub   = Color(0xFFB0B7C3)

@Composable
fun FirstRunScreen(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FrBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(56.dp))
        Text(
            "Welcome to NoBonk",
            color = FrText,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "A heads-up assistant for when you're walking and glancing at your phone.",
            color = FrSub,
            fontSize = 16.sp,
            lineHeight = 22.sp
        )

        Spacer(Modifier.height(24.dp))

        RationaleItem(
            emoji = "📷",
            title = "The back camera runs continuously",
            body = "While active, NoBonk watches the view ahead through your back camera and " +
                "warns you when a person or obstacle is close, or closing in fast."
        )
        RationaleItem(
            emoji = "🔒",
            title = "Nothing is recorded",
            body = "No video or photos are ever recorded, saved, or sent anywhere. Frames are " +
                "analyzed in memory on your device and immediately discarded. NoBonk works " +
                "fully offline and has no internet permission."
        )
        RationaleItem(
            emoji = "🔔",
            title = "Overlay & notifications",
            body = "The screen-overlay permission lets NoBonk flash a full-screen \"LOOK UP!\" " +
                "warning over other apps. Notifications keep you aware it's running in the " +
                "background."
        )
        RationaleItem(
            emoji = "📍",
            title = "Location is optional and off by default",
            body = "You can turn on coarse location later, only for the history map. It stays " +
                "on your device."
        )

        Spacer(Modifier.height(8.dp))

        // Prominent safety disclaimer
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(FrCard)
                .border(2.dp, FrAmber.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                .padding(16.dp)
                .semantics {
                    contentDescription = "Safety disclaimer: NoBonk is an assistive backup, " +
                        "not a certified safety device. It can miss hazards. Keep looking up " +
                        "and stay aware of your surroundings."
                }
        ) {
            Text(
                "⚠️  IMPORTANT SAFETY NOTICE",
                color = FrAmber,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "NoBonk is an assistive backup — NOT a certified safety device. It can miss " +
                    "people, cars, glass walls, poles, curbs and drop-offs, and it works less " +
                    "reliably in low light or at a bad angle. Never rely on it to keep you safe: " +
                    "keep looking up and stay aware of your surroundings.",
                color = FrText,
                fontSize = 15.sp,
                lineHeight = 21.sp
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = FrGreen)
        ) {
            Text(
                "I understand — continue",
                color = Color(0xFF072012),
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "You'll be asked for camera access next.",
            color = FrSub,
            fontSize = 13.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun RationaleItem(emoji: String, title: String, body: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(emoji, fontSize = 24.sp, modifier = Modifier.padding(end = 12.dp, top = 2.dp))
        Column {
            Text(title, color = FrText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(body, color = FrSub, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}
