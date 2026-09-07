# iPhone sensing experiments

Separate research projects for testing specific hypotheses. None is linked into the NoBonk app or establishes working background obstacle detection. No new hardware or paid service is required to inspect or build these sources.

| Experiment | Question | Current evidence |
| --- | --- | --- |
| [NearbyPhoneLab](NearbyPhoneLab/README.md) | Can one foreground-paired, consenting phone continue UWB ranging with a Live Activity? | 10 state tests and simulator/device builds pass; two physical phones still required |
| [BackgroundLab](BackgroundLab/README.md) | Does the system grant continued CPU processing after Home? | Foreground generated-frame baseline runs; simulator declined the background request |
| CameraPiPLab | Can an ordinary visible PiP renderer receive fresh rear-camera frames after Home, without calling privileges? | Isolated prototype in validation; no physical-frame result |
| AcousticLab | What can a matched filter infer from synthetic echoes, and where must it report uncertainty? | Offline investigation; no microphone use, emitted sound or hardware result |

Visible UI, a Live Activity or a successful build must never be substituted for evidence that sensing still receives fresh data. Stop, stale data, revoked permissions, interruptions and system cancellation are acceptance gates in each project. See each experiment's README before running it.
