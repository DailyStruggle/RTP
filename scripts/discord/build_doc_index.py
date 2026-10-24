#!/usr/bin/env python3
"""
Compiles repository operator documentation, shipped YAML configuration schemas,
and deterministic BIT fault codes into a local SQLite FTS5 index for the Discord
Forum support and triage bot.

Tables generated in `scripts/discord/generated/doc-index.sqlite`:
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
import pathlib
import re
import sqlite3
from dataclasses import dataclass
from typing import Iterable

ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_DB_PATH = ROOT / "scripts" / "discord" / "generated" / "doc-index.sqlite"
SITE_BASE = "https://dailystruggle.github.io/RTP"

ALLOWED_DEV_DOCS = frozenset({"BIT_CODES.md", "SUPPORT_MATRIX.md"})
EXCLUDED_DOC_PREFIXES = (
    "adr/",
    "architecture/",
    "publishing/",
)

DIRECTIVE_RE = re.compile(r"^#\s*(@(?:type|options|range|unit|source|default))\s*:\s*(.+?)\s*$")
KEY_LINE_RE = re.compile(r"^(?P<indent>\s*)(?P<key>[A-Za-z0-9_.-]+)\s*:\s*(?P<val>.*?)\s*$")
HEADING_RE = re.compile(r"^(#{1,3})\s+(.+?)\s*$")
SLUG_STRIP_RE = re.compile(r"[^\w\s-]")
SLUG_SPACE_RE = re.compile(r"[-\s]+")

STOPWORDS = frozenset({
    "a", "an", "and", "are", "as", "at", "be", "by", "can", "do", "does",
    "for", "from", "how", "i", "in", "into", "is", "it", "my", "of", "on",
    "or", "set", "setting", "the", "to", "use", "what", "when", "where",
    "which", "with", "why", "you", "your",
})

SYNONYM_EXPANSIONS: dict[str, tuple[str, ...]] = {
    "radius": ("shape.radius", "centerRadius", "teleport", "area", "distance", "QUICK_START"),
    "spawn": ("centerRadius", "shape.radius", "center", "hole", "distance"),
    "distance": ("shape.radius", "centerRadius", "radius", "QUICK_START"),
    "border": ("worldBorderOverride", "shape.radius", "worldborder"),
    "gui": ("openOnBareRtp", "guimenu.yml", "ADDONS", "menu"),
    "menu": ("openOnBareRtp", "guimenu.yml", "ADDONS", "gui"),
    "disable": ("openOnBareRtp", "false", "off"),
    "import": ("MIGRATION", "betterrtp", "ezrtp", "justrtp"),
    "migrate": ("MIGRATION", "import", "betterrtp", "ezrtp", "justrtp"),
    "betterrtp": ("MIGRATION", "import", "betterrtp"),
    "ezrtp": ("MIGRATION", "import", "ezrtp"),
    "justrtp": ("MIGRATION", "import", "justrtp"),
    "folia": ("SUPPORT_MATRIX", "folia", "region"),
    "fabric": ("SUPPORT_MATRIX", "fabric", "mods"),
    "neoforge": ("SUPPORT_MATRIX", "neoforge", "mods"),
}


@dataclass(frozen=True)
class DocSection:
    title: str
    heading: str
    path: str
    url: str
    body: str
    tags: str


@dataclass(frozen=True)
class ConfigKeyEntry:
    file_path: str
    key_path: str
    default_value: str
    directives: str
    description: str


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


def slugify_heading(heading: str) -> str:
    """Converts a Markdown heading into a MkDocs-compatible URL anchor slug."""
    cleaned = heading.replace("`", "").strip().lower()
    cleaned = SLUG_STRIP_RE.sub("", cleaned)
    return SLUG_SPACE_RE.sub("-", cleaned).strip("-")


def doc_site_url(docs_rel: str, heading_slug: str = "") -> str:
    """Computes the canonical https://dailystruggle.github.io/RTP/... URL for a doc."""
    norm = docs_rel.replace("\\", "/")
    if norm.startswith("docs/"):
        norm = norm[len("docs/"):]
    if norm.endswith(".md"):
        norm = norm[:-3]
    parts = [p for p in norm.split("/") if p]
    if parts and parts[-1] in ("index", "INDEX", "README"):
        parts.pop()
    base = SITE_BASE + "/" + ("/".join(parts) + "/" if parts else "")
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


def split_markdown_sections(path: pathlib.Path, repo_root: pathlib.Path = ROOT) -> list[DocSection]:
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
        url = doc_site_url(rel_docs, current_slug)
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
                if level in (2, 3):
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
    under `rtp-plugin/src/main/resources` and `addons/**/src/main/resources`.
    Excludes `lang/**`, hidden `.lang.yml` files, `plugin.yml`, and test fixtures.
    """
    results: list[tuple[str, pathlib.Path]] = []
    plugin_res = repo_root / "rtp-plugin" / "src" / "main" / "resources"
    if plugin_res.is_dir():
        for yml in sorted(plugin_res.rglob("*.yml")):
            rel = yml.relative_to(plugin_res).as_posix()
            if rel.startswith("lang/") or "/." in f"/{rel}" or rel == "plugin.yml":
                continue
            results.append((rel, yml))

    addons_dir = repo_root / "addons"
    if addons_dir.is_dir():
        for res_dir in sorted(addons_dir.glob("**/src/main/resources")):
            for yml in sorted(res_dir.rglob("*.yml")):
                rel = yml.relative_to(res_dir).as_posix()
                if "/." in f"/{rel}" or rel == "plugin.yml" or rel.startswith("bit/"):
                    continue
                results.append((rel, yml))
    return results


def parse_yaml_config_keys(logical_path: str, yml_path: pathlib.Path) -> list[ConfigKeyEntry]:
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
                if comment_body and not comment_body.startswith("---"):
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
        full_description = comment_text
        if parent_comments and parent_comments not in comment_text:
            full_description = f"{parent_comments} {comment_text}".strip()

        indent_stack.append((indent, key, comment_text))
        dotted_key = ".".join(p[1] for p in indent_stack)

        default_val = val if val else (pending_default_override or "")
        if default_val.startswith('"') and default_val.endswith('"') and len(default_val) >= 2:
            default_val = default_val[1:-1]
        elif default_val.startswith("'") and default_val.endswith("'") and len(default_val) >= 2:
            default_val = default_val[1:-1]

        directives_str = "; ".join(pending_directives)

        if val or pending_directives or comment_text:
            entries.append(
                ConfigKeyEntry(
                    file_path=logical_path,
                    key_path=dotted_key,
                    default_value=default_val,
                    directives=directives_str,
                    description=full_description,
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
        (e.file_path, e.key_path): e for e in entries
    }
    region_shape = region_entries.get(("definitions/regions/default.yml", "shape"))
    region_vert = region_entries.get(("definitions/regions/default.yml", "vert"))

    synthesized: list[ConfigKeyEntry] = []
    for entry in entries:
        if entry.file_path != "config.yml":
            continue
        if entry.key_path.startswith("defaults.shape."):
            short_key = entry.key_path[len("defaults."):]
            if ("definitions/regions/default.yml", short_key) not in region_entries:
                extra_desc = region_shape.description if region_shape else ""
                combined_desc = f"{entry.description} {extra_desc}".strip()
                synthesized.append(
                    ConfigKeyEntry(
                        file_path="definitions/regions/default.yml",
                        key_path=short_key,
                        default_value=entry.default_value,
                        directives=entry.directives,
                        description=combined_desc,
                    )
                )
        elif entry.key_path.startswith("defaults.vert."):
            short_key = entry.key_path[len("defaults."):]
            if ("definitions/regions/default.yml", short_key) not in region_entries:
                extra_desc = region_vert.description if region_vert else ""
                combined_desc = f"{entry.description} {extra_desc}".strip()
                synthesized.append(
                    ConfigKeyEntry(
                        file_path="definitions/regions/default.yml",
                        key_path=short_key,
                        default_value=entry.default_value,
                        directives=entry.directives,
                        description=combined_desc,
                    )
                )
    return entries + synthesized


def parse_bit_codes(repo_root: pathlib.Path = ROOT) -> list[BitCodeEntry]:
    """
    Parses `docs/dev/bit-codes.lock` and merges metadata + remediation from
    `docs/dev/BIT_CODES.md` into deterministic `BitCodeEntry` rows.
    """
    lock_path = repo_root / "docs" / "dev" / "bit-codes.lock"
    md_path = repo_root / "docs" / "dev" / "BIT_CODES.md"

    md_details: dict[str, tuple[str, str, str]] = {}
    if md_path.is_file():
        for line in md_path.read_text(encoding="utf-8").splitlines():
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

    for line in lock_path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        parts = stripped.split()
        if len(parts) < 4:
            continue
        namespace, id_str, key, severity = parts[0], parts[1], parts[2], parts[3]
        code_id = int(id_str)
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
            )
        )
    return results


def build_index(
    repo_root: pathlib.Path = ROOT,
    db_path: pathlib.Path = DEFAULT_DB_PATH,
) -> sqlite3.Connection:
    """Builds the SQLite FTS5 documentation, YAML schema, and BIT code database."""
    if str(db_path) != ":memory:":
        db_path.parent.mkdir(parents=True, exist_ok=True)
        if db_path.exists():
            db_path.unlink()

    conn = sqlite3.connect(str(db_path))
    conn.row_factory = sqlite3.Row

    with conn:
        conn.execute("DROP TABLE IF EXISTS doc_sections")
        conn.execute("DROP TABLE IF EXISTS config_keys")
        conn.execute("DROP TABLE IF EXISTS bit_codes")

        conn.execute(
            """
            CREATE VIRTUAL TABLE doc_sections USING fts5(
                title,
                heading,
                path,
                url UNINDEXED,
                body,
                tags,
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
                tokenize='porter unicode61'
            )
            """
        )
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
                remediation TEXT NOT NULL
            )
            """
        )
        conn.execute("CREATE INDEX idx_bit_codes_ns_id ON bit_codes(namespace, id)")

        doc_rows: list[DocSection] = []
        for doc_file in iter_doc_files(repo_root):
            doc_rows.extend(split_markdown_sections(doc_file, repo_root))

        conn.executemany(
            "INSERT INTO doc_sections(title, heading, path, url, body, tags) VALUES (?, ?, ?, ?, ?, ?)",
            [(d.title, d.heading, d.path, d.url, d.body, d.tags) for d in doc_rows],
        )

        config_rows: list[ConfigKeyEntry] = []
        for logical_path, yml_path in iter_shipped_yaml_files(repo_root):
            config_rows.extend(parse_yaml_config_keys(logical_path, yml_path))
        config_rows = expand_region_inherited_keys(config_rows)

        conn.executemany(
            "INSERT INTO config_keys(file_path, key_path, default_value, directives, description) VALUES (?, ?, ?, ?, ?)",
            [(c.file_path, c.key_path, c.default_value, c.directives, c.description) for c in config_rows],
        )

        bit_rows = parse_bit_codes(repo_root)
        conn.executemany(
            """
            INSERT INTO bit_codes(namespace, id, key, severity, tier, subsystem, summary, remediation)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [
                (b.namespace, b.id, b.key, b.severity, b.tier, b.subsystem, b.summary, b.remediation)
                for b in bit_rows
            ],
        )

    return conn


def _tokenize_query(question: str) -> list[str]:
    """Extracts safe alphanumeric + dotted tokens from a natural-language question."""
    raw_tokens = re.findall(r"[A-Za-z0-9_.-]+", question)
    tokens: list[str] = []
    for tok in raw_tokens:
        low = tok.lower().strip(".-_")
        if not low or (low in STOPWORDS and "." not in low):
            continue
        if low not in tokens:
            tokens.append(low)
    return tokens


def _build_fts_or_query(tokens: Iterable[str]) -> str:
    """Builds a safe FTS5 OR expression quoting each token."""
    expanded: list[str] = []
    for tok in tokens:
        if tok not in expanded:
            expanded.append(tok)
        for syn in SYNONYM_EXPANSIONS.get(tok.lower(), ()):
            syn_clean = syn.lower()
            if syn_clean not in expanded:
                expanded.append(syn_clean)

    quoted: list[str] = []
    for tok in expanded:
        safe = tok.replace('"', "")
        if safe:
            quoted.append(f'"{safe}"')
    return " OR ".join(quoted)


def search_config_keys(
    conn: sqlite3.Connection,
    query: str,
    limit: int = 5,
) -> list[dict[str, str]]:
    """
    Searches `config_keys` by exact key_path match first, followed by FTS5 BM25
    ranking with domain boosts for core operator files (`definitions/regions/default.yml`,
    `addons/guimenu.yml`, `config.yml`, `safety.yml`).
    """
    q_strip = query.strip()
    if not q_strip:
        return []

    exact_rows = conn.execute(
        """
        SELECT file_path, key_path, default_value, directives, description
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

    results: list[dict[str, str]] = [dict(r) for r in exact_rows]
    seen = {(r["file_path"], r["key_path"]) for r in results}

    tokens = _tokenize_query(q_strip)
    fts_expr = _build_fts_or_query(tokens)
    if not fts_expr:
        return results[:limit]

    fts_rows = conn.execute(
        """
        SELECT
            file_path,
            key_path,
            default_value,
            directives,
            description,
            bm25(config_keys, 2.0, 5.0, 1.0, 1.5, 3.0) AS score
        FROM config_keys
        WHERE config_keys MATCH ?
        ORDER BY score ASC
        LIMIT 25
        """,
        (fts_expr,),
    ).fetchall()

    q_lower = q_strip.lower()
    wants_radius = any(w in q_lower for w in ("radius", "spawn", "distance", "border", "far", "hole"))
    wants_gui = any(w in q_lower for w in ("gui", "menu", "chest", "bare", "openonbarertp"))

    def rank_row(row: sqlite3.Row) -> float:
        score = float(row["score"])
        fp = row["file_path"]
        kp = row["key_path"]
        if wants_radius:
            if fp == "definitions/regions/default.yml" and kp in ("shape.radius", "shape.centerRadius", "worldBorderOverride"):
                score -= 20.0
            elif fp == "config.yml" and kp in ("defaults.shape.radius", "defaults.shape.centerRadius"):
                score -= 10.0
        if wants_gui:
            if fp == "addons/guimenu.yml" and kp == "openOnBareRtp":
                score -= 25.0
        return score

    for row in sorted(fts_rows, key=rank_row):
        pair = (row["file_path"], row["key_path"])
        if pair not in seen:
            seen.add(pair)
            results.append(
                {
                    "file_path": row["file_path"],
                    "key_path": row["key_path"],
                    "default_value": row["default_value"],
                    "directives": row["directives"],
                    "description": row["description"],
                }
            )
            if len(results) >= limit:
                break

    return results[:limit]


def search_docs(
    conn: sqlite3.Connection,
    query: str,
    limit: int = 5,
) -> list[dict[str, str]]:
    """Searches `doc_sections` using FTS5 BM25 ranking with intent-aware boosting."""
    tokens = _tokenize_query(query)
    fts_expr = _build_fts_or_query(tokens)
    if not fts_expr:
        return []

    rows = conn.execute(
        """
        SELECT
            title,
            heading,
            path,
            url,
            body,
            tags,
            bm25(doc_sections, 3.0, 4.0, 2.5, 0.0, 2.0, 3.0) AS score
        FROM doc_sections
        WHERE doc_sections MATCH ?
        ORDER BY score ASC
        LIMIT 25
        """,
        (fts_expr,),
    ).fetchall()

    q_lower = query.lower()
    wants_radius = any(w in q_lower for w in ("radius", "spawn", "distance", "border", "setup"))
    wants_gui = any(w in q_lower for w in ("gui", "menu", "bare", "openonbarertp"))
    wants_migration = any(w in q_lower for w in ("import", "migrate", "migration", "betterrtp", "ezrtp", "justrtp"))
    wants_platform = any(w in q_lower for w in ("folia", "fabric", "neoforge", "spigot", "paper", "velocity"))

    def rank_doc(row: sqlite3.Row) -> float:
        score = float(row["score"])
        path = row["path"]
        body = row["body"]
        if wants_radius and path.endswith("QUICK_START.md"):
            score -= 15.0
            if "/rtp editor" in body or "/rtp admin setup" in body or "shape.radius" in body:
                score -= 10.0
        if wants_gui and (path.endswith("ADDONS.md") or path.endswith("QUICK_START.md")):
            score -= 15.0
            if "openOnBareRtp" in body or "GUI" in row["heading"]:
                score -= 10.0
        if wants_migration and path.endswith("MIGRATION.md"):
            score -= 25.0
        if wants_platform and (path.endswith("SUPPORT_MATRIX.md") or path.endswith("QUICK_START.md")):
            score -= 15.0
        return score

    ranked = sorted(rows, key=rank_doc)
    results: list[dict[str, str]] = []
    for r in ranked[:limit]:
        results.append(
            {
                "title": r["title"],
                "heading": r["heading"],
                "path": r["path"],
                "url": r["url"],
                "body": r["body"],
                "tags": r["tags"],
            }
        )
    return results


def lookup_bit_code(conn: sqlite3.Connection, token: str) -> dict[str, object] | None:
    """
    Deterministic O(1) lookup of a BIT code by key (`RTP_REGION_UNKNOWN_WORLD`)
    or by `NAMESPACE-ID` / `NAMESPACE ID` (`RTP-1301`, `RTP 1301`).
    """
    cleaned = token.strip().strip("`[]()")
    if not cleaned:
        return None

    row = conn.execute(
        """
        SELECT namespace, id, key, severity, tier, subsystem, summary, remediation
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
        row = conn.execute(
            """
            SELECT namespace, id, key, severity, tier, subsystem, summary, remediation
            FROM bit_codes
            WHERE upper(namespace) = ? AND id = ?
            """,
            (ns, num),
        ).fetchone()
        if row:
            return dict(row)

    return None


def extract_bit_codes(conn: sqlite3.Connection, text: str) -> list[dict[str, object]]:
    """
    Scans text (forum post or `mclo.gs` log) for `[RTP bit]` output, BIT keys
    (`RTP_REGION_UNKNOWN_WORLD`, `ACTION_UNKNOWN_STEP_KEY`, etc.), or `RTP-1301` IDs.
    """
    if not text:
        return []

    found: list[dict[str, object]] = []
    seen_keys: set[str] = set()

    # 1. Match explicit BIT keys like RTP_REGION_UNKNOWN_WORLD or ACTION_UNKNOWN_STEP_KEY
    key_candidates = re.findall(
        r"\b(?:ACTION|BIT|HEATMAP|PROBE|RTP|TEST)_[A-Z0-9_]{3,}\b",
        text.upper(),
    )
    for cand in key_candidates:
        entry = lookup_bit_code(conn, cand)
        if entry and str(entry["key"]) not in seen_keys:
            seen_keys.add(str(entry["key"]))
            found.append(entry)

    # 2. Match NAMESPACE-ID tokens like RTP-1301 or [RTP bit] RTP 1301
    ns_id_candidates = re.findall(
        r"\b(ACTION|BIT|HEATMAP|PROBE|RTP|TEST)[-\s:]0*(\d{1,4})\b",
        text.upper(),
    )
    for ns, num in ns_id_candidates:
        entry = lookup_bit_code(conn, f"{ns}-{num}")
        if entry and str(entry["key"]) not in seen_keys:
            seen_keys.add(str(entry["key"]))
            found.append(entry)

    return found


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build SQLite FTS5 doc, config, and BIT code index.")
    parser.add_argument(
        "--db",
        type=pathlib.Path,
        default=DEFAULT_DB_PATH,
        help="Output SQLite database path (default: scripts/discord/generated/doc-index.sqlite)",
    )
    args = parser.parse_args(argv)

    conn = build_index(ROOT, args.db)
    try:
        doc_count = conn.execute("SELECT count(*) FROM doc_sections").fetchone()[0]
        cfg_count = conn.execute("SELECT count(*) FROM config_keys").fetchone()[0]
        bit_count = conn.execute("SELECT count(*) FROM bit_codes").fetchone()[0]
        print(
            f"Built {args.db}: {doc_count} doc sections, "
            f"{cfg_count} config keys, {bit_count} BIT codes."
        )
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
