#!/usr/bin/env python3
"""Smoke-check both packaged frontends and unauthenticated API boundaries."""

import http.cookiejar
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser


class AssetPaths(HTMLParser):
    def __init__(self):
        super().__init__()
        self.paths = []

    def handle_starttag(self, tag, attrs):
        for name, value in attrs:
            if name in ("src", "href") and value:
                self.paths.append(value)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def get(opener, url):
    with opener.open(url, timeout=10) as response:
        return response.status, response.headers, response.read()


def request(opener, url, data=None, headers=None):
    req = urllib.request.Request(url, data=data, headers=headers or {})
    try:
        with opener.open(req, timeout=10) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as response:
        return response.code, response.read()


def json_body(raw):
    try:
        return json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise AssertionError("Expected a JSON API response") from exc


def check_frontend(opener, base, path, prefix):
    status, headers, body = get(opener, base + path)
    require(status == 200, f"{path} returned HTTP {status}")
    require("text/html" in headers.get("Content-Type", ""), f"{path} is not HTML")
    require(b'<div id="root">' in body, f"{path} does not contain the app root")

    parser = AssetPaths()
    parser.feed(body.decode("utf-8"))
    assets = [value for value in parser.paths if value.endswith(".js") or value.endswith(".css")]
    require(assets, f"{path} has no JavaScript or CSS asset references")
    for asset in assets:
        require(asset.startswith(prefix), f"{path} asset escaped {prefix}: {asset}")
        asset_status, _, _ = get(opener, urllib.parse.urljoin(base + path, asset))
        require(asset_status == 200, f"asset {asset} returned HTTP {asset_status}")


def main():
    origin = os.environ.get("MP4_PACKAGED_ORIGIN", "http://127.0.0.1:18080").rstrip("/")
    expected_revision = os.environ.get("MP4_EXPECTED_REVISION")
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    check_frontend(opener, origin, "/", "/assets/")
    check_frontend(opener, origin, "/developer/", "/developer/assets/")

    if expected_revision:
        status, _, body = get(opener, origin + "/assets/revision.txt")
        require(status == 200 and body.decode("utf-8").strip() == expected_revision,
                "Maintainer build revision does not match the expected revision")

    csrf_status, _, csrf_raw = get(opener, origin + "/api/auth/csrf")
    require(csrf_status == 200, f"unauthenticated CSRF request returned HTTP {csrf_status}")
    csrf = json_body(csrf_raw)
    require(isinstance(csrf, dict) and csrf.get("token") and
            csrf.get("headerName") == "X-CSRF-TOKEN", "CSRF endpoint returned an invalid token")

    login_status, login_raw = request(
        opener,
        origin + "/api/auth/login",
        data=b"username=packaging-smoke",
        headers={
            "Accept": "application/json",
            "Content-Type": "application/x-www-form-urlencoded",
            "Origin": origin,
            csrf["headerName"]: csrf["token"],
        },
    )
    require(login_status == 400, f"unauthenticated login probe returned HTTP {login_status}, expected 400")
    require(json_body(login_raw).get("code") == "VALIDATION_FAILED",
            "login probe did not reach login-form validation")

    for route in ("/api/developer/reports", "/api/maintainer/reports"):
        status, raw = request(opener, origin + route, headers={"Accept": "application/json"})
        require(status == 401, f"unauthenticated {route} returned HTTP {status}, expected 401")
        body = json_body(raw)
        require(body.get("code") == "UNAUTHENTICATED" and "items" not in body,
                f"unauthenticated {route} returned an unexpected response")

    print("Packaged root and /developer/ assets load; login is reachable; protected APIs deny anonymous access.")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, urllib.error.URLError) as error:
        print(f"Packaged Developer smoke check failed: {error}", file=sys.stderr)
        sys.exit(1)
