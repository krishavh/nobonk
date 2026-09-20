# Walking mode — experimental Android closed test

Walking mode is an optional, default-off setting, accessible directly from the setup controls. It is not an always-on automatic launcher. Enable motion access, allow the detection notification and overlay, then explicitly tap Arm walking mode with NoBonk visible. The camera foreground service is promoted before the Activity moves to the background. Camera and service model initialization wait for at least 16 fresh step-detector events spanning 20 seconds, with no gap over 3.5 seconds. This is an estimate of sustained walking, not a guarantee.

After scanning starts, the step monitor stays active. Sixty seconds without a fresh step pauses scanning, silences cues and releases the camera/model. Twenty seconds of fresh sustained walking qualify another scan; brief stops do not toggle the camera. The same explicitly armed foreground service remains active, with Waiting or Paused / camera off / Stop notifications. Motion cannot distinguish outdoors from indoors: no location is used by this mode. Stop, disabling the pending mode, or opening NoBonk ends the session and cancels the walking monitor and pending startup. Scanning never restarts after process death or reboot. The saved preference is not saved session authorization. Each waiting period (including after an automatic pause) expires after 30 minutes of elapsed time; idle cleanup occurs on the next process wake. Keep the screen on for more reliable sensor delivery. Missing sensors and denied permissions preserve manual scanning as the alternative.

## Validation and limits

JVM tests exercise fresh/stale/duplicate/slow steps, gaps, repeated start/pause cycles, Stop at every phase, old-frame/model/camera callbacks across generations, and per-engine retirement races. Debug and release build checks do not prove physical step sensor or delayed-camera behavior. Pixel and Samsung physical-device testing is still required; no Android phone was connected during implementation. Closed testers should treat this option as experimental and keep looking up.

Suggested trial in a safe, open space: arm; verify camera indicator is off while waiting; walk naturally for 20–30 seconds with screen on and rear camera uncovered; verify camera indicator and scanning status appear; stand still for at least 60 seconds and verify camera off / Paused; walk again for 20–30 seconds and verify scanning returns while another app stays open; Stop from notification, then walk again and verify it stays stopped. Also Stop during waiting and reopen the app while waiting: neither should start the camera. Retry with denied motion permission and verify manual background scanning remains available. Do not deliberately approach obstacles or test in traffic.

## Contribution and review

Astra implemented Android service, permission, setup and website changes. A local Kaaval model (GLM-5.3-Flash, currently served in place of Qwen) generated lifecycle test proposals; supervised corrections removed invalid assertions and concurrency deadlocks. Fable 5.1 critiqued the design. No credentials, user messages or camera data were sent to these models.

References: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start and https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion . Delayed camera binding within the same already-authorized camera foreground service is an implementation inference that still needs device validation, not a promise of automatic background camera access from a stopped app.

## September 20 implementation review

Fable 5.1 High critiqued repeated camera reopening, sensor batching, Stop, timer boundaries and resource ownership. Astra owns implementation and verification. The requested 20/60-second rules are retained; speculative central-box/size thresholds were not adopted because they could hide real people. Google documents while-in-use camera restrictions and recommends foreground-service sensor use, but these documents alone do not prove delayed/repeated camera access on every phone. Physical validation remains explicitly separate from emulator and replay evidence.
