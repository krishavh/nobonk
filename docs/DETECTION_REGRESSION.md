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
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ai.genwhy.nobonk.ui.DetectionOverlayRegressionTest
```

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
