"""Guard deployment hooks against cross-environment secret exposure."""
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[5]
DEPLOY_WORKFLOW = ROOT / ".github/workflows/deploy-development.yml"
PRODUCTION_WORKFLOW = ROOT / ".github/workflows/deploy-production.yml"


class DeploySecretScopeTest(unittest.TestCase):
    def test_deploy_step_receives_only_its_environment_hook(self):
        workflow = DEPLOY_WORKFLOW.read_text(encoding="utf-8")
        production_wrapper = PRODUCTION_WORKFLOW.read_text(encoding="utf-8")

        step = re.search(
            r"(?ms)^      - name: Request Render deployment for this revision\n"
            r"(?P<body>.*?)(?=^      - name:|\Z)",
            workflow,
        )
        self.assertIsNotNone(step, "the Render deployment step must be present")
        env_block = re.search(r"(?ms)^        env:\n(?P<body>.*?)(?=^        run:)", step["body"])
        self.assertIsNotNone(env_block, "the deployment step must declare its inputs")
        secret_bindings = re.findall(
            r"(?m)^          [A-Z][A-Z0-9_]*:\s*\$\{\{[^\n]*secrets\.[^\n]*\}\}\s*$",
            env_block["body"],
        )

        # A target-environment scoped common hook or one selected expression is
        # sufficient. Two separately injected hooks make the other environment's
        # credential available to the deployment process.
        with self.subTest("one target-selected hook is injected"):
            self.assertEqual(
                1,
                len(secret_bindings),
                "the deployment process must receive one target-selected hook credential",
            )
        with self.subTest("production wrapper does not inherit all secrets"):
            self.assertNotRegex(
                production_wrapper,
                r"(?m)^\s*secrets:\s*inherit\s*$",
                "the production wrapper must not forward every caller secret",
            )


if __name__ == "__main__":
    unittest.main()
