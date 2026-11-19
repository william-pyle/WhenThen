"""Produce or promote a release candidate from successful, exact-commit owner CI."""
import hashlib
import io
import json
import os
import subprocess
import sys
import zipfile
from release_support import ROOT, version

REPOSITORY = 'william-pyle/WhenThen'
WORKFLOW = '.github/workflows/build.yml'
APK = 'app-release-unsigned.apk'
TARGET = {'runner': 'ubuntu-24.04', 'java': 17, 'compileSdk': 36,
          'buildTools': '35.0.0', 'variant': 'release', 'features': [],
          'gradleArguments': ['-PciUnsignedRelease=true', '--build-cache']}
INPUTS = ['build.gradle', 'app/build.gradle', 'settings.gradle', 'gradle.properties',
          'gradle/wrapper/gradle-wrapper.properties', 'gradle/wrapper/gradle-wrapper.jar',
          'version.properties', '.github/workflows/build.yml']


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def input_hash(root=ROOT):
    return sha256(b''.join(name.encode() + b'\0' + (root / name).read_bytes() + b'\0' for name in INPUTS))


def git_sha():
    return subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()


def api(path, raw=False):
    data = subprocess.check_output(['gh', 'api', f'repos/{REPOSITORY}{path}'])
    return data if raw else json.loads(data)


def validate_run(run, commit):
    require(run['repository']['full_name'] == REPOSITORY, 'Wrong producer repository')
    require(run['head_repository']['full_name'] == REPOSITORY, 'Fork producer is forbidden')
    require(run['path'] == WORKFLOW, 'Wrong producer workflow')
    require(run['head_sha'] == commit and run['head_branch'] == 'main', 'Wrong producer commit/branch')
    require(run['event'] in ('push', 'workflow_dispatch'), 'Untrusted producer event')
    require(run['status'] == 'completed' and run['conclusion'] == 'success', 'CI gate is not green')


def artifact_name(commit, attempt):
    return f'release-candidate-{commit}-{attempt}'


def validate_archive(artifact, run, data, commit, root=ROOT):
    validate_run(run, commit)
    require(not artifact['expired'], 'Candidate expired; explicitly rerun CI for this exact commit')
    require(artifact['name'] == artifact_name(commit, run['run_attempt']), 'Wrong artifact name/attempt')
    producer = artifact['workflow_run']
    require(producer['id'] == run['id'] and producer['head_sha'] == commit
            and producer['head_repository_id'] == run['repository']['id'], 'Wrong artifact producer')
    require(artifact['digest'] == 'sha256:' + sha256(data), 'Artifact archive digest mismatch')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        require(sorted(archive.namelist()) == sorted([APK, 'provenance.json']), 'Unexpected candidate files')
        require(sum(f.file_size for f in archive.infolist()) < 100_000_000, 'Oversized candidate')
        apk = archive.read(APK)
        manifest = json.loads(archive.read('provenance.json'))
    name, code = version(root)
    expected = {'schema': 1, 'repository': REPOSITORY, 'workflow': WORKFLOW,
                'commit': commit, 'runId': run['id'], 'runAttempt': run['run_attempt'],
                'versionName': name, 'versionCode': code, 'target': TARGET,
                'inputsSha256': input_hash(root), 'apkSha256': sha256(apk), 'apkBytes': len(apk)}
    require(manifest == expected, 'Candidate provenance, version, target, inputs or APK hash mismatch')
    return apk, manifest


def create():
    commit = git_sha()
    require(os.environ['GITHUB_REPOSITORY'] == REPOSITORY, 'Wrong repository')
    require(os.environ['GITHUB_SHA'] == commit and os.environ['GITHUB_REF'] == 'refs/heads/main', 'Wrong source')
    require(os.environ['GITHUB_EVENT_NAME'] in ('push', 'workflow_dispatch'), 'Untrusted event')
    name, code = version()
    apk = (ROOT / 'app/build/outputs/apk/release' / APK).read_bytes()
    manifest = {'schema': 1, 'repository': REPOSITORY, 'workflow': WORKFLOW, 'commit': commit,
                'runId': int(os.environ['GITHUB_RUN_ID']), 'runAttempt': int(os.environ['GITHUB_RUN_ATTEMPT']),
                'versionName': name, 'versionCode': code, 'target': TARGET,
                'inputsSha256': input_hash(), 'apkSha256': sha256(apk), 'apkBytes': len(apk)}
    output = ROOT / 'dist/candidate'
    output.mkdir(parents=True, exist_ok=False)
    (output / APK).write_bytes(apk)
    (output / 'provenance.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')


def fetch():
    commit = git_sha()
    # Query only the named workflow and exact SHA. Never fall back to latest/main artifacts.
    runs = api(f'/actions/workflows/build.yml/runs?head_sha={commit}&branch=main&per_page=100')['workflow_runs']
    eligible = [r for r in runs if r['event'] in ('push', 'workflow_dispatch')]
    require(bool(eligible), 'No exact-commit producer. Run Android build for this commit before releasing.')
    run = max(eligible, key=lambda r: r['id'])
    validate_run(run, commit)
    artifacts = api(f'/actions/runs/{run["id"]}/artifacts?per_page=100')['artifacts']
    candidates = [a for a in artifacts if a['name'] == artifact_name(commit, run['run_attempt'])]
    require(len(candidates) == 1, 'Missing/ambiguous candidate; rerun exact-commit CI, not release builds')
    artifact = candidates[0]
    require(not artifact['expired'], 'Candidate expired; explicitly rerun exact-commit CI')
    data = api(f'/actions/artifacts/{artifact["id"]}/zip', raw=True)
    apk, manifest = validate_archive(artifact, run, data, commit)
    output = ROOT / 'dist/candidate'
    output.mkdir(parents=True, exist_ok=False)
    (output / APK).write_bytes(apk)
    (output / 'provenance.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    evidence = {'producer': manifest, 'artifactId': artifact['id'], 'archiveDigest': artifact['digest'],
                'producerUrl': run['html_url']}
    (ROOT / 'dist/provenance.json').write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')
    print('Verified candidate from', run['html_url'], 'artifact', artifact['id'])


if __name__ == '__main__':
    {'create': create, 'fetch': fetch}[sys.argv[1]]()
