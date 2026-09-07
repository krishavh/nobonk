package ai.genwhy.nobonk.ui

import ai.genwhy.nobonk.ui.theme.NB
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shown after the safety gate when the camera permission is missing or was revoked. Nothing scans here. */
@Composable
fun CameraRequiredScreen(permanentlyDenied: Boolean, onRetry: () -> Unit, onOpenSettings: () -> Unit, onExit: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(NB.Night).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Wordmark(size = 48)
        Spacer(Modifier.height(28.dp))
        Text("Camera access is required", color = NB.Ink, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(12.dp))
        Text(
            "NoBonk watches the path ahead through the rear camera. Without camera access it cannot detect anything, so nothing is running right now. Frames are processed on this phone only and never saved or uploaded.",
            color = NB.Sub, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        if (!permanentlyDenied) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(52.dp), shape = NB.ChipShape,
                colors = ButtonDefaults.buttonColors(containerColor = NB.Safe, contentColor = Color(0xFF04140D))) { Text("Allow camera", fontSize = 16.sp, fontWeight = FontWeight.Black) }
            Spacer(Modifier.height(10.dp))
        }
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().height(52.dp), shape = NB.ChipShape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NB.Accent)) { Text("Open app settings", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Exit NoBonk", color = NB.Sub, fontSize = 15.sp) }
    }
}
