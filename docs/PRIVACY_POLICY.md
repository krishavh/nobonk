# NoBonk — Privacy Policy

**Last updated:** 2026-09-07
**App:** NoBonk (`ai.genwhy.nobonk`)
**Developer:** Published by a parent/guardian on behalf of Krishav (student author).
**Contact:** support@genwhy.ai (Haarith, parent and account holder), or an issue at github.com/krishavh/nobonk.
**Public URL of this policy:** https://krishavh.github.io/privacy/nobonk.html (short-form page; this file is the long-form text).

> **Plain-language summary:** NoBonk runs entirely on your phone. It uses the
> back camera to watch the path ahead and warn you before you walk into
> something. **No photos or video are ever recorded, saved, or sent anywhere.**
> The app has no internet permission and works in airplane mode. It stores settings, a safety acknowledgment and a private, on-device history of alert events, and — *only if
> you turn it on* — an approximate (coarse) location tag for those events so a
> map of your close-call spots can be shown. You can delete that history at any
> time. Camera frames and history are not uploaded by NoBonk.

---

## 1. Who we are

NoBonk is a not-for-profit student science-fair project (Project MS-SOFT-241,
2026 Alameda County Science & Engineering Fair). Because the author is a minor,
the app is published through a verified Google Play developer account held by a
parent/guardian, who is the data controller for the purposes of this policy.

## 2. What NoBonk does

NoBonk uses your phone's back camera and an on-device AI vision model to estimate the presence of people and selected objects, with additional image heuristics for possible obstacles. It can miss hazards or produce false alerts; it is not a safety device or a replacement for looking up. Alerts are vibration, an on-screen warning, a short alert sound, and — only if you switch it on — a spoken phrase such as "Person on your left. Look up." All processing happens **on the device**.

## 3. Camera data — never recorded

- The camera feed is analyzed **frame by frame in memory** and each frame is
  **immediately discarded** after the AI model reads it.
- **No image or video is ever written to storage, shown to anyone, or
  transmitted off the device.**
- The camera is used only while you have detection running.

## 4. Data the app can store on your device

NoBonk persists local settings, the accepted safety-notice version, update-prompt preferences, a measured execution-provider choice, and **foreground detection-event history**. Background scanning currently does not add history events. History is encrypted in private storage (`detection_events.enc`); a temporary encrypted file may be used during compaction. NoBonk does not upload these records. Each event may contain:

- a timestamp,
- the alert level and detected object type (e.g. "person", "dog"),
- an approximate distance estimate, and
- **optionally**, a **coarse** location (see §5).

This file never leaves your device, is not included in cloud backups
(`allowBackup` is disabled and backup/transfer rules explicitly exclude it), and
can be erased at any time (see §7).

## 5. Location — optional, coarse, off by default

- Location tagging is **optional and off by default**. NoBonk works fully with
  location denied.
- If — and only if — you enable it, NoBonk requests **approximate (coarse)**
  location (`ACCESS_COARSE_LOCATION`) so the history screen can show *roughly*
  where your close calls happen. NoBonk does **not** use precise/GPS-level
  location.
- Location, if captured, is stored **only in the on-device history file** and is
  **never transmitted** anywhere.
- You can turn it off again, or delete all stored locations, at any time.

## 6. What NoBonk does NOT do

- **No internet.** The app declares **no `INTERNET` permission** and makes no
  network connections. It cannot send your data anywhere even if it wanted to.
- **No accounts, no sign-in, no advertising, no analytics or tracking SDKs.**
  Libraries include ONNX Runtime, CameraX, Jetpack Compose, AndroidX Security
  and Google Play In-App Updates.
- **No microphone.** NoBonk never requests audio recording.
- **Motion sensors** (accelerometer/gravity) are read in memory to tell whether the
  phone is pointed forward and whether you are walking; readings are never stored.
- **Optional spoken alerts** hand a short phrase (for example "Person on your left.
  Look up.") to your phone's own text-to-speech engine, which is separate software
  covered by its maker's policy. The phrase contains no personal data, and NoBonk
  itself still has no network access. Voice alerts are off by default.
- **App updates via Google Play.** NoBonk can ask the Google Play Store whether a newer version exists (an in-app update suggestion you can decline or snooze). That check is performed by the Play Store, which processes your device details and installed version under Google's own policy; NoBonk requests update availability for its app. Camera frames, alerts, settings and history are never part of it. Builds not installed from Google Play simply see no update suggestion.
- **No data selling or sharing.** Because nothing leaves the device, there is
  nothing to sell or share.

## 7. Your controls

- **Delete your history:** use the in-app "Clear history" control to erase all
  stored events (and any stored coarse locations) once deletion succeeds; the app reports a deletion failure.
- **Revoke permissions:** you can revoke Camera or Location permission at any
  time in Android Settings. Uninstalling the app removes all its on-device data.

## 8. Children's privacy

NoBonk is intended for users **13 and older** and is **not** enrolled in Google
Play's "Designed for Families" program. It does not knowingly collect personal
information from children, and in any case collects no data off the device.

## 9. Security

On-device history is stored in the app's private, sandboxed storage, excluded
from backups, and encrypted at rest with an Android Keystore-backed AES-256-GCM key
(`androidx.security.crypto`).

## 10. Changes to this policy

If this policy changes, the updated version will be posted at the same public URL
(see below) with a new "Last updated" date.

## 11. Contact

Questions or requests: support@genwhy.ai (Haarith, parent and account holder).

---

### Publication note (for the developer)
The public short-form page is https://krishavh.github.io/privacy/nobonk.html (repo
`krishavh/krishavh.github.io`); Play Console → App content → Privacy policy points at it.
Keep this file and that page in agreement whenever permissions or stored data change.
