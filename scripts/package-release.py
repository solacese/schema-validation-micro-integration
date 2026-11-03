#!/usr/bin/env python3
"""Export committed source plus verified JARs as portable release bundles."""
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tarfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def main():
    os.chdir(ROOT)
    if subprocess.check_output(['git', 'status', '--porcelain']).strip():
        raise SystemExit('Release packaging requires a clean worktree.')
    version = ET.parse('pom.xml').getroot().find('{http://maven.apache.org/POM/4.0.0}version').text
    if not re.fullmatch(r'\d+\.\d+\.\d+', version or ''):
        raise SystemExit('Release version must be a numeric major.minor.patch.')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    epoch = int(subprocess.check_output(['git', 'show', '-s', '--format=%ct', 'HEAD'], text=True))
    package = f'schema-validation-micro-integration-{version}'
    stage = ROOT / 'target' / 'release' / package
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True)
    paths = subprocess.check_output(['git', 'ls-files', '-z']).decode().split('\0')
    for name in filter(None, paths):
        relative = Path(name)
        if (relative.parts[0] in {'.git', '.runtime', 'target', 'dist', 'lib'}
                or (relative.parts[0].startswith('.env') and name != '.env.example')
                or relative.parts[0].startswith('schema-registry-v')):
            raise SystemExit(f'Refusing to package a local-only file tracked by Git: {name}')
        source = ROOT / relative
        if source.is_symlink():
            raise SystemExit(f'Refusing to export a symbolic link: {name}')
        output = stage / relative
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, output)
    lib = stage / 'lib'
    lib.mkdir()
    shutil.copy2('target/schema-validation-micro-integration.jar', lib / 'schema-validation-micro-integration.jar')
    shutil.copy2('target/healthcheck.jar', lib / 'healthcheck.jar')
    for name in ['bom.json', 'bom.xml']:
        shutil.copy2(ROOT / 'target' / name, stage / name)
    metadata = {'version': version, 'commit': commit, 'schemaRegistryDistribution': '1.1.2',
                'javaRelease': 17, 'artifacts': {p.name: sha256(p) for p in sorted(lib.iterdir())}}
    (stage / 'RELEASE.json').write_text(json.dumps(metadata, indent=2) + '\n')
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    # Clear only this project's previous generated release assets.
    for old in dist.iterdir():
        if old.is_file() and (old.name.startswith('schema-validation-micro-integration-')
                or old.name in {'SHA256SUMS', 'bom.json', 'bom.xml', 'Schema-Validation-MI-Installation-Guide.docx'}):
            old.unlink()
    members = sorted(p for p in stage.rglob('*') if p.is_file())
    with (dist / f'{package}.tar.gz').open('wb') as raw:
        with gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=epoch) as compressed:
            with tarfile.open(fileobj=compressed, mode='w') as archive:
                for source in members:
                    info = archive.gettarinfo(str(source), arcname=str(Path(package) / source.relative_to(stage)))
                    info.uid = info.gid = 0
                    info.uname = info.gname = ''
                    info.mtime = epoch
                    info.mode = 0o755 if source.stat().st_mode & stat.S_IXUSR else 0o644
                    with source.open('rb') as content:
                        archive.addfile(info, content)
    import datetime
    stamp = datetime.datetime.fromtimestamp(epoch, datetime.timezone.utc).timetuple()[:6]
    with zipfile.ZipFile(dist / f'{package}.zip', 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for source in members:
            info = zipfile.ZipInfo(str(Path(package) / source.relative_to(stage)), stamp)
            info.create_system = 3
            mode = 0o755 if source.stat().st_mode & stat.S_IXUSR else 0o644
            info.external_attr = (stat.S_IFREG | mode) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, source.read_bytes())
    shutil.copy2(lib / 'schema-validation-micro-integration.jar', dist / f'{package}.jar')
    for name in ['bom.json', 'bom.xml']:
        shutil.copy2(stage / name, dist / name)
    shutil.copy2(stage / 'docs/Schema-Validation-MI-Installation-Guide.docx', dist)
    checksums = [f'{sha256(path)}  {path.name}' for path in sorted(dist.iterdir()) if path.is_file() and path.name != 'SHA256SUMS']
    (dist / 'SHA256SUMS').write_text('\n'.join(checksums) + '\n')
    print(f'Packaged {package} at commit {commit}\nAssets: {dist}\nDocker context: {stage}')
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write(f'version={version}\ncontext=target/release/{package}\n')


if __name__ == '__main__':
    main()
