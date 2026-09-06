# NoBonk architecture

One frame, one path. Everything below runs on the phone; nothing touches the network.

```mermaid
flowchart TD
    CAM[CameraX ImageAnalysis<br/>RGBA_8888, KEEP_ONLY_LATEST] --> CAD{FrameCadence<br/>10 fps tracking · 5 fps idle · 3 fps long idle}
    CAD -->|skip| DROP[close frame]
    CAD --> GEO[FrameGeometry + one Canvas draw<br/>rotate · downscale · night-boost gain]
    GEO --> LUMA[brightness + variance<br/>blocked-camera / low-light]
    GEO --> YOLO[ObjectDetector<br/>YOLO26 n/s · ONNX Runtime<br/>NNAPI → XNNPACK → CPU · in-app NMS]
    GEO --> ENV[FrameAnalyzer<br/>blank wall · ground hazard]
    YOLO --> TRK[ApproachTracker<br/>IoU tracks · fill velocity · TTC · constant bearing]
    TRK --> POL[AlertPolicy<br/>fill-fraction ladder LOW / MEDIUM / HIGH<br/>user distance = sensitivity]
    POL --> HYST[linger + per-track HIGH mute<br/>bad-angle gating]
    HYST --> CUES[Cues<br/>haptic ladder · stereo chirp panned by bearing · optional voice]
    HYST --> UI[Result → Compose overlay / background HUD<br/>smoothed boxes · LOOK UP with side · stats]
    SENS[SensorMonitor<br/>phone angle] --> HYST
    INTR[CameraIntrinsics<br/>focal / sensor size] --> YOLO
```

## Modules

| Package | What lives there | Tested |
|---|---|---|
| `ml/FrameGeometry` | rotation + downscale plan, never upscales | ✅ |
| `ml/FrameCadence` | adaptive analysis interval | ✅ |
| `ml/LowLight` | blocked-camera, low-light, night-boost gain | ✅ |
| `ml/Letterbox`, `ml/Nms` | model input/output plumbing | ✅ |
| `ml/ObjectDetector` | ONNX Runtime session, EP fallback, raw-head or end-to-end decode, distance estimate | — (needs device) |
| `ml/CameraIntrinsics` | normalized focal from Camera2 characteristics | ✅ |
| `ml/ApproachTracker` | tracks, fill velocity, TTC, bearing tests | ✅ |
| `ml/AlertPolicy` | fill-fraction alert ladder | ✅ |
| `ml/AlertCue`, `ml/VoiceCue` | chirp synthesis + pan law, spoken phrase | ✅ |
| `ml/BoxSmoother` | display-only EMA | ✅ |
| `ml/DetectionEngine` | the single frame path shared by screen and service | — (Android) |
| `viewmodel/DetectionViewModel` | Compose state, persisted settings, fps | — |
| `service/DetectionService` | foreground camera service + WindowManager HUD | — |
| `ui/` | design system (`theme/Theme.kt`, `components/Ui.kt`), screens | — |

## Design principles

- **Safety logic never waits on presentation.** Smoothing, cadence back-off and cue rate limits act on the display and on power, never on the ladder that decides HIGH.
- **Escalate instantly, de-escalate slowly.** Linger, per-track mute and N-of-M bearing tests remove flicker without adding latency to the first warning.
- **Everything the phone can tell us, we use.** Focal length, sensor size, orientation sensors, battery — all read locally, none stored.
- **Privacy is structural.** No `INTERNET` permission in the merged manifest (library-injected ones are stripped), no frame ever leaves memory, history is local and deletable.
