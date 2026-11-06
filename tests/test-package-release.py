"""Check that release source archives contain project files and no private state."""
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile


class ReleasePackageTest(unittest.TestCase):
    def test_source_archive_excludes_private_and_unrelated_files(self):
        repo = pathlib.Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory(prefix='whenthen-package-test-') as folder:
            root = pathlib.Path(folder)
            fixtures = {
                'app/build.gradle': "android { defaultConfig { versionName '1.0.0' } }",
                'app/src/main/java/Example.java': 'class Example {}',
                'scripts/example.py': 'print("example")',
                'tests/example.py': '# example test',
                'docs/images/icon.png': 'example-image',
                'app/build/outputs/apk/release/app-release.apk': 'fixture-apk',
                'app/build/intermediates/private.txt': 'DO_NOT_EXPORT',
                '.local/tools/signing.properties': 'DO_NOT_EXPORT',
                'app/private.p12': 'DO_NOT_EXPORT',
                'app/signing.properties': 'DO_NOT_EXPORT',
                'app/AGENTS.md': 'DO_NOT_EXPORT',
                'app/.agents/instructions.txt': 'DO_NOT_EXPORT',
                'AGENTS.md': 'DO_NOT_EXPORT',
                'personal-notes.txt': 'DO_NOT_EXPORT',
                'test-results/device.json': 'DO_NOT_EXPORT',
                'README.md': '# WhenThen',
                'LICENSE': 'Example license',
            }
            for name, content in fixtures.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding='utf-8')
            shutil.copy2(repo / 'scripts/package-release.py', root / 'scripts/package-release.py')
            subprocess.run([sys.executable, str(root / 'scripts/package-release.py')], check=True, capture_output=True)
            with zipfile.ZipFile(root / 'dist/WhenThen-1.0.0-source.zip') as archive:
                names = set(archive.namelist())
                for name in ['README.md', 'app/build.gradle', 'app/src/main/java/Example.java', 'scripts/example.py', 'tests/example.py', 'docs/images/icon.png']:
                    self.assertIn('WhenThen/' + name, names)
                for name in names:
                    self.assertNotIn(b'DO_NOT_EXPORT', archive.read(name), name)
                self.assertFalse(any('/build/' in name or name.lower().endswith('/agents.md') for name in names))
            checksums = json.loads((root / 'dist/checksums.json').read_text(encoding='utf-8'))
            self.assertEqual(len(checksums), 2)
            self.assertEqual((root / 'dist/WhenThen-1.0.0.apk').read_text(), 'fixture-apk')


if __name__ == '__main__':
    unittest.main()
