#!/usr/bin/env python3
"""Run the repository's focused verification groups from one entry point."""

import argparse
import os
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]


def _run(name, command, cwd=None):
    print(f"[verify] {name}", flush=True)
    subprocess.run(command, cwd=cwd or ROOT, check=True)


def _python(*parts):
    return [sys.executable, *(str(part) for part in parts)]


def _npm_verify(frontend):
    command = ["npm", "run", "verify"]
    if os.name == "nt":
        command = ["cmd", "/d", "/c", "npm run verify"]
    _run(f"{frontend} frontend", command, ROOT / "src/frontend" / frontend)


def _openspec_validate():
    command = ["openspec", "validate", "--all", "--strict", "--no-interactive"]
    if os.name == "nt":
        command = ["cmd", "/d", "/c", "openspec validate --all --strict --no-interactive"]
    _run("OpenSpec validation", command)


def _frontend(args):
    selected = [args.frontend] if args.frontend else ["developer", "maintainer"]
    for frontend in selected:
        _npm_verify(frontend)


def _backend(_args):
    _run("auth Gradle wrapper checksum", _python("workflow/security/check.py", "--wrapper-only"))
    _run("auth setup", _python("src/backend/auth/test/run_setup.py"))


def _security(_args):
    _run("release security scans", _python("workflow/security/check.py"))


def _tooling(_args):
    _run(
        "deployment checks",
        _python("-m", "unittest", "discover", "-s", "src/backend/platform/deployment/test", "-p", "test_*.py"),
    )
    _run("verification runner tests", _python("-m", "unittest", "discover", "-s", "workflow/test", "-p", "test_*.py"))
    _openspec_validate()
    _run("GitHub Actions lint", _python("workflow/security/check.py", "--lint", "actionlint"))
    _run("Git whitespace check", ["git", "diff", "--check"])


GROUPS = {
    "frontend": _frontend,
    "backend": _backend,
    "security": _security,
    "tooling": _tooling,
}


def _fixture(args):
    root = Path(args.fixture_root).resolve()
    if not all((root / name).is_file() for name in ("SPEC.md", "test_contract.py")):
        raise ValueError("Fixture must contain its frozen spec and contract tests")
    allowed = {"PATH", "SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "TEMP", "TMP", "PATHEXT",
               "PROGRAMDATA", "LOCALAPPDATA", "APPDATA", "USERPROFILE"}
    environment = {key: value for key, value in os.environ.items() if key.upper() in allowed}
    subprocess.run(_python("-B", "-m", "unittest", "discover", "-v"), cwd=root,
                   env=environment, check=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--group", choices=("all", *GROUPS, "fixture"), default="all")
    parser.add_argument("--frontend", choices=("developer", "maintainer"))
    parser.add_argument("--fixture-root", help="Isolated synthetic evaluation directory; only with --group fixture")
    args = parser.parse_args(argv)
    if (args.group == "fixture") != bool(args.fixture_root):
        parser.error("--fixture-root is required only with --group fixture")
    if args.frontend and args.group != "frontend":
        parser.error("--frontend can only be used with --group frontend")

    if args.group == "fixture":
        _fixture(args)
        return 0
    selected_groups = GROUPS if args.group == "all" else {args.group: GROUPS[args.group]}
    for run_group in selected_groups.values():
        run_group(args)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, subprocess.CalledProcessError) as failure:
        code = failure.returncode if isinstance(failure, subprocess.CalledProcessError) else 1
        print(f"Verification failed ({type(failure).__name__}).", file=sys.stderr)
        raise SystemExit(code)
