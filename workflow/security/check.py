#!/usr/bin/env python3
"""Run redacted secret detection and dependency scans; missing tools/network fail verification."""
import argparse
import hashlib
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
from tools import binary

ROOT = Path(__file__).resolve().parents[2]


def run(command, **kwargs):
    subprocess.run([str(part) for part in command], check=True, cwd=ROOT, **kwargs)


def secrets_check():
    # Scan the tracked working tree, including staged additions, without exporting private/untracked notes.
    paths = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode("utf-8").split("\0")
    with tempfile.TemporaryDirectory(prefix="mp4-secret-scan-") as scratch:
        snapshot = Path(scratch)
        for name in filter(None, paths):
            source = ROOT / name
            if source.is_symlink() or not source.resolve().is_relative_to(ROOT):
                raise RuntimeError("Refusing to follow a tracked path outside the repository")
            if source.is_file():
                destination = snapshot / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, destination)
        run([binary("gitleaks"), "dir", "--redact=100", "--no-banner", "--exit-code=1", snapshot])


def verify_wrapper():
    wrapper = ROOT / "src/backend/auth/gradle/wrapper/gradle-wrapper.jar"
    expected = "76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3"
    if hashlib.sha256(wrapper.read_bytes()).hexdigest() != expected:
        raise RuntimeError("Gradle wrapper checksum verification failed")


def dependencies_check():
    verify_wrapper()
    auth = ROOT / "src/backend/auth"
    wrapper = auth / ("gradlew.bat" if os.name == "nt" else "gradlew")
    run([wrapper, "--no-daemon", "-p", auth, "-I", ROOT / "workflow/security/dependencies.gradle", "writeSecurityInventory"])
    inventory = auth / "build/security/osv-scanner.json"
    run([binary("osv-scanner"), "scan", "source", "--lockfile", f"osv-scanner:{inventory}",
         "--lockfile", ROOT / "src/frontend/developer/package-lock.json",
         "--lockfile", ROOT / "src/frontend/maintainer/package-lock.json",
         "--lockfile", ROOT / "src/frontend/shared/markdown/package-lock.json"])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lint", nargs="?", const="actionlint", choices=["actionlint"],
                        help="Validate Actions workflows using pinned actionlint")
    parser.add_argument("--secrets-only", action="store_true")
    parser.add_argument("--wrapper-only", action="store_true")
    args = parser.parse_args(argv)
    if args.wrapper_only:
        verify_wrapper()
    elif args.lint:
        run([binary("actionlint"), "-shellcheck=", "-pyflakes="])
    else:
        secrets_check()
        if not args.secrets_only:
            dependencies_check()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as failure:
        print(f"Security verification failed ({type(failure).__name__}).", file=sys.stderr)
        sys.exit(1)
