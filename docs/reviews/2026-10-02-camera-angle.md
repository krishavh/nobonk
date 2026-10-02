# Rear camera angle review — October 2, 2026

## Findings and change

The old `atan2(-gz, -gy)` was incorrect for Android gravity coordinates. An upright portrait phone with `(0, +g, 0)` produced approximately -180°, then smoothing/clamping could report -90°. Dropping the x component also made landscape posture unreliable. The documentation incorrectly dismissed downward-facing rear-camera risk: a screen facing upward means the rear camera points downward.

The corrected elevation is `atan2(-gz, hypot(gx, gy))`, in degrees. Portrait, landscape and upside-down phones facing the horizon all report 0°. Screen up reports -90° (rear camera toward ground); screen down reports +90°. This is the elevation of the rear camera, not Android Euler pitch.

The existing conservative 72° warning and 82° bad-angle entry cutoffs now apply symmetrically. BAD enters above 82° and remains until the smoothed elevation improves to 78° or less in magnitude. This small recovery band prevents ordinary noise around 82° from repeatedly releasing the angle gate. Classification changes only on accepted sensor samples, never on UI polling. A stale gap or explicit reset discards both smoothing and the prior BAD classification. These are product heuristics, not camera field-of-view or safety guarantees. A less steep camera can still miss people and objects; the scene should be checked visually in the setup preview. We intentionally did not lower the bad-angle entry threshold without real-phone evidence.

First samples initialize immediately instead of inheriting a false horizontal position. Missing, stopped, invalid-only or stale (>2 seconds) sensors report UNKNOWN with an honest hint. Sensor timestamps, rather than callback receipt time, govern freshness. Old/out-of-order readings are ignored. A gap or new session resets smoothing. UNKNOWN leaves detection running; it does not claim successful calibration.

## Verification and limits

Fourteen JVM regression tests cover horizon posture, screen-up/down sign, 925 pitch/roll combinations, malformed vectors, first-sample behavior, both warning directions, freshness boundaries, out-of-order samples, reset/gap behavior, repeated boundary oscillation, recovery at 78°, and polling-independent hysteresis. Run `./gradlew testDebugUnitTest --tests '*CameraAnglePolicyTest'` with the project Android JDK/SDK. All fourteen passed in the consolidated 278-test JVM run on October 2.

No physical-phone calibration claim is made. Gravity estimates depend on device sensor quality; the raw accelerometer fallback is less reliable during motion. This change does not measure actual camera field of view, infer uncovered lens state, or calibrate model distance estimates. Real-device checks should cover upright and landscape holding, natural walking posture, screen-up/down, Stop/restart, and delayed/missing sensor behavior before release.

## Primary sources checked

- Android sensor coordinate system: x right, y up, z out of the screen; device axes do not rotate with display orientation: https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview
- Android SensorEvent gravity/accelerometer coordinates and timestamps: https://developer.android.com/reference/android/hardware/SensorEvent
- Gravity and accelerometer behavior: https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion
