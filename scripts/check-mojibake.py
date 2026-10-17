#!/usr/bin/env python3
"""
Mojibake scanner and fixer for UTF-8 text files read/written as CP1252/Latin-1.
ENTERPRISE_READINESS.md Item 49.

Usage:
    python scripts/check-mojibake.py          # report; exit 1 on findings
    python scripts/check-mojibake.py --fix    # repair in place, report what remains

Detection and repair live in scripts/mojibake.py (shared with the pre-commit hook).
Intentional mentions: add `mojibake-ok` to the line, or use a Markdown code span.
"""

import sys
import subprocess
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import mojibake  # noqa: E402

TEXT_EXTENSIONS = {'.java', '.gradle', '.md', '.txt', '.yml', '.yaml', '.xml', '.json', '.sh', '.py', '.toml'}

# Matched against whole path components, not substrings (build.gradle is scanned).
EXCLUDED_DIRS = {'.git', '.gradle', 'build', 'out', 'target', 'site'}

EXCLUDED_FILES = {
    'scripts/check-mojibake.py',
    'scripts/import-translation-bundle.ps1',
    '.junie/AGENTS.md',
    'docs/dev/ENTERPRISE_READINESS.md',
}


def is_excluded(rel_path: str) -> bool:
    if rel_path in EXCLUDED_FILES:
        return True
    return any(part in EXCLUDED_DIRS for part in rel_path.split('/')[:-1])


def check_file(path: Path, rel_path: str, fix: bool = False) -> list[str]:
    if is_excluded(rel_path):
        return []
    try:
        raw = path.read_bytes()
        content = raw.decode('utf-8')
    except (UnicodeDecodeError, PermissionError, OSError):
        return []

    fixed, findings = mojibake.repair(content, markdown=mojibake.is_markdown(rel_path))
    repaired = [f for f in findings if f.replacement is not None]
    if fix and repaired:
        path.write_bytes(fixed.encode('utf-8'))
        print(f"[FIXED] {rel_path}: replaced {len(repaired)} mojibake sequence(s)")
        for f in repaired:
            print(f"  {mojibake.describe(rel_path, f)}")
        findings = mojibake.scan(fixed, markdown=mojibake.is_markdown(rel_path))
    return [mojibake.describe(rel_path, f) for f in findings]


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='backslashreplace')
    fix_mode = '--fix' in sys.argv
    repo_root = Path(__file__).resolve().parent.parent

    res = subprocess.run(['git', 'ls-files', '-z'], cwd=repo_root, capture_output=True, check=True)
    tracked = [p for p in res.stdout.decode('utf-8', errors='replace').split('\0') if p]

    all_issues = []
    for rel_path in tracked:
        path = repo_root / rel_path
        if path.suffix.lower() in TEXT_EXTENSIONS and path.is_file():
            all_issues.extend(check_file(path, rel_path, fix=fix_mode))

    if all_issues:
        print(f"\nFound {len(all_issues)} mojibake issue(s):")
        for issue in all_issues:
            print(f"  {issue}")
        if not fix_mode:
            print("\nRun with --fix to repair the automatically recoverable ones.")
        sys.exit(1)
    print("No mojibake found in tracked text files.")
    sys.exit(0)


if __name__ == '__main__':
    main()
