# NoBonk for iPhone — foreground prototype

Native SwiftUI / AVFoundation / Apple Vision starting point, built locally on the Mac. No cloud inference, paid SDK, external dependency, account, subscription or network client. It reuses NoBonk's corrected Blender icon and credits Krishav.

## Working scope

- Full first-use safety notice with an unchecked acknowledgment switch. Existing acknowledgment versions are rechecked when the notice version changes.
- Short warning at the top with **OK — continue** on every fresh launch and after leaving for another app.
- Rear-camera preview, local **people-only** detection, bounding boxes, explicit Start/Stop, sound and haptic controls.
- A conservative initial cue when a person occupies a large central portion of the image for three consecutive analyzed frames. Three-second cooldown. This is image size, **not a calibrated distance or collision prediction**.
- Camera stops on inactive/background transitions and interruptions. Returning requires the reminder after backgrounding; scanning is always started explicitly. Permission denial has a Settings action. Permission callbacks cannot silently start capture.
- No frame recording, history, location, microphone, photo-library, telemetry or background modes. Only the notice version is persisted locally. Required-reason API declarations cover local preferences and elapsed-time cooldowns.

This prototype is not Android feature parity and is not ready for App Store/TestFlight distribution. It does **not** detect cars, pets, walls, potholes or general obstacles. The UI makes that limit explicit. Test on a real iPhone before relying on any behavior; NoBonk is never a safety device.

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

- Five Swift package tests pass (safety gates, repeated reopen, read-only notice, persistent-person cue/cooldown, invalid/peripheral boxes).
- All app and core Swift files pass `swiftc -typecheck` against the installed iPhoneOS SDK.
- Both property lists pass `plutil -lint`.
- Full Xcode packaging is **blocked by the local toolchain**, not reported as passing. Xcode 26.6 (17F113) exposes iOS 26.5 SDK 23F81a, but initially had only older simulator runtimes. Apple's free iOS 26.5 arm64 runtime 23F73 was downloaded and installed successfully. Xcode still reports “iOS 26.5 is not installed” when resolving destinations, including after standard first-launch initialization. Resolve the SDK/runtime registration in Xcode Settings → Components before attempting device installation or claiming a successful full app build. No installable IPA has been produced.

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
