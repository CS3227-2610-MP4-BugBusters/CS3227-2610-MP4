"""Download fixed scanner releases, verify upstream archive hashes, and cache outside the repo."""
import hashlib
import io
import os
from pathlib import Path
import platform
import tarfile
import urllib.request
import zipfile

# Official upstream release checksums, reviewed on 9 November 2026.
PINS = {
    "gitleaks": ("gitleaks/gitleaks", "8.30.1", {
        "Linux": ("gitleaks_8.30.1_linux_x64.tar.gz", "551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb"),
        "Windows": ("gitleaks_8.30.1_windows_x64.zip", "d29144deff3a68aa93ced33dddf84b7fdc26070add4aa0f4513094c8332afc4e")}),
    "osv-scanner": ("google/osv-scanner", "2.7.1", {
        "Linux": ("osv-scanner_linux_amd64", "fb7dab19a8bd9bbe5c3189f09d4b7ee1820c9553a23bac202648a56efd2f6ee8"),
        "Windows": ("osv-scanner_windows_amd64.exe", "7121dcf420aefbbf89a7f0050c4d3094e079ff93afd334dfc96f16b02ec85800")}),
    "actionlint": ("rhysd/actionlint", "1.7.12", {
        "Linux": ("actionlint_1.7.12_linux_amd64.tar.gz", "8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8"),
        "Windows": ("actionlint_1.7.12_windows_amd64.zip", "6e7241b51e6817ea6a047693d8e6fed13b31819c9a0dd6c5a726e1592d22f6e9")}),
}


def binary(name):
    system = platform.system()
    if system not in ("Linux", "Windows") or platform.machine().lower() not in ("amd64", "x86_64"):
        raise RuntimeError("Pinned verification tools currently support Linux/Windows x64")
    repository, version, assets = PINS[name]
    asset, expected = assets[system]
    cache = Path(os.environ.get("LOCALAPPDATA", Path.home() / ".cache")) / "mp4-verification" / name / version
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / asset
    if not archive.is_file() or hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
        url = f"https://github.com/{repository}/releases/download/v{version}/{asset}"
        with urllib.request.urlopen(url, timeout=60) as response:
            content = response.read(160 * 1024 * 1024 + 1)
        if hashlib.sha256(content).hexdigest() != expected:
            raise RuntimeError(f"Checksum verification failed for {name}")
        archive.write_bytes(content)
    content = archive.read_bytes()
    filename = name + (".exe" if system == "Windows" else "")
    if asset.endswith(".zip"):
        with zipfile.ZipFile(io.BytesIO(content)) as compressed:
            content = compressed.read(filename)
    elif asset.endswith(".tar.gz"):
        with tarfile.open(fileobj=io.BytesIO(content), mode="r:gz") as compressed:
            member = compressed.extractfile(filename)
            if member is None:
                raise RuntimeError(f"Missing executable in {name} archive")
            content = member.read()
    destination = cache / ("bin-" + filename)
    if not destination.is_file() or destination.read_bytes() != content:
        destination.write_bytes(content)
    destination.chmod(0o755)
    return str(destination)
