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


def on_config(config):
    """Surface the version once, site-wide, in the footer."""
    existing = config.get("copyright") or ""
    stamp = f"LeafRTP v{_VERSION}"
    config["copyright"] = f"{stamp} &middot; {existing}" if existing else stamp
    return config


def on_page_markdown(markdown, page, config, files):
    """Drop the scattered per-page version stamp; expand any stray token."""
    markdown = _STAMP_RE.sub("", markdown)
    if _TOKEN in markdown:
        markdown = markdown.replace(_TOKEN, _VERSION)
    return markdown
