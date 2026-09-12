import copy
import unittest

from scripts.architecture.check_guardrails import evaluate_frontier, validate_manifest


def policy():
    return dict(schema_version=1, frontier_id='synthetic', branch='feat/synthetic',
                allowed_paths=['settings.gradle.kts'], required_artifacts=[],
                forbidden_content_patterns=['retrofit'])


class GuardPolicyTests(unittest.TestCase):
    def test_exact_allowed_path_passes(self):
        self.assertEqual(evaluate_frontier(policy(), ['settings.gradle.kts'], {}), [])

    def test_path_outside_allowlist_fails(self):
        self.assertIn('ARCH_PATH_NOT_ALLOWED:README.md',
                      evaluate_frontier(policy(), ['README.md'], {}))

    def test_governance_mutation(self):
        for path in ['README.md', 'SECURITY.md', 'app/AGENTS.md',
                     '.github/workflows/governance.yml',
                     'scripts/security/scan_secrets.py',
                     'scripts/architecture/check_guardrails.py',
                     'docs/architecture/README.md']:
            with self.subTest(path=path):
                self.assertIn('ARCH_GOVERNANCE_MUTATION:' + path,
                              evaluate_frontier(policy(), [path], {}))

    def test_forbidden_dependency(self):
        errors = evaluate_frontier(policy(), ['settings.gradle.kts'],
                                   {'settings.gradle.kts': 'RETROFIT'})
        self.assertTrue(any(e.startswith('ARCH_DEPENDENCY_FORBIDDEN:') for e in errors))

    def test_invalid_manifests(self):
        cases = [{}, [], None]
        for key in policy():
            p = policy()
            del p[key]
            cases.append(p)
        for key, value in [('schema_version', True), ('allowed_paths', ['../escape']),
                           ('allowed_paths', ['/absolute']), ('allowed_paths', ['a/./b']),
                           ('allowed_paths', ['a//b']), ('allowed_paths', ['a\\b']),
                           ('allowed_paths', ['a', 'a']), ('allowed_paths', ['AGENTS.md']),
                           ('required_artifacts', ['outside']),
                           ('forbidden_content_patterns', ['[']), ('branch', 'docs/spoof'),
                           ('frontier_id', ''), ('allowed_paths', ['*.kt'])]:
            p = copy.deepcopy(policy())
            p[key] = value
            cases.append(p)
        for p in cases:
            with self.subTest(policy=p):
                self.assertTrue(any(e.startswith('ARCH_POLICY_INVALID')
                                    for e in validate_manifest(p)))

    def test_candidate_traversal(self):
        for path in ['../outside', '/outside', 'a/../b', 'a\\b', 'a//b']:
            self.assertTrue(evaluate_frontier(policy(), [path], {}))


if __name__ == '__main__':
    unittest.main()
