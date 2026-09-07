# Nearby Phone Lab

An isolated, distance-only research app for two consenting iPhone testers. The experiment asks whether a foreground-paired Nearby Interaction session continues delivering useful measurements while a Live Activity is active and another app is visible. **It does not detect obstacles or unpaired people. It is not part of the shipping NoBonk app.**

## Status and scope

- Minimum iOS 18.4. The iPhone 16 Pro Max and iPhone 16 Plus have second-generation Ultra Wideband hardware, but the app checks actual runtime capabilities and gracefully rejects devices without precise ranging.
- Separate application and Live Activity extension, separate bundle IDs, no main-app dependencies. XcodeGen project, no package dependencies.
- Local validation on September 7, 2026: **10 state tests passed; unsigned generic simulator and device builds passed with Xcode 26.6.** No compiler/build warnings remained. **No physical pairing, background runtime, Live Activity stop interaction, latency, battery, distance accuracy, or installation has been verified.** A successful build is not evidence of successful background ranging.
- Camera assistance and extended-distance mode are explicitly disabled. No camera, microphone, GPS, server, analytics, saved history or proximity notifications. Session event notes exist only in memory.
- Only `nearby-interaction` is declared in `UIBackgroundModes`. There is no invented Nearby Interaction entitlement, audio background mode or silent-audio workaround. The Live Activity is explicitly started while the app is in the foreground.

## Build without signing or installing

From this directory, with Xcode 26.6 and XcodeGen:

```sh
swift test --disable-sandbox --scratch-path /private/tmp/nobonk-nearbylab-swift-build
xcodegen generate --spec project.yml
xcodebuild -project NearbyPhoneLab.xcodeproj -scheme NearbyPhoneLab \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /private/tmp/nobonk-nearbylab-simulator \
  CODE_SIGNING_ALLOWED=NO build
xcodebuild -project NearbyPhoneLab.xcodeproj -scheme NearbyPhoneLab \
  -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath /private/tmp/nobonk-nearbylab-device \
  CODE_SIGNING_ALLOWED=NO build
```

`--disable-sandbox` only disables Swift Package Manager's nested manifest sandbox, which cannot start inside this host's managed execution sandbox. The package has no dependencies or plugins. Generated projects/plists and build products are not source-controlled. These commands do not boot a simulator, register an identifier, sign, create a provisioning profile, install an app or incur a purchase.

## Session design

1. One tester creates a session; the other browses. Every session uses a random `Phone-XXXXXX` display name rather than the owner's device name.
2. The joining tester selects the matching name and confirms an invitation. The receiving tester separately approves the matching name. Invitations in the background, during another pairing, or from incompatible protocol versions are rejected. Neither side automatically accepts.
3. Multipeer Connectivity requires encryption. Peers exchange only versioned, size-limited, secure-coded `NIDiscoveryToken` archives; no account, device name, location or measurements are sent through this application protocol. Tokens are temporary and discarded on Stop.
4. Each side creates one `NISession`, runs a `NINearbyPeerConfiguration`, and waits for actual distance callbacks. The default legacy ranging mode is used. Direction capability is displayed as diagnostics but direction is never consumed.
5. A fresh reading enables the **Enable background experiment** button. Each tester presses this while foregrounded, then switches apps. The transport may disconnect in the background; this deliberately does not invalidate a successfully configured Nearby Interaction session.
6. Stop immediately invalidates the state generation, releases the NI/MPC sessions and discovery, rejects any pending invitation, and ends the activity. Old framework-object callbacks cannot mutate a new session. Pending activity updates check their lease and are ordered before activity termination. A Live Activity Stop intent requests the same teardown without opening the main UI. Ending/dismissing the activity also stops the experiment.
7. Framework suspension clears the reading. Only the same still-authorized session can resume after `sessionSuspensionEnded`; Stop or failure cannot revive it. A timeout, peer-ended removal or invalidation ends this experiment and requires explicit new pairing.

The encrypted transport does not establish a verified real-world identity or implement a cryptographic comparison-code ceremony. The temporary names and two approvals are suitable for a supervised, nearby experiment with a known partner; do not present this as a production identity/authentication design.

## Freshness and measurement interpretation

- Times for freshness are monotonic and captured when the NI callback arrives, before its actor hop. Wall-clock dates are used only to set the Live Activity's `staleDate`.
- No callback means the reading expires after two seconds. Nil or invalid distance clears it. No measurement is never displayed as zero.
- The foreground uses a SwiftUI timeline to reevaluate freshness; this is not a background keepalive. The activity's `staleDate` marks cached readings stale without inventing new samples. The OS controls Live Activity presentation timing; physical validation must check the rendered stale state.
- Updates to the Live Activity are limited to at most one per second for valid data, with immediate clearing on unavailable/suspended data. This throttle is an application choice, not a guaranteed NI cadence.
- The largest observed gap spans accepted samples, including a resumed suspension. Sampling is optional and can stop entirely; there is no guaranteed rate, range or duration. Obstruction, body position, competing UWB sessions and system policy can affect it.
- Distance to one token-bearing phone is not obstacle awareness. It cannot reveal a wall, a person without the paired app, a safe path, heading or a reliable time to collision. Do not walk distracted or stage collisions while testing.

## Physical test gate

Use the iPhone 16 Pro Max and 16 Plus after installing the separate app through an explicitly chosen signing workflow. Record both iOS versions, battery levels and relevant permission settings. Launch from the Home Screen **without the debugger attached** for all background trials.

1. Enable Wi-Fi and Bluetooth. On one phone create a session; on the other find it. Read the temporary names to each other. Confirm both sides, allowing Local Network and Nearby Interaction when asked.
2. Keep both apps visible and phones unobstructed, approximately 1–3 meters apart. Confirm nonzero sample counts and fresh values change with a small, safe separation change. A blank UI is not a pass.
3. Enable the Live Activity on both phones while both apps are still visible. Switch one phone to another app, then both. Test 30 seconds, 2 minutes and 10 minutes, noting fresh/stale behavior and sample counts when returning. Try one locked phone, then both; test each separately rather than assuming lock-screen success from an app-switch result.
4. Repeat with a body between the phones, portrait/landscape, pockets, Low Power Mode, and another UWB feature such as Find My. Record limitations instead of promising performance. Stop safely if the signal becomes stale or either tester is uncomfortable.
5. Stop from each phone's main UI and from each Live Activity. Verify the stopping phone releases ranging and removes the activity, shows no fresh distance afterward, and never restarts on foregrounding. The other phone may receive a peer-ended callback or may instead become stale; peer-ended delivery is not guaranteed.
6. Decline an invitation; cancel while it connects; tap Stop while receiving measurements; immediately create a new session; leave and return; revoke permissions; dismiss an activity; force-quit and relaunch. None may resume an old session. Denied permission must produce a useful error without a crash. Review UI confirmation and activity button behavior on the actual devices.

**Stopping criteria:** if a stale value remains presented as fresh, Stop allows fresh callbacks to continue, a canceled session restarts, or pairing occurs without both approvals, stop the trial and fix it before testing with anyone else. If baseline foreground ranging fails, do not add background complexity. If foreground succeeds but Live Activity background ranging fails, capture the NI error and observed state, compare permissions/OS versions, and report that path as unproven rather than adding unrelated keepalive modes. Success on one pair is not evidence of universal obstacle detection or device compatibility.

## Primary evidence checked September 7, 2026

| Claim | Apple source | Scope |
| --- | --- | --- |
| iOS 18.4 adds background NI sessions with an active Live Activity | [iOS & iPadOS 18.4 release notes](https://developer.apple.com/documentation/ios-ipados-release-notes/ios-ipados-18_4-release-notes), [Nearby Interaction](https://developer.apple.com/documentation/nearbyinteraction) | API eligibility, not measured runtime/latency |
| Supported iPhone hardware | [iPhone 16 Pro Max specifications](https://support.apple.com/en-us/121032), [iPhone 16 Plus specifications](https://support.apple.com/en-us/121030) | Runtime capabilities still checked |
| Pairing tokens and relative peer ranging | [Implementing interactions between users in close proximity](https://developer.apple.com/documentation/nearbyinteraction/implementing-interactions-between-users-in-close-proximity), [NISession](https://developer.apple.com/documentation/nearbyinteraction/nisession) | Apple sample's token exchange informed this baseline; automatic acceptance was replaced |
| MPC browsing/advertising stops and sessions disconnect in the background | [Multipeer Connectivity](https://developer.apple.com/documentation/multipeerconnectivity) | Transport loss must be kept separate from NI lifecycle |
| Direction and distance availability differ | [NIDeviceCapability](https://developer.apple.com/documentation/nearbyinteraction/nidevicecapability), [NINearbyObject](https://developer.apple.com/documentation/nearbyinteraction/ninearbyobject) | Optional measurements, no guaranteed direction |
| Extended distance has additional constraints | [Extending advanced direction finding and ranging](https://developer.apple.com/documentation/nearbyinteraction/extending-advanced-direction-finding-and-ranging) | Disabled for this baseline; one extended-distance session can be preempted |
| Activity UI has its own lifecycle and staleness | [Displaying live data with Live Activities](https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities) | Stale dates do not generate measurements or grant general background execution |
| Background validation needs actual devices without a debugger | [Apple DTS: Testing Background Modes](https://developer.apple.com/forums/thread/14855) | Simulator compile is not a background execution test |

The Xcode 26.6 NearbyInteraction headers were also read for camera-assistance defaults, suspension callbacks, optional distance, session invalidation and resource/session-limit errors. Camera assistance can create an AR session implicitly, so it is explicitly set false here. No fixed universal session limit, sampling frequency or timeout was inferred from error names.
