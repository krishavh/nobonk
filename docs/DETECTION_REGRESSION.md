# Detection-box regression coverage

## September 2026: recognized objects discarded before rendering

In Everything mode, the raw YOLO decoder selected only eight COCO classes. The
shipped models output 80 classes. Chairs, bottles and potted plants were discarded
even when their scores exceeded the existing confidence threshold. A camera view
could therefore be live while no object box appeared. Generic surface warnings
are a separate signal and do not establish that YOLO detections are displayed.

The decoder now selects and labels all 80 model classes. People mode still filters
to people downstream. Confidence, alert sensitivity, camera behavior and model
files are unchanged. Everything means the model's 80 classes, not every possible
obstacle; recognition still depends on the scene and device.

## Automated checks

* `CocoRawHeadDecoderTest` checks all 80 labels against independently extracted
  model metadata, both raw tensor layouts, chair/person overlap, and competing
  class scores. It also replays the Sharp-model bottle candidate captured from
  the reported recording. The private recording is not included in the repository.
* `DetectionOverlayRegressionTest` runs the production decoder, alert policy and
  Compose Canvas on Android. It checks visible bracket pixels at independently
  specified coordinates: neutral cyan, LOW green, MEDIUM amber and HIGH red. It
  verifies that clearing detections removes stale boxes. A text-only status is
  insufficient to pass these checks.
* Android CI runs the visual tests before producing its release-bundle artifact
  and retains test reports. This is a workflow check, not a claim that branch
  protection has been configured.

Run locally with an Android emulator:

```sh
./gradlew -Pbundle :app:testDebugUnitTest :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ai.genwhy.nobonk.ui.DetectionOverlayRegressionTest
```

`-Pbundle` disables the default ARM-only sideload splits so Intel CI emulators
receive a compatible APK too; the same rendering assertions run on ARM and Intel.

The new bottle and chair tests were also run with the old eight-class selection
temporarily restored: both failed. They pass with the corrected decoder.

## Device acceptance before calling a build field-verified

On a real phone, select Everything and check Fast and Sharp in a well-lit, safe
stationary scene with a person, chair, bottle and plant. Confirm named boxes align
with objects. A recognized object below the alert threshold should still have a
neutral box. Check escalating colored alerts as the object occupies more of the
frame, People-only filtering, Stop/Start and returning from background mode.

The automated pixel tests use controlled detections; they do not measure real
camera recall. The recording replay confirms the Sharp model recognized a bottle,
not that every object or either model always succeeds on a live phone.


## People-mode isolation and readiness (1.0.18)

`PeopleModeInstrumentedTest` feeds real RGBA frames through the shipped Fast model
and `DetectionEngine`. A uniform-gray positive control must trigger the actual
wall heuristic in Everything mode, then produce no wall/ground HUD in People
mode. Scope changes and new session tokens reset readiness; covered frames remain
unready, and all input frames close. This test runs in CI with the pixel tests.

`StartupCancellationInstrumentedTest` also verifies that a warmed model without
camera results is not advertised as ready, scope changes clear public hazards,
and changing scope cannot restart a stopped session. Run separately from heavy
release optimization to avoid resource-contention timeouts on local emulators:

```sh
./gradlew -Pbundle connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ai.genwhy.nobonk.ml.StartupCancellationInstrumentedTest
```

Pure tests cover all-class People filtering, scope-generation invalidation,
consecutive person confirmation, disjoint/duplicate/missing observations,
1.2-second inference intervals, readiness timestamps/blocked frames, background
status and verified provider selection/fallback. They do not establish outdoor
precision or recall. Field follow-up: start in People, walk safely past chairs,
shadows and walls, confirm no object/surface warnings, then verify nearby people
still produce cues. Everything deliberately retains broader object warnings.

## Scan controls regression (1.0.19)

`ScanControlsRegressionTest` exercises the production status tag and control dock with shared state. It verifies that both People/Everything controls agree, changing scope does not invoke Start, History is only exposed after opening Settings, History closes the sheet before navigating, and the compact Start control retains a 48dp touch target. A second case scrolls the settings sheet at doubled text size and invokes the fixed-header Stop control.

For narrow-window visual review, use an isolated 1080px emulator at density 540 (320dp wide) and system `font_scale=2.0`, not just a 320dp composable or local font-scale override inside a wider dialog window. CI applies these emulator settings too. The tests save screenshots under `/data/local/tmp/nobonk-ui-*.png` on the disposable emulator. Rendering tests separately verify actual colored detection brackets. These controls tests do not replace physical-phone camera or background-service tests.

## Background field acceptance (1.0.22 candidate)

Record phone model, Android version, app version from Settings, detection scope,
model and sensitivity. Start stationary in a safe place with another person
helping; do not manufacture near-collisions or test in traffic. These observations
remain outstanding until a real phone is available.

1. In People mode, point at chairs, walls and shadows, then at a person. Check
   that nonperson/surface warnings remain absent and person boxes still appear.
   Record misidentified people separately: filtering cannot correct a model
   that incorrectly labels another object as a person.
2. Hold one stable confirmed hazard in view. Time successive sound/haptic/voice
   cues: the shared minimum repeat intervals are 6 seconds for high, 10 for
   medium and 15 for low. Escalation or a new high-priority person may cue sooner.
   Confirm visual boxes stay live between sounds. Then clear the scene and
   introduce another person; reduced repetition must not suppress fresh hazards.
3. Choose Run in background, then open another app. Move Open NoBonk away from
   that app's controls and bring up its keyboard. The return control must not
   cover the keyboard. Open NoBonk must return to the active preview. Repeat with
   notification Stop: returning afterward must stay stopped, with the camera
   indicator off. Record camera release timing rather than assuming it is instant.
4. Let another camera app take access. NoBonk must show paused/unavailable,
   clear old hazards and stay silent. After access returns, new frames must be
   required before alerts resume. Explicit Stop must defeat recovery.
5. Slowly tilt the uncovered rear camera up and down in portrait and landscape.
   Verify angle guidance matches orientation, settles without rapid flicker,
   and does not imply calibrated distance. Repeat with an obscured camera.
6. Test the optional walking reminder separately: arm it, walk, receive one
   prompt, dismiss it and confirm the camera never started. Re-arm and choose
   Start explicitly. Stop must disarm it. Notification denial must produce an
   understandable setup message instead of silently waiting for an unseen prompt.
7. Open Settings → feedback, select a category, inspect the optional diagnostics
   and email draft, then cancel. Confirm nothing sends automatically. Copy the
   report if there is no configured email app.

Use the same safe route and settings when comparing the installed release and
candidate. Report observed alert counts and missed detections, not an unsupported
accuracy percentage. Keep videos private unless separately approved for sharing.

### Processing gaps must not rearm alerts

A missing frame is not a clear scene. After a HIGH cue, neither the same track nor a reassigned track may use a three-second processing gap to bypass the six-second repeat interval. Suppressed frames break clear-scene evidence without consuming a cue. Observed clear frames spanning three seconds rearm the first hazard; escalation still bypasses the ordinary repeat interval. These cases are covered in AlertCadenceTest.
