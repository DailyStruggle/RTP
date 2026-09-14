#!/usr/bin/env python3
"""
Mojibake scanner and fixer for UTF-8 text files read/written as CP1252/Latin-1.
ENTERPRISE_READINESS.md Item 49.
"""

import sys
import subprocess
from pathlib import Path

# Common mojibake sequences produced when UTF-8 multi-byte sequences are decoded as CP1252/Latin-1
MOJIBAKE_MAP = {
    # Punctuation / dashes / arrows
    'â€”': '—',   # Em dash (U+2014)
    'â€“': '–',   # En dash (U+2013)
    'â†’': '→',   # Right arrow (U+2192)
    'â–¶': '▶',   # Black right-pointing triangle (U+25B6)
    'âš¡': '⚡',   # High voltage (U+26A1)
    'âœŽ': '✎',   # Lower right pencil (U+270E)
    'âœ…': '✅',   # White heavy check mark (U+2705)
    'Â«': '«',    # Left-pointing double angle quotation mark (U+00AB)
    'Â»': '»',    # Right-pointing double angle quotation mark (U+00BB)
    'Â·': '·',    # Middle dot (U+00B7)
    'Â²': '²',    # Superscript two (U+00B2)
    'Â°': '°',    # Degree sign (U+00B0)
    'Â§': '§',    # Section sign (U+00A7)
    'â€¦': '…',   # Horizontal ellipsis (U+2026)
    'â‰ˆ': '≈',   # Almost equal to (U+2248)
    'âˆ’': '−',   # Minus sign (U+2212)
    'âˆˆ': '∈',   # Element of (U+2208)
    'Î£': 'Σ',    # Greek capital letter Sigma (U+03A3)

    # Box-drawing characters
    'â”Œ': '┌',
    'â”€': '─',
    'â”': '┐',
    'â”‚': '│',
    'â””': '└',
    'â”˜': '┘',
    'â”œ': '├',
    'â”¤': '┤',
    'â”¬': '┬',
    'â”´': '┴',
    'â”¼': '┼',
    'â–¼': '▼',
}

# Substrings that strongly signal mojibake
MARKERS = ['â€', 'â”', 'â–', 'âš', 'âœ', 'Â«', 'Â»', 'Â·', 'Â²', 'Â°', 'Â§', 'â€¦', 'â†', 'â‰', 'âˆ']

EXCLUDED_PATHS = [
    '.git',
    '.gradle',
    'build',
    'out',
    'target',
    'site',
    'scripts/check-mojibake.py',
    'scripts/import-translation-bundle.ps1',
    '.junie/AGENTS.md',
    'docs/dev/ENTERPRISE_READINESS.md',
]

def is_tracked(path_str: str) -> bool:
    try:
        res = subprocess.run(['git', 'ls-files', '--error-unmatch', path_str],
                             capture_output=True, text=True)
        return res.returncode == 0
    except Exception:
        return True

def fix_content(text: str) -> tuple[str, int]:
    # Fix double-encoded UTF-8: UTF-8 bytes decoded as CP1252, then re-encoded as UTF-8.
    # To repair cleanly, we can encode text to CP1252 (or latin-1 where cp1252 fails) and decode as utf-8.
    # But because some valid UTF-8 may be present, we map specific known corrupt multi-byte CP1252 strings.
    count = 0
    for bad, good in MOJIBAKE_MAP.items():
        if bad in text:
            occurrences = text.count(bad)
            count += occurrences
            text = text.replace(bad, good)
    return text, count

def check_file(path: Path, fix: bool = False) -> list[str]:
    rel_path = path.as_posix()
    for exc in EXCLUDED_PATHS:
        if exc in rel_path:
            return []

    try:
        content = path.read_text(encoding='utf-8')
    except (UnicodeDecodeError, PermissionError):
        return []

    if fix:
        new_content, replaced = fix_content(content)
        if replaced > 0:
            path.write_text(new_content, encoding='utf-8')
            print(f"[FIXED] {rel_path}: replaced {replaced} mojibake sequences")
            content = new_content

    issues = []
    lines = content.splitlines()
    for idx, line in enumerate(lines, 1):
        for marker in MARKERS:
            if marker in line:
                issues.append(f"{rel_path}:{idx}: contains marker '{marker}' -> {line.strip()[:100]}")
                break
    return issues

def main():
    fix_mode = '--fix' in sys.argv
    repo_root = Path(__file__).resolve().parent.parent

    # Get tracked files from git
    res = subprocess.run(['git', 'ls-files'], cwd=repo_root, capture_output=True, text=True, check=True)
    tracked_files = [repo_root / line.strip() for line in res.stdout.splitlines() if line.strip()]

    all_issues = []
    text_extensions = {'.java', '.gradle', '.md', '.txt', '.yml', '.yaml', '.xml', '.json', '.sh', '.py', '.toml'}

    for path in tracked_files:
        if path.suffix.lower() in text_extensions and path.is_file():
            issues = check_file(path, fix=fix_mode)
            all_issues.extend(issues)

    if all_issues:
        print(f"\nFound {len(all_issues)} mojibake issue(s):")
        for issue in all_issues:
            print(f"  {issue}")
        sys.exit(1)
    else:
        print("No mojibake found in tracked text files.")
        sys.exit(0)

if __name__ == '__main__':
    main()
