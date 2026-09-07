# NoBonk for iPhone — foreground prototype

Native SwiftUI / AVFoundation preview, built locally on the Mac. Apple Vision People is the default; an optional, checksum-pinned ONNX Fast Objects model adds eight selected classes through the free official ONNX Runtime SDK. No cloud inference, paid SDK, account, subscription or inference network client. It reuses NoBonk's corrected Blender icon and credits Krishav.

## Working scope

- Full first-use safety notice with an unchecked acknowledgment switch. Existing acknowledgment versions are rechecked when the notice version changes.
- Short warning at the top with **OK — continue** on every fresh launch and after leaving for another app.
- Compact camera dashboard with an expandable preview, a persistent Start/Stop button, a top safety reminder, and sound/haptic controls. This leaves space for NoBonk’s setup controls; it does not put another app below the camera.
- Local **People** or optional **Fast Objects** detection with bounding boxes fitted to preview letterboxing. Earlier/Balanced/Closer cues use apparent image size, not metres. See [Fast model setup, provenance and validation](FAST-OBJECTS.md).
- A cue when the same supported class occupies the selected central portion of the image for three consecutive analyzed frames. Three-second cooldown. This is image size, **not a calibrated distance or collision prediction**.
- Generation tokens reject late camera callbacks after Stop or a new session; failed starts release the retry latch. The owned audio cue also stops immediately when scanning or sound is disabled. Camera stops on inactive/background transitions and interruptions. Returning requires the reminder after backgrounding; scanning is always started explicitly. Permission denial has a Settings action. Permission callbacks cannot silently start capture.
- No frame recording, history, location, microphone, photo-library, telemetry or background modes. The notice version and optional compiled-model cache are persisted locally; camera frames are not. Required-reason API declarations cover local preferences and elapsed-time cooldowns.
- App Shortcuts and an iOS 18+ Control widget open the setup screen after device authentication. They preserve the acknowledgment and explicit Start; see [Quick access](QUICK-ACCESS.md).
- A deliberately started scan keeps the screen awake. Stop, leaving the app, locking the phone or a capture interruption ends scanning and restores ordinary auto-lock behavior. Debug builds log local capability and first-frame timing without logging camera images.

This prototype is not Android feature parity and is not ready for App Store/TestFlight distribution. People mode does **not** detect cars or pets. Fast Objects adds selected vehicles, cats and dogs, but neither mode covers walls, potholes or general obstacles. The UI makes that limit explicit. Test on a real iPhone before relying on any behavior; NoBonk is never a safety device.

## Performance strategy

One serial camera queue runs one reused Apple Vision request, with late capture frames discarded instead of queued. Native bi-planar YUV buffers are preferred over converting every frame to BGRA. Camera preview and analysis are separate: analysis targets up to 12 frames/s when measured work is short, backs off as inference takes longer, and respects heat and Low Power Mode. The first Vision initialization sample does not throttle subsequent fast frames. This is pacing logic, not a claim that a particular iPhone reaches 12 frames/s.

Vision selects its computing hardware automatically; no CPU-only flag or forced GPU assignment is used. The interface reports measured analysis duration and a target cadence, not an unverified processor label. Real iPhones still need profiling for startup, accuracy, battery use and sustained heat. There is no model download. Optional Fast Objects verifies and warms its graph before starting capture; see [Fast Objects](FAST-OBJECTS.md) for Core ML configuration, CPU fallback and phase timings.

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

Installing on an actual iPhone requires the owner's signing team and Developer Mode on the device. A development build of the app and Control extension has now signed successfully with the owner's existing identity; no new membership was purchased. The local development IPA is limited to devices in its provisioning profile, and is not a public download or TestFlight release. Signing credentials and the IPA are not committed. Simulator builds cannot validate real-camera detection or haptics.

## Validation on September 7, 2026

- Twenty-five Swift package tests pass (including ten Fast decoder/preprocessing/metadata tests): safety gates, reopen behavior, persistent-person cues and cooldown, invalid/peripheral boxes, sensitivity, concurrent generation invalidation, retry, PCM cue payload, preview geometry, adaptive pacing, heat/Low Power Mode, and cold initialization.
- Three hosted quick-access tests pass: opening preserves acknowledgment state and the reminder, and requires foreground presentation plus device authentication.
- Full Xcode simulator build succeeds with Xcode 26.6 and the installed iOS 26.5 runtime. The earlier SDK/runtime registration blocker is resolved.
- iPhone 17 Pro (iOS 26.5) and iPhone SE (iOS 17.0) simulators launch successfully. Visual checks cover the safety acknowledgment, compact/expanded layouts, permission denial with a Settings action, and larger text on the smaller screen. No live detection is simulated or claimed from these screenshots.
- Property lists pass `plutil -lint`. Generic-device build and strict code-signature verification pass for the app and Control extension. Both paired iPhones were unavailable when checked, so installation, system-control invocation and real-camera performance remain unverified.


## Why foreground-only

Apple documents camera interruption when an ordinary app moves into the background. Multitasking camera access has specific eligibility (supported iPad multitasking, approved entitlement, or applicable VoIP use). NoBonk is not a VoIP app. A visible ordinary Picture in Picture renderer is being investigated separately; displaying a floating view does not itself grant camera access. Do not add fake calling, silent audio or unrelated location modes to keep the process alive.

Isolated [experiments](Experiments/README.md) investigate fresh-frame delivery in ordinary PiP, background CPU execution and consenting nearby phones. These targets are not included in the NoBonk app, and their builds do not establish working background obstacle detection.

- [Camera unavailable in background](https://developer.apple.com/documentation/avfoundation/avcapturesession/interruptionreason/videodevicenotavailableinbackground)
- [Multitasking-camera support conditions](https://developer.apple.com/documentation/avfoundation/avcapturesession/ismultitaskingcameraaccesssupported)
- [App Review Guidelines, 2.5.4](https://developer.apple.com/app-store/review/guidelines/)
- [Required privacy reasons](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitypereasons)

## Next milestones

1. Physical iPhone check: acknowledgment, grant/deny camera, Start/Stop, Home/reopen, lock/unlock, interruption, text scaling, VoiceOver, preview/box alignment and thermal behavior.
2. Export the existing licensed YOLO26 weights to Core ML and integrate the verified model outputs, labels and preprocessing. Port Android's tested alert policy and calibration before exposing metres or approach estimates. Preserve model license notices.
3. Compare detection/alert quality on-device, then prepare privacy and accessibility review, signing and TestFlight under the owner's existing Apple account. No fee is authorized by this prototype.
