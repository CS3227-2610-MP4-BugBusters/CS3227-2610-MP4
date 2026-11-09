"""Exercise failure controls without downloading or executing untrusted binaries."""
import importlib.util
import io
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("mp4_scanner_tools", Path(__file__).resolve().parents[1] / "security/tools.py")
tools = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tools)


class SecurityToolTests(unittest.TestCase):
    def test_corrupt_download_and_network_failure_never_produce_executable(self):
        for failure in (None, OSError("synthetic offline fixture")):
            with self.subTest(network_failure=failure is not None), tempfile.TemporaryDirectory() as scratch:
                with patch.dict(tools.os.environ, {"LOCALAPPDATA": scratch}), \
                     patch.object(tools.platform, "system", return_value="Windows"), \
                     patch.object(tools.platform, "machine", return_value="AMD64"), \
                     patch.object(tools.urllib.request, "urlopen", side_effect=failure, return_value=io.BytesIO(b"corrupt fixture")):
                    with self.assertRaises((OSError, RuntimeError)):
                        tools.binary("gitleaks")
                self.assertFalse(list(Path(scratch).rglob("bin-*.exe")))


if __name__ == "__main__":
    unittest.main()
