# Fast model / Core ML feasibility

This isolated Mac experiment runs the exact pinned NoBonk Fast ONNX graph through ONNX Runtime's CPU and Core ML execution providers. It uses three generated tensors: uniform gray, RGB gradients and seeded noise. There are no photos, camera access, network requests, model downloads or iPhone app changes.

Run with a Python environment that already contains NumPy and an ONNX Runtime build with Core ML support:

```sh
python compare_fast.py /path/to/yolo26n_416.onnx --output /tmp/nobonk-parity.json
```

The script verifies the model SHA-256, input/output contract and finite outputs. It writes an ORT execution profile and compiled model cache to a new temporary folder. Profiling must show actual Core ML node events; a provider name in the available-provider list is insufficient. The cache and model are not committed.

## Observed September 7, 2026

On this Mac with ORT 1.23.2, all three cases used the Core ML execution provider in the profile. Maximum raw-box difference from the ORT CPU reference was 0.00562 model-input pixels; maximum class-score difference was 0.000000686. All outputs were finite and the expected `[1,84,3549]` shape. These are numerical smoke checks on synthetic inputs, not real-image or detector-accuracy validation.

The initial Core ML-backed inference took approximately 2.57 seconds; the following different synthetic inputs took 30 ms and 8 ms under the current desktop load. This is not a controlled benchmark, cached-start comparison or any estimate of iPhone speed. Core ML provider execution does not establish whether individual operations ran on CPU, GPU or Neural Engine. The experiment is evidence that the exact graph can execute through Core ML on this Mac.

## Gates before putting this in the iPhone app

1. Add an official iOS ORT build with Core ML support and preserve model/runtime licenses and source provenance. Desktop Python packages cannot be embedded in an iPhone app.
2. Compare actual camera preprocessing, RGB ordering, gray letterbox padding, rotation, box mapping, all selected classes and NMS. The graph's metadata identifies cat as 15 and dog as 16; horse 17 must not be relabeled cat.
3. Validate real frames around confidence/overlap thresholds. The current exploratory limits of one model pixel and 0.005 score are not calibrated acceptance criteria for alert behavior.
4. Preserve the main app's acknowledgment, explicit Start/Stop, serial inference owner, generation guards and thermal pacing. General object detection requires deliberate UI/policy changes; weights alone do not establish feature parity.
5. Measure startup, compiled-cache reuse, memory, sustained performance and battery on both an older and a recent iPhone. Cache keys must incorporate the model hash, runtime and configuration. A model-path cache key alone is unsafe when contents can change.

[Official ORT Core ML documentation](https://onnxruntime.ai/docs/execution-providers/CoreML-ExecutionProvider.html) describes iOS distributions, provider options and cache behavior. Core ML execution does not grant background camera access.
