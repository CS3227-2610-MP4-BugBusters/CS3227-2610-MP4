#!/usr/bin/env python3
"""Generate TDD handoff packets and validate them in a PreToolUse hook.

`make` builds a packet from a fixed template, so the format does not depend on the
orchestrator remembering it. `check` reads a hook payload on stdin and denies an Agent
call to a named role when its packet is incomplete or carries author claims.
"""
import argparse
import json
import re
import sys

FIELDS = ("TASK", "ROLE", "REVISION", "CRITERIA", "CONTRACT", "READ", "WRITE", "COMMANDS", "PRIOR", "SCRATCH", "OUT OF SCOPE")
ROLES = ("test_designer", "baseline_verifier", "implementer", "candidate_verifier", "security_reviewer")
INDEPENDENT = ("baseline_verifier", "candidate_verifier", "security_reviewer")
CLAIM = re.compile(
    r"\b(designer|implementer)('s)?\b[^.\n]{0,40}\b(says|said|reports|reported|claims|claimed|notes|believes|confirmed)\b"
    r"|\b(designer|implementer)('s)?\s+(report|notes|claims|reasoning)\s*:",
    re.I,
)
COVERAGE_METHOD = (
    "Coverage method: split each SHALL sentence and each THEN or AND line in CRITERIA into separate clauses. "
    "For each clause, quote the exact assertion that would fail if only that clause were broken, and name one wrong "
    "implementation it rejects. For every assertion with a branch or helper, check that each branch can be reached and "
    "can fail for the fixture input. Report FAIL only if a clause in CRITERIA has no such assertion, or a branch is "
    "unreachable or vacuous. Record gaps against design or contract detail outside CRITERIA as non-blocking notes."
)
REPORT_BUDGET = (
    "Report budget: write the full report to report.md under SCRATCH. Return only the verdict, one line per failed "
    "check or finding, and the report path, in at most 15 lines."
)
METHOD_ROLES = ("baseline_verifier", "candidate_verifier")


def problems(role, prompt):
    found = [f"missing field {name}:" for name in FIELDS if not re.search(rf"^{name}:", prompt, re.M)]
    stated = re.search(r"^ROLE:\s*(\S+)", prompt, re.M)
    if stated and stated.group(1) != role:
        found.append(f"ROLE says {stated.group(1)} but the agent is {role}")
    if role in INDEPENDENT:
        claim = CLAIM.search(prompt)
        if claim:
            found.append(f"author claim in an independent packet: '{claim.group(0)}'")
        if "Report budget:" not in prompt:
            found.append("missing the Report budget block")
    if role in METHOD_ROLES and "Coverage method:" not in prompt:
        found.append("missing the Coverage method block")
    return found


def render(args):
    values = {
        "TASK": args.task, "ROLE": args.role, "REVISION": args.revision, "CRITERIA": args.criteria,
        "CONTRACT": args.contract, "READ": args.read, "WRITE": args.write, "COMMANDS": args.commands,
        "PRIOR": args.prior, "SCRATCH": args.scratch, "OUT OF SCOPE": args.out_of_scope,
    }
    text = "\n".join(f"{name}: {values[name]}" for name in FIELDS) + "\n"
    if args.role in METHOD_ROLES:
        text += "\n" + COVERAGE_METHOD + "\n"
    if args.role in INDEPENDENT:
        text += "\n" + REPORT_BUDGET + "\n"
    if args.note:
        text += "\n" + args.note + "\n"
    return text


def make(args):
    text = render(args)
    found = problems(args.role, text)
    if found:
        print("Packet rejected: " + "; ".join(found), file=sys.stderr)
        return 1
    if args.output:
        with open(args.output, "w", encoding="utf8") as handle:
            handle.write(text)
    else:
        sys.stdout.write(text)
    return 0


def check():
    payload = json.load(sys.stdin)
    tool_input = payload.get("tool_input") or {}
    role = tool_input.get("subagent_type")
    if payload.get("tool_name") != "Agent" or role not in ROLES:
        return 0
    found = problems(role, tool_input.get("prompt", ""))
    if found:
        reason = "Handoff packet rejected: " + "; ".join(found) + ". Build it with workflow/packet.py make."
        print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "deny", "permissionDecisionReason": reason}}))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("check", help="Validate a PreToolUse hook payload from stdin")
    builder = commands.add_parser("make", help="Build a packet")
    builder.add_argument("--role", required=True, choices=ROLES)
    for name in ("task", "revision", "criteria", "read", "write", "commands", "scratch"):
        builder.add_argument(f"--{name}", required=True)
    builder.add_argument("--contract", default="none")
    builder.add_argument("--prior", default="none")
    builder.add_argument("--out-of-scope", required=True)
    builder.add_argument("--note", help="Task-neutral instructions; author claims are rejected")
    builder.add_argument("--output")
    args = parser.parse_args()
    return check() if args.command == "check" else make(args)


if __name__ == "__main__":
    sys.exit(main())
