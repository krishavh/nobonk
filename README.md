# NoBonk 🚶📱

**Your phone watches where you're going, because you won't.**

NoBonk is an Android app that taps you on the shoulder before you walk into someone. It uses an on-device AI vision model to spot approaching people, walls, and ground hazards through your phone's back camera — and warns you with vibration and on-screen alerts — all while you're still staring at your screen.

![License](https://img.shields.io/badge/license-AGPL--3.0-blue)
![Platform](https://img.shields.io/badge/platform-Android%2015%2B-green)
![Language](https://img.shields.io/badge/kotlin-100%25-purple)
![Awards](https://img.shields.io/badge/STEM4All-1st%20Place%20%2B%20IEEE%20Award-gold)

Built by **Krishav**, an 8th grader, for the 2026 Alameda County Science & Engineering Fair (Project MS-SOFT-241).

> **Don't be a smombie.** NoBonk's on-device AI keeps an eye on the path ahead so a glance at your phone doesn't end in a collision.

## Awards

- 🥇 **1st Place** — STEM4All Science Fair 2026
- 🏆 **IEEE Award** — STEM4All 2026
- 🥉 **3rd Place (Category)** — Alameda County Science & Engineering Fair (ACSEF) 2026

## The problem

People walk while looking at their phones and run into each other, walls, and curbs. Unlike distracted *driving*, you can't realistically pass a law against walking with a phone. So instead of fighting the phone, NoBonk makes the phone itself watch the path ahead.

## How it works

1. **Camera** — the back camera captures frames in real time (~10 fps). When you're looking at your screen, the back camera naturally faces forward.
2. **On-device AI** — a small YOLO11 model (via ONNX Runtime Mobile) detects people, animals, and obstacles in each frame. No internet needed.
3. **Distance estimation** — a pinhole-camera model converts bounding-box size to approximate distance; a box growing frame-over-frame means something is approaching.
4. **Approach tracking** — IoU tracking plus time-to-collision physics (`ApproachDetector.kt`) flags anything closing distance fast enough to hit you within ~2 seconds.
5. **Hazards the model can't classify** — `FrameAnalyzer.kt` detects blank walls (gradient-invariant adjacent-cell brightness analysis) and ground hazards like potholes and step-downs.
6. **Alerts** — escalating haptic + on-screen warnings (LOW / MEDIUM / HIGH) based on distance thresholds you choose. Runs as a background foreground-service, so warnings appear over whatever app you're using.
7. **Detection history** — sessions, alert counts, peak-danger hours, and danger hotspots, stored only on your device.

## Privacy by design

- **No video or photos are ever recorded, stored, or transmitted.** Frames are processed in memory and immediately discarded.
- **Everything runs on-device.** No network connection is used or required — the app works in airplane mode.
- **GPS is optional.** If you grant location, alert locations are saved to a local file *on your phone only*, so the history screen can show where your close calls happen. Deny location and everything else still works.
- **`allowBackup` is disabled** so history isn't swept into cloud backups.
- You can clear all history at any time from within the app.

## Measured results (Pixel 9a)

| Metric | Result |
|---|---|
| Detection accuracy (good lighting) | 80%+ |
| Detection accuracy (low light) | 3/10 — known limitation |
| Approach detection trials | 8/10 correct |
| Distance error at 1 m | ±30 cm |
| Battery drain (background mode) | ~10%/hr |

## Getting the model

The AI model file is **not** included in this repo (it's large, and the weights are distributed by Ultralytics under AGPL-3.0). After cloning, export a model and drop it into `app/src/main/assets/`.

The app loads `yolo11n.onnx` by default (the nano model, ~10 MB — fastest, good for phones). To produce it with the [Ultralytics](https://docs.ultralytics.com/) tools:

```bash
pip install ultralytics
yolo export model=yolo11n.pt format=onnx
```

Then move `yolo11n.onnx` into `app/src/main/assets/`. The detector also supports the `s`/`m` variants and the YOLO26 family — change the `modelName` in `ObjectDetector.kt` to match the file you add.

## Building it yourself

1. Install [Android Studio](https://developer.android.com/studio).
2. Clone this repo and open the folder in Android Studio.
3. Add a model file to `app/src/main/assets/` (see above).
4. Enable Developer Mode + USB debugging on your phone, connect it, and press **Run**.

Or from the command line: `./gradlew assembleDebug` (a helper script, `build_and_install.sh`, builds and installs to a connected device).

**Requirements:** Android 15+ (API 35). Best results on recent Pixel devices. iOS is not supported — Apple does not allow background camera access, which is the whole point of the app.

## Known limitations

- Doesn't work well in low light (camera hardware limitation).
- Distance estimates are approximate and depend on camera angle — the app warns you when the phone is held too flat.
- Older/slower phones may lag; walk at slow-to-medium speed.
- **NoBonk is a student-built safety prototype, not a certified safety device. It will miss things. Keep looking up.**

## Contributing

Issues and pull requests welcome! Some good areas to dig into: better low-light handling, per-phone distance calibration, smarter alert descriptions, IoU-based multi-person ID matching, and a formal study on whether the app actually reduces near-misses. Check the open issues for known bugs.

## Acknowledgments

This project leaned heavily on AI coding tools, and they deserve real credit for the amount of code they helped produce:

- **Claude Code** and **OpenAI Codex** — most of the Kotlin in this repo was generated and refactored with these tools, working from the author's design and direction.
- **Google Gemini** — debugging help, security/privacy review, and the alert-system diagram.
- **Warp AI** — terminal workflow and build scripting.

The problem itself, the iOS-to-Android decision, the distance-estimation and wall-detection approaches, the privacy-first design, the false-alert tuning, and all the real-world testing came from the author — the tools wrote code to fit those ideas, not the other way around.

Built with Android Studio, Jetpack Compose, CameraX, and ONNX Runtime. YOLO model weights from the official [Ultralytics](https://github.com/ultralytics/ultralytics) repositories. Thanks to the ACSEF Winter Bootcamp for project feedback.

## License

Licensed under the **GNU Affero General Public License v3.0** (AGPL-3.0) — see [LICENSE](LICENSE). AGPL-3.0 was chosen for compatibility with Ultralytics YOLO, which is itself AGPL-3.0. In short: use, modify, and share freely, but derivative works must remain open source under the same license, including over a network.
