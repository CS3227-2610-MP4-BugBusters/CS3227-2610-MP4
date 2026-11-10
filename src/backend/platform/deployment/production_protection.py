"""Validate production environment metadata before using a deploy hook."""
import json
import sys


def protected(environment):
    if not isinstance(environment, dict) or str(environment.get("name", "")).casefold() != "production":
        return False
    rules = environment.get("protection_rules")
    if not isinstance(rules, list):
        return False
    for rule in rules:
        if not isinstance(rule, dict) or rule.get("type") != "required_reviewers" or rule.get("prevent_self_review") is not True:
            continue
        reviewers = rule.get("reviewers")
        if not isinstance(reviewers, list):
            continue
        for entry in reviewers:
            if not isinstance(entry, dict) or entry.get("type") not in ("User", "Team"):
                continue
            reviewer = entry.get("reviewer")
            if isinstance(reviewer, dict) and type(reviewer.get("id")) is int and reviewer["id"] > 0:
                return True
    return False


if __name__ == "__main__":
    try:
        approved = protected(json.load(sys.stdin))
    except Exception:
        approved = False
    if not approved:
        print("Production requires configured reviewers and blocked self-review.", file=sys.stderr)
        sys.exit(1)
    print("Production reviewer protection verified.")
