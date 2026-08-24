package com.persondetection.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * In-app open-source licenses / attribution screen (T-DOCS-LICENSES).
 *
 * Also satisfies the AGPL-3.0 §13 obligation for the bundled Ultralytics YOLO model by
 * surfacing a link to the corresponding source. Reachable from the History screen footer.
 */

private val LicBg      = Color(0xFF0A0E1A)
private val LicCard    = Color(0xFF141828)
private val LicCyan    = Color(0xFF00E5FF)
private val LicText    = Color(0xFFEEEEEE)
private val LicSub     = Color(0xFF9E9E9E)

private data class Lib(
    val name: String,
    val license: String,
    val note: String
)

private val LIBRARIES = listOf(
    Lib(
        "ONNX Runtime",
        "MIT License",
        "Microsoft. On-device neural-network inference engine used to run the YOLO detector."
    ),
    Lib(
        "AndroidX CameraX",
        "Apache License 2.0",
        "Google / Android Open Source Project. Camera capture + analysis pipeline."
    ),
    Lib(
        "Ultralytics YOLO",
        "AGPL-3.0 License",
        "The bundled object-detection model weights and export tooling are licensed under " +
            "the GNU Affero General Public License v3.0. Per AGPL §13, the complete " +
            "corresponding source for this app — including the exact model / export recipe — " +
            "is published at the link below."
    ),
    Lib(
        "AndroidX Security (Jetpack)",
        "Apache License 2.0",
        "Keystore-backed encryption used to protect on-device detection history at rest."
    ),
    Lib(
        "Jetpack Compose & Kotlin Coroutines",
        "Apache License 2.0",
        "JetBrains / Google. UI toolkit and asynchronous processing."
    )
)

private const val SOURCE_URL = "https://github.com/krishavh/nobonk"

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LicBg)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 44.dp, start = 8.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back to history", tint = LicCyan)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "OPEN-SOURCE LICENSES",
                color = LicText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
        }

        Text(
            "NoBonk is built on open-source software. The components below are used under " +
                "their respective licenses.",
            color = LicSub,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        LIBRARIES.forEach { lib -> LicenseCard(lib) }

        // Source availability (AGPL §13)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(LicCard)
                .border(1.dp, LicCyan.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text(
                "SOURCE CODE",
                color = LicCyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "The complete corresponding source for this app and the detection model is " +
                    "available at:",
                color = LicSub,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                SOURCE_URL,
                color = LicCyan,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "NoBonk is an assistive aid, not a certified safety device. Always keep looking up.",
            color = LicSub,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun LicenseCard(lib: Lib) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(LicCard)
            .padding(16.dp)
    ) {
        Text(lib.name, color = LicText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(lib.license, color = LicCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(lib.note, color = LicSub, fontSize = 13.sp, lineHeight = 18.sp)
    }
}
