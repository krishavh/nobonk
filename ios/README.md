# NoBonk for iPhone — foreground prototype

Native SwiftUI / AVFoundation / Apple Vision starting point, built locally on the Mac. No cloud inference, paid SDK, external dependency, account, subscription or network client. It reuses NoBonk's corrected Blender icon and credits Krishav.

## Working scope

- Full first-use safety notice with an unchecked acknowledgment switch. Existing acknowledgment versions are rechecked when the notice version changes.
- Short warning at the top with **OK — continue** on every fresh launch and after leaving for another app.
- Compact camera dashboard with an expandable preview, a persistent Start/Stop button, a top safety reminder, and sound/haptic controls. This leaves space for NoBonk’s setup controls; it does not put another app below the camera.
- Local **people-only** detection with bounding boxes correctly fitted to the preview’s letterboxing. Earlier/Balanced/Closer cues use apparent person size, not metres.
- A cue when a person occupies the selected central portion of the image for three consecutive analyzed frames. Three-second cooldown. This is image size, **not a calibrated distance or collision prediction**.
- Generation tokens reject late camera callbacks after Stop or a new session; failed starts release the retry latch. The owned audio cue also stops immediately when scanning or sound is disabled. Camera stops on inactive/background transitions and interruptions. Returning requires the reminder after backgrounding; scanning is always started explicitly. Permission denial has a Settings action. Permission callbacks cannot silently start capture.
- No frame recording, history, location, microphone, photo-library, telemetry or background modes. Only the notice version is persisted locally. Required-reason API declarations cover local preferences and elapsed-time cooldowns.

This prototype is not Android feature parity and is not ready for App Store/TestFlight distribution. It does **not** detect cars, pets, walls, potholes or general obstacles. The UI makes that limit explicit. Test on a real iPhone before relying on any behavior; NoBonk is never a safety device.

## Performance strategy

One serial camera queue runs one reused Apple Vision request, with late capture frames discarded instead of queued. Native bi-planar YUV buffers are preferred over converting every frame to BGRA. Camera preview and analysis are separate: analysis targets up to 12 frames/s when measured work is short, backs off as inference takes longer, and respects heat and Low Power Mode. The first Vision initialization sample does not throttle subsequent fast frames. This is pacing logic, not a claim that a particular iPhone reaches 12 frames/s.

Vision selects its computing hardware automatically; no CPU-only flag or forced GPU assignment is used. The interface reports measured analysis duration and a target cadence, not an unverified processor label. Real iPhones still need profiling for startup, accuracy, battery use and sustained heat. There is no model download or explicit warmup benchmark before the first camera frame.

- [Apple: selecting camera pixel formats](https://developer.apple.com/documentation/technotes/tn3121-selecting-a-pixel-format-for-an-avcapturevideodataoutput)
- [Apple: Vision compute-device configuration](https://developer.apple.com/documentation/vision/vnrequest)

## Open and build

Open `NoBonk.xcodeproj` in Xcode. iOS 17+, portrait iPhone. A checked-in Xcode project is included; `project.yml` is its reproducible XcodeGen source.

```sh
xcodegen generate
swift test
xcodebuild -project NoBonk.xcodeproj -scheme NoBonk \
  -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
```

Installing on an actual iPhone requires choosing the owner's signing team in Xcode and enabling Developer Mode on the device. No signing identity or paid membership is configured by this change. Simulator builds cannot validate real-camera detection or haptics.

## Validation on September 7, 2026

- Fifteen Swift package tests pass: safety gates, reopen behavior, persistent-person cues and cooldown, invalid/peripheral boxes, sensitivity, concurrent generation invalidation, retry, PCM cue payload, preview geometry, adaptive pacing, heat/Low Power Mode, and cold initialization.
- Full Xcode simulator build succeeds with Xcode 26.6 and the installed iOS 26.5 runtime. The earlier SDK/runtime registration blocker is resolved.
- iPhone 17 Pro (iOS 26.5) and iPhone SE (iOS 17.0) simulators launch successfully. Visual checks cover the safety acknowledgment, compact/expanded layouts, permission denial with a Settings action, and larger text on the smaller screen. No live detection is simulated or claimed from these screenshots.
- Property lists pass `plutil -lint`. No installable IPA or TestFlight release has been produced.


## Why foreground-only

Apple documents camera interruption when an ordinary app moves into the background. Multitasking camera access has specific eligibility (supported iPad multitasking, approved entitlement, or applicable VoIP use). NoBonk is not a VoIP app. Do not add fake calling, silent audio, location or Picture in Picture modes to keep the camera alive. Fable independently reviewed this approach.

- [Camera unavailable in background](https://developer.apple.com/documentation/avfoundation/avcapturesession/interruptionreason/videodevicenotavailableinbackground)
- [Multitasking-camera support conditions](https://developer.apple.com/documentation/avfoundation/avcapturesession/ismultitaskingcameraaccesssupported)
- [App Review Guidelines, 2.5.4](https://developer.apple.com/app-store/review/guidelines/)
- [Required privacy reasons](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitypereasons)

## Next milestones

1. Physical iPhone check: acknowledgment, grant/deny camera, Start/Stop, Home/reopen, lock/unlock, interruption, text scaling, VoiceOver, preview/box alignment and thermal behavior.
2. Export the existing licensed YOLO26 weights to Core ML and integrate the verified model outputs, labels and preprocessing. Port Android's tested alert policy and calibration before exposing metres or approach estimates. Preserve model license notices.
3. Compare detection/alert quality on-device, then prepare privacy and accessibility review, signing and TestFlight under the owner's existing Apple account. No fee is authorized by this prototype.
