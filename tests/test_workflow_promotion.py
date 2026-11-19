"""Offline trust-boundary regressions; no signing key, publication or device access."""
import copy
import io
import json
import pathlib
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'scripts'))
import ci_artifact as ci
import publish_release as publish


class PromotionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        for name in ci.INPUTS:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture', encoding='utf-8')
        (self.root / 'version.properties').write_text('VERSION_NAME=1.0.7\nVERSION_CODE=8\n')
        self.commit = 'a' * 40
        self.run = {'id': 123, 'run_attempt': 1, 'repository': {'full_name': ci.REPOSITORY, 'id': 456},
                    'head_repository': {'full_name': ci.REPOSITORY}, 'path': ci.WORKFLOW,
                    'head_sha': self.commit, 'head_branch': 'main', 'event': 'push',
                    'status': 'completed', 'conclusion': 'success', 'html_url': 'https://github.com/run/123'}
        self.apk = b'unsigned fixture'
        self.manifest = {'schema': 1, 'repository': ci.REPOSITORY, 'workflow': ci.WORKFLOW,
                         'commit': self.commit, 'runId': 123, 'runAttempt': 1, 'versionName': '1.0.7',
                         'versionCode': 8, 'target': ci.TARGET, 'inputsSha256': ci.input_hash(self.root),
                         'apkSha256': ci.sha256(self.apk), 'apkBytes': len(self.apk)}
        self.artifact = {'id': 777, 'expired': False, 'name': ci.artifact_name(self.commit, 1),
                         'workflow_run': {'id': 123, 'head_sha': self.commit, 'head_repository_id': 456}}

    def archive(self, manifest=None, extra=None):
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w') as z:
            z.writestr(ci.APK, self.apk)
            z.writestr('provenance.json', json.dumps(manifest or self.manifest))
            if extra:
                z.writestr(extra, b'unexpected')
        data = output.getvalue()
        self.artifact['digest'] = 'sha256:' + ci.sha256(data)
        return data

    def verify(self, data):
        return ci.validate_archive(self.artifact, self.run, data, self.commit, self.root)

    def test_valid_exact_commit_candidate(self):
        self.assertEqual(self.verify(self.archive())[0], self.apk)

    def test_rejects_wrong_producer_and_failed_or_pending_gate(self):
        data = self.archive()
        for key, value in [('repository', {'full_name': 'other/repo'}),
                           ('head_repository', {'full_name': 'fork/repo'}), ('path', 'other.yml'),
                           ('head_sha', 'b' * 40), ('head_branch', 'feature'), ('event', 'pull_request'),
                           ('status', 'in_progress'), ('conclusion', 'failure'), ('conclusion', 'cancelled')]:
            with self.subTest(key=key, value=value), patch.dict(self.run, {key: value}):
                with self.assertRaises(ValueError): self.verify(data)

    def test_rejects_artifact_identity_expiry_and_digest(self):
        data = self.archive()
        for key, value in [('expired', True), ('name', 'latest'), ('digest', ''),
                           ('workflow_run', {'id': 999, 'head_sha': self.commit, 'head_repository_id': 456}),
                           ('workflow_run', {'id': 123, 'head_sha': 'b' * 40, 'head_repository_id': 456}),
                           ('workflow_run', {'id': 123, 'head_sha': self.commit, 'head_repository_id': 999})]:
            with self.subTest(key=key), patch.dict(self.artifact, {key: value}):
                with self.assertRaises(ValueError): self.verify(data)
        with self.assertRaises(ValueError): self.verify(data + b'tampered')

    def test_rejects_provenance_mismatch(self):
        for key, value in [('schema', 2), ('repository', 'other/repo'), ('workflow', 'other.yml'),
                           ('commit', 'b' * 40), ('runId', 999), ('runAttempt', 2),
                           ('versionName', '1.0.6'), ('versionCode', 7), ('target', {}),
                           ('inputsSha256', 'bad'), ('apkSha256', 'bad'), ('apkBytes', 0)]:
            manifest = copy.deepcopy(self.manifest)
            manifest[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.verify(self.archive(manifest))

    def test_rejects_changed_build_inputs_and_extra_paths(self):
        for extra in ['../escape', 'extra.txt']:
            with self.assertRaises(ValueError): self.verify(self.archive(extra=extra))
        data = self.archive()
        (self.root / 'build.gradle').write_text('changed')
        with self.assertRaises(ValueError): self.verify(data)

    def test_missing_candidate_does_not_fall_back_to_build(self):
        for runs, artifacts in [([], []), ([self.run], []), ([self.run], [self.artifact, self.artifact])]:
            with patch.object(ci, 'git_sha', return_value=self.commit), patch.object(ci, 'api', side_effect=[{'workflow_runs': runs}, {'artifacts': artifacts}]):
                with self.assertRaises(ValueError): ci.fetch()

    def test_checks_apk_identity_version_certificate(self):
        badging = "package: name='com.jazcosolutions.whenthen' versionCode='8' versionName='1.0.7'"
        cert = 'Signer #1 certificate SHA-256 digest: ' + publish.CERTIFICATE
        publish.validate_apk_metadata(badging, cert, '1.0.7', 8)
        for bad, signed in [(badging.replace('whenthen', 'other'), cert),
                            (badging.replace("'8'", "'7'"), cert),
                            (badging.replace('1.0.7', '1.0.6'), cert), (badging, cert.replace(publish.CERTIFICATE, '0' * 64)),
                            (badging, cert + '\n' + cert)]:
            with self.assertRaises(ValueError): publish.validate_apk_metadata(bad, signed, '1.0.7', 8)

    def test_publication_is_manifest_last_and_never_clobbers(self):
        output = self.root / 'dist'
        output.mkdir()
        files = ['WhenThen.apk', 'WhenThen-1.0.7-source.zip', 'checksums.json', 'provenance.json', 'update.json']
        for file in files: (output / file).write_text('{}')
        with patch.object(publish, 'ROOT', self.root), patch.object(publish, 'version', return_value=('1.0.7', 8)), patch.dict(publish.os.environ, {'RELEASE_TAG': 'v1.0.7'}), patch.object(publish, 'api', return_value={'draft': False, 'prerelease': False, 'assets': []}), patch.object(publish.subprocess, 'run') as upload, patch.object(publish, 'public_bytes', side_effect=lambda tag, file: (output / file).read_bytes()):
            publish.publish()
            self.assertEqual([pathlib.Path(call.args[0][4]).name for call in upload.call_args_list], files)
            self.assertTrue(all('--clobber' not in call.args[0] for call in upload.call_args_list))

    def test_corrupt_existing_asset_stops_before_manifest_upload(self):
        output = self.root / 'dist'
        output.mkdir()
        (output / 'WhenThen.apk').write_bytes(self.apk)
        (output / 'provenance.json').write_text('{}')
        with patch.object(publish, 'ROOT', self.root), patch.object(publish, 'version', return_value=('1.0.7', 8)), patch.dict(publish.os.environ, {'RELEASE_TAG': 'v1.0.7'}), patch.object(publish, 'api', return_value={'draft': False, 'prerelease': False, 'assets': [{'name': 'WhenThen.apk'}]}), patch.object(publish.subprocess, 'run') as upload, patch.object(publish, 'public_bytes', return_value=b'wrong'):
            with self.assertRaises(ValueError): publish.publish()
            upload.assert_not_called()

    def test_completed_release_cannot_be_changed(self):
        with patch.object(publish, 'version', return_value=('1.0.7', 8)), patch.dict(publish.os.environ, {'RELEASE_TAG': 'v1.0.7'}), patch.object(publish, 'api', return_value={'draft': False, 'prerelease': False, 'assets': [{'name': 'update.json'}]}), patch.object(publish.subprocess, 'run') as upload:
            with self.assertRaises(ValueError): publish.publish()
            upload.assert_not_called()


if __name__ == '__main__':
    unittest.main()
