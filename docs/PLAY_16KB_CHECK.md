# 16 KB Page-Size Verification (T-REL-16K)

**Why this matters:** Google Play requires that any app shipping native code and
targeting **Android 15+** support **16 KB memory pages** for all new app
submissions and updates from **November 1, 2025**. Newer devices (and the Play
"16 KB" device tier) run with 16 KB pages; a native library aligned only to the
old 4 KB boundary fails to load, crashing the app on those devices. Play blocks
such uploads at review.

NoBonk bundles native `.so` libraries from:
- **ONNX Runtime** (`onnxruntime-android`) — the ML inference engine, and
- **CameraX** (camera-core / camera2) — CameraX itself is mostly Java/Kotlin but
  its transitive deps can carry native code.

So this check is mandatory before every production upload.

---

## What the build already does

`app/build.gradle.kts` sets:

```kotlin
packaging {
    jniLibs {
        useLegacyPackaging = false   // uncompressed + page-aligned in the AAB/APK
    }
}
```

- `useLegacyPackaging = false` (the modern default) keeps `.so` files
  **uncompressed and page-aligned** inside the artifact.
- We do **not** set `android:extractNativeLibs="true"` and do **not** set
  `useLegacyPackaging = true` — either would reintroduce the old 4 KB behavior.
- AGP 8.5.1+ with NDK r27+ produces 16 KB-aligned segments by default; our
  toolchain (AGP 9.0.1) does. **But alignment ultimately depends on how each
  prebuilt dependency was compiled**, which is exactly why we verify the built
  artifact rather than trusting the config.

---

## How to verify (two ways)

### Option A — the bundled helper script (fastest)

```bash
# 1. Build the release artifact
./gradlew bundleRelease          # or: ./gradlew assembleRelease

# 2. Verify every bundled .so is 16 KB-aligned
scripts/check_16kb_alignment.sh  # auto-finds the newest .aab/.apk
#   or pass an explicit path:
scripts/check_16kb_alignment.sh app/build/outputs/bundle/release/app-release.aab
```

The script unzips the artifact, finds every `lib/<abi>/*.so`, and inspects each
one's ELF `LOAD` segment alignment with `llvm-readelf` / `readelf` / `objdump`
(whichever is on `PATH` — the NDK ships `llvm-readelf`). It prints ALIGNED /
UNALIGNED per library and exits non-zero if any library fails.

**Expected output:** every library `ALIGNED`, `RESULT: PASS`.

### Option B — Google's official script

Google publishes `check_elf_alignment.sh`
(https://developer.android.com/guide/practices/page-sizes#alignment-use-script).
Download it and run it against the same artifact; it reports each `.so` as
`ALIGNED (16 KB)` or `UNALIGNED`.

### Option C — manual spot check

```bash
unzip -o app-release.aab -d /tmp/nobonk_aab
# For any .so, LOAD segments must be aligned to 2**14 (16384 = 0x4000):
$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/*/bin/llvm-readelf -l \
    /tmp/nobonk_aab/base/lib/arm64-v8a/libonnxruntime.so | grep LOAD
# The Align column must read 0x4000 (or larger), never 0x1000.
```

---

## If a library FAILS

1. **Update the offending dependency to a 16 KB-ready release.** ONNX Runtime has
   shipped 16 KB-aligned Android libraries since **1.20.0**; NoBonk already pins
   `onnxruntime = 1.21.1`, which is compliant. If a check ever fails, bump to the
   latest `1.2x` in `gradle/libs.versions.toml`.
2. **Re-confirm packaging flags:** `jniLibs.useLegacyPackaging = false`, no
   `extractNativeLibs=true`.
3. Rebuild and re-run the script until `RESULT: PASS`.
4. Only a library we cannot update (none currently) would require rebuilding it
   from source with NDK r27+ and `-Wl,-z,max-page-size=16384`.

---

## Record of last verification

> Fill this in when you run the check on a machine with the Android SDK/NDK.

| Date | Artifact | ONNX RT | Result | Run by |
|------|----------|---------|--------|--------|
| _pending_ | app-release.aab | 1.21.1 | _run `scripts/check_16kb_alignment.sh`_ | _guardian/dev_ |

> **Note:** This check could not be executed in the release-engineering sandbox
> (no JDK/Android SDK installed there). It must be run once on the build machine
> before the first production upload, and the row above filled in.
