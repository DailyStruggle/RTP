"""
Scan the repository for *Parameter constructor invocations (subclasses of
io.github.dailystruggle.commandsapi.common.CommandParameter) and extract
(permission, description) pairs alongside file:line, supporting both
same-line and multi-line registrations.

Two patterns are scanned:

  1. Direct/anonymous instantiation:
         new <Foo>Parameter( <permission>, <description>, ... )
     where <permission> and <description> are the first two arguments. The
     class name must end with "Parameter".

  2. super(...) calls inside a class whose name ends with "Parameter":
         class <Foo>Parameter extends CommandParameter {
             ... super( <permission>, <description>, ... )
         }

Output: a Markdown table on stdout (default) or written to a file via --out.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from typing import Iterator

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir))

SKIP_DIRS = {".git", "build", "out", "target", ".gradle", ".idea", "node_modules"}

NEW_PARAM_RE = re.compile(r"\bnew\s+([A-Z][A-Za-z0-9_]*Parameter)\s*\(")
CLASS_PARAM_RE = re.compile(r"\bclass\s+([A-Z][A-Za-z0-9_]*Parameter)\b")
SUPER_RE = re.compile(r"\bsuper\s*\(")


def iter_java_files(root: str) -> Iterator[str]:
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            if fn.endswith(".java"):
                yield os.path.join(dirpath, fn)


def extract_args(text: str, open_paren_idx: int) -> tuple[list[str], int] | None:
    """Given text and the index of '(', return (args, end_index_after_close_paren).

    Splits top-level (depth-0) comma-separated arguments, preserving string
    literals and nested parens/braces. Returns None if parens are unbalanced
    within the slice.
    """
    depth = 0
    in_str = False
    in_char = False
    in_line_cmt = False
    in_block_cmt = False
    escape = False
    args: list[str] = []
    cur: list[str] = []
    i = open_paren_idx
    while i < len(text):
        c = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ""
        if in_line_cmt:
            if c == "\n":
                in_line_cmt = False
            i += 1
            continue
        if in_block_cmt:
            if c == "*" and nxt == "/":
                in_block_cmt = False
                i += 2
                continue
            i += 1
            continue
        if in_str:
            cur.append(c)
            if escape:
                escape = False
            elif c == "\\":
                escape = True
            elif c == '"':
                in_str = False
            i += 1
            continue
        if in_char:
            cur.append(c)
            if escape:
                escape = False
            elif c == "\\":
                escape = True
            elif c == "'":
                in_char = False
            i += 1
            continue
        # not in string / comment
        if c == "/" and nxt == "/":
            in_line_cmt = True
            i += 2
            continue
        if c == "/" and nxt == "*":
            in_block_cmt = True
            i += 2
            continue
        if c == '"':
            in_str = True
            cur.append(c)
            i += 1
            continue
        if c == "'":
            in_char = True
            cur.append(c)
            i += 1
            continue
        if c == "(":
            depth += 1
            if depth > 1:
                cur.append(c)
            i += 1
            continue
        if c == ")":
            depth -= 1
            if depth == 0:
                args.append("".join(cur).strip())
                return args, i + 1
            cur.append(c)
            i += 1
            continue
        if c == "," and depth == 1:
            args.append("".join(cur).strip())
            cur = []
            i += 1
            continue
        cur.append(c)
        i += 1
    return None


def line_of(text: str, idx: int) -> int:
    return text.count("\n", 0, idx) + 1


def normalize_arg(arg: str) -> str:
    # Collapse whitespace + newlines for readable table cell
    return re.sub(r"\s+", " ", arg).strip()


def scan_file(path: str) -> list[tuple[str, int, str, str, str, str]]:
    """Return list of (file, line, kind, classname, permission, description)."""
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        text = f.read()
    results: list[tuple[str, int, str, str, str, str]] = []

    # Pattern 1: new <X>Parameter(
    for m in NEW_PARAM_RE.finditer(text):
        cls = m.group(1)
        paren = m.end() - 1
        extracted = extract_args(text, paren)
        if not extracted:
            continue
        args, _ = extracted
        if len(args) < 2:
            continue
        perm, desc = args[0], args[1]
        results.append((path, line_of(text, m.start()), "new", cls, normalize_arg(perm), normalize_arg(desc)))

    # Pattern 2: super(...) inside class <X>Parameter
    for cm in CLASS_PARAM_RE.finditer(text):
        cls = cm.group(1)
        # find the class body's opening brace after the class name
        brace = text.find("{", cm.end())
        if brace < 0:
            continue
        # Find matching close brace to bound the search
        depth = 0
        i = brace
        end = len(text)
        in_str = False
        in_char = False
        in_line_cmt = False
        in_block_cmt = False
        escape = False
        while i < len(text):
            c = text[i]
            nxt = text[i + 1] if i + 1 < len(text) else ""
            if in_line_cmt:
                if c == "\n":
                    in_line_cmt = False
                i += 1
                continue
            if in_block_cmt:
                if c == "*" and nxt == "/":
                    in_block_cmt = False
                    i += 2
                    continue
                i += 1
                continue
            if in_str:
                if escape:
                    escape = False
                elif c == "\\":
                    escape = True
                elif c == '"':
                    in_str = False
                i += 1
                continue
            if in_char:
                if escape:
                    escape = False
                elif c == "\\":
                    escape = True
                elif c == "'":
                    in_char = False
                i += 1
                continue
            if c == "/" and nxt == "/":
                in_line_cmt = True
                i += 2
                continue
            if c == "/" and nxt == "*":
                in_block_cmt = True
                i += 2
                continue
            if c == '"':
                in_str = True
                i += 1
                continue
            if c == "'":
                in_char = True
                i += 1
                continue
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    end = i
                    break
            i += 1
        body = text[brace:end]
        # find super(...) in body
        for sm in SUPER_RE.finditer(body):
            paren = sm.end() - 1
            extracted = extract_args(body, paren)
            if not extracted:
                continue
            args, _ = extracted
            if len(args) < 2:
                continue
            perm, desc = args[0], args[1]
            abs_idx = brace + sm.start()
            results.append((path, line_of(text, abs_idx), "super", cls, normalize_arg(perm), normalize_arg(desc)))
    return results


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=ROOT)
    parser.add_argument("--out", default=None, help="Write Markdown table to this path (default: stdout)")
    args = parser.parse_args()

    all_rows: list[tuple[str, int, str, str, str, str]] = []
    for jf in iter_java_files(args.root):
        try:
            all_rows.extend(scan_file(jf))
        except Exception as exc:  # noqa: BLE001 - script, surface and continue
            print(f"# WARN: {jf}: {exc}", file=sys.stderr)

    # sort by file then line
    all_rows.sort(key=lambda r: (r[0].lower(), r[1]))

    lines: list[str] = []
    lines.append("| File | Line | Kind | Class | Permission | Description |")
    lines.append("|------|-----:|------|-------|------------|-------------|")
    for path, ln, kind, cls, perm, desc in all_rows:
        rel = os.path.relpath(path, args.root).replace("\\", "/")
        def esc(s: str) -> str:
            return s.replace("|", "\\|")
        lines.append(f"| {esc(rel)} | {ln} | {kind} | {esc(cls)} | {esc(perm)} | {esc(desc)} |")
    out = "\n".join(lines) + "\n"
    if args.out:
        with open(args.out, "w", encoding="utf-8", newline="\n") as f:
            f.write(out)
        print(f"Wrote {len(all_rows)} rows to {args.out}")
    else:
        sys.stdout.write(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
