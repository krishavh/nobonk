package ai.genwhy.nobonk.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import ai.genwhy.nobonk.BuildConfig
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/** A reviewable email draft. No network permission, automatic upload, photos or history access. */
@Composable
internal fun FeedbackDialog(settingsSummary: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var topic by rememberSaveable { mutableStateOf("Too many alerts") }
    var message by rememberSaveable { mutableStateOf("") }
    var includeDetails by rememberSaveable { mutableStateOf(true) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    val details = "NoBonk ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n$settingsSummary"
    val body = "$topic\n\n$message" + if (includeDetails) "\n\n$details" else ""
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Help improve NoBonk") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Scanning is stopped while you write. Your email app opens a draft for you to review and send.")
                for (option in listOf("Too many alerts", "Missed a person or object", "Background or controls", "Something else")) {
                    FilterChip(selected = topic == option, onClick = { topic = option }, label = { Text(option) })
                }
                OutlinedTextField(value = message, onValueChange = { message = it.take(4000) },
                    label = { Text("What happened?") }, placeholder = { Text("Where was the camera pointing? What did you expect?") },
                    modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)
                Row {
                    Checkbox(checked = includeDetails, onCheckedChange = { includeDetails = it })
                    Text("Include app, phone and settings details", Modifier.padding(top = 12.dp))
                }
                if (includeDetails) Text(details, style = MaterialTheme.typography.bodySmall)
                Text("No camera images, detection history or location are attached. Send to support@nobonk.com.", style = MaterialTheme.typography.bodySmall)
                notice?.let { Text(it) }
                TextButton(onClick = { clipboard.setText(AnnotatedString(body)); notice = "Copied. You can paste this into an email or message." }) { Text("Copy feedback") }
            }
        },
        confirmButton = {
            TextButton(enabled = message.isNotBlank(), onClick = {
                val uri = Uri.parse("mailto:support@nobonk.com?subject=" + Uri.encode("NoBonk feedback: $topic") + "&body=" + Uri.encode(body))
                try { context.startActivity(Intent(Intent.ACTION_SENDTO, uri)) }
                catch (_: android.content.ActivityNotFoundException) { notice = "No email app found. Use Copy feedback instead." }
            }) { Text("Open email draft") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
