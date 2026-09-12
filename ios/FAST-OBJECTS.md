# Optional Fast Objects detector

This preview uses the exact existing Android Fast ONNX graph locally on iPhone. Apple Vision **People** remains the default. The explicit **Fast Objects** choice covers person, bicycle, car, motorcycle, bus, truck, dog and cat. It does not provide general obstacle coverage, walls, steps, depth, calibrated metres or collision prediction. “Fast” is the model name; performance is measured rather than promised.

## Reproducible setup and provenance

No model weights or SDK caches are committed. From `ios/`, copy the existing local graph through the checksum gate:

```sh
python3 scripts/setup_fast_model.py /absolute/path/to/yolo26n_416.onnx
xcodegen generate
xcodebuild -project NoBonk.xcodeproj -scheme NoBonk \
  -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
```

Without that optional asset, the app builds and offers People mode; Fast Objects is disabled. There is no in-app model download. A supplied asset is verified again before opening its runtime session.

- Graph SHA-256: `9931a595afd6c780bdbe5ef12e99d34526b6f30dbc7641cf920572798e4ed96d` (9,833,208 bytes).
- Exact graph metadata: input `images`, Float32 `[1,3,416,416]`; output `output0`, Float32 `[1,84,3549]`; ONNX opset 17, raw head, `end2end=False`. Metadata and all 80 class names are recorded in `Tests/Fixtures/fast-graph-contract.json`.
- Export metadata identifies Ultralytics 8.4.142, September 6, 2026, and **AGPL-3.0**. This port does not change that model's license or establish new model distribution rights. The runtime's separate MIT license does not cover the weights. Preserve the model's provenance and existing project license obligations in any distribution.
- [Microsoft's official Swift package](https://github.com/microsoft/onnxruntime-swift-package-manager) is pinned to **1.24.2**, revision `b7fb7f7dea8a2469e6335d95a61b8f36d0dc83b2`, in both `project.yml` and `Package.resolved`.
- The [pinned package manifest](https://github.com/microsoft/onnxruntime-swift-package-manager/blob/1.24.2/Package.swift) obtains its binary from `https://download.onnxruntime.ai/pod-archive-onnxruntime-c-1.24.2.zip` with checksum `f7100a992d2a8135168c8afd831e6a58b465349101982aa58b3e11d36e600b54`. SwiftPM verifies the archive. This SDK version supports ARM64 iOS simulators; Intel simulator architecture is excluded.

## Compute and capture ownership

The existing serial camera queue owns initialization, a reused ORT session, reused input tensor, inference and decoding. Late video frames are discarded. Each capture delegate holds an immutable scan generation and mode, so an old queued frame cannot acquire a new scan's identity. Stop invalidates that generation immediately, including while model setup is busy. Camera capture still requires the safety gates and explicit Start, and stops on leaving the app.

Fast mode uses BGRA input; People mode continues to prefer native YUV and reuses its Vision request. The existing measured-work/thermal/Low Power Mode pacing applies to both. No audio-session behavior is changed by this port.

[Core ML provider configuration](https://onnxruntime.ai/docs/execution-providers/CoreML-ExecutionProvider.html) requests MLProgram, static shapes and `MLComputeUnits=ALL`; Apple/ORT determine partitioning and execution. The same graph falls back to a two-thread CPU session if Core ML initialization or its first warm-up run fails. We do not silently replace Objects with People. The UI reports **configuration**, not claimed GPU/Neural Engine placement. Later inference failures stop scanning with an error.

The compiled-model cache is separated by graph SHA, runtime, OS version and provider settings. It contains compiled model artifacts, not camera frames. Camera pixels are neither persisted nor uploaded. Structured setup/preprocess/inference/decode intervals use `OSSignposter`; measured phase times can be inspected locally. Debug builds are substantially slower in the scalar preprocessor: use an optimized Release build for phone profiling.

## Decoder and preprocessing contract

The production preprocessor implements center-sampled bilinear letterboxing, symmetric fractional padding of RGB 114, RGB planar Float32 normalization by 255, and inverse mapping to portrait preview coordinates. Interpolation is rounded to an 8-bit destination before normalization, as in Android's Canvas path. Skia's filter rounding may differ by one intensity level; the two platforms are **not asserted bit-identical for resized camera images**. Portrait rotation, channel order, stride and padding have direct tests.

Raw-head class selection uses the same supported-class order as Android: `[0,1,2,3,5,7,16,15]`. In particular **cat=15, dog=16; horse=17 is excluded**. Confidence is inclusive at 0.4; per-class NMS uses strict IoU greater than 0.45 and stable ties. Invalid/nonfinite/negative-area outputs are rejected rather than reordered into valid detections. Cues use three consecutive analyzed frames for the same selected class in the configured central image region, not object tracking or a physical distance.

## Repeatable validation

```sh
swift test
# Optional numerical probe on this Mac; no camera or simulator is used.
xcodebuild -project NoBonk.xcodeproj -scheme NoBonkFastProbe \
  -configuration Release -destination 'platform=macOS,arch=arm64' \
  -derivedDataPath /tmp/nobonk-fast-probe CODE_SIGNING_ALLOWED=NO build
/tmp/nobonk-fast-probe/Build/Products/Release/NoBonkFastProbe \
  NoBonk/Models/yolo26n_416.onnx Tests/Fixtures/fast-graph-reference.json
```

The command-line probe compiles the **production Swift preprocessor, ORT binding and decoder**, not a replacement implementation. It generates gray and RGB-gradient CVPixelBuffers, verifies runtime output shape/finiteness, and compares selected output samples against the same graph evaluated using Python ORT 1.23.2. `scripts/generate_fast_reference.py` regenerates the small fixture from a verified local graph, with pre-existing NumPy/ORT installations; it downloads nothing.

These synthetic samples validate tensor layout and runtime wiring, not recognition quality. Exploratory bounds are one model pixel for coordinates and 0.005 for scores. Mac results are not iPhone performance results and provider configuration is not proof of hardware placement. Physical iPhone work still needs startup, real objects, stop/restart, accuracy, sustained temperature/battery, and older-phone profiling. No App Store, TestFlight, signing or paid service is part of this change.

September 7 local validation: all 25 Swift core tests passed; unsigned iPhone Release and ARM64 generic simulator builds passed. The production Swift Mac probe passed on both explicit CPU and Core ML-preferred configurations: maximum sampled coordinate difference was 0.000527 model pixels, maximum sampled score difference was 0.000000278 versus the recorded Python reference. Both synthetic patterns produced no accepted detections, so positive-class decoding is covered separately by raw-head unit fixtures. Release preprocessing measured about 1.7–1.8 ms on this Mac; that number is not an iPhone prediction. The built iPhone app's bundled graph SHA was checked, and an incorrect source asset was rejected without replacing it.
