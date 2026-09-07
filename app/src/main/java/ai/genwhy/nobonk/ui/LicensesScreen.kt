package ai.genwhy.nobonk.ui

import ai.genwhy.nobonk.BuildConfig
import ai.genwhy.nobonk.ui.components.SectionLabel
import ai.genwhy.nobonk.ui.theme.NB
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Lib(val name: String, val license: String, val note: String)

private val LIBRARIES = listOf(
    Lib("Ultralytics YOLO26", "AGPL-3.0", "Detector weights (yolo26n, yolo26s) and export tooling. Per AGPL §13 the complete corresponding source — including the exact export recipe — is published in the repository (docs/MODEL.md)."),
    Lib("ONNX Runtime", "MIT", "Microsoft. Runs the detector on the phone's CPU/NPU."),
    Lib("AndroidX CameraX", "Apache-2.0", "Camera preview and the RGBA analysis stream."),
    Lib("Jetpack Compose, Material 3, Kotlin coroutines", "Apache-2.0", "UI toolkit and async processing (Google / JetBrains)."),
    Lib("AndroidX Security", "Apache-2.0", "Keystore-backed encryption of the on-device history."),
    Lib("desugar_jdk_libs", "GPL-2.0 with Classpath Exception", "java.time on older Android versions."),
)

private const val SOURCE_URL = "github.com/krishavh/nobonk"
private const val PRIVACY_URL = "krishavh.github.io/privacy/nobonk.html"

/** About, credits and open-source licenses. Reached from History → "About". */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(NB.Night).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = NB.Accent) }
            Text("About NoBonk", color = NB.Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(14.dp))
        Wordmark(size = 56)
        Spacer(Modifier.height(8.dp))
        Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · offline · no accounts · no ads", color = NB.Sub, fontSize = 13.sp)

        Spacer(Modifier.height(24.dp))
        SectionLabel("Made by")
        Spacer(Modifier.height(8.dp))
        Card {
            Text("Krishav", color = NB.Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Student · Dublin, California. The problem, the design, the false-alert tuning and every sidewalk test.", color = NB.Sub, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(10.dp))
            Text("Haarith", color = NB.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Parent and Play account holder.", color = NB.Sub, fontSize = 14.sp)
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel("Built with help from")
        Spacer(Modifier.height(8.dp))
        Card {
            Credit("Claude (Anthropic)", "Most of the Kotlin, the release engineering, the zero-allocation frame path, model export and benchmarks — via Claude Code.")
            Credit("OpenAI Codex & ChatGPT Astra", "Refactoring, Play Console and registration workflow, website.")
            Credit("Kaaval", "The family's local Hermes agent on a DGX Spark (Qwen3.8-Flash-Next): hundreds of autonomous build-and-test iterations.")
            Credit("Google Gemini · Warp AI", "Debugging, security review, alert-system diagram; terminal workflow.")
            Text("AI tools wrote a lot of code here. The ideas, the decisions and the testing were Krishav's — and it says so honestly, because that's the point of a student project.", color = NB.Dim, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp))
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel("Open-source licenses")
        Spacer(Modifier.height(8.dp))
        LIBRARIES.forEach { lib ->
            Card(modifier = Modifier.padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(lib.name, color = NB.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(lib.license, color = NB.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(4.dp))
                Text(lib.note, color = NB.Sub, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }

        Spacer(Modifier.height(10.dp))
        SectionLabel("Source & privacy")
        Spacer(Modifier.height(8.dp))
        Card {
            Text("NoBonk is itself AGPL-3.0. Source, model recipe and issue tracker:", color = NB.Sub, fontSize = 13.sp)
            Text(SOURCE_URL, color = NB.Accent, fontSize = 14.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 4.dp))
            Text("Privacy policy:", color = NB.Sub, fontSize = 13.sp)
            Text(PRIVACY_URL, color = NB.Accent, fontSize = 14.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(36.dp))
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(NB.CardShape).background(NB.Surface).border(1.dp, NB.Line, NB.CardShape).padding(14.dp), content = content)
}

@Composable
private fun Credit(who: String, what: String) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(who, color = NB.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(what, color = NB.Sub, fontSize = 13.sp, lineHeight = 18.sp)
    }
}
