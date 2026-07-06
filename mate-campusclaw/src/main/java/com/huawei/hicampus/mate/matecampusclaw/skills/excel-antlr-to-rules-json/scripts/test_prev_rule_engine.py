"""prev() DSL compile path: ANTLR emit + rule_engine transform."""
from __future__ import annotations

import unittest

from compile_trigger import compile_trigger_formula
from emit_rule_engine import (
    expression_uses_prev,
    transform_prev_for_rule_engine,
    verify_rule_engine_syntax,
)


class PrevRuleEngineTest(unittest.TestCase):
    def test_antlr_emits_prev_call(self) -> None:
        _ast, text, errs = compile_trigger_formula("[CO]==prev([CO])", ["CO"])
        self.assertEqual(errs, [])
        self.assertEqual(text, "(CO == prev(CO))")
        self.assertTrue(expression_uses_prev(text))

    def test_transform_for_rule_engine(self) -> None:
        self.assertEqual(
            transform_prev_for_rule_engine("(CO == prev(CO))"),
            "(CO == __prev_CO__)",
        )

    def test_verify_rule_engine_syntax_ok(self) -> None:
        self.assertIsNone(verify_rule_engine_syntax("(CO == prev(CO))"))

    def test_verify_rule_engine_syntax_rejects_invalid_syntax(self) -> None:
        err = verify_rule_engine_syntax("(CO == prev(CO")
        self.assertIsNotNone(err)
        self.assertIn("rule_engine compile error", err or "")


if __name__ == "__main__":
    unittest.main()
