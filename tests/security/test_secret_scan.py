import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from scripts.security.scan_secrets import iter_candidate_files, scan_text


SCANNER = Path(__file__).resolve().parents[2] / 'scripts/security/scan_secrets.py'


class SecretScanTests(unittest.TestCase):
    def test_patterns(self):
        examples = {'PRIVATE_KEY': '-----BEGIN ' + 'RSA PRIVATE KEY-----',
                    'GITHUB_TOKEN': 'ghp_' + 'A' * 36,
                    'AWS_ACCESS_KEY': 'AKIA' + 'A' * 16,
                    'OPENAI_STYLE_SECRET': 'sk-' + 'A' * 32}
        for rule, value in examples.items():
            with self.subTest(rule=rule):
                findings = scan_text('synthetic.txt', value)
                self.assertIn('SECRET_SCAN_FINDING:synthetic.txt:' + rule, findings)
                self.assertNotIn(value, str(findings))

    def test_harmless(self):
        self.assertEqual(scan_text('README.md', 'Synthetic documentation only.'), [])

    def test_ignored_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for path in ['.git/token', '.gradle/token', '.idea/token', 'app/build/token',
                         'binary.jar', 'large.txt', 'keep.txt']:
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text('x' * (2 * 1024 * 1024 + 1) if path == 'large.txt' else 'synthetic')
            self.assertEqual(iter_candidate_files([tmp]), [str(root / 'keep.txt')])

    def test_symlink_never_followed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            os.symlink('/nonexistent/synthetic', root / 'link')
            result = subprocess.run([sys.executable, '-I', str(SCANNER), str(root)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 1)
            self.assertIn('UNSAFE_ENTRY', result.stdout)

    def test_git_blobs_use_trusted_scanner(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            def git(*args):
                return subprocess.check_output(['git', '-C', tmp, *args], stderr=subprocess.PIPE).decode().strip()
            git('init', '-b', 'main')
            git('config', 'user.name', 'Synthetic Test')
            git('config', 'user.email', 'synthetic@example.invalid')
            (root / 'README.md').write_text('synthetic')
            git('add', '.')
            git('commit', '-m', 'base')
            base = git('rev-parse', 'HEAD')
            marker = root / 'marker'
            target = root / 'scripts/security/scan_secrets.py'
            target.parent.mkdir(parents=True)
            target.write_text('from pathlib import Path\nPath(' + repr(str(marker)) + ').touch()\n')
            (root / 'synthetic.txt').write_text('ghp_' + 'A' * 36)
            (root / 'binary.jar').write_bytes(b'\x00\xffsynthetic')
            git('add', '.')
            git('commit', '-m', 'candidate')
            head = git('rev-parse', 'HEAD')
            git('switch', '--detach', base)
            result = subprocess.run([sys.executable, '-I', str(SCANNER), '--git-ref', head],
                                    cwd=root, capture_output=True, text=True)
            self.assertEqual(result.returncode, 1)
            self.assertIn('GITHUB_TOKEN', result.stdout)
            self.assertFalse(marker.exists())
            self.assertNotIn('ghp_' + 'A' * 36, result.stdout)

    def test_git_rejects_symlinks_and_path_indirection(self):
        for name, link in [('link', True), ('..\\outside', False)]:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                def git(*args):
                    return subprocess.check_output(['git', '-C', tmp, *args], stderr=subprocess.PIPE).decode().strip()
                git('init', '-b', 'main')
                git('config', 'user.name', 'Synthetic Test')
                git('config', 'user.email', 'synthetic@example.invalid')
                if link:
                    os.symlink('../outside', root / name)
                else:
                    (root / name).write_text('synthetic')
                git('add', '.')
                git('commit', '-m', 'synthetic unsafe entry')
                result = subprocess.run([sys.executable, '-I', str(SCANNER),
                                         '--git-ref', git('rev-parse', 'HEAD')],
                                        cwd=root, capture_output=True, text=True)
                self.assertEqual(result.returncode, 1)
                self.assertIn('UNSAFE_ENTRY', result.stdout)


if __name__ == '__main__':
    unittest.main()
