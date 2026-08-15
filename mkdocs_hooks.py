"""MkDocs build hook: single-source the version stamp on the website.

Docs carry the literal token `@version@` (see the version-stamp comment in
`gradle.properties`). For the released jar that token is expanded at package
time by the rtp-plugin `copyDocs` task (Gradle `ReplaceTokens`). The MkDocs
site, however, publishes the raw Markdown, so without this hook every page's
header would show the literal `@version@` instead of the real version.

Only a subset of pages carry the `**Current Plugin Version:** @version@`
header line, so on the website the version appeared on some pages and not
others, which read inconsistently. Rather than hand-stamp every published
page (and keep them all in sync on a version bump), this hook shows the
version in exactly one place site-wide - the footer - and drops the scattered
per-page stamp lines from the website render. The per-page stamps stay in the
Markdown for the jar-packaged docs, which are expanded by Gradle, not by this
hook.

The authoritative `version` is read from `gradle.properties` (the same single
source Gradle uses), so the website stays in sync with the jar and neither has
to be hand-edited on a version bump.
"""

import re
from pathlib import Path

_TOKEN = "@version@"
_FALLBACK = "unknown"

# Directory holding the project-wide ADRs, relative to this file / docs_dir.
_ADR_DIR = Path(__file__).parent / "docs" / "adr"
_H1_RE = re.compile(r"^#\s+(.+?)\s*$", re.MULTILINE)
_ADR_NUM_RE = re.compile(r"ADR-(\d+)")

# `.junie/AGENTS.md` is the canonical agent guide, but it lives outside the
# published `docs/` tree, so every relative link to it (`../../.junie/AGENTS.md`,
# `../.junie/AGENTS.md`, etc.) resolves outside `docs_dir` and MkDocs both warns
# and renders a dead link. Rewrite those relative links to the canonical GitHub
# URL (preserving any `#fragment`) so the published site links out to the file
# on GitHub instead of 404-ing. The repo-relative source links stay untouched.
_AGENTS_URL = "https://github.com/DailyStruggle/RTP/blob/V3/.junie/AGENTS.md"
_AGENTS_LINK_RE = re.compile(
    r"\]\((?:\.\./)*\.junie/AGENTS\.md(#[^)\s]*)?\)",
)

# Matches the per-page stamp line (e.g. `**Current Plugin Version:** `@version@``)
# plus any single trailing blank line, so removing it leaves no stray gap.
_STAMP_RE = re.compile(
    r"^\*\*Current Plugin Version:\*\*.*$\n?(?:\n)?",
    re.MULTILINE,
)


def _read_version() -> str:
    props = Path(__file__).parent / "gradle.properties"
    try:
        for line in props.read_text(encoding="utf-8").splitlines():
            stripped = line.strip()
            if stripped.startswith("version="):
                return stripped.split("=", 1)[1].strip() or _FALLBACK
    except OSError:
        pass
    return _FALLBACK


_VERSION = _read_version()


def _adr_title(path: Path) -> str:
    """First H1 of an ADR file, trimmed for a compact sidebar label."""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        return path.stem
    m = _H1_RE.search(text)
    title = m.group(1).strip() if m else path.stem
    # ADR bodies open with a long descriptive H1; keep the sidebar readable by
    # dropping any trailing parenthetical and prefixing the ADR number.
    title = title.split(" (", 1)[0].strip()
    num = _ADR_NUM_RE.search(path.name)
    if num and not title.upper().startswith("ADR-"):
        title = f"ADR-{num.group(1)}: {title}"
    return title


def _adr_sort_key(path: Path):
    m = _ADR_NUM_RE.search(path.name)
    # README first, TEMPLATE last, real ADRs by number in between.
    if path.name.lower() == "readme.md":
        return (0, 0, path.name)
    if "TEMPLATE" in path.name.upper():
        return (2, 0, path.name)
    return (1, int(m.group(1)) if m else 1_000_000, path.name)


def _build_adr_nav():
    """An 'ADRs' nav section listing every project-wide ADR page.

    Without this, the ADR pages are published but absent from the nav, so
    Material renders them with no sidebar - a reader has to hit Back to reach
    the next ADR. Adding them to the nav gives every ADR page the shared
    left-hand sidebar so they can be browsed in sequence.
    """
    if not _ADR_DIR.is_dir():
        return None
    files = sorted(_ADR_DIR.glob("*.md"), key=_adr_sort_key)
    items = []
    for f in files:
        rel = f"adr/{f.name}"
        if f.name.lower() == "readme.md":
            items.append({"Overview": rel})
        else:
            items.append({_adr_title(f): rel})
    return {"Architecture decisions": items} if items else None


def on_config(config):
    """Surface the version once, site-wide, in the footer."""
    existing = config.get("copyright") or ""
    stamp = f"LeafRTP v{_VERSION}"
    config["copyright"] = f"{stamp} &middot; {existing}" if existing else stamp

    # Append the generated ADR section to the hand-authored nav so the ADR
    # pages gain the shared sidebar (see _build_adr_nav).
    adr_nav = _build_adr_nav()
    if adr_nav is not None and isinstance(config.get("nav"), list):
        config["nav"].append(adr_nav)

    return config


def on_page_markdown(markdown, page, config, files):
    """Drop the scattered per-page version stamp; expand any stray token."""
    markdown = _STAMP_RE.sub("", markdown)
    if _TOKEN in markdown:
        markdown = markdown.replace(_TOKEN, _VERSION)
    markdown = _AGENTS_LINK_RE.sub(
        lambda m: f"]({_AGENTS_URL}{m.group(1) or ''})", markdown
    )
    return markdown
