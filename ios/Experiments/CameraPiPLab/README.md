# Camera PiP Lab — disconfirmation experiment

This separate iOS 18+ app asks one narrow question: **does an ordinary sample-buffer Picture in Picture presentation continue receiving fresh frames from the iPhone camera after Home or lock?** A visible or frozen PiP window is not evidence of continuing camera capture, and neither capture nor this experiment establishes obstacle detection. No inference is included.

The experiment is not integrated into NoBonk, App Store ready, or verified on a physical phone. It deliberately does not use the video-call PiP API, VoIP, multitasking-camera entitlement, a camera-access override, BG tasks, hidden audio, a microphone, networking, SDKs, or persisted camera frames. No account, signing, purchase or device provisioning is performed by its build instructions.

## What it does

- Explicit acknowledgment and foreground **Start camera**. Granting camera permission does not start capture; tap Start again.
- Ordinary `AVPictureInPictureController.ContentSource(sampleBufferDisplayLayer:playbackDelegate:)`, visible **Open PiP**, and a live/infinite playback time range. Automatic inline PiP is disabled.
- Standard `audio` background-mode capability for visible video PiP and a `.playback` / `.moviePlayback` audio-session category. There are no audio samples, players, recorder, microphone input or silent playback loop. The category is not used to claim camera access and is released when PiP ends or cannot start.
- Reports the camera session's default `isMultitaskingCameraAccessSupported` and `isMultitaskingCameraAccessEnabled` values, without changing them.
- Serial capture ownership, native frames, approximately 15 fps when supported, and a one-frame display mailbox. Stop synchronously invalidates the frame generation before queued capture teardown. Old frames/callbacks cannot restart a stopped session.
- Increasing camera presentation timestamps, total capture count, display submission count, last frame age, largest arrival gap, interruptions and one retained Home/lock report. Duplicate or older timestamps do not count as new frames. Display submission is not proof that a frame was actually shown in PiP.
- The Home report separately counts frames arriving after the first second, and freezes the last-frame age **at return**, so resumed foreground capture cannot erase evidence of a background freeze.
- OSLog metadata at most once per second, subsystem `ai.genwhy.nobonk.camerapiplab`. No frame content is logged. The in-app report is memory only and is lost on force-quit.

Leaving without active PiP stops capture. During the deliberate PiP trial, the experiment leaves the original capture request in place and observes what iOS permits. It does not retry or start capture from background. iOS itself can resume an interrupted session on returning to foreground; this is documented behavior and must not be mistaken for background success. Stop or ending PiP ends the trial and retains its report.

## Build and local checks

From this folder, with Xcode and XcodeGen already installed:

```sh
xcodegen generate
swift test
xcodebuild -project NoBonkCameraPiPLab.xcodeproj -scheme NoBonkCameraPiPLab -destination 'generic/platform=iOS Simulator' -derivedDataPath /private/tmp/nobonk-camerapiplab-sim CODE_SIGNING_ALLOWED=NO build
xcodebuild -project NoBonkCameraPiPLab.xcodeproj -scheme NoBonkCameraPiPLab -destination 'generic/platform=iOS' -derivedDataPath /private/tmp/nobonk-camerapiplab-device CODE_SIGNING_ALLOWED=NO build
```

`swift test` runs three macOS journal tests using synthetic Core Media buffers: stale/duplicate rejection, Stop/new-generation rejection, and a background freeze that stays visible after foreground frames return. It does not open a camera or simulator. The unsigned device build is not an installable signed distribution.

## Physical iPhone protocol

1. Install using an already authorized development setup; record phone model, iOS build, app commit, power/thermal conditions and Low Power Mode. Run the installed app without an attached debugger. Remain stationary, hold the phone in portrait, and point at a clock or moving object in a safe place.
2. Acknowledge, allow camera access, then explicitly Start. Verify live preview, increasing capture count/PTS, low frame age, and record the reported multitasking supported/enabled values.
3. Tap Open visible PiP. If impossible/failed, record the exact status and stop. Do not change entitlements, invent a call, or play silent audio to make it work.
4. Wait until PiP is visibly active. Press Home. Move the clock/object for **15 seconds** and independently note whether the PiP picture continues changing, freezes or disappears. Return and capture the report immediately. Camera frames after one second alone are insufficient; inspect frame age at return, largest gap, interruptions and the observed motion throughout the interval.
5. Start a fresh trial and repeat with device lock for 15 seconds. Keep Home and lock results separate. If a short trial succeeds, repeat 60 seconds and five minutes, then under competing app load and Low Power Mode. A success on one phone/OS does not imply general support or App Review acceptance.
6. While PiP is active, test closing it and its Pause control. Both should end capture. Return and verify Stop, zero further count growth and no retained image. Rapid Start→Stop→Start must not deliver frames from the old generation. Test permission denial separately.
7. Preserve the report/Console event times for each trial. Note whether the system resumed capture on return. No screenshot of a PiP window by itself qualifies as success.

An ordinary camera-background interruption is reason **1** (`videoDeviceNotAvailableInBackground`); the UI also retains the last numeric reason after the interruption ends. Any stale frame age, missing frames, interruption, vanished PiP, or PiP startup rejection is a useful negative result. A positive result only establishes fresh camera delivery on that exact configuration for the measured interval, not reliable detection, audio alerts, battery behavior, indefinite execution or approval to ship.

## API boundaries and sources

- [Ordinary sample-buffer PiP source](https://developer.apple.com/documentation/avkit/avpictureinpicturecontroller/contentsource-swift.class/init(samplebufferdisplaylayer:playbackdelegate:)) and [live-content time range](https://developer.apple.com/documentation/avkit/avpictureinpicturesamplebufferplaybackdelegate/pictureinpicturecontrollertimerangeforplayback(_:)). These are video-presentation interfaces, not a universal camera authorization.
- [Camera unavailable in background](https://developer.apple.com/documentation/avfoundation/avcapturesession/interruptionreason/videodevicenotavailableinbackground) documents preservation of a start request and resumption when the app returns if it has not been explicitly stopped.
- [Multitasking camera support](https://developer.apple.com/documentation/avfoundation/avcapturesession/ismultitaskingcameraaccesssupported). Runtime values are evidence about the actual configuration; this experiment adds no entitlement or enable override.
- [Playback audio category](https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/playback). The standard PiP/video capability does not justify a fake audio keepalive or guarantee camera access.

Do not transplant this probe into production based on compilation or simulator behavior. Physical measurements and a separate review of the intended feature are required.
