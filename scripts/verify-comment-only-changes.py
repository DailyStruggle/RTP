#!/usr/bin/env python3
"""
verify-comment-only-changes.py - Verify that git changes to Java files are comment-only.

Usage:
    python scripts/verify-comment-only-changes.py [--base <ref>] [--verbose]

Compares the working tree (or staged changes) of all modified .java files against
a base git reference (default: HEAD) and verifies that no code tokens, literals,
or logic were altered - only comments and whitespace.
"""

from __future__ import annotations

import argparse
import difflib
import subprocess
import sys
from pathlib import Path


def tokenize_java_no_comments(source: str) -> list[tuple[str, int]]:
    """
    Tokenizes Java source code while stripping all comments (line, block, and Javadoc).
    String literals, char literals, text blocks, identifiers, keywords, numbers,
    and operators are preserved as tokens.
    
    Returns a list of (token_string, line_number).
    """
    tokens: list[tuple[str, int]] = []
    i = 0
    n = len(source)
    line_num = 1

    while i < n:
        c = source[i]

        # Newlines
        if c == '\n':
            line_num += 1
            i += 1
            continue
        if c == '\r':
            if i + 1 < n and source[i + 1] == '\n':
                i += 1
            line_num += 1
            i += 1
            continue

        # Whitespace
        if c.isspace():
            i += 1
            continue

        start_line = line_num

        # Text block """ (Java 15+)
        if source[i:i + 3] == '"""':
            start = i
            i += 3
            while i < n:
                if source[i] == '\n':
                    line_num += 1
                    i += 1
                elif source[i] == '\r':
                    if i + 1 < n and source[i + 1] == '\n':
                        i += 1
                    line_num += 1
                    i += 1
                elif source[i] == '\\':
                    i += 2  # skip escape
                elif source[i:i + 3] == '"""':
                    i += 3
                    break
                else:
                    i += 1
            tokens.append((source[start:i], start_line))
            continue

        # Single-line string literal "..."
        if c == '"':
            start = i
            i += 1
            while i < n:
                if source[i] in ('\n', '\r'):
                    break
                if source[i] == '\\':
                    i += 2
                elif source[i] == '"':
                    i += 1
                    break
                else:
                    i += 1
            tokens.append((source[start:i], start_line))
            continue

        # Character literal '.'
        if c == "'":
            start = i
            i += 1
            while i < n:
                if source[i] in ('\n', '\r'):
                    break
                if source[i] == '\\':
                    i += 2
                elif source[i] == "'":
                    i += 1
                    break
                else:
                    i += 1
            tokens.append((source[start:i], start_line))
            continue

        # Line comment //
        if source[i:i + 2] == '//':
            i += 2
            while i < n and source[i] not in ('\r', '\n'):
                i += 1
            continue

        # Block / Javadoc comment /* ... */
        if source[i:i + 2] == '/*':
            i += 2
            while i < n:
                if source[i] == '\n':
                    line_num += 1
                    i += 1
                elif source[i] == '\r':
                    if i + 1 < n and source[i + 1] == '\n':
                        i += 1
                    line_num += 1
                    i += 1
                elif source[i:i + 2] == '*/':
                    i += 2
                    break
                else:
                    i += 1
            continue

        # Identifier, keyword, or number
        if c.isalnum() or c in ('_', '$'):
            start = i
            while i < n and (source[i].isalnum() or source[i] in ('_', '$')):
                i += 1
            tokens.append((source[start:i], start_line))
            continue

        # Operators and punctuation
        matched_op = False
        for op_len in (3, 2):
            if i + op_len <= n:
                sub = source[i:i + op_len]
                if sub in (
                    '>>>', '>>=', '<<=', '===', '!==', '...',
                    '==', '!=', '<=', '>=', '&&', '||', '++', '--',
                    '<<', '>>', '+=', '-=', '*=', '/=', '%=', '&=',
                    '|=', '^=', '->', '::'
                ):
                    tokens.append((sub, start_line))
                    i += op_len
                    matched_op = True
                    break
        if not matched_op:
            tokens.append((c, start_line))
            i += 1

    return tokens


def get_git_status_java(base_ref: str) -> list[tuple[str, str]]:
    """
    Returns list of (status_code, file_path) for all changed .java files against base_ref.
    """
    cmd = ['git', 'diff', '--name-status', base_ref]
    res = subprocess.run(cmd, capture_output=True, text=True, check=True)
    results = []
    for line in res.stdout.splitlines():
        if not line.strip():
            continue
        parts = line.split(maxsplit=1)
        if len(parts) != 2:
            continue
        status, path = parts[0], parts[1]
        if path.endswith('.java'):
            results.append((status, path))
    return results


def get_file_content_at_ref(path: str, ref: str) -> str | None:
    try:
        # Use forward slashes for git object path
        git_path = path.replace('\\', '/')
        res = subprocess.run(
            ['git', 'show', f'{ref}:{git_path}'],
            capture_output=True,
            text=True,
            encoding='utf-8',
            errors='replace',
            check=True
        )
        return res.stdout
    except subprocess.CalledProcessError:
        return None


def get_working_file_content(path: str) -> str | None:
    p = Path(path)
    if not p.is_file():
        return None
    try:
        return p.read_text(encoding='utf-8', errors='replace')
    except Exception:
        return None


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify that Java changes against git ref are comment-only.")
    parser.add_argument('--base', default='HEAD', help="Base git ref to compare against (default: HEAD)")
    parser.add_argument('--verbose', '-v', action='store_true', help="Show verbose diff for non-comment changes")
    args = parser.parse_args()

    java_changes = get_git_status_java(args.base)
    if not java_changes:
        print(f"No Java files modified against '{args.base}'.")
        return 0

    print(f"Checking {len(java_changes)} modified Java file(s) against '{args.base}'...")

    comment_only_count = 0
    non_comment_files: list[tuple[str, str, list[str]]] = []

    for status, file_path in java_changes:
        if status != 'M':
            non_comment_files.append((file_path, f"File status is '{status}' (added/deleted/renamed)", []))
            continue

        base_content = get_file_content_at_ref(file_path, args.base)
        curr_content = get_working_file_content(file_path)

        if base_content is None:
            non_comment_files.append((file_path, f"Could not read base version at {args.base}", []))
            continue
        if curr_content is None:
            non_comment_files.append((file_path, "Could not read working tree file", []))
            continue

        base_tokens_with_line = tokenize_java_no_comments(base_content)
        curr_tokens_with_line = tokenize_java_no_comments(curr_content)

        base_tokens = [t[0] for t in base_tokens_with_line]
        curr_tokens = [t[0] for t in curr_tokens_with_line]

        if base_tokens == curr_tokens:
            comment_only_count += 1
        else:
            diff = list(difflib.unified_diff(
                [f"{tok} (L{line})\n" for tok, line in base_tokens_with_line],
                [f"{tok} (L{line})\n" for tok, line in curr_tokens_with_line],
                fromfile=f"{file_path} ({args.base})",
                tofile=f"{file_path} (working tree)",
                n=3
            ))
            non_comment_files.append((file_path, "Token stream mismatch (code/literals modified)", diff))

    print("-" * 70)
    print(f"Total Java files changed: {len(java_changes)}")
    print(f"Comment-only Java files:  {comment_only_count}")
    print(f"Non-comment Java files:   {len(non_comment_files)}")
    print("-" * 70)

    if non_comment_files:
        print("\nFAIL: The following Java files contain non-comment changes:")
        for path, reason, diff in non_comment_files:
            print(f"\n  [!] {path}: {reason}")
            if args.verbose and diff:
                print("      Diff:")
                for line in diff[:30]:
                    print("      " + line.rstrip())
                if len(diff) > 30:
                    print(f"      ... ({len(diff) - 30} more diff lines)")
        return 1

    print("\nSUCCESS: All Java changes are strictly comment-only (no code logic, tokens, or literals altered).")
    return 0


if __name__ == '__main__':
    sys.exit(main())
