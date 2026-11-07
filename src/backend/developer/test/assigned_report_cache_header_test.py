"""Check private assigned-report responses cannot be stored by shared caches."""

import os
import urllib.error
import urllib.request


def request(path, token=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(os.environ["MP4_TEST_ORIGIN"].rstrip("/") + path, headers=headers)
    try:
        response = urllib.request.urlopen(req, timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.headers.get("Cache-Control"), response.read()


def main():
    status, cache_control, body = request("/api/developer/reports", "d1-token")
    assert status == 200, (status, body)
    assert cache_control == "no-store", cache_control

    status, cache_control, body = request("/api/developer/reports")
    assert status == 401, (status, body)
    assert cache_control == "no-store", cache_control
    print("Assigned-report success and error responses set Cache-Control: no-store.")


if __name__ == "__main__":
    main()
