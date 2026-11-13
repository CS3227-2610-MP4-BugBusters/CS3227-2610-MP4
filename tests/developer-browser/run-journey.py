#!/usr/bin/env python3
"""Run Playwright using the existing disposable packaged-app journey fixture."""

import importlib.util
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "src/backend/platform/deployment/test/run-packaged-developer-journey.py"
spec = importlib.util.spec_from_file_location("packaged_journey", RUNNER)
journey = importlib.util.module_from_spec(spec)
spec.loader.exec_module(journey)


def browser_journey(origin, developer, other_developer, reporter, report_id, psql_bin, db_env):
    env = os.environ.copy()
    env.update({"MP4_BROWSER_ORIGIN": origin, "MP4_BROWSER_USERNAME": developer["username"],
                "MP4_BROWSER_PASSWORD": developer["password"], "MP4_BROWSER_REPORT_ID": report_id,
                "MP4_BROWSER_OTHER_USERNAME": other_developer["username"],
                "MP4_BROWSER_OTHER_PASSWORD": other_developer["password"]})
    subprocess.run(["npm", "test"], cwd=Path(__file__).parent, env=env, check=True)
    reporter_session = journey.HttpBrowser(origin)
    reporter_session.login(reporter)
    status, published = reporter_session.json_request("GET", f"/api/reports/{report_id}/checklist/shared")
    assert status == 200 and published == {"steps": ["Reproduce the export failure"]}
    status = journey.psql(psql_bin, db_env, "mp4_developer_journey",
                          f"SELECT status FROM reports WHERE id='{report_id}'").strip()
    assert status == "RESOLUTION_PROPOSED", f"Unexpected stored report status: {status}"


try:
    journey.main(exercise=browser_journey, success_message="Packaged Developer browser journey passed.")
except Exception as error:
    print(f"Developer browser journey failed: {type(error).__name__}: {error}", file=sys.stderr)
    sys.exit(1)
