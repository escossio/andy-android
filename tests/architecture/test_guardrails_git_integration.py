import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
GUARD = ROOT / 'scripts/architecture/check_guardrails.py'


class GitGuardTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name)
        self.git('init', '-b', 'main')
        self.git('config', 'user.name', 'Synthetic Test')
        self.git('config', 'user.email', 'synthetic@example.invalid')
        self.manifest = '.github/architecture/frontiers/android-app-bootstrap-v1.json'
        self.write(self.manifest, (ROOT / self.manifest).read_text())
        self.policy = json.loads((self.repo / self.manifest).read_text())
        self.base = self.commit()
        self.marker = self.repo / 'marker'

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args],
                                       stderr=subprocess.PIPE).decode().strip()

    def write(self, path, content):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            target.write_bytes(content)
        else:
            target.write_text(content)

    def commit(self):
        self.git('add', '.')
        self.git('commit', '-m', 'synthetic fixture')
        return self.git('rev-parse', 'HEAD')

    def candidate(self):
        for path in self.policy['required_artifacts']:
            self.write(path, 'synthetic fixture\n')

    def run_guard(self, branch='feat/android-app-bootstrap-v1', expected='ARCH_PASS'):
        head = self.commit()
        # The candidate is never the execution worktree: restore the trusted base.
        self.git('switch', '--detach', self.base)
        result = subprocess.run([sys.executable, '-I', str(GUARD), '--base-ref', self.base,
                                 '--head-ref', head, '--branch', branch], cwd=self.repo,
                                capture_output=True, text=True)
        self.assertIn(expected, result.stdout, result.stderr)
        self.assertEqual(result.returncode, int(expected != 'ARCH_PASS'))
        self.assertFalse(self.marker.exists())
        self.assertEqual(self.git('rev-parse', 'HEAD'), self.base)
        return result

    def test_allowed_feature(self):
        self.candidate()
        self.run_guard()

    def test_unknown_frontier(self):
        self.write('app/probe.txt', 'synthetic')
        self.run_guard('test/unassigned', 'ARCH_FRONTIER_UNKNOWN')

    def test_governance_and_docs_modes(self):
        self.write('docs/architecture/probe.md', 'synthetic')
        self.run_guard('docs/synthetic')

    def test_governance_cannot_add_feature(self):
        self.write('app/probe.txt', 'synthetic')
        self.run_guard('chore/architecture-governance/probe', 'ARCH_PATH_NOT_ALLOWED')

    def test_candidate_cannot_replace_judges(self):
        self.candidate()
        payload = 'from pathlib import Path\nPath(' + repr(str(self.marker)) + ').touch()\n'
        for path in ['scripts/architecture/check_guardrails.py',
                     'scripts/security/scan_secrets.py', '.github/workflows/governance.yml']:
            self.write(path, payload)
        self.write(self.manifest, '{}')
        self.run_guard(expected='ARCH_GOVERNANCE_MUTATION')

    def test_forbidden_dependency(self):
        self.candidate()
        self.write('app/build.gradle.kts', 'implementation("retrofit")')
        self.run_guard(expected='ARCH_DEPENDENCY_FORBIDDEN')

    def test_required_missing(self):
        self.write('settings.gradle.kts', 'synthetic')
        self.run_guard(expected='ARCH_REQUIRED_ARTIFACT_MISSING')

    def test_malicious_shell_is_data(self):
        self.candidate()
        self.write('gradlew', 'touch ' + str(self.marker) + '\n')
        self.run_guard()

    def test_binary_is_data(self):
        self.candidate()
        self.write('gradle/wrapper/gradle-wrapper.jar', b'\x00\xffsynthetic')
        self.run_guard()

    def test_symlink_is_rejected(self):
        self.candidate()
        (self.repo / 'gradlew').unlink()
        os.symlink('../outside', self.repo / 'gradlew')
        self.run_guard(expected='ARCH_CONTENT_FORBIDDEN')

    def test_rename_checks_deleted_source(self):
        self.write('README.md', 'synthetic')
        self.base = self.commit()
        self.candidate()
        (self.repo / 'README.md').unlink()
        self.run_guard(expected='ARCH_GOVERNANCE_MUTATION')

    def test_malformed_trusted_policy(self):
        self.write(self.manifest, '{')
        self.base = self.commit()
        self.write('app/probe.txt', 'synthetic')
        self.run_guard(expected='ARCH_POLICY_INVALID')

    def test_ambiguous_trusted_policy(self):
        self.write('.github/architecture/frontiers/duplicate.json', json.dumps(self.policy))
        self.base = self.commit()
        self.write('app/probe.txt', 'synthetic')
        self.run_guard(expected='ARCH_POLICY_INVALID')


if __name__ == '__main__':
    unittest.main()
