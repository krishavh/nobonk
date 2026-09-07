# Android runtime review — 2026-09-07

Base: `35c528272650bb751c041f46ad9c9de03f3a3d53` (version 1.0.12 / code 13).
This review branch deliberately does not change the version or publish/sign a release.

## Changes supported by the audit

- **Reuse native tensor storage.** Each prepared model owns one direct input tensor and,
  for the shipped static output shape, one direct output tensor. Provider verification and
  timing use this same path. Decoding reads the output buffer while its owner is alive.
  Dynamic output shapes keep a copying fallback. Model replacement/disposal still goes
  through the existing engine ownership lock or service single-flight gate.
- **Keep battery state current.** A lifecycle-owned system battery receiver replaces the
  foreground launch-only reading and the background query on every incoming camera frame.
  The existing foreground pause below 10% and background cadence policy remain distinct.
  A power pause invalidates in-flight foreground results, removes old alerts, releases the
  foreground camera and stops its sensors. Charging resumes only a user-enabled session.
- **Scope foreground work to the resumed detection screen.** The setup screen releases
  its sensor/cue ownership when hidden, in History/About, or during a background handoff.
  Returning preserves a user Stop. Model completion and battery recovery respect that owner.
- **Accept service startup once.** Repeated start commands cannot allocate another model
  or overwrite the active session's settings. Late failures after Stop cannot write a stale
  background-failure message. Model/camera callbacks require their expected lifecycle phase.
- **Use monotonic runtime timing.** Frame admission, cue cooldowns, approach tracking,
  stationary detection, FPS and history debouncing use elapsed time. Event timestamps and
  persisted provider-cache age retain wall-clock time. Network/manual clock adjustments
  therefore cannot freeze scanning until the old wall time catches up.
- **Publish sensor state safely.** Pitch is volatile; motion-state access is synchronized.
  Stopping sensors clears the old stationary estimate before the next session.

## Measurements and validation

Actual Android library: ONNX Runtime **1.29.0**. API 36.1 arm64 emulator,
`google/sdk_gphone64_arm64/emu64a:16/BE4B.251210.005/14574095:user/release-keys`,
2 virtual CPU cores, 2 GB configured RAM. Host was also doing iOS development.
These results are **not physical-phone FPS, power or thermal measurements**.

Both packaged model files were checked before testing:

| Model | SHA-256 |
|---|---|
| Fast, `yolo26n_416.onnx` | `9931a595afd6c780bdbe5ef12e99d34526b6f30dbc7641cf920572798e4ed96d` |
| Sharp, `yolo26s_416.onnx` | `706b34041df74890c4d5720b26e47ced3d825a3b7dd1bad6cf73fcdcb7740356` |

The raw output is `[1,84,3549]`: **298,116 floats / 1,192,464 payload bytes**.
The old `getValue()` path creates 86 nested array objects to carry it. The new static path
avoids that result-sized copy and those arrays. It still allocates small result/decoding
objects and may require device-to-host transfer inside a hardware execution provider.

The native test runs CPU and XNNPACK against both real models. Three different synthetic
input frames compare every output element (absolute + relative tolerance `1e-5`), then
compare decoded class/box/confidence and class-aware NMS results. It verifies reusable
output identity, repeated result disposal, idempotent runner close, rejection after close,
and that closing the runner leaves the caller-owned session usable. The COCO fixture tests
exercise the actual flat decoder with meaningful class scores, cats/dogs, horse exclusion,
thresholds, portrait letterboxing, and transposed layout.

One repeated ABBA run (20 inferences per phase) produced:

| Model / provider | Old phase medians, ms | Pinned phase medians, ms |
|---|---:|---:|
| Fast / CPU | 23.32, 21.35 | 20.02, 19.65 |
| Fast / XNNPACK | 20.94, 20.81 | 20.60, 20.63 |
| Sharp / CPU | 62.65, 60.18 | 60.08, 59.40 |
| Sharp / XNNPACK | 66.35, 66.73 | 66.63, 66.68 |

Across two runs, ART's process-wide allocation counter reported approximately
1.12–1.40 MB/frame on the array path and below counter resolution to 4.9 KB/frame on the
pinned path. Counter granularity and unrelated runtime allocations prevent claiming
zero allocation or treating the counter as exact per-thread accounting. The exact removed
float payload above follows directly from the model shape and library implementation.

Startup previously allocated a 2,076,672-byte heap input plus a direct copy for **each**
probe inference. Prepared candidates now reuse one direct input/output pair through warmup,
timing and adoption. Candidate models are still created sequentially; there is no claim that
this eliminates model graph initialization or hardware-driver compilation.

Battery instrumentation uses actual system broadcasts and the real ViewModel/model load:
50% → 9% pause → 50% recovery while hidden → explicit Stop → 8% → 60%. It checks user intent,
cleared results, and no receiver updates after cleanup. It resets the emulator battery override
in `finally`. Pure session tests cover old-frame rejection across pause/recovery/handoff and
Stop; duplicate-start tests cover loading, binding, running and stopped phases.

Validation at review: **151 JVM tests passed; lint 0 errors / 57 existing warnings**;
debug app, instrumentation APK and unsigned release AAB build successfully. Native parity
and battery integration passed. Final source review and release version assignment are separate.

## Experiments and remaining limits

- **Thread spinning:** an opt-in Android-native experiment compared default, disabled and
  1 ms bounded/backoff policies at a nominal 10 Hz, forward and reverse order. Process CPU
  time and inference latency varied substantially under shared-host load; conclusions were
  inconsistent. No production thread-policy change is justified by these measurements.
  `OrtPowerExperimentTest` is skipped unless `runPowerExperiment=true` is supplied.
- **Thermal adaptation:** Android offers status/headroom APIs, but old-device support varies,
  and a NONE status alone is insufficient evidence of no throttling. Do not add frame-loop
  headroom polling. A future implementation needs visible degraded-state messaging and
  physical-device response/temperature tests before reducing hazard-detection cadence.
- **Baseline profiles:** the AAB already contains merged library profiles. An app-specific
  profile should record notice → setup → first frame → background/Open → Stop/History flows
  in a non-obfuscated profile-generation variant, then be measured on a minified release.
  ART profiles do not precompile the native ONNX graph/driver, and documentation's general
  speedups are not a measured NoBonk startup improvement.
- **Camera work:** keep latest-frame backpressure, crop/rotation mapping, and single-flight
  ownership. Changing RGBA/YUV handling, resolution or scene thresholds needs real camera
  fixtures and box/color alignment checks; no unsupported conversion or quality change is
  bundled here. Android/CameraX documentation's byte-order descriptions should be checked
  against the concrete library and device before modifying raw-pixel handling.
- **Hardware selection:** CPU/XNNPACK can beat a partially supported accelerator graph.
  A verified NNAPI session is not proof every operation runs on an NPU/GPU. NNAPI is deprecated
  on Android 15; migrating the model/runtime is a separate parity/per-device performance project.
- **Still needed:** Pixel-class and older low-memory phone tests for cold/warm startup,
  Fast/Sharp sustained inference, physical camera handoff/Stop, thermal behavior and battery
  energy. Also review foreground/background retaining separate prepared models before any
  shared-model ownership redesign. No image asset change was warranted by this runtime work.

## Claim provenance

All sources below were checked **2026-09-07**. Confidence describes the documented API or
observed implementation, not a prediction of speed on an untested phone.

| Claim | Primary source | Confidence / contradiction / gap |
|---|---|---|
| `getValue()` copies to nested arrays; `getFloatBuffer()` also copies | [ORT 1.29.0 OnnxTensor source](https://raw.githubusercontent.com/microsoft/onnxruntime/v1.29.0/java/src/main/java/ai/onnxruntime/OnnxTensor.java) | High; flat extraction alone is not zero-copy. |
| Pinned outputs are caller-owned and survive Result.close | [ORT Java OrtSession API](https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtSession.html) | High; additionally verified against packaged 1.29.0 native code. |
| Direct input buffers can back reusable tensors | [ORT OnnxTensor API](https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OnnxTensor.html) | High; writes/run/close must share one owner; output transfer inside EP remains possible. |
| Battery level arrives through a sticky system broadcast | [Android battery monitoring](https://developer.android.com/training/monitoring-device-state/battery-monitoring) | High; receiver cleanup verified on emulator, OEM behavior remains a device check. |
| Wall time may jump; elapsedRealtime is monotonic including sleep | [Android SystemClock](https://developer.android.com/reference/android/os/SystemClock) | High; event date/time intentionally uses a different clock. |
| Spinning trades CPU/power for latency | [ORT thread management](https://onnxruntime.ai/docs/performance/tune-performance/threading.html) | High for mechanism; experiment insufficient to choose a phone policy. |
| XNNPACK owns a separate pool; configurations require model-specific testing | [ORT XNNPACK provider](https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html) | High; existing ORT=1/XNNPACK=4 setup retained; emulator 2-thread experiment is not a tuning recommendation. |
| Thermal APIs have support/polling limits | [Android Thermal API](https://developer.android.com/games/optimize/adpf/thermal) | High; no physical thermal/energy measurement in this review. |
| Baseline profiles optimize ART paths | [Android Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview) | High; no measured app-specific profile improvement yet. |
| CameraX conversion/backpressure/closure are explicit obligations | [CameraX image analysis](https://developer.android.com/media/camera/camerax/analyze) | High; retaining tested geometry rather than inferring new pixel ordering. |
| Accelerator partitioning can reduce performance | [ORT mobile guidance](https://onnxruntime.ai/docs/tutorials/mobile/) | High; no hardware-provider claim beyond actual successful inference and timed choice. |
| NNAPI deprecated starting Android 15 | [Android NNAPI migration](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide) | High; does not mean existing NNAPI devices stop working. |

## Reproduce

With the two verified assets installed locally and the Android SDK/JDK configured:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew bundleRelease -Pbundle
adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w ai.genwhy.nobonk.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5580 shell am instrument -w -e class ai.genwhy.nobonk.ml.OrtPowerExperimentTest -e runPowerExperiment true ai.genwhy.nobonk.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5580 logcat -d -s NoBonkBufferBench:I NoBonkPowerBench:I '*:S'
```

Battery-override instrumentation self-restricts to an emulator and must not be repurposed
to alter a user's phone battery state. The measurement logs contain synthetic model work.
