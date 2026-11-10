"""Exercise the actual inline deployment gate without requesting a deployment."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import textwrap
import unittest

WORKFLOW = Path(__file__).resolve().parents[5] / ".github/workflows/deploy-development.yml"
SHA = "a" * 40


def run(identifier, state="completed", result="success", attempt=1, **overrides):
    return dict(id=identifier, run_attempt=attempt, head_sha=SHA, head_branch="main",
                event="push", status=state, conclusion=result, **overrides)


class DeploymentGateTest(unittest.TestCase):
    def gate(self, runs):
        source = WORKFLOW.read_text(encoding="utf-8")
        block = re.search(r"(?m)^          import json, os, sys\n.*?^          '$", source, re.S)
        self.assertIsNotNone(block, "the checked inline Python gate must exist")
        script = textwrap.dedent(block.group(0).rsplit("\n", 1)[0])
        environment = dict(os.environ, EXPECTED_SHA=SHA)
        return subprocess.run([sys.executable, "-c", script], input=json.dumps({"workflow_runs": runs}),
                              text=True, capture_output=True, env=environment).returncode

    def test_newer_failure_blocks_older_success(self):
        self.assertNotEqual(0, self.gate([run(11, result="failure"), run(10)]))

    def test_pending_check_blocks_older_success(self):
        self.assertNotEqual(0, self.gate([run(10), run(11, state="in_progress", result=None)]))

    def test_latest_attempt_must_pass(self):
        self.assertNotEqual(0, self.gate([run(10), run(10, result="failure", attempt=2)]))

    def test_successful_recheck_allows_deployment(self):
        self.assertEqual(0, self.gate([run(10, result="failure"), run(11)]))

    def test_other_events_and_revisions_do_not_satisfy_gate(self):
        unrelated = run(12)
        unrelated["head_sha"] = "b" * 40
        dispatch = run(13)
        dispatch["event"] = "workflow_dispatch"
        self.assertNotEqual(0, self.gate([unrelated, dispatch]))

    def test_missing_or_malformed_status_fails_closed(self):
        self.assertNotEqual(0, self.gate([]))
        self.assertNotEqual(0, self.gate([None]))

    def test_query_does_not_hide_running_checks(self):
        self.assertNotIn("&status=completed", WORKFLOW.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
