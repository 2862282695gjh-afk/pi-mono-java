"""Unit tests for parse_effective_data → EffectivePolicy."""
from __future__ import annotations

import unittest

from excel_io import EffectivePolicy, parse_effective_data


class ParseEffectiveDataTest(unittest.TestCase):
    def test_no_setting_is_last_point(self) -> None:
        policy = parse_effective_data("无需设置")
        self.assertEqual(policy, EffectivePolicy(metric="last_point", duration_seconds=60))

    def test_no_setting_phrase_is_last_point(self) -> None:
        policy = parse_effective_data("无需设置诊断时间和延迟时间")
        self.assertEqual(policy.metric, "last_point")
        self.assertEqual(policy.duration_seconds, 60)

    def test_window_ratio_is_ratio_true(self) -> None:
        policy = parse_effective_data("30min内，90%")
        self.assertEqual(policy.metric, "ratio_true")
        self.assertEqual(policy.duration_seconds, 1800)
        self.assertAlmostEqual(policy.threshold, 0.9)


if __name__ == "__main__":
    unittest.main()
