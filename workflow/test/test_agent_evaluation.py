"""Verify that the fixture auditor exposes failures and unrelated candidate changes."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("mp4_agent_cases", Path(__file__).resolve().parents[1] / "evaluation/agents/run.py")
cases = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(cases)


class AgentEvaluationTests(unittest.TestCase):
    def test_failed_verification_scope_violation_and_clean_control(self):
        with tempfile.TemporaryDirectory() as scratch, contextlib.redirect_stdout(io.StringIO()):
            root = Path(scratch) / "candidate"
            baseline = root.parent / "candidate-baseline.json"
            cases.prepare(root, "DEV-WF-02")
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertNotEqual(0, cases.audit(root, None, baseline))
            self.assertNotEqual(0, json.loads(output.getvalue())["check_exit"])
            (root / "app.py").write_text(cases.APP.format(attack="synthetic", implementation=cases.CORRECT), encoding="utf-8")
            self.assertEqual(0, cases.audit(root, None, baseline))
            (root / "unrelated.md").write_text("outside permitted scope", encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertNotEqual(0, cases.audit(root, None, baseline))
            self.assertEqual(["unrelated.md"], json.loads(output.getvalue())["out_of_scope"])


if __name__ == "__main__":
    unittest.main()
