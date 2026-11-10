"""Contract tests for the repository-wide verification entry point."""

import importlib
import subprocess
import unittest
from unittest.mock import patch


def _command_text(call):
    """Return command and working directory as searchable text."""
    args = call.args[0]
    if isinstance(args, (list, tuple)):
        args = " ".join(str(part) for part in args)
    cwd = call.kwargs.get("cwd", "")
    return f"{args} {cwd}".replace("\\", "/")


class VerifyRunnerTests(unittest.TestCase):
    def _main(self):
        try:
            module = importlib.import_module("workflow.verify")
        except ModuleNotFoundError as error:
            if error.name == "workflow.verify":
                module = None
            else:
                raise
        self.assertIsNotNone(module, "workflow.verify entry point must exist")
        runner = getattr(module, "main", None)
        self.assertTrue(callable(runner), "workflow.verify.main(argv=None) must be callable")
        return runner

    def test_default_runs_frontend_backend_openspec_and_ci_security_checks(self):
        main = self._main()
        with patch("workflow.verify.subprocess.run") as run:
            run.return_value = subprocess.CompletedProcess(args=[], returncode=0)

            result = main([])

        self.assertIn(result, (None, 0))
        commands = [_command_text(call) for call in run.call_args_list]
        all_commands = "\n".join(commands).lower()
        for expected in (
            "src/frontend/developer",
            "src/frontend/maintainer",
            "src/frontend/shared/markdown",
            "npm run verify",
            "src/backend/auth/test/run_setup.py",
            "src/backend/reporter/test/run-reporter-db.sh",
            "src/backend/reporter/test/report_timestamps_migration_test.py",
            "src/backend/reporter/test/run-reporter-api.sh",
            "workflow/security/check.py",
            "src/backend/platform/deployment/test",
            "workflow/test",
            "openspec validate --all --strict --no-interactive",
            "actionlint",
            "diff --check",
        ):
            with self.subTest(expected=expected):
                self.assertIn(expected.lower(), all_commands)

    def test_frontend_group_can_target_one_role_and_frontend_is_group_scoped(self):
        main = self._main()
        with patch("workflow.verify.subprocess.run") as run:
            run.return_value = subprocess.CompletedProcess(args=[], returncode=0)
            result = main(["--group", "frontend", "--frontend", "developer"])

        self.assertIn(result, (None, 0))
        commands = [_command_text(call).lower() for call in run.call_args_list]
        self.assertTrue(commands, "the selected frontend verification must run")
        self.assertTrue(all("src/frontend/developer" in command for command in commands))
        self.assertTrue(all("src/frontend/maintainer" not in command for command in commands))

        with patch("workflow.verify.subprocess.run") as run:
            with self.assertRaises(SystemExit):
                main(["--group", "tooling", "--frontend", "developer"])
        run.assert_not_called()

    def test_reporter_group_runs_every_reporter_test_file(self):
        main = self._main()
        with patch("workflow.verify.subprocess.run") as run:
            run.return_value = subprocess.CompletedProcess(args=[], returncode=0)
            result = main(["--group", "reporter"])

        self.assertIn(result, (None, 0))
        commands = "\n".join(_command_text(call) for call in run.call_args_list)
        for name in (
            "report_timestamps_migration_test.py",
            "create_report_api_test.py",
            "create_report_validation_test.py",
            "create_report_rate_limit_test.py",
            "list_own_reports_api_test.py",
            "read_own_report_api_test.py",
            "reporter_access_api_test.py",
            "edit_report_api_test.py",
            "reporter_status_api_test.py",
        ):
            with self.subTest(test_file=name):
                self.assertIn(name, commands)
        self.assertEqual(run.call_count, 3, "migration test plus two API groups")
        self.assertNotIn("frontend", commands)

    def test_failed_child_propagates_failure_and_stops_the_runner(self):
        main = self._main()
        failure = subprocess.CalledProcessError(17, ["mock-verification-child"])
        with patch("workflow.verify.subprocess.run", side_effect=failure) as run:
            with self.assertRaises(subprocess.CalledProcessError) as raised:
                main([])

        self.assertEqual(raised.exception.returncode, 17)
        self.assertEqual(run.call_count, 1, "later verification groups must not start")


if __name__ == "__main__":
    unittest.main()
