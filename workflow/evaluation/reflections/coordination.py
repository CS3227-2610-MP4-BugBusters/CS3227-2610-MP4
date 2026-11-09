#!/usr/bin/env python3
"""Create four isolated, equivalent-pair synthetic tasks for the frozen coordination comparison."""
import argparse
import hashlib
import json
from pathlib import Path


def prepare(destination):
    if destination.exists():
        raise ValueError("Use a new directory; do not carry fixes between conditions")
    destination.mkdir(parents=True)
    records = {}
    for task, maximum in (("title", 120), ("reason", 200)):
        for condition in ("single", "multi"):
            name = f"{condition}-{task}"
            root = destination / name
            root.mkdir()
            (root / "validation.py").write_text("def valid(value):\n    return False\n", encoding="utf-8")
            (root / "SPEC.md").write_text(
                f"VAL-{task.upper()}-01: valid(value) accepts strings whose stripped length is 1..{maximum} Unicode code points. Reject other types and blank/overlong strings. Return bool; do not mutate input.\n", encoding="utf-8")
            (root / "AGENTS.md").write_text(
                "Read SPEC.md and validation.py before editing. Only validation.py may change; tests/spec are frozen. Verifier tasks are read-only. Run python -B -m unittest discover -v and report actual results.\n", encoding="utf-8")
            (root / "test_contract.py").write_text(
                "import unittest\nfrom validation import valid\nclass ValidationTests(unittest.TestCase):\n"
                "    def test_valid_boundaries(self):\n"
                f"        for value in ('x', '  x  ', 'x'*{maximum}, '😀'*{maximum}):\n"
                "            self.assertIs(valid(value), True)\n"
                "    def test_invalid_inputs(self):\n"
                f"        for value in (None, 12, True, [], '', '   ', 'x'*{maximum+1}):\n"
                "            self.assertIs(valid(value), False)\n", encoding="utf-8")
            records[name] = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in root.iterdir()}
    (destination / "coordinator-baselines.json").write_text(json.dumps(records,indent=2),encoding="utf-8")
    print(json.dumps({"prepared":list(records),"protocol_sha256":hashlib.sha256(Path(__file__).with_name("protocol.json").read_bytes()).hexdigest()}))


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output",type=Path,required=True)
    prepare(parser.parse_args().output.resolve())
