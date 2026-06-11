# PersonDetectionAndroid ProGuard / R8 rules
# Keep these rules conservative: when in doubt, keep the class.
# Run a release build and verify the app works before tightening anything.

# ── ONNX Runtime ─────────────────────────────────────────────────────────────
# ONNX Runtime uses JNI internally and accesses Java classes by name from C++.
# Removing any of these classes causes a crash at inference time.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ── Android entry points (Activities, Services, BroadcastReceivers) ──────────
# R8 keeps @Keep-annotated and manifest-declared entry points by default, but
# being explicit avoids surprises.
-keep class com.persondetection.android.MainActivity { *; }
-keep class com.persondetection.android.service.DetectionService { *; }

# ── Data classes used with JSON serialisation ─────────────────────────────────
# DetectionEvent and SessionSummary are serialised/deserialised manually via
# org.json.JSONObject — no reflection, but the class and field names must
# survive obfuscation because we use them as string keys in toJson/fromJson.
# R8 will obfuscate the class bytecode names but leaves field access intact
# when accessed directly (not via reflection), so these rules are precautionary.
-keepclassmembers class com.persondetection.android.data.DetectionEvent {
    public *;
}
-keepclassmembers class com.persondetection.android.data.SessionSummary {
    public *;
}

# ── Kotlin data classes & sealed classes ─────────────────────────────────────
# Kotlin generates componentN() and copy() methods; keep them so destructuring
# and copy calls in coroutines work correctly.
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ── Jetpack Compose ───────────────────────────────────────────────────────────
# The Compose compiler plugin and R8 together handle Composable functions
# correctly without extra rules — the Compose BOM ships its own consumer rules.
# No additional Compose rules are needed.

# ── CameraX ───────────────────────────────────────────────────────────────────
# CameraX ships its own consumer ProGuard rules via the AAR.
# No additional rules needed.

# ── Coroutines ────────────────────────────────────────────────────────────────
# kotlinx-coroutines ships consumer rules. Keep the debug agent if it somehow
# ends up in the release classpath.
-dontwarn kotlinx.coroutines.debug.*

# ── Android Keystore / Security ───────────────────────────────────────────────
# Used by EncryptedFile (future SEC-04 work). Keep to avoid issues if added.
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ── Suppress known-harmless warnings from transitive dependencies ─────────────
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ── Debugging: uncomment to emit a mapping file for crash de-obfuscation ──────
# Upload the mapping file to Play Console so stack traces are readable.
# -printmapping build/outputs/mapping/release/mapping.txt
