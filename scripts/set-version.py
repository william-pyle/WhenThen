"""Set a new release version and automatically advance Android's version code."""
import argparse
import re
from release_support import ROOT, version

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('version', help='New version, for example 1.0.2')
args = parser.parse_args()
old, code = version()
if not re.fullmatch(r'[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,4}', args.version):
    parser.error('Use a version such as 1.0.2.')
if tuple(map(int, args.version.split('.'))) <= tuple(map(int, old.split('.'))):
    parser.error('The new version must be greater than ' + old)
(ROOT / 'version.properties').write_text(f'VERSION_NAME={args.version}\nVERSION_CODE={code + 1}\n', encoding='utf-8')
print(f'Prepared {args.version} (Android version code {code + 1}). Commit and push this change before publishing tag v{args.version}.')
