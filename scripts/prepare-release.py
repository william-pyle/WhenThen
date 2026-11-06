"""Validate a published release before building and signing its APK."""
import argparse
import json
import os
import urllib.request
from release_support import ROOT, version

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('tag')
args = parser.parse_args()
name, code = version()
if args.tag != 'v' + name:
    raise SystemExit('The release tag must match version.properties: v' + name)

def get(path):
    request = urllib.request.Request('https://api.github.com/repos/william-pyle/WhenThen' + path,
        headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Accept': 'application/vnd.github+json', 'User-Agent': 'WhenThen-release'})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)

release = get('/releases/tags/' + args.tag)
if release['draft'] or release['prerelease']:
    raise SystemExit('Publish a stable release before running this workflow.')
if any(asset['name'] == 'update.json' for asset in release['assets']):
    raise SystemExit('This release is already complete. Publish a new version instead of replacing its APK.')
for previous in get('/releases?per_page=100'):
    if previous['id'] == release['id'] or previous['draft'] or previous['prerelease']:
        continue
    for asset in previous['assets']:
        if asset['name'] != 'update.json': continue
        url = asset['browser_download_url']
        if not url.startswith('https://github.com/william-pyle/WhenThen/releases/download/'):
            raise SystemExit('Unexpected previous manifest location.')
        with urllib.request.urlopen(url, timeout=30) as response:
            previous_code = int(json.load(response)['versionCode'])
        if code <= previous_code:
            raise SystemExit('Android VERSION_CODE must be higher than all existing releases. Use scripts/set-version.py.')
(ROOT / 'dist').mkdir(exist_ok=True)
(ROOT / 'dist/release-notes.txt').write_text(release.get('body') or 'Improvements and fixes for WhenThen.', encoding='utf-8')
print('Validated', args.tag, 'with Android version code', code)
