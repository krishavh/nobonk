# AcousticLab — an offline feasibility experiment

**This is synthetic signal processing, not iPhone sonar and not a NoBonk feature.**
No microphone, speaker, audio files, network, permissions or iOS app target are used.
No physical devices or people were tested. The scripts generate arrays in memory,
print diagnostic JSON and exit. They do not generate a playable WAV.

The question is whether a real acoustic sensor merits a separate prototype:
could the phone analyze actual sound/echoes while another app is open? That would
use microphone access for its stated sensing purpose. It would not keep a camera
alive or grant camera access.

## Run the experiment

From this directory, using Python 3.9 or later (standard library only):

```sh
PYTHONDONTWRITEBYTECODE=1 python3 acoustic_lab.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest -v test_acoustic_lab.py
```

The tests exercise known delays, direct-path-only input, missing calibration,
noise without a reference, weak echoes in noise, a strong noisy echo, comparable
multipath, paired-chirp disagreement, search boundaries, clipping, nonfinite data,
truncated input, and invalid configuration. They are algorithm sanity checks.

The deterministic demonstration produces:

| Synthetic condition | Result |
| --- | --- |
| Only the direct speaker-to-microphone path | `unresolved` |
| Echo delayed 280 samples at 48 kHz | `matched_echo`, delay 280 samples |
| Two similarly strong, well-separated echoes | `unresolved` |
| Weak echo in seeded Gaussian noise | `unresolved` |
| Opposing chirps with a 125 Hz echo frequency-shift surrogate | `unresolved` |

`matched_echo` means that a delayed copy matches the mathematical template. It
does **not** mean a person, wall, obstacle, collision threat or safe route was
detected. `unresolved` never means the path is clear.

## What the model assumes

The default fixture is a Hann-windowed 20–22 kHz chirp of 10 ms at 48 kHz. These
are numerical parameters, not a recommended sound exposure or emission level.
The analyzer uses quadrature matched filters, a calibrated direct-path position,
a relative evidence gate and a conservative comparable-peak ambiguity gate.
Opposing chirps reject disagreement instead of reporting a forced velocity/range.

The printed `monostatic_proxy_m` is `343 * delay_samples / sample_rate / 2`.
Real phone speakers and microphones occupy different positions. Relative to the
direct path, an echo measures an excess **bistatic path length**, not generally
twice the obstacle distance. Hardware delays, direct coupling, body reflections,
mic beamforming, angle and temperature require separate investigation.

For the selected bandwidth, ideal range separation `c/(2B)` is about **8.6 cm**.
The 48 kHz sample-bin spacing corresponds to about **3.6 mm** in the simplified
round-trip calculation. Bin spacing is not resolving power or measurement
accuracy. A perfect synthetic 280-sample match must not become a claim of 1 mm
real-world accuracy.

The gates are lab heuristics, not calibrated probabilities. Coherent paths closer
than one bandwidth-limited cell can merge into one match. A dominant indirect
reflection can also look like one echo. The frequency-shift fixture is not a full
moving-room or walking-body simulation. Gaussian noise does not simulate wind,
handling, clothing, other phones, music or nonlinear hardware. This deliberately
small program has no field reliability claim.

## Evidence reviewed on September 7, 2026

**There is a documented background audio route.** Apple's
[record category](https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/record)
allows microphone recording when backgrounded or locked using `UIBackgroundModes`
`audio`, with permission. Apple's
[playAndRecord category](https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/playandrecord)
supports simultaneous real playback/capture; mixing requires configuration.
This supports feasibility of a genuine acoustic feature, not guaranteed App Review
acceptance, continuous availability, or indefinite execution under every condition.
Calls, alarms and other nonmixable sessions can interrupt audio. Start deliberately
while foregrounded, stop promptly, and report interruptions as unavailable.

**iPhone audio geometry cannot be imported from Android research.** Apple's
[sample-rate preference](https://developer.apple.com/documentation/avfaudio/avaudiosession/setpreferredsamplerate(_:))
is a request, with a device-dependent range typically 8–48 kHz; inspect actual
hardware formats after activation. [Measurement mode](https://developer.apple.com/documentation/avfaudio/avaudiosession/mode-swift.struct/measurement)
minimizes processing and uses the primary microphone. Apple's
[stereo capture sample](https://developer.apple.com/documentation/avfaudio/capturing-stereo-audio-from-built-in-microphones)
selects microphone/beamformer data sources and supported polar patterns. Stereo
channels are not proof of independent raw top/bottom microphones suitable for
time-difference-of-arrival angle estimation. Begin with single-channel evidence.

**Directly relevant research is promising but limited.**
[ObstacleWatch, IMWUT 2018, DOI 10.1145/3287072](https://sheng-tan.github.io/pdf/obstaclewatch.pdf)
used speaker chirps and two microphones for pedestrian collision predictions.
Its evaluation used Samsung S5, Note5 and S8+, not iPhones, and reports over 92%
aggregate results with its own recordings and protocol. It studied frontal walls,
bins, signs, cars and people; human reflections were weaker. Phone orientation
changed accuracy. Holes, ramps and threats from the side/back were outside scope,
and concurrent nearby chirping devices remained future work. These results do not
validate NoBonk, this lab, an iPhone, or a public safety claim.

**There is substantial disconfirming evidence.**
[Practical Problems for Acoustic Sensing, MobiCom 2022, DOI 10.1145/3495243.3560527](https://pmc.ncbi.nlm.nih.gov/articles/PMC12798654/)
measured unwanted audible leakage from nominally ultrasonic sensing, including
iPhone 11 Pro. Raising volume did not reliably improve the useful signal. The
study found iOS mixes strong sensing/music signals by scaling down their volumes,
so mixing capability does not imply an unaffected listening experience. It also
measured a 22% Samsung S9+ battery drop after two hours of maximum-volume chirps
and demonstrated degraded sensing under device motion. Those particular power
and leakage measurements are not estimates for a current iPhone or NoBonk.

[PowerPhone, MobiCom 2023](https://pmc.ncbi.nlm.nih.gov/articles/PMC12765216/)
distinguishes higher-rate audio delivered by interpolation from actual hardware
sampling. Its true 192 kHz path required Android driver/system reconfiguration;
the authors could not apply that reconfiguration to iOS. Do not assume that an
API returning more samples means added acoustic bandwidth or new information.

**Passive audio is a different, narrower experience.** Apple's
[SoundAnalysis](https://developer.apple.com/documentation/soundanalysis)
provides sound-event classification, not distance measurements or proof that a
quiet obstacle is absent. [Auto++, IMWUT 2017](https://www.winlab.rutgers.edu/~sugangli/papers/ubicomp2017.pdf)
studied approaching-car audio on Nexus phones. It included a Chevrolet Volt,
so blanket claims that passive acoustics cannot detect any electric car would be
incorrect. Its experiment nonetheless does not establish reliable detection of
arbitrary quiet cars, bicycles, people or stationary walls. A passive experiment
should say “sound noticed,” never “path clear” or “obstacle at 2 m.”

**“Ultrasonic” is not a guarantee of inaudibility or comfort.** A
[primary child-audiometry study](https://pmc.ncbi.nlm.nih.gov/articles/PMC9450627/)
measured hearing through 18 kHz. A [2024 dog-hearing study](https://pmc.ncbi.nlm.nih.gov/articles/PMC10892234/)
tested five dogs at 20 kHz and found clear sensitivity, while cautioning against
population-wide conclusions from a small sample. Neither paper certifies any
phone waveform as safe or comfortable. An adult failing to hear a chirp is not
an exposure measurement; device amplification can also create audible leakage.
No chirps should be tried around children, pets or unsuspecting bystanders as an
informal test. Any later physical test needs controlled, measured sound levels.

**New routing support deserves a separate test.** iOS 26.2
[dualRoute](https://developer.apple.com/documentation/avfaudio/avaudiosession/mode-swift.struct/dualroute)
under `multiRoute` can use built-in mic/speaker plus a secondary bidirectional
audio device and requires `allowBluetoothHFP`. Hardware volume controls affect
both routes and processing may be applied. Apple's
[channel-mapping documentation](https://developer.apple.com/documentation/avfaudio/routing-audio-to-specific-devices-in-multidevice-sessions)
shows how to keep selected output channels silent and target others. This may
allow phone-only sensing output with headphone cues; it is not proof that another
app's music remains available or that the ultrasonic response is usable.
Never send experimental chirps into headphones. Verify actual route and mapping,
and stop if the route changes. Older devices need a separate availability path.

**Review and privacy are separate gates.**
[Apple's Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)
require intended API/background-mode use, explicit microphone consent and clear
indication of recording (2.5.1, 2.5.4, 2.5.14), and prohibit encouraging physically
risky device use (1.4.5). A real audio sensing session has a stronger intended-use
case than a silent keepalive, but no Apple staff statement found here preapproves
this sonar product. Explain its actual sensor, control and limitation behavior.
[Apple DTS, August 2024](https://developer.apple.com/forums/thread/761287)
confirms no public API to remove the microphone indicator or add custom sounds to
the system's Sound Recognition list. Keep processing local, use bounded in-memory
buffers, discard them, and never claim that “not saved” means “not accessed.”

## Next experiment, only after a deliberate hardware-test decision

1. Keep a separate native audio-only diagnostic app. Record actual input/output
   formats, route, microphone data source, processing options and interruption
   events. Use CPU signal processing first; audio background permission does not
   itself promise background GPU/Neural Engine execution.
2. Establish actual speaker-to-microphone frequency response and calibrated
   sound output on each device. Do not compensate for a weak response by blindly
   maximizing volume. Test with no headphones, no people/pets in the field, and no
   street or walking-collision trial.
3. Use an independently measured stationary target at 0.5, 1, 2 and 3 m plus
   empty-space controls. Vary target size, angle and hard/soft surfaces. Compare
   distributions of fresh echo evidence, not just a convenient median error.
4. Then challenge it with case/hand occlusion, movement, reverberation, wind,
   playback from another app, a second sensing device and route changes. Report
   misses and false alerts separately. Limit any supported mode to demonstrated
   conditions; otherwise return unavailable.
5. Test background, lock/unlock, call/Siri interruption and Stop/restart on a
   physical phone detached from Xcode. Verify continuous fresh sample timestamps
   and immediate teardown. No audio buffer means no sensing claim.
6. Test cues separately: Apple's
   [haptics-during-recording option](https://developer.apple.com/documentation/avfaudio/avaudiosession/allowhapticsandsystemsoundsduringrecording)
   defaults to false. If enabled for feedback, mark cue/handling contamination and
   do not interpret the app's own output as a new environmental event.

No such hardware experiment has been performed by this lab. The current decision
is to retain this as a research branch, with the camera experience and its honest
foreground limitations remaining the shipping iPhone path.
