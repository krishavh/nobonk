# Nearby Phone Lab

An isolated, distance-only research app for two consenting iPhone testers. The experiment asks whether a foreground-paired Nearby Interaction session continues delivering useful measurements while a Live Activity is active and another app is visible. **It does not detect obstacles or unpaired people. It is not part of the shipping NoBonk app.**

## Status and scope

- Minimum iOS 18.4. The iPhone 16 Pro Max and iPhone 16 Plus have second-generation Ultra Wideband hardware, but the app checks actual runtime capabilities and gracefully rejects devices without precise ranging.
- Separate application and Live Activity extension, separate bundle IDs, no main-app dependencies. XcodeGen project, no package dependencies.
- Local validation on September 7, 2026: **10 state tests passed; unsigned generic simulator and device builds passed with Xcode 26.6.** No compiler/build warnings remained. **No physical pairing, background runtime, Live Activity stop interaction, latency, battery, distance accuracy, or installation has been verified.** A successful build is not evidence of successful background ranging.
- Journal revision validated on September 7, 2026: **22 tests passed (10 session-state and 12 journal/lifecycle regressions); the unsigned generic iOS device build passed.** This revision did not launch a simulator, attach to a phone, sign, or install anything. Xcode logged unavailable CoreSimulator-service diagnostics during its generic-device build; there were no Swift compiler warnings or errors. Physical behavior remains unverified.
- Camera assistance and extended-distance mode are explicitly disabled. No camera, microphone, GPS, server, analytics, saved history or proximity notifications. Session event notes exist only in memory.
- Background instrumentation now keeps up to six frozen, in-memory trial reports. These are callback-receipt measurements between observed application notifications, not a claim of hardware acquisition timing, lock detection, or verified physical background operation.
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

Journal tests exercise the actual synchronous notification observer, receipt-time cutoffs even when a newer callback acquires the lock first, old-session callbacks and failures after restart, delayed foreground UI work, Stop/failure retention, re-arming, duplicate/old lifecycle transitions, missing readings, suspension and interval-edge silence. They use a synthetic monotonic clock and no radio or hardware. They establish instrumentation behavior only.

## Session design

1. One tester creates a session; the other browses. Every session uses a random `Phone-XXXXXX` display name rather than the owner's device name.
2. The joining tester selects the matching name and confirms an invitation. The receiving tester separately approves the matching name. Invitations in the background, during another pairing, or from incompatible protocol versions are rejected. Neither side automatically accepts.
3. Multipeer Connectivity requires encryption. Peers exchange only versioned, size-limited, secure-coded `NIDiscoveryToken` archives; no account, device name, location or measurements are sent through this application protocol. Tokens are temporary and discarded on Stop.
4. Each side creates one `NISession`, runs a `NINearbyPeerConfiguration`, and waits for actual distance callbacks. The default legacy ranging mode is used. Direction capability is displayed as diagnostics but direction is never consumed.
5. A fresh reading enables the **Enable background experiment** button. Each tester presses this while foregrounded, then switches apps. The transport may disconnect in the background; this deliberately does not invalidate a successfully configured Nearby Interaction session.
6. Stop immediately invalidates the state generation, releases the NI/MPC sessions and discovery, rejects any pending invitation, and ends the activity. Old framework-object callbacks cannot mutate a new session. Pending activity updates check their lease and are ordered before activity termination. A Live Activity Stop intent requests the same teardown without opening the main UI. Ending/dismissing the activity also stops the experiment.
7. Framework suspension clears the reading. Only the same still-authorized session can resume after `sessionSuspensionEnded`; Stop or failure cannot revive it. A timeout, peer-ended removal or invalidation ends this experiment and requires explicit new pairing.
8. Once the activity is enabled and distance is fresh, select a planned **App switch** or **Device lock** trial, then **Arm next background interval**. `didEnterBackgroundNotification` starts that one interval. `willEnterForegroundNotification` synchronously freezes it before the UI's asynchronous scene change. Stop or NI termination freezes it earlier, with that distinct end reason. Re-arm for each subsequent interval. The selected label is the tester's plan; the app does not infer device lock from an ordinary background notification.

The encrypted transport does not establish a verified real-world identity or implement a cryptographic comparison-code ceremony. The temporary names and two approvals are suitable for a supervised, nearby experiment with a known partner; do not present this as a production identity/authentication design.

## Freshness and measurement interpretation

- Times for freshness are monotonic and captured when the NI callback arrives, before its actor hop. Wall-clock dates are used only to set the Live Activity's `staleDate`.
- The trial journal records a callback synchronously under a lock and binds it to its producing `NISession` identity. It counts callbacks containing a finite, nonnegative distance from this session's single peer configuration. NI does not supply a sensor acquisition timestamp here: these counts cannot establish when the hardware originally measured the distance. No callback for another session, before the interval, after Stop, or at/after the foreground boundary can freshen the completed background report. A callback that loses the journal lock to foreground freezing is conservatively omitted. The foreground handler also applies its timestamp cutoff if a newer callback or termination won the lock first; resumed UI readings cannot rewrite the resulting report.
- Each report shows elapsed interval, valid-distance callback count, count after the first second, unavailable callbacks, suspensions/resumptions, age of the last background distance at the interval's end, and longest silence. Silence includes both the leading and trailing gaps; an interval with no valid distance has its full duration as silence and **no** fabricated distance age. A foreground reading just before or after the trial cannot shorten these values.
- The first-second exclusion helps distinguish transitional delivery from longer continuation. A nonzero count after one second still does not prove sustained delivery: inspect trailing age, largest silence, actual Live Activity behavior and both phones' reports for the entire interval. Reports ending on Stop/failure describe only the measured portion; they must not be presented as completing the planned duration.
- Receipt metadata is held only until the foreground boundary, and six compact reports are retained in memory. An accidentally long interval stops recording at 100,000 receipt events with an explicit **Journal capacity reached** reason; it does not silently truncate and claim complete coverage. This ends instrumentation, not the consenting NI session.
- No callback means the reading expires after two seconds. Nil or invalid distance clears it. No measurement is never displayed as zero.
- The foreground uses a SwiftUI timeline to reevaluate freshness; this is not a background keepalive. The activity's `staleDate` marks cached readings stale without inventing new samples. The OS controls Live Activity presentation timing; physical validation must check the rendered stale state.
- Updates to the Live Activity are limited to at most one per second for valid data, with immediate clearing on unavailable/suspended data. This throttle is an application choice, not a guaranteed NI cadence.
- The largest observed gap spans accepted samples, including a resumed suspension. Sampling is optional and can stop entirely; there is no guaranteed rate, range or duration. Obstruction, body position, competing UWB sessions and system policy can affect it.
- Distance to one token-bearing phone is not obstacle awareness. It cannot reveal a wall, a person without the paired app, a safe path, heading or a reliable time to collision. Do not walk distracted or stage collisions while testing.

## Physical test gate

Use the iPhone 16 Pro Max and 16 Plus after installing the separate app through an explicitly chosen signing workflow. Record both iOS versions, battery levels and relevant permission settings. Launch from the Home Screen **without the debugger attached** for all background trials.

1. Enable Wi-Fi and Bluetooth. On one phone create a session; on the other find it. Read the temporary names to each other. Confirm both sides, allowing Local Network and Nearby Interaction when asked.
2. Keep both apps visible and phones unobstructed, approximately 1–3 meters apart. Confirm nonzero sample counts and fresh values change with a small, safe separation change. A blank UI is not a pass.
3. Enable the Live Activity on both phones while both apps are still visible. On each phone that will leave the app, select **App switch** and **Arm next background interval**. Switch one phone to another app, then both. Re-arm for separate 30-second, 2-minute and 10-minute trials. On returning, record the **frozen report** immediately, including its end reason, duration, callbacks after one second, last-distance age and longest silence. The live foreground sample count can grow again and is not evidence for the preceding interval. Note independently whether the Live Activity showed changing values or became stale.
4. Select **Device lock** and re-arm for fresh trials with one locked phone, then both. Keep the planned lock and app-switch reports separate; the label is not OS confirmation that the device was locked. Record what the testers actually did and compare both phones. If a trial fails or is stopped early, its report must retain that shorter duration and terminal reason.
5. Repeat with a body between the phones, portrait/landscape, pockets, Low Power Mode, and another UWB feature such as Find My. Record limitations instead of promising performance. Stop safely if the signal becomes stale or either tester is uncomfortable.
6. Stop from each phone's main UI and from each Live Activity. Verify the stopping phone releases ranging and removes the activity, shows no fresh distance afterward, and never restarts on foregrounding. The other phone may receive a peer-ended callback or may instead become stale; peer-ended delivery is not guaranteed.
7. Decline an invitation; cancel while it connects; tap Stop while receiving measurements; immediately create a new session; leave and return; revoke permissions; dismiss an activity; force-quit and relaunch. None may resume an old session. Denied permission must produce a useful error without a crash. Review UI confirmation and activity button behavior on the actual devices. A new pairing clears old reports; preserve the previous results before doing that. Force-quit also loses the in-memory reports.

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
