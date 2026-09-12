# NoBonk for iPhone — Browse & scan and Read / write preview

Native SwiftUI / AVFoundation preview, built locally on the Mac. Apple Vision People is the default; an optional, checksum-pinned ONNX Fast Objects model adds eight selected classes through the free official ONNX Runtime SDK. No cloud inference, paid SDK, account, subscription or inference network client. It reuses NoBonk's corrected Blender icon and credits Krishav.

## What you can do

| Capability | Current development preview |
| --- | --- |
| Browse & scan | A compact live camera panel above a secure website inside NoBonk; Start/Stop stays visible. |
| Reading and web video | User-opened HTTPS pages and compatible inline media. Full-screen media or leaving the app stops scanning. Particular sites, sign-ins and DRM need testing. |
| Web chat | Compatible chat websites can be opened in the browsing pane. Service support varies; this does not embed other native apps. |
| Read / write | Read text you explicitly paste or write below the visible camera. Read mode uses a scrollable, selectable view without the keyboard. Paste appends to existing text. Content stays in session memory; NoBonk does not save, sync or fetch messages. |
| Messages / WhatsApp handoff | **Pause & share** opens Apple’s share sheet with the draft; available destinations depend on installed apps. A configured phone also offers a direct Messages composer in the larger layout. You choose the recipient and confirm sending. Scanning pauses until you return and tap Start. No native inbox access. |
| People | Apple Vision detects people locally. |
| Fast Objects | Optional verified graph detects people, bicycles, cars, motorcycles, buses, trucks, cats and dogs locally. |
| Cues | Visual indication, optional sound and haptics, with Earlier/Balanced/Closer sensitivity. These use apparent image size, not metres. |
| Quick access | Siri/App Shortcuts plus supported Action button, Control Center and Lock Screen controls open setup. Acknowledgment and explicit Start still apply. |

**0.2.6 (build 9)** is the latest signed development preview. Build 8 installed and launched on HRD during September 12 testing; build 9 contains the final compact-keyboard refinement. Installation and lock-state results are recorded in the local delivery handoff. This is a development installation, not a public App Store or TestFlight release. The Android closed-test link does not install the iPhone app.

This update adds a parent-owned, in-memory message draft, explicit paused handoffs to Messages or the share sheet, and Instagram-web/YouTube browser shortcuts. Native Instagram and WhatsApp cannot run inside this pane; Instagram website sign-in/messaging remains unverified. NoBonk hides its own content when inactive, but the system composer/share sheet is managed by iOS; comprehensive snapshot prevention is not claimed.

The scan status distinguishes preparation, waiting for analysis, recent detections and delayed analysis. Five-second-old analyzed-frame observations no longer appear as fresh boxes or trigger cues. Delayed analysis does not abort a slower phone's capture; fresh results recover automatically. Freshness uses monotonic callback-receipt time, not the sensor's capture timestamp. An empty result never means the path is clear.

Forty core tests and seventeen hosted WebKit/layout tests pass. Layout tests cover a short 320-point-wide editor pane and large accessibility text; they are not a substitute for physically typing with different keyboards. Signing, installation and launch are verified separately. Real camera accuracy, haptic feel, Instagram sign-in, and third-party share destinations still require phone testing.

## Start deliberately; stop predictably

- Full first-use safety notice with an unchecked acknowledgment switch. Existing acknowledgment versions are rechecked when the notice version changes.
- Short warning at the top with **OK — continue** on every fresh launch and after leaving for another app.
- Compact camera dashboard with an expandable preview, a persistent Start/Stop button, a top safety reminder, and sound/haptic controls. The Set up tab holds detector and cue controls; Browse & scan puts a website below the camera. Draft lets you write in a local editor below the camera. Other native apps cannot occupy either pane.
- Local **People** or optional **Fast Objects** detection with bounding boxes fitted to preview letterboxing. Earlier/Balanced/Closer cues use apparent image size, not metres. See [Fast model setup, provenance and validation](FAST-OBJECTS.md).
- A cue when the same supported class occupies the selected central portion of the image for three consecutive analyzed frames. Three-second cooldown. This is image size, **not a calibrated distance or collision prediction**.
- Generation tokens reject late camera callbacks after Stop or a new session; failed starts release the retry latch. The owned audio cue also stops immediately when scanning or sound is disabled. Camera stops on inactive/background transitions and interruptions. Returning requires the reminder after backgrounding; scanning is always started explicitly. Permission denial has a Settings action. Permission callbacks cannot silently start capture.
- No frame recording, detection history, location, microphone capture, photo-library access, telemetry or background modes. The notice version and optional compiled-model cache are persisted locally; camera frames are not. Website traffic is separate: user-opened pages connect to the internet, follow their own privacy policies and use a nonpersistent WebKit data store. Website camera, microphone and motion permission requests are denied. NoBonk holds your draft in memory and prefills it only when you explicitly hand it off. It does not read inboxes, recipients, or edits inside Apple’s composer. The receiving app and keyboard have their own privacy behavior. Required-reason API declarations cover local preferences and elapsed-time cooldowns.
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

## Earlier validation on September 7, 2026

- **34 Swift core tests pass**, covering the safety gates, generation invalidation, detector preprocessing/decoding, native session ownership, pacing, preview geometry and deterministic audio cancellation.
- Three hosted quick-access tests pass. Nine real WebKit tests pass with zero skips, including actual navigation rejection, a permission callback forwarded to production denial, lazy browser creation, draft-address preservation and prevention of script-driven playback after persistent suspension. See [WebKit tests](WebKitTests/README.md).
- Generic device and simulator builds pass. The signed 0.2.4 Release build passes strict signature verification; installation awaits device access. Version 0.2.3 was installed on a registered iPhone 16 Plus; HRD still has 0.2.1 pending reconnection. Simulator checks cover layout and permissions, including iPhoneSE/iOS17 at the largest accessibility text size with a software keyboard. Preview and Start/Stop remain pinned; status and browser errors can scroll without clipping. These are not detection-effectiveness or haptic-feel results.
- An initial synthetic Fast probe on iPhone 16 Plus passed CPU/Core ML numerical smoke checks. Later physical reruns exited before completion, including a normal app launch outside XCTest, and are under investigation; do not treat that rerun or the sustained probe as passed. [Device benchmark instructions and limitations](DeviceBenchmarks/README.md) keep synthetic timings separate from real-camera accuracy, battery life and hardware placement.
- Real-world missed detections, low light, full-screen video, supported web chat, audio routes, text scaling, heat and older physical phones still need validation. Device installation is not evidence that all these cases work.

## Why foreground-only

Apple documents camera interruption when an ordinary app moves into the background. Multitasking camera access has specific eligibility (supported iPad multitasking, approved entitlement, or applicable VoIP use). NoBonk is not a VoIP app. A visible ordinary Picture in Picture renderer is being investigated separately; displaying a floating view does not itself grant camera access. Do not add fake calling, silent audio or unrelated location modes to keep the process alive.

Isolated [experiments](Experiments/README.md) investigate fresh-frame delivery in ordinary PiP, background CPU execution and consenting nearby phones. These targets are not included in the NoBonk app, and their builds do not establish working background obstacle detection.

- [Camera unavailable in background](https://developer.apple.com/documentation/avfoundation/avcapturesession/interruptionreason/videodevicenotavailableinbackground)
- [Multitasking-camera support conditions](https://developer.apple.com/documentation/avfoundation/avcapturesession/ismultitaskingcameraaccesssupported)
- [App Review Guidelines, 2.5.4](https://developer.apple.com/app-store/review/guidelines/)
- [Required privacy reasons](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitypereasons)

A real YouTube page played inline in the taller accessible layout on the SE simulator, while the normal-text viewport exposed a clipped player. The 0.2.4 correction compiles but its fresh visual check awaits Mac unlock. Camera remained paused during playback. See [the compatibility record](BROWSE-COMPATIBILITY.md) for the exact evidence and remaining checks.

## Try it on the installed phone

1. Set up while standing still. Accept the safety notice, choose People or Fast Objects, then tap **Start** and allow the camera if prompted.
2. Point the rear camera toward a person or a supported object in good light. Compare labels and cues with what you actually see. Leave plenty of space; do not walk toward hazards to test it.
3. Choose **Browse**, enter a secure web address and keep the camera visible. Inline content compatibility varies. If video opens full screen or another app, expect scanning to stop and require Start again.
4. Choose **Draft**, type a harmless test sentence and check the camera and Stop remain reachable. **Pause & share** stops the camera before opening available sharing apps. Cancel, return and verify your draft remains. On a configured phone, **Pause & Messages** also opens Apple’s composer in the larger layout. Sending is always your choice. Return and tap Start to scan again.
5. Try Stop, Home/reopen, lock/unlock, changing modes, Sound/Haptics and larger text. Report the phone model, iOS/app version, what you tried and what happened. Keep personal messages and account details out of recordings.

## Next milestones

Finish physical lifecycle and sustained-performance investigation, compare real detection/alert quality across phones, and validate accessibility, audio and web-media behavior. A separate Messages camera-panel experiment asks whether useful scanning can coexist with a visible Messages conversation; compact extensions replace the keyboard, so simultaneous typing is not promised. It is not included in this app.

Prepare the remaining privacy, licensing and distribution review before public TestFlight/App Store availability. Existing development signing is used; no new paid service or membership was purchased.

## Selected-text update — September 12, 2026

Version 0.2.7 adds Write / Read switching inside the Read / write workspace. Copy chosen text in another app, return to NoBonk, choose Read and tap Paste; then deliberately Start scanning. Existing text is retained and new pasted paragraphs append. Clear removes it from NoBonk’s session. This is not a live inbox or automatic message reader. Returning to another app pauses scanning. No text is sent to Qwen; Qwen assisted development using source code only.

A separate [Messages Camera Lab](Experiments/MessagesCameraLab/README.md) is installed on HRD for an owner-run test. Host installation works; changing camera frames inside a Messages conversation are not yet verified. It is not part of the main preview.
