# Google Play Data Safety — Answer Sheet (T-REL-PRIVACY)

This is a paste-ready script for the adult account holder to complete
**Play Console → App content → Data safety**. It reflects the app's actual,
fully-offline behavior. Where a target-state assumption is made, it is flagged.

---

## The one fact that drives every answer

Google defines **"collection"** as *data your app transmits off the device*, and
**"sharing"** as *transferring it to a third party*. **NoBonk transmits nothing.**
It has **no `INTERNET` permission** and makes no network calls. Camera frames are
processed in memory and discarded; the only persisted data (the detection-event
history, plus optional coarse location) **stays on the device**.

Therefore, per Google's definitions, NoBonk **collects no data** and **shares no
data** — even though it *processes* the camera on-device and can *store* a local
history. On-device-only processing and on-device-only storage are explicitly
**not** "collection." (You will still describe this honestly in the privacy
policy and store listing.)

---

## Form answers

### Data collection and security

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | **N/A** — no data is transmitted (no data leaves the device). |
| Do you provide a way for users to request that their data is deleted? | **Yes** — in-app "Clear history" erases all on-device data; uninstalling removes everything. |

Because the first answer is **No**, the Console will not ask you to enumerate
data types. You are done with the data-type matrix.

### Data types — for your records (NOT entered as "collected")

Even though none of this is "collected" (nothing is transmitted), keep this
internal note so the answers stay defensible if reviewed:

| Processed/stored on device | Transmitted off device? | Declared as collected? |
|---|---|---|
| Camera frames (real-time, in memory) | No — discarded immediately | No |
| Detection-event history (local file) | No — on-device only | No |
| Approximate/coarse location (optional, off by default) | No — on-device only | No |

---

## Important consistency check before you submit

The Data Safety answers above assume the app ships with **T-SEC-LOCATION**
applied: location tagging is **optional, coarse (`ACCESS_COARSE_LOCATION`), off
by default**, requested **in context**, and never transmitted.

- ✅ If that is the shipped behavior, the answers above are correct and the
  privacy policy matches.
- ⚠️ **If the build still requests `ACCESS_FINE_LOCATION` and/or persists precise
  location up front**, then:
  - the "No data collected" answer is *still technically correct* (nothing is
    transmitted), **but** the privacy policy and store listing must be updated to
    say "precise location," and Play may scrutinize an offline app requesting
    `FINE` location. **Strongly prefer landing T-SEC-LOCATION (coarse-only) first.**

Whatever ships, the three surfaces must agree:
**app permissions ⇄ privacy policy ⇄ Data Safety form.**

---

## Foreground Service (camera) — Console declaration

Separate from Data Safety, Play requires a **Foreground Service** use-case
declaration for apps that use FGS (Android 14+/API 34). NoBonk declares
`foregroundServiceType="camera"`. Provide this in
**Play Console → App content → Foreground service permissions**:

> **Which foreground service type(s):** Camera.
>
> **What is the feature / why the app needs it:**
> NoBonk is a walk-safety aid. Its core function is to watch the path ahead
> through the back camera and warn the user (vibration + on-screen alert) before
> they walk into a person, wall, or ground hazard while looking at their phone.
> To do this the camera must keep analyzing frames **while the app is in the
> background / the screen shows another app** — that is the entire purpose of the
> product. A foreground service with type `camera` is the only supported way to
> keep the camera active in the background, and it shows a persistent
> notification so the user always knows detection is running.
>
> **Is it user-initiated and visible?** Yes. The user explicitly starts
> detection; a persistent foreground-service notification is shown the entire
> time; frames are processed in memory only and never recorded or transmitted.
>
> **Demo:** [link a short screen-recording showing the user starting detection,
> the persistent notification, and a warning firing over another app].

Attach a short video demonstrating the background-camera use case — reviewers
routinely ask for one for `camera`-type FGS.
