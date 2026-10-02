# Background CPU experiment — October 2, 2026

The opt-in OrtPowerExperimentTest completed on an Android 10 arm64 emulator. It uses both shipped models, ONNX Runtime 1.29.0, CPU provider with two threads, five warmup calls then twenty timed calls at a target10fps. Forward/reverse policy order reduces simple ordering bias; the host is shared and this is not a phone-energy measurement. The test now uses the verified file loader, avoiding the earlier low-heap asset allocation failure.

Approximate averages of the two runs:

| Model | Spin policy | Median inference(ms) | ProcessCPU(ms/frame) |
|---|---|---:|---:|
| Fast | default |24.3|57.1|
| Fast | off |30.2|50.7|
| Fast | bounded1ms |25.7|53.5|
| Sharp | default |56.4|121.0|
| Sharp | off |61.6|115.0|
| Sharp | bounded1ms |57.1|118.0|

Disabling spinning reduced process CPU time in this experiment but increased inference latency. The bounded setting had a smaller tradeoff. These results do not quantify battery savings or establish good settings on Pixel/Tensor, older physical devices, NNAPI or XNNPACK. The production app uses different thread counts and measures provider selection. **Production settings remain unchanged.**

A follow-up on physical hardware should compare identical camera scenes, provider/model, cadence, thermal starting state and notification/overlay configuration; measure CPU time, end-to-end fresh-frame latency and energy over long enough runs. Preserve output parity and Stop/camera interruption behavior. Do not choose a provider solely from CPU labels or this emulator result.
