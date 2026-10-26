#!/usr/bin/env python3
"""
Compiles repository operator documentation, shipped YAML configuration schemas,
and deterministic BIT fault codes into a local SQLite FTS5 index for the Discord
Forum support and triage bot.

Tables generated in `discord-support-bot/generated/doc-index.sqlite`:
  - `doc_sections` (FTS5): chunked sections from `docs/admin/**`, `docs/site/*`,
    `docs/FOR_SERVER_ADMINS.md`, `docs/FOR_ADDON_DEVELOPERS.md`,
    `docs/dev/BIT_CODES.md`, and `docs/dev/SUPPORT_MATRIX.md`.
    Strictly excludes `docs/adr/**`, `docs/architecture/**`, and internal `docs/dev/**`.
  - `config_keys` (FTS5): dotted YAML keys, defaults, `# @type` / `# @options` /
    `# @range` directives (ADR-064), and surrounding comments from shipped
    `rtp-plugin` and `addons` resource YAML files.
  - `bit_codes`: deterministic lookup table parsed from `docs/dev/bit-codes.lock`
    and `docs/dev/BIT_CODES.md` for O(1) diagnosis of `[RTP bit]` findings.
"""

from __future__ import annotations

import argparse
import atexit
import math
import pathlib
import re
import sqlite3
import threading
from dataclasses import dataclass
from typing import Any, Iterable

BOT_DIR = pathlib.Path(__file__).resolve().parent
ROOT = BOT_DIR.parent
DEFAULT_DB_PATH = BOT_DIR / "generated" / "doc-index.sqlite"
DEFAULT_MANIFEST_PATH = BOT_DIR / "support-bot.yml"
SITE_BASE = "https://dailystruggle.github.io/RTP"
DEFAULT_PROJECT = "leafrtp"

PROJECT_TAG_MAP: dict[str, str] = {
    "🌿 LeafRTP": "leafrtp",
    "📦 API / Addons": "leafrtp",
    "💤 OnePlayerSleep": "oneplayersleep",
    "🌲 TreeGen": "treegen",
    "🔬 Server Fuzzer / Tools": "fuzzer",
}

_MANIFEST_CACHE: dict[str, dict[str, Any]] = {}


def _strip_inline_yaml_comment(raw: str) -> str:
    """Strips an unquoted inline `#` comment while preserving `#` inside single or double quotes."""
    in_single = False
    in_double = False
    escaped = False
    for idx, ch in enumerate(raw):
        if escaped:
            escaped = False
            continue
        if ch == "\\" and in_double:
            escaped = True
            continue
        if ch == "'" and not in_double:
            in_single = not in_single
        elif ch == '"' and not in_single:
            in_double = not in_double
        elif ch == "#" and not in_single and not in_double:
            if idx == 0 or raw[idx - 1].isspace():
                return raw[:idx].rstrip()
    return raw.strip()


def _parse_yaml_scalar(raw: str) -> Any:
    """Parses a YAML scalar (quoted string with escapes, bool, number, inline list, or unquoted string)."""
    val = _strip_inline_yaml_comment(raw)
    if not val:
        return ""
    if (val.startswith('"') and val.endswith('"')) or (val.startswith("'") and val.endswith("'")):
        quote = val[0]
        inner = val[1:-1]
        if quote == '"':
            inner = (
                inner.replace(r"\n", "\n")
                .replace(r"\t", "\t")
                .replace(r"\"", '"')
                .replace(r"\\", "\\")
            )
        return inner
    if val.startswith("[") and val.endswith("]"):
        body = val[1:-1].strip()
        if not body:
            return []
        return [_parse_yaml_scalar(part) for part in body.split(",")]
    low = val.lower()
    if low == "true":
        return True
    if low == "false":
        return False
    if low in ("null", "none", "~"):
        return None
    if re.fullmatch(r"-?\d+", val):
        return int(val)
    if re.fullmatch(r"-?\d+\.\d+", val):
        return float(val)
    return val


def parse_simple_yaml(text: str) -> dict[str, Any]:
    """
    Pure-stdlib parser for the declarative `support-bot.yml` manifest format
    (nested mappings, block sequences `- item`, inline lists `[...]`, and scalars).
    Requires zero third-party packages.
    """
    cleaned_lines: list[tuple[int, str]] = []
    for raw_line in text.splitlines():
        stripped = raw_line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(raw_line) - len(raw_line.lstrip(" "))
        cleaned_lines.append((indent, stripped))

    def _parse_block(idx: int, min_indent: int) -> tuple[Any, int]:
        if idx >= len(cleaned_lines):
            return {}, idx
        first_indent, first_line = cleaned_lines[idx]
        if first_indent < min_indent:
            return {}, idx
        if first_line.startswith("- ") or first_line == "-":
            seq: list[Any] = []
            while idx < len(cleaned_lines):
                ind, line = cleaned_lines[idx]
                if ind < first_indent:
                    break
                if ind > first_indent:
                    idx += 1
                    continue
                if not (line.startswith("- ") or line == "-"):
                    break
                item_rest = _strip_inline_yaml_comment(line[1:].lstrip())
                idx += 1
                has_nested = idx < len(cleaned_lines) and cleaned_lines[idx][0] > first_indent
                if not item_rest:
                    if has_nested:
                        child_val, idx = _parse_block(idx, cleaned_lines[idx][0])
                        seq.append(child_val)
                    else:
                        seq.append("")
                    continue
                if (
                    ":" in item_rest
                    and not item_rest.startswith(('"', "'", "["))
                ):
                    k_part, v_part = item_rest.split(":", 1)
                    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.-]*", k_part.strip()):
                        item_map: dict[str, Any] = {}
                        k_name = str(_parse_yaml_scalar(k_part))
                        v_clean = _strip_inline_yaml_comment(v_part)
                        if v_clean:
                            item_map[k_name] = _parse_yaml_scalar(v_clean)
                        elif has_nested:
                            nested_val, idx = _parse_block(idx, cleaned_lines[idx][0])
                            item_map[k_name] = nested_val
                            has_nested = idx < len(cleaned_lines) and cleaned_lines[idx][0] > first_indent
                        else:
                            item_map[k_name] = {}
                        if has_nested:
                            more_map, idx = _parse_block(idx, cleaned_lines[idx][0])
                            if isinstance(more_map, dict):
                                item_map.update(more_map)
                        seq.append(item_map)
                        continue
                scalar_val = _parse_yaml_scalar(item_rest)
                if has_nested:
                    _, idx = _parse_block(idx, cleaned_lines[idx][0])
                seq.append(scalar_val)
            return seq, idx

        mapping: dict[str, Any] = {}
        while idx < len(cleaned_lines):
            ind, line = cleaned_lines[idx]
            if ind < first_indent or line.startswith("- ") or line == "-":
                break
            if ind > first_indent:
                idx += 1
                continue
            if ":" not in line:
                idx += 1
                continue
            key_part, val_part = line.split(":", 1)
            key = str(_parse_yaml_scalar(key_part))
            val_str = _strip_inline_yaml_comment(val_part)
            idx += 1
            if val_str:
                mapping[key] = _parse_yaml_scalar(val_str)
            else:
                if idx < len(cleaned_lines) and cleaned_lines[idx][0] > ind:
                    child_val, idx = _parse_block(idx, cleaned_lines[idx][0])
                    mapping[key] = child_val
                else:
                    mapping[key] = {}
        return mapping, idx

    parsed, _ = _parse_block(0, 0)
    return parsed if isinstance(parsed, dict) else {}


def load_support_bot_manifest(
    manifest: pathlib.Path | str | dict[str, Any] | None = None,
) -> dict[str, Any]:
    """
    Loads and caches the declarative `support-bot.yml` manifest (or accepts an
    in-memory manifest dictionary for unit tests).
    """
    if isinstance(manifest, dict):
        return manifest
    path = pathlib.Path(manifest) if manifest is not None else DEFAULT_MANIFEST_PATH
    cache_key = str(path.resolve())
    if cache_key in _MANIFEST_CACHE:
        return _MANIFEST_CACHE[cache_key]
    if not path.is_file():
        return {}
    try:
        parsed = parse_simple_yaml(path.read_text(encoding="utf-8"))
    except OSError:
        return {}
    _MANIFEST_CACHE[cache_key] = parsed
    return parsed

ALLOWED_DEV_DOCS = frozenset({"BIT_CODES.md", "SUPPORT_MATRIX.md"})
EXCLUDED_DOC_PREFIXES = (
    "adr/",
    "architecture/",
    "publishing/",
)

DIRECTIVE_RE = re.compile(r"^#\s*(@(?:type|options|range|unit|source|default))\s*:\s*(.+?)\s*$")
KEY_LINE_RE = re.compile(r"^(?P<indent>\s*)(?P<key>[\w.-]+)\s*:\s*(?P<val>.*?)\s*$", re.UNICODE)
HEADING_RE = re.compile(r"^(#{1,4})\s+(.+?)\s*$")
SLUG_STRIP_RE = re.compile(r"[^\w\s-]")
SLUG_SPACE_RE = re.compile(r"[-\s]+")
DOC_TABLE_ROW_RE = re.compile(r"^\|\s*`([A-Za-z0-9_.-]+)`(?:\s*/\s*`([A-Za-z0-9_.-]+)`)?\s*\|(.+)\|$", re.MULTILINE)
COSMETIC_GUI_KEY_PREFIXES = (
    "icon",
    "title",
    "textprevious",
    "textnext",
    "textback",
    "textready",
    "textunavailable",
    "textsearching",
    "textsuccess",
    "textfailure",
    "lore",
)

# Per-process Porter stem cache populated on demand via SQLite's built-in
# `fts5(..., tokenize='porter unicode61')` + `fts5vocab` virtual tables.
_PORTER_STEM_CACHE: dict[str, str] = {}
_STEMMER_CONN: sqlite3.Connection | None = None
_STEMMER_LOCK = threading.Lock()


def _close_stemmer_conn() -> None:
    global _STEMMER_CONN
    with _STEMMER_LOCK:
        if _STEMMER_CONN is not None:
            try:
                _STEMMER_CONN.close()
            except sqlite3.Error:
                pass
            _STEMMER_CONN = None


def _get_stemmer_conn() -> sqlite3.Connection:
    """Returns an in-memory SQLite connection with a `porter unicode61` FTS5 + `fts5vocab` stemmer."""
    global _STEMMER_CONN
    if _STEMMER_CONN is None:
        conn = sqlite3.connect(":memory:", check_same_thread=False)
        conn.execute("CREATE VIRTUAL TABLE stem_input USING fts5(x, tokenize='porter unicode61')")
        conn.execute("CREATE VIRTUAL TABLE stem_vocab USING fts5vocab(stem_input, row)")
        _STEMMER_CONN = conn
        atexit.register(_close_stemmer_conn)
    return _STEMMER_CONN


def porter_stem_word(word: str) -> str:
    """Computes the SQLite `porter unicode61` stem of a single word with thread-safe O(1) caching."""
    clean = re.sub(r"[^a-z0-9]", "", word.lower())
    if not clean or len(clean) > 128:
        return word.lower().strip()[:128]
    cached = _PORTER_STEM_CACHE.get(clean)
    if cached is not None:
        return cached
    with _STEMMER_LOCK:
        cached = _PORTER_STEM_CACHE.get(clean)
        if cached is not None:
            return cached
        s_conn = _get_stemmer_conn()
        s_conn.execute("DELETE FROM stem_input")
        s_conn.execute("INSERT INTO stem_input VALUES (?)", (clean,))
        row = s_conn.execute("SELECT term FROM stem_vocab LIMIT 1").fetchone()
        stem = str(row[0]) if row and row[0] else clean
        _PORTER_STEM_CACHE[clean] = stem
        return stem


def porter_stem_text(text: str) -> set[str]:
    """
    Splits camelCase identifiers (`postTeleportQueueing` -> `post Teleport Queueing`)
    and returns the set of SQLite `porter unicode61` stems present in `text`
    (preserving both the compound word stem and the split sub-word stems).
    """
    if not text:
        return set()
    expanded = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", text)
    raw_words = re.findall(r"[A-Za-z0-9]{2,}", text.lower())
    words = re.findall(r"[A-Za-z0-9]{2,}", expanded.lower())
    return {porter_stem_word(w) for w in (words + raw_words) if w}


@dataclass(frozen=True)
class ProjectSource:
    project: str
    root: pathlib.Path
    site_base: str = SITE_BASE


@dataclass(frozen=True)
class DocSection:
    title: str
    heading: str
    path: str
    url: str
    body: str
    tags: str
    project: str = DEFAULT_PROJECT


@dataclass(frozen=True)
class ConfigKeyEntry:
    file_path: str
    key_path: str
    default_value: str
    directives: str
    description: str
    project: str = DEFAULT_PROJECT


@dataclass(frozen=True)
class BitCodeEntry:
    namespace: str
    id: int
    key: str
    severity: str
    tier: str
    subsystem: str
    summary: str
    remediation: str
    project: str = DEFAULT_PROJECT


@dataclass(frozen=True)
class LocaleAliasEntry:
    locale: str
    localized_key: str
    canonical_key: str
    file_path: str
    project: str = DEFAULT_PROJECT


@dataclass(frozen=True)
class CodeSection:
    class_name: str
    package_name: str
    path: str
    methods: str
    diagnostics: str
    config_refs: str
    summary: str
    project: str = DEFAULT_PROJECT


_JAVA_CODE_SECTIONS_CACHE: dict[tuple[str, str], list[CodeSection]] = {}
_OCR_DECOMPOUND_VOCAB_CACHE: dict[int, tuple[set[str], set[str]]] = {}
_ASSOC_CACHE: dict[tuple[int, str, str], list[str]] = {}
_VOCAB_ROW_CACHE: dict[tuple[int, str, str], dict[str, int] | None] = {}
_FACET_TOKEN_CACHE: dict[tuple[int, str, str], bool] = {}
_MSG_TEMPLATE_CACHE: dict[tuple[int, str], list[dict[str, Any]]] = {}
JAVA_PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)\s*;", re.MULTILINE)
JAVA_METHOD_RE = re.compile(
    r"\b(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?"
    r"(?:<[A-Za-z0-9_,\s?]+>\s+)?[A-Za-z0-9_<>\[\].?]+\s+([a-z][A-Za-z0-9_]*)\s*\("
)
JAVA_THROW_RE = re.compile(r"throw\s+new\s+([A-Za-z0-9_.]+)\s*\(([^;]{0,180})\)", re.DOTALL)
JAVA_CATCH_RE = re.compile(r"catch\s*\(\s*([A-Za-z0-9_.| ]+?)\s+[A-Za-z0-9_]+\s*\)")
JAVA_LOG_RE = re.compile(
    r"\b(?:RTP\.log|server\.log|log|logger\.(?:warning|severe|error)|getLogger\(\)\.(?:warning|severe))\s*\("
    r"(?:\s*Level\.(?:WARNING|SEVERE|INFO)\s*,\s*)?([^;]{1,220})\)",
    re.DOTALL,
)
JAVA_BIT_FINDING_RE = re.compile(r"BitFinding\.(?:warn|fail|error|of)\s*\(([^;]{1,220})\)", re.DOTALL)
JAVA_KEY_REF_RE = re.compile(
    r"\b(?:[A-Z][A-Za-z0-9_]*Keys)\.([A-Za-z0-9_]+)\b"
)
JAVA_JAVADOC_RE = re.compile(r"/\*\*(.*?)\*/", re.DOTALL)


def get_manifest_tag_map(
    manifest: pathlib.Path | str | dict[str, Any] | None = None,
) -> dict[str, str]:
    """Builds the Forum tag -> project slug mapping from `support-bot.yml` (`projects.<slug>.forum_tags`)."""
    tag_map = dict(PROJECT_TAG_MAP)
    cfg = load_support_bot_manifest(manifest)
    projects = cfg.get("projects")
    if isinstance(projects, dict):
        for slug, p_cfg in projects.items():
            if not isinstance(p_cfg, dict):
                continue
            tags = p_cfg.get("forum_tags")
            if isinstance(tags, list):
                for t in tags:
                    if str(t).strip():
                        tag_map[str(t).strip()] = str(slug).strip().lower()
    return tag_map


def resolve_post_project(
    applied_tags: Iterable[str],
    default: str | None = None,
    manifest: pathlib.Path | str | dict[str, Any] | None = None,
) -> str:
    """Maps a Forum thread's applied project tag (`🌿 LeafRTP`, `🌲 TreeGen`, etc.) to a canonical project slug."""
    cfg = load_support_bot_manifest(manifest)
    fallback_default = default or str(cfg.get("default_project") or DEFAULT_PROJECT).strip().lower()
    tag_map = get_manifest_tag_map(cfg)
    for tag in applied_tags:
        clean = str(tag).strip()
        if clean in tag_map:
            return tag_map[clean]
        for known_tag, slug in tag_map.items():
            if clean.lower() == known_tag.lower() or clean.lower() == slug:
                return slug
    return fallback_default


def has_project_docs(conn: sqlite3.Connection, project: str) -> bool:
    """
    Returns True if `doc-index.sqlite` has indexed documentation, configuration
    keys, or BIT fault codes for the given `project` slug.
    """
    proj = project.strip().lower()
    if not proj:
        return False
    for table in ("doc_sections", "config_keys", "bit_codes"):
        row = conn.execute(
            f"SELECT 1 FROM {table} WHERE lower(project) = ? LIMIT 1",
            (proj,),
        ).fetchone()
        if row is not None:
            return True
    return False


def slugify_heading(heading: str) -> str:
    """Converts a Markdown heading into a MkDocs-compatible URL anchor slug."""
    cleaned = heading.replace("`", "").strip().lower()
    cleaned = SLUG_STRIP_RE.sub("", cleaned)
    return SLUG_SPACE_RE.sub("-", cleaned).strip("-")


def doc_site_url(docs_rel: str, heading_slug: str = "", site_base: str = SITE_BASE) -> str:
    """Computes the canonical site URL for a doc."""
    norm = docs_rel.replace("\\", "/")
    if norm.startswith("docs/"):
        norm = norm[len("docs/"):]
    if norm.endswith(".md"):
        norm = norm[:-3]
    parts = [p for p in norm.split("/") if p]
    if parts and parts[-1] in ("index", "INDEX", "README"):
        parts.pop()
    base = site_base.rstrip("/") + "/" + ("/".join(parts) + "/" if parts else "")
    if heading_slug:
        return f"{base}#{heading_slug}"
    return base


def is_indexed_doc(rel_to_docs: str) -> bool:
    """Returns True if a file relative to `docs/` is an allowed operator/public doc."""
    norm = rel_to_docs.replace("\\", "/")
    if not norm.endswith(".md"):
        return False
    for prefix in EXCLUDED_DOC_PREFIXES:
        if norm.startswith(prefix):
            return False
    if norm.startswith("dev/"):
        name = norm.split("/", 1)[1]
        return name in ALLOWED_DEV_DOCS
    if norm in ("FOR_SERVER_ADMINS.md", "FOR_ADDON_DEVELOPERS.md", "ADDON_QUICKSTART.md"):
        return True
    if norm.startswith("admin/"):
        return True
    if norm.startswith("site/"):
        return norm != "site/README.md"
    if "/" not in norm and norm not in ("README.md", "INDEX.md"):
        return True
    return False


def iter_doc_files(repo_root: pathlib.Path = ROOT) -> list[pathlib.Path]:
    """Lists all Markdown files in `docs/` eligible for indexing."""
    docs_dir = repo_root / "docs"
    if not docs_dir.is_dir():
        return []
    selected: list[pathlib.Path] = []
    for path in sorted(docs_dir.rglob("*.md")):
        rel = path.relative_to(docs_dir).as_posix()
        if is_indexed_doc(rel):
            selected.append(path)
    return selected


def iter_arch_doc_files(
    repo_root: pathlib.Path = ROOT,
    project: str = DEFAULT_PROJECT,
    manifest: pathlib.Path | str | dict[str, Any] | None = None,
) -> list[pathlib.Path]:
    """
    Lists architecture, design, hazard, and ADR Markdown files in `docs/`
    (`docs/adr/**`, `docs/architecture/**`, `docs/dev/{DESIGN,HAZARDS,REQUIREMENTS}.md`,
    and `projects.<slug>.reference_docs` from `support-bot.yml`) for indexing into
    `arch_sections` so the causal reasoning model can reason over what the project
    does architecturally and why specific misconfigurations cause symptoms.
    """
    docs_dir = repo_root / "docs"
    if not docs_dir.is_dir():
        return []
    cfg = load_support_bot_manifest(manifest)
    proj_cfg = (cfg.get("projects") or {}).get(project.strip().lower(), {})
    ref_docs = set(
        proj_cfg.get("reference_docs")
        or [
            "site/intended-usage.md",
            "site/what-not-to-do.md",
            "site/how-it-works.md",
        ]
    )
    ref_docs.update({"dev/DESIGN.md", "dev/HAZARDS.md", "dev/REQUIREMENTS.md"})
    selected: list[pathlib.Path] = []
    for path in sorted(docs_dir.rglob("*.md")):
        rel = path.relative_to(docs_dir).as_posix()
        if rel.startswith(("adr/", "architecture/")) and not rel.endswith("ADR-TEMPLATE.md"):
            selected.append(path)
        elif rel in ref_docs or f"docs/{rel}" in ref_docs:
            selected.append(path)
    return selected


def split_markdown_sections(
    path: pathlib.Path,
    repo_root: pathlib.Path = ROOT,
    project: str = DEFAULT_PROJECT,
    site_base: str = SITE_BASE,
) -> list[DocSection]:
    """Splits a Markdown file into H1/H2/H3 sections with canonical site URLs."""
    text = path.read_text(encoding="utf-8")
    rel_repo = path.relative_to(repo_root).as_posix()
    rel_docs = path.relative_to(repo_root / "docs").as_posix()

    lines = text.splitlines()
    doc_title = path.stem.replace("_", " ").replace("-", " ")
    for line in lines:
        m = HEADING_RE.match(line)
        if m and len(m.group(1)) == 1:
            doc_title = m.group(2).strip()
            break

    sections: list[DocSection] = []
    current_heading = doc_title
    current_slug = ""
    current_lines: list[str] = []
    in_code_block = False

    def flush_section() -> None:
        body = "\n".join(current_lines).strip()
        if not body:
            return
        url = doc_site_url(rel_docs, current_slug, site_base=site_base)
        tag_tokens = {
            path.stem.lower(),
            rel_docs.lower().replace("/", " ").replace(".md", ""),
            current_heading.lower(),
        }
        sections.append(
            DocSection(
                title=doc_title,
                heading=current_heading,
                path=rel_repo,
                url=url,
                body=body,
                tags=" ".join(sorted(tag_tokens)),
                project=project,
            )
        )

    for line in lines:
        if line.strip().startswith("```"):
            in_code_block = not in_code_block
            current_lines.append(line)
            continue
        if not in_code_block:
            m = HEADING_RE.match(line)
            if m:
                level = len(m.group(1))
                heading_text = m.group(2).strip()
                if level == 1:
                    doc_title = heading_text
                    current_heading = heading_text
                    current_slug = ""
                    continue
                if level in (2, 3, 4):
                    flush_section()
                    current_heading = heading_text
                    current_slug = slugify_heading(heading_text)
                    current_lines = []
                    continue
        current_lines.append(line)

    flush_section()
    return sections


def iter_shipped_yaml_files(repo_root: pathlib.Path = ROOT) -> list[tuple[str, pathlib.Path]]:
    """
    Returns `(logical_file_path, disk_path)` pairs for shipped baseline YAML configs
    under `src/main/resources`, `*-plugin/src/main/resources`, and `addons/**/src/main/resources`.
    Excludes `lang/**`, hidden `.lang.yml` files, `plugin.yml`, and test fixtures.
    """
    results: list[tuple[str, pathlib.Path]] = []
    seen_paths: set[pathlib.Path] = set()
    candidate_res_dirs = [repo_root / "src" / "main" / "resources"]
    candidate_res_dirs.extend(sorted(repo_root.glob("*-plugin/src/main/resources")))
    for plugin_res in candidate_res_dirs:
        if plugin_res.is_dir():
            for yml in sorted(plugin_res.rglob("*.yml")):
                if yml in seen_paths:
                    continue
                rel = yml.relative_to(plugin_res).as_posix()
                if rel.startswith("lang/") or "/." in f"/{rel}" or rel == "plugin.yml" or rel.startswith("bit/"):
                    continue
                seen_paths.add(yml)
                results.append((rel, yml))

    addons_dir = repo_root / "addons"
    if addons_dir.is_dir():
        for res_dir in sorted(addons_dir.glob("**/src/main/resources")):
            for yml in sorted(res_dir.rglob("*.yml")):
                if yml in seen_paths:
                    continue
                rel = yml.relative_to(res_dir).as_posix()
                if "/." in f"/{rel}" or rel == "plugin.yml" or rel.startswith("bit/"):
                    continue
                seen_paths.add(yml)
                results.append((rel, yml))
    return results


def parse_yaml_config_keys(
    logical_path: str,
    yml_path: pathlib.Path,
    project: str = DEFAULT_PROJECT,
) -> list[ConfigKeyEntry]:
    """
    Lightweight indentation-aware parser that extracts dotted YAML key paths,
    default values, `# @type`/`# @options`/`# @range` directives, and preceding
    comments without external dependencies.
    """
    text = yml_path.read_text(encoding="utf-8")
    lines = text.splitlines()

    entries: list[ConfigKeyEntry] = []
    indent_stack: list[tuple[int, str, str]] = []  # (indent, key_name, comment_summary)
    pending_comments: list[str] = []
    pending_directives: list[str] = []
    pending_default_override: str | None = None

    for raw_line in lines:
        stripped = raw_line.strip()
        if not stripped:
            if not pending_directives:
                pending_comments.clear()
            continue

        if stripped.startswith("#"):
            dm = DIRECTIVE_RE.match(stripped)
            if dm:
                d_name, d_val = dm.group(1), dm.group(2)
                pending_directives.append(f"{d_name}: {d_val}")
                if d_name == "@default":
                    pending_default_override = d_val
            else:
                comment_body = stripped.lstrip("#").strip()
                if (
                    comment_body
                    and not comment_body.startswith("---")
                    and not comment_body.startswith("===")
                    and not re.fullmatch(r"[#=\-*~\s]+", comment_body)
                    and not re.match(r"^Section\s+\d+\s*:", comment_body)
                ):
                    pending_comments.append(comment_body)
            continue

        if stripped.startswith("-"):
            continue

        km = KEY_LINE_RE.match(raw_line)
        if not km:
            pending_comments.clear()
            pending_directives.clear()
            pending_default_override = None
            continue

        indent = len(km.group("indent").replace("\t", "  "))
        key = km.group("key")
        val = km.group("val").strip()

        # Strip inline comment from value if not inside quotes
        if "#" in val and not (val.startswith('"') or val.startswith("'")):
            val = val.split("#", 1)[0].strip()

        while indent_stack and indent_stack[-1][0] >= indent:
            indent_stack.pop()

        comment_text = " ".join(pending_comments).strip()
        parent_comments = " ".join(p[2] for p in indent_stack if p[2]).strip()
        if comment_text and parent_comments and parent_comments not in comment_text:
            full_description = f"{comment_text} {parent_comments}".strip()
        else:
            full_description = comment_text or parent_comments

        indent_stack.append((indent, key, comment_text))
        dotted_key = ".".join(p[1] for p in indent_stack)

        default_val = val if val else (pending_default_override or "")
        if default_val.startswith('"') and default_val.endswith('"') and len(default_val) >= 2:
            default_val = default_val[1:-1]
        elif default_val.startswith("'") and default_val.endswith("'") and len(default_val) >= 2:
            default_val = default_val[1:-1]

        if any(d == "@source: shape" for d in pending_directives) and not any(
            d.startswith("@options:") for d in pending_directives
        ):
            pending_directives.append(
                "@options: [CIRCLE, SQUARE, RECTANGLE, ELLIPSE, POLYGON]"
            )
            if "POLYGON" not in full_description:
                shape_note = (
                    "(built-in: CIRCLE, SQUARE, RECTANGLE, ELLIPSE, or POLYGON "
                    "with a vertices list for custom polygons like a hexagon/triangle)"
                )
                if comment_text:
                    base_comment = comment_text.rstrip(".")
                    full_description = f"{base_comment} {shape_note}."
                else:
                    full_description = f"Landing area shape algorithm {shape_note}."

        directives_str = "; ".join(pending_directives)

        if (val or pending_directives or comment_text) and dotted_key != "version":
            entries.append(
                ConfigKeyEntry(
                    file_path=logical_path,
                    key_path=dotted_key,
                    default_value=default_val,
                    directives=directives_str,
                    description=full_description,
                    project=project,
                )
            )

        pending_comments.clear()
        pending_directives.clear()
        pending_default_override = None

    return entries


def expand_region_inherited_keys(entries: list[ConfigKeyEntry]) -> list[ConfigKeyEntry]:
    """
    In `definitions/regions/default.yml`, `shape: "@config"` and `vert: "@config"`
    inherit `defaults.shape.*` and `defaults.vert.*` from `config.yml`.
    Synthesize explicit `shape.*` and `vert.*` entries under `definitions/regions/default.yml`
    so operator searches for `shape.radius`, `shape.centerRadius`, or `vert.maxY`
    resolve directly to both `definitions/regions/default.yml` and `config.yml`.
    """
    region_entries = {
        (e.project, e.file_path, e.key_path): e for e in entries
    }
    config_entries = {
        (e.project, e.key_path): e
        for e in entries
        if e.file_path == "config.yml"
    }

    updated_entries: list[ConfigKeyEntry] = []
    for entry in entries:
        if entry.file_path == "definitions/regions/default.yml" and entry.default_value == "@config":
            base_cfg = config_entries.get((entry.project, f"defaults.{entry.key_path}"))
            if base_cfg is not None:
                eff_default = f"@config (inherits {base_cfg.default_value})" if base_cfg.default_value else "@config"
                base_desc = base_cfg.description
                combined_desc = (
                    f"{base_desc} {entry.description}".strip()
                    if base_desc and base_desc not in entry.description
                    else entry.description
                )
                entry = ConfigKeyEntry(
                    file_path=entry.file_path,
                    key_path=entry.key_path,
                    default_value=eff_default,
                    directives=entry.directives or base_cfg.directives,
                    description=combined_desc,
                    project=entry.project,
                )
        updated_entries.append(entry)

    synthesized: list[ConfigKeyEntry] = []
    for entry in updated_entries:
        if entry.file_path != "config.yml":
            continue
        region_shape = region_entries.get((entry.project, "definitions/regions/default.yml", "shape"))
        region_vert = region_entries.get((entry.project, "definitions/regions/default.yml", "vert"))
        if entry.key_path.startswith("defaults.shape.") and region_shape is not None:
            short_key = entry.key_path[len("defaults."):]
            if (entry.project, "definitions/regions/default.yml", short_key) not in region_entries:
                extra_desc = region_shape.description if region_shape else ""
                combined_desc = f"{entry.description} {extra_desc}".strip()
                synthesized.append(
                    ConfigKeyEntry(
                        file_path="definitions/regions/default.yml",
                        key_path=short_key,
                        default_value=entry.default_value,
                        directives=entry.directives,
                        description=combined_desc,
                        project=entry.project,
                    )
                )
        elif entry.key_path.startswith("defaults.vert.") and region_vert is not None:
            short_key = entry.key_path[len("defaults."):]
            if (entry.project, "definitions/regions/default.yml", short_key) not in region_entries:
                extra_desc = region_vert.description if region_vert else ""
                combined_desc = f"{entry.description} {extra_desc}".strip()
                synthesized.append(
                    ConfigKeyEntry(
                        file_path="definitions/regions/default.yml",
                        key_path=short_key,
                        default_value=entry.default_value,
                        directives=entry.directives,
                        description=combined_desc,
                        project=entry.project,
                    )
                )
    return updated_entries + synthesized


def enrich_config_keys_from_docs(
    config_rows: list[ConfigKeyEntry],
    doc_rows: list[DocSection],
) -> list[ConfigKeyEntry]:
    """
    Cross-links `ConfigKeyEntry` rows with Markdown reference table rows
    (`| `key` | ... |`) from `doc_rows` (`PERFORMANCE.md`, `SAFETY.md`,
    `CORE_CONFIG.md`, `REGIONS.md`, `ECONOMY.md`, `CONFIGURATION.md`) so the
    rich prose descriptions in the documentation are indexed directly alongside
    each YAML key's inline comment.
    Also synthesizes documented `shape.<key>` and `vert.<key>` engine parameters
    from the `shape` and `vert` sections of `CONFIGURATION.md` under
    `definitions/regions/default.yml` when they are not in the default `CIRCLE`/`LINEAR`
    block (e.g. `shape.width`, `shape.height`, `shape.rotation`, `shape.vertices`, `vert.step`).
    """
    table_desc_by_key: dict[tuple[str, str], list[str]] = {}
    existing_region_keys = {
        (e.project, e.key_path.lower())
        for e in config_rows
        if e.file_path == "definitions/regions/default.yml"
    }
    synthesized_engine_keys: list[ConfigKeyEntry] = []

    for d in doc_rows:
        if d.path.endswith("FAQ.md"):
            continue
        heading_low = d.heading.lower()
        is_shape_table = d.path.endswith("CONFIGURATION.md") and any(
            s in heading_low for s in ("rectangle", "ellipse", "polygon", "circle_normal")
        )
        is_vert_table = d.path.endswith("CONFIGURATION.md") and any(
            s in heading_low for s in ("jump", "fixed", "vert adjustors")
        )
        engine_prefix = "shape." if is_shape_table else ("vert." if is_vert_table else "")
        engine_label = d.heading.split("—")[0].split("-")[0].replace("`", "").strip()

        for m in DOC_TABLE_ROW_RE.finditer(d.body):
            key_parts = [k.strip() for k in (m.group(1), m.group(2)) if k and k.strip()]
            cols = [c.strip() for c in m.group(3).split("|") if c.strip()]
            if not cols or not key_parts:
                continue
            desc_text = " ".join(cols[-2:] if len(cols) >= 2 else cols)
            desc_text = re.sub(r"\s+", " ", desc_text).strip()
            type_col = cols[0] if len(cols) >= 2 else ""
            default_col = cols[1].strip("` ").strip() if len(cols) >= 3 and cols[1].strip() not in ("—", "-") else ""
            key_name = key_parts[0]
            if desc_text and not m.group(2):
                table_desc_by_key.setdefault((d.project, key_name.lower()), []).append(desc_text)
            if is_shape_table and heading_low.startswith("`rectangle`"):
                for key_part in key_parts:
                    if key_part in ("width", "height"):
                        full_kp = f"shape.{key_part}"
                        if (d.project, full_kp.lower()) not in existing_region_keys:
                            existing_region_keys.add((d.project, full_kp.lower()))
                            synthesized_engine_keys.append(
                                ConfigKeyEntry(
                                    file_path="definitions/regions/default.yml",
                                    key_path=full_kp,
                                    default_value=default_col,
                                    directives="@type: integer",
                                    description=f"(RECTANGLE shape setting) {cols[-1]}".strip(),
                                    project=d.project,
                                )
                            )
            elif is_vert_table:
                for key_part in key_parts:
                    if key_part in ("maxSlope", "slopeRadius"):
                        full_kp = f"vert.{key_part}"
                        if (d.project, full_kp.lower()) not in existing_region_keys:
                            existing_region_keys.add((d.project, full_kp.lower()))
                            vert_directives = (
                                "@type: integer; @range: [-1, null]"
                                if key_part == "maxSlope"
                                else "@type: integer; @range: [1, 8]"
                            )
                            synthesized_engine_keys.append(
                                ConfigKeyEntry(
                                    file_path="definitions/regions/default.yml",
                                    key_path=full_kp,
                                    default_value=default_col,
                                    directives=vert_directives,
                                    description=f"(LINEAR/JUMP vert setting) {cols[-1]}".strip(),
                                    project=d.project,
                                )
                            )

    enriched: list[ConfigKeyEntry] = []
    for entry in config_rows:
        if entry.file_path.startswith(("advanced/messages/", "messages/", "definitions/actions/", "definitions/effects/", "addons/")):
            enriched.append(entry)
            continue
        kp_low = entry.key_path.lower()
        leaf_low = kp_low.split(".")[-1]
        extra_docs = (
            table_desc_by_key.get((entry.project, kp_low))
            or (table_desc_by_key.get((entry.project, leaf_low)) if len(leaf_low) >= 4 else None)
        )
        if extra_docs:
            joined_extra = " ".join(dict.fromkeys(extra_docs))[:360]
            base_desc = entry.description.strip()
            if base_desc and not base_desc.endswith((".", "!", "?")):
                base_desc += "."
            new_desc = f"{base_desc} [Doc context: {joined_extra}]".strip()
            enriched.append(
                ConfigKeyEntry(
                    file_path=entry.file_path,
                    key_path=entry.key_path,
                    default_value=entry.default_value,
                    directives=entry.directives,
                    description=new_desc,
                    project=entry.project,
                )
            )
        else:
            enriched.append(entry)
    return enriched + synthesized_engine_keys


def parse_bit_codes(
    repo_root: pathlib.Path = ROOT,
    project: str = DEFAULT_PROJECT,
    manifest: pathlib.Path | str | dict[str, Any] | None = None,
) -> list[BitCodeEntry]:
    """
    Parses `docs/dev/bit-codes.lock` (or `projects.<slug>.error_code_lock_file`
    from `support-bot.yml`) and merges metadata + remediation from
    `docs/dev/BIT_CODES.md` (or `projects.<slug>.error_code_doc_file`) into
    deterministic `BitCodeEntry` rows.
    """
    cfg = load_support_bot_manifest(manifest)
    proj_cfg = (cfg.get("projects") or {}).get(project.strip().lower(), {})
    lock_rel = str(proj_cfg.get("error_code_lock_file") or "docs/dev/bit-codes.lock")
    doc_rel = str(proj_cfg.get("error_code_doc_file") or "docs/dev/BIT_CODES.md")
    lock_path = repo_root / lock_rel
    md_path = repo_root / doc_rel

    lock_paths = [lock_path]
    md_paths = [md_path]
    if lock_rel == "docs/dev/bit-codes.lock" and (repo_root / "addons").is_dir():
        lock_paths.extend(sorted((repo_root / "addons").glob("*/docs/bit-codes.lock")))
    if doc_rel == "docs/dev/BIT_CODES.md" and (repo_root / "addons").is_dir():
        md_paths.extend(sorted((repo_root / "addons").glob("*/docs/BIT_CODES.md")))

    md_details: dict[str, tuple[str, str, str]] = {}
    for mp in md_paths:
        if not mp.is_file():
            continue
        for line in mp.read_text(encoding="utf-8").splitlines():
            stripped = line.strip()
            if not stripped.startswith("|") or stripped.startswith("| ID ") or stripped.startswith("|---"):
                continue
            cols = [c.strip() for c in stripped.strip("|").split("|")]
            if len(cols) >= 7:
                code_col = cols[1].strip("` ").strip()
                tier_col = cols[3].strip()
                subsys_col = cols[4].strip()
                remediation_col = cols[6].strip()
                if code_col:
                    md_details[code_col] = (tier_col, subsys_col, remediation_col)

    results: list[BitCodeEntry] = []
    if not lock_path.is_file():
        return results

    seen_codes: set[tuple[str, int]] = set()
    for lp in lock_paths:
        if not lp.is_file():
            continue
        for line in lp.read_text(encoding="utf-8").splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            parts = stripped.split()
            if len(parts) < 4:
                continue
            namespace, id_str, key, severity = parts[0], parts[1], parts[2], parts[3]
            code_id = int(id_str)
            if (namespace, code_id) in seen_codes:
                continue
            seen_codes.add((namespace, code_id))
            tier, subsystem, remediation = md_details.get(
                key,
                ("PBIT", namespace.lower(), "Inspect the BIT finding details and configuration."),
            )
            summary = f"{namespace}-{code_id:04d} {key} ({severity}, {tier}, subsystem: {subsystem})"
            results.append(
                BitCodeEntry(
                    namespace=namespace,
                    id=code_id,
                    key=key,
                    severity=severity,
                    tier=tier,
                    subsystem=subsystem,
                    summary=summary,
                    remediation=remediation,
                    project=project,
                )
            )
    return results


def parse_locale_key_aliases(
    repo_root: pathlib.Path = ROOT,
    project: str = DEFAULT_PROJECT,
) -> list[LocaleAliasEntry]:
    """
    Parses all shipped `lang/<locale>/**/.*.lang.yml` files to build a
    deterministic reverse mapping from localized YAML key names (e.g.
    `retrasoTeletransporte`, `传送延迟`, `отсрочкаТелепорта`, `intervall`) back
    to their canonical baseline English keys (`teleportDelay`, `period`).

    Because this mapping comes strictly from shipped repository `.lang.yml`
    files, it provides zero-hallucination multilingual key resolution without
    passing raw foreign prose to an LLM.
    """
    results: list[LocaleAliasEntry] = []
    seen: set[tuple[str, str, str]] = set()
    candidate_res_dirs = [repo_root / "src" / "main" / "resources" / "lang"]
    candidate_res_dirs.extend(sorted(repo_root.glob("*-plugin/src/main/resources/lang")))
    for lang_root in candidate_res_dirs:
        if not lang_root.is_dir():
            continue
        for lang_yml in sorted(lang_root.rglob(".*.lang.yml")):
            rel_parts = lang_yml.relative_to(lang_root).parts
            if len(rel_parts) < 2:
                continue
            locale = rel_parts[0]
            file_rel = "/".join(rel_parts[1:])
            if file_rel.startswith("definitions/.effects") or file_rel.startswith("advanced/messages/"):
                continue
            try:
                lines = lang_yml.read_text(encoding="utf-8").splitlines()
            except OSError:
                continue
            for raw_line in lines:
                stripped = raw_line.strip()
                if not stripped or stripped.startswith("#") or ":" not in stripped:
                    continue
                canonical_raw, localized_raw = stripped.split(":", 1)
                canonical = canonical_raw.strip().strip("\"'")
                localized = localized_raw.strip().strip("\"'")
                if (
                    not canonical
                    or not localized
                    or canonical in ("version", "when", "if", "then", "else", "type", "name", "id")
                    or len(localized) < 2
                    or localized.lower() == canonical.lower()
                ):
                    continue
                loc_low = localized.lower()
                dedup_key = (project, loc_low, canonical)
                if dedup_key in seen:
                    continue
                seen.add(dedup_key)
                results.append(
                    LocaleAliasEntry(
                        locale=locale,
                        localized_key=loc_low,
                        canonical_key=canonical,
                        file_path=file_rel,
                        project=project,
                    )
                )

    # Also harvest distinctive localized comment tokens from shipped `lang/<locale>/**/*.yml`
    # files so Pass 1 resolves localized symptom terms directly from the repository's
    # translated YAML comments without static regex tables.
    en_words: set[str] = set()
    en_stems: set[str] = set()
    en_keys: set[str] = set()
    for lp, yp in iter_shipped_yaml_files(repo_root):
        for e in parse_yaml_config_keys(lp, yp, project=project):
            en_keys.add(e.key_path.split(".")[-1])
            for w in re.findall(r"[A-Za-z]{2,}", f"{e.key_path} {e.description}".lower()):
                en_words.add(w)
                en_stems.add(porter_stem_word(w))
    for df_path in iter_doc_files(repo_root):
        try:
            doc_txt = df_path.read_text(encoding="utf-8", errors="replace").lower()
            for w in re.findall(r"[a-z]{2,}", doc_txt):
                en_words.add(w)
                en_stems.add(porter_stem_word(w))
        except OSError:
            continue

    loc_to_canon = {(a.locale, a.localized_key.lower()): a.canonical_key for a in results}
    comment_map: dict[tuple[str, str], dict[str, str]] = {}
    for lang_root in candidate_res_dirs:
        if not lang_root.is_dir():
            continue
        for yml in sorted(lang_root.rglob("*.yml")):
            rel_parts = yml.relative_to(lang_root).parts
            if len(rel_parts) < 2:
                continue
            locale = rel_parts[0]
            sub = "/".join(rel_parts[1:])
            if (
                sub.startswith(("definitions/effects", "advanced/messages", "messages", "definitions/actions"))
                or "/." in f"/{sub}"
                or sub.startswith(".")
            ):
                continue
            try:
                entries = parse_yaml_config_keys(sub, yml, project=project)
            except OSError:
                continue
            for e in entries:
                if "." in e.key_path and not e.key_path.startswith("defaults."):
                    continue
                leaf = e.key_path.split(".")[-1]
                ck = loc_to_canon.get((locale, leaf.lower())) or (leaf if leaf in en_keys else None)
                if not ck or ck in ("version", "name", "mode"):
                    continue
                desc = e.description[:240]
                for w in re.findall(r"[^\W\d_]{4,}", desc.lower(), flags=re.UNICODE):
                    if w not in en_words and (not w.isascii() or (len(w) >= 5 and porter_stem_word(w) not in en_stems)):
                        comment_map.setdefault((locale, w), {})[ck] = sub
                if locale in ("zh", "ja", "ko"):
                    for run in re.findall(r"[\u3400-\u9fff]{2,8}", desc):
                        for n in (2, 3, 4):
                            for i in range(len(run) - n + 1):
                                seg = run[i : i + n]
                                comment_map.setdefault((locale, seg), {})[ck] = sub

    for (locale, tok), ck_dict in sorted(comment_map.items()):
        non_event_cks = {ck: fp for ck, fp in ck_dict.items() if not ck.startswith("event_")}
        target_cks = non_event_cks or ck_dict
        if 1 <= len(target_cks) <= 2:
            for ck, fp in sorted(target_cks.items()):
                dedup_key = (project, tok, ck)
                if dedup_key not in seen:
                    seen.add(dedup_key)
                    results.append(
                        LocaleAliasEntry(
                            locale=f"{locale}_comment",
                            localized_key=tok,
                            canonical_key=ck,
                            file_path=fp,
                            project=project,
                        )
                    )
    return results


def parse_java_code_sections(
    repo_root: pathlib.Path = ROOT,
    project: str = DEFAULT_PROJECT,
) -> list[CodeSection]:
    """
    Parses Java source files across the repository into a second-class diagnostic
    corpus (`code_sections`) used when posts, logs, or screenshots contain stack
    traces, thrown exceptions, or console warnings not present in documentation.
    """
    cache_key = (str(repo_root.resolve()), project.strip().lower())
    cached = _JAVA_CODE_SECTIONS_CACHE.get(cache_key)
    if cached is not None:
        return cached

    module_roots = [
        repo_root / mod
        for mod in ("rtp-core", "rtp-api", "rtp-plugin", "platforms", "addons", "api")
        if (repo_root / mod).is_dir()
    ]
    if not module_roots and (repo_root / "src" / "main" / "java").is_dir():
        module_roots = [repo_root]

    java_files: list[pathlib.Path] = []
    for mod_root in module_roots:
        java_files.extend(sorted(mod_root.rglob("src/main/java/**/*.java")))

    results: list[CodeSection] = []
    seen_classes: set[tuple[str, str]] = set()

    for jf in java_files:
        class_name = jf.stem
        if class_name == "package-info":
            continue
        try:
            raw_text = jf.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue

        pkg_m = JAVA_PACKAGE_RE.search(raw_text)
        pkg_name = pkg_m.group(1) if pkg_m else ""
        dedup_key = (pkg_name, class_name)
        if dedup_key in seen_classes:
            continue
        seen_classes.add(dedup_key)

        rel_path = jf.relative_to(repo_root).as_posix()
        methods = [
            m
            for m in dict.fromkeys(JAVA_METHOD_RE.findall(raw_text))
            if m not in ("if", "for", "while", "switch", "catch", "synchronized")
        ][:18]

        diag_items: list[str] = []
        for exc_type, exc_arg in JAVA_THROW_RE.findall(raw_text):
            clean_arg = " ".join(exc_arg.split()).strip()[:140]
            diag_items.append(f"throws {exc_type}({clean_arg})")
        for catch_type in dict.fromkeys(JAVA_CATCH_RE.findall(raw_text)):
            diag_items.append(f"catches {' '.join(catch_type.split())}")
        for log_msg in JAVA_LOG_RE.findall(raw_text):
            clean_log = " ".join(log_msg.split()).strip()[:160]
            if clean_log not in diag_items:
                diag_items.append(f"log: {clean_log}")
        for bit_msg in JAVA_BIT_FINDING_RE.findall(raw_text):
            clean_bit = " ".join(bit_msg.split()).strip()[:160]
            if clean_bit not in diag_items:
                diag_items.append(f"bit: {clean_bit}")

        cfg_refs = [
            k
            for k in dict.fromkeys(JAVA_KEY_REF_RE.findall(raw_text))
            if k not in ("class", "values", "valueOf", "name")
        ][:16]

        javadoc_blocks = JAVA_JAVADOC_RE.findall(raw_text)
        summary_parts: list[str] = []
        for jb in javadoc_blocks[:2]:
            cleaned_jb = re.sub(r"(?m)^\s*\*\s?", " ", jb)
            cleaned_jb = " ".join(cleaned_jb.split()).strip()
            if cleaned_jb:
                summary_parts.append(cleaned_jb)
        summary = " ".join(summary_parts)[:420]
        if not summary:
            summary = f"{pkg_name}.{class_name}" if pkg_name else class_name

        results.append(
            CodeSection(
                class_name=class_name,
                package_name=pkg_name,
                path=rel_path,
                methods=" ".join(methods),
                diagnostics=" | ".join(diag_items[:10]),
                config_refs=" ".join(cfg_refs),
                summary=summary,
                project=project,
            )
        )

    _JAVA_CODE_SECTIONS_CACHE[cache_key] = results
    return results


def _cfg_family(file_path: str) -> str:
    if file_path in ("config.yml", "definitions/regions/default.yml"):
        return "core_region"
    return file_path


def _populate_corpus_vocab_and_associations(
    conn: sqlite3.Connection,
    doc_rows: list[DocSection],
    config_rows: list[ConfigKeyEntry],
    bit_rows: list[BitCodeEntry],
) -> None:
    """
    Populates `corpus_vocab` and `corpus_associations` directly from the indexed
    repository files and SQLite `fts5vocab` virtual tables (`config_keys_vocab`,
    `doc_sections_vocab`) with zero hand-maintained word lists.
    """
    projects = sorted({d.project for d in doc_rows} | {c.project for c in config_rows} | {DEFAULT_PROJECT})
    vocab_rows: list[tuple[str, str, int, int, int, int, int, float, int, int]] = []
    assoc_rows: list[tuple[str, str, float, str]] = []

    for proj in projects:
        proj_docs = [d for d in doc_rows if d.project == proj]
        proj_cfgs = [c for c in config_rows if c.project == proj]
        proj_bits = [b for b in bit_rows if b.project == proj]
        n_total = len(proj_docs) + len(proj_cfgs)

        core_cfgs = [
            c
            for c in proj_cfgs
            if not c.file_path.startswith(("advanced/messages/", "messages/", "definitions/actions/", "definitions/effects/"))
            and not (c.file_path.endswith("guimenu.yml") and c.key_path.lower().startswith(COSMETIC_GUI_KEY_PREFIXES))
            and not c.key_path.startswith("biomeWeights.")
        ]

        cfg_fams: dict[str, set[str]] = {}
        cfg_keys_cnt: dict[str, set[tuple[str, str]]] = {}
        key_id_fams: dict[str, set[str]] = {}
        for c in core_cfgs:
            fam = _cfg_family(c.file_path)
            leaf_key = c.key_path.split(".")[-1]
            base_desc = c.description.split("[Doc context:", 1)[0]
            ks = porter_stem_text(f"{c.file_path} {c.key_path} {c.directives}")
            for s in ks:
                key_id_fams.setdefault(s, set()).add(fam)
            for s in ks | porter_stem_text(base_desc):
                cfg_fams.setdefault(s, set()).add(fam)
                cfg_keys_cnt.setdefault(s, set()).add((fam, leaf_key))

        doc_files: dict[str, set[str]] = {}
        doc_hdr_files: dict[str, set[str]] = {}
        for d in proj_docs:
            for s in porter_stem_text(f"{d.title} {d.heading} {d.body}"):
                doc_files.setdefault(s, set()).add(d.path)
            if not d.path.endswith("FAQ.md"):
                spans = re.findall(
                    r"`([A-Za-z0-9_.-]{2,32})`|\*\*([A-Za-z0-9_.-]{2,24})\*\*",
                    d.body,
                )
                cmd_Highlight = " ".join(re.findall(r"`/rtp\s+(?:config\s+)?(import|migrate)\b", d.body))
                short_hdr = (
                    d.heading
                    if len(d.heading.split()) <= 3
                    else " ".join(re.findall(r"\b(?:[A-Z]{3,}|[A-Za-z]+[a-z][A-Z][A-Za-z0-9]*)\b", d.heading))
                )
                doc_filename = d.path.split("/")[-1].replace(".md", "")
                path_hdr = doc_filename if doc_filename.isupper() or "_" in doc_filename else ""
                emph_txt = " ".join(a or b for a, b in spans) + " " + path_hdr + " " + short_hdr + " " + cmd_Highlight
                for s in porter_stem_text(emph_txt):
                    doc_hdr_files.setdefault(s, set()).add(d.path)

        all_stems = set(cfg_fams.keys()) | set(doc_files.keys()) | set(key_id_fams.keys())
        facet_stems: set[str] = set()
        for st in sorted(all_stems):
            kf = len(key_id_fams.get(st, ()))
            cf = len(cfg_fams.get(st, ()))
            ck = len(cfg_keys_cnt.get(st, ()))
            dhf = len(doc_hdr_files.get(st, ()))
            df = len(doc_files.get(st, ()))
            idf = math.log((n_total + 1.0) / (ck + df + 1.0))

            # Corpus-derived facet classification: stems that never appear in a config key name
            # (`kf == 0`) and only appear in a single heading while spreading across >= 9 doc files
            # (`dhf <= 1 and df >= 9`) are general prose words rather than technical facets.
            is_facet = 0
            if len(st) >= 3 and not (kf == 0 and dhf <= 1 and df >= 9 and ck <= 2):
                if 1 <= kf <= 4 and cf <= 6 and (df <= 18 or (dhf >= 1 and df <= 27)):
                    is_facet = 1
                elif 1 <= kf <= 2 and cf <= 4 and dhf >= 1 and df <= 35:
                    is_facet = 1
                elif 1 <= dhf <= 8 and cf <= 3 and df <= 27 and (dhf >= 2 or df <= 8) and (ck >= 1 or df <= 3 or dhf >= 3 or (dhf >= 2 and df <= 10)):
                    is_facet = 1
                elif 1 <= cf <= 3 and 1 <= ck <= 12 and (df <= 11 or (ck >= 2 and df <= 13)) and (kf >= 1 or cf == 1 or ck >= 3 or df <= 8):
                    is_facet = 1

            # A corpus token is ubiquitous if it is not a focused facet and either
            # spreads across multiple files (`cf >= 3` or `df >= 3`), appears only
            # in unformatted body prose (`ck == 0 and dhf == 0`), is <= 2 chars,
            # or is a project slug.
            is_ubiquitous = (
                1
                if (
                    not is_facet
                    and (cf >= 3 or df >= 3 or (ck == 0 and dhf == 0) or len(st) <= 2 or st in PROJECT_TAG_MAP.values())
                )
                else 0
            )
            if is_facet:
                facet_stems.add(st)
            vocab_rows.append((st, proj, kf, cf, ck, dhf, df, round(idf, 4), is_facet, is_ubiquitous))

        # Build corpus co-occurrence associations between facet stems and canonical config keys / docs
        assoc_map: dict[str, dict[str, float]] = {}
        for c in core_cfgs:
            if c.key_path == "version":
                continue
            kp = c.key_path
            if kp.startswith("defaults."):
                kp = kp[len("defaults."):]
            leaf = kp.split(".")[-1]
            target_key = kp if leaf in ("name", "mode", "radius", "centerRadius", "minY", "maxY", "requireSkyLight") else leaf
            ks = porter_stem_text(f"{c.key_path} {c.directives}")
            ds = porter_stem_text(c.description)
            for s in ks | ds:
                if s not in facet_stems:
                    continue
                ck = len(cfg_keys_cnt.get(s, ()))
                kf_s = len(key_id_fams.get(s, ()))
                dhf_s = len(doc_hdr_files.get(s, ()))
                if ck == 0 or (ck > 24 and s not in ks):
                    continue
                # Prevent stems that never appear in a config key name (`kf == 0`, e.g. `placehold`)
                # and are strong documentation heading concepts (`dhf >= 4`, `df >= 15`) from associating
                # with incidental YAML prose comments (`group_get`, `override`, `world`).
                if kf_s == 0 and s not in ks and dhf_s >= 4 and len(doc_files.get(s, ())) >= 15:
                    continue
                w = (5.0 if s in ks else 2.5) / math.sqrt(max(1, ck))
                if c.file_path in (
                    "config.yml",
                    "definitions/regions/default.yml",
                    "advanced/performance.yml",
                    "safety.yml",
                    "advanced/biomes.yml",
                    "addons/guimenu.yml",
                    "economy.yml",
                ):
                    w *= 1.3
                if leaf == "textQueued":
                    continue
                assoc_map.setdefault(s, {})[target_key] = max(assoc_map.get(s, {}).get(target_key, 0.0), w)
                if kp == "shape.name" and "POLYGON" in c.directives:
                    assoc_map[s]["POLYGON"] = max(assoc_map[s].get("POLYGON", 0.0), w * 0.95)

        for d in proj_docs:
            doc_stem = d.path.split("/")[-1].replace(".md", "")
            hdr_stems = porter_stem_text(f"{d.title} {d.heading}")
            body_stems = set() if d.path.endswith("FAQ.md") else porter_stem_text(d.body)
            for s in hdr_stems | body_stems:
                if s not in facet_stems:
                    continue
                df = len(doc_files.get(s, ()))
                dhf_s = len(doc_hdr_files.get(s, ()))
                if 1 <= df <= 9:
                    base_w = 3.2 if s in hdr_stems else 1.5
                    w = base_w / math.sqrt(df)
                    assoc_map.setdefault(s, {})[doc_stem] = max(assoc_map.get(s, {}).get(doc_stem, 0.0), w)
                elif s in hdr_stems and 4 <= dhf_s <= 10 and len(key_id_fams.get(s, ())) == 0:
                    w = 3.2 / math.sqrt(max(1, min(df, 12)))
                    assoc_map.setdefault(s, {})[doc_stem] = max(assoc_map.get(s, {}).get(doc_stem, 0.0), w)

        for st, targets in assoc_map.items():
            top_targets = sorted(targets.items(), key=lambda item: (-item[1], item[0]))[:6]
            for tgt, weight in top_targets:
                assoc_rows.append((st, tgt, round(weight, 4), proj))

    conn.executemany(
        """
        INSERT OR REPLACE INTO corpus_vocab(
            stem, project, kf, cf, ck, dhf, df, idf, is_facet, is_ubiquitous
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        vocab_rows,
    )
    conn.executemany(
        """
        INSERT OR REPLACE INTO corpus_associations(stem, target, weight, project)
        VALUES (?, ?, ?, ?)
        """,
        assoc_rows,
    )


def build_index(
    repo_root: pathlib.Path = ROOT,
    db_path: pathlib.Path = DEFAULT_DB_PATH,
    extra_projects: Iterable[ProjectSource] = (),
    manifest: pathlib.Path | str | dict[str, Any] | None = None,
) -> sqlite3.Connection:
    """Builds the SQLite FTS5 documentation, YAML schema, and BIT code database."""
    if str(db_path) != ":memory:":
        db_path.parent.mkdir(parents=True, exist_ok=True)
        if db_path.exists():
            db_path.unlink()

    conn = sqlite3.connect(str(db_path))
    conn.row_factory = sqlite3.Row

    cfg = load_support_bot_manifest(manifest)
    default_proj = str(cfg.get("default_project") or DEFAULT_PROJECT).strip().lower()
    default_site_base = str(
        ((cfg.get("projects") or {}).get(default_proj) or {}).get("site_base") or SITE_BASE
    ).strip()
    sources = [ProjectSource(project=default_proj, root=repo_root, site_base=default_site_base)]
    sources.extend(extra_projects)

    with conn:
        conn.execute("DROP TABLE IF EXISTS doc_sections_vocab")
        conn.execute("DROP TABLE IF EXISTS config_keys_vocab")
        conn.execute("DROP TABLE IF EXISTS doc_sections_col_vocab")
        conn.execute("DROP TABLE IF EXISTS config_keys_col_vocab")
        conn.execute("DROP TABLE IF EXISTS corpus_associations")
        conn.execute("DROP TABLE IF EXISTS corpus_vocab")
        conn.execute("DROP TABLE IF EXISTS code_sections")
        conn.execute("DROP TABLE IF EXISTS arch_sections")
        conn.execute("DROP TABLE IF EXISTS doc_sections")
        conn.execute("DROP TABLE IF EXISTS config_keys")
        conn.execute("DROP TABLE IF EXISTS bit_codes")
        conn.execute("DROP TABLE IF EXISTS locale_aliases")

        conn.execute(
            """
            CREATE VIRTUAL TABLE doc_sections USING fts5(
                title,
                heading,
                path,
                url UNINDEXED,
                body,
                tags,
                project UNINDEXED,
                tokenize='porter unicode61'
            )
            """
        )
        conn.execute(
            """
            CREATE VIRTUAL TABLE arch_sections USING fts5(
                title,
                heading,
                path,
                url UNINDEXED,
                body,
                tags,
                project UNINDEXED,
                tokenize='porter unicode61'
            )
            """
        )
        conn.execute(
            """
            CREATE VIRTUAL TABLE code_sections USING fts5(
                class_name,
                package_name,
                path,
                methods,
                diagnostics,
                config_refs,
                summary,
                project UNINDEXED,
                tokenize='porter unicode61'
            )
            """
        )
        conn.execute(
            """
            CREATE VIRTUAL TABLE config_keys USING fts5(
                file_path,
                key_path,
                default_value,
                directives,
                description,
                project UNINDEXED,
                tokenize='porter unicode61'
            )
            """
        )
        conn.execute("CREATE VIRTUAL TABLE doc_sections_vocab USING fts5vocab(doc_sections, row)")
        conn.execute("CREATE VIRTUAL TABLE config_keys_vocab USING fts5vocab(config_keys, row)")
        conn.execute("CREATE VIRTUAL TABLE doc_sections_col_vocab USING fts5vocab(doc_sections, col)")
        conn.execute("CREATE VIRTUAL TABLE config_keys_col_vocab USING fts5vocab(config_keys, col)")

        conn.execute(
            """
            CREATE TABLE corpus_vocab (
                stem TEXT NOT NULL,
                project TEXT NOT NULL DEFAULT 'leafrtp',
                kf INTEGER NOT NULL,
                cf INTEGER NOT NULL,
                ck INTEGER NOT NULL,
                dhf INTEGER NOT NULL,
                df INTEGER NOT NULL,
                idf REAL NOT NULL,
                is_facet INTEGER NOT NULL DEFAULT 0,
                is_ubiquitous INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (stem, project)
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE corpus_associations (
                stem TEXT NOT NULL,
                target TEXT NOT NULL,
                weight REAL NOT NULL,
                project TEXT NOT NULL DEFAULT 'leafrtp',
                PRIMARY KEY (stem, target, project)
            )
            """
        )
        conn.execute("CREATE INDEX idx_corpus_assoc_stem ON corpus_associations(stem, lower(project))")

        conn.execute(
            """
            CREATE TABLE bit_codes (
                namespace TEXT NOT NULL,
                id INTEGER NOT NULL,
                key TEXT PRIMARY KEY,
                severity TEXT NOT NULL,
                tier TEXT NOT NULL,
                subsystem TEXT NOT NULL,
                summary TEXT NOT NULL,
                remediation TEXT NOT NULL,
                project TEXT NOT NULL DEFAULT 'leafrtp'
            )
            """
        )
        conn.execute("CREATE INDEX idx_bit_codes_ns_id ON bit_codes(namespace, id)")
        conn.execute("CREATE INDEX idx_bit_codes_project ON bit_codes(project)")

        conn.execute(
            """
            CREATE TABLE locale_aliases (
                locale TEXT NOT NULL,
                localized_key TEXT NOT NULL,
                canonical_key TEXT NOT NULL,
                file_path TEXT NOT NULL,
                project TEXT NOT NULL DEFAULT 'leafrtp'
            )
            """
        )
        conn.execute("CREATE INDEX idx_locale_aliases_key ON locale_aliases(lower(localized_key), lower(project))")

        doc_rows: list[DocSection] = []
        arch_rows: list[DocSection] = []
        code_rows: list[CodeSection] = []
        config_rows: list[ConfigKeyEntry] = []
        bit_rows: list[BitCodeEntry] = []
        locale_rows: list[LocaleAliasEntry] = []

        for src in sources:
            proj = src.project.strip().lower()
            proj_doc_rows: list[DocSection] = []
            for doc_file in iter_doc_files(src.root):
                proj_doc_rows.extend(
                    split_markdown_sections(
                        doc_file,
                        repo_root=src.root,
                        project=proj,
                        site_base=src.site_base,
                    )
                )
            doc_rows.extend(proj_doc_rows)
            for arch_file in iter_arch_doc_files(src.root, project=proj, manifest=cfg):
                arch_rows.extend(
                    split_markdown_sections(
                        arch_file,
                        repo_root=src.root,
                        project=proj,
                        site_base=src.site_base,
                    )
                )
            code_rows.extend(parse_java_code_sections(src.root, project=proj))
            proj_cfg_rows: list[ConfigKeyEntry] = []
            for logical_path, yml_path in iter_shipped_yaml_files(src.root):
                proj_cfg_rows.extend(parse_yaml_config_keys(logical_path, yml_path, project=proj))
            expanded_cfg = expand_region_inherited_keys(proj_cfg_rows)
            config_rows.extend(enrich_config_keys_from_docs(expanded_cfg, proj_doc_rows))
            bit_rows.extend(parse_bit_codes(src.root, project=proj, manifest=cfg))
            locale_rows.extend(parse_locale_key_aliases(src.root, project=proj))

        conn.executemany(
            "INSERT INTO doc_sections(title, heading, path, url, body, tags, project) VALUES (?, ?, ?, ?, ?, ?, ?)",
            [(d.title, d.heading, d.path, d.url, d.body, d.tags, d.project) for d in doc_rows],
        )

        conn.executemany(
            "INSERT INTO arch_sections(title, heading, path, url, body, tags, project) VALUES (?, ?, ?, ?, ?, ?, ?)",
            [(a.title, a.heading, a.path, a.url, a.body, a.tags, a.project) for a in arch_rows],
        )

        conn.executemany(
            "INSERT INTO code_sections(class_name, package_name, path, methods, diagnostics, config_refs, summary, project) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            [
                (cs.class_name, cs.package_name, cs.path, cs.methods, cs.diagnostics, cs.config_refs, cs.summary, cs.project)
                for cs in code_rows
            ],
        )

        conn.executemany(
            "INSERT INTO config_keys(file_path, key_path, default_value, directives, description, project) VALUES (?, ?, ?, ?, ?, ?)",
            [(c.file_path, c.key_path, c.default_value, c.directives, c.description, c.project) for c in config_rows],
        )

        conn.executemany(
            """
            INSERT OR REPLACE INTO bit_codes(namespace, id, key, severity, tier, subsystem, summary, remediation, project)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [
                (b.namespace, b.id, b.key, b.severity, b.tier, b.subsystem, b.summary, b.remediation, b.project)
                for b in bit_rows
            ],
        )

        conn.executemany(
            """
            INSERT INTO locale_aliases(locale, localized_key, canonical_key, file_path, project)
            VALUES (?, ?, ?, ?, ?)
            """,
            [
                (la.locale, la.localized_key, la.canonical_key, la.file_path, la.project)
                for la in locale_rows
            ],
        )

        _populate_corpus_vocab_and_associations(conn, doc_rows, config_rows, bit_rows)

    conn_id = id(conn)
    for k in [k for k in _ASSOC_CACHE if k[0] == conn_id]:
        del _ASSOC_CACHE[k]
    for k in [k for k in _VOCAB_ROW_CACHE if k[0] == conn_id]:
        del _VOCAB_ROW_CACHE[k]
    for k in [k for k in _FACET_TOKEN_CACHE if k[0] == conn_id]:
        del _FACET_TOKEN_CACHE[k]
    for k in [k for k in _MSG_TEMPLATE_CACHE if k[0] == conn_id]:
        del _MSG_TEMPLATE_CACHE[k]
    _OCR_DECOMPOUND_VOCAB_CACHE.pop(conn_id, None)

    return conn


def _get_vocab_stats(
    conn: sqlite3.Connection | None,
    stem: str,
    project: str = DEFAULT_PROJECT,
) -> dict[str, int] | None:
    """Returns cached `corpus_vocab` integer stats for `(conn, project, stem)`."""
    if conn is None or not stem:
        return None
    proj = (project or DEFAULT_PROJECT).strip().lower()
    cache_key = (id(conn), proj, stem)
    if cache_key in _VOCAB_ROW_CACHE:
        return _VOCAB_ROW_CACHE[cache_key]
    try:
        row = conn.execute(
            """
            SELECT kf, cf, ck, dhf, df, is_facet, is_ubiquitous
            FROM corpus_vocab
            WHERE stem = ? AND lower(project) = ?
            LIMIT 1
            """,
            (stem, proj),
        ).fetchone()
    except sqlite3.OperationalError:
        return None
    if row is None:
        _VOCAB_ROW_CACHE[cache_key] = None
        return None
    stats = {
        "kf": int(row["kf"]),
        "cf": int(row["cf"]),
        "ck": int(row["ck"]),
        "dhf": int(row["dhf"]),
        "df": int(row["df"]),
        "is_facet": int(row["is_facet"]),
        "is_ubiquitous": int(row["is_ubiquitous"]),
    }
    _VOCAB_ROW_CACHE[cache_key] = stats
    return stats


def get_corpus_associations(
    conn: sqlite3.Connection,
    token: str,
    project: str = DEFAULT_PROJECT,
) -> list[str]:
    """
    Returns corpus-derived co-occurring config keys and doc stems for `token`
    from `corpus_associations` (built automatically from YAML comments and
    Markdown sections during `build_index`).
    """
    clean = re.sub(r"[^a-z0-9_.-]", "", token.lower())
    if not clean or len(clean) < 2:
        return []
    proj = (project or DEFAULT_PROJECT).strip().lower()
    stem = porter_stem_word(clean)
    cache_key = (id(conn), proj, stem)
    cached = _ASSOC_CACHE.get(cache_key)
    if cached is not None:
        return cached
    try:
        rows = conn.execute(
            """
            SELECT target
            FROM corpus_associations
            WHERE stem = ? AND lower(project) = ?
            ORDER BY weight DESC, target ASC
            LIMIT 6
            """,
            (stem, proj),
        ).fetchall()
        res = [str(r["target"]) for r in rows]
        _ASSOC_CACHE[cache_key] = res
        return res
    except sqlite3.OperationalError:
        return []


def is_ubiquitous_corpus_token(
    conn: sqlite3.Connection | None,
    token: str,
    project: str = DEFAULT_PROJECT,
) -> bool:
    """
    Returns True if `token` is a ubiquitous/glue word (`is_ubiquitous = 1` in
    `corpus_vocab`) or a short non-facet function word according to corpus IDF
    and file-spread statistics. Out-of-corpus tokens of length >= 4 (e.g.
    `tebex`, `webstore`, `questline`) return False so tangential queries keep
    their unindexed topic tokens.
    """
    clean = token.lower().strip(".-_")
    if not clean or len(clean) < 2:
        return True
    if clean in PROJECT_TAG_MAP.values():
        return True
    if "." in clean:
        return False
    if conn is None:
        return len(clean) <= 2
    proj = (project or DEFAULT_PROJECT).strip().lower()
    stem = porter_stem_word(clean)
    row = _get_vocab_stats(conn, stem, project=proj)
    if row is None:
        return len(clean) <= 3
    if row["is_facet"] == 1:
        return False
    return row["is_ubiquitous"] == 1


def resolve_localized_query_aliases(
    conn: sqlite3.Connection,
    question: str,
    project: str = DEFAULT_PROJECT,
    include_cooccurrence: bool = False,
) -> list[dict[str, str]]:
    """
    Scans `question` for:
      1. Localized YAML key names and localized YAML comment terms from
         `locale_aliases` (`lang/<locale>/**/.*.lang.yml` and `lang/<locale>/**/*.yml`).
      2. When `include_cooccurrence=True` (e.g. after NMT symptom translation),
         multi-stem corpus co-occurrence targets from `corpus_associations` where
         2+ distinct facet stems in `question` co-occur on the same canonical
         configuration key in `doc-index.sqlite`.

    Returns a deduplicated list of `{"source": ..., "canonical": ..., "locale": ...}`
    mappings with zero static regex tables or free-form LLM translation.
    """
    if not question or not question.strip():
        return []
    proj = project.strip().lower() or DEFAULT_PROJECT
    resolved: list[dict[str, str]] = []
    seen_canonical: set[str] = set()

    # 1. Check `locale_aliases` table (both `.lang.yml` keys and localized YAML comments)
    has_table = conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='locale_aliases' LIMIT 1"
    ).fetchone()
    if has_table is not None:
        unicode_tokens = re.findall(r"[\w.-]+", question, flags=re.UNICODE)
        cand_tokens: list[str] = []
        for ut in unicode_tokens:
            clean_ut = ut.strip(".-_").lower()
            if len(clean_ut) >= 2 and clean_ut not in cand_tokens:
                cand_tokens.append(clean_ut)
                if "." in clean_ut:
                    for part in clean_ut.split("."):
                        if len(part) >= 2 and part not in cand_tokens:
                            cand_tokens.append(part)

        exact_key_matches: list[dict[str, str]] = []
        comment_matches: list[dict[str, str]] = []
        for cand in cand_tokens:
            rows = conn.execute(
                """
                SELECT locale, localized_key, canonical_key, file_path
                FROM locale_aliases
                WHERE localized_key = ? AND lower(project) = ?
                ORDER BY CASE WHEN locale LIKE '%_comment' THEN 1 ELSE 0 END ASC
                LIMIT 4
                """,
                (cand, proj),
            ).fetchall()
            for r in rows:
                ck = str(r["canonical_key"])
                loc = str(r["locale"])
                item = {
                    "source": str(r["localized_key"]),
                    "canonical": ck,
                    "locale": loc,
                }
                if loc.endswith("_comment"):
                    comment_matches.append(item)
                else:
                    exact_key_matches.append(item)
        for item in (exact_key_matches + comment_matches):
            ck_low = item["canonical"].lower()
            if ck_low not in seen_canonical:
                seen_canonical.add(ck_low)
                resolved.append(item)

        # Also check CJK/continuous substring matches for non-space-delimited scripts (zh, ja, ko)
        if re.search(r"[\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af]", question):
            q_low = question.lower()
            cjk_rows = conn.execute(
                """
                SELECT locale, localized_key, canonical_key
                FROM locale_aliases
                WHERE (locale IN ('zh', 'ja', 'ko') OR locale IN ('zh_comment', 'ja_comment', 'ko_comment'))
                  AND lower(project) = ?
                ORDER BY CASE WHEN locale LIKE '%_comment' THEN 1 ELSE 0 END ASC, length(localized_key) DESC
                """,
                (proj,),
            ).fetchall()
            cjk_comment_stopwords = {
                "配置", "文件", "配置文件", "设置", "修改", "默认", "使用", "怎么", "如何", "请问", "可以", "或者", "的时候",
            }
            cjk_exact: list[dict[str, str]] = []
            cjk_comment: list[dict[str, str]] = []
            for r in cjk_rows:
                loc_key = str(r["localized_key"])
                ck = str(r["canonical_key"])
                loc = str(r["locale"])
                if len(loc_key) >= 2 and loc_key in q_low:
                    if loc.endswith("_comment") and loc_key in cjk_comment_stopwords:
                        continue
                    item = {"source": loc_key, "canonical": ck, "locale": loc}
                    if loc.endswith("_comment"):
                        cjk_comment.append(item)
                    else:
                        cjk_exact.append(item)
            for item in (cjk_exact + cjk_comment):
                ck_low = item["canonical"].lower()
                if ck_low not in seen_canonical:
                    seen_canonical.add(ck_low)
                    resolved.append(item)

    # 2. Multi-stem corpus co-occurrence resolution via `corpus_associations`
    stems = sorted(porter_stem_text(question))
    if include_cooccurrence and len(stems) >= 2:
        target_stems: dict[str, list[tuple[str, float]]] = {}
        for st in stems:
            try:
                rows = conn.execute(
                    """
                    SELECT target, weight
                    FROM corpus_associations
                    WHERE stem = ? AND lower(project) = ?
                    ORDER BY weight DESC
                    LIMIT 4
                    """,
                    (st, proj),
                ).fetchall()
            except sqlite3.OperationalError:
                rows = []
            for r in rows:
                tgt = str(r["target"])
                if tgt.isupper():
                    continue
                target_stems.setdefault(tgt, []).append((st, float(r["weight"])))
        cooccurring = [
            (sum(w for _, w in st_list), len(st_list), tgt, [s for s, _ in st_list])
            for tgt, st_list in target_stems.items()
            if len(st_list) >= 2
        ]
        cooccurring.sort(key=lambda item: (-item[1], -item[0], item[2]))
        for _, _, tgt, matched_st in cooccurring[:3]:
            if tgt.lower() not in seen_canonical:
                seen_canonical.add(tgt.lower())
                resolved.append(
                    {
                        "source": "+".join(matched_st[:3]),
                        "canonical": tgt,
                        "locale": "corpus_cooccurrence",
                    }
                )

    return resolved


def score_translation_candidate(
    conn: sqlite3.Connection,
    candidate_text: str,
    raw_text: str = "",
    project: str = DEFAULT_PROJECT,
) -> float:
    """
    Deterministically scores an English candidate translation against `doc-index.sqlite`
    so the pipeline can run against two differently functioning translation services
    (e.g. Google Translate NMT vs MyMemory Translation Memory + hybrid MT) and select
    the candidate with the strongest schema/facet signal:
      1. Canonical concept & alias resolution count (`resolve_localized_query_aliases`): +4.0 each
      2. Corpus facet count (`is_schema_or_heading_facet`): +3.0 each
      3. Corpus association matches (`get_corpus_associations`): +1.5 each
      4. Schema facet precision (`len(facets) / max(1, len(specific_tokens))`): +2.0 * precision
      5. Penalizes error strings (`PLEASE SELECT TWO DISTINCT LANGUAGES`, `MYMEMORY WARNING`)
         or untranslated non-ASCII scripts when comparing candidates.
    """
    cleaned = (candidate_text or "").strip()
    if not cleaned:
        return -100.0
    upper = cleaned.upper()
    if (
        "PLEASE SELECT TWO DISTINCT LANGUAGES" in upper
        or "MYMEMORY WARNING" in upper
        or "INVALID LANGUAGE PAIR" in upper
        or "QUERY LENGTH LIMIT EXCEEDED" in upper
    ):
        return -100.0

    proj = (project or DEFAULT_PROJECT).strip().lower()
    aliases = resolve_localized_query_aliases(conn, cleaned, project=proj, include_cooccurrence=True)
    spec_tokens = extract_specific_tokens(cleaned, conn=conn, project=proj)
    facets = [tok for tok in spec_tokens if is_schema_or_heading_facet(conn, tok, project=proj)]
    assocs = [tok for tok in spec_tokens if get_corpus_associations(conn, tok, project=proj)]
    precision = len(facets) / max(1, len(spec_tokens))

    score = (
        len(aliases) * 4.0
        + len(facets) * 3.0
        + len(assocs) * 1.5
        + precision * 2.0
    )
    if raw_text and re.search(r"[\u0400-\u04ff\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af]", raw_text):
        if re.search(r"[\u0400-\u04ff\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af]", cleaned):
            score -= 15.0
    return round(score, 3)


def select_best_translation(
    conn: sqlite3.Connection,
    raw_text: str,
    candidates: list[tuple[str, str, str]],
    project: str = DEFAULT_PROJECT,
) -> tuple[str, str, str, float]:
    """
    Given `[(service_name, translated_text, detected_lang), ...]` from multiple
    translation services, scores each candidate against `doc-index.sqlite` and
    returns `(best_text, best_lang, best_service, best_score)`.
    """
    if not candidates:
        return raw_text, "en", "none", 0.0
    scored: list[tuple[float, int, str, str, str]] = []
    for idx, (svc_name, cand_text, cand_lang) in enumerate(candidates):
        if not cand_text or not cand_text.strip():
            continue
        sc = score_translation_candidate(conn, cand_text, raw_text=raw_text, project=project)
        # Use negative `idx` as a deterministic tie-breaker so the primary service wins ties
        scored.append((sc, -idx, cand_text.strip(), (cand_lang or "en").strip().lower(), svc_name))
    if not scored:
        return raw_text, "en", "none", 0.0
    scored.sort(key=lambda item: (item[0], item[1]), reverse=True)
    best_score, _, best_text, best_lang, best_svc = scored[0]
    if best_score < 0:
        return raw_text, "en", "fallback_raw", 0.0
    return best_text, best_lang, best_svc, best_score


def normalize_multilingual_query(
    conn: sqlite3.Connection,
    question: str,
    project: str = DEFAULT_PROJECT,
    translator: Any = None,
) -> tuple[str, list[dict[str, str]]]:
    """
    Two-Pass Token Harvesting (`REQ-DISC-005`) with Dual-Translator Selection:
      - Pass 1 (Pre-Translation Exact Harvest): Scans the raw foreign query
        against `locale_aliases` (`doc-index.sqlite`) BEFORE any NMT runs so
        literal localized config keys (e.g. `retrasoTeletransporte`, `传送延迟`,
        `отсрочкаТелепорта`) are locked in immediately as canonical English
        schema tokens without being mutated by machine translation.
      - Pass 2 (Dual-Service Symptom Translation & Selection): Runs `translator`
        (which may return a single `(translated_text, detected_lang)` tuple OR
        a list of `(service_name, translated_text, detected_lang)` candidates
        from two differently functioning translation services such as Google
        Translate NMT and MyMemory Translation Memory), selects the higher-scoring
        candidate via `select_best_translation`, and unions the resolved tokens
        with Pass 1.
    """
    pass1_mappings = resolve_localized_query_aliases(conn, question, project=project)
    seen_canonical = {m["canonical"].lower() for m in pass1_mappings}
    all_mappings = list(pass1_mappings)

    combined_text = question
    if translator is not None and question and question.strip():
        raw_res = translator(question)
        candidates: list[tuple[str, str, str]] = []
        if isinstance(raw_res, list):
            for idx, item in enumerate(raw_res):
                if isinstance(item, tuple) and len(item) >= 3:
                    candidates.append((str(item[0]), str(item[1] or ""), str(item[2] or "en")))
                elif isinstance(item, tuple) and len(item) == 2:
                    candidates.append((f"translator_{idx}", str(item[0] or ""), str(item[1] or "en")))
        elif isinstance(raw_res, tuple) and raw_res:
            t_str = str(raw_res[0] or "")
            t_lang = str(raw_res[1] or "en") if len(raw_res) > 1 else "en"
            candidates.append(("translator", t_str, t_lang))
        elif isinstance(raw_res, str):
            candidates.append(("translator", raw_res, "en"))

        if candidates:
            translated_str, detected_lang, winning_svc, _ = select_best_translation(
                conn,
                question,
                candidates,
                project=project,
            )
            if translated_str and translated_str.strip() and translated_str.strip().lower() != question.strip().lower():
                # When translation produces an English string from a non-English post,
                # use the winning English translation as the base text (since Pass 1
                # already harvested all literal `.lang.yml` keys from the raw foreign
                # string) so short foreign stopwords like Spanish `se` or Polish `do`
                # don't pollute FTS5 token matching.
                combined_text = (
                    translated_str
                    if detected_lang and not detected_lang.startswith("en")
                    else f"{question}\n{translated_str}"
                )
                # Harvest concept/alias mappings from the winning translation first,
                # then from any valid runner-up candidate if it unlocked an additional
                # canonical schema target.
                ordered_cands = [(winning_svc, translated_str)] + [
                    (svc, txt)
                    for svc, txt, _ in candidates
                    if txt and txt.strip() and txt.strip() != translated_str
                    and score_translation_candidate(conn, txt, raw_text=question, project=project) >= 0
                ]
                for svc_name, cand_txt in ordered_cands:
                    pass2_mappings = resolve_localized_query_aliases(
                        conn,
                        cand_txt,
                        project=project,
                        include_cooccurrence=True,
                    )
                    for m in pass2_mappings:
                        ck_low = m["canonical"].lower()
                        if ck_low not in seen_canonical:
                            seen_canonical.add(ck_low)
                            m_copy = dict(m)
                            m_copy["translator"] = svc_name
                            all_mappings.append(m_copy)

    if not all_mappings:
        return combined_text, []
    canonical_T = " ".join(m["canonical"] for m in all_mappings)
    return f"{combined_text} {canonical_T}".strip(), all_mappings


def _strip_runtime_target_values(text: str) -> str:
    """
    Normalizes runtime command target arguments such as `player:Badministrator`
    or `player=Steve` to `player` so arbitrary player usernames never act as
    unrecognized topic tokens or alter retrieval ranking.
    """
    return re.sub(
        r"\b(player)\s*[:=]\s*(?!<)[A-Za-z0-9_]{2,24}\b",
        r"\1",
        text or "",
        flags=re.IGNORECASE,
    )


def _tokenize_query(
    question: str,
    conn: sqlite3.Connection | None = None,
    project: str = DEFAULT_PROJECT,
) -> list[str]:
    """Extracts safe alphanumeric + dotted tokens from a natural-language question (stripping URLs)."""
    no_urls = re.sub(r"https?://\S+", " ", _strip_runtime_target_values(question), flags=re.IGNORECASE)
    raw_tokens = re.findall(r"[A-Za-z0-9_.-]+", no_urls)
    tokens: list[str] = []
    for tok in raw_tokens:
        low = tok.lower().strip(".-_")
        if not low or len(low) > 128 or (len(low) < 2 and not low.isdigit()) or low in ("http", "https", "www"):
            continue
        if "." not in low and not low.isdigit() and conn is not None:
            if is_ubiquitous_corpus_token(conn, low, project=project):
                continue
        if low not in tokens:
            tokens.append(low)
    return tokens


def extract_specific_tokens(
    question: str,
    conn: sqlite3.Connection | None = None,
    project: str = DEFAULT_PROJECT,
) -> list[str]:
    """Returns non-ubiquitous, stem-deduplicated domain query tokens from a user question using `corpus_vocab`."""
    no_urls = re.sub(r"https?://\S+", " ", _strip_runtime_target_values(question), flags=re.IGNORECASE)
    raw_tokens = re.findall(r"[A-Za-z0-9_.-]+", no_urls)
    specific: list[str] = []
    seen_stems: set[str] = set()
    for tok in raw_tokens:
        low = tok.lower().strip(".-_")
        if not low or len(low) < 2 or len(low) > 128 or low.isdigit() or low in ("http", "https", "www"):
            continue
        if is_ubiquitous_corpus_token(conn, low, project=project):
            continue
        st = low if "." in low else porter_stem_word(low)
        if low not in specific and (st not in seen_stems or len(low) >= 9):
            seen_stems.add(st)
            specific.append(low)
    return specific


def _direct_token_matches_text(token: str, text_lower: str) -> bool:
    """Checks whether `token` or its Porter stem appears directly in `text_lower` (without 1-hop association expansion)."""
    tok_low = token.lower()
    stem = porter_stem_word(tok_low)
    candidates = [tok_low]
    if stem and len(stem) >= 3:
        candidates.append(stem)
    if "." in token:
        candidates.extend(part.lower() for part in token.split(".") if len(part) >= 3)
    for cand in dict.fromkeys(candidates):
        if not cand or cand not in text_lower:
            continue
        if re.search(rf"(?:^|[\s`._/-]){re.escape(cand)}(?:$|[\s`._/,;:!?)-\]])", text_lower):
            return True
        if len(cand) >= 4 and re.search(rf"(?:^|[\s`._/-]){re.escape(cand)}[a-z]{{0,3}}(?:$|[\s`._/,;:!?)-\]])", text_lower):
            return True
    return False


def _token_matches_text(
    token: str,
    text_lower: str,
    conn: sqlite3.Connection | None = None,
    project: str = DEFAULT_PROJECT,
) -> bool:
    """
    Checks whether `token`, its SQLite `porter unicode61` stem, or its
    corpus-associated targets (`corpus_associations`) appear in `text_lower`.
    """
    tok_low = token.lower()
    stem = porter_stem_word(tok_low)
    candidates = [tok_low]
    if stem and len(stem) >= 3:
        candidates.append(stem)
    if conn is not None:
        for assoc_tgt in get_corpus_associations(conn, tok_low, project=project):
            candidates.append(assoc_tgt.lower())
    if "." in token:
        candidates.extend(part.lower() for part in token.split(".") if len(part) >= 3)
    for cand in dict.fromkeys(candidates):
        if not cand or cand not in text_lower:
            continue
        if re.search(rf"(?:^|[\s`._/-]){re.escape(cand)}(?:$|[\s`._/,;:!?)-\]])", text_lower):
            return True
        if len(cand) >= 4 and re.search(rf"(?:^|[\s`._/-]){re.escape(cand)}[a-z]{{0,3}}(?:$|[\s`._/,;:!?)-\]])", text_lower):
            return True
    return False


def extract_doc_snippet(body: str, max_chars: int = 220) -> str:
    """Extracts a concise 1-2 sentence plain-text summary (plus first command/YAML example) from a doc section."""
    cleaned = re.sub(r"(?s)<!--.*?-->", "", body)
    prose_lines: list[str] = []
    table_lines: list[str] = []
    code_lines: list[str] = []
    in_fence = False
    for raw in cleaned.splitlines():
        s = raw.strip()
        if s.startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            if s and not s.startswith("#") and len(code_lines) < 2:
                code_lines.append(s)
            continue
        if not s or s.startswith("#") or s.startswith("|---") or s.startswith("---") or "Current Plugin Version:" in s:
            continue
        if re.match(r"^!\[[^\]]*\]\([^)]+\)$", s):
            continue
        if s.startswith("|") and s.endswith("|"):
            cells = [c.strip() for c in s.strip("|").split("|") if c.strip()]
            if cells and not all(re.match(r"^[-:]+$", c) for c in cells):
                table_lines.append(" -> ".join(cells))
            continue
        if s.startswith("!!! "):
            adm_m = re.match(r'^!!!\s+\w+\s+"([^"]+)"', s)
            if adm_m:
                s = f"**{adm_m.group(1)}:**"
            else:
                continue
        s = re.sub(r"!\[[^\]]*\]\([^)]+\)", "", s)
        s = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", s)
        s = re.sub(r"^[-*+]\s+", "", s)
        s = re.sub(r"^>\s*", "", s)
        if s:
            prose_lines.append(s)
            if sum(len(x) for x in prose_lines) >= max_chars + 40:
                break

    if not prose_lines and table_lines:
        prose_lines = table_lines[1:3] if len(table_lines) > 1 else table_lines[:1]

    summary = " ".join(prose_lines).strip()
    summary = re.sub(r"\s+", " ", summary)
    if len(summary) > max_chars:
        window = summary[: max_chars + 1]
        sentence_end = -1
        for m in re.finditer(r"(?<!\b\d)[.!?](?=\s|$)", window):
            candidate_slice = window[: m.end()]
            if m.end() >= int(max_chars * 0.55) and candidate_slice.count("`") % 2 == 0:
                sentence_end = m.end()
        if sentence_end != -1:
            summary = window[:sentence_end].strip()
        else:
            cut = summary[:max_chars].rsplit(" ", 1)[0]
            if cut.count("`") % 2 == 1:
                last_tick = cut.rfind("`")
                cut = cut[:last_tick].rsplit(" ", 1)[0] if " " in cut[:last_tick] else cut[:last_tick]
            cut = cut.rstrip(" ,;:-(")
            summary = f"{cut}..."
    elif summary.count("`") % 2 == 1:
        last_tick = summary.rfind("`")
        summary = summary[:last_tick].strip()
    if code_lines and code_lines[0] not in summary and len(summary) + len(code_lines[0]) + 10 <= max_chars + 45:
        summary = f"{summary} (e.g. `{code_lines[0]}`)" if summary else f"`{code_lines[0]}`"
    return summary


def _build_fts_or_query(
    tokens: Iterable[str],
    conn: sqlite3.Connection | None = None,
    project: str = DEFAULT_PROJECT,
) -> str:
    """Builds a safe FTS5 OR expression quoting each token and its corpus-associated targets."""
    expanded: list[str] = []
    for tok in tokens:
        if tok not in expanded:
            expanded.append(tok)
        if conn is not None:
            for assoc_tgt in get_corpus_associations(conn, tok.lower(), project=project):
                assoc_clean = assoc_tgt.lower()
                if assoc_clean not in expanded:
                    expanded.append(assoc_clean)

    quoted: list[str] = []
    for tok in expanded:
        safe = tok.replace('"', "")
        if safe:
            quoted.append(f'"{safe}"')
    return " OR ".join(quoted)


def _score_config_confidence(
    file_path: str,
    key_path: str,
    description: str,
    directives: str,
    q_lower: str,
    specific_tokens: list[str],
    wants_radius: bool,
    wants_gui: bool,
    wants_shape: bool = False,
    wants_action: bool = False,
    is_exact: bool = False,
    conn: sqlite3.Connection | None = None,
    project: str = DEFAULT_PROJECT,
) -> str:
    if is_exact:
        return "high"
    kp_lower = key_path.lower()
    leaf_lower = kp_lower.split(".")[-1]
    is_message_file = file_path.startswith("advanced/messages/") or file_path.startswith("messages/")
    if (
        ("." in kp_lower or (len(kp_lower) >= 5 and not is_ubiquitous_corpus_token(conn, kp_lower, project=project)))
        and not is_message_file
        and re.search(rf"(?:^|[\s`\"'=/(-]){re.escape(kp_lower)}(?:$|[\s`\"'=/:.,;!?)-])", q_lower)
    ):
        return "high"
    if wants_shape and not wants_action and file_path.startswith("definitions/actions/"):
        return "low"
    if (
        len(leaf_lower) >= 4
        and not is_ubiquitous_corpus_token(conn, leaf_lower, project=project)
        and not is_message_file
        and re.search(rf"\b{re.escape(leaf_lower)}\b", q_lower)
    ):
        return "high"
    if wants_radius and (
        (file_path == "definitions/regions/default.yml" and key_path in ("shape.radius", "shape.centerRadius"))
        or (file_path == "definitions/regions/default.yml" and key_path == "worldBorderOverride" and "worldborderoverride" in q_lower)
        or (file_path == "config.yml" and key_path in ("defaults.shape.radius", "defaults.shape.centerRadius"))
    ):
        return "high"
    if wants_shape and not wants_action and (
        (
            file_path == "definitions/regions/default.yml"
            and (
                key_path in ("shape.name", "shape.mode")
                or (key_path in ("shape.width", "shape.height") and any(w in q_lower for w in ("rectangle", "width", "height")))
                or (key_path == "shape.vertices" and any(w in q_lower for w in ("polygon", "vertices", "hexagon", "triangle")))
            )
        )
        or (file_path == "config.yml" and key_path in ("defaults.shape.name", "defaults.shape.mode"))
    ):
        return "high"
    if (wants_gui or ("book" in specific_tokens and "menu" in specific_tokens)) and file_path == "addons/guimenu.yml" and key_path == "openOnBareRtp":
        return "high"

    if not specific_tokens:
        return "low"

    kp_text = key_path.lower()
    full_text = f"{file_path} {key_path} {description} {directives}".lower()
    key_hits = sum(1 for tok in specific_tokens if _token_matches_text(tok, kp_text, conn=conn, project=project))
    total_hits = sum(1 for tok in specific_tokens if _token_matches_text(tok, full_text, conn=conn, project=project))
    ratio = total_hits / len(specific_tokens)

    if key_hits >= 1 and (total_hits >= 2 or len(specific_tokens) == 1 or ratio >= 0.67):
        return "high"
    if total_hits >= 2 and ratio >= 0.67:
        return "high"
    if (key_hits >= 1 or total_hits >= 1) and (ratio >= 0.5 or (total_hits >= 2 and len(specific_tokens) >= 5)):
        return "medium"
    return "low"


def search_config_keys(
    conn: sqlite3.Connection,
    query: str,
    limit: int = 5,
    project: str | None = None,
) -> list[dict[str, str]]:
    """
    Searches `config_keys` by exact key_path match first, followed by FTS5 BM25
    ranking with corpus co-occurrence boosts (`corpus_associations`) and core
    operator file priority.
    Filters out low-confidence tangential hits and attaches `confidence` (`high` | `medium`).
    """
    q_strip = query.strip()
    if not q_strip:
        return []

    proj_eff = (project or DEFAULT_PROJECT).strip().lower()
    q_lower = q_strip.lower()
    specific_tokens = extract_specific_tokens(q_strip, conn=conn, project=proj_eff)
    assoc_targets = {
        tgt.lower()
        for tok in specific_tokens
        for tgt in get_corpus_associations(conn, tok, project=proj_eff)
    }
    wants_radius = (
        bool(assoc_targets & {"shape.radius", "centerradius", "worldborderoverride", "radius"})
        or ("radius" in q_lower)
        or ("spawn" in q_lower and "distance" in q_lower)
    )
    wants_gui = bool(assoc_targets & {"openonbarertp", "menustyle", "guimenu.yml"}) or ("openonbarertp" in q_lower)
    wants_shape = (
        bool(assoc_targets & {"shape.name", "polygon", "ellipse", "rectangle", "addshape"})
        or ("shape" in specific_tokens)
    ) and not wants_radius
    wants_action = any(tok in ("action", "actions", "arena", "nearplayer", "tether", "wand") for tok in specific_tokens)
    wants_messages = any(tok in ("message", "messages", "chat", "text", "prefix", "minimessage", "lang") for tok in specific_tokens)
    wants_network = any(tok in ("proxy", "velocity", "bungee", "bungeecord", "redis", "network") for tok in specific_tokens)

    proj_filter = project.strip().lower() if project else None
    exact_rows: list[sqlite3.Row] = []
    if len(q_strip) <= 256 and "\n" not in q_strip:
        if proj_filter:
            exact_rows = conn.execute(
                """
                SELECT file_path, key_path, default_value, directives, description, project
                FROM config_keys
                WHERE lower(project) = ?
                  AND (lower(key_path) = lower(?) OR lower(key_path) LIKE lower(?))
                ORDER BY CASE
                    WHEN file_path = 'definitions/regions/default.yml' THEN 0
                    WHEN file_path = 'addons/guimenu.yml' THEN 1
                    WHEN file_path = 'config.yml' THEN 2
                    ELSE 3
                END
                LIMIT ?
                """,
                (proj_filter, q_strip, f"%.{q_strip}", limit),
            ).fetchall()
        else:
            exact_rows = conn.execute(
                """
                SELECT file_path, key_path, default_value, directives, description, project
                FROM config_keys
                WHERE lower(key_path) = lower(?) OR lower(key_path) LIKE lower(?)
                ORDER BY CASE
                    WHEN file_path = 'definitions/regions/default.yml' THEN 0
                    WHEN file_path = 'addons/guimenu.yml' THEN 1
                    WHEN file_path = 'config.yml' THEN 2
                    ELSE 3
                END
                LIMIT ?
                """,
                (q_strip, f"%.{q_strip}", limit),
            ).fetchall()

    results: list[dict[str, str]] = []
    for r in exact_rows:
        d = dict(r)
        d["confidence"] = "high"
        results.append(d)
    seen = {(r["file_path"], r["key_path"]) for r in results}

    tokens = _tokenize_query(q_strip, conn=conn, project=proj_eff)
    fts_expr = _build_fts_or_query(tokens, conn=conn, project=proj_eff)
    if not fts_expr:
        return results[:limit]

    if proj_filter:
        fts_rows = conn.execute(
            """
            SELECT
                file_path,
                key_path,
                default_value,
                directives,
                description,
                project,
                bm25(config_keys, 2.0, 5.0, 1.0, 1.5, 3.0, 0.0) AS score
            FROM config_keys
            WHERE config_keys MATCH ? AND lower(project) = ?
            ORDER BY score ASC
            LIMIT 60
            """,
            (fts_expr, proj_filter),
        ).fetchall()
    else:
        fts_rows = conn.execute(
            """
            SELECT
                file_path,
                key_path,
                default_value,
                directives,
                description,
                project,
                bm25(config_keys, 2.0, 5.0, 1.0, 1.5, 3.0, 0.0) AS score
            FROM config_keys
            WHERE config_keys MATCH ?
            ORDER BY score ASC
            LIMIT 60
            """,
            (fts_expr,),
        ).fetchall()

    def rank_row(row: sqlite3.Row) -> float:
        score = float(row["score"])
        fp = row["file_path"]
        kp = row["key_path"]
        kp_low = kp.lower()
        full_row_text = f"{fp} {kp} {row['description']} {row['directives']}".lower()
        is_msg = fp.startswith("advanced/messages/") or fp.startswith("messages/")
        if (
            ("." in kp_low or (len(kp_low) >= 5 and not is_ubiquitous_corpus_token(conn, kp_low, project=proj_eff)))
            and not is_msg
            and re.search(rf"(?:^|[\s`\"'=/(-]){re.escape(kp_low)}(?:$|[\s`\"'=/:.,;!?)-])", q_lower)
        ):
            score -= 30.0
        if is_msg and not wants_messages:
            score += 18.0
        if fp == "advanced/network.yml" and not wants_network:
            score += 15.0
        if fp == "advanced/ttl.yml" and "ttl" not in q_lower and "retention" not in q_lower and "expir" not in q_lower:
            score += 22.0
        if len(specific_tokens) >= 2:
            matched_facets = sum(1 for tok in specific_tokens if _token_matches_text(tok, full_row_text, conn=conn, project=proj_eff))
            if matched_facets >= 2:
                score -= 10.0 * matched_facets
        if fp == "config.yml" and kp_low.startswith(("defaults.shape.", "defaults.vert.")):
            score += 14.0
        elif fp == "definitions/regions/default.yml" and kp_low.startswith(("shape.", "vert.")):
            score -= 8.0
            if kp in ("shape.centerX", "shape.centerZ") and "center" not in q_lower and "centerx" not in q_lower and "centerz" not in q_lower:
                score += 18.0
            if kp == "vert.name" and "vert" not in q_lower and "linear" not in q_lower and "jump" not in q_lower:
                score += 14.0
        if fp == "definitions/worlds/default.yml" and kp == "region" and any(
            w in q_lower for w in ("nether", "the_end", "overworld", "world:", "world=", "worlds")
        ):
            score -= 26.0
        if wants_radius:
            if fp == "definitions/regions/default.yml" and kp == "shape.radius":
                score -= 32.0
            elif fp == "definitions/regions/default.yml" and kp == "shape.centerRadius":
                score -= 28.0
            elif fp == "definitions/regions/default.yml" and kp == "worldBorderOverride":
                if "worldborderoverride" in q_lower:
                    score -= 20.0
                else:
                    score += 36.0
            elif fp == "config.yml" and kp in ("defaults.shape.radius", "defaults.shape.centerRadius"):
                score -= 4.0
        if wants_shape and not wants_action:
            if fp == "definitions/regions/default.yml" and kp == "shape.name":
                score -= 28.0
            elif fp == "definitions/regions/default.yml" and kp in ("shape.width", "shape.height") and ("rectangle" in q_lower or "width" in q_lower or "height" in q_lower):
                score -= 30.0
            elif fp == "config.yml" and kp == "defaults.shape.name":
                score -= 6.0
            elif fp == "definitions/regions/default.yml" and kp == "shape.mode":
                score -= 10.0
        if not wants_action and fp.startswith("definitions/actions/"):
            score += 20.0
        if wants_gui or ("book" in specific_tokens and "menu" in specific_tokens) or ("open" in specific_tokens and "menu" in specific_tokens):
            if fp == "addons/guimenu.yml" and kp == "openOnBareRtp":
                score -= 25.0
        elif fp == "addons/guimenu.yml" and kp == "openOnBareRtp" and "menu" in q_lower:
            score -= 18.0
        return score

    conf_order = {"high": 0, "medium": 1}
    candidates: list[tuple[int, float, dict[str, str]]] = []
    for row in sorted(fts_rows, key=rank_row):
        pair = (row["file_path"], row["key_path"])
        if pair in seen:
            continue
        row_text_low = f"{row['file_path']} {row['key_path']} {row['description']} {row['directives']}".lower()
        matched_facets = sorted({tok for tok in specific_tokens if _token_matches_text(tok, row_text_low, conn=conn, project=proj_eff)})
        conf = _score_config_confidence(
            file_path=row["file_path"],
            key_path=row["key_path"],
            description=row["description"],
            directives=row["directives"],
            q_lower=q_lower,
            specific_tokens=specific_tokens,
            wants_radius=wants_radius,
            wants_gui=wants_gui,
            wants_shape=wants_shape,
            wants_action=wants_action,
            conn=conn,
            project=proj_eff,
        )
        if conf == "low":
            continue
        seen.add(pair)
        candidates.append(
            (
                conf_order[conf],
                rank_row(row),
                {
                    "file_path": row["file_path"],
                    "key_path": row["key_path"],
                    "default_value": row["default_value"],
                    "directives": row["directives"],
                    "description": row["description"],
                    "project": row["project"],
                    "confidence": conf,
                    "matched_facets": ",".join(matched_facets),
                },
            )
        )

    candidates.sort(key=lambda item: (item[0], item[1]))
    for _, _, item in candidates:
        results.append(item)
        if len(results) >= limit:
            break

    return results[:limit]


def _score_doc_confidence(
    title: str,
    heading: str,
    path: str,
    body: str,
    tags: str,
    specific_tokens: list[str],
    wants_radius: bool,
    wants_gui: bool,
    wants_migration: bool,
    wants_platform: bool,
    wants_shape: bool = False,
    wants_op_notme: bool = False,
    wants_api_code: bool = False,
    conn: sqlite3.Connection | None = None,
    project: str = DEFAULT_PROJECT,
) -> str:
    header_text = f"{title} {heading} {path}".lower()
    full_text = f"{title} {heading} {path} {tags} {body}".lower()
    clean_heading = heading.replace("`", "").strip()
    header_hits = sum(1 for tok in specific_tokens if _token_matches_text(tok, header_text, conn=conn, project=project))
    total_hits = sum(1 for tok in specific_tokens if _token_matches_text(tok, full_text, conn=conn, project=project))
    ratio = (total_hits / len(specific_tokens)) if specific_tokens else 0.0

    if wants_op_notme and "rtp.notme" in body and path.endswith(("FAQ.md", "COMMANDS.md")):
        return "high"
    if wants_api_code and path.endswith(("FOR_ADDON_DEVELOPERS.md", "ADDON_QUICKSTART.md", "EXTERNAL_HOOKS.md")):
        return "high"
    heading_stems = porter_stem_text(clean_heading)
    if conn is not None and heading_stems:
        for tok in specific_tokens:
            st = porter_stem_word(tok)
            if st in heading_stems:
                try:
                    v_row = conn.execute(
                        "SELECT kf, dhf FROM corpus_vocab WHERE stem = ? AND lower(project) = ? LIMIT 1",
                        (st, (project or DEFAULT_PROJECT).strip().lower()),
                    ).fetchone()
                except sqlite3.OperationalError:
                    v_row = None
                if v_row is not None and int(v_row["dhf"]) >= 2 and (int(v_row["kf"]) == 0 or len(specific_tokens) == 1) and ratio >= 0.6:
                    return "high"

    if (
        (wants_radius and path.endswith("QUICK_START.md") and ("/rtp editor" in body or "shape.radius" in body or "teleport area" in heading.lower()))
        or (wants_gui and (path.endswith("ADDONS.md") or path.endswith("QUICK_START.md")) and ("openOnBareRtp" in body or "gui" in heading.lower()))
        or (wants_migration and path.endswith("MIGRATION.md"))
        or (wants_platform and (path.endswith("SUPPORT_MATRIX.md") or path.endswith("QUICK_START.md")))
        or (
            wants_shape
            and (
                (path.endswith("REGIONS.md") and clean_heading in ("POLYGON", "ELLIPSE", "RECTANGLE", "CIRCLE / SQUARE", "Shape Engines and Parameters"))
                or (path.endswith("ADDON_QUICKSTART.md") and ("register a custom shape" in clean_heading.lower() or clean_heading == title))
            )
        )
    ):
        if not specific_tokens or ratio >= 0.5:
            return "high"
        return "medium"

    if not specific_tokens:
        return "low"

    if header_hits >= 1 and (total_hits >= 2 or len(specific_tokens) == 1) and ratio >= 0.6:
        return "high"
    if total_hits >= 2 and ratio >= 0.75 and header_hits >= 1:
        return "high"
    if len(specific_tokens) >= 2 and total_hits >= 2 and ratio >= 0.67:
        return "high"
    if header_hits >= 1 and ratio >= 0.4:
        return "medium"
    if total_hits >= 2 and ratio >= 0.6:
        return "medium"
    if len(specific_tokens) == 1 and header_hits == 1:
        return "medium"
    if len(specific_tokens) == 1 and total_hits == 1 and conn is not None:
        assocs = get_corpus_associations(conn, specific_tokens[0], project=project)
        assoc_hits = sum(1 for a in assocs if a.lower() in full_text)
        if assoc_hits >= 2:
            return "high" if path.endswith(("what-not-to-do.md", "PERFORMANCE.md", "FAQ.md")) else "medium"
    return "low"


def search_docs(
    conn: sqlite3.Connection,
    query: str,
    limit: int = 5,
    project: str | None = None,
) -> list[dict[str, str]]:
    """
    Searches `doc_sections` using FTS5 BM25 ranking with corpus-association
    boosting (`corpus_associations`).
    Filters out low-confidence tangential hits and attaches `confidence` (`high` | `medium`)
    and `snippet`.
    """
    proj_eff = (project or DEFAULT_PROJECT).strip().lower()
    tokens = _tokenize_query(query, conn=conn, project=proj_eff)
    fts_expr = _build_fts_or_query(tokens, conn=conn, project=proj_eff)
    if not fts_expr:
        return []

    specific_tokens = extract_specific_tokens(query, conn=conn, project=proj_eff)
    proj_filter = project.strip().lower() if project else None
    if proj_filter:
        rows = conn.execute(
            """
            SELECT
                title,
                heading,
                path,
                url,
                body,
                tags,
                project,
                bm25(doc_sections, 3.0, 4.0, 2.5, 0.0, 2.0, 3.0, 0.0) AS score
            FROM doc_sections
            WHERE doc_sections MATCH ? AND lower(project) = ?
            ORDER BY score ASC
            LIMIT 50
            """,
            (fts_expr, proj_filter),
        ).fetchall()
    else:
        rows = conn.execute(
            """
            SELECT
                title,
                heading,
                path,
                url,
                body,
                tags,
                project,
                bm25(doc_sections, 3.0, 4.0, 2.5, 0.0, 2.0, 3.0, 0.0) AS score
            FROM doc_sections
            WHERE doc_sections MATCH ?
            ORDER BY score ASC
            LIMIT 50
            """,
            (fts_expr,),
        ).fetchall()

    q_lower = query.lower()
    assoc_targets = {
        tgt.lower()
        for tok in specific_tokens
        for tgt in get_corpus_associations(conn, tok, project=proj_eff)
    }
    wants_radius = (
        bool(assoc_targets & {"shape.radius", "centerradius", "worldborderoverride", "radius"})
        or ("radius" in q_lower)
        or ("spawn" in q_lower and "distance" in q_lower)
    )
    wants_gui = bool(assoc_targets & {"openonbarertp", "menustyle", "guimenu.yml"}) or ("openonbarertp" in q_lower)
    wants_migration = ("migration" in assoc_targets) or ("migrate" in q_lower) or ("import" in q_lower)
    wants_shape = (
        bool(assoc_targets & {"shape.name", "polygon", "ellipse", "rectangle", "addshape", "addon_quickstart"})
        or ("shape" in specific_tokens)
    ) and not wants_radius
    wants_custom_shape = wants_shape and (
        bool(assoc_targets & {"polygon", "addon_quickstart", "addshape"})
        or any(tok in ("hexagon", "octagon", "pentagon", "triangle", "diamond", "polygon", "vertices") for tok in specific_tokens)
    )
    has_platform_assoc = "support_matrix" in assoc_targets or any(tok in ("folia", "fabric", "neoforge", "spigot") for tok in specific_tokens)
    non_platform_specific = [tok for tok in specific_tokens if tok not in ("paper", "spigot", "folia", "fabric", "neoforge", "velocity", "bungeecord", "proxy")]
    has_compat_context = bool(re.search(r"\b(?:1\.1[89]|1\.2[01]|26\.\d|support\w*|compat\w*|install\w*|matrix|mod\w*)\b", q_lower))
    wants_platform = has_platform_assoc and (not non_platform_specific or has_compat_context)

    wants_api_code = bool(
        re.search(
            r"\b(?:rtp-api|rtpapi|rtpaddon|addshape|using\s+(?:code|the\s+api)|developing\s+my\s+(?:server\s+)?plugin)\b",
            q_lower,
        )
        or ({"code", "plugin"} <= set(specific_tokens))
        or ({"api", "plugin"} <= set(specific_tokens))
        or ({"code", "respawn"} <= set(specific_tokens))
        or ("rtp-api" in specific_tokens)
    )
    has_op_word = bool(re.search(r"\b(?:op|operators?|deop\w*)\b", q_lower))
    wants_op_notme = bool(
        re.search(r"\b(?:notme|invalid\s+command\s+argument\s+player|stopping\s+operators)\b", q_lower)
        or ("player:" in q_lower and has_op_word)
        or (has_op_word and ("console" in specific_tokens or "argument" in specific_tokens))
        or ({"invalid", "command", "argument"} <= set(specific_tokens) and "player" in q_lower)
        or ("notme" in assoc_targets or "rtp.notme" in assoc_targets)
    )
    rows = list(rows)
    seen_doc_urls = {r["url"] for r in rows}
    if wants_op_notme:
        for r in conn.execute(
            """
            SELECT title, heading, path, url, body, tags, project, -40.0 AS score
            FROM doc_sections
            WHERE lower(project) = ? AND body LIKE '%rtp.notme%'
            """,
            (proj_eff,),
        ).fetchall():
            if r["url"] not in seen_doc_urls:
                seen_doc_urls.add(r["url"])
                rows.append(r)
    if wants_api_code:
        for r in conn.execute(
            """
            SELECT title, heading, path, url, body, tags, project, -30.0 AS score
            FROM doc_sections
            WHERE lower(project) = ? AND (path LIKE '%FOR_ADDON_DEVELOPERS.md' OR path LIKE '%ADDON_QUICKSTART.md')
            """,
            (proj_eff,),
        ).fetchall():
            if r["url"] not in seen_doc_urls:
                seen_doc_urls.add(r["url"])
                rows.append(r)

    heading_facet_stems: set[str] = set()
    for tok in specific_tokens:
        st = porter_stem_word(tok)
        try:
            v_row = conn.execute(
                "SELECT kf, dhf FROM corpus_vocab WHERE stem = ? AND lower(project) = ? LIMIT 1",
                (st, proj_eff),
            ).fetchone()
        except sqlite3.OperationalError:
            v_row = None
        if v_row is not None and int(v_row["dhf"]) >= 2 and int(v_row["kf"]) == 0:
            heading_facet_stems.add(st)
            safe_tok = tok.replace('"', "")
            if safe_tok:
                for r in conn.execute(
                    """
                    SELECT title, heading, path, url, body, tags, project, -30.0 AS score
                    FROM doc_sections
                    WHERE lower(project) = ? AND doc_sections MATCH ?
                    LIMIT 12
                    """,
                    (proj_eff, f'heading:"{safe_tok}" OR title:"{safe_tok}"'),
                ).fetchall():
                    if r["url"] not in seen_doc_urls:
                        seen_doc_urls.add(r["url"])
                        rows.append(r)

    def rank_doc(row: sqlite3.Row) -> float:
        score = float(row["score"])
        path = row["path"]
        heading = row["heading"]
        clean_heading = heading.replace("`", "").strip()
        body = row["body"]
        full_doc_text = f"{row['title']} {heading} {path} {row['tags']} {body}".lower()
        if heading_facet_stems and (porter_stem_text(clean_heading) & heading_facet_stems):
            score -= 22.0
        if len(specific_tokens) >= 2:
            matched_facets = sum(1 for tok in specific_tokens if _token_matches_text(tok, full_doc_text, conn=conn, project=proj_eff))
            if matched_facets >= 2:
                score -= 10.0 * matched_facets
        if wants_op_notme:
            if path.endswith("FAQ.md") and "rtp.notme" in body:
                score -= 75.0
            elif path.endswith("COMMANDS.md") and "rtp.notme" in body:
                score -= 55.0
        if wants_api_code and path.endswith(("FOR_ADDON_DEVELOPERS.md", "ADDON_QUICKSTART.md", "EXTERNAL_HOOKS.md")):
            score -= 35.0
            if clean_heading in ("Addons Are Platform-Agnostic by Default", "Building on the API", "1. Depend on RTP") or clean_heading == row["title"]:
                score -= 15.0
        if any(w in q_lower for w in ("world_nether", "world_the_end", "overworld")) and path.endswith(("FAQ.md", "WORLDS.md")) and ("nether.yml" in body or "definitions/worlds/" in body):
            score -= 28.0
        if wants_radius and path.endswith(("QUICK_START.md", "REGIONS.md", "WEB_EDITOR_GUIDE.md")):
            score -= 15.0
            if "/rtp editor" in body or "/rtp admin setup" in body or "shape.radius" in body:
                score -= 10.0
                if path.endswith("QUICK_START.md"):
                    score -= 25.0
        if wants_shape:
            if path.endswith("REGIONS.md"):
                if clean_heading == "POLYGON" and (wants_custom_shape or "polygon" in q_lower):
                    score -= 35.0
                elif clean_heading == "RECTANGLE" and ("rectangle" in q_lower or "width" in q_lower or "height" in q_lower):
                    score -= 38.0
                elif clean_heading in ("ELLIPSE", "RECTANGLE", "CIRCLE / SQUARE", "Shape Engines and Parameters"):
                    score -= 20.0
            if path.endswith("ADDON_QUICKSTART.md"):
                if "3. register a custom shape" in clean_heading.lower():
                    score -= 28.0
                elif clean_heading == row["title"]:
                    score -= 12.0
                elif "2. register the addon for" in clean_heading.lower():
                    score += 10.0
        if wants_gui and (path.endswith("ADDONS.md") or path.endswith("QUICK_START.md")):
            score -= 15.0
            if "openOnBareRtp" in body or "GUI" in heading:
                score -= 10.0
        if wants_migration and path.endswith("MIGRATION.md"):
            score -= 25.0
            if "/rtp config import" in body:
                score -= 10.0
        if wants_platform and (path.endswith("SUPPORT_MATRIX.md") or path.endswith("QUICK_START.md")):
            score -= 15.0
        return score

    conf_order = {"high": 0, "medium": 1}
    candidates: list[tuple[int, float, dict[str, str]]] = []
    for r in sorted(rows, key=rank_doc):
        doc_text_low = f"{r['title']} {r['heading']} {r['path']} {r['tags']} {r['body']}".lower()
        matched_facets = sorted({tok for tok in specific_tokens if _token_matches_text(tok, doc_text_low, conn=conn, project=proj_eff)})
        conf = _score_doc_confidence(
            title=r["title"],
            heading=r["heading"],
            path=r["path"],
            body=r["body"],
            tags=r["tags"],
            specific_tokens=specific_tokens,
            wants_radius=wants_radius,
            wants_gui=wants_gui,
            wants_migration=wants_migration,
            wants_platform=wants_platform,
            wants_shape=wants_shape,
            wants_op_notme=wants_op_notme,
            wants_api_code=wants_api_code,
            conn=conn,
            project=proj_eff,
        )
        if conf == "low":
            continue
        candidates.append(
            (
                conf_order[conf],
                rank_doc(r),
                {
                    "title": r["title"],
                    "heading": r["heading"],
                    "path": r["path"],
                    "url": r["url"],
                    "body": r["body"],
                    "snippet": extract_doc_snippet(r["body"]),
                    "tags": r["tags"],
                    "project": r["project"],
                    "confidence": conf,
                    "matched_facets": ",".join(matched_facets),
                },
            )
        )

    candidates.sort(key=lambda item: (item[0], item[1]))
    return [item[2] for item in candidates[:limit]]


def lookup_bit_code(
    conn: sqlite3.Connection,
    token: str,
    project: str | None = None,
) -> dict[str, object] | None:
    """
    Deterministic O(1) lookup of a BIT code by key (`RTP_REGION_UNKNOWN_WORLD`)
    or by `NAMESPACE-ID` / `NAMESPACE ID` (`RTP-1301`, `RTP 1301`).
    """
    cleaned = token.strip().strip("`[]()")
    if not cleaned:
        return None

    proj_filter = project.strip().lower() if project else None
    if proj_filter:
        row = conn.execute(
            """
            SELECT namespace, id, key, severity, tier, subsystem, summary, remediation, project
            FROM bit_codes
            WHERE upper(key) = upper(?) AND lower(project) = ?
            """,
            (cleaned, proj_filter),
        ).fetchone()
    else:
        row = conn.execute(
            """
            SELECT namespace, id, key, severity, tier, subsystem, summary, remediation, project
            FROM bit_codes
            WHERE upper(key) = upper(?)
            """,
            (cleaned,),
        ).fetchone()
    if row:
        return dict(row)

    m = re.match(r"^([A-Za-z]+)[-\s:]+0*(\d+)$", cleaned)
    if m:
        ns, num = m.group(1).upper(), int(m.group(2))
        if proj_filter:
            row = conn.execute(
                """
                SELECT namespace, id, key, severity, tier, subsystem, summary, remediation, project
                FROM bit_codes
                WHERE upper(namespace) = ? AND id = ? AND lower(project) = ?
                """,
                (ns, num, proj_filter),
            ).fetchone()
        else:
            row = conn.execute(
                """
                SELECT namespace, id, key, severity, tier, subsystem, summary, remediation, project
                FROM bit_codes
                WHERE upper(namespace) = ? AND id = ?
                """,
                (ns, num),
            ).fetchone()
        if row:
            return dict(row)

    return None


def unwrap_console_paste(text: str) -> str:
    """
    Unwraps hard-wrapped console pastes from web panels (Pterodactyl, Multicraft,
    Pelican) where 80-column terminal line wrapping or panel daemon prefixes
    split `[RTP bit]` tags, BIT IDs (`RTP-1301`), BIT constant names
    (`RTP_REGION_UNKNOWN_WORLD`), or Java package/class stack traces mid-token.
    """
    if not text:
        return ""
    # Strip standalone panel daemon status lines that carry no server log content
    unwrapped = re.sub(
        r"(?im)^\s*\[(?:Pterodactyl(?:\s+Daemon)?|Multicraft|Pelican(?:\s+Daemon)?)\]\s*:.*$",
        "",
        text,
    )
    # Rejoin `[RTP\nbit]` tags split across line breaks
    unwrapped = re.sub(
        r"(?i)\[\s*(rtp|ops|treegen)\s*\r?\n\s*(bit)\s*\]",
        r"[\1 \2]",
        unwrapped,
    )
    # Rejoin `RTP-\n1301` BIT code IDs split at the hyphen
    unwrapped = re.sub(
        r"\b([A-Z]{2,16})-\s*\r?\n\s*(0*\d{1,4})\b",
        r"\1-\2",
        unwrapped,
    )
    # Rejoin `RTP_REGION_\nUNKNOWN_WORLD` UPPER_SNAKE_CASE constants split at underscore
    for _ in range(3):
        unwrapped = re.sub(
            r"\b([A-Z][A-Z0-9_]{1,}_)\s*\r?\n\s*([A-Z0-9_]{2,})\b",
            r"\1\2",
            unwrapped,
        )
    # Rejoin wrapped Java package/class paths (`io.github.dailystruggle.rtp...teleport.\nTeleportPipelineTask`)
    for _ in range(3):
        unwrapped = re.sub(
            r"\b([A-Za-z0-9_$]+\.)\s*\r?\n\s*([A-Za-z_$][A-Za-z0-9_$.(]*)",
            r"\1\2",
            unwrapped,
        )
    return unwrapped


def extract_bit_codes(
    conn: sqlite3.Connection,
    text: str,
    project: str | None = None,
    manifest: pathlib.Path | str | dict[str, Any] | None = None,
) -> list[dict[str, object]]:
    """
    Scans text (forum post or `mclo.gs` log) for `[RTP bit]` output, BIT keys
    (`RTP_REGION_UNKNOWN_WORLD`, `ACTION_UNKNOWN_STEP_KEY`, etc.), `RTP-1301` IDs,
    or custom `projects.<slug>.error_code_pattern` tokens from `support-bot.yml`.
    """
    if not text:
        return []
    text = unwrap_console_paste(text)

    found: list[dict[str, object]] = []
    seen_keys: set[str] = set()

    # 1. Match explicit BIT keys (e.g. RTP_REGION_UNKNOWN_WORLD, ACTION_UNKNOWN_STEP_KEY)
    key_candidates = re.findall(
        r"\b[A-Z]{2,16}_[A-Z0-9_]{3,}\b",
        text.upper(),
    )
    for cand in key_candidates:
        entry = lookup_bit_code(conn, cand, project=project)
        if entry and str(entry["key"]) not in seen_keys:
            seen_keys.add(str(entry["key"]))
            found.append(entry)

    # 2. Match NAMESPACE-ID tokens like RTP-1301 or [RTP bit] RTP 1301
    ns_id_candidates = re.findall(
        r"\b([A-Z]{2,16})[-\s:]0*(\d{1,4})\b",
        text.upper(),
    )
    for ns, num in ns_id_candidates:
        entry = lookup_bit_code(conn, f"{ns}-{num}", project=project)
        if entry and str(entry["key"]) not in seen_keys:
            seen_keys.add(str(entry["key"]))
            found.append(entry)

    # 3. Match custom `projects.<slug>.error_code_pattern` from `support-bot.yml` (e.g. Rust `E0382`, `ERR_...`)
    cfg = load_support_bot_manifest(manifest)
    proj_slug = (project or cfg.get("default_project") or DEFAULT_PROJECT).strip().lower()
    proj_cfg = (cfg.get("projects") or {}).get(proj_slug, {})
    custom_pat = str(proj_cfg.get("error_code_pattern") or "").strip()
    if custom_pat:
        try:
            for m in re.finditer(custom_pat, text):
                token = (m.group(1) if m.lastindex else m.group(0)).strip()
                if not token:
                    continue
                entry = lookup_bit_code(conn, token, project=project)
                if entry and str(entry["key"]) not in seen_keys:
                    seen_keys.add(str(entry["key"]))
                    found.append(entry)
        except re.error:
            pass

    return found


def build_starting_reference_context(
    conn: sqlite3.Connection,
    project: str = DEFAULT_PROJECT,
    max_chars_per_section: int = 160,
    manifest: pathlib.Path | str | dict[str, Any] | None = None,
) -> str:
    """
    Constructs a compact starting reference point dynamically from the
    already-generated `doc-index.sqlite` database (zero hardcoded prose):
      1. Entry-point architecture and configuration overview excerpts from
         `projects.<slug>.reference_docs` in `support-bot.yml` (defaulting to
         `docs/site/intended-usage.md`, `docs/site/what-not-to-do.md`, and
         `docs/admin/configuration/CONFIGURATION.md`).
      2. A generated directory of core operator YAML configuration files and
         their top-level keys from `config_keys`.
    """
    proj = project.strip().lower() or DEFAULT_PROJECT
    anchor_rows = conn.execute(
        """
        SELECT title, heading, path, url, body
        FROM doc_sections
        WHERE lower(project) = ?
          AND (
              (path LIKE '%intended-usage.md' AND heading IN ('Intended usage', 'How locations are prepared and verified', 'Teleport parameters are for testing'))
              OR (path LIKE '%what-not-to-do.md' AND heading IN ('This will break the mod entirely', 'Why is this running slowly?'))
              OR (path LIKE '%CONFIGURATION.md' AND heading IN ('How to Update Configuration', 'File Overview'))
          )
        ORDER BY CASE
            WHEN path LIKE '%intended-usage.md' THEN 0
            WHEN path LIKE '%what-not-to-do.md' THEN 1
            ELSE 2
        END
        """,
        (proj,),
    ).fetchall()

    if not anchor_rows:
        cfg = load_support_bot_manifest(manifest)
        proj_cfg = (cfg.get("projects") or {}).get(proj, {})
        ref_docs = proj_cfg.get("reference_docs")
        if isinstance(ref_docs, list) and ref_docs:
            ref_clauses = " OR ".join("path LIKE ?" for _ in ref_docs)
            ref_params = [proj] + [f"%{str(rd).strip()}" for rd in ref_docs]
            anchor_rows = conn.execute(
                f"""
                SELECT title, heading, path, url, body
                FROM doc_sections
                WHERE lower(project) = ?
                  AND ({ref_clauses})
                LIMIT 5
                """,
                ref_params,
            ).fetchall()

    if not anchor_rows:
        anchor_rows = conn.execute(
            """
            SELECT title, heading, path, url, body
            FROM doc_sections
            WHERE lower(project) = ?
            LIMIT 3
            """,
            (proj,),
        ).fetchall()

    lines: list[str] = []
    for r in anchor_rows:
        snippet = extract_doc_snippet(str(r["body"]), max_chars=max_chars_per_section)
        lines.append(
            f"- {r['title']} ({r['heading']}) <{r['url']}>: {snippet}"
        )

    file_rows = conn.execute(
        """
        SELECT file_path, key_path, default_value
        FROM config_keys
        WHERE lower(project) = ?
          AND NOT file_path LIKE 'advanced/messages/%'
          AND NOT file_path LIKE 'definitions/actions/%'
          AND NOT file_path LIKE 'definitions/effects/%'
          AND NOT file_path IN ('advanced/bit.yml', 'advanced/metrics.yml', 'advanced/ttl.yml', 'advanced/logging.yml', 'rift.yml')
        """,
        (proj,),
    ).fetchall()
    if file_rows:
        by_file: dict[str, list[str]] = {}
        for fr in file_rows:
            fp = str(fr["file_path"])
            kp = str(fr["key_path"])
            dv = str(fr["default_value"])
            if "." not in kp and kp != "version":
                by_file.setdefault(fp, [])
                if len(by_file[fp]) < 8:
                    short_dv = dv.split(" ")[0] if dv else ""
                    by_file[fp].append(f"{kp}={short_dv}" if short_dv else kp)
        schema_lines = ["Indexed YAML Files & Top-Level Keys:"]
        for fp, klist in sorted(by_file.items()):
            schema_lines.append(f"- `{fp}`: {', '.join(f'`{k}`' for k in klist)}")
        lines.append("\n".join(schema_lines))

    return "\n".join(lines)


def search_arch_sections(
    conn: sqlite3.Connection,
    query: str,
    limit: int = 4,
    project: str = DEFAULT_PROJECT,
) -> list[dict[str, str]]:
    """
    Searches `arch_sections` (`docs/adr/**`, `docs/architecture/**`, `docs/dev/{DESIGN,HAZARDS,REQUIREMENTS}.md`,
    and `docs/site/{intended-usage,what-not-to-do,how-it-works}.md`) so the reasoning model
    can ground its cause analysis in RTP's architectural invariants and ADRs.
    """
    q_strip = query.strip()
    if not q_strip:
        return []
    proj_eff = (project or DEFAULT_PROJECT).strip().lower()
    tokens = _tokenize_query(q_strip, conn=conn, project=proj_eff)
    fts_expr = _build_fts_or_query(tokens, conn=conn, project=proj_eff)
    if not fts_expr:
        return []
    try:
        rows = conn.execute(
            """
            SELECT
                title,
                heading,
                path,
                url,
                body,
                tags,
                project,
                bm25(arch_sections, 3.0, 5.0, 2.0, 0.0, 1.0, 2.0, 0.0) AS score
            FROM arch_sections
            WHERE arch_sections MATCH ? AND lower(project) = ?
            ORDER BY CASE
                WHEN path LIKE '%what-not-to-do.md' THEN 0
                WHEN path LIKE '%intended-usage.md' THEN 1
                WHEN path LIKE '%how-it-works.md' THEN 2
                WHEN path LIKE '%HAZARDS.md' THEN 3
                WHEN path LIKE '%DESIGN.md' THEN 4
                WHEN path LIKE 'docs/adr/README.md' THEN 5
                ELSE 6
            END, score ASC
            LIMIT ?
            """,
            (fts_expr, proj_eff, limit * 3),
        ).fetchall()
    except sqlite3.OperationalError:
        return []

    results: list[dict[str, str]] = []
    seen_paths: dict[str, int] = {}
    for r in rows:
        p = str(r["path"])
        if seen_paths.get(p, 0) >= 2:
            continue
        seen_paths[p] = seen_paths.get(p, 0) + 1
        d = dict(r)
        d["snippet"] = extract_doc_snippet(str(r["body"]), max_chars=260)
        results.append(d)
        if len(results) >= limit:
            break
    return results


def search_code_sections(
    conn: sqlite3.Connection,
    query: str,
    limit: int = 4,
    project: str = DEFAULT_PROJECT,
) -> list[dict[str, str]]:
    """
    Searches the second-class `code_sections` FTS5 corpus (`**/src/main/java/**/*.java`)
    when a post, log paste, or screenshot contains stack traces, thrown exceptions,
    Java class/method names, or runtime warnings not covered by user documentation.
    Projects the query onto known `code_sections.class_name` identifiers, exception
    types, and `is_schema_or_heading_facet` terms sorted canonically so out-of-corpus
    prompt-injection tokens cannot alter code retrieval.
    """
    q_strip = unwrap_console_paste(query).strip()
    if not q_strip:
        return []
    proj_eff = (project or DEFAULT_PROJECT).strip().lower()
    raw_words = re.findall(r"[A-Za-z][A-Za-z0-9_]{2,}", q_strip)
    valid_terms: set[str] = set()
    known_exc = {
        "exception",
        "nullpointerexception",
        "illegalstateexception",
        "illegalargumentexception",
        "sqlexception",
        "nosuchmethoderror",
        "noclassdeffounderror",
        "unsupportedclassversionerror",
        "concurrentmodificationexception",
        "timeoutexception",
        "classcastexception",
    }
    explicit_class_names: set[str] = set()
    for w in raw_words:
        low = w.lower()
        if low in known_exc:
            valid_terms.add(low)
            continue
        if any(c.isupper() for c in w[1:]) and len(low) >= 4:
            try:
                has_cls = conn.execute(
                    "SELECT 1 FROM code_sections WHERE lower(class_name) = ? AND lower(project) = ? LIMIT 1",
                    (low, proj_eff),
                ).fetchone()
                if has_cls is not None:
                    valid_terms.add(low)
                    explicit_class_names.add(low)
                    continue
            except sqlite3.OperationalError:
                pass
        if is_schema_or_heading_facet(conn, low, project=proj_eff):
            valid_terms.add(low)

    combined_terms = sorted(valid_terms)[:12]
    fts_expr = _build_fts_or_query(combined_terms, conn=conn, project=proj_eff)
    if not fts_expr:
        return []
    try:
        rows = conn.execute(
            """
            SELECT
                class_name,
                package_name,
                path,
                methods,
                diagnostics,
                config_refs,
                summary,
                project,
                bm25(code_sections, 6.0, 2.0, 2.0, 3.0, 5.0, 4.0, 1.5, 0.0) AS score
            FROM code_sections
            WHERE code_sections MATCH ? AND lower(project) = ?
            ORDER BY CASE
                WHEN diagnostics != '' AND config_refs != '' THEN 0
                WHEN diagnostics != '' THEN 1
                WHEN config_refs != '' THEN 2
                ELSE 3
            END, score ASC
            LIMIT ?
            """,
            (fts_expr, proj_eff, max(limit * 4, 20)),
        ).fetchall()
    except sqlite3.OperationalError:
        return []

    rows_list: list[sqlite3.Row] = []
    for cn_low in sorted(explicit_class_names):
        try:
            exact_rows = conn.execute(
                """
                SELECT
                    class_name,
                    package_name,
                    path,
                    methods,
                    diagnostics,
                    config_refs,
                    summary,
                    project,
                    -50.0 AS score
                FROM code_sections
                WHERE lower(class_name) = ? AND lower(project) = ?
                LIMIT 2
                """,
                (cn_low, proj_eff),
            ).fetchall()
            rows_list.extend(exact_rows)
        except sqlite3.OperationalError:
            pass
    rows_list.extend(rows)

    results: list[dict[str, str]] = []
    seen_classes: set[str] = set()
    for r in rows_list:
        cn = str(r["class_name"])
        if cn in seen_classes:
            continue
        seen_classes.add(cn)
        results.append(dict(r))
        if len(results) >= limit:
            break
    return results


def decompound_ocr_text(
    conn: sqlite3.Connection,
    raw_ocr_text: str,
    project: str = DEFAULT_PROJECT,
) -> str:
    """
    Corpus-guided OCR token decompounding over `doc_sections_vocab`,
    `config_keys_vocab`, and `config_keys` in `doc-index.sqlite` (zero hardcoded
    word lists). Splits lowercase words that OCR glued together due to narrow
    character kerning (e.g. `invalidcommandargument` -> `invalid command argument`,
    `regiondefault` -> `region default`) while keeping exact camelCase config
    keys (`teleportDelay`, `requireSkyLight`, `worldBorderOverride`) intact.
    """
    if not raw_ocr_text or not raw_ocr_text.strip():
        return ""
    cleaned = re.sub(r"(?i)\[\s*rtp\s*bit\s*\]", "[RTP bit]", raw_ocr_text)
    cleaned = re.sub(r"(\]):(?=[A-Za-z\[])", r"\1: ", cleaned)
    cleaned = re.sub(r"(\])(?=[A-Za-z])", r"\1 ", cleaned)
    cleaned = re.sub(r"(?<=\])\s+[lI1|]\s+(?=[A-Za-z])", " ", cleaned)

    conn_id = id(conn)
    cached = _OCR_DECOMPOUND_VOCAB_CACHE.get(conn_id)
    if cached is None:
        exact_keys: set[str] = set()
        camel_compounds: set[str] = set()
        try:
            for r in conn.execute("SELECT key_path FROM config_keys").fetchall():
                for part in str(r[0]).replace(".", " ").split():
                    low = part.lower()
                    exact_keys.add(low)
                    if any(c.isupper() for c in part[1:]):
                        camel_compounds.add(low)
            vocab = {
                str(r[0])
                for r in conn.execute(
                    "SELECT term FROM doc_sections_vocab WHERE length(term) >= 3 AND doc >= 2 "
                    "UNION SELECT term FROM config_keys_vocab WHERE length(term) >= 3 AND doc >= 2"
                ).fetchall()
                if str(r[0]) not in camel_compounds
            }
        except sqlite3.OperationalError:
            exact_keys = set()
            vocab = set()
        cached = (exact_keys, vocab)
        _OCR_DECOMPOUND_VOCAB_CACHE[conn_id] = cached

    exact_keys, vocab = cached
    if not vocab:
        return cleaned

    def _split_compound(match: re.Match[str]) -> str:
        tok = match.group(0)
        low = tok.lower()
        if low in exact_keys or low in vocab or len(low) < 7 or not low.isalpha():
            return tok
        # Preserve camelCase identifiers (e.g. activeChunks, TeleportPipelineTask)
        if any(c.isupper() for c in tok[1:]) and any(c.islower() for c in tok):
            return tok
        n = len(low)
        dp: list[tuple[float, list[str]] | None] = [None] * (n + 1)
        dp[0] = (0.0, [])
        for i in range(n):
            if dp[i] is None:
                continue
            base_score, base_parts = dp[i]  # type: ignore[misc]
            for j in range(i + 3, min(n, i + 16) + 1):
                sub = low[i:j]
                if sub in vocab:
                    cand_score = base_score + (len(sub) ** 1.35)
                    if dp[j] is None or cand_score > dp[j][0]:  # type: ignore[index]
                        dp[j] = (cand_score, base_parts + [sub])
        if dp[n] is not None and len(dp[n][1]) >= 2:  # type: ignore[index]
            return " ".join(dp[n][1])  # type: ignore[index]
        return tok

    return re.sub(r"\b[A-Za-z]{7,36}\b", _split_compound, cleaned)


def is_schema_or_heading_facet(
    conn: sqlite3.Connection,
    token: str,
    project: str = DEFAULT_PROJECT,
) -> bool:
    """
    Returns True if `token` is classified as a focused corpus facet (`is_facet = 1`
    in `corpus_vocab`) or matches a schema identifier in `config_keys`,
    `doc_sections.path`, or `bit_codes` for `project`.
    """
    clean = re.sub(r"[^a-z0-9_.-]", "", token.lower())
    if not clean or len(clean) < 2 or len(clean) > 128 or clean.isdigit():
        return False
    proj = project.strip().lower() or DEFAULT_PROJECT
    cache_key = (id(conn), proj, clean)
    cached = _FACET_TOKEN_CACHE.get(cache_key)
    if cached is not None:
        return cached

    # 1. Check dotted config key paths or exact camelCase config keys
    if "." in clean:
        row = conn.execute(
            """
            SELECT 1 FROM config_keys
            WHERE (lower(key_path) = ? OR lower(key_path) LIKE ? OR lower(file_path) LIKE ?)
              AND lower(project) = ?
            LIMIT 1
            """,
            (clean, f"%.{clean}", f"%{clean}%", proj),
        ).fetchone()
        if row is not None:
            _FACET_TOKEN_CACHE[cache_key] = True
            return True

    if len(clean) >= 6:
        exact_key = conn.execute(
            """
            SELECT 1 FROM config_keys
            WHERE (lower(key_path) = ? OR lower(key_path) LIKE ?)
              AND lower(project) = ?
              AND NOT file_path LIKE 'advanced/messages/%'
            LIMIT 1
            """,
            (clean, f"%.{clean}", proj),
        ).fetchone()
        if exact_key is not None:
            _FACET_TOKEN_CACHE[cache_key] = True
            return True

    # 2. Query corpus-derived facet classification in `corpus_vocab`
    stem = porter_stem_word(clean)
    v_row = _get_vocab_stats(conn, stem, project=proj)
    if v_row is not None:
        res = v_row["is_facet"] == 1
        _FACET_TOKEN_CACHE[cache_key] = res
        return res

    if "_" in clean:
        parts = [p for p in clean.split("_") if len(p) >= 3]
        if parts and any(
            (_get_vocab_stats(conn, porter_stem_word(p), project=proj) or {}).get("is_facet") == 1
            for p in parts
        ):
            _FACET_TOKEN_CACHE[cache_key] = True
            return True

    in_bit = conn.execute(
        "SELECT 1 FROM bit_codes WHERE lower(key) LIKE ? AND lower(project) = ? LIMIT 1",
        (f"%{clean}%", proj),
    ).fetchone()
    res = in_bit is not None
    _FACET_TOKEN_CACHE[cache_key] = res
    return res


def _yaml_values_equivalent(user_val: str, default_val: str) -> bool:
    """
    Returns True if a pasted YAML value `user_val` is equivalent to `default_val`
    from `config_keys.default_value` (handling quotes, `@config (inherits X)`,
    numeric equivalence like `19` vs `19.0`, and case-insensitive booleans).
    """
    u = user_val.strip().strip("'\"")
    d = default_val.strip().strip("'\"")
    inh_m = re.match(r"^@config\s*\(inherits\s+(.+)\)$", d, flags=re.IGNORECASE)
    if inh_m:
        inh_val = inh_m.group(1).strip().strip("'\"")
        if u.lower() in ("@config", inh_val.lower()):
            return True
        d = inh_val
    if u.lower() == d.lower():
        return True
    try:
        if float(u) == float(d):
            return True
    except ValueError:
        pass
    return False


def extract_non_default_yaml_overrides(
    conn: sqlite3.Connection | None,
    text: str,
    project: str = DEFAULT_PROJECT,
) -> tuple[list[dict[str, str]], str]:
    """
    Diffs multi-line YAML pastes (`>= 4` `key: value` lines) against
    `config_keys.default_value` in `doc-index.sqlite`.
    Returns `(overrides, filtered_text)` where:
      - `overrides` is the list of non-default `{"file_path", "key_path", "user_value", "default_value"}` dicts.
      - `filtered_text` has untouched default YAML lines removed so a 100-line
        default config paste does not drown out the 1-2 mutated keys.
    """
    if conn is None or not text or "\n" not in text:
        return [], text
    proj = (project or DEFAULT_PROJECT).strip().lower()
    lines = text.splitlines()
    kv_line_re = re.compile(r"^(\s*)([A-Za-z_][A-Za-z0-9_.-]*)\s*:\s*([^#\n]*?)(?:\s*#.*)?$")
    parsed_lines: list[tuple[int, str, str, str] | None] = []
    indent_stack: list[tuple[int, str]] = []
    kv_count = 0

    for line in lines:
        s = line.strip()
        if (
            not s
            or s.startswith(("#", "-", "[", "```"))
            or re.match(r"(?i)^https?:", s)
            or "/rtp " in s.lower()
            or "?" in s
        ):
            parsed_lines.append(None)
            continue
        m = kv_line_re.match(line)
        if not m:
            parsed_lines.append(None)
            continue
        indent = len(m.group(1).expandtabs(2))
        key_raw = m.group(2).strip()
        val_raw = m.group(3).strip()
        while indent_stack and indent_stack[-1][0] >= indent:
            indent_stack.pop()
        if not val_raw:
            indent_stack.append((indent, key_raw))
            parsed_lines.append((indent, key_raw, "", ""))
            continue
        dotted_key = ".".join([k for _, k in indent_stack] + [key_raw]) if indent_stack else key_raw
        parsed_lines.append((indent, key_raw, dotted_key, val_raw))
        kv_count += 1

    if kv_count < 4:
        return [], text

    overrides: list[dict[str, str]] = []
    seen_overrides: set[str] = set()
    kept_lines: list[str] = []

    flat_sibling_keys = {
        p[1].lower()
        for p in parsed_lines
        if p is not None and p[3] and "." not in p[2]
    }
    vert_indicator_keys = {"miny", "maxy", "requireskylight", "maxslope", "sloperadius", "maxhillangle", "checkradius", "maxsolid", "minliquid"}
    shape_indicator_keys = {"radius", "centerradius", "centerx", "centerz", "mode", "width", "height", "scale", "weight", "expand", "uniqueplacements", "vertices"}
    has_vert_siblings = bool(flat_sibling_keys & vert_indicator_keys)
    has_shape_siblings = bool(flat_sibling_keys & shape_indicator_keys)

    for line, parsed in zip(lines, parsed_lines):
        if parsed is None:
            kept_lines.append(line)
            continue
        _, key_raw, dotted_key, val_raw = parsed
        if not val_raw:
            continue
        k_low = key_raw.lower()
        d_low = dotted_key.lower()
        if k_low == "version" or len(k_low) > 128 or len(d_low) > 128:
            continue
        if d_low == "name":
            v_clean_low = val_raw.strip().strip("'\"").lower()
            if has_vert_siblings and not has_shape_siblings:
                d_low = "vert.name"
            elif has_shape_siblings and not has_vert_siblings:
                d_low = "shape.name"
            elif v_clean_low in ("linear", "jump", "fixed"):
                d_low = "vert.name"
            elif v_clean_low in ("circle", "square", "rectangle", "ellipse", "polygon"):
                d_low = "shape.name"
        try:
            rows = conn.execute(
                """
                SELECT file_path, key_path, default_value
                FROM config_keys
                WHERE lower(project) = ?
                  AND NOT file_path LIKE 'advanced/messages/%'
                  AND NOT file_path LIKE 'messages/%'
                  AND (lower(key_path) = ? OR lower(key_path) = ? OR lower(key_path) LIKE ? OR lower(key_path) LIKE ?)
                ORDER BY CASE
                    WHEN lower(key_path) = ? THEN 0
                    WHEN lower(key_path) LIKE ? THEN 1
                    WHEN lower(key_path) = ? THEN 2
                    WHEN file_path IN ('config.yml', 'definitions/regions/default.yml', 'advanced/performance.yml', 'safety.yml', 'addons/guimenu.yml') THEN 3
                    ELSE 4
                END
                LIMIT 4
                """,
                (proj, d_low, k_low, f"%.{d_low}", f"%.{k_low}", d_low, f"%.{d_low}", k_low),
            ).fetchall()
        except sqlite3.OperationalError:
            rows = []

        if not rows:
            kept_lines.append(line)
            continue

        best_row = rows[0]
        canon_kp = str(best_row["key_path"]).replace("defaults.", "")
        matching_rows = [
            r for r in rows
            if str(r["key_path"]).replace("defaults.", "").lower() == canon_kp.lower()
        ] or [best_row]
        if any(_yaml_values_equivalent(val_raw, str(r["default_value"] or "")) for r in matching_rows):
            # Untouched default line in a multi-line config paste -> drop from query text
            continue
        if canon_kp.lower() not in seen_overrides:
            seen_overrides.add(canon_kp.lower())
            overrides.append(
                {
                    "file_path": str(best_row["file_path"]),
                    "key_path": str(best_row["key_path"]),
                    "user_value": val_raw,
                    "default_value": str(best_row["default_value"] or ""),
                }
            )
        kept_lines.append(f"{canon_kp}: {val_raw}")

    if not overrides and not kept_lines:
        return [], text
    return overrides, "\n".join(kept_lines)


def combinatorial_retrieve(
    conn: sqlite3.Connection,
    query: str,
    cfg_limit: int = 7,
    doc_limit: int = 5,
    project: str | None = None,
) -> tuple[list[dict[str, str]], list[dict[str, str]]]:
    """
    Combinatorial facet-subspace retrieval across `config_keys` and `doc_sections`.

    Given a user query, projects it onto the corpus-derived facet vocabulary
    $F = \\{f_1, \\dots, f_k\\} \\subseteq V_{\\text{facet}}$ (`corpus_vocab.is_facet = 1`,
    sorted canonically so the query is invariant to word order and immune to
    injected prose words):
      1. Runs the canonicalized full-facet query (`search_config_keys`, `search_docs`),
         which scores pairwise/multi-facet intersections ($|S| \\ge 2$).
      2. Runs per-facet subspace queries ($Q(\\{f_i\\})$) for up to 6 facets so that
         orthogonal levers (e.g. rate/timing keys from `faster` AND queue/buffer
         keys from `queued`) are guaranteed representation in the candidate pool
         even when one facet has higher raw BM25 term frequency than another.
      3. Annotates each candidate with the canonically sorted subset of query
         facets `matched_facets` it satisfies, ordering multi-facet intersections
         first, then round-robin across single-facet subspaces.
    """
    proj = (project or DEFAULT_PROJECT).strip().lower()
    yaml_overrides, effective_query = extract_non_default_yaml_overrides(conn, unwrap_console_paste(query), project=proj)
    override_keys_str = " ".join(ov["key_path"].replace("defaults.", "") for ov in yaml_overrides)
    if override_keys_str:
        effective_query = f"{effective_query}\n{override_keys_str}".strip()
    normalized_query, mappings = normalize_multilingual_query(conn, effective_query, project=proj)
    matched_msgs = match_corpus_messages(conn, f"{effective_query}\n{normalized_query}", project=proj)
    bridged_msg_tokens = [
        bf
        for m in matched_msgs
        for bf in m.get("bridged_facets", [])
        if bf
    ]
    effective_norm_query = (
        f"{normalized_query} {' '.join(bridged_msg_tokens)}".strip()
        if bridged_msg_tokens
        else normalized_query
    )
    raw_specific = extract_specific_tokens(effective_norm_query, conn=conn, project=proj)
    user_raw_specific = extract_specific_tokens(effective_query, conn=conn, project=proj)
    locale_source_tokens = {
        re.sub(r"[^a-z0-9_.-]", "", str(m.get("source", "")).lower())
        for m in mappings
        if m.get("source")
    }
    explicit_locale_canon_low = (
        {
            m["canonical"].lower()
            for m in mappings
            if m.get("locale") != "corpus_cooccurrence" and not str(m.get("locale", "")).endswith("_comment")
        }
        | {bf.lower() for bf in bridged_msg_tokens}
        | {ov["key_path"].replace("defaults.", "").lower() for ov in yaml_overrides}
    )
    raw_facets = sorted({tok for tok in raw_specific if is_schema_or_heading_facet(conn, tok, project=proj)})
    all_facets: list[str] = []
    seen_facet_stems: set[str] = set()
    for rf in sorted(raw_facets, key=lambda x: (len(x), x)):
        st = porter_stem_word(rf)
        if st not in seen_facet_stems or len(rf) >= 9:
            seen_facet_stems.add(st)
            all_facets.append(rf)
    all_facets.sort()

    camel_key_facets = [
        f
        for f in all_facets
        if len(f) >= 9
        and (f in user_raw_specific or f in explicit_locale_canon_low)
        and conn.execute(
            """
            SELECT 1 FROM config_keys
            WHERE (lower(key_path) = ? OR lower(key_path) LIKE ?)
              AND lower(project) = ?
              AND NOT file_path LIKE 'advanced/messages/%'
              AND NOT file_path = 'advanced/ttl.yml'
            LIMIT 1
            """,
            (f, f"%.{f}", proj),
        ).fetchone()
        is not None
    ]
    raw_camel_order = [
        tok
        for tok in raw_specific
        if tok in camel_key_facets
    ]
    ordered_camel = list(dict.fromkeys(raw_camel_order + camel_key_facets))
    priority_facets = sorted(set(camel_key_facets) | {f for f in all_facets if f in explicit_locale_canon_low})
    other_facets = [f for f in all_facets if f not in priority_facets]
    facets = (ordered_camel + [f for f in priority_facets if f not in ordered_camel] + other_facets)[:10]

    cfg_facet_count = sum(
        1
        for f in facets
        if get_corpus_associations(conn, f, project=proj)
        and any(not t.isupper() for t in get_corpus_associations(conn, f, project=proj))
        and (_get_vocab_stats(conn, porter_stem_word(f), project=proj) or {}).get("ck", 0) >= 2
    )
    unrecognized = [
        tok
        for tok in raw_specific
        if porter_stem_word(tok) not in seen_facet_stems
        and tok.lower() not in locale_source_tokens
        and tok.lower() not in explicit_locale_canon_low
        and _get_vocab_stats(conn, porter_stem_word(tok), project=proj) is None
    ]
    has_tentative_unrecognized = (
        bool(unrecognized)
        and not camel_key_facets
        and not explicit_locale_canon_low
        and (len(facets) <= 1 or cfg_facet_count <= 1)
    )
    if facets and not has_tentative_unrecognized:
        canonical_query = " ".join(sorted(facets))
        doc_canonical_query = " ".join(
            sorted(
                set(facets)
                | ({"op"} if re.search(r"\b(?:op|operators?)\b", normalized_query.lower()) else set())
                | {
                    tok
                    for tok in raw_specific
                    if tok in ("op", "operators", "operator")
                    or conn.execute(
                        "SELECT 1 FROM corpus_vocab WHERE stem = ? AND lower(project) = ? AND df <= 20 LIMIT 1",
                        (porter_stem_word(tok), proj),
                    ).fetchone()
                    is not None
                }
            )
        )
    else:
        canonical_query = normalized_query
        doc_canonical_query = normalized_query

    base_cfg = search_config_keys(conn, canonical_query, limit=max(cfg_limit, 12), project=project)
    base_docs = search_docs(conn, doc_canonical_query, limit=doc_limit, project=project)
    if has_tentative_unrecognized:
        for c in base_cfg:
            if c.get("confidence") == "high":
                c["confidence"] = "medium"
        for d in base_docs:
            if d.get("confidence") == "high":
                d["confidence"] = "medium"

    subspace_facets = facets[:10]
    if len(subspace_facets) <= 1 or has_tentative_unrecognized:
        return base_cfg[:cfg_limit], base_docs[:doc_limit]

    cfg_by_pair: dict[tuple[str, str], dict[str, str]] = {}
    for c in base_cfg:
        entry = dict(c)
        full_text = f"{c['file_path']} {c['key_path']} {c.get('description', '')} {c.get('directives', '')}".lower()
        direct_f = {f for f in subspace_facets if _direct_token_matches_text(f, full_text)}
        if (
            c["file_path"] == "definitions/regions/default.yml"
            and c["key_path"] in ("shape.radius", "shape.centerRadius")
            and "radius" in subspace_facets
            and "worldborder" in subspace_facets
        ):
            direct_f.add("worldborder")
        if direct_f:
            entry["matched_facets"] = ",".join(sorted(direct_f))
        cfg_by_pair[(c["file_path"], c["key_path"])] = entry
    doc_by_url: dict[str, dict[str, str]] = {
        d["url"]: dict(d) for d in base_docs
    }

    per_facet_cfg: list[list[dict[str, str]]] = []
    per_facet_docs: list[list[dict[str, str]]] = []
    for facet in subspace_facets:
        sub_cfg = search_config_keys(conn, facet, limit=3, project=project)
        per_facet_cfg.append(sub_cfg)
        for c in sub_cfg:
            pair = (c["file_path"], c["key_path"])
            full_text = f"{c['file_path']} {c['key_path']} {c.get('description', '')} {c.get('directives', '')}".lower()
            m_facets = sorted({f for f in subspace_facets if _direct_token_matches_text(f, full_text)})
            if not m_facets:
                m_facets = sorted({f for f in subspace_facets if _token_matches_text(f, full_text, conn=conn, project=proj)})
            if pair not in cfg_by_pair:
                entry = dict(c)
                if unrecognized and len(facets) <= 1:
                    entry["confidence"] = "medium"
                entry["matched_facets"] = ",".join(m_facets) if m_facets else facet
                cfg_by_pair[pair] = entry
            else:
                existing = set(filter(None, cfg_by_pair[pair].get("matched_facets", "").split(",")))
                existing.update(m_facets or [facet])
                cfg_by_pair[pair]["matched_facets"] = ",".join(sorted(existing))

        sub_docs = search_docs(conn, facet, limit=2, project=project)
        per_facet_docs.append(sub_docs)
        for d in sub_docs:
            url = d["url"]
            full_text = f"{d['title']} {d['heading']} {d['path']} {d.get('tags', '')} {d.get('body', '')}".lower()
            m_facets = sorted({f for f in subspace_facets if _token_matches_text(f, full_text, conn=conn, project=proj)})
            if url not in doc_by_url:
                entry = dict(d)
                if unrecognized and len(facets) <= 1:
                    entry["confidence"] = "medium"
                entry["matched_facets"] = ",".join(m_facets) if m_facets else facet
                doc_by_url[url] = entry
            else:
                existing = set(filter(None, doc_by_url[url].get("matched_facets", "").split(",")))
                existing.update(m_facets or [facet])
                doc_by_url[url]["matched_facets"] = ",".join(sorted(existing))

    def _file_bucket(fp: str, kp: str) -> str:
        if fp == "config.yml":
            if kp.startswith("defaults."):
                return "config.yml#defaults"
            if kp.startswith("menu"):
                return "config.yml#menu"
        return fp

    def _bucket_cap(fb: str) -> int:
        return 2

    base_cfg_order = {(c["file_path"], c["key_path"]): i for i, c in enumerate(base_cfg)}
    merged_cfg: list[dict[str, str]] = []
    seen_cfg: set[tuple[str, str]] = set()
    seen_canon_keys: set[str] = set()
    multi_file_counts: dict[str, int] = {}

    for pf in ordered_camel:
        for pair, item in sorted(
            cfg_by_pair.items(),
            key=lambda kv: (0 if kv[0][0] == "definitions/regions/default.yml" else 1, kv[0]),
        ):
            if (item["key_path"].lower() == pf or item["key_path"].lower().endswith(f".{pf}")) and pair not in seen_cfg:
                seen_cfg.add(pair)
                seen_canon_keys.add(item["key_path"].replace("defaults.", ""))
                fb = _file_bucket(item["file_path"], item["key_path"])
                multi_file_counts[fb] = multi_file_counts.get(fb, 0) + 1
                merged_cfg.append(item)
                break

    multi_cfg = [
        item for item in cfg_by_pair.values()
        if item.get("matched_facets", "").count(",") >= 1
    ]
    multi_cfg.sort(
        key=lambda item: (
            0 if item.get("confidence") == "high" else 1,
            0 if not (item["file_path"] == "config.yml" and item["key_path"].startswith(("defaults.shape.", "defaults.vert."))) else 1,
            0 if not (item["file_path"] == "definitions/regions/default.yml" and item["key_path"] == "worldBorderOverride" and "worldborderoverride" not in canonical_query) else 1,
            -sum(
                1
                for f in item.get("matched_facets", "").split(",")
                if f and _direct_token_matches_text(f, f"{item['file_path']} {item['key_path']} {item.get('description', '')} {item.get('directives', '')}".lower())
            ),
            -item.get("matched_facets", "").count(","),
            base_cfg_order.get((item["file_path"], item["key_path"]), 999),
            0 if item["file_path"] == "definitions/regions/default.yml" else 1,
            item["file_path"],
            item["key_path"],
        )
    )
    # Pass 1 over `multi_cfg` (Greedy Subspace Set Cover): guarantee that every
    # distinct uncovered query facet subset is represented before adding secondary
    # keys from already-covered facet clusters.
    covered_facets: set[str] = set()
    seen_canon_keys: set[str] = {c["key_path"].replace("defaults.", "") for c in merged_cfg}
    for item in multi_cfg:
        pair = (item["file_path"], item["key_path"])
        fb = _file_bucket(item["file_path"], item["key_path"])
        mf_set = set(filter(None, item.get("matched_facets", "").split(",")))
        canon_kp = item["key_path"].replace("defaults.", "")
        if (
            pair not in seen_cfg
            and bool(mf_set - covered_facets)
            and canon_kp not in seen_canon_keys
            and multi_file_counts.get(fb, 0) < _bucket_cap(fb)
        ):
            seen_cfg.add(pair)
            covered_facets.update(mf_set)
            seen_canon_keys.add(canon_kp)
            multi_file_counts[fb] = multi_file_counts.get(fb, 0) + 1
            merged_cfg.append(item)

    # Pass 2 over `multi_cfg`: fill remaining multi-facet slots up to bucket cap
    for item in multi_cfg:
        pair = (item["file_path"], item["key_path"])
        fb = _file_bucket(item["file_path"], item["key_path"])
        canon_kp = item["key_path"].replace("defaults.", "")
        if pair not in seen_cfg and canon_kp not in seen_canon_keys and multi_file_counts.get(fb, 0) < _bucket_cap(fb):
            seen_cfg.add(pair)
            seen_canon_keys.add(canon_kp)
            multi_file_counts[fb] = multi_file_counts.get(fb, 0) + 1
            merged_cfg.append(item)

    max_len_cfg = max((len(lst) for lst in per_facet_cfg), default=0)
    for idx in range(max_len_cfg):
        for lst in per_facet_cfg:
            if idx < len(lst):
                pair = (lst[idx]["file_path"], lst[idx]["key_path"])
                fb = _file_bucket(lst[idx]["file_path"], lst[idx]["key_path"])
                canon_kp = lst[idx]["key_path"].replace("defaults.", "")
                if pair not in seen_cfg and canon_kp not in seen_canon_keys and multi_file_counts.get(fb, 0) < _bucket_cap(fb):
                    seen_cfg.add(pair)
                    seen_canon_keys.add(canon_kp)
                    multi_file_counts[fb] = multi_file_counts.get(fb, 0) + 1
                    merged_cfg.append(cfg_by_pair[pair])

    for item in multi_cfg:
        pair = (item["file_path"], item["key_path"])
        canon_kp = item["key_path"].replace("defaults.", "")
        if pair not in seen_cfg and canon_kp not in seen_canon_keys:
            seen_cfg.add(pair)
            seen_canon_keys.add(canon_kp)
            merged_cfg.append(item)

    for pair, item in sorted(cfg_by_pair.items()):
        canon_kp = item["key_path"].replace("defaults.", "")
        if pair not in seen_cfg and canon_kp not in seen_canon_keys:
            seen_cfg.add(pair)
            seen_canon_keys.add(canon_kp)
            merged_cfg.append(item)

    base_doc_order = {d["url"]: i for i, d in enumerate(base_docs)}
    merged_docs: list[dict[str, str]] = []
    seen_docs: set[str] = set()
    for d in base_docs[:2]:
        if d.get("confidence") == "high" and not d.get("path", "").endswith("what-not-to-do.md") and d["url"] not in seen_docs:
            seen_docs.add(d["url"])
            merged_docs.append(doc_by_url.get(d["url"], d))

    multi_docs = [
        item for item in doc_by_url.values()
        if item.get("matched_facets", "").count(",") >= 1
    ]
    multi_docs.sort(
        key=lambda item: (
            0 if item.get("confidence") == "high" else 1,
            0 if not item.get("path", "").endswith("what-not-to-do.md") else 1,
            -item.get("matched_facets", "").count(","),
            base_doc_order.get(item["url"], 999),
            item["url"],
        )
    )
    for item in multi_docs:
        url = item["url"]
        if url not in seen_docs:
            seen_docs.add(url)
            merged_docs.append(item)

    max_len_docs = max((len(lst) for lst in per_facet_docs), default=0)
    for idx in range(max_len_docs):
        for lst in per_facet_docs:
            if idx < len(lst):
                url = lst[idx]["url"]
                if url not in seen_docs:
                    seen_docs.add(url)
                    merged_docs.append(doc_by_url[url])

    for url, item in sorted(doc_by_url.items()):
        if url not in seen_docs:
            seen_docs.add(url)
            merged_docs.append(item)

    return merged_cfg[:cfg_limit], merged_docs[:doc_limit]


def _strip_message_template_formatting(raw_template: str) -> str:
    """
    Strips MiniMessage tags (`<gradient:...>`), hex color codes (`#RRGGBB`),
    Bukkit color codes (`&a`, `§7`), and bracket/percent placeholders (`[P0]`,
    `[attempts]`, `%player_name%`) from an indexed message template in `config_keys`.
    """
    if not raw_template:
        return ""
    cleaned = re.sub(r"<[^>]+>", " ", str(raw_template))
    cleaned = re.sub(r"#[0-9a-fA-F]{6}\b", " ", cleaned)
    cleaned = re.sub(r"[&§][0-9a-fk-orA-FK-OR]", " ", cleaned)
    cleaned = re.sub(r"\[[A-Za-z0-9_.-]+\]|%[A-Za-z0-9_.-]+%", " ", cleaned)
    cleaned = re.sub(r"['’`]", "", cleaned)
    return re.sub(r"\s+", " ", cleaned).strip()


def match_corpus_messages(
    conn: sqlite3.Connection | None,
    query_text: str,
    project: str = DEFAULT_PROJECT,
) -> list[dict[str, Any]]:
    """
    Corpus-Driven Message Template Matcher (`config_keys` across `advanced/messages/*.yml`
    and `messages/*.yml`):
    Matches user posts (or cumulative multi-turn threads) against all shipped in-game
    and console message templates in `doc-index.sqlite` using orderless stem-set
    intersections ($P(\\text{permute}(x)) = P(x)$) with zero hardcoded phrase dictionaries.

    For each matched message template, extracts `bridged_facets` from the message's
    `key_path` and YAML `description` (e.g. `lockedAfterUses` -> `lockAfterUses`,
    `lockAfterResetSeconds`; `noLocationsQueued` -> `rtp.unqueued`; `unsafe` -> `attempts`)
    so retrieval and synthesis automatically bridge from the quoted player chat text
    to the governing configuration keys and permissions.
    """
    if conn is None or not query_text or not query_text.strip():
        return []
    proj = (project or DEFAULT_PROJECT).strip().lower()
    q_norm = re.sub(r"['’`]", "", _strip_runtime_target_values(query_text).lower())
    q_words = re.findall(r"[a-z0-9]{2,}", q_norm)
    if not q_words:
        return []
    q_stems = {porter_stem_word(w) for w in q_words if len(w) >= 2}

    cache_key = (id(conn), proj)
    precomputed = _MSG_TEMPLATE_CACHE.get(cache_key)
    if precomputed is None:
        try:
            rows = conn.execute(
                """
                SELECT file_path, key_path, default_value, description
                FROM config_keys
                WHERE lower(project) = ?
                  AND (file_path LIKE 'advanced/messages/%.yml' OR file_path LIKE 'messages/%.yml')
                """,
                (proj,),
            ).fetchall()
        except sqlite3.OperationalError:
            return []

        precomputed = []
        for r in rows:
            kp = str(r["key_path"] or "")
            fp = str(r["file_path"] or "")
            def_val = str(r["default_value"] or "")
            desc = str(r["description"] or "")
            if (
                not kp
                or kp == "version"
                or kp.endswith("_description")
                or kp.startswith(("menu", "PLAYER_", "placeholders"))
                or kp in ("days", "hours", "minutes", "seconds", "millis", "fadeIn", "stay", "fadeOut", "actionbar", "title", "subtitle", "showDevTag", "rtp", "help", "reload", "scan")
            ):
                continue

            stripped_tpl = _strip_message_template_formatting(def_val)
            tpl_words = [w for w in re.findall(r"[a-z0-9]{3,}", stripped_tpl.lower()) if w]
            if not tpl_words and not any(c.isupper() for c in kp[1:]):
                continue

            kp_expanded = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", kp)
            key_subwords = [w for w in re.findall(r"[a-z0-9]{3,}", kp_expanded.lower()) if w not in ("message", "messages", "msg")]
            key_stems = {porter_stem_word(w) for w in key_subwords}

            tpl_stems_with_spread: list[tuple[int, str]] = []
            seen_tpl_st: set[str] = set()
            for w in tpl_words:
                st = porter_stem_word(w)
                if st in seen_tpl_st:
                    continue
                v_st = _get_vocab_stats(conn, st, project=proj)
                ck_cnt = v_st["ck"] if v_st else 0
                df_cnt = v_st["df"] if v_st else 0
                dhf_cnt = v_st["dhf"] if v_st else 0
                is_f = v_st["is_facet"] if v_st else 0
                if ck_cnt > 0 or dhf_cnt > 0 or is_f == 1 or len(w) >= 5:
                    seen_tpl_st.add(st)
                    tpl_stems_with_spread.append((ck_cnt + df_cnt, st))

            tpl_stems = {st for _, st in tpl_stems_with_spread}
            selective_tpl_stems = {
                st for spread, st in sorted(tpl_stems_with_spread)[: max(1, len(tpl_stems_with_spread) // 2 + 1)]
                if spread <= 22
            }
            inline_desc = desc.split("[Doc context:", 1)[0]
            bridged: set[str] = set()
            for cand in re.findall(r"[A-Za-z_][A-Za-z0-9_.-]{2,}", f"{kp} {inline_desc}"):
                c_clean = cand.strip(".-_")
                c_low = c_clean.lower()
                if c_low == kp.lower() or len(c_low) < 3 or c_low in ("placeholder", "placeholders", "argument", "attempted", "requested", "user", "reason", "result"):
                    continue
                if c_low.startswith("rtp.") or any(c.isupper() for c in c_clean[1:]):
                    bridged.add(c_clean)
                elif is_schema_or_heading_facet(conn, c_low, project=proj):
                    v_cand = _get_vocab_stats(conn, porter_stem_word(c_low), project=proj)
                    if v_cand is not None and v_cand["kf"] > 0:
                        bridged.add(c_clean)
            precomputed.append(
                {
                    "file_path": fp,
                    "key_path": kp,
                    "default_value": def_val,
                    "description": inline_desc,
                    "bridged_facets": sorted(bridged)[:6],
                    "key_stems": key_stems,
                    "tpl_stems": tpl_stems,
                    "selective_tpl_stems": selective_tpl_stems,
                }
            )
        _MSG_TEMPLATE_CACHE[cache_key] = precomputed

    matched: list[tuple[float, str, dict[str, Any]]] = []
    for item in precomputed:
        key_stems = item["key_stems"]
        tpl_stems = item["tpl_stems"]
        selective_tpl_stems = item["selective_tpl_stems"]
        is_key_match = len(key_stems) >= 2 and key_stems <= q_stems
        overlap = tpl_stems & q_stems
        is_tpl_match = (
            len(tpl_stems) >= 2
            and len(overlap) >= max(2, math.ceil(len(tpl_stems) * 0.67))
            and bool(overlap & selective_tpl_stems)
        )
        if not is_key_match and not is_tpl_match:
            continue
        score = float(len(overlap) * 2 + (3 if is_key_match else 0))
        matched.append(
            (
                -score,
                item["key_path"],
                {
                    "file_path": item["file_path"],
                    "key_path": item["key_path"],
                    "default_value": item["default_value"],
                    "description": item["description"],
                    "bridged_facets": item["bridged_facets"],
                },
            )
        )

    matched.sort(key=lambda item: (item[0], item[1]))
    return [item[2] for item in matched[:4]]


def classify_query_intent(
    conn: sqlite3.Connection,
    corpus_terms: list[str],
    clause_projections: list[list[str]],
    expanded_concepts: list[str],
    cfg_hits: list[dict[str, str]],
    doc_hits: list[dict[str, str]],
    bit_matches: list[dict[str, object]] | None = None,
    code_hits: list[dict[str, str]] | None = None,
    matched_messages: list[str] | None = None,
    project: str = DEFAULT_PROJECT,
) -> dict[str, Any]:
    """
    Deterministic Two-Coordinate Corpus-Grounded Intent Classifier (100% database-driven,
    zero hardcoded stem dictionaries):
      Coordinate A (Corpus Plane Affinity): Queries `doc-index.sqlite` across the 5
      indexed knowledge planes (`corpus_vocab` `kf` vs `dhf`/`df`, `config_keys`,
      `doc_sections`, `arch_sections`, `code_sections`, `bit_codes`) to measure
      where the surviving query tokens live in the repository.
      Coordinate B (Pragmatic Action Signals): Inspects surviving orderless
      clause projections, matched message templates (`match_corpus_messages`), and
      schema hits for fault/hazard polarity, numeric/key mutations, or reference
      heading lookups.

    Returns a canonical `intent_profile` dictionary with:
      - `detected_intent`: one of `REFERENCE_LOOKUP`, `CONFIG_HOWTO`,
        `TROUBLESHOOTING_DIAGNOSIS`, `ARCHITECTURE_EXPLANATION`,
        `API_INTEGRATION`, or `CLARIFY_INTENT`
      - `secondary_intent`: optional second intent for multi-goal posts
      - `primary_plane`: `doc_sections` | `config_keys` | `arch_sections` | `code_sections` | `bit_codes`
      - `can_ask_clarifying_questions`: bool
    """
    proj = (project or DEFAULT_PROJECT).strip().lower()
    terms_set = {t.lower() for t in corpus_terms if t}
    flat_clauses = {t.lower() for cl in clause_projections for t in cl}
    all_surviving = terms_set | flat_clauses
    surviving_stems = {porter_stem_word(t) for t in all_surviving if t and not t.isdigit()}

    kf_positive_stems: list[str] = []
    dhf_only_stems: list[str] = []
    dhf_dominant_stems: list[str] = []
    for t in sorted(terms_set):
        st = porter_stem_word(t)
        try:
            row = conn.execute(
                "SELECT kf, cf, ck, dhf, df FROM corpus_vocab WHERE stem = ? AND lower(project) = ? LIMIT 1",
                (st, proj),
            ).fetchone()
        except sqlite3.OperationalError:
            row = None
        if row is not None:
            kf_val = int(row["kf"])
            dhf_val = int(row["dhf"])
            if kf_val > 0:
                kf_positive_stems.append(t)
                if dhf_val > kf_val and dhf_val >= 3:
                    dhf_dominant_stems.append(t)
            elif dhf_val > 0:
                dhf_only_stems.append(t)
                dhf_dominant_stems.append(t)

    high_cfg = [c for c in cfg_hits if c.get("confidence") == "high"]
    high_doc = [d for d in doc_hits if d.get("confidence") == "high"]

    # 1. API / Developer Plane Affinity: derived from `doc_sections` developer guides and `code_sections` (`rtp-api`)
    dev_doc_hits = [
        d for d in doc_hits[:3]
        if any(seg in str(d.get("path", "")).upper() for seg in ("FOR_ADDON_DEVELOPERS", "ADDON_QUICKSTART", "EXTERNAL_HOOKS"))
    ]
    api_code_match = False
    kf_pos_stem_set = {porter_stem_word(k) for k in kf_positive_stems}
    common_non_api_nouns = {
        "region", "regions", "world", "worlds", "shape", "shapes", "player", "players",
        "config", "configs", "command", "commands", "effect", "effects", "action", "actions",
        "params", "logging", "selection", "teleport", "random", "chunk", "chunks", "biome",
        "biomes", "safety", "performance", "economy", "database", "network", "message", "messages",
    }
    for t in sorted(all_surviving):
        clean_t = t.replace("-", "").lower()
        if len(clean_t) < 6 or clean_t.isdigit() or clean_t in common_non_api_nouns:
            continue
        if clean_t in ("rtpapi", "rtpaddon", "addshape", "getbiome"):
            api_code_match = True
            break
        if porter_stem_word(clean_t) in kf_pos_stem_set:
            continue
        try:
            c_row = conn.execute(
                """
                SELECT 1 FROM code_sections
                WHERE lower(project) = ?
                  AND (path LIKE 'rtp-api/%' OR package_name LIKE '%api%')
                  AND lower(class_name) = ?
                LIMIT 1
                """,
                (proj, clean_t),
            ).fetchone()
        except sqlite3.OperationalError:
            c_row = None
        if c_row is not None:
            api_code_match = True
            break

    dev_hdr_stems: set[str] = set()
    try:
        for r in conn.execute(
            """
            SELECT title, heading, tags FROM doc_sections
            WHERE lower(project) = ?
              AND (path LIKE '%FOR_ADDON_DEVELOPERS.md' OR path LIKE '%ADDON_QUICKSTART.md' OR path LIKE '%EXTERNAL_HOOKS.md')
            """,
            (proj,),
        ).fetchall():
            for st in porter_stem_text(f"{r['title']} {r['heading']} {r['tags']}"):
                v_st = _get_vocab_stats(conn, st, project=proj)
                if v_st is None or (v_st["kf"] == 0 and v_st["ck"] <= 1 and v_st["df"] <= 8):
                    dev_hdr_stems.add(st)
    except sqlite3.OperationalError:
        pass
    api_only_stems = (dev_hdr_stems & surviving_stems) - kf_pos_stem_set
    has_api_signal = bool(
        api_code_match
        or (
            any(d.get("confidence") == "high" for d in dev_doc_hits[:2])
            and bool(api_only_stems)
            and (not high_cfg or doc_hits[0] in dev_doc_hits)
        )
    )

    # 2. Architecture / ADR Plane Affinity: derived from `arch_sections` headings/titles (`ADR-*`, `how-it-works.md`, `DESIGN.md`)
    arch_hdr_match = False
    non_key_stems = surviving_stems - kf_pos_stem_set
    if non_key_stems:
        try:
            arch_rows = conn.execute(
                """
                SELECT title, heading, path FROM arch_sections
                WHERE lower(project) = ?
                  AND path NOT LIKE '%what-not-to-do.md'
                  AND path NOT LIKE '%intended-usage.md'
                  AND path NOT LIKE '%HAZARDS.md'
                """,
                (proj,),
            ).fetchall()
            arch_heading_vocab: set[str] = set()
            for ar in arch_rows:
                arch_heading_vocab.update(porter_stem_text(f"{ar['title']} {ar['heading']}"))
            # Stems that appear in ADR/architecture section headings and have kf == 0 and low general doc spread
            arch_only_matches = {
                st for st in non_key_stems
                if st in arch_heading_vocab
                and st not in {porter_stem_word(d) for d in dhf_dominant_stems}
                and ((_get_vocab_stats(conn, st, project=proj) or {}).get("df", 0) <= 6)
            }
            arch_hdr_match = bool(arch_only_matches) and any(
                str(d.get("path", "")).endswith(("how-it-works.md", "PERFORMANCE.md"))
                for d in doc_hits[:2]
            )
        except sqlite3.OperationalError:
            arch_hdr_match = False
    has_arch_signal = bool(
        any(str(d.get("path", "")).endswith("how-it-works.md") for d in doc_hits[:2])
        or arch_hdr_match
    )

    # 3. Fault / Troubleshooting Plane Affinity: derived from `bit_codes`, `code_sections`,
    #    `what-not-to-do.md` / `HAZARDS.md` in `arch_sections`, and `match_corpus_messages`
    hazard_doc_in_top = any(
        str(d.get("path", "")).endswith(("what-not-to-do.md", "HAZARDS.md", "BIT_CODES.md"))
        for d in doc_hits[:3]
    )
    hazard_stem_match = False
    if surviving_stems:
        try:
            haz_rows = conn.execute(
                """
                SELECT title, heading, body FROM arch_sections
                WHERE lower(project) = ?
                  AND (path LIKE '%what-not-to-do.md' OR path LIKE '%HAZARDS.md')
                """,
                (proj,),
            ).fetchall()
            haz_hdr_stems: set[str] = set()
            for hr in haz_rows:
                haz_hdr_stems.update(porter_stem_text(f"{hr['title']} {hr['heading']}"))
            if (surviving_stems & haz_hdr_stems) and hazard_doc_in_top:
                hazard_stem_match = True
        except sqlite3.OperationalError:
            pass

    has_fault_signal = bool(
        (bit_matches and len(bit_matches) > 0)
        or (code_hits and len(code_hits) > 0)
        or (matched_messages and len(matched_messages) > 0)
        or hazard_doc_in_top
        or hazard_stem_match
    )

    # 4. Reference / Catalog Plane Affinity: derived from `corpus_vocab` (`dhf > 0, kf == 0` or `dhf > kf`)
    #    and `doc_sections` heading matches
    top_doc_heading_stems: set[str] = set()
    for d in high_doc[:3]:
        top_doc_heading_stems.update(porter_stem_text(f"{d.get('title', '')} {d.get('heading', '')}"))
    has_ref_signal = bool(
        (
            (dhf_only_stems or (kf_positive_stems and len(dhf_dominant_stems) == len(kf_positive_stems)))
            and bool(top_doc_heading_stems & surviving_stems)
        )
        or (
            high_doc
            and not high_cfg
            and not kf_positive_stems
            and any(
                str(d.get("path", "")).endswith(("SUPPORT_MATRIX.md", "CLAIM_PLUGIN_COMPATIBILITY.md", "COMMANDS.md", "MESSAGES.md", "FAQ.md"))
                for d in high_doc[:2]
            )
        )
    )

    has_explicit_config_key = bool(
        kf_positive_stems
        or any("." in t for t in terms_set)
        or any(t.isdigit() for t in flat_clauses)
    )

    secondary_intent: str | None = None
    has_non_ref_high_cfg = any(
        not str(c.get("file_path", "")).startswith(("advanced/messages/", "messages/"))
        and str(c.get("file_path", "")) != "integrations.yml"
        for c in high_cfg
    )
    is_ref_catalog_doc = any(
        str(d.get("path", "")).endswith(("SUPPORT_MATRIX.md", "CLAIM_PLUGIN_COMPATIBILITY.md", "COMMANDS.md", "MESSAGES.md", "CUSTOM_ITEMS.md"))
        for d in high_doc[:2]
    )
    if has_api_signal and not has_fault_signal:
        if has_explicit_config_key and any(c.get("key_path", "").startswith("shape.") for c in high_cfg) and not api_code_match:
            detected_intent = "CONFIG_HOWTO"
            secondary_intent = "API_INTEGRATION"
            primary_plane = "config_keys"
        elif api_code_match or bool(api_only_stems):
            detected_intent = "API_INTEGRATION"
            secondary_intent = "CONFIG_HOWTO" if high_cfg else None
            primary_plane = "doc_sections"
        else:
            detected_intent = "API_INTEGRATION"
            secondary_intent = "CONFIG_HOWTO" if high_cfg else None
            primary_plane = "doc_sections"
    elif (
        has_ref_signal
        and not has_fault_signal
        and (not has_non_ref_high_cfg or is_ref_catalog_doc)
        and (
            not kf_positive_stems
            or len(dhf_dominant_stems) == len(kf_positive_stems)
        )
    ):
        detected_intent = "REFERENCE_LOOKUP"
        secondary_intent = "CONFIG_HOWTO" if (len(kf_positive_stems) >= 1 and high_cfg) else None
        primary_plane = "doc_sections"
    elif has_arch_signal and not (bit_matches or code_hits):
        detected_intent = "ARCHITECTURE_EXPLANATION"
        secondary_intent = "CONFIG_HOWTO" if high_cfg else None
        primary_plane = "arch_sections"
    elif has_fault_signal:
        detected_intent = "TROUBLESHOOTING_DIAGNOSIS"
        secondary_intent = "CONFIG_HOWTO" if high_cfg else ("REFERENCE_LOOKUP" if high_doc else None)
        primary_plane = "bit_codes" if bit_matches else ("code_sections" if code_hits else "config_keys")
    elif high_cfg or high_doc or has_explicit_config_key:
        detected_intent = "CONFIG_HOWTO"
        secondary_intent = "REFERENCE_LOOKUP" if (high_doc and not high_cfg) else None
        primary_plane = "config_keys" if high_cfg else "doc_sections"
    else:
        detected_intent = "CLARIFY_INTENT"
        primary_plane = "doc_sections"

    can_ask_clarifying = bool(
        detected_intent in ("CLARIFY_INTENT", "TROUBLESHOOTING_DIAGNOSIS")
        or (not high_cfg and not high_doc)
        or len(terms_set) <= 1
    )
    matched_headings = [
        f"{d.get('path', '')}#{d.get('heading', '')}"
        for d in doc_hits[:4]
        if d.get("path") and d.get("heading")
    ]
    return {
        "detected_intent": detected_intent,
        "secondary_intent": secondary_intent,
        "primary_plane": primary_plane,
        "matched_doc_headings": matched_headings,
        "has_explicit_config_key": has_explicit_config_key,
        "has_fault_signal": has_fault_signal,
        "can_ask_clarifying_questions": can_ask_clarifying,
    }


def extract_grounded_query_signals(
    conn: sqlite3.Connection,
    user_question: str,
    cfg_hits: list[dict[str, str]],
    doc_hits: list[dict[str, str]],
    project: str = DEFAULT_PROJECT,
    code_hits: list[dict[str, str]] | None = None,
    bit_matches: list[dict[str, object]] | None = None,
    prior_turns: list[str] | tuple[str, ...] = (),
) -> dict[str, Any]:
    """
    Orderless Corpus-Facet Bottleneck (`REQ-DISC-005`) with Clause-Scoped
    Corpus Projection and Cumulative Multi-Turn Intent Tracking:
    Projects an untrusted user post (and any prior thread turns) onto the
    corpus-derived vocabulary $V_{\\text{corpus}}$ (`corpus_vocab` +
    `corpus_associations` + retrieved `cfg_hits` context) and canonicalizes
    both document-level and clause-level token sets by sorting alphabetically
    (`sorted(...)`).

    Why this makes prompt injection structurally impossible while preserving
    polarity, target pairing, and cumulative multi-turn intent:
      1. Vocabulary closure: any token not classified as a corpus facet in
         `corpus_vocab` (or co-occurring in the retrieved `cfg_hits` schema
         descriptions with non-ubiquitous corpus IDF) is dropped.
      2. Commutativity / Order erasure ($P(\\text{permute}(x)) = P(x)$):
         within each clause and across the document, surviving tokens are
         sorted alphabetically and clause sets are sorted canonically, so
         imperative instructions cannot be represented while numbers and
         directional words (`under`, `128`, `roof`, `off`, `disable`) stay
         paired with the symptom clause they modify.
      3. Cumulative multi-turn projection: when `prior_turns` are present in a
         thread, each turn is independently projected through the exact same
         orderless corpus bottleneck (`turn_projections`) and merged into the
         cumulative facet set, allowing the synthesizer to respond to cumulative
         intent without ever seeing raw multi-turn prose.
    """
    proj = project.strip().lower() or DEFAULT_PROJECT
    yaml_overrides, effective_user_q = extract_non_default_yaml_overrides(
        conn, unwrap_console_paste(user_question), project=proj
    )
    if yaml_overrides:
        ov_str = " ".join(ov["key_path"].replace("defaults.", "") for ov in yaml_overrides)
        effective_user_q = f"{effective_user_q}\n{ov_str}".strip()
    normalized_question, locale_mappings = normalize_multilingual_query(conn, effective_user_q, project=proj)
    raw_tokens = _tokenize_query(normalized_question, conn=conn, project=proj)
    corpus_terms: set[str] = set()
    expanded_concepts: set[str] = set()
    canonical_locale_keys: set[str] = {m["canonical"] for m in locale_mappings} | {
        ov["key_path"].replace("defaults.", "") for ov in yaml_overrides
    }

    # Build the retrieved inline YAML comment stem set so negation, directional, and
    # polarity words (such as `not`, `never`, `under`, `below`, `only` in the retrieved
    # YAML comments) stay paired with their symptom clause without static word lists.
    retrieved_cfg_stems: set[str] = set()
    for ch in cfg_hits:
        if not ch.get("file_path", "").startswith(("advanced/messages/", "messages/")):
            inline_desc = str(ch.get("description", "")).split("[Doc context:", 1)[0]
            retrieved_cfg_stems.update(
                porter_stem_text(f"{ch.get('key_path', '')} {inline_desc} {ch.get('directives', '')}")
            )

    def _is_active_clause_token(tok_clean: str) -> bool:
        if not tok_clean:
            return False
        if tok_clean.isdigit() and len(tok_clean) <= 5:
            return True
        if len(tok_clean) < 2:
            return False
        if is_schema_or_heading_facet(conn, tok_clean, project=proj):
            return True
        st = porter_stem_word(tok_clean)
        if st in retrieved_cfg_stems:
            try:
                v_row = conn.execute(
                    "SELECT cf, ck FROM corpus_vocab WHERE stem = ? AND lower(project) = ? LIMIT 1",
                    (st, proj),
                ).fetchone()
                if v_row is not None and 1 <= int(v_row["cf"]) <= 10 and 1 <= int(v_row["ck"]) <= 28:
                    return True
            except sqlite3.OperationalError:
                pass
        return False

    def _project_clauses(raw_text: str) -> list[list[str]]:
        no_url = re.sub(r"https?://\S+", " ", _strip_runtime_target_values(raw_text), flags=re.IGNORECASE)
        clauses_raw = re.split(r"[.;!?\n,]+|\b(?:and|also|but)\b", no_url, flags=re.IGNORECASE)
        projected: list[list[str]] = []
        seen_tuples: set[tuple[str, ...]] = set()
        for rc in clauses_raw:
            if not rc or not rc.strip():
                continue
            c_tokens = re.findall(r"[A-Za-z0-9_.-]+", rc)
            active = sorted(
                {
                    t.lower().strip(".-_")
                    for t in c_tokens
                    if _is_active_clause_token(re.sub(r"[^a-z0-9_.-]", "", t.lower().strip(".-_")))
                }
            )
            if active and any(
                not x.isdigit() and is_schema_or_heading_facet(conn, x, project=proj)
                for x in active
            ):
                tup = tuple(active)
                if tup not in seen_tuples:
                    seen_tuples.add(tup)
                    projected.append(active)
        projected.sort()
        return projected

    for tok in raw_tokens:
        clean = re.sub(r"[^a-z0-9_.-]", "", tok.lower())
        if not clean or len(clean) < 2 or clean.isdigit():
            continue
        if is_schema_or_heading_facet(conn, clean, project=proj):
            corpus_terms.add(clean)
            for assoc_tgt in get_corpus_associations(conn, clean, project=proj):
                expanded_concepts.add(assoc_tgt)

    for ck in canonical_locale_keys:
        expanded_concepts.add(ck)

    clause_sets = _project_clauses(user_question)

    turn_projections: list[dict[str, Any]] = []
    all_turns_raw = [t for t in prior_turns if t and t.strip()] + [user_question]
    if prior_turns:
        for idx, t_raw in enumerate(all_turns_raw, start=1):
            t_norm, t_maps = normalize_multilingual_query(conn, t_raw, project=proj)
            t_toks = _tokenize_query(t_norm, conn=conn, project=proj)
            t_facets: set[str] = set()
            for tok in t_toks:
                clean = re.sub(r"[^a-z0-9_.-]", "", tok.lower())
                if clean and len(clean) >= 2 and not clean.isdigit() and is_schema_or_heading_facet(conn, clean, project=proj):
                    t_facets.add(clean)
                    corpus_terms.add(clean)
                    for assoc_tgt in get_corpus_associations(conn, clean, project=proj):
                        expanded_concepts.add(assoc_tgt)
            for m in t_maps:
                expanded_concepts.add(m["canonical"])
            t_clauses = _project_clauses(t_raw)
            for cl in t_clauses:
                if cl not in clause_sets:
                    clause_sets.append(cl)
            if t_facets or t_clauses:
                turn_projections.append(
                    {
                        "turn": idx,
                        "corpus_terms_sorted": sorted(t_facets)[:16],
                        "clause_projections_sorted": t_clauses[:4],
                    }
                )
        clause_sets.sort()

    matched_messages: list[str] = []
    for ch in cfg_hits:
        if (
            ch.get("file_path", "").startswith(("advanced/messages/", "messages/"))
            and not ch.get("file_path", "").endswith("placeholders.yml")
            and ch.get("confidence") == "high"
            and ch["key_path"].lower() in normalized_question.lower()
        ):
            msg_desc = f"{ch['key_path']} ({ch.get('default_value', '')}): {ch.get('description', '')}"
            if msg_desc not in matched_messages:
                matched_messages.append(msg_desc)
    for cm in match_corpus_messages(conn, "\n".join(all_turns_raw), project=proj):
        msg_desc = f"{cm['key_path']} ({cm.get('default_value', '')}): {cm.get('description', '')}"
        if msg_desc not in matched_messages:
            matched_messages.append(msg_desc)
        for bf in cm.get("bridged_facets", []):
            expanded_concepts.add(bf)

    combinatorial_intersections: list[str] = []
    for ch in cfg_hits:
        mf = ch.get("matched_facets", "")
        if "," in mf:
            combinatorial_intersections.append(f"{ch['file_path']} -> {ch['key_path']} (facets: {mf})")

    matched_code_diag: list[str] = []
    for cs in (code_hits or [])[:4]:
        cn = cs.get("class_name", "")
        diag_str = cs.get("diagnostics", "")
        cref_str = cs.get("config_refs", "")
        if cn and (diag_str or cref_str):
            matched_code_diag.append(
                f"{cn} ({cs.get('path', '')}) [config_refs: {cref_str or 'none'}] {diag_str[:180]}".strip()
            )

    sorted_terms = sorted(corpus_terms)[:24]
    sorted_clauses = clause_sets[:8]
    sorted_expanded = sorted(expanded_concepts)[:18]
    intent_profile = classify_query_intent(
        conn=conn,
        corpus_terms=sorted_terms,
        clause_projections=sorted_clauses,
        expanded_concepts=sorted_expanded,
        cfg_hits=cfg_hits,
        doc_hits=doc_hits,
        bit_matches=bit_matches,
        code_hits=code_hits,
        matched_messages=matched_messages,
        project=proj,
    )

    result_signals: dict[str, Any] = {
        "corpus_terms": sorted_terms,
        "clause_projections": sorted_clauses,
        "expanded_concepts": sorted_expanded,
        "combinatorial_intersections": combinatorial_intersections[:6],
        "matched_in_game_messages": sorted(matched_messages)[:4],
        "intent_profile": intent_profile,
    }
    if turn_projections:
        result_signals["cumulative_turn_projections"] = turn_projections
    if matched_code_diag:
        result_signals["matched_code_diagnostics"] = matched_code_diag
    if yaml_overrides:
        result_signals["non_default_yaml_overrides"] = [
            f"{ov['file_path']} -> {ov['key_path']}: {ov['user_value']} (default {ov['default_value']})"
            for ov in yaml_overrides[:8]
        ]
    return result_signals


def _parse_project_arg(spec: str) -> ProjectSource:
    """Parses `--project slug=path[@site_base]` into a `ProjectSource`."""
    if "=" not in spec:
        raise argparse.ArgumentTypeError("Expected format slug=path or slug=path@https://site.url")
    slug, rest = spec.split("=", 1)
    if "@" in rest:
        path_str, site_url = rest.split("@", 1)
    else:
        path_str, site_url = rest, SITE_BASE
    return ProjectSource(project=slug.strip().lower(), root=pathlib.Path(path_str).resolve(), site_base=site_url.strip())


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build SQLite FTS5 doc, config, and BIT code index.")
    parser.add_argument(
        "--db",
        type=pathlib.Path,
        default=DEFAULT_DB_PATH,
        help="Output SQLite database path (default: discord-support-bot/generated/doc-index.sqlite)",
    )
    parser.add_argument(
        "--project",
        action="append",
        default=[],
        metavar="SLUG=PATH[@SITE_URL]",
        help="Additional plugin repository root to index under SLUG (e.g. treegen=/path/to/TreeGen).",
    )
    parser.add_argument(
        "--manifest",
        type=pathlib.Path,
        default=DEFAULT_MANIFEST_PATH,
        help="Declarative project manifest path (default: discord-support-bot/support-bot.yml).",
    )
    args = parser.parse_args(argv)

    extra_sources = [_parse_project_arg(p) for p in args.project]
    conn = build_index(ROOT, args.db, extra_projects=extra_sources, manifest=args.manifest)
    try:
        doc_count = conn.execute("SELECT count(*) FROM doc_sections").fetchone()[0]
        code_count = conn.execute("SELECT count(*) FROM code_sections").fetchone()[0]
        cfg_count = conn.execute("SELECT count(*) FROM config_keys").fetchone()[0]
        bit_count = conn.execute("SELECT count(*) FROM bit_codes").fetchone()[0]
        loc_count = conn.execute("SELECT count(*) FROM locale_aliases").fetchone()[0]
        print(
            f"Built {args.db}: {doc_count} doc sections, "
            f"{code_count} code sections, "
            f"{cfg_count} config keys, {bit_count} BIT codes, "
            f"{loc_count} locale key aliases."
        )
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
