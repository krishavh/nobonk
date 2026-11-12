# NoBonk 1.0.24 (27) audit

This candidate starts from the public 1.0.23 (25) source, with the prepared offline video guide and support@nobonk.com links. Codex reviewed the code with Claude Fable 5.2 at high effort. Findings were checked against the implementation and reproduced where practical. A model review is not a guarantee of correctness.

## Changes

- A person clipped by the top or bottom of the frame must grow in width as well as height before height growth can drive approach escalation. Revealing more of a stationary person by tilting the phone must not alone produce an imminent-approach warning. Normal size-based warnings remain in place.
- A short camera or angle interruption no longer makes a returning track count as a new person for the faster repeat cue. Severity escalation remains available. Visual detection continues independently of audible/haptic repeat limits.
- Covered-lens checks use brightness and variance from unboosted pixels. The variance threshold is 6.4, preserving the previous boosted steady-state boundary. Dim, textured scenes remain usable. Night boost still reaches the model.
- History accepts all 80 labels emitted by the shipped model and the legacy object label. Previously, valid Everything-mode records for chairs, benches and many other classes were skipped after reopening the app. Unknown labels still fail validation. Encryption and local storage are unchanged.
- Builds check the presence and exact SHA-256 of both model assets. Missing or changed weights stop the build.
- The video guide works offline, starts only after Play is tapped, and stops scanning before it opens.

The public app still requires an explicit Start. Walking reminders remain disabled. People confirmation, detection boxes, red background edge, launch reminder and Stop behavior are retained. No new public permission is added.

## Evidence recorded November 11

- 312 JVM tests passed with no failures or skips.
- 25 Android instrumented tests passed on an isolated API 36.1 emulator. They cover actual model loading, People-only filtering, low-light processing, history round trips through encrypted storage, camera competition/recovery, explicit background admission, colored overlays, controls, feedback and the video guide.
- New history tests failed on the original allowlist and passed after the fix. Initial approach/cadence regressions and repeated-dark-frame processing also reproduced the original failures before their fixes.
- The model files match the installed Google Play 1.0.23 APK byte for byte. Fast SHA-256: `9931a595afd6c780bdbe5ef12e99d34526b6f30dbc7641cf920572798e4ed96d`. Sharp SHA-256: `706b34041df74890c4d5720b26e47ced3d825a3b7dd1bad6cf73fcdcb7740356`.
- The user accepted the optimized private Pixel 9a preview after being asked to check People mode, background scanning, return, Stop and a stationary-person tilt check. The later history-only fix is covered by the Android tests above.
- The public merged manifest check passed: private camera service, no Internet, walking-recognition or health-service permission, and no debuggable release flag.
- An additional 3 Android tests passed for startup cancellation/model replacement, native ONNX buffer parity and rejected unacknowledged service starts. Private still-scene replay passed 20 cases (80 frames) across both models and scopes. The fixtures remain local and are not included in source uploads.
- CI now runs the added low-light and history tests, camera-interruption and manual-admission checks, and the offline guide tests.

## Limits and follow-ups

These are functional and regression checks, not an outdoor accuracy, battery or collision-prevention study. The clipped-person guard applies within 1% of the top or bottom edge; detections farther inside the image still need real-world evaluation. A different person arriving immediately after a camera interruption may wait for the normal repeat interval unless severity rises.

Existing non-blocking findings are retained for a later change: history samples one event per class in a short interval and can undercount higher-severity events in a crowd; optional location state/freshness can be clearer; an update-card tap during a Play refresh can do nothing; old cached model files could be pruned. These are not described as fixed by this release.

A source commit is not evidence that Google Play has approved or delivered the update. Signing, upload, review and rollout are recorded separately in the release handoff.
