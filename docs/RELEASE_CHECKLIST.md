# NoBonk — Play Store Release Checklist

Owner of this doc: release engineering. Audience: the **adult parent/guardian**
who will hold the Play Console account, plus any agent finishing the build.

Status legend: ✅ done in repo · 🔧 needs a build machine · 👤 human/Console step.

---

## 0. TL;DR — what still blocks submission

| # | Item | Who | Status |
|---|---|---|---|
| B01 | Adult, ID-verified Play developer account | 👤 guardian | pending (§1) |
| B02 | minSdk/targetSdk/compileSdk | release-eng | ✅ minSdk 29, target/compile 36 |
| B03 | Release signing config (no secrets committed) | release-eng | ✅ config done; 🔧 keystore must be generated (§2) |
| B04 | AGPL / YOLO model licensing decision | author+guardian | ✅ decision recorded (§6); 🔧 commit the model/recipe |
| R1 | Privacy policy published at a public URL | 👤 | ✅ drafted `docs/PRIVACY_POLICY.md`; 👤 publish + link |
| R2 | Data Safety form | 👤 | ✅ answers in `docs/DATA_SAFETY.md`; 👤 enter |
| R3 | Foreground-service (camera) declaration | 👤 | ✅ text in `docs/DATA_SAFETY.md`; 👤 enter + demo video |
| R4 | 16 KB `.so` alignment verified | 🔧 | ✅ script + doc; 🔧 run once (`docs/PLAY_16KB_CHECK.md`) |
| R5 | Store assets (icon 512², feature 1024×500, screenshots) | 👤 | ✅ specs/copy in `docs/STORE_LISTING.md`; 🔧 produce images |
| R6 | Location reconciliation (T-SEC-LOCATION, coarse+opt-in) | core agent | ⏳ verify before submit (§5) |
| T1 | Closed test: 12 testers × 14 days (new personal accounts) | 👤 | ⏳ plan ~2+ weeks lead (§4) |

---

## 1. Developer account (T-REL-ACCOUNT) 👤

**The account holder MUST be an adult (18+).** Krishav is a minor and cannot hold
a Google Play developer account (Play's Developer Distribution Agreement requires
18+). Therefore:

- A **parent/guardian registers** the Google Play Console developer account
  ($25 one-time fee) and completes **identity + address verification** (Google
  requires government ID for personal accounts; D-U-N-S number for organizations).
- The **developer name** shown on the store is the guardian's verified name (or a
  registered org name), **not** the minor's.
- **Krishav is credited as the author in the app's full description** (see
  `STORE_LISTING.md`), which is the appropriate place to attribute the student.
- **Target audience: 13+ / general audience.** Do **not** enroll in **Designed
  for Families** (that program is for child-directed apps and adds obligations
  NoBonk neither needs nor qualifies for).
- Personal accounts created recently are subject to the **closed-testing
  requirement** (§4) — factor that lead time in.

## 2. Signing & keystore (T-REL-SDK) 🔧

The Gradle config (`app/build.gradle.kts`) already defines a `release`
`signingConfig` that reads credentials from Gradle properties **or** environment
variables and **commits no secrets**. You must generate the keystore once:

```bash
# Generate an upload key (RSA 2048, 27-year validity — Play requires >= 25y past 2033)
keytool -genkeypair -v \
  -keystore ~/keys/nobonk-upload.jks \
  -alias nobonk-upload \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storetype JKS
# You will be prompted for a store password, key password, and a name/org.
```

Then supply the credentials **outside the repo** — either in
`~/.gradle/gradle.properties`:

```properties
NOBONK_STORE_FILE=/home/<you>/keys/nobonk-upload.jks
NOBONK_STORE_PASSWORD=********
NOBONK_KEY_ALIAS=nobonk-upload
NOBONK_KEY_PASSWORD=********
```

…or as environment variables of the same names (best for CI). With them present,
`./gradlew bundleRelease` produces a **signed** `app-release.aab`. Without them,
the build still works but the release artifact is unsigned (safe default).

**Enroll in Play App Signing** (default for new apps): you upload with this
**upload key**; Google manages the actual app-signing key. **Back up
`nobonk-upload.jks` and its passwords** in a safe place — losing the upload key
means requesting a reset from Google. **Never commit the `.jks` or passwords.**

## 3. Build the release artifact 🔧

On a machine with JDK 17+ and the Android SDK (compileSdk 36 / build-tools 36,
NDK r27+ present via ONNX/CameraX):

```bash
./gradlew clean
./gradlew bundleRelease          # -> app/build/outputs/bundle/release/app-release.aab
scripts/check_16kb_alignment.sh  # must print RESULT: PASS  (see PLAY_16KB_CHECK.md)
```

Also recommended:
- Upload the **R8 mapping file**
  (`app/build/outputs/mapping/release/mapping.txt`) to the Console for readable
  crash stack traces.
- Sanity-install on an **API 29 emulator** and a modern device; verify the
  foreground-service camera path (start detection, background the app, confirm a
  warning fires over another app) — this exercises the lowered `minSdk`.

> ⚠️ This checklist's Gradle changes were **not build-verified in the
> release-engineering sandbox** (no JDK/Android SDK there). Run
> `./gradlew bundleRelease` (or at least `assembleDebug`) once on the build
> machine and fix any sync error before submitting. The config was reviewed for
> correctness: `compileSdk=36` needs AGP ≥ 8.9 (repo uses AGP 9.0.1 ✓);
> core-library desugaring dep added; signing block is credential-guarded.

## 4. Closed testing (new personal accounts) 👤 ⏳

Google requires **personal** developer accounts (registered under the newer
policy) to run a **closed test with at least 12 testers who opted in, for at
least 14 continuous days**, before they can apply for **production** access.

- Recruit **≥12 testers** (friends/family with Google accounts) and add them to a
  **Closed testing** track email list / Google Group.
- Keep the test **running ≥14 days** with those testers actually opted in.
- Only then does the **"Apply for production"** button unlock.
- **Plan ≥2–3 weeks of lead time** for this before any target launch date.
- (Organization accounts may be exempt, but assume the requirement applies.)

## 5. Location / privacy reconciliation (depends on T-SEC-LOCATION) ⏳

Before submitting, confirm the shipped build matches the privacy story:
- Manifest should request **`ACCESS_COARSE_LOCATION` only** (drop
  `ACCESS_FINE_LOCATION`).
- Location tagging **opt-in, default OFF**, requested **in context**.
- README/tagline wording matches (the README "nothing recorded" claim was
  softened by release-eng to cover the optional on-device coarse location — see
  README "Privacy by design").
- If FINE location is still present at submit time, update the privacy policy and
  Data Safety wording to "precise location" (still "no data collected" since
  nothing is transmitted) — but coarse-only is strongly preferred. See
  `DATA_SAFETY.md` → "consistency check."

## 6. Licensing decision — AGPL / YOLO (T-REL-LICENSE) ✅ decision

**Context.** NoBonk's detector is a **YOLO (Ultralytics) model**. Ultralytics
YOLO and its weights are licensed **AGPL-3.0**. AGPL-3.0 is a strong copyleft:
distributing an application that incorporates AGPL-covered material (the model
counts) obligates you to release the **complete corresponding source** under a
compatible license, **and** — the AGPL's distinctive **§13** — to offer that
source to users who interact with the software **over a network**. NoBonk has no
network component, so the §13 network clause is largely moot, but the **source-
availability obligation on distribution still applies** once the app is published
on Play.

**Decision: ship under Option A — comply with AGPL-3.0 (keep it open).**

Rationale: NoBonk is already a public, not-for-profit student project intended to
be open; retraining on a permissively-licensed detector (Option B) would add
significant work and change the measured results. Complying is cheap here.

**Obligations we therefore commit to (must all be true at each release):**

1. **Public source, matching the released binary.** The GitHub repo stays public
   and licensed **AGPL-3.0** (repo already has `LICENSE` + README license
   section). For **each `versionCode` uploaded to Play, tag the exact commit**
   (e.g. `git tag v1.0-vc1 && git push --tags`) so the published APK's source is
   identifiable and reproducible.
2. **Model availability.** The `.onnx` weights are "corresponding source" for the
   detector. Because the file is large and generated, satisfy this by **either**
   (a) committing/releasing the exact `yolo11n.onnx` used (e.g. as a GitHub
   Release asset tied to the version tag), **or** (b) committing the **exact,
   reproducible export recipe** — pinned `ultralytics` version + `yolo export`
   command + source weights identifier — so anyone can regenerate the identical
   model. Recipe is in the README; **pin the `ultralytics` version** and record
   the upstream weights hash to make it truly reproducible.
3. **In-app source/licenses notice (satisfies AGPL §13 spirit + attribution).**
   Provide an in-app **"Open-source licenses"** screen listing ONNX Runtime
   (MIT), CameraX (Apache-2.0), and **Ultralytics YOLO (AGPL-3.0)** with a
   **link to the public source repo**. (Tracked as `T-DOCS-LICENSES`; it is the
   user-facing half of this obligation — ensure it ships.)
4. **Preserve notices.** Keep the AGPL `LICENSE` file and the README license
   section; don't strip Ultralytics attribution.

**If the guardian later prefers a closed-source path**, the only compliant route
is **Option B**: replace the detector with a permissively-licensed model
(e.g. an Apache/BSD/MIT-licensed detector) and remove all AGPL-covered material —
a retrain + re-benchmark effort. Not chosen now.

## 7. Store presence 👤 🔧

- Enter listing copy from `docs/STORE_LISTING.md` (short ≤80, full ≤4000,
  category **Tools**, tags, contact email).
- Produce and upload graphics per `docs/STORE_LISTING.md` specs (512² icon,
  1024×500 feature graphic, ≥2 screenshots — 4–6 recommended).
- Complete **Content ratings** (IARC), **Target audience** (13+, not DFF),
  **Ads = No**, **Data safety**, **Privacy policy URL**, **App access** (note the
  app needs Camera; no login required).

---

## 8. Step-by-step: what the parent/guardian does in Play Console

> Do these roughly in order. Items marked ⏳ have lead time — start them early.

1. **Register** a Google Play Console developer account (adult, $25) and complete
   **identity/address verification**. (§1)
2. **Generate the upload keystore** and store the credentials outside the repo.
   (§2) Back it up.
3. On the build machine, **build the signed AAB** (`./gradlew bundleRelease`) and
   **run `scripts/check_16kb_alignment.sh`** → must PASS. (§3, R4)
4. In Console, **Create app** → name **NoBonk**, Default language English (US),
   App or game = **App**, Free, accept declarations.
5. **Publish the privacy policy** (GitHub Pages) and paste the URL into
   **App content → Privacy policy**. (R1)
6. Fill **App content**: **Data safety** (answers in `DATA_SAFETY.md` → "No data
   collected/shared"); **Foreground service permissions** (camera justification +
   demo video, `DATA_SAFETY.md`); **Content ratings** (IARC, `STORE_LISTING.md`);
   **Target audience** (13+, **not** Designed for Families); **Ads** = No; **App
   access** (all features available without login; camera required); **Government
   apps / Health** = No. (R2, R3, R5)
7. **Store listing:** paste short + full descriptions; upload the **512² icon**,
   **1024×500 feature graphic**, and **screenshots** (≥2, 4–6 recommended); set
   category **Tools**, contact email. (R5, `STORE_LISTING.md`)
8. **Create a Closed testing release:** upload the AAB, add **≥12 testers**, and
   **run the test for ≥14 continuous days**. ⏳ (§4)
9. After 14 days with 12+ opted-in testers, **Apply for production access** (the
   Console gates this for new personal accounts). ⏳
10. **Create the Production release:** upload the AAB (or promote the tested
    build), add release notes, and **submit for review**. Upload the **R8 mapping
    file**. (§3)
11. **Tag the released commit** (`v1.0-vc1`) and **publish the model/recipe** to
    satisfy AGPL. (§6)
12. Respond to any review questions (the FGS-camera demo video usually preempts
    the common one).

---

## 9. What remains / cannot be done in this repo

These need a human and/or a build machine — release engineering has done
everything that can be committed:

- 👤 **Adult account registration + ID verification** (§1).
- 🔧 **Actual keystore generation** and supplying `NOBONK_*` credentials (§2) —
  config is ready; secrets are intentionally not in the repo.
- 🔧 **Running the release build** and the **16 KB check** on an SDK machine (§3)
  — the sandbox had no JDK/Android SDK, so Gradle was not executed here.
- 👤/🔧 **Real store graphics** (512² icon PNG, 1024×500 feature graphic, device
  screenshots) — specs and copy are written; images are binary, produced on
  device.
- 👤 **Publishing the privacy policy URL** and filling `<GUARDIAN_CONTACT_EMAIL>`.
- 👤 **Closed test (12 testers / 14 days)** and **production application** (§4).
- ⏳ **Confirm T-SEC-LOCATION landed** (coarse, opt-in) before submit (§5); if
  not, adjust privacy/Data-Safety wording.
- 🔧 **Commit/release the exact `.onnx` or pinned export recipe** and **tag the
  release commit** for AGPL (§6).
- (Recommended, other agents) ship the in-app **Open-source licenses** screen
  (`T-DOCS-LICENSES`) — the user-facing half of AGPL §13.
