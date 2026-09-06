# Changelog

## 1.0 (unreleased) — 2026-09-06 "look up" pass

### Performance
- **Zero-allocation frame path.** CameraX RGBA plane → reusable raw bitmap → one Canvas draw (rotate + downscale) into a reusable work bitmap. Three Bitmap allocations per frame removed; steady-state GC churn gone. Pure `FrameGeometry` helper with unit tests.
- **YOLO26 only, raw head + in-app NMS.** `Fast` (yolo26n, 9 MB) and `Sharp` (yolo26s, default). Measured 1.5× faster than the end-to-end export on CPU and more accelerator-friendly. yolo11s (36 MB, unused by default) and un-bundled picker entries removed. See `docs/MODEL_CHOICE.md`.

### Design
- New design system (`ui/theme/Theme.kt`, `ui/components`): one colour language (cyan system · green safe · amber watch · rose act-now), glass panels, consistent type and shapes.
- Detection screen rebuilt: corner-bracket boxes with soft fill and breathing "approaching" ring, danger vignette on HIGH, top status pill (live dot · NPU/CPU · model · battery), a **bottom control dock** with the nearest object and a proximity meter, alert-distance chips, collapsible model/detect settings, and clear actions. Unified notice banners; restyled LOOK UP, warm-up and camera-covered states; drawn wordmark.

### Release engineering
- ONNX Runtime 1.21.1 → 1.29.0 (all native libs 16 KB-aligned; Play requirement). Per-ABI sideload APKs; AAB for Play.
- GitHub Actions CI: unit tests, debug APK, unsigned release AAB, 16 KB check.
- README model section and acknowledgments brought current; `docs/MODEL_CHOICE.md` added.

