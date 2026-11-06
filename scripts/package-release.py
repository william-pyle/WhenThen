"""Package the signed APK and editable source without private or generated files."""
import hashlib
import json
import pathlib
import shutil
import zipfile
from release_support import version as read_version

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / 'dist'
version, version_code = read_version(ROOT)
apk = ROOT / 'app/build/outputs/apk/release/app-release.apk'
if not apk.is_file():
    raise SystemExit('Build a signed release APK before packaging: scripts/build-android.ps1 -Tasks assembleRelease')

OUT.mkdir(parents=True, exist_ok=True)
apk_out = OUT / 'WhenThen.apk'
source_out = OUT / f'WhenThen-{version}-source.zip'
shutil.copy2(apk, apk_out)

# An allowlist keeps local instructions, credentials and unrelated files out.
source_paths = [ROOT / name for name in [
    'README.md', 'LICENSE', '.gitignore', '.gitattributes',
    'settings.gradle', 'build.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat', 'version.properties',
]]
source_paths += [p for folder in ['app', 'gradle', 'scripts', 'tests', 'docs', '.github/workflows'] for p in (ROOT / folder).rglob('*') if p.is_file()]
excluded_parts = {'build', '.gradle', '.idea', '__pycache__', '.local', '.agents', '.claude', '.codex'}
excluded_names = {'local.properties', 'signing.properties', 'agents.md', 'claude.md'}
excluded_extensions = {'.p12', '.pfx', '.jks', '.keystore', '.pem', '.pyc', '.log'}
with zipfile.ZipFile(source_out, 'w', zipfile.ZIP_DEFLATED) as archive:
    for source in sorted(source_paths):
        relative = source.relative_to(ROOT)
        if any(part in excluded_parts for part in relative.parts):
            continue
        if source.name.lower() in excluded_names or source.suffix.lower() in excluded_extensions:
            continue
        if source.is_file():
            archive.write(source, pathlib.PurePosixPath('WhenThen') / relative)

record = [{'file': p.name, 'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in [apk_out, source_out]]
(OUT / 'checksums.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
manifest = {'schema': 1, 'applicationId': 'com.jazcosolutions.whenthen', 'versionName': version,
            'versionCode': version_code, 'size': record[0]['bytes'], 'sha256': record[0]['sha256'],
            'apkUrl': f'https://github.com/william-pyle/WhenThen/releases/download/v{version}/WhenThen.apk',
            'notes': 'A new WhenThen release is ready. Your rules stay on this phone.'}
notes = OUT / 'release-notes.txt'
if notes.exists(): manifest['notes'] = notes.read_text(encoding='utf-8')[:1800]
(OUT / 'update.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
print(json.dumps(record, indent=2))
