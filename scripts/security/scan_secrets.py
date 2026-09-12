"""Scan UTF-8 worktree files or inert Git blobs; never execute candidate code."""
import argparse
import os
from pathlib import Path
import re
import subprocess


MAX_SIZE = 2 * 1024 * 1024
SKIP_DIRS = {'.git', '.gradle', '.idea', 'build', '__pycache__'}
BINARY = {'.jar', '.png', '.jpg', '.jpeg', '.gif', '.webp', '.zip', '.gz', '.pdf', '.pyc'}
RULES = {
    'PRIVATE_KEY': re.compile(r'-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----'),
    'GITHUB_TOKEN': re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{50,})\b'),
    'AWS_ACCESS_KEY': re.compile(r'\bAKIA[A-Z0-9]{16}\b'),
    'OPENAI_STYLE_SECRET': re.compile(r'\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{32,}\b'),
}


def scan_text(path: str, text: str) -> list[str]:
    return ['SECRET_SCAN_FINDING:' + path + ':' + name
            for name, pattern in RULES.items() if pattern.search(text)]


def ignored(path):
    return bool(set(Path(path).parts) & SKIP_DIRS) or Path(path).suffix.lower() in BINARY


def iter_candidate_files(paths: list[str]) -> list[str]:
    result = set()
    for raw in paths:
        root = Path(raw)
        if root.is_symlink():
            raise ValueError('UNSAFE_ENTRY')
        if not root.exists():
            raise ValueError('UNREADABLE_PATH')
        if root.is_dir():
            candidates = []
            for directory, dirs, files in os.walk(root, followlinks=False):
                for name in dirs + files:
                    if (Path(directory) / name).is_symlink():
                        raise ValueError('UNSAFE_ENTRY')
                dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
                candidates.extend(Path(directory) / f for f in files)
        else:
            candidates = [root]
        for path in candidates:
            if path.is_symlink() or not path.is_file():
                raise ValueError('UNSAFE_ENTRY')
            if not ignored(path) and path.stat().st_size <= MAX_SIZE:
                result.add(str(path))
    return sorted(result)


def git(*args):
    return subprocess.run(['git', '--no-replace-objects', *args], check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def git_texts(ref):
    if not re.fullmatch(r'[0-9a-fA-F]{40}|[0-9a-fA-F]{64}', ref):
        raise ValueError('INVALID_REF')
    ref = git('rev-parse', '--verify', ref + '^{commit}').decode().strip()
    for entry in git('ls-tree', '-r', '-z', ref).split(b'\0'):
        if not entry:
            continue
        meta, raw = entry.split(b'\t', 1)
        mode, kind, oid = meta.decode().split()
        path = raw.decode('utf-8')
        if (path.startswith('/') or '\\' in path
                or any(p in ('', '.', '..') for p in path.split('/'))
                or mode not in ('100644', '100755') or kind != 'blob'):
            raise ValueError('UNSAFE_ENTRY')
        if ignored(path) or int(git('cat-file', '-s', oid)) > MAX_SIZE:
            continue
        data = git('cat-file', 'blob', oid)
        try:
            if b'\0' not in data:
                yield path, data.decode('utf-8')
        except UnicodeDecodeError:
            continue


def worktree_texts(paths):
    for path in iter_candidate_files(paths):
        try:
            data = Path(path).read_bytes()
            if b'\0' not in data:
                yield path, data.decode('utf-8')
        except UnicodeDecodeError:
            continue


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--git-ref')
    parser.add_argument('paths', nargs='*')
    args = parser.parse_args()
    errors = []
    try:
        source = git_texts(args.git_ref) if args.git_ref else worktree_texts(args.paths or ['.'])
        for path, text in source:
            errors.extend(scan_text(path, text))
    except (ValueError, OSError, subprocess.CalledProcessError) as exc:
        rule = str(exc) if str(exc) in ('UNSAFE_ENTRY', 'INVALID_REF', 'UNREADABLE_PATH') else 'UNREADABLE_INPUT'
        errors.append('SECRET_SCAN_FINDING:input:' + rule)
    for error in errors:
        print(error.encode('unicode_escape').decode('ascii'))
    if not errors:
        print('SECRET_SCAN_PASS')
    return int(bool(errors))


if __name__ == '__main__':
    raise SystemExit(main())
