#!/usr/bin/env python3
"""
Builds every storefront page from one tagged source, docs/publishing/FRONT_PAGE.md.

Targets (outputs go to scripts/release/generated/, which is gitignored: build them
locally before pasting into BuiltByBit; release.yml builds them before pushing):
    bbb-pro    BuiltByBit paid-support listing   FRONT_PAGE_PRO.bbcode   (pasted by hand)
    bbb-lite   BuiltByBit free listing           FRONT_PAGE_LITE.bbcode  (pasted by hand)
    spigot     SpigotMC free listing             FRONT_PAGE_SPIGOT.bbcode (pasted by hand)
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
steps ("1. ...") pass through as plain text lines. Local image references
(![alt](../assets/img/...)) are validated against disk and rewritten to absolute
raw GitHub URLs during rendering for all storefront targets. Relative links
(`../admin/COMMANDS.md#anchor`) are validated against disk and rewritten: published
docs become https://dailystruggle.github.io/RTP/... pages (same mapping as mkdocs
directory URLs), anything else becomes a GitHub blob/tree URL.

Usage:
    python scripts/release/build_front_pages.py                  # write all outputs
    python scripts/release/build_front_pages.py --check          # validate every target, write nothing
    python scripts/release/build_front_pages.py --target hangar --stdout
"""

import argparse
import functools
import pathlib
import re
import sys
import urllib.request
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
    # SpigotMC (XenForo 1) ignores `[IMG width=...]` and does not render SVG:
    # badges become plain [IMG] tags pointing at PNG renders.
    raster_badges: bool = False


_LITE_VARS = {"name": "LeafRTP", "jar": "LeafRTP-x.y.z.jar"}
TARGETS = {
    "bbb-pro": Target("bbb-pro", "bbcode", "FRONT_PAGE_PRO.bbcode",
                      {"name": "LeafRTP-Pro", "jar": "LeafRTP-Pro-x.y.z.jar"}),
    "bbb-lite": Target("bbb-lite", "bbcode", "FRONT_PAGE_LITE.bbcode", _LITE_VARS),
    "spigot": Target("spigot", "bbcode", "FRONT_PAGE_SPIGOT.bbcode", _LITE_VARS, denylist=True,
                     raster_badges=True),
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

# Zone 1 (title down to the first `## ` heading after `## What it is`) is read by
# every visitor, most of whom don't know these words. Each term is defined in
# Zone 3 (Performance / How it works); a Zone 1 hit fails the build so the
# plain-language top survives later passes. Link targets are not checked.
ZONE1_START = "## What it is"
ZONE1_JARGON = [
    (re.compile(r"TP/s"), "'TP/s' (say 'teleports per second')"),
    (re.compile(r"\bMSPT\b"), "'MSPT'"),
    (re.compile(r"\bTPS\b"), "'TPS' (say 'full speed')"),
    (re.compile(r"(?i)tick thread|off[- ]tick|main thread|server thread|single thread"), "thread jargon"),
    (re.compile(r"(?i)\bticks?\b"), "'tick' (say 'lag spike' or 'full speed')"),
    (re.compile(r"(?i)\bp9[59]\b"), "percentile jargon"),
    (re.compile(r"\b(ACCUMULATE|REROLL)\b"), "spacing mode names"),
    (re.compile(r"(?i)\bshuffle\b"), "'shuffle'"),
    (re.compile(r"(?i)pre-?filter"), "'pre-filter'"),
    (re.compile(r"(?i)\bcandidates?\b"), "'candidate' (say 'spot')"),
    (re.compile(r"\b(SPI|TTL)\b"), "engineering acronym"),
    (re.compile(r"(?i)\b(closed|open)-loop\b"), "harness jargon"),
    (re.compile(r"(?i)space-filling|\bspiral\b|\bhilbert\b|\bcurves?\b|\bL[123]\b|\bradix\b"),
     "internal curve/tier jargon"),
]
# Lines the author has removed and rejected; they keep reappearing in rewrites.
ZONE1_REJECTED = [
    (re.compile(r"(?i)took that personally|that's laggy"), "rejected subtitle"),
    (re.compile(r"(?i)\d[\d,]*k?\s+downloads"), "download count (kept in the rename FAQ only)"),
    (re.compile(r"(?i)(works|runs) (with no|without( any)?) setup|no setup (needed|required)"),
     "'works with no setup'"),
]
_LINK_TARGET = re.compile(r"\]\([^)]*\)")
_TAGLINE_LINE = re.compile(r'tagline:\s*"([^"]+)"')
# Short-version bullets are `[**Adjective**](link): fact.`; a label of one or two
# words, never a sentence.
SHORT_VERSION_START = "### The short version"
_SHORT_BULLET = re.compile(r"^- \[\*\*[A-Z][\w-]*( [\w-]+)?\*\*\]\([^)]+\): \S")

# Zone 2 (`## Common setups` through `## Features`, stopping at `## Performance`)
# is the operator on-ramp: keep internal math, dotted CLI config paths, and
# stress-harness commands out of it.
ZONE2_SECTIONS = {"## Common setups", "## Install", "## Features"}
ZONE2_STOP = "## Performance"
ZONE2_JARGON = [
    (re.compile(r"(?i)space-filling|\bspiral\b|\bhilbert\b|\bcurves?\b"),
     "curve/spiral math jargon in Zone 2 operator on-ramp"),
    (re.compile(r"\bshape\.radius="), "dotted CLI config path in Zone 2 (keep in FAQ/Reference)"),
    (re.compile(r"/rtp\s+test\b"), "stress-test command in Zone 2"),
]

# Any section that mentions `space-filling` or `spiral` must also state in the
# same section that the math is internal/automatic and users only configure
# standard shapes (CIRCLE/SQUARE) or a block radius, so search engines and LLMs
# never infer that operators have to configure space-filling curves.
_CURVE_TERM = re.compile(r"(?i)space-filling|\bspiral\b")
_CURVE_ATTRIBUTION = re.compile(
    r"(?i)(never (a |configure )[^\n]*curve|under the hood|internal space-filling|runs automatically)"
)

# Section 6 (reference block after `## Why I made it` / `## What paying gets you`)
# keeps compact summary tables only; full catalogs live in docs/admin/.
REFERENCE_START_HEADINGS = {"## Why I made it", "## What paying gets you"}
MAX_REFERENCE_TABLE_ROWS = 10

# Share of source lines that are audience-specific before the report warns that
# the pages are drifting back toward separate copies.
DRIFT_WARN = 0.15

HEADING_COLOR = "#27AE60"
RAW_BASE = "https://raw.githubusercontent.com/dailystruggle/RTP/V3"
SITE_BASE = "https://dailystruggle.github.io/RTP"  # mkdocs.yml site_url
REPO_BASE = "https://github.com/DailyStruggle/RTP"
REPO_BRANCH = "V3"
DOCS_DIR = ROOT / "docs"
# Mirrors mkdocs.yml exclude_docs: these docs have no page on the website.
SITE_EXCLUDED = ("site/README.md", "MAP.md", "publishing/")

_OPEN = re.compile(r"^\s*<!--\s*(kind|only|not)\s*:\s*(.*?)\s*-->\s*$")
_CLOSE = re.compile(r"^\s*<!--\s*/(kind|only|not)\s*-->\s*$")
_VAR = re.compile(r"\{\{\s*(\w+)\s*\}\}")
_LATEX = re.compile(r"\$[^$\s][^$]*\$")
_RELATIVE_LINK = re.compile(r"\]\((?!https?://|#|mailto:)([^)]*)\)")
_IMAGE = re.compile(r"!\[(?P<alt>[^\]]*)\]\((?P<src>[^)\s]+)\)")


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


def _resolve_images(line_no, line, source_dir=SOURCE.parent, raw_base=RAW_BASE):
    """Rewrites local image paths to absolute raw-GitHub URLs, validating existence on disk."""
    def repl(m):
        alt = m.group("alt")
        src = m.group("src")
        if src.startswith("http://") or src.startswith("https://") or src.startswith("//"):
            return m.group(0)
        has_angles = src.startswith("<") and src.endswith(">")
        raw_path = src[1:-1] if has_angles else src
        clean_src = raw_path.split("?")[0].split("#")[0]
        suffix = raw_path[len(clean_src):]

        candidate = (source_dir / clean_src).resolve()
        if not candidate.is_file():
            candidate = (ROOT / clean_src.lstrip("/\\")).resolve()
        if not candidate.is_file():
            raise FrontPageError(f"line {line_no}: local image '{src}' does not exist on disk")

        try:
            rel_to_root = candidate.relative_to(ROOT).as_posix()
        except ValueError:
            raise FrontPageError(f"line {line_no}: local image '{src}' resolves outside the repository: {candidate}")

        new_url = f"{raw_base.rstrip('/')}/{rel_to_root}{suffix}"
        return f"![{alt}]({new_url})"

    return _IMAGE.sub(repl, line)


def _site_url(docs_rel):
    """Website URL (mkdocs directory URLs) for a docs-relative .md path, or None if unpublished."""
    if any(docs_rel == x or (x.endswith("/") and docs_rel.startswith(x)) for x in SITE_EXCLUDED):
        return None
    parts = docs_rel[:-3].split("/")
    if parts[-1] in ("index", "README"):
        parts.pop()
    return SITE_BASE + "/" + "/".join(parts) + ("/" if parts else "")


def _resolve_doc_links(line_no, line, source_dir=SOURCE.parent):
    """Rewrites relative links to absolute URLs, validating the target exists on disk.

    Published docs/**/*.md become website URLs; every other repo path (code,
    unpublished docs, directories) becomes a GitHub blob/tree URL on the release branch.
    """
    def repl(m):
        target = m.group(1)
        path, hash_, anchor = target.partition("#")
        if not path:
            return m.group(0)
        candidate = (source_dir / path).resolve()
        if not candidate.exists():
            candidate = (ROOT / path.lstrip("/\\")).resolve()
        if not candidate.exists():
            raise FrontPageError(f"line {line_no}: relative link '{target}' does not exist on disk")
        try:
            rel = candidate.relative_to(ROOT).as_posix()
        except ValueError:
            raise FrontPageError(f"line {line_no}: relative link '{target}' resolves outside the repository")
        url = None
        if candidate.is_file() and candidate.suffix == ".md":
            try:
                url = _site_url(candidate.relative_to(DOCS_DIR).as_posix())
            except ValueError:
                pass
        if url is None:
            kind = "tree" if candidate.is_dir() else "blob"
            url = f"{REPO_BASE}/{kind}/{REPO_BRANCH}/{rel}"
        return f"]({url}{hash_}{anchor})"

    return _RELATIVE_LINK.sub(repl, line)


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


_SVG_WIDTH = re.compile(rb"<svg\b[^>]*?\swidth=\"(\d+(?:\.\d+)?)\"")


def _is_svg_badge(url):
    return url.startswith("https://img.shields.io/") or url.split("?", 1)[0].endswith(".svg")


@functools.lru_cache(maxsize=None)
def badge_width(url):
    """Intrinsic px width of an SVG badge, or None if it can't be fetched.

    BuiltByBit stretches an SVG without an explicit width to the full post width,
    so badges need `[IMG width=...]`. A failed fetch degrades to a plain [IMG]
    with a warning rather than failing the build.
    """
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "build_front_pages"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            m = _SVG_WIDTH.search(resp.read(4096))
    except (OSError, ValueError) as e:
        print(f"warning: badge width lookup failed for {url}: {e}", file=sys.stderr)
        return None
    if not m:
        print(f"warning: no width attribute in badge {url}", file=sys.stderr)
        return None
    return round(float(m.group(1)))


_GH_WORKFLOW_BADGE = re.compile(r"https://github\.com/([^/]+)/([^/]+)/actions/workflows/([^/?]+)/badge\.svg$")


def raster_badge_url(url, line_no=0):
    """PNG equivalent of an SVG badge; raster URLs pass through unchanged."""
    if url.startswith("https://img.shields.io/"):
        return "https://raster.shields.io/" + url[len("https://img.shields.io/"):]
    m = _GH_WORKFLOW_BADGE.match(url)
    if m:
        return "https://raster.shields.io/github/actions/workflow/status/{}/{}/{}".format(*m.groups())
    if _is_svg_badge(url):
        raise FrontPageError(f"line {line_no}: no PNG equivalent known for SVG image {url}")
    return url


def _bb_img(url, raster_badges=False, line_no=0):
    if raster_badges:
        return f"[IMG]{raster_badge_url(url, line_no)}[/IMG]"
    width = badge_width(url) if _is_svg_badge(url) else None
    return f'[IMG width="{width}px"]{url}[/IMG]' if width else f"[IMG]{url}[/IMG]"


def inline_bbcode(text, line_no=0, raster_badges=False):
    codes = []

    def stash(m):
        codes.append(m.group(1))
        return f"\x00{len(codes) - 1}\x00"

    s = re.sub(r"`([^`]+)`", stash, text)
    s = re.sub(r"\[!\[[^\]]*\]\(([^)\s]+)\)\]\(([^)\s]+)\)",
               lambda m: f"[URL='{m.group(2)}']{_bb_img(m.group(1), raster_badges, line_no)}[/URL]", s)
    s = re.sub(r"!\[[^\]]*\]\(([^)\s]+)\)", lambda m: _bb_img(m.group(1), raster_badges, line_no), s)

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


def to_bbcode(kept, raster_badges=False):
    inline = functools.partial(inline_bbcode, raster_badges=raster_badges)
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
            out.append("[*]" + inline(item.group(2), line_no))
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
                cells = "".join(f"[{tag}]{inline(c, row_no)}[/{tag}]" for c in _cells(row))
                out.append(f"[TR]{cells}[/TR]")
            out.append("[/TABLE]")
            continue

        heading = _HEADING.match(stripped)
        if heading:
            level, text = len(heading.group(1)), inline(heading.group(2), line_no)
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
            out.append(inline(line, line_no) if stripped else "")
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


def check_zone1_jargon(kept):
    """Returns problems for jargon in Zone 1; empty when the page has no `## What it is`."""
    if not any(line.strip() == ZONE1_START for _, line in kept):
        return []
    problems = []
    seen_start = False
    for n, line in kept:
        stripped = line.strip()
        if stripped == ZONE1_START:
            seen_start = True
        elif seen_start and stripped.startswith("## "):
            break
        text = _LINK_TARGET.sub("]", line)
        for pattern, label in ZONE1_JARGON:
            if pattern.search(text):
                problems.append(f"line {n}: {label} above the fold; move it below or say it plainly: "
                                f"{stripped[:120]}")
        for pattern, label in ZONE1_REJECTED:
            if pattern.search(text):
                problems.append(f"line {n}: {label} was rejected by the author; remove it: {stripped[:120]}")
    return problems


def check_short_version_labels(kept):
    """Returns problems for short-version bullets whose bold label is not a one- or two-word label."""
    problems = []
    inside = False
    for n, line in kept:
        stripped = line.strip()
        if stripped == SHORT_VERSION_START:
            inside = True
        elif inside and (stripped.startswith("<details") or stripped.startswith("#")):
            break
        elif inside and stripped.startswith("- ") and not _SHORT_BULLET.match(stripped):
            problems.append(f"line {n}: short-version bullet must be '[**Adjective**](link): fact.': "
                            f"{stripped[:120]}")
    return problems


def check_listing_metadata(source_text):
    """Checks `tagline: "..."` entries in the author-notes header against ZONE1_JARGON."""
    problems = []
    for n, line in enumerate(source_text.split("\n"), start=1):
        if line.strip() == "-->":
            break
        for m in _TAGLINE_LINE.finditer(line):
            tagline = m.group(1)
            for pattern, label in ZONE1_JARGON:
                if pattern.search(tagline):
                    problems.append(f"line {n}: {label} in listing metadata tagline; "
                                    f"use plain head-query copy: {tagline[:120]}")
    return problems


def check_zone2_onramp(kept):
    """Returns problems for curve/math jargon or CLI config paths in the Zone 2 operator on-ramp."""
    problems = []
    in_zone2 = False
    for n, line in kept:
        stripped = line.strip()
        if stripped in ZONE2_SECTIONS:
            in_zone2 = True
            continue
        if stripped.startswith("## ") and stripped not in ZONE2_SECTIONS:
            if stripped == ZONE2_STOP or in_zone2:
                in_zone2 = False
        if not in_zone2:
            continue
        text = _LINK_TARGET.sub("]", line)
        for pattern, label in ZONE2_JARGON:
            if pattern.search(text):
                problems.append(f"line {n}: {label}: {stripped[:120]}")
    return problems


def check_curve_attribution(kept):
    """Ensures any section mentioning `space-filling` or `spiral` states that the math is internal."""
    problems = []
    sections = []
    current_heading = "top"
    current_lines = []
    for n, line in kept:
        stripped = line.strip()
        if stripped.startswith("## "):
            if current_lines:
                sections.append((current_heading, current_lines))
            current_heading = stripped
            current_lines = []
        else:
            current_lines.append((n, line))
    if current_lines:
        sections.append((current_heading, current_lines))

    for heading, lines in sections:
        cleaned = [(n, _LINK_TARGET.sub("]", line)) for n, line in lines]
        hits = [(n, text) for n, text in cleaned if _CURVE_TERM.search(text)]
        if not hits:
            continue
        section_text = "\n".join(text for _, text in cleaned)
        if not _CURVE_ATTRIBUTION.search(section_text):
            first_line, snippet = hits[0]
            problems.append(
                f"line {first_line}: '{heading}' mentions space-filling/spiral math without stating "
                f"that it is internal and admins only configure standard shapes/radii: {snippet.strip()[:120]}"
            )
    return problems


def check_reference_table_caps(kept):
    """Caps markdown pipe tables in the post-story reference block to MAX_REFERENCE_TABLE_ROWS rows."""
    problems = []
    in_reference = False
    i = 0
    while i < len(kept):
        line_no, line = kept[i]
        stripped = line.strip()
        if stripped in REFERENCE_START_HEADINGS:
            in_reference = True
        elif stripped.startswith("## ") and stripped not in REFERENCE_START_HEADINGS:
            in_reference = False
        if in_reference and stripped.startswith("|"):
            rows = []
            while i < len(kept) and kept[i][1].strip().startswith("|"):
                rows.append(kept[i])
                i += 1
            data_rows = max(0, len(rows) - 2)
            if data_rows > MAX_REFERENCE_TABLE_ROWS:
                problems.append(
                    f"line {rows[0][0]}: reference table has {data_rows} rows "
                    f"(max {MAX_REFERENCE_TABLE_ROWS}); link to docs/admin/ for the full catalog"
                )
            continue
        i += 1
    return problems


def render(source_text, target, source_path=SOURCE, raw_base=RAW_BASE):
    """Returns (output text, audience-specific line count, shared line count)."""
    source_dir = source_path.parent if isinstance(source_path, pathlib.Path) else pathlib.Path(source_path).parent
    kept, specific, shared = select_lines(source_text, target.name)
    kept = [(n, _substitute(n, line, target)) for n, line in kept]
    kept = [(n, _resolve_images(n, line, source_dir=source_dir, raw_base=raw_base)) for n, line in kept]
    kept = [(n, _resolve_doc_links(n, line, source_dir=source_dir)) for n, line in kept]
    _lint(kept)
    jargon = (
        check_listing_metadata(source_text)
        + check_zone1_jargon(kept)
        + check_short_version_labels(kept)
        + check_zone2_onramp(kept)
        + check_curve_attribution(kept)
        + check_reference_table_caps(kept)
    )
    if jargon:
        raise FrontPageError("\n".join(jargon))
    if target.fmt == "bbcode":
        lines = to_bbcode(kept, raster_badges=target.raster_badges)
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


def build_all(source_text, source_path=SOURCE, raw_base=RAW_BASE):
    outputs = {}
    stats = (0, 0)
    for name, target in TARGETS.items():
        text, specific, shared = render(source_text, target, source_path=source_path, raw_base=raw_base)
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
    parser.add_argument("--raw-base", default=RAW_BASE,
                        help="base URL for rewritten local image links (default: %(default)s)")
    args = parser.parse_args(argv)

    if args.stdout and not args.target:
        parser.error("--stdout needs --target")
    try:
        source_text = args.source.read_text(encoding="utf-8")
        outputs, (specific, shared) = build_all(source_text, source_path=args.source, raw_base=args.raw_base)
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
