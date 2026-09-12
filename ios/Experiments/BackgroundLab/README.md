# Background processing lab

An isolated iOS 26 experiment that asks whether a user-started CPU workload receives continued background execution. It never opens a camera, records audio, accesses the network, or detects a real person or obstacle.

The workload performs Apple Vision human-rectangle analysis on 2,400 generated checkerboard frames. Every supported Vision compute stage must offer a CPU device; otherwise the run reports an error. No GPU/ANE entitlement is requested. The separate 100-frame foreground baseline uses the same processor and cancels when the app is backgrounded.

## Build

```sh
xcodegen generate
xcodebuild -project NoBonkBackgroundLab.xcodeproj -scheme NoBonkBackgroundLab \
  -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
```

Only `processing` is declared as a background mode, with a bundle-prefixed permitted task identifier. Each request gets a fresh UUID, is registered once and uses the `.fail` strategy. The screen distinguishes submission from actual task launch. A successful submission alone is never counted as a running background task.

Stop and system expiration cancel the same thread-safe run token. The worker checks it between Vision requests; a running request may need to finish before its resources unwind. UI results are rejected immediately after Stop or a new run. The worker completes an accepted system task once, including failures. There is no automatic retry, relaunch or queued work after cancellation.

## Observed on September 7, 2026

| Check | Result | Meaning |
| --- | --- | --- |
| iPhone 17 Pro simulator, iOS 26.5 build | Passed | Compiles against simulator SDK |
| Generic physical-iPhone SDK build | Passed | Compiles; not a device execution result |
| Continued-processing request in simulator | `BGTaskSchedulerErrorDomain` code 1; no work launched | Background execution is unverified, not successful |
| Foreground CPU baseline | 100 generated frames completed in 6 seconds | Processing path executes in this simulator; not phone performance or detector accuracy |
| Stop during baseline | UI stopped at its last published count of 50/100 | Subsequent results did not replace the stopped state |
| Home during baseline | Returned to Stopped with zero published frames; no restart | Foreground-only control canceled on leaving the app |

The simulator cannot establish background scheduler behavior, camera permission, hardware throughput, alert delivery or battery life. No synthetic frame is presented as a real detection. Local counters reset on the next run; the app stores no camera data or test history.

## Physical protocol

1. Install on a compatible iPhone through the owner's signing workflow, then launch from Home without a debugger attached. Record the iOS version, Low Power Mode and background settings.
2. Run the foreground baseline. Check real counts and completion. Stop a repeat midway; its counts must not resume.
3. Start the continued-processing experiment while the app is visible. Wait for the **Granted** status, then switch apps. Return after a short interval and compare actual completed and backgrounded counts. A system progress panel by itself is not proof of useful work.
4. Repeat with the screen locked, Low Power Mode and another app doing work. Exercise app Stop, system cancellation and force-quit. Record denial, expiration and zero background counts as results.
5. Do not substitute a foreground run if permission is declined and then label it a background success. No camera-access conclusion follows even if all 2,400 test frames complete while backgrounded.

## Primary evidence

- [Apple: performing long-running tasks](https://developer.apple.com/documentation/backgroundtasks/performing-long-running-tasks-on-ios-and-ipados)
- [Apple DTS: ongoing user-started sessions and resource limits](https://developer.apple.com/forums/thread/840384)
- [Apple DTS: submission can succeed without the launch callback arriving](https://developer.apple.com/forums/thread/807370)
- [Apple: background GPU capability](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.background-tasks.continued-processing.gpu)

The installed iOS 26.5 SDK supports `submit(_:)`; the newer completion-handler submission API and dedicated background inference entitlement are iOS 27+, so this experiment does not use them.
