# Opt-in physical iPhone Fast benchmark

`FastDeviceBenchmarkTests` belongs to the existing hosted `NoBonkQuickAccessTests` target. It is skipped unless `NOBONK_RUN_DEVICE_BENCH=1` reaches the test process, and also skips Simulator or Debug builds. It uses the production `FastObjectDetector` with generated CVPixelBuffers; it never requests camera access, loads a website or changes app safety preferences.

From the repository root, first copy the exact local model with `ios/scripts/setup_fast_model.py`. Regenerate `ios/NoBonk.xcodeproj` if merging project files. Root's existing signing/team setup remains responsible for the app and hosted test installation. Example using the already authorized team:

```sh
TEST_RUNNER_NOBONK_RUN_DEVICE_BENCH=1 xcodebuild \
  -project ios/NoBonk.xcodeproj -scheme NoBonk -configuration Release \
  -destination 'platform=iOS,id=YOUR_CONNECTED_DEVICE_ID' \
  -clonedSourcePackagesDirPath /private/tmp/nobonk-ios-fast-packages \
  -derivedDataPath /private/tmp/nobonk-ios-physical-benchmark \
  -resultBundlePath /private/tmp/nobonk-ios-physical-benchmark.xcresult \
  -parallel-testing-enabled NO \
  -only-testing:NoBonkQuickAccessTests/FastDeviceBenchmarkTests/testFastDetectorSyntheticDevicePerformance \
  DEVELOPMENT_TEAM=Z736W4KC49 CODE_SIGN_STYLE=Automatic ENABLE_TESTABILITY=YES test
```

Use a fresh result-bundle path on another run. `TEST_RUNNER_` passes the opt-in environment variable through `xcodebuild` to the hosted test. In Xcode, set the unprefixed `NOBONK_RUN_DEVICE_BENCH=1` in the scheme's Test environment and choose Release plus `ENABLE_TESTABILITY=YES` so `@testable import NoBonk` can access the unchanged production implementation. No signing or device run was performed by the agent authoring this harness.

The test runs explicit CPU, then Core ML-preferred configurations. For each it creates a session, performs three warm frames and twenty measured frames, releases that entire detector within an autorelease pool, then repeats with a new cache-eligible session. A previous provider session is not retained by the harness. The first session might already find a compiled cache; this is **not a claimed cold-cache measurement**, and the test deletes no caches.

The two synthetic patterns are gray114 and a deterministic RGB gradient, alternating each frame. Warm frames compare selected raw output values with the exact graph fixture generated using Python ORT 1.23.2; those bounds are a numerical smoke test (at most one model pixel and 0.005 score difference), not recognition accuracy. Measured frames omit the raw inspection callback. Setup includes the runtime's own one-run warm-up. Total is wall time around the production `detect` call; preprocess/inference/decode come from the implementation's phase clock. Each phase reports the median and nearest-rank p95 from twenty frames.

One concise JSON attachment, `NoBonk-Fast-synthetic-device-benchmark.json`, is kept in the xcresult. The same JSON is printed once with the `NOBONK_DEVICE_BENCH_JSON` prefix for machine extraction. It records model family from `uname` (not a unique device identifier), OS, pinned runtime/model hash, thermal and Low Power Mode before/after, provider configuration, first/second setup, all timing summaries and parity results. XCTest/Xcode may independently include device metadata in its own result bundle.

Core ML-preferred configuration does not prove GPU or Neural Engine placement. Thermal pressure, other apps and power mode are observed, not controlled. This is synthetic throughput on a specific phone, **not physical-camera FPS, obstacle effectiveness, sustained battery behavior, or a promise of performance on another iPhone**.

Validation by the author: generic unsigned physical-iPhone Release `build-for-testing` succeeded with `ENABLE_TESTABILITY=YES`. Execution on the connected phone is owned by the parent agent; no simulator was run.

## Sustained synthetic cadence probe

A second test, `FastSustainedBenchmarkTests/testFastDetectorAtProductionCadenceForThreeMinutes`, is independently opt-in. It skips ordinary runs, Simulator and Debug. It uses a single Core ML-preferred production detector and a **reused 720×1280 portrait BGRA gradient** matching rotated 720p camera-buffer dimensions. No camera is opened. Unlike the short tight-loop benchmark, this runs the actual `AnalysisCadence` with live `ProcessInfo` thermal/Low Power inputs and sleeps off Main between admissions. It does not change power mode or thermal policy.

Enable it using the earlier command but replace the opt-in/test selector and result path:

```sh
TEST_RUNNER_NOBONK_RUN_SUSTAINED_BENCH=1 \
TEST_RUNNER_NOBONK_SUSTAINED_SECONDS=180 \
xcodebuild -project ios/NoBonk.xcodeproj -scheme NoBonk -configuration Release \
  -destination 'platform=iOS,id=YOUR_CONNECTED_DEVICE_ID' \
  -clonedSourcePackagesDirPath /private/tmp/nobonk-ios-fast-packages \
  -derivedDataPath /private/tmp/nobonk-ios-physical-benchmark \
  -resultBundlePath /private/tmp/nobonk-ios-sustained-benchmark.xcresult \
  -parallel-testing-enabled NO \
  -only-testing:NoBonkQuickAccessTests/FastSustainedBenchmarkTests/testFastDetectorAtProductionCadenceForThreeMinutes \
  DEVELOPMENT_TEAM=Z736W4KC49 CODE_SIGN_STYLE=Automatic ENABLE_TESTABILITY=YES test
```

Duration defaults to 180 seconds; an explicit override is bounded to 30–600 seconds. Allow a test timeout longer than the requested duration plus setup. Cancellation is forwarded to the worker and checked between frames; no camera/session may be created by another test concurrently. The parent agent owns all phone runs.

The JSON attachment `NoBonk-Fast-sustained-device-benchmark.json` is also printed once with prefix `NOBONK_SUSTAINED_BENCH_JSON`. Approximately 30-second windows report admitted frames; median/nearest-rank-p95 preprocess, inference, decode and total milliseconds; target cadence interval; thermal-state frame counts; Low Power frame counts; and sampled resident memory. Timings are retained only for the current window (hard cap1,024 samples per metric), summarized, then discarded. Each inference has its own autorelease pool. No per-frame logs or unbounded measurements are retained.

Memory readings use Mach `task_info` / `MACH_TASK_BASIC_INFO` **resident_size for the whole hosted app process**, including UI/WebKit/test overhead. Readings are taken before/after setup, about once per second, at window boundaries and at the end. The reported peak is the highest **sampled RSS**, which can miss transient peaks. RSS is not energy, battery use, physical footprint or total allocated memory. Setup and the detector's internal warm-up are excluded from the timed cadence loop but reported separately. The constant gradient is not an accuracy test; no real-camera FPS, sustained camera/display cost or general phone-performance guarantee follows from this measurement.

Author validation for this addition: generic unsigned physical-iPhone Release `build-for-testing` succeeded; no simulator or device was run for the sustained test.
