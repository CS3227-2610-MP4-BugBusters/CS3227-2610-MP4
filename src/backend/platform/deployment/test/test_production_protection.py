import importlib.util
from pathlib import Path
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "production_protection.py"
SPEC = importlib.util.spec_from_file_location("production_protection", SOURCE)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def configured():
    return {"name": "production", "protection_rules": [{
        "type": "required_reviewers", "prevent_self_review": True,
        "reviewers": [{"type": "User", "reviewer": {"id": 123}}]}]}


class ProductionProtectionTest(unittest.TestCase):
    def test_actual_configured_reviewers_allow_release(self):
        self.assertTrue(MODULE.protected(configured()))

    def test_environment_name_alone_is_insufficient(self):
        self.assertFalse(MODULE.protected({"name": "production"}))

    def test_self_review_must_be_blocked(self):
        value = configured()
        value["protection_rules"][0]["prevent_self_review"] = False
        self.assertFalse(MODULE.protected(value))

    def test_an_actual_reviewer_is_required(self):
        for reviewers in ([], [None], [{"type": "User", "reviewer": {}}]):
            value = configured()
            value["protection_rules"][0]["reviewers"] = reviewers
            self.assertFalse(MODULE.protected(value))

    def test_other_environment_and_malformed_data_fail_closed(self):
        value = configured()
        value["name"] = "development"
        self.assertFalse(MODULE.protected(value))
        for invalid in (None, [], "production", {"name": "production", "protection_rules": "bad"}):
            self.assertFalse(MODULE.protected(invalid))


if __name__ == "__main__":
    unittest.main()
