"""Shared release version helpers; no credentials or machine-specific paths."""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]

def version(root=ROOT):
    props = dict(line.split('=', 1) for line in (root / 'version.properties').read_text(encoding='utf-8').splitlines() if '=' in line and not line.startswith('#'))
    name = props['VERSION_NAME'].strip()
    code = int(props['VERSION_CODE'])
    if not re.fullmatch(r'[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,4}', name) or not 1 <= code <= 2147483647:
        raise ValueError('version.properties must contain a numeric x.y.z version and positive Android version code.')
    return name, code
