# Short-screen video viewport check

The prior runtime (e4c3933) loaded the user-supplied public YouTube video
`https://www.youtube.com/watch?v=uj7l9rwRWSQ` inside NoBonk on an iPhone SE
(3rd generation) simulator, iOS 17. It redirected to the mobile watch page.

At normal text size, the small website viewport clipped the portrait player
into a thin strip. In the existing accessible layout, the taller website pane
allowed manual inline playback: the visible playhead advanced from 0:22 to 1:08
of 1:57 while NoBonk's paused preview and Start remained reachable. Close and
reopen worked. No login or native YouTube app was used.

This correction automatically uses the compact arrangement below 700 pt of
available height and reserves at least 328 pt for BrowserPane (roughly 240 pt for
the page after native address/navigation bars). Preview and Start/Stop remain
pinned outside scrolling content.

**Validation distinction:** the playback observation predates this correction.
The corrected source passed a generic simulator Release build; its new normal-
text layout has NOT yet been visually checked because the Mac locked during
validation. Treat this as a preview correction pending that check, not a
verified playback fix. No simultaneous camera/video, physical performance,
fullscreen coverage, or actual Stop teardown claim is made. Camera stayed
paused; the simulator app had no optional Fast model weights.

Next: on an unattended, unlocked Mac, install this commit on the dedicated SE
simulator; reopen the same URL at normal text size; check full player visibility,
advancing playback time, and reachable preview/action. Then check page close/
reopen and suspension when leaving Browse. Broader site compatibility remains
unverified.
