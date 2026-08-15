"""MkDocs build hook: expand the single-source version stamp on the website.

Docs carry the literal token `@version@` (see the version-stamp comment in
`gradle.properties`). For the released jar that token is expanded at package
time by the rtp-plugin `copyDocs` task (Gradle `ReplaceTokens`). The MkDocs
site, however, publishes the raw Markdown, so without this hook every page's
header would show the literal `@version@` instead of the real version.

This hook reads the authoritative `version` from `gradle.properties` (the same
single source Gradle uses) and substitutes it into each page at build time, so
the website stays in sync with the jar and neither has to be hand-edited on a
version bump.
"""

from pathlib import Path

_TOKEN = "@version@"
_FALLBACK = "unknown"


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


def on_page_markdown(markdown, page, config, files):
    """Replace the `@version@` token with the real version in every page."""
    if _TOKEN in markdown:
        return markdown.replace(_TOKEN, _VERSION)
    return markdown
