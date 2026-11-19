"""Verify signing identity and publish immutable assets, checking public final bytes."""
import json
import os
import pathlib
import re
import subprocess
import sys
import urllib.request
from ci_artifact import REPOSITORY, api, require, sha256
from release_support import ROOT, version

CERTIFICATE = 'ecb9369b20a4c0ff7c3ffedd51ffe036d05081d0250f3c13ec22b81f40ab59c1'
APPLICATION_ID = 'com.jazcosolutions.whenthen'


def validate_apk_metadata(badging, certificates, name, code):
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    require(package is not None and package.groups() == (APPLICATION_ID, str(code), name), 'Wrong APK identity/version')
    digests = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)', certificates)
    require(digests == [CERTIFICATE], 'Wrong APK signing certificate')


def verify_apk():
    tools = pathlib.Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    apk = ROOT / 'app/build/outputs/apk/release/app-release.apk'
    certificates = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
    validate_apk_metadata(badging, certificates, *version())
    print('Verified APK application ID, version and existing signing certificate')


def public_bytes(tag, filename):
    url = f'https://github.com/{REPOSITORY}/releases/download/{tag}/{filename}'
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read()


def verify_bytes(expected, actual, filename):
    require(sha256(expected) == sha256(actual), 'Published bytes mismatch: ' + filename)


def publish():
    name, _ = version()
    tag = os.environ['RELEASE_TAG']
    require(tag == 'v' + name, 'Wrong release tag')
    release = api('/releases/tags/' + tag)
    require(not release['draft'] and not release['prerelease'], 'Stable published release required')
    existing = {a['name'] for a in release['assets']}
    require('update.json' not in existing, 'Completed releases cannot be replaced')
    output = ROOT / 'dist'
    provenance = json.loads((output / 'provenance.json').read_text(encoding='utf-8'))
    provenance['signedApkSha256'] = sha256((output / 'WhenThen.apk').read_bytes())
    provenance['signingCertificateSha256'] = CERTIFICATE
    (output / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n', encoding='utf-8')
    files = ['WhenThen.apk', f'WhenThen-{name}-source.zip', 'checksums.json', 'provenance.json']
    # No clobber: an interrupted upload can only resume with identical existing bytes.
    for filename in files + ['update.json']:
        if filename not in existing:
            subprocess.run(['gh', 'release', 'upload', tag, str(output / filename), '--repo', REPOSITORY], check=True)
        verify_bytes((output / filename).read_bytes(), public_bytes(tag, filename), filename)
    print('All public assets verified; update.json uploaded last. No installation performed.')


if __name__ == '__main__':
    {'verify-apk': verify_apk, 'publish': publish}[sys.argv[1]]()
