#!/usr/bin/env python3
"""Extract registry application files for an ARM or x86 JVM/Node runtime."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sys
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parent.parent
VERSION = os.environ.get("SCHEMA_REGISTRY_VERSION", "1.1.2")


def unpack(archive, target, prefix=None):
    """Extract regular files/directories only, rejecting traversal and links."""
    # Docker whiteouts remove files from earlier layers. Apply them before
    # extracting this layer so a replaced directory can contain new files.
    if prefix:
        for member in archive.getmembers():
            parts = PurePosixPath(member.name).parts
            if not parts or parts[0] != prefix or ".." in parts:
                continue
            if parts[-1].startswith(".wh."):
                directory = target.joinpath(*parts[:-1])
                if parts[-1] == ".wh..wh..opq":
                    if directory.exists():
                        shutil.rmtree(directory)
                else:
                    removed = directory / parts[-1][4:]
                    if removed.is_dir():
                        shutil.rmtree(removed)
                    elif removed.exists():
                        removed.unlink()
    for member in archive.getmembers():
        parts = PurePosixPath(member.name).parts
        if ".." in parts or member.name.startswith("/"):
            raise ValueError(f"Unsafe archive member: {member.name}")
        if prefix and (not parts or parts[0] != prefix):
            continue
        if prefix and parts[-1].startswith(".wh."):
            continue
        if not (member.isfile() or member.isdir()):
            raise ValueError(f"Unsupported archive link or special file: {member.name}")
        output = target.joinpath(*parts)
        if member.isdir():
            output.mkdir(parents=True, exist_ok=True)
        else:
            output.parent.mkdir(parents=True, exist_ok=True)
            with archive.extractfile(member) as source, output.open("wb") as dest:
                shutil.copyfileobj(source, dest)
            output.chmod(member.mode & 0o777)


def checksum(path):
    sidecar = Path(str(path) + ".md5")
    if not sidecar.is_file():
        return
    expected = sidecar.read_text().split()[0].lower()
    if not re.fullmatch(r"[0-9a-f]{32}", expected):
        raise ValueError(f"Invalid MD5 checksum file: {sidecar}")
    digest = hashlib.md5()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != expected:
        raise ValueError(f"Checksum mismatch: {path}")
    print(f"Verified distribution checksum: {path.name}")


def prepare():
    if not re.fullmatch(r"\d+\.\d+\.\d+", VERSION):
        raise ValueError("SCHEMA_REGISTRY_VERSION must be a release such as 1.1.2")
    name = f"schema-registry-v{VERSION}"
    configured = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("SCHEMA_REGISTRY_DIST", "")
    candidates = [Path(configured).expanduser()] if configured else [
        ROOT / name, ROOT / f"{name}.tar.gz", Path.home() / "Downloads" / f"{name}.tar.gz",
        Path.home() / "Downloads" / name,
    ]
    source = next((p.resolve() for p in candidates if p.exists()), None)
    runtime = Path(os.environ.get("SCHEMA_REGISTRY_RUNTIME_DIR", ROOT / ".runtime" / "schema-registry" / VERSION)).resolve()
    marker = runtime / "prepared.json"
    if source is None and not configured and marker.is_file() and (runtime / "backend/deployments/registry-runner.jar").is_file():
        if json.loads(marker.read_text()).get("version") == VERSION:
            print(f"Registry {VERSION} already prepared at {runtime}")
            return
    if source is None:
        raise ValueError(
            f"Missing {name}.tar.gz. Download the release from "
            f"https://products.solace.com/products/{VERSION}GA/Schema_Registry/solace-schema-registry/ "
            "and put it in Downloads, or set SCHEMA_REGISTRY_DIST to the archive/extracted directory.")
    signature = {"version": VERSION, "source": str(source), "size": source.stat().st_size,
                 "modified_ns": source.stat().st_mtime_ns, "format": 1}
    if marker.is_file() and json.loads(marker.read_text()) == signature and (runtime / "backend/deployments/registry-runner.jar").is_file():
        print(f"Registry {VERSION} already prepared at {runtime}")
        return
    runtime.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=f"registry-{VERSION}-", dir=runtime.parent) as temporary:
        stage = Path(temporary)
        if source.is_file():
            checksum(source)
            package = stage / "package"
            with tarfile.open(source, "r:*") as archive:
                unpack(archive, package)
        else:
            package = source
        compose = list(package.rglob("compose-files/compose.yaml"))
        if len(compose) != 1:
            raise ValueError("Expected one compose-files/compose.yaml in the distribution")
        package = compose[0].parent.parent
        prepared = stage / "prepared"
        shutil.copytree(compose[0].parent, prepared / "compose-files")
        images = [p for p in (package / "docker-images").glob("solace-registry-*.tar.gz") if "-ui-" not in p.name]
        if len(images) != 1:
            raise ValueError("Expected one solace-registry backend image archive")
        backend = prepared / "backend"
        backend.mkdir()
        with tarfile.open(images[0], "r:*") as image:
            manifests = json.load(image.extractfile("manifest.json"))
            if len(manifests) != 1:
                raise ValueError("Expected a single-platform Docker image manifest")
            manifest = manifests[0]
            for layer_name in manifest["Layers"]:
                with tarfile.open(fileobj=image.extractfile(layer_name), mode="r:*") as layer:
                    unpack(layer, backend, prefix="deployments")
            config = json.load(image.extractfile(manifest["Config"]))
            (prepared / "image-config.json").write_text(json.dumps(config, indent=2))
        runners = list(backend.glob("deployments/*-runner.jar"))
        if len(runners) != 1:
            raise ValueError("Expected one registry runner JAR in /deployments")
        runners[0].rename(backend / "deployments/registry-runner.jar")
        if not (prepared / "compose-files/oidc-provider/dist/bundle.js").is_file():
            raise ValueError("Distribution is missing the embedded identity provider")
        (prepared / "prepared.json").write_text(json.dumps(signature, indent=2) + "\n")
        if runtime.exists():
            raise ValueError(f"Runtime already exists with different inputs: {runtime}. Use a new SCHEMA_REGISTRY_RUNTIME_DIR.")
        prepared.rename(runtime)
    print(f"Registry {VERSION} prepared at {runtime}")


if __name__ == "__main__":
    try:
        prepare()
    except (ValueError, OSError, tarfile.TarError, KeyError) as error:
        sys.exit(f"Registry preparation failed: {error}")
