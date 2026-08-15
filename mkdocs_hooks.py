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

# Repo location for rewriting links that escape docs_dir (see
# _rewrite_external_links). The published site only serves pages under docs/,
# so any relative link that resolves to a repo file outside docs/ (source code,
# CHANGELOG, sibling-module docs/ADRs, etc.) has no page on the site and 404s.
# Those links do resolve when the Markdown is read on GitHub, so rewrite them to
# the equivalent GitHub URL instead of leaving a dead link.
_REPO_URL_BASE = "https://github.com/DailyStruggle/RTP"
_REPO_BRANCH = "V3"
_MD_LINK_RE = re.compile(r"\]\(\s*([^)\s]+?)((?:\s+\"[^\"]*\")?)\s*\)")
_SKIP_LINK_PREFIXES = (
    "http://",
    "https://",
    "mailto:",
    "tel:",
    "ftp://",
    "//",
)

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


def _rewrite_external_links(markdown: str, page, config) -> str:
    """Rewrite relative links that escape docs_dir to absolute GitHub URLs.

    Pages under docs/ (notably the internal dev/adr/architecture trees) link to
    repo files outside docs/ with repo-relative paths like
    `../../platforms/rtp-fabric/docs/adr/...` or `../../CHANGELOG.md`. Those
    resolve fine when the Markdown is browsed on GitHub, but the published site
    has no page for them, so they render as dead links (404). Map any link whose
    resolved target lands outside docs_dir - but still inside the repo - to the
    matching `blob`/`tree` URL on GitHub. Links that stay inside docs_dir, are
    already absolute, or point outside the repo are left untouched.
    """
    docs_dir = Path(config["docs_dir"]).resolve()
    repo_root = docs_dir.parent
    page_dir = (docs_dir / page.file.src_uri).parent

    def repl(match: "re.Match") -> str:
        target = match.group(1)
        title = match.group(2) or ""
        lowered = target.lower()
        if (
            lowered.startswith(_SKIP_LINK_PREFIXES)
            or target.startswith("#")
            or target.startswith("/")
        ):
            return match.group(0)

        path_part, sep, frag = target.partition("#")
        frag = f"#{frag}" if sep else ""
        if not path_part:
            return match.group(0)

        resolved = (page_dir / path_part).resolve()
        try:
            resolved.relative_to(docs_dir)
            return match.group(0)  # stays inside the published site
        except ValueError:
            pass
        try:
            rel = resolved.relative_to(repo_root).as_posix()
        except ValueError:
            return match.group(0)  # outside the repo entirely; leave as-is

        kind = "tree" if path_part.endswith("/") else "blob"
        url = f"{_REPO_URL_BASE}/{kind}/{_REPO_BRANCH}/{rel}"
        return f"]({url}{frag}{title})"

    return _MD_LINK_RE.sub(repl, markdown)


def on_page_markdown(markdown, page, config, files):
    """Drop the scattered per-page version stamp; expand any stray token."""
    markdown = _STAMP_RE.sub("", markdown)
    if _TOKEN in markdown:
        markdown = markdown.replace(_TOKEN, _VERSION)
    markdown = _AGENTS_LINK_RE.sub(
        lambda m: f"]({_AGENTS_URL}{m.group(1) or ''})", markdown
    )
    markdown = _rewrite_external_links(markdown, page, config)
    return markdown
