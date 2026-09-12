# NoBonk Messages Camera Lab

A separate iPhone research host and iMessage extension. It asks a narrow question:
can a **visible, compact camera panel** keep receiving new frames while a person
reads the Messages conversation above it? This is not a background workaround,
shipping NoBonk feature, general chat client, or App Store approval claim.

## Supported code path and unknown behavior

- iOS 18.4+, portrait. Uses a proper `app-extension.messages` target,
  `com.apple.message-payload-provider`, and a storyboard-hosted
  `MSMessagesAppViewController`. No main app target changes.
- The host explains how to find the extension; it does not activate a camera.
- The extension requires a safety acknowledgment on each activation and a
  separate Start. Compact mode has a small preview, freshness/count status, and
  Start/Stop pinned outside the scrolling notice/details. Use Read notice/Details
  to expand when the compact panel is too short for the notice. Actual clipped or
  undersized camera/Stop regions fail closed and cancel the run. Expand for the
  complete notice and frozen trial diagnostics.
- Serial video-only AVFoundation capture and one reusable
  `VNDetectHumanRectanglesRequest`, targeting at most 4 inference attempts/second
  and 15 camera frames/second when the device supports that cadence. These are
  ceilings, not performance guarantees. No Fast/Sharp asset download is needed.
- Only People detections with confidence >=0.5 are counted. No obstacle distance,
  wall/traffic detection, collision prediction, vibration or audio cue is claimed.
- No conversation object is inspected. The lifecycle signatures receive a
  framework-supplied `MSConversation`, but no message, participant or conversation
  property is read. No insertion, sending, inbox access or conversation logging.
- Camera frames and Vision results remain in memory; there is no saving/uploading,
  microphone input, network client, shared app group, background mode or private API.
- Existing NoBonk brand artwork is copied unchanged into the host and extension
  catalogs. Messages drawer presentation of the app icon needs physical inspection.
- Camera authorization/availability, actual Messages layout, preview freshness,
  extension memory limits, prolonged operation and simultaneous typing **remain
  physical-test questions**. A compile or passing unit test proves none of them.

Apple documents compact presentation as replacing the keyboard. Expanded mode
can host an extension's own keyboard-using controls; this lab does not add such
controls or claim typing in the native conversation while scanning. Moving to
the keyboard, another app, the lock screen, media camera or another extension may
dismiss/suspend this panel. We intentionally stop and require Start again.

## Stop and evidence boundaries

Every Start has an immutable activation/run token. Camera permission completions,
per-run output delegates, interruption observers, preview UI hops and completed
Vision requests must still match that token. Stop, resize, view disappearance,
`willResignActive`, host resignation/background, camera interruption and runtime
errors invalidate admission immediately. There is no restart from an interruption
ended event. A permission prompt that suspends the host also cancels that Start.

The synchronous journal freezes metadata before dispatching camera teardown or
UI refresh. Native `stopRunning()` is serialized off the UI thread because it can
block; an in-flight Vision operation cannot publish its result after Stop. The
physical delay until the green camera indicator disappears still needs measuring.
We do not depend on async cleanup finishing before an extension is terminated.

Frame freshness uses callback receipt time and distinct monotonic sample PTS,
not `session.isRunning`. The preview is covered when no accepted frame is newer
than 1.5 seconds. People counts have their own 1.5-second completion freshness.
This is a short staleness bound, not a promise of continuously live detection.

Diagnostics retain only counts, monotonic timestamps, end reasons and the last
People count. On a lifecycle boundary, receipts at or after its captured timestamp
are excluded, even if another queue wins the journal lock first. Old producer and
resumed foreground callbacks cannot grow an ended trial. At most six ended trials
remain in memory. At 10,800 camera callbacks a trial stops with an explicit metadata
limit. Reset cancels the current run, clears diagnostics and requires acknowledgment.
Termination may discard the entire journal; no absence of a report proves capture
continued or stopped at a particular time.

## Build and unit validation

On 2026-09-12, all 14 unit tests passed again. A signed Release build passed
strict code-signature verification and was installed and launched on the paired
HRD iPhone. This confirms the host installation only: the owner still needs to
open the extension in Messages and observe changing frame/scan counters while
reading a chosen conversation. No conversation content was accessed during
installation, and camera operation in the Messages extension remains unverified.

Validation on 2026-09-07: **14 unit tests passed**; unsigned generic-device Release
and generic-simulator Debug builds passed using the installed iOS 26.5 SDK. An
independent read-only review of lifecycle, native ownership, privacy and visible
camera admission found no remaining concrete defect after the two fixes described
in the implementation. No simulator was launched and no phone trial was performed
as part of this implementation. The App Intents metadata tool reports that it has
no framework dependency to extract; this lab does not use App Intents.

From this directory:

```sh
swift test --disable-sandbox
xcodegen generate
xcodebuild -project NoBonkMessagesCameraLab.xcodeproj -scheme NoBonkMessagesCameraLab -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
xcodebuild -project NoBonkMessagesCameraLab.xcodeproj -scheme NoBonkMessagesCameraLab -configuration Debug -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

The Foundation/CoreGraphics tests cover acknowledgment, delayed permission admission,
duplicate activation/Start, stale frame/error/inference callbacks after restart,
actual synchronous NotificationCenter boundaries, callback/boundary timestamp
ordering, resumed foreground frames, reset, stale evidence, bounded metadata,
visible-region admission and frozen Stop reports.
Unsigned build products require an authorized developer to configure signing
before installation. This experiment does not create profiles or install itself.

## Physical protocol — not yet executed

Use an existing iPhone 16 Plus or 16 Pro Max, record its exact iOS build, and keep
the device in portrait. Use a test conversation chosen by the owner; avoid putting
personal message contents in any shared recording. Remain stationary with a helper
and an unobstructed camera view. Do not test by walking into traffic or obstacles.

1. **Discovery and permission.** Install the host and embedded extension through
   the owner's approved signing workflow. Open Messages → a conversation → `+` →
   the lab (possibly `More`, depending on iOS). Record whether compact opens and
   whether the safety notice/44-point buttons fit. Acknowledge, Start and allow
   camera access. If the permission prompt stopped the activation, acknowledge
   and Start again. Denying access must leave the camera off and explain next steps.
2. **Fresh visible baseline.** Hold still, then have a helper move through the rear
   camera view for 20 seconds. Observe changing preview, frame age below 1.5s,
   increasing frame/scan counts and plausible People counts. A zero count is not
   evidence the scene is safe. Record if Messages refuses camera access or kills
   the extension. Do not infer GPU/ANE use from a Vision result.
3. **Reading alongside the panel.** While stationary, scroll/read the conversation
   above the compact panel for 20 seconds. Check that the preview and metadata keep
   changing. Record dismissal, interception of scrolling, staleness and layout
   height. This is the core feasibility gate; fail the hypothesis if reading
   dismisses/freezes the visible panel or the camera does not deliver fresh frames.
4. **Typing boundary.** Select Messages' compose field / native keyboard without
   sending anything. Record whether the extension disappears. Expected conservative
   outcome: camera stops and cannot return without acknowledgement/Start. Do not
   label simultaneous typing supported merely because the old preview remains.
5. **Stop and restart.** Start for 10 seconds, then Stop. Preview must immediately
   show CAMERA OFF, frame/scan counts must freeze, and the camera indicator should
   disappear after native teardown. Measure this delay. Restart and compare run
   IDs; old trial counts must stay fixed. Repeat rapidly five times.
6. **Presentation and orientation.** Expand, collapse, and rotate out of portrait.
   Each change stops the trial and requires another Start; landscape Start is
   refused. Check the notice, status, diagnostics and buttons at large text sizes.
7. **Leave, lock and interrupt.** Separately switch app, lock/unlock, dismiss the
   extension, open another Messages extension, and open another camera consumer.
   Reopen the lab and inspect the frozen report. Counts must not resume from the
   old run; acknowledgment and Start are required. If the process was terminated,
   report “journal unavailable after termination,” not a background-capture result.
8. **Reset and prolonged use.** Reset while running: camera off, history empty,
   acknowledgment required. Then try a separate two-minute visible trial, observing
   heat, staleness, preview direction and termination. End on errors, heat discomfort,
   stale evidence, unexpected camera persistence or mismatched report counts.

For each trial record device/iOS, presentation, first-frame delay, frame and scan
counts, last receipt relative to Start, end reason, Stop-to-camera-indicator delay,
and whether the extension survived. A screenshot alone cannot establish changing
frames; a short owner-controlled recording or observed moving helper is needed.

## Apple evidence checked 2026-09-07

- [Messages framework](https://developer.apple.com/documentation/messages/):
  custom UI extensions and required camera usage descriptions. It does not promise
  unrestricted or background camera operation.
- [MSMessagesAppViewController](https://developer.apple.com/documentation/messages/msmessagesappviewcontroller):
  public lifecycle and presentation callbacks used by this lab.
- [Compact presentation](https://developer.apple.com/documentation/messages/msmessagesapppresentationstyle/compact):
  the compact UI occupies the keyboard area; extension keyboard controls need expanded presentation.
- [willResignActive](https://developer.apple.com/documentation/messages/msmessagesappviewcontroller/willresignactive(with:)):
  cleanup should return quickly and must not rely on async work finishing before termination.
- Xcode's installed iMessage Extension template and iOS 26.5 SDK headers:
  the Messages product type, storyboard extension entry, and public
  `NSExtensionHostWillResignActive` / `NSExtensionHostDidEnterBackground` notifications.

These API declarations support building the experiment, not approval of this use
case or proof that it works on the target phones. Keep it separate until physical
evidence and product/review requirements justify integration.
