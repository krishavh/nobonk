# Detection model provenance & AGPL-3.0 reproducibility

NoBonk's on-device detector runs **Ultralytics YOLO** weights, exported to ONNX and
bundled in `app/src/main/assets/`. Those weights are licensed under the
**GNU Affero General Public License v3.0 (AGPL-3.0)**. Per AGPL **§13**, the complete
corresponding source for the model must be made available to anyone who uses the app.
This file is that corresponding source for the model: it names the exact model files
the app expects and gives the exact, pinned recipe to regenerate them.

The `.onnx` files themselves are **not committed** (they are large binaries and are
gitignored). If a specific `.onnx` is shipped in a release, it is attached to the
matching GitHub Release / release tag; otherwise the recipe below is authoritative.

## Weights license

- **Upstream:** [Ultralytics YOLO](https://github.com/ultralytics/ultralytics)
- **License:** AGPL-3.0 (weights **and** export tooling)
- NoBonk is itself distributed under AGPL-3.0 (see `/LICENSE`) for compatibility.

## Models the app expects

The model roster is defined authoritatively in
`app/src/main/java/ai/genwhy/nobonk/viewmodel/DetectionViewModel.kt`
(`enum class AccuracyMode`) and the service/detector defaults in
`service/DetectionService.kt` and `ml/ObjectDetector.kt`.

| Asset filename       | Input size | Family | Upstream weights | NMS       | Used by mode |
|----------------------|-----------:|--------|------------------|-----------|--------------|
| `yolo11s.onnx`       | 416        | YOLO11 | `yolo11s.pt`     | in-app    | **S (default)** |
| `yolo11m.onnx`       | 416 & 640  | YOLO11 | `yolo11m.pt`     | in-app    | M (416), H (640) |
| `yolo26n_416.onnx`   | 416        | YOLO26 | `yolo26n.pt`     | NMS-free  | S (YOLO26)   |
| `yolo26s_416.onnx`   | 416        | YOLO26 | `yolo26s.pt`     | NMS-free  | M (YOLO26)   |
| `yolo26m_416.onnx`   | 416        | YOLO26 | `yolo26m.pt`     | NMS-free  | H (YOLO26)   |

Notes:
- **Default model:** `yolo11s.onnx` at **416 px** (the `ObjectDetector` constructor
  default, the `DetectionService.modelFile` default, and `AccuracyMode.Y11S`). The app
  ships and runs fine with only `yolo11s.onnx` present; the other assets are optional
  accuracy tiers surfaced in the settings UI.
- `yolo11m.onnx` is referenced at **both** 416 px (`Y11M`) and 640 px (`Y11H`). Export
  it with **dynamic spatial axes** (`dynamic=True`) so a single file serves both sizes;
  the detector reads the model's actual input dimension at load and adapts
  (`ObjectDetector.readInputSize`).
- The **YOLO26** entries are exported **NMS-free** (`skipNms = true` in the enum): the
  model emits post-NMS boxes directly, so no `yolo export ... nms=True` head is added.

## Exact export recipe (reproduces the bytes)

Exact-byte reproduction requires pinning the toolchain. Record the **exact** versions
you used alongside any shipped `.onnx` (they are part of the AGPL corresponding source):

```bash
# 1. Pin the exporter. Use the SAME version that produced the shipped .onnx.
#    YOLO11 weights export cleanly on the 8.3.x line; the YOLO26 weights require the
#    Ultralytics release that first published the yolo26* checkpoints. Pin explicitly.
python -m venv .venv && source .venv/bin/activate
pip install "ultralytics==8.3.0"        # YOLO11 tier  (bump for the YOLO26 tier)

# 2. Capture the exact provenance you must commit next to the model:
pip freeze | grep -E '^(ultralytics|torch|onnx|onnxslim|numpy)=='

# 3. Export each asset. Ultralytics downloads the .pt checkpoint on first use.

# --- YOLO11 tier (in-app NMS; opset 12; static 416, dynamic where reused) ---
yolo export model=yolo11s.pt format=onnx imgsz=416 opset=12               # -> yolo11s.onnx
yolo export model=yolo11m.pt format=onnx imgsz=640 opset=12 dynamic=True  # -> yolo11m.onnx (serves 416 & 640)

# --- YOLO26 tier (NMS-free head baked in; static 416) ---
yolo export model=yolo26n.pt format=onnx imgsz=416 opset=12   # -> yolo26n.onnx  (rename to yolo26n_416.onnx)
yolo export model=yolo26s.pt format=onnx imgsz=416 opset=12   # -> yolo26s.onnx  (rename to yolo26s_416.onnx)
yolo export model=yolo26m.pt format=onnx imgsz=416 opset=12   # -> yolo26m.onnx  (rename to yolo26m_416.onnx)

# 4. Place the files where the app loads them from:
mkdir -p app/src/main/assets
mv yolo11s.onnx yolo11m.onnx app/src/main/assets/
mv yolo26n.onnx app/src/main/assets/yolo26n_416.onnx
mv yolo26s.onnx app/src/main/assets/yolo26s_416.onnx
mv yolo26m.onnx app/src/main/assets/yolo26m_416.onnx
```

Only `yolo11s.onnx` is required for the app to run; export the rest only if you ship
those accuracy tiers.

## Reproducibility caveat (AGPL honesty)

`yolo export` output can vary bit-for-bit with the versions of `ultralytics`, `torch`,
`onnx`, and `onnxslim`, and with the CPU/GPU used. To let a downstream user reproduce
the **exact** shipped bytes, when you release a build:

1. Run step 2 above and paste the resulting version lines here (replace this caveat).
2. Record the SHA-256 of each shipped `.onnx` (e.g. `sha256sum app/src/main/assets/*.onnx`).
3. Tag the release commit so the source, this recipe, and the weights identifier all
   line up (see `docs/RELEASE_CHECKLIST.md` §6).

Because the functional behaviour (not the byte layout) is what matters to users, any
version on the pinned major line that exports the same upstream checkpoint at the same
`imgsz`/`opset` yields a behaviourally-equivalent model.
