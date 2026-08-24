# NoBonk — Round-2 findings (safety calibration + real-world usefulness)

From an expert calibration/UX pass on the rewritten core. These make the app *trustworthy*, not just *reachable*. Implement in priority order.

## Geometry baseline (auditable)
distance `d = 1.479 / fill` for a person (focal 0.87 ≈ 60° VFOV). Reaction budget for a distracted walker ≈ 1.2–1.6 s. Pipeline latency (10fps + 2-frame hysteresis + 700ms linger + inference) already eats ~0.2–0.4 s.

## CRITICAL — wire the collision signal that's currently dead code
- **`ApproachTracker` computes `imminent` (TTC) and exposes `isImminent()`, but NOTHING consumes it.** `DetectionEngine` only reads the `approachingIds` boolean and calls `escalate()` (one level). **Fix: force HIGH when tracker TTC ≤ 1.5 s** (raise from the internal 1.2 s). This is the only way to get head-on lead time without alarming the whole sidewalk.
- At default preset, fill-only HIGH (0.70) fires at ~0.75 s to a head-on collision — too late after latency. **Lower the fill-only HIGH backstop to 0.60** (person); keep the 0.85 cap so a frame-filler always hits HIGH. Suggested person ladder: HIGH 0.60 / MED 0.42 / LOW 0.28.
- **Vehicles/bikes MUST be TTC-driven** (fill can never be early enough at 5–10 m/s).

## HIGH — kill false-alarm fatigue (crowded sidewalk = spam today)
- **No trajectory test today** → walking toward a group flags everyone `isApproaching`. **Add constant-bearing gating:** only escalate / allow HIGH when the box center stays within a central collision cone (|centerX − 0.5| < ~0.15) across the tracked frames (an object you'll pass drifts laterally; one that'll hit you stays centered). Biggest false-positive killer, cheap with the existing tracker.
- Raise `minClosingFillRate` from 0.04 to ~0.10–0.15/s so only genuine fast-closers count.
- Reserve full-screen "LOOK UP!" + sound for HIGH that is BOTH imminent (TTC) AND on-bearing; LOW/MED = haptic only.
- After a HIGH on a track, mute re-alert on that same track for ~2 s.

## HIGH — real-world coverage gaps
- **Vehicles OFF by default:** `isObjectDetectionEnabled` / non-person default is `false`, but the marketing promises walls/vehicles/obstacles. Turn on vehicle detection by default (or make the copy honest). At minimum default-include cars/bikes/buses.
- **Phone-angle NOT gated in the background service** (`SensorMonitor` runs only in the foreground ViewModel; `DetectionService` never uses it). Move angle logic into `DetectionEngine`; in the service, suppress/annotate alerts when angle is BAD (phone flat / pointed at ground).
- **Reliability indicators:** add a visible "low light — reduced reliability" banner and a "bad angle — point forward" cue, since detection silently degrades in exactly the risky cases.
- Known silently-missed hazards to note in-app/limitations: glass walls, poles/posts (not COCO classes), curb step-UPs, fast cyclists/scooters.

## PRODUCT / UX for Play
- **First-run rationale screen** before camera/overlay/notification permissions: why the back camera runs continuously, nothing is recorded, and the prominent **"assistive backup, not a certified safety device — keep looking up"** disclaimer (surface at runtime, not just the store).
- **Accessibility (weak today):** warning icons have `contentDescription = null`; alerts encode meaning in color + emoji only. Add real contentDescriptions + text labels; don't rely on color (colorblind) or emoji (TalkBack). Bump the HUD overlay text from 12sp to ≥20sp bold high-contrast on a solid backing bar (must be glanceable in motion). Document the 3 haptic patterns so users learn them.
- **Battery messaging:** state expected drain (~8–12%/hr continuous camera+AI) in the listing to pre-empt 1-star reviews.

## Also finish (infra carryover)
- Finish XNNPACK EP + honest EP label (in progress), encrypted history at rest, append-only single-event writes, `Log.*` guarded by `BuildConfig.DEBUG`, in-app `LicensesScreen` (AGPL §13), remaining dead-code/imports.
- Green `assembleDebug` + `assembleRelease` (R8) + `testDebugUnitTest` must all pass, with new tests for TTC-drives-HIGH and constant-bearing suppression.

## Honest bar
Round 1 made the alarm *reachable*. Round 2 (TTC-drives-HIGH + constant-bearing gating + vehicle default + angle gating + reliability indicators + loud disclaimer) makes it an honest, shippable "helpful nudge." Without them it over-promises on the exact head-on/cyclist cases users would trust.
