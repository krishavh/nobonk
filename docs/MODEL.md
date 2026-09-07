# NoBonk model assets and provenance

NoBonk uses Ultralytics YOLO26 weights under AGPL-3.0. The app is also AGPL-3.0; see [LICENSE](../LICENSE) and [Ultralytics](https://github.com/ultralytics/ultralytics). Model assets are distributed in the public release bundle and excluded from Git because of their size.

## Install the exact shipped models

From the repository root, run `python3 scripts/install_verified_models.py`. It downloads the pinned public v1.0.10-rc-ac1b822 AAB, checks its SHA-256, extracts only the two expected files, checks each model hash, then installs them in `app/src/main/assets/`. An existing downloaded AAB can be supplied with `--bundle PATH`. CI runs the same script before building; a checksum or download failure stops artifact creation.

| Asset | Mode | Input | Output / suppression | SHA-256 |
|---|---|---|---|---|
| yolo26n_416.onnx | Fast; default for new installations | 416 × 416 | Raw head; class-aware NMS in the app | 9931a595afd6c780bdbe5ef12e99d34526b6f30dbc7641cf920572798e4ed96d |
| yolo26s_416.onnx | Sharp; optional | 416 × 416 | Raw head; class-aware NMS in the app | 706b34041df74890c4d5720b26e47ced3d825a3b7dd1bad6cf73fcdcb7740356 |

Existing users keep their saved model selection. Both assets are required. The old YOLO11 and YOLO26-medium recipes do not describe current releases.

## Export provenance

The release handoff records ultralytics 8.4.142, torch 2.14.0+cpu, ONNX opset 17, a static 416-pixel input and `simplify=True`, with the raw one-to-many head (`end2end=False`, `nms=False`). Upstream checkpoints are `yolo26n.pt` and `yolo26s.pt`. Full exporter dependency locks and checkpoint hashes were not included in that handoff, so an exact-byte rebuild from the upstream checkpoints is **not yet established**. The verified extraction above reproduces the actual shipped assets without pretending an unpinned export is equivalent.

Before replacing these assets, record upstream checkpoint hashes, the complete exporter environment and command, model hashes, output shape, preprocessing and suppression behavior. Validate detections on physical devices and update the pinned installer in the same change. Do not substitute a different YOLO head while retaining the existing hashes or documentation.

## Execution and startup

The Android runtime measures XNNPACK (optimized CPU), NNAPI and the standard CPU provider using a warm-up plus three timed inferences. XNNPACK wins close results; another provider must be over 15% faster. NNAPI CPU-reference fallback is disabled, but unsupported graph nodes can still run in ONNX Runtime on the CPU. **NNAPI selection does not prove GPU/NPU placement.** The interface labels it NNAPI, not NPU.

The measured choice is kept privately for at most 30 days, keyed by model bytes, Android build fingerprint, ONNX Runtime version, app version and selection-policy revision. Subsequent loads verify that choice with one inference instead of benchmarking every provider again. A verification failure triggers fresh selection. A separate full preprocessing/decode warm-up checks the complete image path. Fast uses the smaller model for lower latency and memory use; Sharp trades more processing for finer detection. No frame-rate guarantee is made without measurements on the actual phone.

References: [ONNX Runtime mobile deployment](https://onnxruntime.ai/docs/tutorials/mobile/), [NNAPI configuration](https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html).
