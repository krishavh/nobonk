"""Algorithm sanity checks on idealized arrays, not sensor validation."""
import math
import unittest

from acoustic_lab import Config, analyze, analyze_pair, fixture


class SyntheticEchoTests(unittest.TestCase):
    def test_absent_echo_never_means_clear_path(self):
        result = analyze(fixture(), direct_index=96)
        self.assertEqual(result.status, "unresolved")
        self.assertIsNone(result.monostatic_proxy_m)

    def test_known_delay(self):
        result = analyze(fixture(((280, 0.5),)), direct_index=96)
        self.assertEqual(result.status, "matched_echo")
        self.assertLessEqual(abs(result.delay_samples - 280), 1)
        self.assertAlmostEqual(result.monostatic_proxy_m, 280 / 48000 * 343 / 2, places=2)

    def test_noise_only(self):
        result = analyze(fixture(direct_gain=0, noise_std=0.1), direct_index=96)
        self.assertEqual(result.status, "unresolved")

    def test_weak_echo_in_noise(self):
        result = analyze(fixture(((280, 0.02),), direct_gain=0.4, noise_std=0.08), direct_index=96)
        self.assertEqual(result.status, "unresolved")
        self.assertEqual(result.reason, "no_echo_above_evidence_gate")

    def test_strong_echo_in_seeded_noise(self):
        result = analyze(fixture(((400, 0.5),), direct_gain=0.4, noise_std=0.03), direct_index=96)
        self.assertEqual(result.status, "matched_echo")
        self.assertLessEqual(abs(result.delay_samples - 400), 1)

    def test_boundary_echo_is_not_precise_range(self):
        result = analyze(fixture(((87, 0.5),)), direct_index=96)
        self.assertEqual(result.status, "unresolved")
        self.assertIsNone(result.monostatic_proxy_m)

    def test_comparable_multipath_has_no_forced_range(self):
        result = analyze(fixture(((280, 0.45), (500, 0.45))), direct_index=96)
        self.assertEqual(result.reason, "multiple_comparable_echoes")
        self.assertIsNone(result.monostatic_proxy_m)
        self.assertEqual(len(result.candidates), 2)

    def test_no_direct_calibration(self):
        self.assertEqual(analyze(fixture(((280, 0.5),))).reason,
                         "missing_calibrated_direct_reference")

    def test_missing_direct_signal(self):
        self.assertEqual(analyze(fixture(direct_gain=0), direct_index=96).reason,
                         "direct_reference_not_observed")

    def test_invalid_and_truncated_samples(self):
        for values in [[], [math.nan], [math.inf]]:
            self.assertEqual(analyze(values, direct_index=96).reason, "invalid_samples")
        self.assertEqual(analyze([0.0] * 100, direct_index=96).reason, "incomplete_capture_window")

    def test_clipping_rejects(self):
        samples = fixture(((280, 0.5),))
        samples[0] = 1.0
        self.assertEqual(analyze(samples, direct_index=96).reason, "clipped_or_out_of_normalized_range")

    def test_consistent_chirp_pair(self):
        result = analyze_pair(fixture(((400, 0.45),), direction=1),
                              fixture(((400, 0.45),), direction=-1), direct_index=96)
        self.assertEqual(result.status, "matched_echo")

    def test_ambiguous_motion_surrogate(self):
        result = analyze_pair(fixture(((400, 0.45),), direction=1, echo_shift_hz=125),
                              fixture(((400, 0.45),), direction=-1, echo_shift_hz=125), direct_index=96)
        self.assertEqual(result.reason, "paired_chirp_disagreement_motion_or_interference")
        self.assertIsNone(result.monostatic_proxy_m)

    def test_invalid_band_or_configuration(self):
        for kwargs in [{"sample_rate": 40000}, {"duration_s": 0}, {"low_hz": math.nan},
                       {"min_proxy_m": 4}, {"min_echo_ratio": 0}]:
            with self.assertRaises(ValueError):
                Config(**kwargs)


if __name__ == "__main__":
    unittest.main()
