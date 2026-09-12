#!/usr/bin/env python3
"""audit_config_comments.py

Cross-platform audit and fix utility for YAML config files.
Audits:
  1. Missing newlines before comment blocks (e.g. key: val directly followed by # comment for next key).
  2. Missing spaces after comment hashes (e.g. `#servers:` instead of `# servers:`).
  3. Unnecessarily long comments (> 8 lines or lines > 120 chars) that violate
     ADR-064 / CONFIG_COMMENT_STYLE telegraphic style and harm in-game menu hover tooltips.

Usage:
  python scripts/audit_config_comments.py [--root DIR] [--fix-newlines] [--fix-spaces] [--check]
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

# Ensure UTF-8 output on Windows
if sys.stdout and hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def scan_file(path: Path) -> dict:
    try:
        text = path.read_text(encoding="utf-8")
    except Exception as e:
        return {"error": str(e)}

    lines = text.splitlines(keepends=True)

    missing_newlines = []
    missing_space_hash = []
    long_comment_blocks = []
    long_lines = []

    current_block = []
    current_start = 0

    for i, line in enumerate(lines, 1):
        stripped = line.strip()

        # Check missing newline before comment
        # A comment block starts when line is a comment, but previous line was non-empty and not a comment.
        if i > 1 and stripped.startswith("#"):
            prev_line = lines[i - 2]
            prev_stripped = prev_line.strip()
            # If previous line is not a comment and not empty
            if prev_stripped != "" and not prev_stripped.startswith("#"):
                curr_indent = len(line) - len(line.lstrip())
                prev_indent = len(prev_line) - len(prev_line.lstrip())
                # Exclude case where prev_line is a section header (ends with ':') and comment is indented inside it
                is_section_intro = prev_stripped.endswith(":") and curr_indent > prev_indent
                if not is_section_intro:
                    missing_newlines.append((i, prev_line.rstrip(), line.rstrip()))

        # Check missing space after # in comment
        if "#" in stripped:
            idx = line.find("#")
            prefix = line[:idx]
            if prefix.count('"') % 2 == 0 and prefix.count("'") % 2 == 0:
                comment_part = line[idx:].rstrip("\r\n")
                if re.match(r"^#[A-Za-z0-9]", comment_part):
                    if not re.match(r"^#[0-9A-Fa-f]{6}(\b|[^A-Za-z0-9])", comment_part) and not comment_part.startswith("#minecraft:"):
                        missing_space_hash.append((i, line))

        # Check line width of comments (> 120 chars)
        if stripped.startswith("#") and len(line.rstrip("\r\n")) > 120:
            long_lines.append((i, len(line.rstrip("\r\n")), line))

        # Check long comment blocks (> 8 lines)
        if stripped.startswith("#"):
            if not current_block:
                current_start = i
            current_block.append((i, line))
        else:
            if current_block:
                if len(current_block) > 8:
                    long_comment_blocks.append((current_start, len(current_block), current_block))
                current_block = []

    if current_block and len(current_block) > 8:
        long_comment_blocks.append((current_start, len(current_block), current_block))

    return {
        "missing_newlines": missing_newlines,
        "missing_space_hash": missing_space_hash,
        "long_comment_blocks": long_comment_blocks,
        "long_lines": long_lines,
    }


def fix_file(path: Path, fix_newlines: bool = False, fix_spaces: bool = False) -> tuple[int, int]:
    try:
        text = path.read_text(encoding="utf-8")
    except Exception:
        return (0, 0)

    lines = text.splitlines(keepends=True)
    new_lines = []
    newlines_added = 0
    spaces_fixed = 0

    for i, line in enumerate(lines):
        stripped = line.strip()

        # Fix missing newline before comment
        if fix_newlines and i > 0 and stripped.startswith("#"):
            prev_line = lines[i - 1]
            prev_stripped = prev_line.strip()
            if prev_stripped != "" and not prev_stripped.startswith("#"):
                curr_indent = len(line) - len(line.lstrip())
                prev_indent = len(prev_line) - len(prev_line.lstrip())
                is_section_intro = prev_stripped.endswith(":") and curr_indent > prev_indent
                if not is_section_intro:
                    new_lines.append("\n")
                    newlines_added += 1

        # Fix missing space after '#'
        if fix_spaces and "#" in stripped:
            idx = line.find("#")
            prefix = line[:idx]
            if prefix.count('"') % 2 == 0 and prefix.count("'") % 2 == 0:
                comment_part = line[idx:].rstrip("\r\n")
                if re.match(r"^#[A-Za-z0-9]", comment_part):
                    if not re.match(r"^#[0-9A-Fa-f]{6}(\b|[^A-Za-z0-9])", comment_part) and not comment_part.startswith("#minecraft:"):
                        line = prefix + "# " + line[idx + 1:]
                        spaces_fixed += 1

        new_lines.append(line)

    if newlines_added > 0 or spaces_fixed > 0:
        path.write_text("".join(new_lines), encoding="utf-8")

    return (newlines_added, spaces_fixed)


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit and fix YAML config comments, newlines, and spacing.")
    parser.add_argument("--root", default=".", help="Root directory to scan (default: .)")
    parser.add_argument("--check", action="store_true", help="Exit with non-zero if issues found")
    parser.add_argument("--fix-newlines", action="store_true", help="Automatically insert missing newlines before comment blocks")
    parser.add_argument("--fix-spaces", action="store_true", help="Automatically fix missing space after '#' in comments")
    args = parser.parse_args()

    root_path = Path(args.root)
    yaml_files = []

    if root_path.is_file():
        yaml_files.append(root_path)
    else:
        for p in root_path.rglob("*.yml"):
            if any(part in p.parts for part in [".git", "build", ".gradle", "node_modules", "site"]):
                continue
            yaml_files.append(p)
        for p in root_path.rglob("*.yaml"):
            if any(part in p.parts for part in [".git", "build", ".gradle", "node_modules", "site"]):
                continue
            yaml_files.append(p)

    yaml_files.sort()

    total_missing_newlines = 0
    total_missing_space_hash = 0
    total_long_comment_blocks = 0
    total_long_lines = 0

    files_with_issues = 0

    for yf in yaml_files:
        res = scan_file(yf)
        if "error" in res:
            continue

        nl_count = len(res["missing_newlines"])
        h_count = len(res["missing_space_hash"])
        b_count = len(res["long_comment_blocks"])
        l_count = len(res["long_lines"])

        if nl_count or h_count or b_count or l_count:
            files_with_issues += 1
            total_missing_newlines += nl_count
            total_missing_space_hash += h_count
            total_long_comment_blocks += b_count
            total_long_lines += l_count

            print(f"\n{yf.as_posix()}:")
            if nl_count:
                print(f"  Missing newline before comment block ({nl_count} occurrence(s)):")
                for lineno, prev, curr in res["missing_newlines"][:3]:
                    print(f"    L{lineno}: after '{prev.strip()[:40]}' -> '{curr.strip()[:40]}'")
                if nl_count > 3:
                    print(f"    ... and {nl_count - 3} more occurrence(s)")
            if h_count:
                print(f"  Missing space after '#' ({h_count} occurrence(s)):")
                for lineno, line in res["missing_space_hash"]:
                    print(f"    L{lineno}: {line.rstrip()}")
            if b_count:
                print(f"  Long comment blocks > 8 lines ({b_count} occurrence(s)):")
                for start, count, blk in res["long_comment_blocks"][:2]:
                    preview = blk[0][1].strip()[:70]
                    print(f"    L{start} ({count} lines): {preview}...")
                if b_count > 2:
                    print(f"    ... and {b_count - 2} more block(s)")
            if l_count:
                print(f"  Comment lines > 120 chars ({l_count} occurrence(s)):")
                for lineno, length, line in res["long_lines"][:2]:
                    print(f"    L{lineno} ({length} chars): {line.strip()[:70]}...")

    print("\n" + "=" * 50)
    print("AUDIT SUMMARY:")
    print(f"  Total YAML files scanned: {len(yaml_files)}")
    print(f"  Files with issues: {files_with_issues}")
    print(f"  Missing newlines before comments: {total_missing_newlines}")
    print(f"  Missing space after '#': {total_missing_space_hash}")
    print(f"  Long comment blocks (>8 lines): {total_long_comment_blocks}")
    print(f"  Long comment lines (>120 chars): {total_long_lines}")
    print("=" * 50)

    if (args.fix_newlines or args.fix_spaces) and (total_missing_newlines > 0 or total_missing_space_hash > 0):
        tot_nl = 0
        tot_sp = 0
        for yf in yaml_files:
            nl, sp = fix_file(yf, fix_newlines=args.fix_newlines, fix_spaces=args.fix_spaces)
            tot_nl += nl
            tot_sp += sp
        print(f"\n[FIX] Applied: {tot_nl} newline(s) inserted, {tot_sp} comment space(s) fixed.")

    if args.check and (total_missing_newlines or total_missing_space_hash):
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
