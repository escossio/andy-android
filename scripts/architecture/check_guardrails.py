"""Trusted, standard-library-only architecture checks over inert Git objects."""
import argparse
import json
import re
import subprocess
from pathlib import Path


ZONES = ('app', 'core', 'sdk', 'features', 'capabilities', 'integrations', 'data', 'sync')
GOVERNANCE_DIRS = ('.github/architecture/', '.github/workflows/',
                   'scripts/architecture/', 'scripts/security/',
                   'tests/architecture/', 'tests/security/', 'docs/architecture/')
GOVERNANCE_FILES = {'AGENTS.md', 'README.md', 'SECURITY.md', 'LICENSE', '.gitignore',
                    'tests/__init__.py'} | {z + '/AGENTS.md' for z in ZONES}
FIELDS = {'schema_version', 'frontier_id', 'branch', 'allowed_paths',
          'required_artifacts', 'forbidden_content_patterns'}


def valid_path(path):
    return (isinstance(path, str) and bool(path) and not path.startswith('/')
            and not any(c in path for c in '\\*?[]:\x00\r\n')
            and all(p not in ('', '.', '..') for p in path.split('/')))


def governance(path):
    return (path in GOVERNANCE_FILES or path.endswith('/AGENTS.md')
            or any(path == p.rstrip('/') or path.startswith(p) for p in GOVERNANCE_DIRS))


def validate_manifest(manifest: dict) -> list[str]:
    bad = ['ARCH_POLICY_INVALID:manifest']
    if not isinstance(manifest, dict) or set(manifest) != FIELDS:
        return bad
    if type(manifest['schema_version']) is not int or manifest['schema_version'] != 1:
        return bad
    for key in ('frontier_id', 'branch'):
        if not isinstance(manifest[key], str) or not manifest[key].strip():
            return bad
    if not re.fullmatch(r'feat/[a-zA-Z0-9][a-zA-Z0-9/_-]*', manifest['branch']):
        return bad
    for key in ('allowed_paths', 'required_artifacts', 'forbidden_content_patterns'):
        values = manifest[key]
        if (not isinstance(values, list) or not all(isinstance(v, str) and v for v in values)
                or len(values) != len(set(values))):
            return bad
    if not manifest['allowed_paths']:
        return bad
    if any(not valid_path(p) or governance(p) for p in manifest['allowed_paths']):
        return bad
    if not set(manifest['required_artifacts']) <= set(manifest['allowed_paths']):
        return bad
    try:
        for pattern in manifest['forbidden_content_patterns']:
            re.compile(pattern, re.IGNORECASE)
    except re.error:
        return bad
    return []


def evaluate_frontier(manifest: dict, changed_paths: list[str],
                      candidate_text: dict[str, str]) -> list[str]:
    errors = validate_manifest(manifest)
    if errors:
        return errors
    for path in sorted(set(changed_paths)):
        if governance(path):
            errors.append('ARCH_GOVERNANCE_MUTATION:' + path)
        if not valid_path(path) or path not in manifest['allowed_paths']:
            errors.append('ARCH_PATH_NOT_ALLOWED:' + path)
    for path, text in sorted(candidate_text.items()):
        for pattern in manifest['forbidden_content_patterns']:
            if re.search(pattern, text, re.IGNORECASE):
                errors.append('ARCH_DEPENDENCY_FORBIDDEN:' + path + ':' + pattern)
    return errors


def git(*args):
    return subprocess.run(['git', '--no-replace-objects', *args], check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def commit_ref(ref):
    if not re.fullmatch(r'[0-9a-fA-F]{40}|[0-9a-fA-F]{64}', ref):
        raise ValueError('ARCH_CONTENT_FORBIDDEN:invalid_commit_sha')
    return git('rev-parse', '--verify', ref + '^{commit}').decode().strip()


def tree(ref):
    entries = {}
    for entry in git('ls-tree', '-r', '-z', ref).split(b'\0'):
        if not entry:
            continue
        meta, raw_path = entry.split(b'\t', 1)
        path = raw_path.decode('utf-8')
        mode, kind, oid = meta.decode().split()
        entries[path] = (mode, kind, oid)
    return entries


def json_policy(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('ARCH_POLICY_INVALID:duplicate_json_key')
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=unique)


def validate_set(manifests):
    if not manifests:
        raise ValueError('ARCH_POLICY_INVALID:no_manifests')
    ids, branches = set(), set()
    for manifest in manifests:
        if validate_manifest(manifest):
            raise ValueError('ARCH_POLICY_INVALID:manifest')
        if manifest['frontier_id'] in ids or manifest['branch'] in branches:
            raise ValueError('ARCH_POLICY_INVALID:ambiguous_frontier')
        ids.add(manifest['frontier_id'])
        branches.add(manifest['branch'])
    return manifests


def load_manifests_from_ref(ref: str) -> list[dict]:
    try:
        manifests = []
        for path, (mode, kind, _) in tree(ref).items():
            if path.startswith('.github/architecture/frontiers/') and path.endswith('.json'):
                if mode != '100644' or kind != 'blob':
                    raise ValueError('ARCH_POLICY_INVALID:manifest_mode')
                manifests.append(json_policy(git('show', '--no-ext-diff', '--no-textconv', ref + ':' + path)))
        return validate_set(manifests)
    except (OSError, UnicodeError, json.JSONDecodeError, subprocess.CalledProcessError) as exc:
        raise ValueError('ARCH_POLICY_INVALID:manifest_read') from exc


def changed_paths(base_ref: str, head_ref: str) -> list[str]:
    # No rename detection: a rename is a deletion plus an addition, checking both paths.
    raw = git('diff', '--no-ext-diff', '--no-textconv', '--no-renames',
              '--name-only', '-z', base_ref, head_ref, '--')
    return sorted(p.decode('utf-8') for p in raw.split(b'\0') if p)


def candidate_text_from_ref(head_ref: str, paths: list[str]) -> dict[str, str]:
    entries = tree(head_ref)
    result = {}
    for path in paths:
        if not valid_path(path):
            raise ValueError('ARCH_CONTENT_FORBIDDEN:invalid_path')
        if path not in entries:
            continue
        mode, kind, _ = entries[path]
        if mode not in ('100644', '100755') or kind != 'blob':
            raise ValueError('ARCH_CONTENT_FORBIDDEN:non_regular_entry')
        raw = git('show', '--no-ext-diff', '--no-textconv', head_ref + ':' + path)
        try:
            if b'\0' not in raw:
                result[path] = raw.decode('utf-8')
        except UnicodeDecodeError:
            pass
    return result


def evaluate_refs(base, head, branch):
    base, head = commit_ref(base), commit_ref(head)
    manifests = load_manifests_from_ref(base)
    paths = changed_paths(base, head)
    if any(not valid_path(p) for p in paths):
        return ['ARCH_CONTENT_FORBIDDEN:invalid_path']
    matching = [p for p in manifests if p['branch'] == branch]
    if branch.startswith('chore/architecture-governance/'):
        errors = ['ARCH_PATH_NOT_ALLOWED:' + p for p in paths
                  if p not in GOVERNANCE_FILES and not any(p.startswith(d) for d in GOVERNANCE_DIRS)]
    elif branch.startswith('docs/'):
        errors = ['ARCH_PATH_NOT_ALLOWED:' + p for p in paths
                  if p not in ('README.md', 'SECURITY.md') and not p.startswith('docs/')]
    elif len(matching) == 1:
        policy = matching[0]
        errors = evaluate_frontier(policy, paths, {})
        if errors:
            return errors
        entries = tree(head)
        for path in policy['required_artifacts']:
            if path not in entries:
                errors.append('ARCH_REQUIRED_ARTIFACT_MISSING:' + path)
        text = candidate_text_from_ref(head, sorted(set(paths + policy['required_artifacts'])))
        errors.extend(evaluate_frontier(policy, paths, text))
        return errors
    else:
        return ['ARCH_FRONTIER_UNKNOWN:unassigned_branch']
    if not errors:
        candidate_text_from_ref(head, paths)  # Reject links and non-regular changed entries.
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--policy-self-check', action='store_true')
    parser.add_argument('--base-ref')
    parser.add_argument('--head-ref')
    parser.add_argument('--branch')
    args = parser.parse_args()
    try:
        if args.policy_self_check:
            root = Path(__file__).resolve().parents[2]
            paths = sorted((root / '.github/architecture/frontiers').glob('*.json'))
            if any(p.is_symlink() for p in paths):
                raise ValueError('ARCH_POLICY_INVALID:manifest_mode')
            validate_set([json_policy(p.read_bytes()) for p in paths])
            errors = []
        else:
            if not all((args.base_ref, args.head_ref, args.branch)):
                parser.error('provide --base-ref, --head-ref and --branch')
            errors = evaluate_refs(args.base_ref, args.head_ref, args.branch)
    except (ValueError, OSError, subprocess.CalledProcessError) as exc:
        code = str(exc).split(':', 1)[0]
        if code not in ('ARCH_POLICY_INVALID', 'ARCH_CONTENT_FORBIDDEN'):
            code = 'ARCH_POLICY_INVALID' if args.policy_self_check else 'ARCH_CONTENT_FORBIDDEN'
        errors = [code + ':invalid_input']
    for error in errors:
        print(error.encode('unicode_escape').decode('ascii'))
    if not errors:
        print('ARCH_PASS')
    return int(bool(errors))


if __name__ == '__main__':
    raise SystemExit(main())
