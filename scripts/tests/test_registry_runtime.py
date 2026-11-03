import hashlib
import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / "prepare-registry-runtime.py"
spec = importlib.util.spec_from_file_location("runtime", MODULE)
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimeExtractionTest(unittest.TestCase):
    def layer(self, files):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w") as archive:
            for name, content in files.items():
                member = tarfile.TarInfo(name)
                payload = content.encode()
                member.size = len(payload)
                archive.addfile(member, io.BytesIO(payload))
        buffer.seek(0)
        return tarfile.open(fileobj=buffer)

    def test_docker_whiteout_removes_old_runner_and_preserves_other_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.layer({"deployments/old-runner.jar": "old", "deployments/lib/library.jar": "lib"}) as layer:
                runtime.unpack(layer, root, prefix="deployments")
            with self.layer({"deployments/.wh.old-runner.jar": "", "deployments/new-runner.jar": "new"}) as layer:
                runtime.unpack(layer, root, prefix="deployments")
            self.assertFalse((root / "deployments/old-runner.jar").exists())
            self.assertEqual((root / "deployments/new-runner.jar").read_text(), "new")
            self.assertTrue((root / "deployments/lib/library.jar").is_file())
            self.assertFalse((root / "deployments/.wh.old-runner.jar").exists())

    def test_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as directory, self.layer({"../escaped": "bad"}) as layer:
            with self.assertRaisesRegex(ValueError, "Unsafe"):
                runtime.unpack(layer, Path(directory))

    def test_rejects_symlinks(self):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w") as archive:
            link = tarfile.TarInfo("escape")
            link.type = tarfile.SYMTYPE
            link.linkname = "/tmp/escape"
            archive.addfile(link)
        buffer.seek(0)
        with tempfile.TemporaryDirectory() as directory, tarfile.open(fileobj=buffer) as archive:
            with self.assertRaisesRegex(ValueError, "link"):
                runtime.unpack(archive, Path(directory))

    def test_verifies_vendor_checksum_and_rejects_corruption(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "release.tar.gz"
            path.write_bytes(b"distribution")
            Path(str(path) + ".md5").write_text(hashlib.md5(path.read_bytes()).hexdigest() + "  release.tar.gz")
            runtime.checksum(path)
            path.write_bytes(b"corrupt")
            with self.assertRaisesRegex(ValueError, "mismatch"):
                runtime.checksum(path)


if __name__ == "__main__":
    unittest.main()
