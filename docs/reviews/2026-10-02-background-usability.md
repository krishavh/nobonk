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

286 JVM tests and debug lint passed. Both Android 10 (API 29) and Android 16 (API 36.1) full instrumentation runs passed 29 tests, with only the explicitly optional power experiment skipped. These runs include real native-model output parity for both models, model replacement/Stop, camera competition/recovery using a real competing Camera2 client, keyboard layering with a focused text editor, draggable overlays, and feedback at 2× text size.

The first Android 10 run exposed a 38 MB Java allocation on its 48 MB heap while reading a model asset. Production had the same loading pattern. Models now stream through a 64 KiB buffer into private, hash-verified, atomically installed files; ORT loads by file path. Valid cache hits do not rewrite the file. Eight unit tests cover bounded allocation, exact identity, concurrent loads, corruption repair, cancellation and failed extraction. The previously failing native-model test passed afterward on both API levels.

Emulator tests cannot establish outdoor false-positive rates, real haptic comfort, physical camera angles or device-specific battery behavior. Release-artifact verification is still pending.

Before release: inspect the release artifact, test on a physical phone, and resolve the existing Play walking-service declaration evidence requirement. The previous closed-test release and Google production-access application are separate from this candidate. The support@nobonk.com routing configuration exists, but end-to-end delivery still needs verification.
