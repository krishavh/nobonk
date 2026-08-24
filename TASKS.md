# NoBonk — Fix Backlog (agent-pickable)

**How to use:** Each task is self-contained. Before starting, set `Status: CLAIMED (<agent>)`; when done, `Status: DONE` + one-line note. **Respect `Files` + `Conflicts` to avoid stepping on another agent.** Work on a branch; small, tested commits. Do NOT change `applicationId`, licensing, or delete features without a task saying so.

Legend — Priority: **P0** safety/release-blocker · **P1** high · **P2** medium · **P3** cleanup.
Base path: `app/src/main/java/com/persondetection/android/`

---

## ⚙️ Coordination map (read first)
- **T-CORE (P0-3) unifies the two pipelines** into one `DetectionEngine` and touches `DetectionViewModel.kt`, `DetectionService.kt`, `ObjectDetector.kt`. **Most ML/perf tasks depend on it or edit those files — do T-CORE FIRST, solo.** After it lands, the ML tasks (T-ML-*) apply to the single engine.
- **Independent, safe to run in parallel right now:** T-REL-* (build/manifest/proguard/res), T-DOCS-* (new files), T-UI-CRASH (ui/HistoryScreen.kt), T-CLEAN-IMPORTS.
- Every Kotlin change must still **compile**; run `./gradlew assembleDebug` before marking DONE. If a build isn't possible in your sandbox, say so and hand back a diff.

---

## P0 — Safety-critical + release blockers

### T-ML-DISTANCE (P0) — Make the alarm ladder physically reachable
**Status: DONE** — alarm ladder now driven by frame-fill fraction (`ml/AlertPolicy.kt`), per-class ladders; unit-proven HIGH reachable for person AND car at 0.5/1/2/3.5m.
**Files:** `ml/ObjectDetector.kt` (`estimateDistance` ~350-379, `createDetection` box clamp), `viewmodel/DetectionViewModel.kt` (`getAlertLevel` ~514-530), `model/Detection.kt` (AlertLevel doc). **Depends on:** T-CORE (edits same files).
**Problem:** Distance saturates at `realHeight×0.87` (~1.48 m for a person), so HIGH (`<threshold×0.65`) is unreachable; non-person objects only alert `<0.8 m` (impossible → cars/bikes never warn). ML-01, ML-02.
**Do:** Re-scope proximity so imminent collisions are detectable — recommended: drive alert level off **box-fill fraction** (e.g. person box height > 0.6–0.75 of frame = HIGH) and/or true pixel height before the letterbox crop, instead of a fabricated meters value. Give **non-person objects a LOW/MED/HIGH ladder** too. Stop clamping `boxHeight` to 1.0 if you keep the ratio model.
**Accept:** A unit test proves HIGH is reachable for a person AND a car at every threshold preset (0.5/1/2/3.5). Manual: walking toward the camera escalates NONE→…→HIGH.

### T-ML-APPROACH (P0) — Wire "approaching" to real warnings + fix tracking
**Status: DONE** — `ml/ApproachTracker.kt` (best-IoU same-class + growth fallback + hysteresis); approach escalates alert one level in BOTH modes; tests prove fast-closer→HIGH and no false flag in a 2-person scene.
**Files:** `ml/ApproachDetector.kt`, `viewmodel/DetectionViewModel.kt` (alert path ~443-465), `service/DetectionService.kt` (~193 discards result). **Depends on:** T-CORE.
**Problem:** ML-03. `isApproaching` only draws a red ring; the Service discards it; track match (`IoU>0.3`, greedy, class-agnostic) breaks on fast approach and resets velocity to 0.
**Do:** (a) `approaching` must **escalate the alert** (bump level / fire overlay+haptic), foreground AND background. (b) Match by **best IoU among same-class tracks** (not first), remove matched track from pool. (c) Add hysteresis: require N consecutive approaching frames before asserting. (d) Use centroid+size gating so fast growers still match.
**Accept:** An object closing fast triggers an escalated warning within ~0.5 s in both modes; no false "approaching" from track swaps in a 2-person scene (add a test with synthetic tracks).

### T-CORE (P0) — Unify the two detection pipelines into one `DetectionEngine`
**Status: DONE** — `ml/DetectionEngine.kt` used by both VM + Service; background rotation bug fixed; config passed via intent extras; 6 duplicated funcs removed.
**Files:** NEW `ml/DetectionEngine.kt`; refactor `viewmodel/DetectionViewModel.kt` + `service/DetectionService.kt` to both use it; touches `ObjectDetector.kt`. **Conflicts:** essentially all T-ML-* and T-PERF-frame — **run this FIRST, solo.**
**Problem:** PERF-U01 + traceability. Foreground and background run diverged copies (rotation dropped in background → wrong distance; threshold 1.5 vs 2.0; JPEG q100 vs 70; approach discarded).
**Do:** Extract one engine owning: frame→bitmap conversion (single, WITH `rotationDegrees`), pre-scale once, detect, approach, wall/ground analyze, alert-level, haptics, sound. Both VM and Service call it and pass the same user config (accuracyMode, threshold). Fix the **background rotation bug** here. De-duplicate `imageProxyToBitmap`, `getAlertLevel`, `handleHaptics/triggerHaptic`, `playAlertSound`, class→label mapping.
**Accept:** One code path; background mode now honors accuracyMode + threshold and computes correct (rotation-aware) distances; `./gradlew assembleDebug` passes; both modes visibly detect+warn.

### T-REL-SDK (P0) — minSdk 35→29, targetSdk/compileSdk →36, desugaring, signing
**Status:** DONE (release-eng) — minSdk=29, compile/targetSdk=36, coreLibraryDesugaring + desugar_jdk_libs 2.1.5, credential-guarded release signingConfig (NOBONK_* from gradle.properties/env, no secrets), jniLibs useLegacyPackaging=false. NOT build-verified (sandbox has no JDK/SDK); run `./gradlew bundleRelease` on build machine. Manifest is core-agent's file — no FGS change needed (camera FGS already declared).
**Files:** `app/build.gradle.kts`, `AndroidManifest.xml`, `gradle/libs.versions.toml`. **Independent.**
**Do:** `minSdk = 29` (floor 26 for overlay; 29 = ~90%+ reach). `compileSdk = 36`, `targetSdk = 36` (new-app API-36 cutoff ~Aug 31). Enable `isCoreLibraryDesugaringEnabled = true` + `coreLibraryDesugaring(desugar_jdk_libs)`. Add a `signingConfigs.release` block reading creds from `~/.gradle/gradle.properties`/env (NEVER commit). Verify existing version guards cover lower APIs (POST_NOTIFICATIONS=T, channel=O, FGS start=R already guarded — confirm FGS-camera only enforced ≥34). Update README build cmd to `bundleRelease`.
**Accept:** `./gradlew bundleRelease` produces a signed `.aab`; app installs + runs on an API-29 emulator (FGS camera path tested).

### T-REL-ACCOUNT (P0, no code) — Publish under an adult's verified account
**Status:** DONE (release-eng) — documented in `docs/RELEASE_CHECKLIST.md` §1 (adult 18+ ID-verified account; Krishav credited as author in listing, not account holder; 13+ target, not Designed for Families).
**Do (doc only, in `docs/RELEASE_CHECKLIST.md`):** Note the Play account holder must be a parent/guardian (18+, ID-verified); Krishav credited as author in the listing, not as account holder. Target audience 13+/adult (NOT "Designed for Families").

### T-REL-LICENSE (P0, decision) — Resolve AGPL/YOLO model licensing
**Status:** DONE (release-eng) — decision recorded: **Option A, comply with AGPL-3.0** (keep repo public). Obligations written in `docs/RELEASE_CHECKLIST.md` §6 (tag exact commit per versionCode, publish exact .onnx or pinned export recipe, in-app licenses screen for §13) + README license section updated. Remaining human steps: commit model/recipe + tag release; ship T-DOCS-LICENSES screen.
**Files:** `docs/RELEASE_CHECKLIST.md`, `README.md`, in-app licenses screen (see T-DOCS-LICENSES).
**Do:** Choose: (A) ship AGPL — keep repo public, tag the exact commit for each versionCode, commit the exact `.onnx` (or the precise `yolo export` recipe), add in-app "Source/Licenses" link (satisfies AGPL §13). OR (B) retrain/switch to a permissively-licensed detector to avoid AGPL. Document the decision + the model provenance.
**Accept:** A written decision + the corresponding source/model-availability step in the checklist.

### T-SEC-LOCATION (P0) — Reconcile the "nothing recorded" story
**Status: DONE (code)** — FINE dropped from manifest; location tagging opt-in default OFF, requested in-context from history-screen callback. (README wording is the build/docs agent's file.)
**Files:** `AndroidManifest.xml`, `MainActivity.kt` (permission batch ~51-64), `viewmodel/DetectionViewModel.kt` (location tracking ~263-348), `data/DetectionEvent.kt`, `README.md`. **Conflicts:** T-SEC-ENCRYPT (same data files) — coordinate.
**Do:** Drop `ACCESS_FINE_LOCATION` (keep COARSE only). Make location-tagging **opt-in, default OFF**, requested **in context** (when the user opens the history map), from the permission-result callback (fixes SEC-N08 race). Update the tagline/README to the accurate wording ("No video or photos are ever recorded, stored, or transmitted; optional coarse location for the history map, on-device only, off by default").
**Accept:** Fresh install requests no location up front; with location off, `detection_events.json` has null coords; README claim matches behavior.

---

## P1 — High

### T-UI-CRASH (P1) — Fix GPS null-format crash in history
**Status: DONE** — guard is now `!= null` (smart-cast lat/lng) in `ui/HistoryScreen.kt`.
**Files:** `ui/HistoryScreen.kt` (~616-621). **Independent.** *(Confirmed by 3 agents.)*
**Do:** Change `if (session.startLatitude != 0.0)` → `if (session.startLatitude != null)` (smart-casts for the `%.4f` format). Same for any sibling `!= 0.0` GPS guard.
**Accept:** A session with null GPS renders without `"null, null"` and without crashing; add a preview/test with a null-coord `SessionSummary`.

### T-PERF-RGBA (P1) — Kill the per-frame JPEG round-trip (+ fixes color corruption)
**Status: DONE** — both ImageAnalysis builders use OUTPUT_IMAGE_FORMAT_RGBA_8888 + imageProxy.toBitmap(); NV21/JPEG path deleted.
**Files:** `ml/DetectionEngine.kt` (post T-CORE), CameraX builders in `DetectionScreen.kt` (~670) + `DetectionService.kt` (~159). **Depends on:** T-CORE.
**Do:** `ImageAnalysis.Builder().setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)`, then `imageProxy.toBitmap()`. Delete the NV21/YuvImage/JPEG path (fixes PERF-C02 stride color corruption). Set a small `ResolutionSelector` analysis resolution (you only feed 416 px).
**Accept:** No JPEG/NV21 code remains in the frame path; detection still works; measurably lower per-frame time.

### T-ML-LETTERBOX (P1) — Aspect-preserving letterbox + box back-mapping
**Status: DONE** — `ml/Letterbox.kt` (bilinear pad + inverse map); round-trip unit test. Boxes decoded in original normalized coords.
**Files:** `ml/ObjectDetector.kt` (preprocess + decode), `DetectionEngine.kt`, `ui/DetectionScreen.kt` (Canvas box mapping ~91-99). **Depends on:** T-CORE.
**Do:** Replace squish-to-square (nearest-neighbor) with letterbox (scale by min, gray pad, bilinear); record scale+pad; inverse-map decoded boxes; map into the PreviewView display rect so boxes line up on screen (fixes ML-04, ML-10).
**Accept:** Boxes visually align with people on the preview; distant/thin pedestrians detected better than before.

### T-ML-NMS (P1) — Sane NMS + confidence + per-class NMS
**Status: DONE** — `ml/Nms.kt` groups by true classId, iou 0.45, confidence 0.40; unit test for dup-collapse + distinct-class survival.
**Files:** `ml/ObjectDetector.kt` (`iouThreshold` ~51, `confidenceThreshold` ~50, `applyNMS` grouping ~300). **Depends on:** T-CORE.
**Do:** `iouThreshold ≈ 0.45`; `confidenceThreshold ≈ 0.4–0.5` (tune); group NMS by **true class id**, not the collapsed "object" display name (fixes ML-08, ML-13, ML-15).
**Accept:** No duplicate boxes on one person in a quick sample; distinct overlapping objects both survive.

### T-SEC-ENCRYPT (P1) — Encrypt history at rest (or drop coords)
**Status: DONE (release-eng)** — `DetectionRepository` now stores a Keystore-backed AES-256-GCM **append log** (`detection_events.enc`); key created/held via `androidx.security.crypto.MasterKey`, per-record AEAD (12-byte IV + tag). Not human-readable at rest. EncryptedFile intentionally not used (its Tink stream can't append — documented); per-record AEAD gives encryption AND append-only, and skips a corrupt record without losing the rest.
**Files:** `data/DetectionRepository.kt` (~23,42,85), `app/build.gradle.kts` (add `androidx.security:security-crypto`). **Conflicts:** T-SEC-LOCATION (same data). **Depends on:** decide with T-SEC-LOCATION whether coords stay.
**Do:** If any location is persisted, use `androidx.security.crypto.EncryptedFile` with a Keystore master key (ProGuard keep already present). Remove the stale "future SEC-04" comments in build.gradle:48 / proguard:57.
**Accept:** `detection_events.json` is not human-readable on disk; read/write round-trips.

### T-REL-PRIVACY (P1, docs) — Privacy policy + Data Safety + FGS declaration
**Status:** DONE (release-eng) — `docs/PRIVACY_POLICY.md` (camera in-memory only, optional coarse location off-by-default, no network/ads/3rd-party, contact placeholder), `docs/DATA_SAFETY.md` (No data collected/shared rationale + consistency check + FGS-camera Console justification), closed-test lead time noted in RELEASE_CHECKLIST §4. Human must publish policy URL + fill contact email.
**Files:** NEW `docs/PRIVACY_POLICY.md` (publish via GitHub Pages), `docs/RELEASE_CHECKLIST.md`. **Independent.**
**Do:** Write the policy (camera in-memory only; optional coarse location, on-device only, deletable; no network/ads/3rd-parties; student project; contact email). Draft the Data-Safety answers → "No data collected/shared" (offline; note on-device processing). Draft the Console **Foreground-Service (camera)** justification. Note the closed-test (12 testers/14 days) lead time.
**Accept:** Policy file + a filled checklist section an adult can paste into the Console.

### T-REL-16K (P1) — Verify 16 KB `.so` page alignment
**Status:** DONE (release-eng) — `docs/PLAY_16KB_CHECK.md` (steps + why) + `scripts/check_16kb_alignment.sh` (auto-finds aab/apk, checks every .so LOAD align >= 0x4000 via readelf/objdump). build.gradle sets jniLibs.useLegacyPackaging=false; ONNX RT 1.21.1 is 16KB-ready. Must run once on SDK machine (not runnable in sandbox).
**Files:** `docs/RELEASE_CHECKLIST.md`. **Independent.**
**Do:** Build the AAB, extract, check every bundled `.so` (ONNX Runtime, CameraX) is 16 KB-aligned (`objdump -p … | grep 'align 2\*\*14'` or Google's `check_elf_alignment.sh`). If any fails, bump ONNX Runtime to latest 1.2x; keep `useLegacyPackaging=false`, no `extractNativeLibs=true`.
**Accept:** All `.so` report 16K-aligned, recorded in the checklist.

### T-REL-ASSETS (P1) — Store assets + adaptive icon
**Status:** DONE (release-eng) — adaptive icon: `mipmap-anydpi-v26/ic_launcher.xml`(+_round) with fg/bg/monochrome layers (`drawable/ic_launcher_{background,foreground,monochrome}.xml`, prefixed to avoid collision; overrides existing PNGs on API 26+). `docs/STORE_LISTING.md`: short(≤80)+full(≤4000) desc, category Tools, 512²/1024×500/screenshot specs, IARC answers, 13+ audience, "not a certified safety device" disclaimer kept prominent, "helps warn" not "prevents". Binary graphics must be produced on device.
**Files:** NEW `res/mipmap-anydpi-v26/ic_launcher.xml` (+round, +monochrome), `docs/STORE_LISTING.md`. **Independent.**
**Do:** Adaptive icon (fg/bg/mono layers). Draft store listing: 512² icon, 1024×500 feature graphic, ≥2 screenshots, short (≤80) + full (≤4000) description, category **Tools**, IARC content-rating answers, contact email, target audience 13+. Keep the "not a certified safety device… keep looking up" disclaimer prominent (avoid "prevents collisions" → "helps warn").
**Accept:** Adaptive icon renders; listing draft complete.

---

## P2 — Medium

### T-ML-LOWLIGHT (P2) — Don't treat darkness as "camera blocked"
**Status: DONE** — `ml/LowLight.kt` requires low brightness AND low variance; engine batches getPixels; dark-structured scene stays live (tested).
**Files:** `ml/FrameAnalyzer.kt` (~161), `viewmodel/DetectionViewModel.kt` `computeCameraBlocked` (~491-506). **Depends on:** T-CORE.
**Do:** Require **low brightness AND low spatial variance** to declare blocked; a dark-but-structured scene keeps detecting (fixes ML-06). Batch pixel reads (see T-PERF-GETPIXELS).
**Accept:** A dim-but-visible scene still detects; a covered lens still shows the block overlay.

### T-ML-HYSTERESIS (P2) — Debounce alert level + ground-hazard persistence
**Status: DONE** — engine alert-level linger (700ms) stops overlay/sound flicker; ground-hazard persistence added in `ml/FrameAnalyzer.kt`.
**Files:** `viewmodel/DetectionEngine.kt`/`getAlertLevel`, `ml/FrameAnalyzer.kt` (ground). **Depends on:** T-CORE.
**Do:** Smooth distance per track; require N consistent frames + dead-band before escalate/de-escalate (fixes ML-11 flicker). Add persistence to ground-hazard like the wall path; keep "possible" wording (ML-12).
**Accept:** No per-frame LOW↔MED↔NONE flicker near a threshold; no single-frame ground-hazard spam on shadows.

### T-PERF-GETPIXELS (P2) — Batch pixel access + reuse bitmaps
**Files:** `ml/FrameAnalyzer.kt`, `viewmodel/DetectionEngine.kt`. **Depends on:** T-CORE, T-PERF-RGBA.
**Do:** Replace `getPixel()` loops with one `getPixels(IntArray,…)` into a reused buffer (PERF-C04); recycle/reuse the scaled bitmap (PERF-P01/M01).
**Accept:** No `getPixel(` in hot loops; no per-frame ARGB bitmap alloc leaks.

### T-PERF-INFER (P2) — XNNPACK EP + prune YOLO class scan
**Status: DONE** — EP tried NNAPI→XNNPACK→CPU, each verified with a warm-up inference before it's claimed (`activeExecutionProvider`); `isHardwareAccelerated` is NNAPI-only so the "NPU" chip never over-claims for XNNPACK/CPU. Class scan restricted to `RELEVANT_CLASS_IDS` (8 classes, not 80).
**Files:** `ml/ObjectDetector.kt` (EP setup ~78, `parseAllObjects` ~194-229). **Depends on:** T-CORE.
**Do:** Add XNNPACK CPU execution provider; treat NNAPI as best-effort and verify the active EP (don't show a false "NPU" chip). Restrict the 8400×80 scan to the ~5 mapped classes (PERF-P02, P04).
**Accept:** Inference runs with a verified accelerated EP; post-processing loop measurably shorter.

### T-PERF-PERSIST (P2) — Append-only history + lazy summaries + clear-history UI
**Status: DONE (release-eng)** — append-only single-event writes now done: each `addEvent` appends ONE encrypted record via `FileOutputStream(append=true)`; in-memory cache appended O(1); no full re-read/re-parse/rewrite per event (trim is the only full rewrite, ~once per 5000 events, via temp-file rename). Summaries computed on demand (History open) only. 'Clear history' button wired to `clearAll()`.
**Files:** `data/DetectionRepository.kt`, `viewmodel/…` (`logEvent`/`getRecentSessions`), NEW small "Clear history" button in `ui/HistoryScreen.kt` (wire the already-written `clearAll()`). **Independent-ish** (data + a UI button).
**Do:** Append one event per write (not full-file rewrite, PERF-P05); compute session summaries only when History opens; append to in-memory list in place. Add a **"Clear history"** control (needed for the privacy story + uses dead `clearAll()`). Handle whole-file corruption non-silently (backup/rename).
**Accept:** Logging an event doesn't rewrite the whole file or rebuild all summaries; user can clear history from the UI.

### T-SEC-LOGGING (P2) — Guard release logging
**Status: DONE (release-eng)** — new `util/Dbg` facade whose every method is guarded by `BuildConfig.DEBUG`; `buildConfig = true` re-enabled; all 5 files migrated off `android.util.Log`. Release builds emit no app logs.
**Files:** all `*.kt` with `Log.*`; `app/build.gradle.kts` (fix false BuildConfig comment). **Low conflict** (additive guards).
**Do:** Wrap `Log.*` in `if (BuildConfig.DEBUG)` or add ProGuard `-assumenosideeffects` for `android.util.Log`. Remove per-frame logs. Correct the build.gradle:48 comment (or actually use BuildConfig.DEBUG — currently `buildConfig=true` is enabled but unused).
**Accept:** Release build emits no app logs; per-frame `Log.d` gone.

---

## P3 — Cleanup (low conflict; batch into one agent)

### T-CLEAN-DEAD (P3) — Remove dead code
**Status: DONE (release-eng)** — ApproachDetector removed earlier; now also removed `AnalyticsEngine.averageDistance`, `DetectionRepository.getSessionEvents`, unused `OverallStats.{avgEventsPerSession,topThreat}`, and the stale `roundToInt` import. `SessionSummary.topThreat` is now surfaced in the session row ("mostly <class>"). Also fixed `DetectionEvent.VALID_CLASS_NAMES` (was missing bus/truck/cat → those records were silently dropped on read now that vehicles default ON).
**Files:** `ml/ApproachDetector.kt` (`isCollisionImminent`, `reset` — unless T-ML-APPROACH uses reset), `data/DetectionRepository.kt` (`getSessionEvents`), `analytics/AnalyticsEngine.kt` (`averageDistance`), `data/DetectionEvent.kt`/repo (`SessionSummary.topThreat`, `OverallStats.topThreat/avgEventsPerSession`, `AlertStats.closeCalls` — remove or surface in UI). **Depends on:** confirm none are used by P0/P1 tasks first.
**Do:** Delete unused functions/fields (PERF-D01..D06) OR wire the useful ones into the UI (topThreat is nice to show). Keep `clearAll` (T-PERF-PERSIST uses it).
**Accept:** No unreferenced public API remains except intentional; app still builds.

### T-CLEAN-IMPORTS (P3) — Unused imports + doc drift + flag
**Status: DONE (my files)** — removed unused Dp/abs imports in HistoryScreen; AlertLevel doc corrected in model/Detection.kt. (build.gradle buildConfig flag is the build agent's file.)
**Status:** PARTIAL (release-eng) — build-config half done: removed unused `buildConfig=true` from `app/build.gradle.kts` (confirmed no BuildConfig references in Kotlin) and cleaned the stale "future SEC-04" proguard comment. Kotlin-side unused imports + doc-drift (HistoryScreen, FrameAnalyzer, Detection.kt, AnalyticsEngine) remain for the core agent. NOTE: if T-SEC-LOGGING wires `BuildConfig.DEBUG`, re-add `buildConfig = true`.
**Files:** `ui/HistoryScreen.kt` (imports Dp:29, abs:36), `ml/FrameAnalyzer.kt` (unused `Log` import), `model/Detection.kt` (AlertLevel doc says 25/50% but code uses 65/85%), `analytics/AnalyticsEngine.kt` (stale "0,0 coords" comment ~114), `app/build.gradle.kts` (`buildConfig=true` unused). **Independent.**
**Do:** Remove unused imports; fix doc drift to match code; remove `buildConfig=true` unless T-SEC-LOGGING starts using BuildConfig.
**Accept:** Lint-clean on these; comments match code.

### T-DOCS-LICENSES (P3) — In-app open-source licenses screen
**Status: DONE (release-eng)** — `ui/LicensesScreen.kt` lists ONNX Runtime (MIT), CameraX (Apache-2.0), Ultralytics YOLO (AGPL-3.0) + security-crypto + Compose, with a source-code link (AGPL §13). Reachable from the History screen footer.
**Files:** NEW `ui/LicensesScreen.kt` + nav entry. **Independent.**
**Do:** List ONNX Runtime (MIT), CameraX (Apache-2.0), Ultralytics YOLO (AGPL-3.0) + a source link (also helps satisfy AGPL §13).
**Accept:** Reachable from the app; lists deps + link.

---

## Suggested first wave (parallel-safe, no conflicts)
`T-CORE` (solo, blocks the ML/perf tasks) **‖** `T-REL-SDK` **‖** `T-REL-PRIVACY` **‖** `T-REL-ASSETS` **‖** `T-UI-CRASH` **‖** `T-CLEAN-IMPORTS` **‖** `T-DOCS-LICENSES` **‖** `T-REL-16K`.
**Second wave (after T-CORE):** all `T-ML-*`, `T-PERF-*`, `T-SEC-ENCRYPT`, `T-PERF-PERSIST`.
