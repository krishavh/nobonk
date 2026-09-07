"""Synthetic-only echo analysis. No device, audio-file, playback, or network APIs.

A matched echo is evidence of a delayed template, never evidence of a safe path.
All distances are monostatic proxies in an idealized simulation, not measurements.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
import json
import math
import random
import statistics


@dataclass(frozen=True)
class Config:
    sample_rate: int = 48000
    low_hz: float = 20000.0
    high_hz: float = 22000.0
    duration_s: float = 0.010
    sound_speed_m_s: float = 343.0
    min_proxy_m: float = 0.30
    max_proxy_m: float = 3.0
    min_echo_ratio: float = 0.12
    ambiguity_ratio: float = 0.65

    def __post_init__(self):
        values = tuple(asdict(self).values())
        if not all(math.isfinite(v) for v in values):
            raise ValueError("Configuration must be finite")
        if not (0 < self.low_hz < self.high_hz < self.sample_rate / 2):
            raise ValueError("Chirp must be strictly below actual Nyquist frequency")
        if self.duration_s <= 0 or self.duration_s * self.sample_rate < 16:
            raise ValueError("Chirp duration is too short")
        if not (0 < self.min_proxy_m < self.max_proxy_m and self.sound_speed_m_s > 0):
            raise ValueError("Invalid simulated range or sound speed")
        if not (0 < self.min_echo_ratio < 1 and 0 < self.ambiguity_ratio < 1):
            raise ValueError("Invalid gate ratios")

    @property
    def resolution_proxy_m(self):
        # Bandwidth-limited ideal separation; NOT accuracy or a hardware guarantee.
        return self.sound_speed_m_s / (2 * (self.high_hz - self.low_hz))


@dataclass(frozen=True)
class Result:
    status: str
    reason: str
    delay_samples: int | None = None
    monostatic_proxy_m: float | None = None
    candidates: tuple[int, ...] = ()


def chirp(config=Config(), direction=1, frequency_shift_hz=0.0, quadrature=False):
    """Return an in-memory synthetic chirp; never play or export it."""
    if direction not in (-1, 1):
        raise ValueError("Direction must be -1 or 1")
    count = round(config.duration_s * config.sample_rate)
    start = config.low_hz if direction == 1 else config.high_hz
    slope = direction * (config.high_hz - config.low_hz) / config.duration_s
    phase_offset = math.pi / 2 if quadrature else 0.0
    return [
        0.5 * (1 - math.cos(2 * math.pi * i / (count - 1)))
        * math.cos(
            2 * math.pi * ((start + frequency_shift_hz) * i / config.sample_rate
                          + 0.5 * slope * (i / config.sample_rate) ** 2)
            + phase_offset
        )
        for i in range(count)
    ]


def fixture(echoes=(), *, config=Config(), direct_index=96, direct_gain=0.8,
            noise_std=0.0, seed=7, direction=1, echo_shift_hz=0.0):
    """Each echo is (delay in samples AFTER direct reference, relative gain).

    Motion surrogate shifts echo frequency only. It is not a moving-room model.
    Seeded Gaussian noise is not representative of real wind, music or microphones.
    """
    source = chirp(config, direction)
    reflected = chirp(config, direction, echo_shift_hz)
    max_lag = math.ceil(2 * config.max_proxy_m / config.sound_speed_m_s * config.sample_rate)
    size = direct_index + max_lag + len(source) + 1
    rng = random.Random(seed)
    recording = [rng.gauss(0, noise_std) for _ in range(size)]
    for offset, gain, signal in [(direct_index, direct_gain, source)] + [
        (direct_index + delay, direct_gain * gain, reflected) for delay, gain in echoes
    ]:
        if offset < 0 or offset + len(signal) > size:
            raise ValueError("Synthetic echo does not fit the fixture")
        for i, sample in enumerate(signal):
            recording[offset + i] += gain * sample
    return recording


def analyze(recording, *, config=Config(), direct_index=None, direction=1):
    """Look for a dominant delayed match relative to a calibrated direct reference.

    Reject ambiguity, low evidence and bad input instead of forcing a distance.
    Thresholds below are deliberately conservative lab heuristics, not calibrated
    false-alarm probabilities. Clipping is checked in normalized synthetic units.
    """
    if not recording or any(not math.isfinite(v) for v in recording):
        return Result("unresolved", "invalid_samples")
    if max(abs(v) for v in recording) >= 1.0:
        return Result("unresolved", "clipped_or_out_of_normalized_range")
    if direct_index is None or not isinstance(direct_index, int) or direct_index < 0:
        return Result("unresolved", "missing_calibrated_direct_reference")
    reference = chirp(config, direction)
    quadrature = chirp(config, direction, quadrature=True)
    energy = sum(v * v for v in reference)
    min_delay = math.ceil(2 * config.min_proxy_m / config.sound_speed_m_s * config.sample_rate)
    max_delay = math.floor(2 * config.max_proxy_m / config.sound_speed_m_s * config.sample_rate)
    if len(recording) < direct_index + max_delay + len(reference):
        return Result("unresolved", "incomplete_capture_window")

    def match(index):
        window = recording[index:index + len(reference)]
        in_phase = sum(a * b for a, b in zip(window, reference))
        out_phase = sum(a * b for a, b in zip(window, quadrature))
        return math.hypot(in_phase, out_phase) / energy

    direct = match(direct_index)
    if direct < 0.1:
        return Result("unresolved", "direct_reference_not_observed")
    curve = [match(direct_index + lag) for lag in range(min_delay, max_delay + 1)]
    # Median envelope noise is intentionally robust to a small number of echoes.
    floor = statistics.median(curve)
    threshold = max(config.min_echo_ratio * direct, floor * 6)
    peak_indices = [i for i in range(1, len(curve) - 1)
                    if curve[i] >= curve[i - 1] and curve[i] > curve[i + 1]
                    and curve[i] >= threshold]
    # One bandwidth-limited cell prevents carrier/nearby sidelobes being counted
    # as independent echoes. It cannot resolve targets within this cell.
    cell = math.ceil(config.sample_rate / (config.high_hz - config.low_hz))
    selected = []
    for i in sorted(peak_indices, key=lambda j: curve[j], reverse=True):
        if all(abs(i - j) > cell for j in selected):
            selected.append(i)
    if not selected:
        return Result("unresolved", "no_echo_above_evidence_gate")
    best = selected[0]
    plausible = [i for i in selected if curve[i] >= config.ambiguity_ratio * curve[best]]
    delays = tuple(sorted(min_delay + i for i in plausible))
    if len(plausible) > 1:
        return Result("unresolved", "multiple_comparable_echoes", candidates=delays)
    if best < cell or best > len(curve) - 1 - cell:
        return Result("unresolved", "echo_near_search_boundary", candidates=delays)
    delay = min_delay + best
    return Result("matched_echo", "one_dominant_delayed_match", delay,
                  delay / config.sample_rate * config.sound_speed_m_s / 2, delays)


def analyze_pair(up, down, *, config=Config(), direct_index=None):
    """Reject disagreement between up/down chirps; not a velocity estimator."""
    first = analyze(up, config=config, direct_index=direct_index, direction=1)
    second = analyze(down, config=config, direct_index=direct_index, direction=-1)
    if first.status != "matched_echo" or second.status != "matched_echo":
        return Result("unresolved", "paired_chirp_lacks_two_matches")
    if abs(first.monostatic_proxy_m - second.monostatic_proxy_m) > config.resolution_proxy_m:
        return Result("unresolved", "paired_chirp_disagreement_motion_or_interference",
                      candidates=(first.delay_samples, second.delay_samples))
    # Return one original observation; averaging would suggest unearned precision.
    return first


def demo():
    config = Config()
    examples = {
        "direct_path_only": analyze(fixture(), direct_index=96),
        "known_synthetic_delay_280_samples": analyze(fixture(((280, 0.5),)), direct_index=96),
        "comparable_multipath": analyze(fixture(((280, 0.45), (500, 0.45))), direct_index=96),
        "noisy_weak_echo": analyze(fixture(((280, 0.02),), direct_gain=0.4, noise_std=0.08), direct_index=96),
        "motion_surrogate": analyze_pair(
            fixture(((400, 0.45),), direction=1, echo_shift_hz=125),
            fixture(((400, 0.45),), direction=-1, echo_shift_hz=125),
            direct_index=96),
    }
    print(json.dumps({"synthetic_only": True, "physical_devices_tested": 0,
                      "ideal_bandwidth_resolution_proxy_m": config.resolution_proxy_m,
                      "results": {name: asdict(value) for name, value in examples.items()}}, indent=2))


if __name__ == "__main__":
    demo()
