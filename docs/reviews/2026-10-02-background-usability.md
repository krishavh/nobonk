# Background usability candidate — October 2, 2026

Version 1.0.22 (24) is a development candidate, not a claim of Google Play availability.

## Changes and rationale

- Sound, vibration and voice share a cadence gate. A stable high-priority hazard repeats at six seconds; medium at ten and low at fifteen. Escalation and a genuinely new high-priority track can prompt sooner. Visual detection boxes remain live. This reduces repeated cues, not model classification errors.
- The return-to-app control can be dragged and remembers a normalized position. It is clamped away from system insets and below the keyboard. Users can turn off the decorative edge trail without disabling detection or warning text.
- Camera availability is separate from service lifetime. Another app taking the camera immediately invalidates in-flight results, clears stale hazards and pauses the scanning indicator. Recovery requires new frames. Stop is terminal for that session; obsolete frame errors cannot stop a subsequently recovered camera.
- Setup explains camera orientation, People versus Everything, approximate distance, NoBonk's own overlay permission, and notification availability. Sensor elevation correction and its limits are documented separately.
- Feedback is an optional, reviewable email draft with selectable categories and optional phone/app/settings details. No images, location or detection history are attached. Copy is available when an email client is unavailable.

## Android limits

NoBonk cannot guarantee an overlay above every application. Android and protected apps can hide overlays, and system windows have their own layering. The overlay permission applies to NoBonk; users do not need to authorize every app they browse. The small return control is touchable; decorative edge strips do not capture the whole screen.

Background camera access starts through a visible, user-initiated flow. A walking reminder must ask once and wait for Start, rather than silently opening the camera. If notifications are denied, the notification drawer cannot be promised as a return path. Camera use by another application can interrupt scanning; an interruption must never be presented as continued detection.

References:

- https://developer.android.com/reference/android/view/WindowManager.LayoutParams
- https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility
- https://developer.android.com/security/fraud-prevention/activities
- https://developer.android.com/develop/ui/compose/notifications/notification-permission
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/reference/androidx/camera/core/CameraState

## Verification and remaining release work

At commit a6dad2a, 286 JVM tests and debug lint passed. Both Android 10 (API 29) and Android 16 (API 36.1) full instrumentation runs passed 29 tests, with only the explicitly optional power experiment skipped. These runs include real native-model output parity for both models, model replacement/Stop, camera competition/recovery using a real competing Camera2 client, keyboard layering with a focused text editor, draggable overlays, and feedback at 2× text size. Later Claude integration changes are undergoing a fresh full run; those earlier results do not certify the combined candidate.

The first Android 10 run exposed a 38 MB Java allocation on its 48 MB heap while reading a model asset. Production had the same loading pattern. Models now stream through a 64 KiB buffer into private, hash-verified, atomically installed files; ORT loads by file path. Valid cache hits do not rewrite the file. Eight unit tests cover bounded allocation, exact identity, concurrent loads, corruption repair, cancellation and failed extraction. The previously failing native-model test passed afterward on both API levels.

Emulator tests cannot establish outdoor false-positive rates, real haptic comfort, physical camera angles or device-specific battery behavior. The a6dad2a release artifact was inspected and locally signed; the combined candidate must be rebuilt and reverified.

## Claude review integration

Claude's October 2 branch was based on the older main checkout, so changes were reviewed individually. Integrated decoder validation, reused frame-transform objects, notification channel wording and the scrollable camera-permission screen. Retained the already verified bounded-allocation model cache rather than replacing it with the competing loader.

The notification return intent now reuses the existing Activity and has an identity distinct from the walking reminder, preventing reminder extras from leaking into ordinary return taps. Pending service-start callbacks use weak Activity references, cancellation, generation checks and a timeout. Rotation cannot allow an obsolete callback to dismiss a newer screen. Manual start errors remain visible.

Rejected the proposed unconditional camera foreground-service promotion on refused starts: walking sessions use a different service type, and camera permission may be missing. Instead, the visible Activity starts the service normally and waits for validation and promotion of the correct type before moving to the background. Failed admission acknowledges rejection and cleans up without leaving an outstanding foreground-start deadline. Stop and failed/obsolete acknowledgements cannot restart detection. New device tests cover refused admission, real notification return and callback recreation.

Decoder checks reject non-finite geometry, invalid confidence and fractional/out-of-range class IDs before clamping. A malformed class value must not become a Person detection. Valid model-output parity remains part of the device suite.

The real MainActivity background/notification/Stop test found an additional Android 10 lifecycle race: after notification Stop, a retained foreground CameraX binding reopened at ON_START before onResume consumed the stopped state. Camera logs showed OPENING and OPEN before the next UI frame released it. CameraPreview now releases only its owned use cases at ON_STOP and prevents late binds; the existing resume key creates a fresh preview only after the app has checked Stop and the safety gate. New preview bindings also require RESUMED, covering compositions created while already stopped. The previously failing Android 10 end-to-end test passed with this fix. It verifies live manual handoff, camera release on Stop, return through the safety reminder without camera activation, and stopped state across real Activity recreation.

After these changes, 298 JVM tests and debug lint passed. The final full Android 10 and Android 16 suites each passed 36 tests with one optional power-experiment skip (37 XML test cases). Raw reports are retained in the local release handoff under background-usability-20261002/final-api29 and final-api36. The test sends the real posted notification PendingIntent with test-only SystemUI-equivalent launch privilege; it does not add background-launch privileges to the production app. Physical-phone acceptance remains outstanding.

Before release: inspect the release artifact, test on a physical phone, and resolve the existing Play walking-service declaration evidence requirement. The previous closed-test release and Google production-access application are separate from this candidate. The support@nobonk.com routing configuration exists, but end-to-end delivery still needs verification.

## Private real-scene replay

An opt-in replay passed on API 29 and API 36.1 using four retained pavement crops and the official Ultralytics bus/person control. Each run exercises both shipped models, both scopes and four temporal frames: 20 cases / 80 frames. API 36 results record zero detections and no hazard alerts for these pavement crops; the positive control retains four people in People mode and five objects in Everything mode. People mode does not produce the bus hazard shown in Everything mode. This is a narrow regression check, not an outdoor accuracy estimate: repeated compressed still images do not reproduce movement, sensors, camera placement or real cue comfort. Private fixtures are SHA-verified, staged only on isolated emulators and excluded from the repository and app bundle. The test is skipped by default.

Evidence is retained in the release handoff: background-private-replay-api29.log, background-private-replay-api36-verified-input.log, and background-usability-20261002/private-replay-results.json.

## Missing-frame cooldown correction

A subsequent focused review found that the cadence gate treated any three-second processing gap as a clear scene. Slow inference or interrupted frame delivery could therefore rearm a repeated alert without observing that the hazard had disappeared. The gate now preserves the emission history through missing/suppressed frames; rearming requires usable NONE observations spanning three seconds without a three-second observation gap. Long gaps also suspend the faster new-track exception until another cue establishes continuity. Blocked-camera early returns explicitly interrupt clear-scene evidence on the cue thread, and warmup frames cannot contribute usable NONE observations. Severity escalation and the existing 1.5-second new-person exception otherwise remain unchanged. Focused tests cover exact boundaries, changed tracker IDs, suppression, recovery and monotonic-clock rollback. Earlier full-device and optimized-APK evidence applies to the prior candidate; this isolated policy correction is validated by the updated unit suite and rebuilt artifact.

The final observation integration passed 304 JVM tests, debug lint and all three targeted API36 device tests (camera interruption/recovery and native model processing). Independent review found no blocker in the policy or main-thread integration. Full UI suites and optimized-APK smoke remain recorded against the earlier combined candidate; they were not rerun after this isolated correction.
