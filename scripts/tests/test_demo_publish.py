"""Exercise demo publishing with and without schema headers under the host Bash."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DemoPublishTest(unittest.TestCase):
    def publish(self, transport, scenario):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts/lib").mkdir(parents=True)
            shutil.copy(ROOT / "scripts/demo-publish.sh", root / "scripts/demo-publish.sh")
            shutil.copy(ROOT / "scripts/lib/env.sh", root / "scripts/lib/env.sh")
            (root / "bin").mkdir()
            capture = root / "arguments.json"
            executable = root / "bin" / ("curl" if transport == "rest" else "sdkperf")
            executable.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, sys\n"
                "from pathlib import Path\n"
                "Path(os.environ['DEMO_CAPTURE_ARGS']).write_text(json.dumps(sys.argv[1:]))\n"
                + ("print('200', end='')\n" if transport == "rest" else "print('Published')\n")
            )
            executable.chmod(0o755)
            env = os.environ.copy()
            env.update(PATH=str(root / "bin") + os.pathsep + env["PATH"],
                       SDKPERF_CMD=str(executable if transport == "sdkperf" else root / "absent-sdkperf"),
                       DEMO_CAPTURE_ARGS=str(capture), SOLACE_INPUT_BROKER="tcp://solace-broker:55555",
                       SOLACE_INPUT_USERNAME="default", SOLACE_INPUT_PASSWORD="default")
            result = subprocess.run(["/bin/bash", str(root / "scripts/demo-publish.sh"), scenario],
                                    env=env, text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            args = json.loads(capture.read_text())
            self.assertNotIn("", args)
            return args

    def test_headerless_publish_works_for_rest_and_sdkperf(self):
        for transport in ("rest", "sdkperf"):
            with self.subTest(transport=transport):
                args = self.publish(transport, "unmapped")
                self.assertFalse(any("schema_group" in arg for arg in args))
                self.assertIn("Solace-Delivery-Mode: persistent" if transport == "rest" else "-mt=persistent", args)

    def test_schema_headers_are_preserved_for_rest_and_sdkperf(self):
        for transport in ("rest", "sdkperf"):
            with self.subTest(transport=transport):
                args = self.publish(transport, "pub-files")
                if transport == "rest":
                    self.assertIn("Solace-User-Property-schema_group: manufacturing-iot", args)
                    self.assertIn("Solace-User-Property-schema_name: pub-files", args)
                    self.assertIn("Solace-User-Property-schema_version: 1.0.0", args)
                else:
                    self.assertIn("-cpl=String,schema_group,manufacturing-iot,String,schema_name,pub-files,String,schema_version,1.0.0", args)
