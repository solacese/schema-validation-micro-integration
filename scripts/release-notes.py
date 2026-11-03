#!/usr/bin/env python3
"""Print the changelog entry for the requested numeric release version."""
from pathlib import Path
import re
import sys

version = sys.argv[1]
if not re.fullmatch(r'\d+\.\d+\.\d+', version):
    sys.exit('Expected a numeric release version')
text = Path('CHANGELOG.md').read_text()
match = re.search(r'^## \[' + re.escape(version) + r'\].*?\n(.*?)(?=^## |\Z)', text, re.M | re.S)
if not match:
    sys.exit(f'Missing CHANGELOG entry for {version}')
print(match.group(1).strip())
print('\nInstallation instructions: [README](https://github.com/solacese/schema-validation-micro-integration/blob/v' + version + '/README.md).')
print('\nContainer image: `ghcr.io/solacese/schema-validation-micro-integration:' + version + '`.')
