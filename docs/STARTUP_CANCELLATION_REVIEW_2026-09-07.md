# Cooperative Android startup cancellation

Reviewed against `701c51308f516cef07126cd88cd3d44742ab626e` (Android 1.0.13, version code 14). This change does not change the release version, model assets, inference settings, provider preference, battery thresholds or permissions.

## Problem and behavior

Stopping or replacing an initialization cancelled its coroutine, but ONNX Runtime model creation and inference are synchronous. The provider selector could finish all remaining native probes after its owner stopped. On an uncached successful three-provider path, the existing algorithm creates four sessions and performs thirteen verification/timing inferences, before the foreground engine's additional warmup. These counts follow from the code; they are not phone timing measurements.

The selector now checks ownership/coroutine cancellation before and after resource creation, verification and each timed probe, and before recreating a winner. Stop observed after one timing probe therefore skips its remaining two timings and subsequent providers. A currently running native call is allowed to return before its session is closed. Cancellation is rethrown rather than interpreted as an unsupported provider, including when the final native operation returns an ordinary error after Stop.

Foreground Stop invalidates the initializing request before cancelling it and dismisses its loading state. A new Start gets a new request generation; the old job cannot clear the new job's loading state. The engine mutex still serializes foreground model replacement and inference. Stop/Start of an already ready model retains that model and its verified cache. Sensors only restart when the current model and scan owner are ready.

The background service checks both its coroutine and lifecycle stop state during loading. Failure/cancellation cleanup owns only the locally constructed, unadopted engine. A successful load still enters the existing non-cancellable main-thread adoption step: the service either adopts it or closes it if shutdown won the race. Cancellation does not close a session concurrently with native work, transfer engine ownership, bind the camera, or restart scanning after Stop.

## Resource and cache review

- Every acquired provider candidate is closed once on cancellation; timing candidates remain scoped by `use`.
- Cancellation after native session creation closes that session before tensor allocation. Cancellation after winner selection closes the still-unadopted prepared model.
- A completed choice is the only path to the existing verified-provider cache write. Partial timing results remain local to the selector. Existing valid cached choices remain usable on a later Start.
- A successfully loaded model remains owned by its engine even if cancellation arrives at the later foreground warmup boundary; the owner mutex controls its eventual replacement/disposal. The service separately closes unadopted engines. No runtime/worker interruption or cross-thread native close was introduced.

## Validation

- Initial full JVM run: 160 tests passed, zero failures/errors. After the final ordinary-error-after-Stop edge case was added, all ten focused `ProviderCancellationTest` tests passed. Existing selection/fallback tests passed in the full run.
- The focused tests cover cancellation before creation, during creation, cached verification, each of three timings, candidate close, winner reconstruction and final provider failure. A real worker/latch test holds a simulated native call open: Stop leaves the resource open until that call returns, then closes it once and starts no subsequent candidate. A fresh job can reuse a verified cache after cancellation.
- A debug-only Android integration test uses the real ViewModel, coroutine jobs, packaged Fast/Sharp models and ONNX Runtime. It checks immediate Stop → Start, cancelled model replacement, stable loading ownership, ordinary ready-model reuse and final stopped state. It runs only on the dedicated emulator, with no camera or physical-device access.
- Debug app and instrumentation APK builds passed. Final lint: zero errors, 57 existing warnings.

The first emulator install stalled while the Mac ran out of disk space. It was stopped, disposable earlier-build intermediates were removed, and the dedicated emulator was restarted. The native integration test then passed in 21.876 seconds. After the final error-path check and repeatable model-selection test adjustment, the exact final debug revision passed again in 26.341 seconds. These are test-suite durations on a loaded Mac emulator, not startup speed or phone FPS measurements.

## Limits and provenance

No physical Android phone was attached during the preceding local ADB inventory. Old/new-phone startup latency, thermal behavior, battery consumption and GPU/NPU selection remain unmeasured here. This change reduces unnecessary work after cancellation; it does not make an in-progress native call preemptible or promise an immediate return from that call. It preserves the existing measured-provider policy and completed-startup reuse.

The cancellation contract follows Kotlin's primary [`ensureActive` documentation](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/ensure-active.html), checked September 7, 2026: non-suspending work requires explicit cooperative checks. Confidence is high for the resource/operation-count claims, supported by the code and focused tests. The important limitation is synchronous native-call completion, not an assumption that coroutine cancellation interrupts ONNX Runtime. No claim is made that these emulator results establish real-phone performance.
