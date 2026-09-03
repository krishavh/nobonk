# NoBonk — Full Audit Report (pre–Play Store)

**Date:** 2026-08-23 · **Repo:** krishavh/nobonk · **Reviewed by:** a 7-agent expert fleet
(security, performance, Play-Store readiness, ML-detection correctness, and 3 traceability layers)

---

## TL;DR — what you need to know

NoBonk is a genuinely well-built app for an 8th grader: clean architecture (Compose → ViewModel → Service → ML → Data), **fully offline** (no `INTERNET` permission — verified), no ads/trackers, `allowBackup=false`. The engineering is real.

**But it is not release-ready, and — more importantly — its core safety feature largely doesn't fire.** Three themes dominate:

### 1. 🔴 The safety alarms are mostly unreachable (the big one)
The monocular distance math **saturates**: a person can never be reported closer than **~1.48 m**, yet the flagship **"LOOK UP!" full-screen overlay + alert sound require < 1.3 m**. So when someone walks straight at you — the whole point — the loudest alarm **never triggers**. Non-person objects (cars, bikes, poles) are worse: they only alert below 0.8 m, which is **arithmetically impossible** to reach, so **vehicles never warn at all**. And the "approaching/collision-imminent" logic that gives the app its name is computed, then **thrown away** — it only draws a red ring, and in background mode it's discarded entirely. *Fixing this is the difference between a safety app and a box-drawing demo.*

### 2. 🟠 There are two detection pipelines, and they've diverged
The **foreground** (looking at the app) and **background** (the real use case — phone in hand, app hidden) run **completely separate copies** of the detection pipeline that don't share config or results. The background one — the important one — has a **rotation bug** (it drops `rotationDegrees`, swapping width/height → **systematically wrong distances**), ignores the user's accuracy/threshold settings, and runs flat-out with no frame cap (battery). This duplication is the root cause of ~half the perf and correctness findings.

### 3. 🟡 "Nothing recorded, ever" isn't quite true, and it's not Play-ready
Camera frames genuinely aren't recorded ✅ and nothing leaves the device ✅ — but the app **does persist up to 5,000 GPS-geotagged detection events as unencrypted JSON**, which contradicts the tagline and is "location data" to Google. And there are **four hard Play-Store blockers** (below).

---

## Findings by area

### 🔒 Security & Privacy
| Sev | ID | Finding |
|---|---|---|
| High | SEC-N01 | "Nothing recorded, ever" is **false** — up to 5,000 GPS-geotagged events persisted to `filesDir/detection_events.json`. Reconcile tagline + make location opt-in/removed. |
| High | SEC-N02 | That history is stored **unencrypted** at rest (the "SEC-04 EncryptedFile" work was scaffolded in ProGuard but never wired up). |
| High | SEC-N03 | `ACCESS_FINE_LOCATION` is over-privileged (COARSE suffices for 55 m hotspots) and requested up-front. |
| Med | SEC-N04 | Verbose `Log` calls ship in **release** (no `BuildConfig.DEBUG` guard), incl. per-frame logs. |
| Med | SEC-N05 | ONNX model loaded from assets with **no integrity check** (low risk while bundled; matters if ever side-loaded). |
| Med | SEC-N06 | `SYSTEM_ALERT_WINDOW` overlay — well-mitigated (NOT_FOCUSABLE/NOT_TOUCHABLE), but draws review scrutiny; add `FLAG_NOT_TOUCHABLE` to the HUD too. |
| Low | SEC-N07/08 | Data-Safety/privacy-policy must declare on-device location; location-permission race at first launch. |
**Verified good:** no network, frames never written, `exported=false` service, input-validated JSON store.

### 🎯 ML / Detection correctness (safety-critical)
| Sev | ID | Finding |
|---|---|---|
| Critical | ML-01 | **Distance saturates** → HIGH "LOOK UP!" alarm unreachable for a person (min ~1.48 m vs threshold 1.3 m). |
| Critical | ML-02 | Non-person objects only alert < 0.8 m → **cars/bikes/poles never alert**. No LOW/MED ladder for them. |
| Critical | ML-03 | Approach/TTC tracking **breaks on fast approach** (IoU>0.3 match fails) **and drives no warning** (only a red ring; discarded in background). The app's namesake warns no one. |
| High | ML-04 | **No letterbox** — frames squished to square with nearest-neighbor → recall loss (missed people) + distorted distance correction. |
| High | ML-05 | Monocular distance hardcodes 1.70 m adult + 60° FOV → wrong for children, wide lenses, angles; UI shows false-precision "%.1f m". |
| High | ML-06 | **Low light misread as "camera blocked"** → all detection disabled at dusk/night (when it's most needed). |
| High | ML-07/08 | Greedy class-agnostic track matching; NMS IoU **0.70** far too loose (→ duplicate boxes → alert spam). |
| Med | ML-11/12/13/15 | No alert hysteresis (flicker); ground-hazard no persistence (shadow ≡ pothole); "object" bucket NMS self-suppression; confidence 0.25 (eval default, too low). |

### ⚡ Performance & battery
| Sev | ID | Finding |
|---|---|---|
| Critical | PERF-C01 | Per-frame **YUV→JPEG→Bitmap** round-trip is the dominant cost. Switch CameraX to `RGBA_8888` output → deletes ~40 lines + biggest cost. |
| Critical | PERF-C02 | NV21 plane copy ignores row/pixel stride → **color corruption** feeding YOLO (fixed by C01). |
| Critical | PERF-C03 | Background service has **no FPS cap** + full-res inference → worst battery path, and it's always-on. |
| Critical | PERF-C04 | `getPixel()` in nested hot loops (JNI-per-pixel) → use `getPixels()` batch. |
| High | PERF-P01..P06 | Per-frame bitmap alloc (no recycle); 8400×80 YOLO scan; `UUID.randomUUID` per box; NNAPI-only (deprecated @API35, no XNNPACK); full-file rewrite per event; ControlPanel recomposes @10 fps. |
| — | PERF-U01 | **Root cause: the entire pipeline is duplicated** (`DetectionService` vs `DetectionViewModel`). Extract one shared `DetectionEngine`. |

### 🔗 Traceability (all 3 layers)
- **UI → ViewModel:** every call/state type-correct **except one real crash** — `HistoryScreen.kt:618` guards GPS with `!= 0.0` but the field is `Double?`; a null (GPS-less session) reaches `String.format("%.4f")` → renders `"null, null"` / can crash. *(Confirmed by 3 independent agents.)*
- **VM → Service → ML:** all *wired* signatures match; the defects are what's **not** connected (config in / results out) + the divergent background pipeline (rotation, threshold, model).
- **ML → Data round-trip:** `toJson ↔ fromJson` **9/9 consistent** ✅; storage lock-safe; but whole-file JSON corruption = **silent total history loss** (no backup), and several computed fields (`topThreat`, `avgEventsPerSession`, `averageDistance`, `AlertStats.closeCalls`) are **never displayed**.

### 🏪 Play Store readiness
**🔴 Blockers:** (B01) developer account must be an **adult's** (Krishav is a minor); (B02) **`minSdk=35`** reaches ~no devices → drop to ~29; (B03) **no release signing config** → can't build an uploadable AAB; (B04) **AGPL/YOLO model licensing** decision (publish source+model, or switch to a permissive model).
**🟠 Required:** privacy policy; Data-Safety form (can honestly say "no data collected" — fully offline); drop FINE location; FGS console declaration; **bump `targetSdk` 35→36** (new-app cutoff ~Aug 31 — imminent); verify **16 KB `.so` alignment**; store assets + adaptive icon.
**🟡 Recommended:** R8 mapping upload; soften safety-claim wording; **closed test w/ ~12 testers for 14 days** (required for new personal accounts — plan ~2 wks lead); in-context permissions; accessibility.

---

## Cross-cutting themes (fix these and many findings collapse)
1. **Unify the two pipelines** → kills the rotation bug, threshold divergence, JPEG-quality divergence, and 6 duplicated functions at once.
2. **Fix the distance model** → unblocks every alarm (ML-01/02) and the meters displayed to the user.
3. **Wire `isApproaching` to actual warnings** → makes the app do what its name says.
4. **Drop/ް gate location + reconcile the privacy story** → fixes the tagline, the Data-Safety form, and the FINE-location Play risk together.

## Recommended order
**P0 (safety + release blockers):** distance model (ML-01/02) → approach wiring (ML-03) → unify pipeline incl. rotation fix → minSdk/targetSdk/signing → privacy/location reconciliation.
**P1:** GPS null crash · letterbox · RGBA (kills JPEG+color bug) · NMS/confidence · encrypt-or-drop history · privacy policy + Data Safety + FGS declaration + 16KB check + store assets.
**P2:** low-light gating · hysteresis · persistence rework · XNNPACK · logging guard · "clear history" UI.
**P3:** dead-code + duplication cleanup · doc drift · accessibility polish.

See **`TASKS.md`** for the itemized, agent-pickable backlog.
