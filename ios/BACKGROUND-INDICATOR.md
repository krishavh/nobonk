# Android background indicator — proposed follow-up

Recommend a quiet, thin outline near the screen edges instead of the wide “NoBonk active” top banner. Do not trace individual launcher tiles: their geometry varies by launcher, folder, widget and orientation; reading other apps' layout would add unnecessary access and fragility. Fable independently agreed with the screen-edge approach.

Use four narrow, non-touchable overlay strips rather than one screen-sized transparent overlay. Respect display cutouts, status/navigation bars, gesture areas and rotation. A faint, steady blue-green line means only “camera scanning active,” never “safe.” A brief amber accent may accompany an actual alert; avoid flashing or perpetual attention-grabbing animation. Stop or remove all strips when capture stops or the screen is off. Accessibility and reduced-animation preferences should be respected.

Keep an accessible **Open NoBonk** pill as a separate small touch target, and retain the Android foreground-service notification with **Stop**. The thin border alone cannot explain a warning, pause scanning, or bring the app back. Use existing overlay permission only; no accessibility service, screen capture, launcher scraping or new data collection.

Prototype separately from the already-submitted 1.0.9 release. Verify taps on apps underneath, edge gestures, keyboards, cutouts, landscape, permission revocation, notification Stop and return-to-app on a physical Pixel before replacing the current banner. Android 12+ blocks some touches through untrusted overlays; strip windows minimize overlap but do not remove the need to test input behavior.

Reference: [Android 12 untrusted touch events](https://developer.android.com/about/versions/12/behavior-changes-all#untrusted-touch-events).
