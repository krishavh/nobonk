# 1.0.13 (versionCode 14) — September 7, 2026

- Reuse native input/output tensor storage through model warmup and scanning, removing the large copied output array on each frame. Verify provider selection and both Fast/Sharp models through the same inference path.
- Refresh battery state during use. Foreground scanning pauses below 10%, clears stale alerts and releases the camera and sensors; recovery respects a user's Stop. Background scanning keeps its existing reduced-cadence policy.
- Release foreground sensor and cue ownership when the setup screen is hidden or hands off to background mode. Reject late results across Stop, screen changes and power pauses, and ignore duplicate background-start commands.
- Use elapsed time for scanning and alert intervals so clock changes do not stall detection. Reset and synchronize motion state between sessions.
- Verified with 151 unit tests, native model-output parity tests and actual emulator camera/background/Open/notification Stop flows. Physical-phone startup, temperature and battery-energy measurements remain separate; no measured phone-speed claim is made.

---

# 1.0.12 (versionCode 13) — September 7, 2026

- Correct the class mapping against the bundled YOLO model metadata: cats use COCO class 15, dogs remain class 16, and class 17 (horse) is excluded from the selected classes. Previously, cats were omitted and horse detections could be labeled as cats.
- Share raw-head class selection and labels in the decoder. Regression tests feed model-shaped tensors through that production decoder and NMS using class IDs extracted from the pinned model metadata; they cover cats, dogs, horse exclusion and existing people/vehicle classes.
- Model weights, confidence/NMS thresholds, scanning lifecycle and launcher artwork are unchanged. This is a separate follow-up to version 1.0.11; the submitted vc12 artifact is not replaced.

---

# Review release — 1.0.11 (versionCode 12), September 7, 2026

- Faster repeat startup: cache the measured execution-provider choice per model/device/runtime/app version, verify it before reuse, rebenchmark on failure or expiry. Fast is the initial model for new installations; saved preferences are preserved.
- Correct provider labels: NNAPI is not advertised as proof of NPU execution. Direct input buffers reduce avoidable tensor copies.
- Serialize foreground model replacement, inference and cleanup; invalidate stale model jobs; keep Stop effective during startup. Show permission and camera recovery screens instead of a blank or misleadingly active view.
- Keep warning overlays translucent and clear of the Open NoBonk control; reflow on rotation. Report inference failures instead of treating them as empty scenes.
- Wire backup exclusions for private files/preferences/databases. Fix legacy encryption initialization, queued history writes after Clear, bounded history compaction and truncated append-log recovery.
- Reuse the corrected Blender brand icon inside the app. Clarify approximate alert sensitivity and possible-obstacle wording.
- Install checksum-verified model assets before CI builds. Correct model and privacy documentation.

Physical-device camera timing, accuracy, thermal behavior and Google Play installation/update checks remain required. This source entry does not mean the release has passed Play review.

---

# Changelog

## 1.0.14 (version code 15) — 2026-09-07

- Stop and model replacement now cancel remaining startup provider probes between native calls.
- A new Start owns its loading state; an older cancelled job cannot reset it.
- Ready models remain reusable after Stop/Start. No provider preference, model, permission or detection-threshold changes.
- Validation and native-call limits: [startup cancellation review](STARTUP_CANCELLATION_REVIEW_2026-09-07.md).

## 1.0.10 (versionCode 11) — 2026-09-07 reliable Stop · edge indicator · Play update suggestion
- **Slim screen-edge indicator replaces the top scan bar in background mode.** Four 3 dp non-touchable strips just inside the status-bar/cutout and gesture-bar insets; static (no animation) — calm mint while watching, amber on MEDIUM, red on HIGH, grey when the camera is blocked (`service/EdgeIndicatorPolicy.kt`, tested). The Open NoBonk pill and the notification Stop are unchanged; the red warning text still appears for HIGH.
- **Google Play flexible in-app update suggestion** (app-update-ktx 2.1.0). A quiet check runs when the app resumes with the gate cleared; a dismissible *Update available* / *Update downloaded* card is shown **only while nothing is scanning** (never over the safety gate, never during foreground or background scanning). *Later* snoozes for a day and remembers the dismissed version; declined/failed flows are quiet; a downloaded update is applied only by a user tap when idle; no Play (sideload/emulator) means no suggestion. About → *Check for updates* + a plain-language note on what Play processes (`update/UpdatePolicy.kt` tested with fake states; real-device validation is Play-installed builds only).
- **Review follow-ups (18:44):** engine release is a deferred ownership hand-off (whoever finishes last closes; no timeout), frame coroutines start ATOMIC so a cancelled launch cannot hold the single-flight gate; foreground Stop cancels a playing chirp/speech/vibration; cue emission is validated per frame inside the engine (`Config.cuesAllowed`), so Stop+Start cannot un-mute a stale inference; the ViewModel re-checks the generation at the main-thread publication boundary and commits history only for the owned session. Cue emission itself now runs on the main thread with the validity checks inside that block, so it serializes with Stop/silence (no chirp can start after a Stop); the Test chip follows the same rule. Edge strips use window alpha 0.75 (under Android's obscuring-touch limit) with explicit side heights and no corner overlap; the update coordinator guards late callbacks after dispose, honours the flow's start result and refreshes the single-use update info; *Later* is a plain day snooze; no-Play reports "couldn't check", never "latest". Service HUD/edge/notification publication is one main-thread block with the lifecycle check inside (serialized with Stop); foreground Stop also stops the motion/angle sensors, Start resumes them. Edge strips are re-laid out on service configuration changes (another app may rotate the display during a background session), preserving the current colour state.
- **Root cause.** Stop only called `stopSelf()`. Work queued before the Stop kept completing after it: a frame in flight re-posted the alert notification and re-created the red warning overlay from an already-destroyed service (so Stop "did not work"), and a Stop during the multi-second model load left the loaded model and sensors alive, then bound the camera anyway.
- **Fix.** `service/ServiceLifecycle.kt` — an explicit, unit-tested state machine every asynchronous step consults: model-load completion, camera binding, frame processing, HUD/notification posting and cues are all refused once Stop has been requested, from any phase. One idempotent `shutdown()` path (app Stop, notification Stop, bind failure, onDestroy): cancel the startup job, halt the engine (cues, speech, vibration, sensors), clear the analyzer and unbind the camera, drop queued main-thread work, remove warning/scan/return overlays, `stopForeground(REMOVE)` + cancel the notification, then `stopSelf()`. `ACTION_STOP` returns `START_NOT_STICKY`; a start after Stop on the same instance is refused.
- **No silent resume.** Stop in the app stops the background service *and* foreground scanning (camera released, dock shows *Stopped — tap Start scanning*); Stop from the notification is remembered so returning to NoBonk shows the stopped state until the user presses Start. Returning to a live session via the pill still hands the camera over (not a user Stop). Safety gates unchanged.
- **Review follow-ups (Astra):** lifecycle transitions synchronized (a Stop can never be overwritten by a late model-load completion); engine adoption happens on the main thread inside a non-cancellable block so a Stop racing the load either closes the just-loaded engine or finds it owned; the ONNX session is released only after the in-flight inference finishes (bounded wait on a daemon thread); the foreground pipeline is generation-tagged (`ml/ScanSession`) and the engine muted on Stop, so a frame already in inference cannot post results, cues or history, and Start begins a fresh generation; the preview guards its async callbacks against disposal and unbinds only its own use cases (the service likewise unbinds only its analysis use case); the activity no longer creates a service just to stop it.
- Tests: `ServiceLifecycleTest` (rapid Stop during model load, Stop during camera binding, in-flight frame after Stop, start-after-stop refused, repeated cycles, hand-off vs user, idempotence, 300-iteration real-thread Stop/onModelLoaded race), `ScanSessionTest`.

## 1.0.9 (versionCode 10) — 2026-09-06 every-launch reminder on warm reopen
- On Android 12+ Back moves the root task to the background without finishing, so a warm reopen gets no `onCreate`. The gate now resets in `onStop` (unless a configuration change, a hand-off we started — permission dialog, overlay-settings screen, *Run in background* — or an authorized background session) and is re-evaluated in `onStart`, so Back/Home → launcher → reopen shows the reminder. Exercised on an Android 15 emulator profile.

## 1.0.8 (versionCode 9) — 2026-09-06 safety prominence (UI only)
- Full notice: the safety warning and the acknowledgment checkbox are now the first content at the top (below a compact wordmark); feature cards follow. Reminder: warning at the top with **OK — continue** directly under it. No text or gate changes.

## 1.0.7 (versionCode 8) — 2026-09-06 final closed-test candidate
- **Corrected launcher icon** (lower-right bracket now inward-facing); same #101B1E background, no other artwork changes.
- **Gate edges from review of vc7:** saved instance state is trusted only with a matching per-process token, so a task restored after process death shows the reminder while same-process rotation preserves it; an explicit `ACTION_START` now requires the gate cleared in this launch (plus the current notice version), while a sticky null-intent restart of an already-authorized session is allowed on the persisted version alone. Tests added for both.

## 1.0.6 (versionCode 7) — 2026-09-06 gate hardening (review of vc6)
- Reading the full notice from the reminder no longer acknowledges anything; Back returns to the pending reminder and only **OK — continue** clears it.
- Camera permission is requested only from the OK / accept callbacks (or on return to a live authorized session), never from a persisted version alone; camera start and background start are guarded by the same per-launch gate.
- "Every launch" means every launch: finishing the app (Back / Not now) or stopping an idle background session resets the gate; only configuration recreation (saved state) and returning to a live, authorized background session preserve it (`safety/AckGate.kt`, `AckGateTest`).
- Service marks itself active only after an authorized, successful start (not for a stop-only instance) and refuses to start detection if the current notice version is not acknowledged.

## 1.0.5 (versionCode 6) — 2026-09-06 startup safety notice with explicit acknowledgment
- **Full safety notice before anything else.** New wording (experimental student-built tool; can miss or misidentify hazards; no alert does not mean the path is clear; never rely on it for roads, driving, cycling or dangerous areas; not a certified safety device). An initially **unchecked** checkbox ("I understand that NoBonk may fail to warn me…") enables *I understand — continue*; *Not now* exits. Camera permission, camera start and background detection are all gated on this acknowledgment (`safety/SafetyNotice.kt`).
- **Versioned local acknowledgment** (`safety_ack_version` in app-private prefs, no upload/analytics): fresh installs and upgrades from earlier builds that only stored `first_run_done` see the notice once; a future wording change bumps the version and re-asks.
- **Mandatory reminder on every launch.** After acknowledgment, every genuine cold app launch shows a concise *Stay aware* card that blocks until **OK — continue** is pressed (never auto-dismissed), with a link to the full notice. It is not shown again within the same process (rotation, History/About), nor when returning to a live background session via the Open NoBonk pill or the notification.
- Full notice is always readable under About → Safety notice. Onboarding intro reworded modestly (no "before you bump into someone").
- Tests: SafetyNoticeTest (fresh install, migration, unchecked/decline, version bump, cold launch, rotation/return, background return, gating, no-guarantee wording).

## 1.0.4 (versionCode 5) — 2026-09-06 final closed-test candidate
- **Launcher icon replaced again** with the approved crisp matte Blender artwork (angular path arrow, obstacle cube, bracket corners) on a #101B1E background; vc4's rounded-tube icon was rejected and that release is marked do-not-upload.

## 1.0.3 (versionCode 4) — 2026-09-06 (published, icon rejected — do not upload)
- **"Open NoBonk" return control in background mode.** A small green pill at the top-end of the screen (its own tiny overlay window, so only the pill takes touches; the app underneath keeps working) stays available for the whole background session, between alerts too, inset below the status bar/cutout with a 48 dp touch target. Tapping brings the existing NoBonk task forward; the activity takes over the camera from the service (single camera client, detection continues in the foreground). Removed when the service stops (Stop action or returning to the app). Notification tap and Stop remain as fallback. The red warning line now sits below the status bar and leaves room for the pill.
- **New launcher icon.** Gender-neutral Blender artwork (winding path, obstacle, awareness arc) replaces the walking-person icon: adaptive foreground (transparent render, safe-zone fitted) on a deep-forest #0B1716 background (rejected), themed monochrome layer derived from the foreground silhouette, legacy rounded/circular mipmaps at every density, and the Play 512 store icon — all generated by `scripts/make_launcher_icons.py` from the published masters (checksums in the commit).

## 1.0.2 (versionCode 3) — 2026-09-06 review follow-up
- **History rows can no longer scroll under the system bars.** vc2 put the status-bar padding inside the scrolling header, so once the header scrolled away the Tap location / Clear history row sat under the clock again. The inset now lives on the LazyColumn viewport (`windowInsetsPadding(systemBars)`), with the header's duplicate padding removed.
- **Plain wording.** "camera heuristic" replaced with *surface warning* and "no object recognised" with *object not identified* in the dock and banner.

## 1.0.1 (versionCode 2) — 2026-09-06 closed-test fixes from Pixel 9a footage
- **History screen respects system bars.** The header row (Tap location / Clear history) sat under the status-bar clock and icons on edge-to-edge Android 15; it now uses status-bar insets, and the list ends above the gesture bar. About screen gets the same bottom clearance.
- **Heuristic vs recognised object, worded truthfully.** When the wall/ground heuristic fires with no recognised object, the dock says *Possible obstacle ahead · camera heuristic · no object recognised* and the banner says *Possible obstacle — wall-like surface ahead · camera heuristic, not a recognised object*, so the two panels no longer contradict each other.
- **First names only** in the in-app About screen (Krishav; Haarith, parent and Play account holder).
- No detection, alert or privacy behaviour changes. Backup-exclusion filename update still queued (out of scope for this build).

## Next maintenance build (queued, not in 1.0 vc1 @ 2a4092b)
- **Backup-exclusion rules name the old file.** `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` exclude `detection_events.json`; the history file has been `detection_events.enc` since encryption-at-rest landed. No exposure today (`allowBackup="false"` disables backup/transfer outright, and the file is AES-256-GCM encrypted with a non-exportable Keystore key), but update both rules to `detection_events.enc` (keep the `.json` line for legacy installs) in the next build. History stays.
- **First names only in the in-app About screen.** `ui/LicensesScreen.kt` still shows full names for the author and account holder; change to "Krishav" / "Haarith (parent and account holder)" to match the README and privacy policy. Public contact: support@genwhy.ai.

## 1.0 (unreleased) — 2026-09-06 "look up" pass

- **Maker credit.** A small "BY KRISHAV" line sits beneath the History/settings controls in the dock, always visible while the app is open (TalkBack: "Made by Krishav").

### Review fixes (2026-09-06 evening, from Astra's source review)
- **Boxes line up with the preview.** Preview and ImageAnalysis now share one `ViewPort` (`UseCaseGroup`), and the frame path honours the analysis `cropRect` (`FrameGeometry` crop-aware overload), so normalized boxes map 1:1 onto the FILL_CENTER preview on tall screens. Previously the overlay assumed the analysis frame and the preview had the same field of view.
- **Honest status wording.** The dock says *No objects detected* (grey) instead of *Clear ahead* (green), and shows *Starting… / Camera blocked / Point phone forward / Paused — battery too low* whenever the pipeline is not actually watching.
- **Readable labels.** Label pills are 12 sp (honour font scaling), density-based padding/strokes, and are clamped inside the canvas (right-edge objects no longer clip their name/distance).
- **HIGH re-alert mute only starts when the cue fires.** A HIGH seen at a bad camera angle no longer consumes the 2 s mute window, so the first audible alert after correcting the angle is immediate (`ml/HighReAlertMute.kt`, regression-tested).

### Alerts & battery (2026-09-06, iteration 2)
- **Directional alert cues.** The notification ringtone is gone. NoBonk now synthesises a short rising chirp (HIGH = urgent triple, MEDIUM = softer double; LOW stays haptic-only) and pans it toward the hazard: something on your left is heard on your left (real spatial cue with earbuds, graceful mono on a speaker). Constant-power pan law, 20 % centre dead-zone, never clips (`ml/AlertCue.kt`, unit-tested). Plays on the accessibility-assistance audio usage so it sits over music without hijacking the alarm stream.
- **Sound / haptics toggles** in the settings drawer, and all settings (alert distance, model, detect scope, cues) now persist across launches.
- **Adaptive frame cadence.** Full rate while anything is in frame or alerting; backs off to ½ after 3 s of empty frames and ⅓ after 12 s, stretched 1.5× under 20 % battery — never below ~2 fps, and the first sighting snaps straight back to full rate. A covered lens (pocket, hand) idles at 2 fps until light returns, and so does a phone held still for 10 s with nothing in frame (raw-accelerometer motion gate, `ml/MotionGate.kt`).
- **▶ Test** chip in the Cues row fires the HIGH haptic + chirp + voice once, so users know what an alert feels like and testers can check cues alone. Applies to both the foreground screen and the background service (`ml/FrameCadence.kt`, unit-tested).
- **Which side.** The LOOK UP screen now says *PERSON ON YOUR LEFT / RIGHT / AHEAD*, shows a side arrow and lights the hazard's edge of the screen, so peripheral vision gets the direction even before you read it. TalkBack gets the same sentence.
- **Per-phone distance calibration.** The distance label used a hard-coded focal length; NoBonk now reads the bound camera's lens focal length and sensor size (Camera2 characteristics) and derives the normalized focal for this phone (`ml/CameraIntrinsics.kt`). Falls back to the old constant when the camera reports nothing.
- **Calm boxes.** Drawn bounding boxes are EMA-smoothed per track (faster alpha while approaching); the alert ladder still uses raw boxes so no latency is added to safety logic (`ml/BoxSmoother.kt`).
- **Voice cue (opt-in).** "Person on your left. Look up." spoken through the phone's offline text-to-speech on each new HIGH hazard, for blind/low-vision walkers and pocket-and-earbuds use. Off by default; toggle in the settings drawer (`ml/VoiceCue.kt`).
- **Background notification** now reads "NoBonk is watching", is silent, and has a **Stop** action.
- **Night boost.** When the scene is dark (mean luma < 60) the detector input is brightened toward the training distribution with a bounded linear gain (≤ 2.5×) in the same Canvas draw that rotates and scales the frame — zero extra passes. The low-light banner says when it is on. Aimed at the "3/10 in low light" limitation; needs on-device re-measurement.
- **Live stats** under the status pill: processed fps and detector latency in ms.
- **About / privacy / licenses** reachable from the settings drawer (was History → About only).

### Performance
- **Execution provider chosen by measurement.** Every provider that can run the graph (NNAPI, XNNPACK, CPU) is timed for three inferences at load; the fastest wins, with XNNPACK preferred unless an accelerator is ≥15 % faster (`ml/EpChooser.kt`). On Android 15+ (where NNAPI is deprecated and often slower than XNNPACK on Tensor phones) XNNPACK is tried first. Log line `Execution provider chosen by measurement`.
- **Zero-allocation frame path.** CameraX RGBA plane → reusable raw bitmap → one Canvas draw (rotate + downscale) into a reusable work bitmap. Three Bitmap allocations per frame removed; steady-state GC churn gone. Pure `FrameGeometry` helper with unit tests.
- **YOLO26 only, raw head + in-app NMS.** `Fast` (yolo26n, 9 MB) and `Sharp` (yolo26s, default). Measured 1.5× faster than the end-to-end export on CPU and more accelerator-friendly. yolo11s (36 MB, unused by default) and un-bundled picker entries removed. See `docs/MODEL_CHOICE.md`.

### Design
- New design system (`ui/theme/Theme.kt`, `ui/components`): one colour language (cyan system · green safe · amber watch · rose act-now), glass panels, consistent type and shapes.
- Detection screen rebuilt: corner-bracket boxes with soft fill and breathing "approaching" ring, danger vignette on HIGH, top status pill (live dot · NPU/CPU · model · battery), a **bottom control dock** with the nearest object and a proximity meter, alert-distance chips, collapsible model/detect settings, and clear actions. Unified notice banners; restyled LOOK UP, warm-up and camera-covered states; drawn wordmark.

### Release engineering
- ONNX Runtime 1.21.1 → 1.29.0 (all native libs 16 KB-aligned; Play requirement). Per-ABI sideload APKs; AAB for Play.
- GitHub Actions CI: unit tests, debug APK, unsigned release AAB, 16 KB check.
- README model section and acknowledgments brought current; `docs/MODEL_CHOICE.md` added.
