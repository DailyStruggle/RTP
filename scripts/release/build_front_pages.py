#!/usr/bin/env python3
"""
Builds every storefront page from one tagged source, docs/publishing/FRONT_PAGE.md.

Targets (outputs go to scripts/release/generated/, which is gitignored: build them
locally before pasting into BuiltByBit; release.yml builds them before pushing):
    bbb-pro    BuiltByBit paid-support listing   FRONT_PAGE_PRO.bbcode   (pasted by hand)
    bbb-lite   BuiltByBit free listing           FRONT_PAGE_LITE.bbcode  (pasted by hand)
    modrinth   Modrinth description              FRONT_PAGE_MODRINTH.md  (pushed by release.yml)
    hangar     Hangar main page                  FRONT_PAGE_HANGAR.md    (pushed by release.yml)

Source markup. Each marker sits alone on its line; blocks may nest; an unclosed
or mismatched block is an error:
    <!-- kind: promo -->            ... <!-- /kind -->   what the block IS; POLICY decides where it goes
    <!-- only: bbb-pro, hangar -->  ... <!-- /only -->   audience copy for the listed targets
    <!-- not: hangar -->            ... <!-- /not -->    audience copy for every other target
    {{name}}, {{jar}}               per-target values (TARGETS[...].variables)
Aliases in only/not: `bbb` = both BuiltByBit targets, `markdown` = modrinth + hangar.
The first HTML comment in the file is author notes and is never emitted. Any
other HTML comment is an error: Modrinth and GitHub hide comments but render
what sits between them, so the raw source must never be published directly.

The BBCode converter accepts a markdown subset (headings, paragraphs, `-` lists,
pipe tables, links, images, badges, **bold**, *italic*, `code`, centred <div>,
<details>/<summary>, ---) and rejects anything else instead of guessing. Numbered
steps ("1. ...") pass through as plain text lines.

Usage:
    python scripts/release/build_front_pages.py                  # write all outputs
    python scripts/release/build_front_pages.py --check          # validate every target, write nothing
    python scripts/release/build_front_pages.py --target hangar --stdout
"""

import argparse
import pathlib
import re
import sys
from dataclasses import dataclass, field

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs" / "publishing" / "FRONT_PAGE.md"
OUT_DIR = ROOT / "scripts" / "release" / "generated"
SOURCE_REL = "docs/publishing/FRONT_PAGE.md"


@dataclass(frozen=True)
class Target:
    name: str
    fmt: str  # "bbcode" or "markdown"
    filename: str
    variables: dict = field(default_factory=dict)
    denylist: bool = False


_LITE_VARS = {"name": "LeafRTP", "jar": "LeafRTP-x.y.z.jar"}
TARGETS = {
    "bbb-pro": Target("bbb-pro", "bbcode", "FRONT_PAGE_PRO.bbcode",
                      {"name": "LeafRTP-Pro", "jar": "LeafRTP-Pro-x.y.z.jar"}),
    "bbb-lite": Target("bbb-lite", "bbcode", "FRONT_PAGE_LITE.bbcode", _LITE_VARS),
    "modrinth": Target("modrinth", "markdown", "FRONT_PAGE_MODRINTH.md", _LITE_VARS, denylist=True),
    "hangar": Target("hangar", "markdown", "FRONT_PAGE_HANGAR.md", _LITE_VARS, denylist=True),
}
ALIASES = {"bbb": ("bbb-pro", "bbb-lite"), "markdown": ("modrinth", "hangar")}

# kind -> targets that keep blocks of that kind. Edit here when a marketplace's
# rules change; never by retagging the source.
POLICY = {
    "promo": {"bbb-pro", "bbb-lite"},     # paid-support pitch, "what you pay for"
    "purchase": {"bbb-pro", "bbb-lite"},  # links or prompts to buy
}

# Fail-closed safety net for targets that must carry no paid-edition copy: a
# forgotten `kind: promo` tag becomes a red build instead of a listing takedown.
DENYLIST = [
    (re.compile(r"\bPro\b"), "'Pro'"),
    (re.compile(r"(?i)\bpremium\b"), "'premium'"),
    (re.compile(r"(?i)builtbybit"), "'BuiltByBit'"),
    (re.compile(r"(?i)\bpurchas"), "'purchase'"),
    (re.compile(r"(?i)\bbuy(s|ing)?\b"), "'buy'"),
    (re.compile(r"(?i)\b(sponsor|donat|patreon|ko-fi|paypal)"), "sponsorship / donation"),
    (re.compile(r"[$\u20ac\u00a3]\s?\d"), "a price"),
]

# Share of source lines that are audience-specific before the report warns that
# the pages are drifting back toward separate copies.
DRIFT_WARN = 0.15

HEADING_COLOR = "#27AE60"

_OPEN = re.compile(r"^\s*<!--\s*(kind|only|not)\s*:\s*(.*?)\s*-->\s*$")
_CLOSE = re.compile(r"^\s*<!--\s*/(kind|only|not)\s*-->\s*$")
_VAR = re.compile(r"\{\{\s*(\w+)\s*\}\}")
_LATEX = re.compile(r"\$[^$\s][^$]*\$")
_RELATIVE_LINK = re.compile(r"\]\((?!https?://|#|mailto:)([^)]*)\)")


class FrontPageError(Exception):
    pass


# --------------------------------------------------------------------------- source


def _strip_notes(text):
    """Returns (body, line offset) with the leading author-notes comment removed."""
    stripped = text.lstrip("\ufeff")
    lead = len(stripped) - len(stripped.lstrip())
    rest = stripped[lead:]
    if rest.startswith("<!--") and not _OPEN.match(rest.split("\n", 1)[0]):
        end = rest.find("-->")
        if end < 0:
            raise FrontPageError("line 1: author-notes comment is never closed")
        consumed = stripped[:lead + end + 3]
        return stripped[lead + end + 3:], consumed.count("\n")
    return stripped, 0


def _targets_in(args, line_no):
    names = [a.strip() for a in args.split(",") if a.strip()]
    if not names:
        raise FrontPageError(f"line {line_no}: block marker lists no targets")
    out = set()
    for n in names:
        if n in ALIASES:
            out.update(ALIASES[n])
        elif n in TARGETS:
            out.add(n)
        else:
            raise FrontPageError(f"line {line_no}: unknown target '{n}' (known: "
                                 f"{', '.join(sorted(TARGETS) + sorted(ALIASES))})")
    return out


def _kinds_in(args, line_no):
    kinds = [a.strip() for a in args.split(",") if a.strip()]
    if not kinds:
        raise FrontPageError(f"line {line_no}: kind marker names no kind")
    for k in kinds:
        if k not in POLICY:
            raise FrontPageError(f"line {line_no}: unknown kind '{k}' (known: {', '.join(sorted(POLICY))})")
    return kinds


def select_lines(text, target):
    """Resolves block markers for one target.

    Returns (lines, audience_specific, shared): the kept source lines and the
    counts of non-blank source lines inside / outside `only`/`not` blocks.
    """
    body, offset = _strip_notes(text)
    stack = []  # (marker type, allows target, opening line, audience-specific)
    kept = []
    specific = shared = 0
    for idx, line in enumerate(body.split("\n")):
        line_no = idx + 1 + offset
        m = _OPEN.match(line)
        if m:
            typ, args = m.group(1), m.group(2)
            if typ == "kind":
                allows = all(target in POLICY[k] for k in _kinds_in(args, line_no))
            elif typ == "only":
                allows = target in _targets_in(args, line_no)
            else:
                allows = target not in _targets_in(args, line_no)
            stack.append((typ, allows, line_no, typ != "kind"))
            continue
        m = _CLOSE.match(line)
        if m:
            if not stack:
                raise FrontPageError(f"line {line_no}: '/{m.group(1)}' closes nothing")
            if stack[-1][0] != m.group(1):
                raise FrontPageError(f"line {line_no}: '/{m.group(1)}' closes the "
                                     f"'{stack[-1][0]}' block opened on line {stack[-1][2]}")
            stack.pop()
            continue
        if "<!--" in line or "-->" in line:
            raise FrontPageError(f"line {line_no}: only block markers may use HTML comments")
        if line.strip():
            if any(s[3] for s in stack):
                specific += 1
            else:
                shared += 1
        if all(s[1] for s in stack):
            kept.append((line_no, line))
    if stack:
        raise FrontPageError(f"line {stack[-1][2]}: '{stack[-1][0]}' block is never closed")
    return kept, specific, shared


def _substitute(line_no, line, target):
    def repl(m):
        key = m.group(1)
        if key not in target.variables:
            raise FrontPageError(f"line {line_no}: unknown variable '{{{{{key}}}}}'")
        return target.variables[key]
    return _VAR.sub(repl, line)


def _lint(kept):
    for line_no, line in kept:
        if _LATEX.search(line):
            raise FrontPageError(f"line {line_no}: '$...$' does not render on any storefront")
        rel = _RELATIVE_LINK.search(line)
        if rel:
            raise FrontPageError(f"line {line_no}: relative link '{rel.group(1)}' breaks on storefronts; "
                                 "use an absolute URL")


def _tidy(lines):
    """Collapses blank runs and trims the ends (removed blocks leave gaps)."""
    out = []
    for line in lines:
        line = line.rstrip()
        if not line and (not out or not out[-1]):
            continue
        out.append(line)
    while out and not out[-1]:
        out.pop()
    return out


# --------------------------------------------------------------------------- bbcode


_LIST_ITEM = re.compile(r"^( *)[-*] (.*)$")
_HEADING = re.compile(r"^(#{1,6}) +(.*?)\s*#*\s*$")
_SUMMARY = re.compile(r"^<summary>\s*(?:<b>)?(.*?)(?:</b>)?\s*</summary>$")
_TABLE_SEP = re.compile(r"^\|?[\s:|-]+\|?$")
_LEFTOVER = re.compile(r"\*\*|\]\(|</?[a-zA-Z][^>]*>|`")


def _plain(text):
    return re.sub(r"[*`]", "", re.sub(r"</?b>", "", text)).replace('"', "'")


def inline_bbcode(text, line_no=0):
    codes = []

    def stash(m):
        codes.append(m.group(1))
        return f"\x00{len(codes) - 1}\x00"

    s = re.sub(r"`([^`]+)`", stash, text)
    s = re.sub(r"\[!\[[^\]]*\]\(([^)\s]+)\)\]\(([^)\s]+)\)",
               lambda m: f"[URL='{m.group(2)}'][IMG]{m.group(1)}[/IMG][/URL]", s)
    s = re.sub(r"!\[[^\]]*\]\(([^)\s]+)\)", lambda m: f"[IMG]{m.group(1)}[/IMG]", s)

    def link(m):
        label, url = m.group(1), m.group(2)
        return label if url.startswith("#") else f"[URL='{url}']{label}[/URL]"

    s = re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", link, s)
    s = re.sub(r"\*\*(.+?)\*\*", r"[B]\1[/B]", s)
    s = re.sub(r"<b>(.*?)</b>", r"[B]\1[/B]", s)
    s = re.sub(r"(?<![\w*])\*(?![\s*])(.+?)(?<![\s*])\*(?![\w*])", r"[I]\1[/I]", s)
    left = _LEFTOVER.search(s)
    if left:
        raise FrontPageError(f"line {line_no}: cannot convert '{left.group(0)}' to BBCode in: {text.strip()}")
    return re.sub(r"\x00(\d+)\x00", lambda m: f"[I]{codes[int(m.group(1))]}[/I]", s)


def _cells(row):
    body = row.strip()
    if body.startswith("|"):
        body = body[1:]
    if body.endswith("|") and not body.endswith("\\|"):
        body = body[:-1]
    return [c.strip().replace("\\|", "|") for c in re.split(r"(?<!\\)\|", body)]


def to_bbcode(kept):
    out = []
    list_depth = 0
    expect_summary = None
    i = 0
    while i < len(kept):
        line_no, line = kept[i]
        stripped = line.strip()

        if expect_summary is not None and stripped:
            m = _SUMMARY.match(stripped)
            if not m:
                raise FrontPageError(f"line {expect_summary}: <details> must be followed by <summary>")
            out.append(f'[SPOILER="{_plain(m.group(1))}"]')
            expect_summary = None
            i += 1
            continue

        item = _LIST_ITEM.match(line)
        if item:
            depth = len(item.group(1)) // 2 + 1
            if depth > list_depth + 1:
                raise FrontPageError(f"line {line_no}: list item indented past its parent")
            while list_depth < depth:
                out.append("[LIST]")
                list_depth += 1
            while list_depth > depth:
                out.append("[/LIST]")
                list_depth -= 1
            out.append("[*]" + inline_bbcode(item.group(2), line_no))
            i += 1
            continue
        while list_depth:
            out.append("[/LIST]")
            list_depth -= 1

        if stripped.startswith("|"):
            rows = []
            while i < len(kept) and kept[i][1].strip().startswith("|"):
                rows.append(kept[i])
                i += 1
            if len(rows) < 2 or not _TABLE_SEP.match(rows[1][1].strip()):
                raise FrontPageError(f"line {rows[0][0]}: table needs a header and a separator row")
            out.append("[TABLE]")
            for n, (row_no, row) in enumerate(rows):
                if n == 1:
                    continue
                tag = "TH" if n == 0 else "TD"
                cells = "".join(f"[{tag}]{inline_bbcode(c, row_no)}[/{tag}]" for c in _cells(row))
                out.append(f"[TR]{cells}[/TR]")
            out.append("[/TABLE]")
            continue

        heading = _HEADING.match(stripped)
        if heading:
            level, text = len(heading.group(1)), inline_bbcode(heading.group(2), line_no)
            if level == 1:
                out.append(f"[SIZE=7][B][COLOR={HEADING_COLOR}]{text}[/COLOR][/B][/SIZE]")
            elif level == 2:
                out.append(f"[SIZE=6][B][COLOR={HEADING_COLOR}]{text}[/COLOR][/B][/SIZE]")
            else:
                out.append(f"[SIZE=4][B]{text}[/B][/SIZE]")
        elif stripped == '<div align="center">':
            out.append("[CENTER]")
        elif stripped == "</div>":
            out.append("[/CENTER]")
        elif stripped == "<details>":
            expect_summary = line_no
        elif stripped == "</details>":
            out.append("[/SPOILER]")
        elif stripped == "---":
            out.append("")
        elif stripped.startswith("```") or stripped.startswith(">"):
            raise FrontPageError(f"line {line_no}: code fences and block quotes are not in the BBCode subset")
        elif stripped.startswith("<"):
            raise FrontPageError(f"line {line_no}: unsupported HTML for BBCode: {stripped}")
        else:
            out.append(inline_bbcode(line, line_no) if stripped else "")
        i += 1

    while list_depth:
        out.append("[/LIST]")
        list_depth -= 1
    if expect_summary is not None:
        raise FrontPageError(f"line {expect_summary}: <details> has no <summary>")

    # No blank line just inside a container: BuiltByBit renders it as an empty row.
    tidy = []
    for line in _tidy(out):
        if not line and tidy and (tidy[-1] in ("[CENTER]", "[LIST]") or tidy[-1].startswith("[SPOILER=")):
            continue
        if line in ("[/CENTER]", "[/SPOILER]", "[/LIST]") and tidy and not tidy[-1]:
            tidy.pop()
        tidy.append(line)
    return tidy


# --------------------------------------------------------------------------- build


def check_denylist(target, text):
    problems = []
    for n, line in enumerate(text.split("\n"), start=1):
        for pattern, label in DENYLIST:
            if pattern.search(line):
                problems.append(f"{target.filename}:{n}: {label} is not allowed on {target.name}: {line.strip()[:120]}")
    return problems


def render(source_text, target):
    """Returns (output text, audience-specific line count, shared line count)."""
    kept, specific, shared = select_lines(source_text, target.name)
    kept = [(n, _substitute(n, line, target)) for n, line in kept]
    _lint(kept)
    if target.fmt == "bbcode":
        lines = to_bbcode(kept)
        text = "\n".join(lines) + "\n"
    else:
        header = (f"<!-- Generated from {SOURCE_REL} by scripts/release/build_front_pages.py "
                  f"(target: {target.name}). Edit the source and rerun; do not edit this file. -->")
        text = header + "\n\n" + "\n".join(_tidy(line for _, line in kept)) + "\n"
    if target.denylist:
        problems = check_denylist(target, text)
        if problems:
            raise FrontPageError("\n".join(problems))
    return text, specific, shared


def build_all(source_text):
    outputs = {}
    stats = (0, 0)
    for name, target in TARGETS.items():
        text, specific, shared = render(source_text, target)
        outputs[name] = text
        stats = (specific, shared)
    return outputs, stats


def main(argv=None):
    parser = argparse.ArgumentParser(description=f"Build storefront pages from {SOURCE_REL}")
    parser.add_argument("--check", action="store_true",
                        help="build and validate every target without writing (CI)")
    parser.add_argument("--target", choices=sorted(TARGETS), help="build one target only")
    parser.add_argument("--stdout", action="store_true", help="print instead of writing (needs --target)")
    parser.add_argument("--source", type=pathlib.Path, default=SOURCE)
    parser.add_argument("--out-dir", type=pathlib.Path, default=OUT_DIR)
    args = parser.parse_args(argv)

    if args.stdout and not args.target:
        parser.error("--stdout needs --target")
    try:
        source_text = args.source.read_text(encoding="utf-8")
        outputs, (specific, shared) = build_all(source_text)
    except (FrontPageError, OSError) as e:
        print(f"::error::front pages: {e}", file=sys.stderr)
        return 1

    names = [args.target] if args.target else list(TARGETS)
    if args.stdout:
        sys.stdout.write(outputs[args.target])
        return 0

    if args.check:
        # Outputs are gitignored, so there is nothing to compare against: build_all
        # already ran every marker, lint and denylist check, and any failure returned 1.
        print(f"front pages valid ({len(names)} targets)")
    else:
        args.out_dir.mkdir(parents=True, exist_ok=True)
        for name in names:
            path = args.out_dir / TARGETS[name].filename
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(outputs[name])
            print(f"wrote {path.relative_to(ROOT) if path.is_relative_to(ROOT) else path}")

    total = specific + shared
    share = specific / total if total else 0.0
    print(f"audience-specific source lines: {specific} of {total} ({share:.0%})")
    if share > DRIFT_WARN:
        print(f"::warning::audience-specific copy is above {DRIFT_WARN:.0%}; "
              "the pages are drifting back toward separate copies")
    return 0


if __name__ == "__main__":
    sys.exit(main())
