# Why YOLO26, and why the raw head — model evaluation (2026-09-06)

NoBonk needs a real-time detector for **people, bikes, cars, buses, trucks, dogs, cats** on a mid-range Android phone, CPU or NNAPI, fully offline. This is what we compared and measured.

## Candidates

| Model | License | COCO mAP (n/s) | Why / why not |
|---|---|---|---|
| **YOLO26** (Ultralytics, Jan 2026) | AGPL-3.0 | 40.9 / ~48 | Up to 43% faster CPU inference than YOLO11 in the nano tier; native end-to-end option; mature ONNX export. **Chosen.** AGPL obligations already met (repo public, recipe in MODEL.md). |
| YOLO11 (Ultralytics) | AGPL-3.0 | 39.5 / 47.0 | Previous default. Slower than YOLO26 at the same size. Retired from the roster. |
| RF-DETR Nano (Roboflow, Apache-2.0) | Apache-2.0 | ~48 (N) | Attractive license; DINOv2 backbone. But a transformer backbone is heavy on phone CPU and NNAPI support for its ops is poor; ONNX nano is still several × the compute of yolo26n. Revisit if it gets a mobile-tuned export. |
| D-FINE nano / YOLOX nano | Apache-2.0 | 42.8 / 25.8 | D-FINE is DETR-family (same mobile concern); YOLOX-nano is fast but well below YOLO26n accuracy. |

## Head format: end-to-end vs raw + in-app NMS (measured)

ONNX Runtime 1.28, CPU EP, 4 threads, x86 workstation, 416×416, p50 of 20 runs. Relative order is what matters; phones are slower in absolute terms.

| Export | Output | Size | p50 |
|---|---|---|---|
| yolo11s (old default) | [1,84,3549] raw | 38 MB | 50.0 ms |
| yolo26s **end-to-end** (old bundle) | [1,300,6] | 38 MB | 47.4 ms |
| **yolo26s raw head** (new bundle, "Sharp") | [1,84,3549] | 38 MB | **31.3 ms** |
| **yolo26n raw head** (new bundle, "Fast") | [1,84,3549] | 9.8 MB | **14.7 ms** |
| yolo26n INT8 dynamic quant | [1,84,3549] | 2.8 MB | 21.2 ms (slower — rejected) |
| yolo26s INT8 dynamic quant | [1,84,3549] | 9.9 MB | 44.4 ms (slower — rejected) |

The end-to-end graph embeds TopK/gather post-processing that costs ~16 ms here and uses ops accelerators often don't support (NNAPI falls back to CPU per-op). The app's own per-class NMS (`ml/Nms.kt`, unit-tested) over the 8 relevant classes is sub-millisecond, so **raw head + in-app NMS** wins on both speed and portability. Dynamic INT8 quantization slowed both models on this CPU (dequant overhead on conv-heavy graphs); static QDQ INT8 with calibration might behave differently on ARM and is left as future work.

## Where the numbers must be re-measured
On device. The in-app HUD reports the active execution provider and inference time; the release checklist asks for a 60-second walk with each mode on the target phone and records fps + battery.
