# NoBonk — Google Play Store Listing Draft (T-REL-ASSETS)

Paste-ready copy plus the exact asset specs the adult account holder needs to
produce. Wording deliberately avoids absolute safety claims ("prevents
collisions") in favor of "helps warn," and keeps the "not a certified safety
device" disclaimer prominent — required both for honesty and to reduce Play
review risk on a safety-themed app.

---

## App identity

| Field | Value |
|---|---|
| App name (≤30 chars) | **NoBonk** |
| Package | `com.persondetection.android` |
| Default language | English (United States) |
| Category | **Tools** |
| Tags | safety, camera, walking, pedestrian |
| Contact email | `<GUARDIAN_CONTACT_EMAIL>` (adult account holder) |
| Website (optional) | repo or GitHub Pages URL |
| Privacy policy URL | published `docs/PRIVACY_POLICY.md` (GitHub Pages) |

---

## Short description (≤80 characters)

> **On-device AI warns you before you walk into someone while on your phone.**

*(69 characters. Alternatives, all ≤80:)*
- `Look up! NoBonk's on-device AI warns you before a walking collision.` (67)
- `Your phone watches the path ahead so a glance down doesn't end in a bonk.` (73)

---

## Full description (≤4000 characters)

> **Don't be a smombie.**
>
> NoBonk taps you on the shoulder before you walk into someone. Using your
> phone's back camera and an on-device AI vision model, it watches the path
> ahead while you're looking down at your screen — and warns you with vibration
> and on-screen alerts when a person, wall, or ground hazard is coming up.
>
> **Everything runs on your phone. Nothing is ever recorded or sent anywhere.**
>
> **How it works**
> • The back camera analyzes the path ahead in real time (~10 fps).
> • A small YOLO AI model, running fully on-device via ONNX Runtime, spots
>   people, obstacles, walls, and ground hazards.
> • When something gets close or is closing in fast, NoBonk escalates a
>   LOW / MEDIUM / HIGH warning — vibration plus an on-screen "LOOK UP!" alert —
>   even while you're using another app.
> • An optional on-device history shows your sessions, alert counts, and (if you
>   choose) a rough map of where your close calls happen.
>
> **Privacy first — by design**
> • No photos or video are ever recorded, stored, or transmitted. Frames are
>   processed in memory and immediately discarded.
> • No internet permission at all — NoBonk works in airplane mode.
> • No accounts, no ads, no trackers, no third-party SDKs.
> • Location is optional, approximate, and off by default; if you turn it on, it
>   stays on your phone. Clear your history anytime.
>
> **Made by a student**
> NoBonk was built by Krishav, an 8th-grade student, for the 2026 Alameda County
> Science & Engineering Fair. It's a real, working prototype — and an honest one.
>
> **⚠️ Important — please read**
> NoBonk is a student-built assistive prototype, **not a certified safety
> device**. It can and will miss things, especially in low light or at speed. It
> is a helper, not a guarantee. **Keep looking up and stay aware of your
> surroundings — NoBonk is a backup, not a replacement for your own attention.**
>
> Requires the camera. Works best on recent phones and in good lighting.

*(~1,750 characters — well within the 4,000 limit.)*

---

## Graphic assets — exact specs

Produce these on the build machine (they are binary and not committed here). The
in-app adaptive icon already exists in `res/`; the Play **store** icon below is a
separate 512×512 asset.

| Asset | Spec | Notes |
|---|---|---|
| **App (store) icon** | **512 × 512 px**, 32-bit **PNG** (with alpha), ≤1 MB | Reuse the adaptive-icon art: dark charcoal→amber background, amber walking pedestrian, red "look-up" burst. Keep key art within the central ~66% (Play applies a mask). No drop shadows baked in. |
| **Feature graphic** | **1024 × 500 px**, PNG or JPEG (no alpha), ≤1 MB | Shown at the top of the listing. Suggested: the pedestrian mark on the left, wordmark "NoBonk" + tagline "Look up." on the right, same amber-on-charcoal palette. Keep text out of the outer ~5% safe margin. |
| **Phone screenshots** | **2–8 required**; PNG/JPEG; 16:9 or 9:16; each side **1080–3840 px**; min side ≥1080 | See shot list below. At least 2 are mandatory; 4–6 recommended. |
| **7" / 10" tablet screenshots** | optional | Only if you promote tablet support; otherwise skip. |
| **Promo video** (optional) | YouTube URL | A 20–30s clip showing a walk + a live warning also doubles as the FGS-camera review demo. |

### Screenshot shot list (capture on-device, good lighting)

1. **Live detection** — camera view with a bounding box on a person and a green
   HUD ("path clear / person ahead").
2. **HIGH alert firing** — the full-screen "LOOK UP!" overlay over the camera (or
   over another app, to show background mode).
3. **Background mode** — the alert appearing over a different app (e.g. a chat),
   demonstrating the whole point.
4. **History / stats** — sessions, alert counts, peak-danger hours.
5. **Hotspot map** — (only if location is enabled) rough close-call map.
6. **Settings** — accuracy mode + distance threshold controls.

Add a short caption band to each (same amber-on-charcoal style) so the store
gallery reads as a set. Keep any "not a safety device" disclaimer legible on at
least one shot.

---

## Content rating (IARC questionnaire)

Complete **Play Console → App content → Content ratings**. Answers for NoBonk:

| Question area | Answer |
|---|---|
| Category | **Utility / Productivity / Communication / Other** (Tools app) |
| Violence | None |
| Sexuality / nudity | None |
| Language (profanity) | None |
| Controlled substances | None |
| Gambling (real or simulated) | None |
| Fear / horror | None |
| User-generated content / social features | None |
| Does the app share the user's **location** with other users? | **No** (location is on-device only, never shared) |
| Does the app collect/share personal info? | **No** |
| In-app purchases | **No** |
| Digital purchases / ads | **No ads, no purchases** |

Expected outcome: rated suitable for **Everyone / PEGI 3** or similar. Even so,
set the **target audience to 13+** (below) rather than enrolling in Designed for
Families.

---

## Target audience & content

**Play Console → App content → Target audience and content:**

- **Target age group:** **13–15, 16–17, and 18+** (i.e. **13 and up**).
- **Do NOT** select any age band under 13, and **do NOT** opt into the
  **Designed for Families** program. NoBonk is a general-audience Tools app, not a
  kids' app.
- Appeals to children? **No** — the store presentation (utility framing, no
  child-oriented characters/branding) targets teens and adults.

---

## Other listing fields

- **Ads:** contains ads? **No.**
- **In-app purchases:** **No.**
- **Government app / financial / health:** **No.** (NoBonk is *not* a medical or
  emergency-safety device — do not tick health/medical categories.)
- **News app:** No.
- **COVID-19 / contact-tracing:** No.

---

## Author credit (per T-REL-ACCOUNT)

The listing (in the full description) credits **Krishav** as the student author.
The **developer/account name** shown on Play is the **adult account holder's**
verified developer name, not the minor's. See `RELEASE_CHECKLIST.md`.
