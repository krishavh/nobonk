# Changelog

## 1.0 (unreleased) — 2026-09-06 "look up" pass

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

