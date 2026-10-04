#!/usr/bin/env python3
"""Generates the static ADR-104 Web Editor HTML application from repository sources.

Reads:
- Shipped baseline configurations from `rtp-plugin/src/main/resources/`
- Field annotations (@type, @range, @unit, @default, @options) from YAML headers
- Packed markdown documentation from `docs/` and `docs/admin/`

Outputs:
- `docs/editor/index.html` (single-source web editor for MkDocs & GitHub Pages)
"""

import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
RESOURCES_DIR = REPO_ROOT / "rtp-plugin" / "src" / "main" / "resources"
DOCS_DIR = REPO_ROOT / "docs"


def discover_config_files(resources_dir: Path = RESOURCES_DIR) -> list[str]:
    """Deterministically discovers all shipped YAML configuration files from disk.

    Excludes localization mapping catalogs under lang/, internal rename-map dotfiles,
    and server plugin descriptors.
    """
    if not resources_dir.is_dir():
        return []

    found: list[str] = []
    for p in resources_dir.rglob("*.yml"):
        if not p.is_file():
            continue
        rel = p.relative_to(resources_dir).as_posix()
        if p.name.startswith(".") or rel.startswith("lang/") or p.name == "plugin.yml":
            continue
        found.append(rel)

    return sorted(found)


CONFIG_FILES = discover_config_files()


def discover_doc_files(docs_dir: Path = DOCS_DIR) -> dict[str, Path]:
    """Deterministically discovers all shipped Markdown documentation files.

    Includes operator runbooks under admin/**, multi-server proxy guides under
    admin/proxies/**, configuration specifications under admin/configuration/**,
    and root landing/navigation manuals (MAP.md, FOR_SERVER_ADMINS.md, index.md).
    Excludes internal engineering trees (dev/, adr/, architecture/, site/) and
    spigot/listing formatting files.
    """
    if not docs_dir.is_dir():
        return {}

    docs: dict[str, Path] = {}
    for p in sorted(docs_dir.rglob("*.md")):
        if not p.is_file():
            continue
        rel = p.relative_to(docs_dir).as_posix()
        if (
            rel.startswith(("dev/", "adr/", "architecture/", "site/"))
            or p.name.startswith((".", "FRONT_PAGE"))
            or p.name.endswith(".bbcode")
            or p.name.endswith(".bak")
        ):
            continue
        docs[rel] = p

    return docs


DOC_FILES = discover_doc_files()

SAMPLE_REGIONS: dict[str, str] = {}


def parse_yaml_annotations(rel_path: str, content: str) -> dict[str, dict[str, str]]:
    """Extracts field comments and annotations (@type, @range, @unit, etc.) into docTracker entries."""
    db: dict[str, dict[str, str]] = {}
    lines = content.splitlines()

    comment_lines: list[str] = []
    annotations: dict[str, str] = {}

    for line in lines:
        stripped = line.strip()
        if not stripped:
            comment_lines.clear()
            annotations.clear()
            continue

        if stripped.startswith("#"):
            c = stripped[1:].strip()
            if c.startswith("@"):
                m = re.match(r"^@([a-zA-Z0-9_]+)\s*:\s*(.*)$", c)
                if m:
                    annotations[m.group(1).lower()] = m.group(2).strip()
            elif not c.startswith("---") and not c.startswith("Documentation:"):
                comment_lines.append(c)
            continue

        # Look for key: value
        km = re.match(r"^([a-zA-Z0-9_.-]+)\s*:", stripped)
        if km:
            key_name = km.group(1)
            lk = re.sub(r"[^a-z0-9]", "", key_name.lower())

            desc = " ".join(comment_lines).strip()
            # If no comments present on this line, don't create a dummy entry that would shadow markdown docs
            if desc:
                meta_parts = []
                if "type" in annotations:
                    meta_parts.append(f"Type: {annotations['type']}")
                if "range" in annotations:
                    meta_parts.append(f"Range: {annotations['range']}")
                if "unit" in annotations:
                    meta_parts.append(f"Unit: {annotations['unit']}")
                if "options" in annotations:
                    meta_parts.append(f"Options: {annotations['options']}")
                if "default" in annotations:
                    meta_parts.append(f"Default: {annotations['default']}")
                meta_parts.append(f"File: {rel_path}")

                db[lk] = {
                    "title": key_name,
                    "desc": desc,
                    "meta": " | ".join(meta_parts)
                }

            comment_lines.clear()
            annotations.clear()

    return db


def md_to_html(text: str) -> str:
    """Converts a markdown snippet into styled, valid HTML for the doc tracker drawer."""
    if not text:
        return ""
    lines = text.strip().splitlines()
    html_lines = []
    in_list = False
    in_code = False
    in_table = False
    table_headers = []

    for line in lines:
        stripped = line.strip()

        # Code fences
        if stripped.startswith("```"):
            if in_code:
                html_lines.append("</code></pre>")
                in_code = False
            else:
                if in_list:
                    html_lines.append("</ul>")
                    in_list = False
                html_lines.append("<pre style=\"background:var(--dark); padding:8px 12px; border-radius:4px; font-size:0.8rem; overflow-x:auto;\"><code>")
                in_code = True
            continue

        if in_code:
            escaped = (line.replace("&", "&amp;")
                           .replace("<", "&lt;")
                           .replace(">", "&gt;"))
            html_lines.append(escaped)
            continue

        # Tables
        if stripped.startswith("|") and stripped.endswith("|"):
            cells = [c.strip() for c in stripped.strip("|").split("|")]
            if not in_table:
                if in_list:
                    html_lines.append("</ul>")
                    in_list = False
                in_table = True
                table_headers = cells
                html_lines.append("<table style=\"width:100%; border-collapse:collapse; margin:8px 0; font-size:0.78rem;\"><thead><tr>")
                for th in cells:
                    html_lines.append(f"<th style=\"border:1px solid var(--overlay); padding:4px 8px; background:var(--overlay); text-align:left;\">{th}</th>")
                html_lines.append("</tr></thead><tbody>")
                continue
            else:
                if all(re.match(r"^:?-+:?$", c) for c in cells):
                    continue
                html_lines.append("<tr>")
                for td in cells:
                    fmt_td = re.sub(r"`([^`]+)`", r"<code style=\"color:var(--yellow);\"><b>\1</b></code>", td)
                    html_lines.append(f"<td style=\"border:1px solid var(--overlay); padding:4px 8px;\">{fmt_td}</td>")
                html_lines.append("</tr>")
                continue
        elif in_table:
            html_lines.append("</tbody></table>")
            in_table = False

        if not stripped:
            if in_list:
                html_lines.append("</ul>")
                in_list = False
            continue

        # Inline formatting helpers
        formatted = stripped
        formatted = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", formatted)
        formatted = re.sub(r"\*([^*]+)\*", r"<i>\1</i>", formatted)
        formatted = re.sub(r"`([^`]+)`", r"<code style=\"color:var(--yellow);\"><b>\1</b></code>", formatted)
        formatted = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r"<a href=\"\2\" target=\"_blank\" style=\"color:var(--accent);\">\1</a>", formatted)

        # Headings
        if stripped.startswith("#### "):
            if in_list:
                html_lines.append("</ul>")
                in_list = False
            html_lines.append(f"<h5 style=\"margin:10px 0 4px; color:var(--accent); font-size:0.85rem;\">{formatted[5:]}</h5>")
            continue
        if stripped.startswith("### "):
            if in_list:
                html_lines.append("</ul>")
                in_list = False
            html_lines.append(f"<h4 style=\"margin:12px 0 6px; color:var(--green); font-size:0.9rem;\">{formatted[4:]}</h4>")
            continue
        if stripped.startswith("## "):
            if in_list:
                html_lines.append("</ul>")
                in_list = False
            html_lines.append(f"<h3 style=\"margin:14px 0 8px; color:var(--mauve); font-size:0.95rem;\">{formatted[3:]}</h3>")
            continue

        # Lists
        if stripped.startswith("- ") or stripped.startswith("* "):
            if not in_list:
                html_lines.append("<ul style=\"margin:6px 0 6px 18px; padding:0; font-size:0.82rem; line-height:1.4;\">")
                in_list = True
            html_lines.append(f"<li style=\"margin-bottom:4px;\">{formatted[2:]}</li>")
            continue

        if in_list:
            html_lines.append("</ul>")
            in_list = False

        # Blockquotes
        if stripped.startswith("> "):
            html_lines.append(f"<div style=\"border-left:3px solid var(--accent); padding:4px 8px; margin:6px 0; background:rgba(137, 180, 250, 0.08); font-size:0.8rem; color:var(--subtext);\">{formatted[2:]}</div>")
            continue

        # Normal paragraph
        html_lines.append(f"<p style=\"margin:6px 0; font-size:0.82rem; line-height:1.4;\">{formatted}</p>")

    if in_list:
        html_lines.append("</ul>")
    if in_table:
        html_lines.append("</tbody></table>")
    if in_code:
        html_lines.append("</code></pre>")

    return "\n".join(html_lines)


def parse_markdown_docs() -> dict[str, dict[str, str]]:
    """Extracts YAML mapping definitions and parameters from project Markdown documentation.

    Reads markdown tables and section documentation in docs/admin/configuration/*.md
    to supply rich, authoritative descriptions, types, defaults, units, and operator guidance
    for configuration keys in the Contextual Doc Tracker as rendered HTML.
    """
    db: dict[str, dict[str, str]] = {}
    cfg_dir = DOCS_DIR / "admin" / "configuration"
    if not cfg_dir.is_dir():
        return db

    # 1. Parse tables (| Key | Type | Default | Description | or | Key | Meaning | ...)
    for md_file in sorted(cfg_dir.glob("*.md")):
        rel_name = md_file.name
        content = md_file.read_text(encoding="utf-8")
        lines = content.splitlines()

        in_table = False
        headers: list[str] = []
        current_section = ""

        for line in lines:
            stripped = line.strip()

            # Track section headers (e.g. `### shape section`, `### vert section`, `## Shape Engines`)
            header_match = re.match(r"^#{1,6}\s+(.*)", stripped)
            if header_match:
                header_text = header_match.group(1).lower()
                if "shape" in header_text:
                    current_section = "shape"
                elif "vert" in header_text:
                    current_section = "vert"
                elif "teleport" in header_text or "core" in header_text:
                    current_section = ""

            if stripped.startswith("|") and stripped.endswith("|"):
                cells = [c.strip() for c in stripped.strip("|").split("|")]
                if not in_table:
                    # Check if header row
                    lower_cells = [c.lower() for c in cells]
                    if "key" in lower_cells:
                        headers = lower_cells
                        in_table = True
                        continue
                else:
                    # Check separator row
                    if all(re.match(r"^:?-+:?$", c) for c in cells):
                        continue
                    if len(cells) < len(headers):
                        continue

                    row = dict(zip(headers, cells))
                    raw_key = row.get("key", "")
                    # Extract keys from backticks, e.g. `radius` or `radius` / `radius2`
                    key_matches = re.findall(r"`([a-zA-Z0-9_.-]+)`", raw_key)
                    if not key_matches:
                        km = re.match(r"^([a-zA-Z0-9_.-]+)", raw_key.strip("`* "))
                        if km:
                            key_matches = [km.group(1)]

                    desc = row.get("description", "") or row.get("meaning", "") or row.get("purpose", "")
                    val_type = row.get("type", "")
                    val_default = row.get("default", "")

                    meta_parts = []
                    if val_type and val_type != "—":
                        meta_parts.append(f"Type: {val_type}")
                    if val_default and val_default != "—":
                        meta_parts.append(f"Default: {val_default}")
                    meta_parts.append(f"Docs: {rel_name}")

                    meta_str = " | ".join(meta_parts)
                    html_content = md_to_html(desc)

                    raw_def = val_default if (val_default and val_default != "—") else ""

                    for k in key_matches:
                        lk = re.sub(r"[^a-z0-9]", "", k.lower())
                        snippet = f"{k}: {raw_def}" if raw_def else ""
                        entry_obj = {
                            "title": k,
                            "desc": desc,
                            "html": html_content,
                            "meta": meta_str,
                            "defaultVal": raw_def,
                            "defaultSnippet": snippet
                        }
                        # If inside a known section (like shape or vert), record both qualified (e.g. shape.name) and unqualified
                        if current_section:
                            qualified_title = f"{current_section}.{k}"
                            qualified_key = f"{current_section}{lk}"
                            db[qualified_key] = {
                                "title": qualified_title,
                                "desc": desc,
                                "html": html_content,
                                "meta": meta_str,
                                "defaultVal": raw_def,
                                "defaultSnippet": snippet
                            }
                        db[lk] = entry_obj
            else:
                in_table = False

    # 2. Add rich domain sections sourced directly from REGIONS.md, CONFIGURATION.md, CORE_CONFIG.md
    domain_sections = {
        "shape": {
            "title": "shape",
            "meta": "Type: Section / Block | Engines: CIRCLE, SQUARE, POLYGON, ELLIPSE, RECTANGLE | Docs: REGIONS.md",
            "defaultVal": "CIRCLE (4096b, centerRadius: 64)",
            "defaultSnippet": """shape:
  name: "CIRCLE"
  mode: "ACCUMULATE"
  radius: 4096b
  centerRadius: 64
  centerX: 0
  centerZ: 0
  weight: 1.0
  uniquePlacements: "auto"
  expand: false""",
            "markdown": """The **`shape`** block defines the horizontal area where players can land and selects candidate coordinates.

### Selection Modes (`mode:`)
- **`ACCUMULATE`**: *(Recommended)* Even distribution, pre-calculated sectors, 1D Archimedean spiral mapping (ADR-001) or continuous Hilbert key space (ADR-085). Best for general server play.
- **`NEAREST`**: Finds the closest non-blocked candidate location. Fast, but may cause player clustering.
- **`REROLL`**: Simple random selection with retries. Even probability but unbounded retry steps.
- **`NONE`**: Pure candidate walk with zero pre-computation. Ideal for massive radii (> 50,000 blocks).

### Supported Shape Engines
- **`CIRCLE`**: Uniform radial disk bounded by `radius` with inner donut deadzone `centerRadius`.
- **`SQUARE`**: Square bounding frame extending `radius` chunks per side.
- **`POLYGON`**: Arbitrary closed polygon boundary defined by `vertices: [[x1, z1], ...]`.
- **`ELLIPSE`**: Circle with independent X and Z semi-axes (`radius`, `radius2`) to match rectangular world borders.
- **`RECTANGLE`**: Explicit side lengths (`width`, `height`) centered on `centerX`/`centerZ`.
- **`CIRCLE_NORMAL` / `SQUARE_NORMAL`**: Gaussian normal distribution clustering around `mean` with spread `deviation`.

### Distance Units
All distances support spatial unit suffixes:
`256c` (chunks), `4096b` (blocks), `4r` (regions), `5km` (kilometers), or `500nb` (Nether blocks).

> 💡 **Operator Gotcha:** `centerRadius` must be strictly smaller than `radius`. If `centerRadius >= radius`, no pickable band remains!"""
        },
        "vert": {
            "title": "vert",
            "meta": "Type: Section / Block | Engines: JUMP, LINEAR, FIXED | Docs: REGIONS.md",
            "defaultVal": "LINEAR (minY: 32, maxY: 255)",
            "defaultSnippet": """vert:
  name: "LINEAR"
  minY: 32
  maxY: 255
  direction: 2
  requireSkyLight: true""",
            "markdown": """The **`vert`** block controls how the Y coordinate (height / elevation) is chosen once a horizontal position is selected.

### Vertical Adjustor Engines
- **`JUMP`**: Scans vertically using fixed `step` block intervals (default: `16`). Fast for finding open surface terrain.
  - *Nether Caveat:* Because `step` skips in 16-block intervals, it can skip thin 1-2 block ledges. In the Nether, prefer `LINEAR` bottom-up.
- **`LINEAR`**: Thorough scan of every Y level between `minY` and `maxY`.
  - `direction: 0`: **Bottom-up** (scans `minY` to `maxY`). Recommended for Nether floor and cave teleports.
  - `direction: 1`: **Top-down** (scans `maxY` to `minY`). Ideal for surface landings.
  - `direction: 2`: **Middle-out** (starts in middle and scans outward).
  - `direction: 3`: **Edges-in**.
- **`FIXED`**: Places player at an exact mid-air Y level (`y: 64`) with no terrain scan. Designed for skyblock worlds; pair with a platform builder!

### Common Settings
- `minY` / `maxY`: Safe elevation bounds (e.g. `32` to `255`, or `32` to `120` in the Nether).
- `requireSkyLight`: When `true`, accepts only locations with direct sky access (above ground only)."""
        },
        "radius": {
            "title": "radius",
            "meta": "Type: Integer / Distance Suffix | Default: 256c (4,096 blocks) | Docs: REGIONS.md",
            "defaultVal": "4096b",
            "defaultSnippet": "radius: 4096b",
            "markdown": """**Outer coordinate limit** of the region. Players will never land farther than this distance from the center.

### Unit Interpretation
- By default, plain numbers without a suffix are measured in **chunks** (`1 chunk = 16 blocks`).
- Supports explicit suffixes:
  - `256c` / `256chunks` = 4,096 blocks
  - `4096b` / `4096blocks` = 4,096 blocks
  - `4r` / `4regions` = 2,048 blocks (1 region = 32 chunks)
  - `5km` = 5,000 blocks
  - `3mi` = 4,828 blocks
  - `500nb` = 4,000 Overworld blocks (Nether coordinates)

> 💡 **Inheritance:** In `regions/<name>.yml`, `shape: "@config"` inherits `defaults.shape.radius` from `config.yml`."""
        },
        "centerradius": {
            "title": "centerRadius",
            "meta": "Type: Integer / Distance Suffix | Default: 64c (1,024 blocks) | Docs: REGIONS.md",
            "defaultVal": "64",
            "defaultSnippet": "centerRadius: 64",
            "markdown": """**Inner exclusion deadzone** radius (the "donut hole"). Players will never land closer than this distance to the center.

### Key Rules
- Protects spawn monuments, lobbies, central claims, and populated hubs from teleport traffic.
- **Must be strictly smaller than `radius`**. If `centerRadius >= radius`, the selectable band is empty.
- Total pickable band width is `radius - centerRadius` chunks.
- Supports spatial suffixes (e.g. `64c`, `1000b`, `1r`)."""
        },
        "centerx": {
            "title": "centerX",
            "meta": "Type: Integer | Default: 0 | Docs: REGIONS.md",
            "defaultVal": "0",
            "defaultSnippet": "centerX: 0",
            "markdown": "Horizontal X coordinate of region center (in chunk coordinates). Multiply by 16 for block coordinate."
        },
        "centerz": {
            "title": "centerZ",
            "meta": "Type: Integer | Default: 0 | Docs: REGIONS.md",
            "defaultVal": "0",
            "defaultSnippet": "centerZ: 0",
            "markdown": "Horizontal Z coordinate of region center (in chunk coordinates). Multiply by 16 for block coordinate."
        },
        "weight": {
            "title": "weight",
            "meta": "Type: Double | Range: > 0.0 | Default: 1.0 | Docs: REGIONS.md",
            "defaultVal": "1.0",
            "defaultSnippet": "weight: 1.0",
            "markdown": "Archimedean spiral distribution bias across valid band (>1.0 pulls center, <1.0 pushes edge, 1.0 uniform)."
        },
        "expand": {
            "title": "expand",
            "meta": "Type: Boolean | Default: false | Docs: REGIONS.md",
            "defaultVal": "false",
            "defaultSnippet": "expand: false",
            "markdown": "Dynamic frontier growth outward as tiers are exhausted. Ignored on POLYGON."
        },
        "mode": {
            "title": "mode",
            "meta": "Type: Enum [ACCUMULATE, NEAREST, REROLL, NONE] | Default: ACCUMULATE | Docs: REGIONS.md",
            "defaultVal": "ACCUMULATE",
            "defaultSnippet": "mode: \"ACCUMULATE\"",
            "markdown": """Coordinate candidate selection algorithm within the region boundary.

- **`ACCUMULATE`**: *(Recommended)* Pre-computes sector weights along a 1D Archimedean spiral mapping (ADR-001) or continuous Hilbert space (ADR-085). Produces uniform dispersion without clustering.
- **`NEAREST`**: Picks the nearest safe coordinate. Fast, but repeatedly places players in similar areas.
- **`REROLL`**: Random uniform sampling with retries.
- **`NONE`**: Pure spiral walk with zero pre-computation. Essential for massive radii (> 50,000 blocks / 3,125 chunks) to avoid long startup calculation."""
        },
        "vertices": {
            "title": "vertices",
            "meta": "Type: List of [X, Z] integer pairs | Min: 3 vertices | Docs: REGIONS.md",
            "defaultVal": "[[-2000, 3000], [2000, 3000], [2000, -3000], [-2000, -3000]]",
            "defaultSnippet": """vertices:
  - [-2000, 3000]
  - [2000, 3000]
  - [2000, -3000]
  - [-2000, -3000]""",
            "markdown": """Ordered boundary coordinate pairs defining an arbitrary closed polygon (when `shape: POLYGON`).

### Format
Uses identical syntax to Chunky `/chunky shape polygon`:
```yaml
vertices:
  - [-2000, 3000]
  - [2000, 3000]
  - [2000, -3000]
  - [-2000, -3000]
```

### Constraints (ADR-034)
- Requires at least 3 vertices.
- Vertices must not be all collinear.
- Boundary edges must not self-intersect.
- `expand` is not supported on polygons (authoring your own boundary locks the perimeter)."""
        },
        "uniqueplacements": {
            "title": "uniquePlacements",
            "meta": "Type: Integer / 'auto' | Default: 0 | Docs: REGIONS.md",
            "defaultVal": "\"auto\"",
            "defaultSnippet": "uniquePlacements: \"auto\"",
            "markdown": """Chunk radius cleared around a landing spot once consumed so it is never reused for future teleports.

- **`0`**: Off (spots can be reused).
- **`1`**: The landing chunk only is retired.
- **`N`**: An `(2N-1)x(2N-1)` chunk square around the landing is retired.
- **`auto`**: Automatically matches the server's effective view distance (lowest power of 2 at or under view distance, e.g. 10 -> 8 chunks).

When paired with `expand: true` in dual-layer shapes, it enables zero-memory dyadic stride downsampling ($S = (2R_u-1)^2$), keeping concurrent players isolated by view distance while driving outward frontier expansion."""
        },
        "backlogcachecap": {
            "title": "backlogCacheCap",
            "meta": "Type: Integer | Default: 1000 (lite: 0) | Docs: REGIONS.md (ADR-028)",
            "defaultVal": "1000",
            "defaultSnippet": "backlogCacheCap: 1000",
            "markdown": """Capacity of the **L3 Backlog Cache** unverified staging buffer (ADR-028).

### How It Works
- Pre-picks candidate coordinates along the spiral without performing any chunk loads or database writes.
- Each region pulse picks the oldest candidate, determines its 32x32 Anvil/Linear region file bin, and classifies all candidates in that bin off-tick in one amortized pass.
- Keeps the verified `cacheCap` constantly replenished so `/rtp` teleports are instantaneous.
- On `rtp-lite`, defaults to `0` (disabled) to conserve RAM."""
        },
        "activechunkcap": {
            "title": "activeChunkCap",
            "meta": "Type: Integer | Default: 10 | Docs: REGIONS.md",
            "defaultVal": "10",
            "defaultSnippet": "activeChunkCap: 10",
            "markdown": """Maximum number of chunks held in the **L1 Hot Queue** with loaded chunk tickets (`keep(true)`) for zero-latency instant teleports.

- When a location is verified, its chunk is kept loaded in L1.
- If L1 reaches `activeChunkCap`, additional verified locations are demoted to the L2 Cold Queue (chunks released from memory, reloaded asynchronously on demand).
- Prevents server RAM exhaustion and force-loading leaks (S-002)."""
        },
        "cachecap": {
            "title": "cacheCap",
            "meta": "Type: Integer | Default: 50 | Docs: REGIONS.md",
            "defaultVal": "50",
            "defaultSnippet": "cacheCap: 50",
            "markdown": """Target capacity of verified safe locations maintained in the background pool.

- As players execute `/rtp`, locations are popped from this queue.
- Background asynchronous workers automatically calculate new candidate coordinates to keep this queue topped up.
- Higher values absorb teleport rushes during peak server events."""
        },
        "spatialresolution": {
            "title": "spatialResolution",
            "meta": "Type: Integer >= 1 or 'auto' | Default: auto | Docs: REGIONS.md",
            "defaultVal": '"auto"',
            "defaultSnippet": 'spatialResolution: "auto"',
            "markdown": "Spatial resolution for MemoryShape rejection tracking (positive integer or 'auto')."
        },
        "requirepermission": {
            "title": "requirePermission",
            "meta": "Type: Boolean | Default: false | Docs: REGIONS.md / WORLDS.md",
            "defaultVal": "false",
            "defaultSnippet": "requirePermission: false",
            "markdown": "If true, players must have explicit node permission (e.g. `rtp.regions.<name>`) to use this region."
        },
        "world": {
            "title": "world",
            "meta": "Type: String / Placeholder | Default: '[0]' | Docs: REGIONS.md / WORLDS.md",
            "defaultVal": "\"[0]\"",
            "defaultSnippet": "world: \"[0]\"",
            "markdown": """The target destination world for this region.

- Supports exact world names (e.g. `world`, `world_nether`, `custom_world`).
- Supports world index placeholders:
  - `[0]`: Primary host world (default Overworld)
  - `[1]`: Nether world
  - `[2]`: The End world"""
        },
        "price": {
            "title": "price",
            "meta": "Type: Double >= 0.0 | Unit: Currency | Docs: ECONOMY.md",
            "defaultVal": "0.0",
            "defaultSnippet": "price: 0.0",
            "markdown": """Vault economy cost deducted from the player upon successful teleportation.

- Set to `0.0` for free teleports.
- If teleportation fails or is cancelled, fees are refunded automatically according to `economy.yml`.
- Accepts `@economy` to inherit global economy defaults."""
        }
    }

    for lk, entry in domain_sections.items():
        html_val = md_to_html(entry["markdown"])
        db[lk] = {
            "title": entry["title"],
            "desc": entry["markdown"],
            "html": html_val,
            "meta": entry["meta"],
            "defaultVal": entry.get("defaultVal", ""),
            "defaultSnippet": entry.get("defaultSnippet", "")
        }

    return db


def load_configs() -> tuple[dict[str, str], dict[str, dict[str, str]]]:
    configs: dict[str, str] = {}
    doc_tracker: dict[str, dict[str, str]] = {}

    # 1. Parse markdown documentation to extract authoritative YAML mapping definitions
    md_tracker = parse_markdown_docs()
    doc_tracker.update(md_tracker)

    # 2. Load authentic resources for file editing
    for rel in CONFIG_FILES:
        path = RESOURCES_DIR / rel.replace("/", "\\")
        if not path.is_file():
            path = RESOURCES_DIR / rel
        if path.is_file():
            text = path.read_text(encoding="utf-8")
            configs[rel] = text

    # 3. Add sample regions
    for rel, content in SAMPLE_REGIONS.items():
        configs[rel] = content

    return configs, doc_tracker


def load_docs() -> dict[str, str]:
    docs: dict[str, str] = {}
    for name, path in DOC_FILES.items():
        if path.is_file():
            docs[name] = path.read_text(encoding="utf-8")
        else:
            docs[name] = f"# {name}\n\nDocumentation file `{path.name}` not found in repository."
    return docs


def generate_html() -> str:
    configs, doc_tracker = load_configs()
    docs = load_docs()

    configs_json = json.dumps(configs, indent=2)
    docs_json = json.dumps(docs, indent=2)
    tracker_json = json.dumps(doc_tracker, indent=2)

    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>LeafRTP Web Workspace - Visual Region Editor & Staging</title>
  <style>
    :root {{
      --bg: #181825;
      --surface: #1e1e2e;
      --overlay: #313244;
      --text: #cdd6f4;
      --subtext: #a6adc8;
      --accent: #89b4fa;
      --green: #a6e3a1;
      --red: #f38ba8;
      --yellow: #f9e2af;
      --mauve: #cba6f7;
      --teal: #94e2d5;
      --blue: #89b4fa;
      --dark: #11111b;
    }}
    * {{ box-sizing: border-box; }}
    body {{
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
      margin: 0;
      display: flex;
      flex-direction: column;
      height: 100vh;
      background: var(--bg);
      color: var(--text);
      overflow: hidden;
    }}
    #top-nav {{
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 8px 16px;
      background: var(--dark);
      border-bottom: 1px solid var(--overlay);
    }}
    .brand {{
      font-weight: bold;
      font-size: 1.05rem;
      color: var(--accent);
      display: flex;
      align-items: center;
      gap: 10px;
    }}
    .session-badge {{
      font-size: 0.75rem;
      padding: 2px 8px;
      border-radius: 12px;
      background: rgba(166, 227, 161, 0.15);
      color: var(--green);
      border: 1px solid rgba(166, 227, 161, 0.3);
      font-weight: 500;
    }}
    .nav-tabs {{ display: flex; gap: 4px; }}
    .nav-tab {{
      background: transparent;
      border: none;
      color: var(--subtext);
      padding: 6px 14px;
      border-radius: 6px;
      cursor: pointer;
      font-size: 0.88rem;
      font-weight: 500;
      transition: all 0.15s;
    }}
    .nav-tab:hover {{ background: var(--overlay); color: var(--text); }}
    .nav-tab.active {{ background: var(--accent); color: var(--dark); font-weight: bold; }}
    .nav-actions {{ display: flex; gap: 8px; align-items: center; }}
    .btn {{
      background: var(--overlay);
      color: var(--text);
      border: 1px solid var(--overlay);
      padding: 6px 14px;
      border-radius: 5px;
      font-size: 0.82rem;
      font-weight: 600;
      cursor: pointer;
      transition: background 0.15s, border-color 0.15s;
    }}
    .btn:hover {{ border-color: var(--accent); }}
    .btn-primary {{ background: var(--green); color: var(--dark); border: none; }}
    .btn-primary:hover {{ opacity: 0.9; }}
    #workspace {{ flex: 1; display: flex; overflow: hidden; position: relative; }}
    .panel-view {{ display: none; width: 100%; height: 100%; }}
    .panel-view.active {{ display: flex; }}

    /* Panel 1: Regions */
    #region-map-container {{ flex: 1; position: relative; background: #12121e; display: flex; flex-direction: column; }}
    #map-canvas {{ flex: 1; width: 100%; height: 100%; cursor: crosshair; }}
    #map-hud {{
      position: absolute;
      top: 14px;
      left: 14px;
      background: rgba(17,17,27,0.9);
      padding: 8px 14px;
      border-radius: 6px;
      border: 1px solid var(--overlay);
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 0.82rem;
      pointer-events: none;
    }}
    #map-layers {{
      position: absolute;
      top: 14px;
      right: 14px;
      background: rgba(17,17,27,0.9);
      padding: 8px 12px;
      border-radius: 6px;
      border: 1px solid var(--overlay);
      display: flex;
      gap: 12px;
      font-size: 0.8rem;
    }}
    #map-layers label {{ cursor: pointer; display: flex; align-items: center; gap: 4px; user-select: none; }}
    #region-sidebar {{
      width: 400px;
      background: var(--surface);
      border-left: 1px solid var(--overlay);
      display: flex;
      flex-direction: column;
      padding: 18px;
      overflow-y: auto;
    }}
    .diff-box {{
      background: var(--dark);
      border-radius: 6px;
      padding: 12px;
      border: 1px solid var(--overlay);
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 0.8rem;
      white-space: pre-wrap;
      line-height: 1.45;
    }}
    .diff-add {{ color: var(--green); }}
    .diff-del {{ color: var(--red); }}
    .diff-warn {{ color: var(--yellow); }}
    .control-group {{ margin-bottom: 14px; }}
    .control-group label {{ display: block; font-size: 0.8rem; color: var(--subtext); margin-bottom: 4px; }}
    .control-input {{
      width: 100%;
      background: var(--dark);
      border: 1px solid var(--overlay);
      color: var(--text);
      padding: 6px 10px;
      border-radius: 4px;
      font-family: ui-monospace, monospace;
      font-size: 0.85rem;
    }}
    .control-input:focus {{ outline: none; border-color: var(--accent); }}

    /* Panel 2: Telemetry */
    .telemetry-section-title {{
      color: var(--text);
      font-size: 1.05rem;
      font-weight: 600;
      margin: 28px 0 12px 0;
      display: flex;
      align-items: center;
      gap: 8px;
    }}
    .telemetry-section-title:first-of-type {{ margin-top: 16px; }}
    .telemetry-grid {{
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
      gap: 14px;
      margin-top: 6px;
    }}
    .telemetry-card {{
      background: var(--surface);
      border: 1px solid var(--overlay);
      border-radius: 8px;
      padding: 16px;
      position: relative;
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      transition: border-color 0.15s ease;
    }}
    .telemetry-card:hover {{ border-color: var(--accent); }}
    .telemetry-card h4 {{
      margin: 0 0 6px 0;
      color: var(--subtext);
      font-size: 0.82rem;
      font-weight: 500;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }}
    .telemetry-val {{ font-size: 1.75rem; font-weight: bold; margin-bottom: 4px; font-family: ui-monospace, monospace; }}
    .telemetry-desc {{ color: var(--subtext); font-size: 0.74rem; margin: 0; line-height: 1.4; }}
    .telemetry-bar-bg {{
      width: 100%;
      height: 6px;
      background: var(--dark);
      border-radius: 3px;
      margin: 6px 0;
      overflow: hidden;
    }}
    .telemetry-bar-fill {{
      height: 100%;
      border-radius: 3px;
      transition: width 0.3s ease;
    }}
    .telemetry-badge {{
      display: inline-block;
      padding: 2px 6px;
      border-radius: 4px;
      font-size: 0.72rem;
      font-weight: 600;
      font-family: ui-monospace, monospace;
    }}
    .badge-ok {{ background: rgba(166, 227, 161, 0.15); color: var(--green); border: 1px solid rgba(166, 227, 161, 0.3); }}
    .badge-warn {{ background: rgba(249, 226, 175, 0.15); color: var(--yellow); border: 1px solid rgba(249, 226, 175, 0.3); }}
    .badge-err {{ background: rgba(243, 139, 168, 0.15); color: var(--red); border: 1px solid rgba(243, 139, 168, 0.3); }}
    .telemetry-table {{
      width: 100%;
      border-collapse: collapse;
      margin-top: 10px;
      font-size: 0.82rem;
      background: var(--surface);
      border-radius: 8px;
      overflow: hidden;
      border: 1px solid var(--overlay);
    }}
    .telemetry-table th {{
      background: var(--dark);
      color: var(--subtext);
      text-align: left;
      padding: 10px 14px;
      font-weight: 600;
      font-size: 0.76rem;
      text-transform: uppercase;
      letter-spacing: 0.5px;
      border-bottom: 1px solid var(--overlay);
    }}
    .telemetry-table td {{
      padding: 10px 14px;
      border-bottom: 1px solid rgba(49, 50, 68, 0.6);
      color: var(--text);
      font-family: ui-monospace, monospace;
    }}
    .telemetry-table tr:last-child td {{ border-bottom: none; }}
    .telemetry-table tr:hover td {{ background: rgba(137, 180, 250, 0.04); }}

    /* Panel 3: Configs */
    #config-sidebar {{
      width: 280px;
      background: var(--bg);
      border-right: 1px solid var(--overlay);
      padding: 12px;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }}
    .cfg-search-box {{
      width: 100%;
      background: var(--dark);
      border: 1px solid var(--overlay);
      color: var(--text);
      padding: 6px 10px;
      border-radius: 5px;
      font-size: 0.82rem;
      outline: none;
      font-family: inherit;
      margin-bottom: 4px;
    }}
    .cfg-search-box:focus {{ border-color: var(--accent); }}
    .cfg-category-title {{
      font-size: 0.72rem;
      text-transform: uppercase;
      letter-spacing: 0.6px;
      color: var(--subtext);
      font-weight: 700;
      margin: 10px 0 4px 4px;
      display: flex;
      align-items: center;
      justify-content: space-between;
    }}
    .cfg-file-btn {{
      display: flex;
      align-items: center;
      justify-content: space-between;
      width: 100%;
      text-align: left;
      padding: 6px 10px;
      margin-bottom: 3px;
      border-radius: 5px;
      background: transparent;
      color: var(--subtext);
      border: none;
      cursor: pointer;
      font-size: 0.83rem;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      transition: all 0.12s ease;
    }}
    .cfg-file-btn:hover {{ background: var(--surface); color: var(--text); }}
    .cfg-file-btn.active {{ background: var(--overlay); color: var(--accent); font-weight: bold; }}
    .cfg-pill {{
      font-size: 0.68rem;
      padding: 1px 5px;
      border-radius: 3px;
      background: rgba(137, 180, 250, 0.12);
      color: var(--accent);
      border: 1px solid rgba(137, 180, 250, 0.25);
    }}
    #config-editor-container {{ flex: 1; display: flex; position: relative; height: 100%; overflow: hidden; }}
    textarea.code-editor {{
      flex: 1;
      height: 100%;
      background: var(--surface);
      color: var(--text);
      border: none;
      padding: 18px;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 0.88rem;
      resize: none;
      outline: none;
      line-height: 1.5;
    }}
    #config-doc-tracker {{
      width: 380px;
      background: #14141e;
      border-left: 1px solid var(--overlay);
      padding: 18px;
      display: flex;
      flex-direction: column;
      overflow-y: auto;
    }}
    .tracker-title {{
      font-size: 0.95rem;
      font-weight: bold;
      color: var(--accent);
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-top: 0;
    }}
    .tracker-badge {{
      font-size: 0.72rem;
      padding: 2px 6px;
      border-radius: 4px;
      background: var(--overlay);
      color: var(--subtext);
      font-family: ui-monospace, monospace;
    }}
    .tracker-card {{
      background: var(--surface);
      border: 1px solid var(--overlay);
      border-radius: 6px;
      padding: 14px;
      margin-top: 12px;
      font-size: 0.84rem;
      line-height: 1.5;
    }}

    /* Panel 4: Docs */
    #doc-sidebar {{
      width: 280px;
      background: var(--bg);
      border-right: 1px solid var(--overlay);
      padding: 12px;
      overflow-y: auto;
    }}
    .doc-category-header {{
      font-size: 0.72rem;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--subtext);
      font-weight: 700;
      padding: 10px 6px 4px 6px;
      margin-top: 6px;
      display: flex;
      align-items: center;
      gap: 6px;
    }}
    .doc-item-btn {{
      display: flex;
      align-items: center;
      justify-content: space-between;
      width: 100%;
      text-align: left;
      padding: 7px 10px;
      margin-bottom: 3px;
      border-radius: 5px;
      background: transparent;
      color: var(--subtext);
      border: none;
      cursor: pointer;
      font-size: 0.82rem;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    }}
    .doc-item-btn:hover {{ background: var(--surface); color: var(--text); }}
    .doc-item-btn.active {{ background: var(--overlay); color: var(--accent); font-weight: bold; }}
    .doc-pill {{
      font-size: 0.65rem;
      padding: 2px 5px;
      border-radius: 4px;
      background: rgba(255, 255, 255, 0.06);
      color: var(--subtext);
      text-transform: uppercase;
      font-weight: 600;
    }}
    #doc-display {{ flex: 1; padding: 28px 40px; overflow-y: auto; background: var(--surface); }}

    /* Formatted HTML Documentation & Material Mirror Styles (Preserving Editor Dark Theme) */
    .markdown-body {{
      color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      font-size: 0.88rem;
      line-height: 1.65;
      word-wrap: break-word;
    }}
    .markdown-body h1, .markdown-body h2, .markdown-body h3, .markdown-body h4, .markdown-body h5, .markdown-body h6 {{
      margin-top: 24px;
      margin-bottom: 12px;
      font-weight: 600;
      line-height: 1.25;
      color: var(--text);
    }}
    .markdown-body h1 {{
      font-size: 1.6rem;
      padding-bottom: 8px;
      border-bottom: 1px solid var(--overlay);
      color: var(--accent);
    }}
    .markdown-body h2 {{
      font-size: 1.25rem;
      padding-bottom: 6px;
      border-bottom: 1px solid rgba(49, 50, 68, 0.6);
      color: var(--mauve, #cba6f7);
    }}
    .markdown-body h3 {{ font-size: 1.05rem; color: var(--green); }}
    .markdown-body h4 {{ font-size: 0.95rem; color: var(--yellow); }}
    .markdown-body p {{ margin-top: 0; margin-bottom: 12px; }}
    .markdown-body a {{ color: var(--accent); text-decoration: none; }}
    .markdown-body a:hover {{ text-decoration: underline; }}
    .markdown-body strong, .markdown-body b {{ font-weight: 600; color: #fff; }}
    .markdown-body code {{
      padding: 2px 6px;
      margin: 0;
      font-size: 85%;
      background: var(--dark);
      border-radius: 4px;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      color: var(--yellow);
      border: 1px solid rgba(255,255,255,0.06);
    }}
    .markdown-body pre {{
      padding: 14px 16px;
      overflow: auto;
      font-size: 85%;
      line-height: 1.45;
      background: var(--dark);
      border-radius: 6px;
      border: 1px solid var(--overlay);
      margin-bottom: 16px;
    }}
    .markdown-body pre code {{
      background: transparent;
      padding: 0;
      border: none;
      color: var(--text);
      font-size: 0.82rem;
    }}
    .markdown-body blockquote {{
      padding: 0 16px;
      color: var(--subtext);
      border-left: 3px solid var(--accent);
      margin: 12px 0;
      background: rgba(137, 180, 250, 0.05);
      border-radius: 0 4px 4px 0;
    }}
    .markdown-body ul, .markdown-body ol {{
      margin-top: 0;
      margin-bottom: 12px;
      padding-left: 24px;
    }}
    .markdown-body li {{ margin-bottom: 4px; }}
    .markdown-body hr {{
      height: 1px;
      padding: 0;
      margin: 20px 0;
      background-color: var(--overlay);
      border: 0;
    }}
    .markdown-body table {{
      border-spacing: 0;
      border-collapse: collapse;
      margin-top: 0;
      margin-bottom: 16px;
      width: 100%;
      overflow: auto;
      display: block;
      border: 1px solid var(--overlay);
      border-radius: 6px;
    }}
    .markdown-body table th, .markdown-body table td {{
      padding: 8px 12px;
      border: 1px solid var(--overlay);
      font-size: 0.82rem;
    }}
    .markdown-body table th {{
      font-weight: 600;
      background: var(--dark);
      color: var(--accent);
    }}
    .markdown-body table tr:nth-child(2n) {{
      background-color: rgba(255, 255, 255, 0.02);
    }}
    /* Material-mirrored Admonitions without bright site colors */
    .admonition {{
      border-left: 4px solid var(--accent);
      background: rgba(137, 180, 250, 0.06);
      border-radius: 4px;
      box-shadow: 0 2px 4px rgba(0,0,0,0.15);
      margin: 16px 0;
      padding: 10px 14px;
      font-size: 0.85rem;
    }}
    .admonition.warning, .admonition.danger {{
      border-left-color: var(--red);
      background: rgba(243, 139, 168, 0.07);
    }}
    .admonition.tip, .admonition.success {{
      border-left-color: var(--green);
      background: rgba(166, 227, 161, 0.07);
    }}
    .admonition.note, .admonition.info {{
      border-left-color: var(--accent);
      background: rgba(137, 180, 250, 0.07);
    }}
    .admonition-title {{
      font-weight: bold;
      margin-bottom: 6px;
      display: flex;
      align-items: center;
      gap: 6px;
      color: var(--text);
    }}
    .source-badge {{
      font-size: 0.7rem;
      padding: 2px 8px;
      border-radius: 4px;
      font-family: ui-monospace, monospace;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.03em;
    }}
    .source-site {{
      background: rgba(166, 227, 161, 0.15);
      color: var(--green);
      border: 1px solid rgba(166, 227, 161, 0.35);
    }}
    .source-local {{
      background: rgba(137, 180, 250, 0.15);
      color: var(--accent);
      border: 1px solid rgba(137, 180, 250, 0.35);
    }}

    /* Region Editor Contextual Doc Drawer (Below the Bar) */
    #region-doc-drawer {{
      border-top: 1px solid var(--overlay);
      background: #14141e;
      display: flex;
      flex-direction: column;
      max-height: 40%;
      min-height: 38px;
      transition: max-height 0.2s ease-in-out;
      z-index: 10;
    }}
    #region-doc-drawer.collapsed {{
      max-height: 38px;
      overflow: hidden;
    }}
    .region-doc-bar {{
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 7px 14px;
      background: var(--dark);
      cursor: pointer;
      user-select: none;
      border-bottom: 1px solid transparent;
    }}
    #region-doc-drawer:not(.collapsed) .region-doc-bar {{
      border-bottom-color: var(--overlay);
    }}
    .region-doc-content {{
      padding: 16px 22px;
      overflow-y: auto;
      flex: 1;
      background: var(--surface);
    }}

    /* Modal / Toast */
    #modal-overlay {{
      display: none;
      position: fixed;
      top: 0; left: 0; width: 100vw; height: 100vh;
      background: rgba(17,17,27,0.75);
      backdrop-filter: blur(4px);
      z-index: 9999;
      align-items: center;
      justify-content: center;
    }}
    .modal-card {{
      background: var(--surface);
      border: 1px solid var(--overlay);
      border-radius: 10px;
      padding: 24px;
      width: 480px;
      max-width: 90vw;
      box-shadow: 0 10px 30px rgba(0,0,0,0.5);
    }}
  </style>
</head>
<body>
<header id="top-nav">
  <div class="brand">
    <span>⚡ LeafRTP Engine Workspace</span>
    <span class="session-badge" id="conn-badge">● STANDALONE</span>
  </div>
  <nav class="nav-tabs">
    <button class="nav-tab active" onclick="switchPanel('panel-regions', this)">🗺️ Visual Region Editor</button>
    <button class="nav-tab" onclick="switchPanel('panel-telemetry', this)">📊 Diagnostics &amp; Radar</button>
    <button class="nav-tab" onclick="switchPanel('panel-configs', this)">⚙️ Config &amp; Prefabs</button>
    <button class="nav-tab" onclick="switchPanel('panel-docs', this)">📖 Shipped Docs</button>
  </nav>
  <div class="nav-actions">
    <button class="btn" onclick="exportPng()">📷 Snapshot</button>
    <button class="btn" onclick="resetStaging()">↺ Revert</button>
    <button class="btn btn-primary" onclick="commitChanges()">⚡ Commit / Apply</button>
  </div>
</header>
<main id="workspace">
  <!-- Panel 1: Visual Region Editor -->
  <section id="panel-regions" class="panel-view active">
    <div id="region-map-container">
      <canvas id="map-canvas"></canvas>
      <div id="map-hud">
        <span>Region: <b id="hud-region" style="color:var(--accent);">default</b></span> |
        <span id="hud-coords">X: 0  Z: 0</span> |
        <span id="hud-shape" style="color:var(--green);">CIRCLE</span>
      </div>
      <div id="map-layers">
        <label><input type="checkbox" id="chk-grid" checked onchange="drawMap()"> Coordinates</label>
        <label><input type="checkbox" id="chk-bins" checked onchange="drawMap()"> P-Bins (<span id="layer-p-label">P=32</span>)</label>
        <label><input type="checkbox" id="chk-bounds" checked onchange="drawMap()"> Boundaries</label>
        <label><input type="checkbox" id="chk-spiral" checked onchange="drawMap()"> Walk Path (Hilbert / Spiral)</label>
        <label><input type="checkbox" id="chk-hazards" checked onchange="drawMap()"> Bad Spots</label>
        <button class="btn" style="padding:2px 8px; font-size:0.75rem;" onclick="resetCanvasView()">⌖ Reset View</button>
      </div>
      <!-- Contextual Documentation & Region Guide Drawer (Below the Bar) -->
      <div id="region-doc-drawer" class="collapsed">
        <div class="region-doc-bar" onclick="toggleRegionDocDrawer()">
          <div style="display:flex; align-items:center; gap:8px;">
            <span style="font-size:0.85rem; font-weight:600; color:var(--accent);">📖 Contextual Guide:</span>
            <span id="region-doc-header-title" style="font-size:0.8rem; color:var(--text); font-weight:500;">Region Configuration &amp; Math (REGIONS.md)</span>
            <span id="region-doc-source-pill" class="source-badge source-local">PACKED DOCS</span>
          </div>
          <div style="display:flex; align-items:center; gap:10px;" onclick="event.stopPropagation()">
            <button class="btn" id="btn-toggle-region-drawer" onclick="toggleRegionDocDrawer()" style="padding:2px 8px; font-size:0.75rem;">▲ Expand</button>
          </div>
        </div>
        <div id="region-doc-content" class="markdown-body region-doc-content"></div>
      </div>
    </div>
    <aside id="region-sidebar">
      <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;">
        <h3 style="margin:0; color:var(--accent);">📋 Staging Diff Inspector</h3>
        <span class="telemetry-badge badge-ok" id="diff-status-pill">CLEAN</span>
      </div>
      <p style="font-size:0.82rem; color:var(--subtext); margin-top:0;">
        Real-time delta preview of configuration changes before server commit (ADR-104 Phase 3).
      </p>

      <!-- Live YAML Staging Diff Window -->
      <div class="diff-box" id="staging-diff" style="flex:1; min-height:180px; max-height:280px; overflow-y:auto; margin-bottom:14px;">
# No uncommitted geometry modifications
# Adjust parameters or drag polygon vertices to stage deltas
      </div>

      <!-- Geometry Validation Inspector Box -->
      <div style="background:var(--dark); border-radius:6px; padding:12px; border:1px solid var(--overlay); margin-bottom:16px;">
        <h4 style="margin:0 0 8px 0; font-size:0.82rem; color:var(--green); display:flex; align-items:center; gap:6px;">
          <span>✔ Mathematical Invariants</span>
          <span style="font-size:0.7rem; color:var(--subtext); font-weight:normal;">(ADR-034 / ADR-099)</span>
        </h4>
        <div id="validation-status" style="font-family:ui-monospace, monospace; font-size:0.78rem; line-height:1.6;">
          ✅ <span class="diff-add">Non-self-intersecting: VALID</span><br>
          ✅ <span class="diff-add">Collinear vertices: SIMPLIFIED</span><br>
          ✅ <span class="diff-add">Within world border: YES</span>
        </div>
      </div>

      <!-- Region Geometry Parameters -->
      <div class="control-group">
        <label>Selected Region:</label>
        <div style="display:flex; gap:8px; align-items:center;">
          <select id="reg-profile-select" class="control-input" style="flex:1;" onchange="handleRegionSelectChange(this.value)">
            <option value="default">default (Default Region)</option>
            <option value="__add_region__">➕ Add Region...</option>
          </select>
          <button id="btn-remove-region" type="button" class="btn" onclick="removeCurrentRegion()" title="The default region is protected and cannot be removed" disabled style="background:rgba(243,139,168,0.15); border:1px solid var(--red); color:var(--red); padding:6px 12px; font-size:0.8rem; cursor:not-allowed; opacity:0.4; white-space:nowrap;">🗑 Remove</button>
        </div>
      </div>
      <div class="control-group">
        <label>Geometric Shape Algorithm:</label>
        <select id="reg-shape" class="control-input" onchange="updateRegionShape(this.value)">
          <option value="CIRCLE">CIRCLE (Dual-Layer Chained Hilbert)</option>
          <option value="SQUARE">SQUARE (Dual-Layer Chained Hilbert)</option>
          <option value="RECTANGLE">RECTANGLE (Bounded Cartesian)</option>
          <option value="ELLIPSE">ELLIPSE (Concentric Elliptical)</option>
          <option value="CIRCLE_NORMAL">CIRCLE_NORMAL (Normal Gaussian Polar)</option>
          <option value="SQUARE_NORMAL">SQUARE_NORMAL (Normal Gaussian Cartesian)</option>
          <option value="POLYGON">POLYGON (Point-in-Polygon &amp; Vertex Mesh)</option>
          <option value="CIRCLE_DEPRECATED_PURE_SPIRAL">CIRCLE_DEPRECATED_PURE_SPIRAL (Legacy 1D Archimedean)</option>
          <option value="SQUARE_DEPRECATED_PURE_SPIRAL">SQUARE_DEPRECATED_PURE_SPIRAL (Legacy Pure Spiral)</option>
        </select>
      </div>
      <div class="control-group" id="grp-radius">
        <label>Outer Teleport Radius:</label>
        <div style="display:flex; gap:8px;">
          <input type="number" id="reg-radius" class="control-input" value="256" min="1" max="100000" oninput="updateRadius(this.value)">
          <select id="reg-unit" class="control-input" style="width:100px;" onchange="updateUnit(this.value)">
            <option value="c">chunks (c)</option>
            <option value="b">blocks (b)</option>
            <option value="r">regions (r)</option>
            <option value="km">kilometers</option>
          </select>
        </div>
        <span id="hint-radius" style="font-size:0.72rem; color:var(--subtext); margin-top:2px; display:block;">= 4,096 blocks</span>
      </div>
      <div class="control-group" id="grp-center-radius">
        <label>Inner Exclusion Radius (Donut Hole):</label>
        <input type="number" id="reg-center-radius" class="control-input" value="64" min="0" max="100000" oninput="updateCenterRadius(this.value)">
        <span id="hint-center-radius" style="font-size:0.72rem; color:var(--subtext); margin-top:2px; display:block;">= 1,024 blocks (inner deadzone)</span>
      </div>
      <div class="control-group" id="grp-rect-axes" style="display:none;">
        <label>Rectangle / Ellipse Axes (Radius X / Z):</label>
        <div style="display:flex; gap:8px;">
          <input type="number" id="reg-radius-x" class="control-input" placeholder="Radius X" value="256" min="1" oninput="updateAxisX(this.value)">
          <input type="number" id="reg-radius-z" class="control-input" placeholder="Radius Z" value="128" min="1" oninput="updateAxisZ(this.value)">
        </div>
        <span id="hint-axes" style="font-size:0.72rem; color:var(--subtext); margin-top:2px; display:block;">X: 4,096 b | Z: 2,048 b</span>
      </div>
      <div class="control-group" id="grp-polygon-points" style="display:none;">
        <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;">
          <label style="margin:0; font-weight:600;">Polygon Vertices (<span id="poly-points-count">0</span>):</label>
          <button type="button" class="btn" style="padding:2px 8px; font-size:0.75rem; background:var(--accent); color:var(--dark); font-weight:bold; border:none; border-radius:4px; cursor:pointer;" onclick="addPolygonVertex()">+ Add Point</button>
        </div>
        <div id="polygon-vertices-list" style="display:flex; flex-direction:column; gap:6px; max-height:220px; overflow-y:auto; padding-right:4px;">
          <!-- Dynamically populated rows -->
        </div>
        <span style="font-size:0.72rem; color:var(--subtext); margin-top:4px; display:block;">Explicit block coordinates [X, Z]. Drag points on canvas or edit values directly. Min 3 vertices required.</span>
      </div>
    </aside>
  </section>

  <!-- Panel 2: Diagnostics & Telemetry Radar -->
  <section id="panel-telemetry" class="panel-view">
    <div style="flex:1; padding:24px 32px; overflow-y:auto;">
      <div style="display:flex; justify-content:space-between; align-items:center;">
        <div>
          <h2 style="color:var(--accent); margin:0;">📊 Real-Time Engine Diagnostics &amp; Radar</h2>
          <p style="color:var(--subtext); font-size:0.85rem; margin-top:4px;">
            Live telemetry stream mirroring in-game <code>/rtp visualization</code> and queue metrics (ADR-046, ADR-053, ADR-089).
          </p>
        </div>
        <span class="session-badge" id="last-telemetry-update">Live Stream Active</span>
      </div>

      <!-- 1. Engine Health & Host Concurrency -->
      <div class="telemetry-section-title">
        <span>⚡ Server &amp; Host Concurrency Budget</span>
        <span style="font-size:0.75rem; color:var(--subtext); font-weight:normal;">(Heartbeat &amp; Tick Invariants)</span>
      </div>
      <div class="telemetry-grid">
        <div class="telemetry-card">
          <h4>Tick Rates (1m / 5m / 15m)</h4>
          <div class="telemetry-val" style="color:var(--green);" id="metric-tps">20.00 / 20.00 / 20.00</div>
          <p class="telemetry-desc">Rolling host ticks per second; >= 19.5 maintains target budget</p>
        </div>
        <div class="telemetry-card">
          <h4>Tick Time (MSPT Mean / Max)</h4>
          <div class="telemetry-val" style="color:var(--accent);" id="metric-mspt">12.4 ms <span style="font-size:0.9rem; color:var(--subtext);">/ 21.8 ms</span></div>
          <div class="telemetry-bar-bg"><div class="telemetry-bar-fill" id="metric-mspt-bar" style="width:24.8%; background:var(--accent);"></div></div>
          <p class="telemetry-desc">Milliseconds per tick (50ms max budget before TPS drops)</p>
        </div>
        <div class="telemetry-card">
          <h4>Tick Budget Utilisation</h4>
          <div class="telemetry-val" style="color:var(--green);" id="metric-budget">24.8%</div>
          <p class="telemetry-desc">Host thread execution ratio; triggers throttling if &gt; 85%</p>
        </div>
        <div class="telemetry-card">
          <h4>Connected Players / Cap</h4>
          <div class="telemetry-val" style="color:var(--mauve);" id="metric-players">42 <span style="font-size:0.9rem; color:var(--subtext);">/ 150 soft cap</span></div>
          <p class="telemetry-desc">Online players vs configured RTP concurrency soft cap</p>
        </div>
        <div class="telemetry-card">
          <h4>JVM Heap Memory</h4>
          <div class="telemetry-val" style="color:var(--text);" id="metric-heap">1.82 GB <span style="font-size:0.9rem; color:var(--subtext);">/ 4.00 GB (45.5%)</span></div>
          <div class="telemetry-bar-bg"><div class="telemetry-bar-fill" id="metric-heap-bar" style="width:45.5%; background:var(--blue);"></div></div>
          <p class="telemetry-desc">Allocated heap memory used; free available: 2.18 GB</p>
        </div>
        <div class="telemetry-card">
          <h4>Platform Scheduler Target</h4>
          <div class="telemetry-val" style="color:var(--yellow); font-size:1.35rem;" id="metric-platform">Paper / Folia Async</div>
          <p class="telemetry-desc">Thread-safe entity scheduler &amp; non-blocking chunk I/O SPI</p>
        </div>
      </div>

      <!-- 2. Multi-Tier Location Queues & Pools -->
      <div class="telemetry-section-title">
        <span>📦 Multi-Tier Location Reservoirs</span>
        <span style="font-size:0.75rem; color:var(--subtext); font-weight:normal;">(L1 kept chunks, L2 cold verification, L3 MCA backlog, login reserve)</span>
      </div>
      <div class="telemetry-grid">
        <div class="telemetry-card">
          <h4>L1 Hot Queue (Chunks Kept)</h4>
          <div class="telemetry-val" style="color:var(--green);" id="metric-l1">16 / 16</div>
          <div class="telemetry-bar-bg"><div class="telemetry-bar-fill" style="width:100%; background:var(--green);"></div></div>
          <p class="telemetry-desc">Force-loaded chunk tickets held in memory for instantaneous 0ms /rtp execution</p>
        </div>
        <div class="telemetry-card">
          <h4>L2 Cold Queue (Pre-Verified)</h4>
          <div class="telemetry-val" style="color:var(--accent);" id="metric-l2">64 / 64</div>
          <div class="telemetry-bar-bg"><div class="telemetry-bar-fill" style="width:100%; background:var(--accent);"></div></div>
          <p class="telemetry-desc">Candidate locations pre-checked off-tick; chunk tickets released to conserve host RAM</p>
        </div>
        <div class="telemetry-card">
          <h4>L3 Backlog (Binned MCA Cache)</h4>
          <div class="telemetry-val" style="color:var(--yellow);" id="metric-l3">10,000</div>
          <p class="telemetry-desc">Raw 32x32 Anvil/Linear NBT screened candidates waiting for promotion (ADR-028)</p>
        </div>
        <div class="telemetry-card">
          <h4>Login Reserve / Fast Pool</h4>
          <div class="telemetry-val" style="color:var(--mauve);" id="metric-login-reserve">8 / 8</div>
          <p class="telemetry-desc">Dedicated hot reserve for first-join and respawn RTP triggers (ADR-023)</p>
        </div>
        <div class="telemetry-card">
          <h4>Total Queued Waiters</h4>
          <div class="telemetry-val" style="color:var(--green);" id="metric-queue-depth">0</div>
          <p class="telemetry-desc">Players currently waiting in asynchronous command queue for dispatch</p>
        </div>
        <div class="telemetry-card">
          <h4>Active Teleport Pipelines</h4>
          <div class="telemetry-val" style="color:var(--blue);" id="metric-pending-teleports">0</div>
          <p class="telemetry-desc">In-flight candidate search &amp; chunk load tasks actively executing</p>
        </div>
      </div>

      <!-- 3. Teleport Pipeline Latency & Percentiles -->
      <div class="telemetry-section-title">
        <span>⏱️ Teleport Pipeline Latency &amp; Histograms</span>
        <span style="font-size:0.75rem; color:var(--subtext); font-weight:normal;">(ADR-053 high-resolution percentiles and latency distribution)</span>
      </div>
      <div class="telemetry-grid">
        <div class="telemetry-card">
          <h4>Mean Latency</h4>
          <div class="telemetry-val" style="color:var(--green);" id="metric-lat-mean">14.2 ms</div>
          <p class="telemetry-desc">Average elapsed duration across all completed teleport pipeline searches</p>
        </div>
        <div class="telemetry-card">
          <h4>P50 Median Latency</h4>
          <div class="telemetry-val" style="color:var(--green);" id="metric-lat-p50">9.5 ms</div>
          <p class="telemetry-desc">50th percentile of candidate selection duration (typical experience)</p>
        </div>
        <div class="telemetry-card">
          <h4>P75 Latency</h4>
          <div class="telemetry-val" style="color:var(--green);" id="metric-lat-p75">14.0 ms</div>
          <p class="telemetry-desc">75th percentile candidate resolution speed</p>
        </div>
        <div class="telemetry-card">
          <h4>P90 Latency</h4>
          <div class="telemetry-val" style="color:var(--accent);" id="metric-lat-p90">22.5 ms</div>
          <p class="telemetry-desc">90th percentile under concurrent region contention</p>
        </div>
        <div class="telemetry-card">
          <h4>P95 Latency</h4>
          <div class="telemetry-val" style="color:var(--accent);" id="metric-lat-p95">31.0 ms</div>
          <p class="telemetry-desc">95th percentile with off-tick linear chunk lookups</p>
        </div>
        <div class="telemetry-card">
          <h4>P99 Tail Latency</h4>
          <div class="telemetry-val" style="color:var(--yellow);" id="metric-lat-p99">48.2 ms</div>
          <p class="telemetry-desc">99th percentile worst-case cold chunk fetch or biome retry</p>
        </div>
        <div class="telemetry-card">
          <h4>Observed Min / Max</h4>
          <div class="telemetry-val" style="color:var(--text); font-size:1.4rem;" id="metric-lat-minmax">2.1 ms <span style="font-size:0.9rem; color:var(--subtext);">/ 76.4 ms</span></div>
          <p class="telemetry-desc">Boundary samples recorded in the active rolling histogram window</p>
        </div>
        <div class="telemetry-card">
          <h4>Histogram Samples / Runs</h4>
          <div class="telemetry-val" style="color:var(--mauve); font-size:1.4rem;" id="metric-lat-samples">1,024 <span style="font-size:0.9rem; color:var(--subtext);">/ 18,490 total</span></div>
          <p class="telemetry-desc">Window sample count and cumulative lifetime pipeline invocations</p>
        </div>
      </div>

      <!-- 4. Pipeline Stages & Microsecond Breakdown -->
      <div class="telemetry-section-title">
        <span>🔍 Pipeline Stage Duration Breakdown (Microseconds)</span>
        <span style="font-size:0.75rem; color:var(--subtext); font-weight:normal;">(ADR-053 fine-grained stage timers)</span>
      </div>
      <div class="telemetry-grid">
        <div class="telemetry-card">
          <h4>1. Shape Generation</h4>
          <div class="telemetry-val" style="color:var(--green); font-size:1.5rem;" id="metric-stage-shape">18.4 µs</div>
          <p class="telemetry-desc">Dual-layer Chebyshev macro-spiral &amp; chained Hilbert space-filling curves</p>
        </div>
        <div class="telemetry-card">
          <h4>2. Chunk Load / Lookup</h4>
          <div class="telemetry-val" style="color:var(--accent); font-size:1.5rem;" id="metric-stage-chunk">8.6 ms</div>
          <p class="telemetry-desc">Off-tick Anvil/Linear NBT byte pre-filter or async chunk ticket fetch</p>
        </div>
        <div class="telemetry-card">
          <h4>3. Vertical Surface Scan</h4>
          <div class="telemetry-val" style="color:var(--green); font-size:1.5rem;" id="metric-stage-vert">64.2 µs</div>
          <p class="telemetry-desc">Linear middle-out raycast finding solid floor and ceiling air clear</p>
        </div>
        <div class="telemetry-card">
          <h4>4. Biome Eligibility</h4>
          <div class="telemetry-val" style="color:var(--green); font-size:1.5rem;" id="metric-stage-biome">12.1 µs</div>
          <p class="telemetry-desc">Whitelist/blacklist filter checking ocean &amp; cave restrictions</p>
        </div>
        <div class="telemetry-card">
          <h4>5. Safety &amp; Claim Intersects</h4>
          <div class="telemetry-val" style="color:var(--green); font-size:1.5rem;" id="metric-stage-safety">32.8 µs</div>
          <p class="telemetry-desc">Towny, WorldGuard, GriefPrevention &amp; hazard block validation (S-003)</p>
        </div>
        <div class="telemetry-card">
          <h4>Total Pipeline Elapsed</h4>
          <div class="telemetry-val" style="color:var(--green); font-size:1.5rem;" id="metric-stage-total">8.73 ms</div>
          <p class="telemetry-desc">End-to-end execution time from command dispatch to player teleport</p>
        </div>
      </div>

      <!-- 5. Active Pipeline Attempt Counter & Safety Rejections -->
      <div class="telemetry-section-title">
        <span>🛡️ Candidate Screening &amp; Rejection Counters</span>
        <span style="font-size:0.75rem; color:var(--subtext); font-weight:normal;">(Causes for rejected coordinate candidates)</span>
      </div>
      <div class="telemetry-grid">
        <div class="telemetry-card">
          <h4>Candidates Accepted</h4>
          <div class="telemetry-val" style="color:var(--green);" id="metric-rej-accepted">1,024 (92.4%)</div>
          <p class="telemetry-desc">Locations successfully verified safe on first or second attempt</p>
        </div>
        <div class="telemetry-card">
          <h4>Water / Ocean Exclusions</h4>
          <div class="telemetry-val" style="color:var(--accent);" id="metric-rej-water">48</div>
          <p class="telemetry-desc">Rejected deep ocean or water surface candidate locations</p>
        </div>
        <div class="telemetry-card">
          <h4>Lava / Fire Hazards</h4>
          <div class="telemetry-val" style="color:var(--red);" id="metric-rej-lava">12</div>
          <p class="telemetry-desc">Candidate spots rejected due to lava pools or fire proximity</p>
        </div>
        <div class="telemetry-card">
          <h4>Claim Land Intersects</h4>
          <div class="telemetry-val" style="color:var(--yellow);" id="metric-rej-claims">8</div>
          <p class="telemetry-desc">Locations within Towny / WorldGuard / GP claims discarded (S-003)</p>
        </div>
        <div class="telemetry-card">
          <h4>World Border Bounds</h4>
          <div class="telemetry-val" style="color:var(--subtext);" id="metric-rej-border">0</div>
          <p class="telemetry-desc">Candidates rejected outside vanilla /worldborder boundaries</p>
        </div>
        <div class="telemetry-card">
          <h4>Suffocation / Solid Blocks</h4>
          <div class="telemetry-val" style="color:var(--subtext);" id="metric-rej-suffocation">16</div>
          <p class="telemetry-desc">Candidates lacking required 2 blocks of air clearance above ground</p>
        </div>
      </div>

      <!-- 6. Per-Region Cache Distribution Table -->
      <div class="telemetry-section-title">
        <span>🗺️ Per-Region Queue Capacities &amp; In-Flight Status</span>
        <span style="font-size:0.75rem; color:var(--subtext); font-weight:normal;">(Live per-region breakdown)</span>
      </div>
      <table class="telemetry-table">
        <thead>
          <tr>
            <th>Region Name</th>
            <th>World</th>
            <th>Queue Waiters</th>
            <th>L1 Hot Queue</th>
            <th>L2 Cold Queue</th>
            <th>Login Reserve</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody id="region-metrics-tbody">
          <tr>
            <td><b style="color:var(--accent);">default</b></td>
            <td>[0]</td>
            <td>0</td>
            <td><span style="color:var(--green);">16 / 16 (100%)</span></td>
            <td><span style="color:var(--accent);">64 / 64 (100%)</span></td>
            <td><span style="color:var(--mauve);">8 / 8</span></td>
            <td><span class="telemetry-badge badge-ok">OPTIMAL</span></td>
          </tr>
          <tr>
            <td><b style="color:var(--accent);">nether_wastes</b></td>
            <td>world_nether</td>
            <td>0</td>
            <td><span style="color:var(--green);">8 / 8 (100%)</span></td>
            <td><span style="color:var(--accent);">32 / 32 (100%)</span></td>
            <td><span style="color:var(--subtext);">0 / 0</span></td>
            <td><span class="telemetry-badge badge-ok">OPTIMAL</span></td>
          </tr>
          <tr>
            <td><b style="color:var(--accent);">the_end</b></td>
            <td>world_the_end</td>
            <td>0</td>
            <td><span style="color:var(--green);">8 / 8 (100%)</span></td>
            <td><span style="color:var(--accent);">32 / 32 (100%)</span></td>
            <td><span style="color:var(--subtext);">0 / 0</span></td>
            <td><span class="telemetry-badge badge-ok">OPTIMAL</span></td>
          </tr>
        </tbody>
      </table>

      <!-- 7. Runtime Concurrency & Ticket Watchdog Logs -->
      <div class="telemetry-section-title">
        <span>📜 Memory &amp; Scheduler Watchdog Audit Log</span>
      </div>
      <div class="diff-box" style="max-width:100%;" id="telemetry-watchdog-log">
[MemoryTracker Watchdog] 0 active chunk ticket leaks detected across all worlds.
[Scheduler Heartbeat] Folia / Async worker pool operating within nominal bounds (0ms drift).
[Off-Tick I/O Pool] Anvil / Linear region readers active: 2 threads (bounded off-tick).
[RegionQueueManager] L1 Hot Queue pre-warmed and ready for instant dispatch.
[ClaimIntegrations] 0 inline chunk stalls; cache hit ratio: 99.8%.
      </div>
    </div>
  </section>

  <!-- Panel 3: Configs & Prefabs -->
  <section id="panel-configs" class="panel-view">
    <div id="config-sidebar">
      <input type="text" id="cfg-filter-input" class="cfg-search-box" placeholder="🔍 Search configs..." oninput="filterConfigs(this.value)">
      <div id="cfg-file-list"></div>
    </div>
    <div id="config-editor-container">
      <textarea id="cfg-editor" class="code-editor" spellcheck="false"></textarea>
      <aside id="config-doc-tracker" style="overflow-y:auto; max-height:calc(100vh - 120px);">
        <div class="tracker-title">
          <span>📖 Contextual Doc Tracker</span>
          <span class="tracker-badge" id="tracker-key-badge">config.yml</span>
        </div>
        <p style="font-size:0.78rem; color:var(--subtext); margin-top:4px;">
          Dynamic contextual manual sourced from site Markdown documentation (ADR-104).
        </p>
        <div class="tracker-card" id="tracker-body">
          <div style="display:flex; justify-content:space-between; align-items:center;">
            <b style="color:var(--green); font-size:1.05rem;" id="tracker-node">shape</b>
            <span id="tracker-ref-indicator" style="display:none; font-size:0.7rem; padding:2px 6px; border-radius:4px; background:rgba(203, 166, 247, 0.18); color:var(--mauve); border:1px solid rgba(203, 166, 247, 0.4);">🔗 Reference</span>
          </div>

          <!-- Dynamic Reference Materializer Action (Shown when focusing @config / @<file>) -->
          <div id="tracker-materialize-box" style="display:none; margin-top:10px; padding:10px; border-radius:6px; background:rgba(203, 166, 247, 0.1); border:1px solid rgba(203, 166, 247, 0.35);">
            <div style="display:flex; align-items:center; justify-content:space-between; gap:8px;">
              <div>
                <b style="font-size:0.82rem; color:var(--mauve);">Inherited Reference (<span id="tracker-ref-token">@config</span>)</b>
                <p style="font-size:0.75rem; color:var(--subtext); margin:2px 0 0;">This setting inherits global defaults from its parent configuration file.</p>
              </div>
              <button class="btn" id="btn-materialize" onclick="materializeCurrentReference()" style="background:var(--mauve); color:#11111b; font-weight:600; white-space:nowrap; padding:5px 10px; font-size:0.75rem;">⚡ Materialize</button>
            </div>
            <div style="margin-top:6px; font-size:0.72rem; color:var(--subtext);">
              Click <b>Materialize</b> to unpack this reference into a full local editable configuration block right in this file.
            </div>
          </div>

          <div style="margin-top:10px; color:var(--text); line-height:1.45;" id="tracker-desc">Loading documentation from Markdown manuals...</div>

          <!-- Canonical Default Snippet Box & Actions -->
          <div id="tracker-default-box" style="margin-top:12px; padding:8px 10px; border-radius:6px; background:rgba(24, 24, 37, 0.7); border:1px solid var(--overlay);">
            <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:4px;">
              <span style="font-size:0.75rem; color:var(--yellow); font-weight:600;">Default YAML:</span>
              <div style="display:flex; gap:6px;">
                <button class="btn" id="btn-copy-default" onclick="copyDefaultSnippet()" style="padding:2px 8px; font-size:0.72rem;">📋 Copy</button>
                <button class="btn" id="btn-insert-default" onclick="insertDefaultSnippet()" style="padding:2px 8px; font-size:0.72rem;">📥 Insert</button>
              </div>
            </div>
            <pre id="tracker-default-snippet" style="margin:4px 0 0; background:var(--dark); padding:6px 8px; border-radius:4px; font-size:0.75rem; color:var(--teal); font-family:ui-monospace, monospace; overflow-x:auto; max-height:140px; white-space:pre;"></pre>
          </div>

          <div style="margin-top:12px; font-size:0.75rem; color:var(--subtext); font-family:ui-monospace, monospace; border-top:1px solid var(--overlay); padding-top:8px;" id="tracker-meta">Type: Section / Block</div>
          <div style="margin-top:12px; border-top:1px solid var(--overlay); padding-top:8px;">
            <a href="#" onclick="switchPanel('panel-docs', document.querySelectorAll('.nav-tab')[3]); return false;" style="color:var(--accent); text-decoration:none; font-size:0.78rem;">📖 Open Full Documentation &rarr;</a>
          </div>
        </div>
      </aside>
    </div>
  </section>

  <!-- Panel 4: Shipped Documentation -->
  <section id="panel-docs" class="panel-view">
    <aside id="doc-sidebar">
      <div style="padding-bottom:10px; margin-bottom:10px; border-bottom:1px solid var(--overlay);">
        <input type="text" id="doc-filter-input" placeholder="🔍 Filter documentation..." oninput="filterDocs(this.value)" class="control-input" style="width:100%; box-sizing:border-box; font-size:0.8rem; padding:6px 10px; border-radius:5px; border:1px solid var(--overlay); background:var(--dark); color:var(--text);">
      </div>
      <div id="doc-sidebar-list"></div>
    </aside>
    <div id="doc-display">
      <div style="display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid var(--overlay); padding-bottom:12px; margin-bottom:16px;">
        <div>
          <h2 id="doc-current-title" style="margin:0; font-size:1.25rem; color:var(--accent);">📖 Documentation</h2>
          <span id="doc-current-path" style="font-size:0.75rem; color:var(--subtext); font-family:ui-monospace, monospace;">Select a manual from the sidebar</span>
        </div>
        <div style="display:flex; align-items:center; gap:10px;">
          <span id="doc-source-pill" class="source-badge source-local">PACKED DOCS</span>
          <span id="doc-current-badge" class="session-badge" style="display:none;">MANUAL</span>
        </div>
      </div>
      <div id="doc-body" class="markdown-body"></div>
    </div>
  </section>
</main>

<!-- Modal / Toast -->
<div id="modal-overlay" onclick="if(event.target===this) this.style.display='none';">
  <div class="modal-card">
    <div style="display:flex; justify-content:space-between; align-items:center;">
      <h3 style="margin:0; color:var(--accent);" id="modal-title">⚡ Hot-Apply Configuration</h3>
      <span class="session-badge" id="modal-status-badge">READY</span>
    </div>
    <p style="font-size:0.85rem; color:var(--subtext); line-height:1.5; margin-top:10px;" id="modal-desc">
      Changes have been staged and validated. You can commit them instantly over WebSocket or execute the in-game command.
    </p>

    <!-- WebSocket Hot-Apply Direct Action -->
    <div id="ws-apply-section" style="margin:14px 0; padding:12px; border-radius:6px; background:rgba(137, 180, 250, 0.08); border:1px solid rgba(137, 180, 250, 0.25);">
      <div style="display:flex; justify-content:space-between; align-items:center;">
        <div>
          <b style="font-size:0.88rem; color:var(--accent);">Instant Hot-Apply (WebSocket)</b>
          <p style="font-size:0.78rem; color:var(--subtext); margin:3px 0 0 0;">Zero-restart atomic swap into live engine memory.</p>
        </div>
        <button class="btn btn-primary" id="btn-ws-apply" onclick="hotApplyWebSocket()" style="white-space:nowrap;">⚡ Hot-Apply</button>
      </div>
      <div id="ws-apply-status" style="font-size:0.78rem; margin-top:8px; font-family:ui-monospace, monospace; display:none;"></div>
    </div>

    <!-- Fallback In-Game Command Action -->
    <div style="margin:14px 0;">
      <label style="display:block; font-size:0.8rem; color:var(--subtext); margin-bottom:6px;">In-Game / Console Command Fallback (ADR-104 Phase 2):</label>
      <div style="background:var(--dark); padding:10px 14px; border-radius:6px; border:1px solid var(--overlay); font-family:ui-monospace, monospace; font-size:0.9rem; color:var(--green); display:flex; justify-content:space-between; align-items:center;">
        <span id="apply-cmd">/rtp editor apply e4d2a90f</span>
        <button class="btn" id="btn-copy-cmd" onclick="copyApplyCmd()" style="padding:4px 10px; font-size:0.75rem;">Copy</button>
      </div>
    </div>

    <div style="display:flex; justify-content:space-between; align-items:center; margin-top:20px;">
      <span style="font-size:0.75rem; color:var(--subtext);" id="modal-token-info">Session token: <code id="token-label">e4d2a90f</code></span>
      <button class="btn" onclick="document.getElementById('modal-overlay').style.display='none'">Close</button>
    </div>
  </div>
</div>

<script>
// Programmatically injected configuration files from repository baseline
const defaultConfigs = {configs_json};

// Programmatically injected documentation files from repository manuals
const defaultDocs = {docs_json};

// Programmatically extracted documentation and schema annotations
const docTrackerDb = {tracker_json};

let configs = defaultConfigs;
let docs = defaultDocs;
let currentConfigFile = 'config.yml';
let currentDocFile = Object.keys(docs)[0] || '';

let regionProfiles = {{
  default: {{ name: 'default', shape: 'CIRCLE', unit: 'c', radius: 256, centerRadius: 64, radiusX: 256, radiusZ: 128, vertices: [] }}
}};
let regionState = regionProfiles.default;

let canvasZoom = 1.0;
let canvasPanX = 0;
let canvasPanY = 0;
let isPanning = false;
let panStartX = 0;
let panStartY = 0;

function resetCanvasView() {{
  canvasZoom = 1.0;
  canvasPanX = 0;
  canvasPanY = 0;
  drawMap();
}}

function switchPanel(panelId, targetBtn) {{
  document.querySelectorAll('.panel-view').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.nav-tab').forEach(t => t.classList.remove('active'));
  document.getElementById(panelId).classList.add('active');
  if (targetBtn) targetBtn.classList.add('active');
  if (panelId === 'panel-regions') {{
    setTimeout(resizeCanvas, 20);
  }}
}}

const cfgFileList = document.getElementById('cfg-file-list');
const cfgEditor = document.getElementById('cfg-editor');

function categorizeConfig(name) {{
  if (name === 'config.yml' || name === 'safety.yml' || name === 'economy.yml' || name === 'language.yml') {{
    return 'Core Configuration';
  }}
  if (name.startsWith('regions/')) {{
    return 'Region Presets';
  }}
  if (name.startsWith('definitions/')) {{
    return 'Definitions & Routing';
  }}
  if (name.startsWith('advanced/')) {{
    return 'Advanced Tuning';
  }}
  return 'Other Configurations';
}}

function getConfigDisplay(name) {{
  let displayName = name;
  let pill = 'config';

  if (name.startsWith('definitions/')) {{
    displayName = name.substring('definitions/'.length);
    const parts = displayName.split('/');
    pill = parts.length > 1 ? parts[0] : 'definitions';
  }} else if (name.startsWith('advanced/')) {{
    displayName = name.substring('advanced/'.length);
    const parts = displayName.split('/');
    pill = parts.length > 1 ? parts[0] : 'advanced';
  }} else if (name.startsWith('regions/')) {{
    displayName = name.substring('regions/'.length);
    pill = 'regions';
  }} else if (name.includes('/')) {{
    displayName = name.split('/').pop();
    pill = name.split('/')[0];
  }} else {{
    displayName = name;
    pill = name.split('.')[0];
  }}

  return {{ displayName, pill }};
}}

function filterConfigs(query) {{
  populateConfigSidebar(query);
}}

function populateConfigSidebar(filterQuery = '') {{
  if (!cfgFileList) return;
  cfgFileList.innerHTML = '';
  const q = (filterQuery || '').trim().toLowerCase();

  const categories = {{}};
  const fileKeys = Object.keys(configs).sort();

  for (const name of fileKeys) {{
    if (q && !name.toLowerCase().includes(q)) continue;
    const cat = categorizeConfig(name);
    if (!categories[cat]) categories[cat] = [];
    categories[cat].push(name);
  }}

  const categoryOrder = [
    'Core Configuration',
    'Region Presets',
    'Definitions & Routing',
    'Advanced Tuning',
    'Other Configurations'
  ];

  for (const cat of categoryOrder) {{
    const list = categories[cat];
    if (!list || list.length === 0) continue;

    const titleEl = document.createElement('div');
    titleEl.className = 'cfg-category-title';
    titleEl.innerHTML = `<span>${{cat}}</span><span style="opacity:0.6;font-size:0.65rem;">${{list.length}}</span>`;
    cfgFileList.appendChild(titleEl);

    for (const name of list) {{
      const b = document.createElement('button');
      b.className = 'cfg-file-btn' + (name === currentConfigFile ? ' active' : '');
      b.setAttribute('data-filename', name);
      const {{ displayName, pill }} = getConfigDisplay(name);
      b.innerHTML = `<span>📄 ${{escapeHtml(displayName)}}</span><span class="cfg-pill">${{escapeHtml(pill)}}</span>`;
      b.onclick = () => selectConfig(name);
      cfgFileList.appendChild(b);
    }}
  }}

  if (Object.keys(categories).length === 0) {{
    const emptyEl = document.createElement('div');
    emptyEl.style.fontSize = '0.78rem';
    emptyEl.style.color = 'var(--subtext)';
    emptyEl.style.padding = '12px 6px';
    emptyEl.textContent = 'No matching configuration files.';
    cfgFileList.appendChild(emptyEl);
  }}
}}

function selectConfig(name) {{
  // Only persist editor text if switching away from an already loaded file
  if (currentConfigFile && currentConfigFile !== name && cfgEditor && cfgEditor.value) {{
    configs[currentConfigFile] = cfgEditor.value;
  }}
  currentConfigFile = name;
  document.querySelectorAll('.cfg-file-btn').forEach(b => {{
    b.classList.toggle('active', b.getAttribute('data-filename') === name);
  }});
  cfgEditor.value = configs[name] || '';
  const badge = document.getElementById('tracker-key-badge');
  if (badge) badge.textContent = name;
  trackCurrentConfigLine();
  updateStagingDiff();
}}

let currentActiveKey = 'shape';
let currentActiveLine = '';
let currentRefToken = null;
let currentDefaultSnippet = '';

const GLOBAL_DEFAULT_SECTIONS = {{
  '@config': {{
    shape: `shape:
  name: "CIRCLE"
  mode: "ACCUMULATE"
  radius: 4096b
  centerRadius: 64
  centerX: 0
  centerZ: 0
  weight: 1.0
  uniquePlacements: "auto"
  expand: false`,
    vert: `vert:
  name: "LINEAR"
  minY: 32
  maxY: 255
  direction: 2
  requireSkyLight: true`,
    cacheCap: 'cacheCap: 50',
    backlogCacheCap: 'backlogCacheCap: 1000',
    activeChunkCap: 'activeChunkCap: 10',
    spatialResolution: 'spatialResolution: "auto"',
    requirePermission: 'requirePermission: false'
  }},
  '@economy': {{
    price: 'price: 0.0',
    defaultPrice: 'defaultPrice: 0.0'
  }}
}};

const SCHEMA_DOC_TAGS = new Set([
  'type', 'range', 'unit', 'default', 'options', 'source',
  'see', 'param', 'author', 'version', 'since', 'deprecated',
  'return', 'returns', 'link', 'throws', 'exception'
]);

function isInheritedReferenceToken(token) {{
  if (!token || typeof token !== 'string') return false;
  const trimmed = token.trim().replace(/^["']|["']$/g, '');
  if (!trimmed.startsWith('@') || trimmed.length <= 1) return false;
  const name = trimmed.substring(1).toLowerCase();
  if (SCHEMA_DOC_TAGS.has(name)) return false;
  return /^[a-zA-Z0-9_.-]+$/.test(name);
}}

function trackCurrentConfigLine() {{
  const text = cfgEditor.value || '';
  const selStart = cfgEditor.selectionStart || 0;
  const lineStart = text.lastIndexOf('\\n', selStart - 1) + 1;
  let lineEnd = text.indexOf('\\n', selStart);
  if (lineEnd === -1) lineEnd = text.length;
  const line = text.substring(lineStart, lineEnd).trim();
  currentActiveLine = line;

  let key = null;
  let candRefToken = null;

  // Pure comment line check (# ...)
  if (line.startsWith('#')) {{
    // Do not treat comment lines as active key-value or reference definitions.
    // Look ahead for the documented key so the doc tracker stays informative:
    const remaining = text.substring(lineEnd);
    const lookahead = remaining.match(/(?:\\r?\\n|^)\\s*([a-zA-Z0-9_.-]+)\\s*:/);
    if (lookahead) {{
      key = lookahead[1];
    }}
  }} else {{
    // Non-comment or mixed line: look for key: value
    const kvMatch = line.match(/^([a-zA-Z0-9_.-]+)\\s*:\\s*(.*)$/);
    if (kvMatch) {{
      key = kvMatch[1];
      const valPart = kvMatch[2].trim();
      // An inherited reference value in YAML is @<file>, optionally quoted, optionally followed by a comment
      const refValMatch = valPart.match(/^(["']?)(@[a-zA-Z0-9_.-]+)\\1(?:\\s*#.*)?$/);
      if (refValMatch) {{
        const candidate = refValMatch[2];
        if (isInheritedReferenceToken(candidate)) {{
          candRefToken = candidate;
        }}
      }}
    }}
  }}

  if (!key) {{
    key = currentConfigFile.includes('region') ? 'shape' : 'teleportDelay';
  }}
  currentActiveKey = key;
  currentRefToken = candRefToken;

  updateDocTracker(key, currentConfigFile, line, currentRefToken);
}}

cfgEditor.addEventListener('keyup', () => {{
  trackCurrentConfigLine();
  if (currentConfigFile) {{
    configs[currentConfigFile] = cfgEditor.value;
    updateStagingDiff();
  }}
}});
cfgEditor.addEventListener('input', () => {{
  if (currentConfigFile) {{
    configs[currentConfigFile] = cfgEditor.value;
    updateStagingDiff();
  }}
}});
cfgEditor.addEventListener('click', trackCurrentConfigLine);

function updateDocTracker(key, file, lineText = '', refToken = null) {{
  const nodeEl = document.getElementById('tracker-node');
  const descEl = document.getElementById('tracker-desc');
  const metaEl = document.getElementById('tracker-meta');
  const refIndicator = document.getElementById('tracker-ref-indicator');
  const matBox = document.getElementById('tracker-materialize-box');
  const refTokenEl = document.getElementById('tracker-ref-token');
  const defaultBox = document.getElementById('tracker-default-box');
  const defaultPre = document.getElementById('tracker-default-snippet');
  if (!nodeEl || !descEl || !metaEl) return;

  const lk = (key || '').toLowerCase().replace(/[^a-z0-9]/g, '');
  const entry = docTrackerDb[lk];

  // Configure reference materialization prompt
  if (isInheritedReferenceToken(refToken)) {{
    if (refIndicator) refIndicator.style.display = 'inline-block';
    if (matBox) matBox.style.display = 'block';
    if (refTokenEl) refTokenEl.textContent = refToken;
  }} else {{
    if (refIndicator) refIndicator.style.display = 'none';
    if (matBox) matBox.style.display = 'none';
  }}

  let snippet = '';
  if (entry) {{
    nodeEl.textContent = entry.title;
    if (entry.html) {{
      descEl.innerHTML = entry.html;
    }} else {{
      descEl.textContent = entry.desc;
    }}
    metaEl.textContent = entry.meta;
    snippet = entry.defaultSnippet || (entry.defaultVal ? `${{entry.title}}: ${{entry.defaultVal}}` : '');
  }} else if (lk.includes('radius')) {{
    nodeEl.textContent = key;
    descEl.innerHTML = '<p style="margin:4px 0;"><b>Outer coordinate offset</b> in blocks or chunks evaluated around center anchor. Defines outer teleport boundary.</p>';
    metaEl.textContent = 'Type: Spatial Unit >= 0 | Docs: REGIONS.md';
    snippet = `${{key}}: 4096b`;
  }} else if (lk.includes('price') || lk.includes('cost')) {{
    nodeEl.textContent = key;
    descEl.innerHTML = '<p style="margin:4px 0;"><b>Vault economy teleport fee</b> charged to player upon successful execution. Zero means free.</p>';
    metaEl.textContent = 'Type: Double >= 0.0 | Unit: Currency | Docs: ECONOMY.md';
    snippet = `${{key}}: 0.0`;
  }} else if (lk.includes('biome')) {{
    nodeEl.textContent = key;
    descEl.innerHTML = '<p style="margin:4px 0;"><b>Biome filter</b> controlling ecosystem eligibility for teleport landings.</p>';
    metaEl.textContent = 'Context: ' + file + ' | Focused Key: ' + key + ' | Docs: SAFETY.md';
    snippet = `${{key}}: []`;
  }} else {{
    nodeEl.textContent = key;
    descEl.innerHTML = '<p style="margin:4px 0; color:var(--subtext);">Engine configuration parameter. Refer to packed schema guides and ADR documentation for parameter limits and pipeline impact.</p>';
    metaEl.textContent = 'Context: ' + file + ' | Focused Key: ' + key;
    snippet = `${{key}}: ""`;
  }}

  currentDefaultSnippet = snippet;
  if (defaultBox && defaultPre) {{
    if (snippet) {{
      defaultBox.style.display = 'block';
      defaultPre.textContent = snippet;
    }} else {{
      defaultBox.style.display = 'none';
    }}
  }}
}}

function copyDefaultSnippet() {{
  if (!currentDefaultSnippet) return;
  navigator.clipboard.writeText(currentDefaultSnippet).then(() => {{
    const btn = document.getElementById('btn-copy-default');
    if (btn) {{
      const prev = btn.textContent;
      btn.textContent = '✓ Copied!';
      btn.style.color = 'var(--green)';
      setTimeout(() => {{
        btn.textContent = prev;
        btn.style.color = '';
      }}, 1500);
    }}
  }});
}}

function insertDefaultSnippet() {{
  if (!currentDefaultSnippet || !cfgEditor) return;
  const selStart = cfgEditor.selectionStart || 0;
  const selEnd = cfgEditor.selectionEnd || selStart;
  const val = cfgEditor.value;
  cfgEditor.value = val.substring(0, selStart) + currentDefaultSnippet + val.substring(selEnd);
  cfgEditor.selectionStart = cfgEditor.selectionEnd = selStart + currentDefaultSnippet.length;
  cfgEditor.focus();
  if (currentConfigFile) {{
    configs[currentConfigFile] = cfgEditor.value;
    updateStagingDiff();
  }}
  trackCurrentConfigLine();
}}

function materializeCurrentReference() {{
  if (!cfgEditor || !currentConfigFile) return;
  const val = cfgEditor.value;
  const selStart = cfgEditor.selectionStart || 0;
  const lineStart = val.lastIndexOf('\\n', selStart - 1) + 1;
  let lineEnd = val.indexOf('\\n', selStart);
  if (lineEnd === -1) lineEnd = val.length;
  const line = val.substring(lineStart, lineEnd);

  // Find key and ref token on line
  const match = line.match(/^([a-zA-Z0-9_.-]+)\\s*:\\s*(["']?)(@[a-zA-Z0-9_.-]+)\\2/);
  const key = match ? match[1] : currentActiveKey;
  const token = match ? match[3] : currentRefToken;
  if (!isInheritedReferenceToken(token)) return;

  // Look up default block in GLOBAL_DEFAULT_SECTIONS
  let replacement = '';
  const group = GLOBAL_DEFAULT_SECTIONS[token] || GLOBAL_DEFAULT_SECTIONS['@config'];
  if (group && group[key]) {{
    replacement = group[key];
  }} else {{
    const lk = key.toLowerCase().replace(/[^a-z0-9]/g, '');
    const entry = docTrackerDb[lk];
    if (entry && entry.defaultSnippet) {{
      replacement = entry.defaultSnippet;
    }} else {{
      replacement = `${{key}}: 0`;
    }}
  }}

  // Perform replacement of the reference line
  const before = val.substring(0, lineStart);
  const after = val.substring(lineEnd);
  cfgEditor.value = before + replacement + after;
  cfgEditor.selectionStart = cfgEditor.selectionEnd = lineStart + replacement.length;
  cfgEditor.focus();

  configs[currentConfigFile] = cfgEditor.value;
  updateStagingDiff();
  trackCurrentConfigLine();

  const matBtn = document.getElementById('btn-materialize');
  if (matBtn) {{
    const orig = matBtn.textContent;
    matBtn.textContent = '✓ Materialized!';
    matBtn.style.background = 'var(--green)';
    setTimeout(() => {{
      matBtn.textContent = orig;
      matBtn.style.background = '';
    }}, 1500);
  }}
}}

const docSidebar = document.getElementById('doc-sidebar');
const docSidebarList = document.getElementById('doc-sidebar-list');
const docBody = document.getElementById('doc-body');
const docCurrentTitle = document.getElementById('doc-current-title');
const docCurrentPath = document.getElementById('doc-current-path');
const docCurrentBadge = document.getElementById('doc-current-badge');

function categorizeDoc(relpath) {{
  if (relpath.startsWith('admin/configuration/')) return 'Configuration Reference';
  if (relpath.startsWith('admin/proxies/')) return 'Proxy & Multi-Server';
  if (relpath.startsWith('admin/')) return 'Operations & Runbooks';
  if (relpath.startsWith('FOR_ADDON') || relpath.startsWith('ADDON') || relpath.startsWith('FOR_CONTRIBUTORS')) return 'Developer & Addon Guides';
  return 'General & Navigation';
}}

function getDocDisplay(relpath) {{
  const parts = relpath.split('/');
  const filename = parts[parts.length - 1];
  let pill = 'doc';
  if (relpath.startsWith('admin/configuration/')) pill = 'config';
  else if (relpath.startsWith('admin/proxies/')) pill = 'proxy';
  else if (relpath.startsWith('admin/')) pill = 'admin';
  else if (relpath.startsWith('FOR_ADDON') || relpath.startsWith('ADDON')) pill = 'addon';
  else if (relpath === 'MAP.md' || relpath === 'index.md' || relpath === 'FOR_SERVER_ADMINS.md') pill = 'guide';
  return {{ displayName: filename, pill: pill }};
}}

function resolveDocKey(key) {{
  if (!key) return Object.keys(docs)[0] || '';
  if (docs[key] !== undefined) return key;
  for (const k of Object.keys(docs)) {{
    if (k === key || k.endsWith('/' + key)) return k;
  }}
  return key;
}}

function filterDocs(query) {{
  populateDocSidebar(query);
}}

function populateDocSidebar(filterQuery = '') {{
  const container = docSidebarList || docSidebar;
  if (!container) return;
  container.innerHTML = '';

  const q = (filterQuery || '').trim().toLowerCase();
  const allKeys = Object.keys(docs).sort();

  const categories = {{
    'Operations & Runbooks': [],
    'Configuration Reference': [],
    'Proxy & Multi-Server': [],
    'General & Navigation': [],
    'Developer & Addon Guides': []
  }};

  for (const name of allKeys) {{
    if (q) {{
      const matchName = name.toLowerCase().includes(q);
      const matchContent = (docs[name] || '').toLowerCase().includes(q);
      if (!matchName && !matchContent) continue;
    }}
    const cat = categorizeDoc(name);
    if (!categories[cat]) categories[cat] = [];
    categories[cat].push(name);
  }}

  let totalShown = 0;
  for (const [catTitle, items] of Object.entries(categories)) {{
    if (items.length === 0) continue;
    totalShown += items.length;

    const hdr = document.createElement('div');
    hdr.className = 'doc-category-header';
    hdr.textContent = catTitle;
    container.appendChild(hdr);

    for (const name of items) {{
      const b = document.createElement('button');
      const isAct = (resolveDocKey(name) === resolveDocKey(currentDocFile));
      b.className = 'doc-item-btn' + (isAct ? ' active' : '');
      b.setAttribute('data-docname', name);

      const disp = getDocDisplay(name);
      const labelSpan = document.createElement('span');
      labelSpan.textContent = disp.displayName;
      b.appendChild(labelSpan);

      const pillSpan = document.createElement('span');
      pillSpan.className = 'doc-pill';
      pillSpan.textContent = disp.pill;
      b.appendChild(pillSpan);

      b.onclick = () => selectDoc(name);
      container.appendChild(b);
    }}
  }}

  if (totalShown === 0) {{
    const empty = document.createElement('div');
    empty.style.padding = '14px 8px';
    empty.style.color = 'var(--subtext)';
    empty.style.fontSize = '0.78rem';
    empty.textContent = 'No documentation matches query.';
    container.appendChild(empty);
  }}
}}

function escapeHtml(str) {{
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}}

/* ADR-104 Client-Side Markdown to HTML Formatter */
function renderMarkdownToHtml(markdown) {{
  if (!markdown) return '<p style="color:var(--subtext); font-style:italic;">No content available.</p>';

  // Strip YAML frontmatter if present
  let cleanMd = markdown;
  if (cleanMd.startsWith('---')) {{
    const endFm = cleanMd.indexOf('\n---', 3);
    if (endFm !== -1) {{
      cleanMd = cleanMd.substring(endFm + 4).trimStart();
    }}
  }}

  const lines = cleanMd.split('\n');
  const out = [];
  let inCode = false;
  let codeLang = '';
  let codeBuf = [];
  let inList = false;
  let listType = 'ul';
  let inTable = false;
  let tableHeaders = [];
  let inBlockquote = false;
  let blockquoteBuf = [];
  let inAdmonition = false;
  let admonitionType = 'note';
  let admonitionTitle = '';
  let admonitionBuf = [];

  function flushList() {{
    if (inList) {{
      out.push(`</${{listType}}>`);
      inList = false;
    }}
  }}

  function flushTable() {{
    if (inTable) {{
      out.push('</tbody></table>');
      inTable = false;
      tableHeaders = [];
    }}
  }}

  function flushBlockquote() {{
    if (inBlockquote) {{
      const bqHtml = renderMarkdownToHtml(blockquoteBuf.join('\n'));
      out.push(`<blockquote>${{bqHtml}}</blockquote>`);
      inBlockquote = false;
      blockquoteBuf = [];
    }}
  }}

  function flushAdmonition() {{
    if (inAdmonition) {{
      const admHtml = renderMarkdownToHtml(admonitionBuf.join('\n'));
      const displayTitle = admonitionTitle || (admonitionType.charAt(0).toUpperCase() + admonitionType.slice(1));
      let icon = 'ℹ️';
      if (admonitionType === 'warning' || admonitionType === 'danger') icon = '⚠️';
      else if (admonitionType === 'tip' || admonitionType === 'success') icon = '💡';
      out.push(`<div class="admonition ${{admonitionType}}"><div class="admonition-title"><span>${{icon}}</span> <span>${{escapeHtml(displayTitle)}}</span></div><div>${{admHtml}}</div></div>`);
      inAdmonition = false;
      admonitionBuf = [];
    }}
  }}

  function renderInline(text) {{
    if (!text) return '';
    let res = escapeHtml(text);
    // Inline code
    res = res.replace(/`([^`]+)`/g, '<code>$1</code>');
    // Bold / italic
    res = res.replace(/\*\*\*([^*]+)\*\*\*/g, '<b><i>$1</i></b>');
    res = res.replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>');
    res = res.replace(/__([^_]+)__/g, '<b>$1</b>');
    res = res.replace(/\*([^*]+)\*/g, '<i>$1</i>');
    res = res.replace(/_([^_]+)_/g, '<i>$1</i>');
    res = res.replace(/~~([^~]+)~~/g, '<del>$1</del>');
    // Links [text](url)
    res = res.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
    // Checkboxes [ ] and [x]
    res = res.replace(/\[ \]/g, '<input type="checkbox" disabled style="margin-right:4px;">');
    res = res.replace(/\[[xX]\]/g, '<input type="checkbox" checked disabled style="margin-right:4px;">');
    return res;
  }}

  for (let i = 0; i < lines.length; i++) {{
    const line = lines[i];
    const trimmed = line.trim();

    // Fenced code blocks
    if (trimmed.startsWith('```')) {{
      if (inCode) {{
        const codeContent = escapeHtml(codeBuf.join('\n'));
        const copyBtn = `<button class="btn" onclick="navigator.clipboard.writeText(this.parentElement.nextElementSibling.textContent); this.textContent='Copied!'; setTimeout(()=>this.textContent='Copy', 1500);" style="padding:2px 8px; font-size:0.7rem;">Copy</button>`;
        const langBadge = codeLang ? `<span style="font-family:ui-monospace, monospace; font-size:0.72rem; color:var(--subtext);">${{escapeHtml(codeLang)}}</span>` : '<span></span>';
        out.push(`<div style="position:relative; margin-bottom:16px; border:1px solid var(--overlay); border-radius:6px; overflow:hidden; background:var(--dark);"><div style="display:flex; justify-content:space-between; align-items:center; padding:6px 12px; background:rgba(255,255,255,0.03); border-bottom:1px solid rgba(255,255,255,0.05);">${{langBadge}}${{copyBtn}}</div><pre style="margin:0; padding:12px 14px; border:none; border-radius:0;"><code>${{codeContent}}</code></pre></div>`);
        inCode = false;
        codeBuf = [];
        codeLang = '';
      }} else {{
        flushList();
        flushTable();
        flushBlockquote();
        flushAdmonition();
        inCode = true;
        codeLang = trimmed.substring(3).trim();
      }}
      continue;
    }}

    if (inCode) {{
      codeBuf.push(line);
      continue;
    }}

    // Admonitions (!!! note "Title" or !!! warning)
    const admMatch = trimmed.match(/^!!!\s+([a-zA-Z0-9_-]+)(?:\s+"([^"]+)")?/);
    if (admMatch) {{
      flushList();
      flushTable();
      flushBlockquote();
      flushAdmonition();
      inAdmonition = true;
      admonitionType = admMatch[1].toLowerCase();
      admonitionTitle = admMatch[2] || '';
      continue;
    }}

    if (inAdmonition) {{
      if (line.startsWith('    ') || line.startsWith('\t')) {{
        admonitionBuf.push(line.replace(/^(?:    |\t)/, ''));
        continue;
      }} else if (!trimmed) {{
        admonitionBuf.push('');
        continue;
      }} else {{
        flushAdmonition();
      }}
    }}

    // Blockquotes
    if (trimmed.startsWith('>')) {{
      flushList();
      flushTable();
      inBlockquote = true;
      blockquoteBuf.push(trimmed.replace(/^>\s?/, ''));
      continue;
    }} else if (inBlockquote) {{
      flushBlockquote();
    }}

    // Tables
    if (trimmed.startsWith('|') && trimmed.endsWith('|')) {{
      flushList();
      const cells = trimmed.slice(1, -1).split('|').map(c => c.trim());
      if (!inTable) {{
        inTable = true;
        tableHeaders = cells;
        out.push('<table><thead><tr>');
        for (const th of cells) {{
          out.push(`<th>${{renderInline(th)}}</th>`);
        }}
        out.push('</tr></thead><tbody>');
      }} else {{
        // Separator row?
        if (cells.every(c => /^:?-+:?$/.test(c))) {{
          continue;
        }}
        out.push('<tr>');
        for (const td of cells) {{
          out.push(`<td>${{renderInline(td)}}</td>`);
        }}
        out.push('</tr>');
      }}
      continue;
    }} else if (inTable) {{
      flushTable();
    }}

    // Blank lines
    if (!trimmed) {{
      flushList();
      continue;
    }}

    // Horizontal Rule
    if (/^(?:---|\*\*\*|___)$/.test(trimmed)) {{
      flushList();
      out.push('<hr>');
      continue;
    }}

    // Headings
    const hMatch = trimmed.match(/^(#{1,6})\s+(.*)$/);
    if (hMatch) {{
      flushList();
      const level = hMatch[1].length;
      const title = hMatch[2];
      const anchor = title.toLowerCase().replace(/[^a-z0-9_-]+/g, '-').replace(/^-|-$/g, '');
      const permalink = `<a href="#${{anchor}}" style="color:var(--overlay); text-decoration:none; margin-left:6px; font-size:0.8em;" title="Permanent link">¶</a>`;
      out.push(`<h${{level}} id="${{anchor}}">${{renderInline(title)}}${{permalink}}</h${{level}}>`);
      continue;
    }}

    // Lists
    const ulMatch = trimmed.match(/^[-*+]\s+(.*)$/);
    const olMatch = trimmed.match(/^(\d+)\.\s+(.*)$/);
    if (ulMatch || olMatch) {{
      const isUl = !!ulMatch;
      const itemContent = isUl ? ulMatch[1] : olMatch[2];
      const targetType = isUl ? 'ul' : 'ol';
      if (!inList || listType !== targetType) {{
        flushList();
        inList = true;
        listType = targetType;
        out.push(`<${{listType}}>`);
      }}
      out.push(`<li>${{renderInline(itemContent)}}</li>`);
      continue;
    }} else {{
      flushList();
    }}

    // Paragraph
    out.push(`<p>${{renderInline(trimmed)}}</p>`);
  }}

  if (inCode) {{
    out.push(`<pre><code>${{escapeHtml(codeBuf.join('\n'))}}</code></pre>`);
  }}
  flushList();
  flushTable();
  flushBlockquote();
  flushAdmonition();

  return out.join('\n');
}}

/* ADR-104 Section 4.6: Official Site Content Pulling & Mirroring */
const OFFICIAL_SITE_BASE = 'https://dailystruggle.github.io/RTP/';

function resolveSiteUrlForDoc(docKey) {{
  if (!docKey) return null;
  let path = docKey.replace(/\\/g, '/');
  // Determine site root from window location if available
  let base = OFFICIAL_SITE_BASE;
  try {{
    if (window.location && window.location.origin) {{
      const loc = window.location.href;
      if (loc.includes('/RTP/editor/')) {{
        base = loc.substring(0, loc.indexOf('/editor/')) + '/';
      }} else if (loc.includes('/editor/')) {{
        base = loc.substring(0, loc.indexOf('/editor/')) + '/';
      }} else if (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') {{
        base = OFFICIAL_SITE_BASE;
      }}
    }}
  }} catch (e) {{
    base = OFFICIAL_SITE_BASE;
  }}

  if (path === 'index.md') return base;
  if (path.endsWith('.md')) {{
    const noExt = path.substring(0, path.length - 3);
    return base + noExt + '/';
  }}
  return base + path;
}}

async function fetchOfficialSitePage(siteUrl) {{
  const res = await fetch(siteUrl, {{ method: 'GET', headers: {{ 'Accept': 'text/html' }} }});
  if (!res.ok) throw new Error(`HTTP ${{res.status}}`);
  const htmlText = await res.text();
  const parser = new DOMParser();
  const doc = parser.parseFromString(htmlText, 'text/html');

  // Extract core article element mirroring MkDocs Material layout
  const article = doc.querySelector('article.md-content__inner') ||
                  doc.querySelector('.md-content') ||
                  doc.querySelector('article') ||
                  doc.querySelector('main');
  if (!article) throw new Error('No article content found');

  // Remove action links, edit buttons, or duplicate titles if present
  article.querySelectorAll('.md-content__button, nav.md-nav, .md-footer, script, style').forEach(el => el.remove());

  // Rewrite relative links to absolute or hash
  article.querySelectorAll('a[href]').forEach(a => {{
    const href = a.getAttribute('href');
    if (href && !href.startsWith('http') && !href.startsWith('#') && !href.startsWith('mailto:')) {{
      try {{
        a.href = new URL(href, siteUrl).href;
        a.target = '_blank';
        a.rel = 'noopener';
      }} catch (e) {{}}
    }}
  }});

  return article.innerHTML;
}}

async function updateDocDisplayContent(docKey, targetElement, sourcePillElement) {{
  const resolved = resolveDocKey(docKey);
  const localMarkdown = docs[resolved] || docs[docKey] || '';

  // Always attempt to pull from official site automatically if URL is resolvable
  const siteUrl = resolveSiteUrlForDoc(resolved);
  if (siteUrl) {{
    if (sourcePillElement) {{
      sourcePillElement.textContent = 'FETCHING SITE...';
      sourcePillElement.className = 'source-badge';
    }}

    try {{
      const siteHtml = await fetchOfficialSitePage(siteUrl);
      if (targetElement) targetElement.innerHTML = siteHtml;
      if (sourcePillElement) {{
        sourcePillElement.textContent = 'OFFICIAL SITE';
        sourcePillElement.className = 'source-badge source-site';
        sourcePillElement.title = `Automatically pulled from ${{siteUrl}} (mirrored format, editor dark colors)`;
      }}
      return;
    }} catch (err) {{
      console.warn(`[LeafRTP] Failed to pull page contents from official site for ${{docKey}} (${{siteUrl}}), falling back to packed docs:`, err);
    }}
  }}

  // Fallback to local packed markdown reformatted for HTML
  const formattedHtml = renderMarkdownToHtml(localMarkdown);
  if (targetElement) targetElement.innerHTML = formattedHtml;
  if (sourcePillElement) {{
    sourcePillElement.textContent = 'PACKED DOCS';
    sourcePillElement.className = 'source-badge source-local';
    sourcePillElement.title = 'Rendered from bundled offline markdown source';
  }}
}}

function toggleRegionDocDrawer() {{
  const drawer = document.getElementById('region-doc-drawer');
  const btn = document.getElementById('btn-toggle-region-drawer');
  if (!drawer) return;
  const isCollapsed = drawer.classList.toggle('collapsed');
  if (btn) {{
    btn.textContent = isCollapsed ? '▲ Expand' : '▼ Collapse';
  }}
  if (!isCollapsed) {{
    renderRegionDocContent();
  }}
}}

function renderRegionDocContent() {{
  const contentEl = document.getElementById('region-doc-content');
  const pillEl = document.getElementById('region-doc-source-pill');
  if (!contentEl) return;
  // Region documentation key
  const regDocKey = 'admin/configuration/REGIONS.md';
  updateDocDisplayContent(regDocKey, contentEl, pillEl);
}}

function selectDoc(name) {{
  const resolved = resolveDocKey(name);
  currentDocFile = resolved;

  document.querySelectorAll('.doc-item-btn').forEach(b => {{
    const btnDoc = b.getAttribute('data-docname');
    b.classList.toggle('active', resolveDocKey(btnDoc) === resolved);
  }});

  const disp = getDocDisplay(resolved);
  if (docCurrentTitle) docCurrentTitle.textContent = '📖 ' + disp.displayName;
  if (docCurrentPath) docCurrentPath.textContent = resolved;
  if (docCurrentBadge) {{
    docCurrentBadge.style.display = 'inline-block';
    docCurrentBadge.textContent = disp.pill.toUpperCase();
  }}

  updateDocDisplayContent(resolved, docBody, document.getElementById('doc-source-pill'));
}}

/* 2D Canvas Renderer for Region Editing */
const canvas = document.getElementById('map-canvas');
const ctx = canvas.getContext('2d');
let draggingVertex = -1;

function resizeCanvas() {{
  if (!canvas) return;
  canvas.width = canvas.clientWidth;
  canvas.height = canvas.clientHeight;
  drawMap();
}}
window.addEventListener('resize', resizeCanvas);

function drawMap() {{
  if (!canvas.width || !canvas.height) return;
  ctx.fillStyle = '#11111b';
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  const cx = canvas.width / 2 + canvasPanX;
  const cy = canvas.height / 2 + canvasPanY;

  // Calculate effective bounding dimensions in blocks
  const unit = regionState.unit || 'c';
  const unitMult = (unit === 'c' ? 16 : (unit === 'r' ? 512 : (unit === 'km' ? 1000 : 1)));
  const rBlocks = (regionState.radius || 256) * unitMult;
  const crBlocks = (regionState.centerRadius || 0) * unitMult;
  let rxBlocks = (regionState.radiusX || regionState.radius || 256) * unitMult;
  let rzBlocks = (regionState.radiusZ || Math.round((regionState.radius || 256) / 2)) * unitMult;

  // Determine span for auto-scale
  let maxSpan = Math.max(rBlocks, 4000);
  if (regionState.shape === 'RECTANGLE' || regionState.shape === 'ELLIPSE') {{
    maxSpan = Math.max(rxBlocks, rzBlocks, 4000);
  }} else if (regionState.shape === 'POLYGON' && regionState.vertices && regionState.vertices.length > 0) {{
    let maxV = 1000;
    for (const v of regionState.vertices) {{
      maxV = Math.max(maxV, Math.abs(v[0]), Math.abs(v[1]));
    }}
    maxSpan = Math.max(maxV, 4000);
  }}

  const baseScale = (Math.min(canvas.width, canvas.height) / 2) / (maxSpan * 1.35);
  const scale = baseScale * canvasZoom;

  const showGrid = document.getElementById('chk-grid')?.checked ?? true;
  const showBins = document.getElementById('chk-bins')?.checked ?? true;
  const showBounds = document.getElementById('chk-bounds')?.checked ?? true;
  const showSpiral = document.getElementById('chk-spiral')?.checked ?? true;
  const showHazards = document.getElementById('chk-hazards')?.checked ?? true;

  // Grid
  if (showGrid) {{
    ctx.strokeStyle = '#1e1e2e';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(0, cy); ctx.lineTo(canvas.width, cy);
    ctx.moveTo(cx, 0); ctx.lineTo(cx, canvas.height);
    ctx.stroke();

    // World border
    ctx.strokeStyle = '#313244';
    ctx.strokeRect(cx - (WORLD_BORDER_MAX * scale), cy - (WORLD_BORDER_MAX * scale), (WORLD_BORDER_MAX * 2) * scale, (WORLD_BORDER_MAX * 2) * scale);
  }}

  // Dynamic P-Bin derived from PointEdgeSelector
  const rChunks = Math.max(1, Math.round(rBlocks / 16));
  const p = derivePointEdgeChunks(rChunks);
  const pBlockEdge = p * 16;
  const pLabel = document.getElementById('layer-p-label');
  if (pLabel) pLabel.textContent = `P=${{p}} (${{pBlockEdge}}b)`;

  // Macro-Tile Coarse P-Bins Grid
  if (showBins) {{
    ctx.strokeStyle = 'rgba(137, 180, 250, 0.08)';
    ctx.lineWidth = 1;
    const binPx = pBlockEdge * scale;
    if (binPx >= 6) {{
      const startX = cx % binPx;
      const startY = cy % binPx;
      ctx.beginPath();
      for (let x = startX; x < canvas.width; x += binPx) {{
        ctx.moveTo(x, 0); ctx.lineTo(x, canvas.height);
      }}
      for (let y = startY; y < canvas.height; y += binPx) {{
        ctx.moveTo(0, y); ctx.lineTo(canvas.width, y);
      }}
      ctx.stroke();
    }}
  }}

  // 1. Walk Path (Continuous full range trajectory sourced directly from rtp-core plugin shape algorithms)
  if (showSpiral) {{
    ctx.lineWidth = 1.3;
    const shape = regionState.shape;

    if (shape === 'CIRCLE' || shape === 'SQUARE') {{
      // ADR-085 Dual-Layer Chained Hilbert Curve across coarse Chebyshev macro-tiles
      // Sourced directly from CircleOptimizedDualLayer.java and SquareOptimizedDualLayer.java
      ctx.strokeStyle = 'rgba(166, 227, 161, 0.65)';
      const area = p * p;
      const kOuter = Math.max(1, Math.ceil(rChunks / p));

      // Adaptive step size inside macro-tile to keep performance snappy (< 16ms)
      const totalTiles = 4 * kOuter * kOuter;
      let stepH = 1;
      if (totalTiles > 400) stepH = Math.max(1, Math.floor(area / 16));
      else if (totalTiles > 100) stepH = Math.max(1, Math.floor(area / 64));

      ctx.beginPath();
      let firstPoint = true;

      for (let K = 1; K <= kOuter; K++) {{
        const sideLen = 2 * K - 1;
        for (let side = 0; side < 4; side++) {{
          for (let sideStep = 0; sideStep < sideLen; sideStep++) {{
            let px, pz;
            if (side === 0) {{
              px = K - 1;
              pz = -(K - 1) + sideStep;
            }} else if (side === 1) {{
              pz = K - 1;
              px = (K - 2) - sideStep;
            }} else if (side === 2) {{
              px = -K;
              pz = (K - 2) - sideStep;
            }} else {{
              pz = -K;
              px = (-K + 1) + sideStep;
            }}

            const orientation = orientationFor(px, pz);

            for (let h = 0; h < area; h += stepH) {{
              const local = hilbertToXY(h, p, orientation);
              const chunkX = px * p + local[0];
              const chunkZ = pz * p + local[1];
              const blockX = chunkX * 16 + 8;
              const blockZ = chunkZ * 16 + 8;

              // Don't cut lines through the shape; draw the full continuous range walk
              const drawX = cx + blockX * scale;
              const drawY = cy + blockZ * scale;

              if (firstPoint) {{
                ctx.moveTo(drawX, drawY);
                firstPoint = false;
              }} else {{
                ctx.lineTo(drawX, drawY);
              }}
            }}
          }}
        }}
      }}
      ctx.stroke();
    }} else if (shape === 'RECTANGLE') {{
      // Sourced directly from Rectangle.java:
      // locationToXZ computes raster order:
      // output.x = (location % width) - (width / 2);
      // output.z = (location / width) - (height / 2);
      // followed by rotation around origin and shift by (centerX, centerZ).
      ctx.strokeStyle = 'rgba(166, 227, 161, 0.65)';
      const w = Math.max(1, rxBlocks * 2);
      const h = Math.max(1, rzBlocks * 2);
      const totalRange = w * h;

      // Draw continuous raster scan path line-by-line across rectangle
      // Sample rows adaptively so it draws crisp and under 16ms
      const targetLines = Math.min(60, Math.floor(h));
      const rowStep = Math.max(1, Math.floor(h / targetLines));
      ctx.beginPath();
      let firstPoint = true;
      let leftToRight = true;

      for (let row = 0; row < h; row += rowStep) {{
        const zLocal = row - Math.floor(h / 2);
        const startCol = leftToRight ? 0 : w - 1;
        const endCol = leftToRight ? w - 1 : 0;
        const colDir = leftToRight ? 1 : -1;

        // Draw line across this row
        const colSteps = Math.min(20, Math.floor(w));
        const cStep = Math.max(1, Math.floor(w / colSteps));

        for (let col = startCol; (colDir > 0 ? col <= endCol : col >= endCol); col += colDir * cStep) {{
          const xLocal = col - Math.floor(w / 2);
          const drawX = cx + xLocal * scale;
          const drawY = cy + zLocal * scale;
          if (firstPoint) {{
            ctx.moveTo(drawX, drawY);
            firstPoint = false;
          }} else {{
            ctx.lineTo(drawX, drawY);
          }}
        }}
        // Connect to exact end of row
        const endXLocal = endCol - Math.floor(w / 2);
        const endDrawX = cx + endXLocal * scale;
        const endDrawY = cy + zLocal * scale;
        ctx.lineTo(endDrawX, endDrawY);

        leftToRight = !leftToRight;
      }}
      ctx.stroke();
    }} else if (shape === 'CIRCLE_DEPRECATED_PURE_SPIRAL') {{
      // Sourced directly from Circle.java:
      // locationToXZ traces an Archimedean polar spiral from inner radius (cr) to outer radius (r).
      // To draw smoothly on canvas without chord-skipping aliasing (chords cutting across the shape),
      // we trace the continuous spiral parameterized by rotational angle theta from cr to r.
      ctx.strokeStyle = 'rgba(166, 227, 161, 0.65)';
      const r = rBlocks;
      const cr = crBlocks;
      const deltaR = Math.max(1, r - cr);
      // Adaptive number of spiral turns based on radius
      const turns = Math.min(60, Math.max(8, Math.floor(deltaR / 12)));
      const totalAngle = turns * 2.0 * Math.PI;
      const steps = Math.min(5000, Math.max(300, turns * 60));

      ctx.beginPath();
      for (let i = 0; i <= steps; i++) {{
        const frac = i / steps;
        const theta = frac * totalAngle;
        const currR = cr + frac * (r - cr);
        const bx = currR * Math.cos(theta);
        const bz = currR * Math.sin(theta);
        const drawX = cx + bx * scale;
        const drawY = cy + bz * scale;
        if (i === 0) ctx.moveTo(drawX, drawY);
        else ctx.lineTo(drawX, drawY);
      }}
      ctx.stroke();
    }} else if (shape === 'SQUARE_DEPRECATED_PURE_SPIRAL') {{
      // Sourced directly from Square.java and SquareGeometry.java:
      // Walks outward ring-by-ring from cr to r.
      // To draw smoothly without chords jumping across the square, we trace continuous ring perimeters.
      ctx.strokeStyle = 'rgba(166, 227, 161, 0.65)';
      const r = rBlocks;
      const cr = crBlocks;
      const deltaR = Math.max(1, r - cr);
      const targetRings = Math.min(50, Math.max(6, Math.floor(deltaR / 10)));
      const ringStep = Math.max(1, Math.floor(deltaR / targetRings));

      ctx.beginPath();
      let firstPoint = true;

      for (let currR = cr; currR <= r; currR += ringStep) {{
        if (currR === 0) continue;
        // Trace 8 octant vertices around square perimeter
        const corners = [
          [currR, 0],
          [currR, currR],
          [0, currR],
          [-currR, currR],
          [-currR, 0],
          [-currR, -currR],
          [0, -currR],
          [currR, -currR],
          [currR, 0]
        ];
        for (const [bx, bz] of corners) {{
          const drawX = cx + bx * scale;
          const drawY = cy + bz * scale;
          if (firstPoint) {{
            ctx.moveTo(drawX, drawY);
            firstPoint = false;
          }} else {{
            ctx.lineTo(drawX, drawY);
          }}
        }}
      }}
      ctx.stroke();
    }} else if (shape === 'CIRCLE_NORMAL') {{
      // Sourced from Circle_Normal.java:
      // Gaussian distribution over polar spiral space
      ctx.strokeStyle = 'rgba(166, 227, 161, 0.65)';
      const r = rBlocks;
      const cr = crBlocks;
      const deltaR = Math.max(1, r - cr);
      const turns = Math.min(50, Math.max(8, Math.floor(deltaR / 12)));
      const totalAngle = turns * 2.0 * Math.PI;
      const steps = Math.min(4000, Math.max(300, turns * 60));

      ctx.beginPath();
      for (let i = 0; i <= steps; i++) {{
        const frac = i / steps;
        const theta = frac * totalAngle;
        const currR = cr + frac * (r - cr);
        const bx = currR * Math.cos(theta);
        const bz = currR * Math.sin(theta);
        const drawX = cx + bx * scale;
        const drawY = cy + bz * scale;
        if (i === 0) ctx.moveTo(drawX, drawY);
        else ctx.lineTo(drawX, drawY);
      }}
      ctx.stroke();
    }} else if (shape === 'SQUARE_NORMAL') {{
      // Sourced from Square_Normal.java:
      // Gaussian distribution over Chebyshev square ring space
      ctx.strokeStyle = 'rgba(166, 227, 161, 0.65)';
      const r = rBlocks;
      const cr = crBlocks;
      const deltaR = Math.max(1, r - cr);
      const targetRings = Math.min(50, Math.max(6, Math.floor(deltaR / 10)));
      const ringStep = Math.max(1, Math.floor(deltaR / targetRings));

      ctx.beginPath();
      let firstPoint = true;

      for (let currR = cr; currR <= r; currR += ringStep) {{
        if (currR === 0) continue;
        const corners = [
          [currR, 0],
          [currR, currR],
          [0, currR],
          [-currR, currR],
          [-currR, 0],
          [-currR, -currR],
          [0, -currR],
          [currR, -currR],
          [currR, 0]
        ];
        for (const [bx, bz] of corners) {{
          const drawX = cx + bx * scale;
          const drawY = cy + bz * scale;
          if (firstPoint) {{
            ctx.moveTo(drawX, drawY);
            firstPoint = false;
          }} else {{
            ctx.lineTo(drawX, drawY);
          }}
        }}
      }}
      ctx.stroke();
    }} else if (shape === 'ELLIPSE') {{
      // Sourced directly from Ellipse.java:
      // Inscribed in Circle with effectiveRadius and effectiveCenterRadius
      // locationToXZ: exact Circle polar spiral with preciseRadius, rotated by degrees
      ctx.strokeStyle = 'rgba(166, 227, 161, 0.65)';
      const effR = Math.max(rxBlocks, rzBlocks);
      const effCr = Math.min(crBlocks, effR - 1);
      const fullRange = Math.max(1, Math.PI * (effR * effR - effCr * effCr));
      const numSamples = 6000;
      const sampleStep = Math.max(1, Math.floor(fullRange / numSamples));

      ctx.beginPath();
      let firstPoint = true;

      for (let loc = 0; loc <= fullRange; loc += sampleStep) {{
        const preciseRadius = Math.sqrt(loc / Math.PI + effCr * effCr);
        const R = Math.floor(preciseRadius);
        const startLoc = R * R - effCr * effCr;
        const currentLocation = Math.floor(loc / Math.PI);
        const remainingLength = currentLocation - startLoc;
        const totalRingLength = 2 * R + 1;
        const proportion = remainingLength / totalRingLength;
        const rotation = (proportion + 0.000069) * 2.0 * Math.PI;

        // Scale to ellipse axes
        const bx = (preciseRadius * (rxBlocks / effR)) * Math.cos(rotation);
        const bz = (preciseRadius * (rzBlocks / effR)) * Math.sin(rotation);
        const drawX = cx + bx * scale;
        const drawY = cy + bz * scale;

        if (firstPoint) {{
          ctx.moveTo(drawX, drawY);
          firstPoint = false;
        }} else {{
          ctx.lineTo(drawX, drawY);
        }}
      }}
      ctx.stroke();
    }} else if (shape === 'POLYGON' && regionState.vertices && regionState.vertices.length >= 3) {{
      // Sourced directly from Polygon.java:
      // Polygon inherits from Square bounded by the polygon AABB, centered at (aabbCenterX, aabbCenterZ)
      // with radius aabbR = max(halfX, halfZ). To draw smoothly without chord jumping across the polygon,
      // we trace continuous concentric Chebyshev perimeter rings centered at the AABB center.
      let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
      for (const v of regionState.vertices) {{
        minX = Math.min(minX, v[0]);
        maxX = Math.max(maxX, v[0]);
        minZ = Math.min(minZ, v[1]);
        maxZ = Math.max(maxZ, v[1]);
      }}
      const halfX = (maxX - minX + 1) / 2;
      const halfZ = (maxZ - minZ + 1) / 2;
      const aabbR = Math.max(halfX, halfZ);
      const aabbCenterX = (minX + maxX) / 2;
      const aabbCenterZ = (minZ + maxZ) / 2;

      ctx.strokeStyle = 'rgba(166, 227, 161, 0.65)';
      const targetRings = Math.min(50, Math.max(6, Math.floor(aabbR / 10)));
      const ringStep = Math.max(1, Math.floor(aabbR / targetRings));

      ctx.beginPath();
      let firstPoint = true;

      for (let currR = ringStep; currR <= aabbR; currR += ringStep) {{
        const corners = [
          [currR, 0],
          [currR, currR],
          [0, currR],
          [-currR, currR],
          [-currR, 0],
          [-currR, -currR],
          [0, -currR],
          [currR, -currR],
          [currR, 0]
        ];
        for (const [bx, bz] of corners) {{
          const drawX = cx + (aabbCenterX + bx) * scale;
          const drawY = cy + (aabbCenterZ + bz) * scale;
          if (firstPoint) {{
            ctx.moveTo(drawX, drawY);
            firstPoint = false;
          }} else {{
            ctx.lineTo(drawX, drawY);
          }}
        }}
      }}
      ctx.stroke();
    }}
  }}

  // 2. Shading out the parts outside the active shape / donut
  // A. Outer Exclusion Shading: everything outside the outer boundary is shaded dark
  if (showBounds) {{
    ctx.save();
    ctx.fillStyle = 'rgba(17, 17, 27, 0.65)';
    ctx.beginPath();
    // Outer canvas rectangle
    ctx.rect(0, 0, canvas.width, canvas.height);

    // Cut out inner valid shape geometry using evenodd winding
    const shape = regionState.shape;
    if (shape === 'CIRCLE' || shape === 'CIRCLE_NORMAL' || shape === 'CIRCLE_DEPRECATED_PURE_SPIRAL') {{
      ctx.arc(cx, cy, rBlocks * scale, 0, Math.PI * 2, true);
    }} else if (shape === 'SQUARE' || shape === 'SQUARE_NORMAL' || shape === 'SQUARE_DEPRECATED_PURE_SPIRAL') {{
      const sz = rBlocks * 2 * scale;
      ctx.rect(cx + rBlocks * scale, cy - rBlocks * scale, -sz, sz);
    }} else if (shape === 'RECTANGLE') {{
      const w = rxBlocks * 2 * scale;
      const h = rzBlocks * 2 * scale;
      ctx.rect(cx + rxBlocks * scale, cy - rzBlocks * scale, -w, h);
    }} else if (shape === 'ELLIPSE') {{
      ctx.ellipse(cx, cy, rxBlocks * scale, rzBlocks * scale, 0, 0, Math.PI * 2, true);
    }} else if (shape === 'POLYGON' && regionState.vertices && regionState.vertices.length >= 3) {{
      // Cut out polygon interior
      for (let i = regionState.vertices.length - 1; i >= 0; i--) {{
        const v = regionState.vertices[i];
        const vx = cx + v[0] * scale;
        const vy = cy + v[1] * scale;
        if (i === regionState.vertices.length - 1) ctx.moveTo(vx, vy);
        else ctx.lineTo(vx, vy);
      }}
      ctx.closePath();
    }}
    ctx.fill('evenodd');
    ctx.restore();

    // B. Inner Donut Exclusion Shading (shade out the donut hole < crBlocks)
    if (crBlocks > 0 && shape !== 'POLYGON') {{
      ctx.save();
      ctx.fillStyle = 'rgba(243, 139, 168, 0.28)';
      ctx.strokeStyle = '#f38ba8';
      ctx.lineWidth = 1.8;
      ctx.beginPath();
      if (shape === 'CIRCLE' || shape === 'CIRCLE_NORMAL' || shape === 'CIRCLE_DEPRECATED_PURE_SPIRAL') {{
        ctx.arc(cx, cy, crBlocks * scale, 0, Math.PI * 2);
      }} else if (shape === 'SQUARE' || shape === 'SQUARE_NORMAL' || shape === 'SQUARE_DEPRECATED_PURE_SPIRAL') {{
        ctx.rect(cx - crBlocks * scale, cy - crBlocks * scale, crBlocks * 2 * scale, crBlocks * 2 * scale);
      }} else if (shape === 'RECTANGLE') {{
        const crRatio = crBlocks / rBlocks;
        const crX = rxBlocks * crRatio * scale;
        const crZ = rzBlocks * crRatio * scale;
        ctx.rect(cx - crX, cy - crZ, crX * 2, crZ * 2);
      }} else if (shape === 'ELLIPSE') {{
        const crRatio = crBlocks / rBlocks;
        ctx.ellipse(cx, cy, rxBlocks * crRatio * scale, rzBlocks * crRatio * scale, 0, 0, Math.PI * 2);
      }}
      ctx.fill();
      ctx.stroke();
      ctx.restore();
    }}

    // C. Outer Boundary Stroke
    ctx.save();
    ctx.strokeStyle = '#89b4fa';
    ctx.lineWidth = 2;
    ctx.beginPath();
    if (shape === 'CIRCLE' || shape === 'CIRCLE_NORMAL' || shape === 'CIRCLE_DEPRECATED_PURE_SPIRAL') {{
      ctx.arc(cx, cy, rBlocks * scale, 0, Math.PI * 2);
      ctx.stroke();
    }} else if (shape === 'SQUARE' || shape === 'SQUARE_NORMAL' || shape === 'SQUARE_DEPRECATED_PURE_SPIRAL') {{
      ctx.strokeRect(cx - rBlocks * scale, cy - rBlocks * scale, rBlocks * 2 * scale, rBlocks * 2 * scale);
    }} else if (shape === 'RECTANGLE') {{
      ctx.strokeRect(cx - rxBlocks * scale, cy - rzBlocks * scale, rxBlocks * 2 * scale, rzBlocks * 2 * scale);
    }} else if (shape === 'ELLIPSE') {{
      ctx.ellipse(cx, cy, rxBlocks * scale, rzBlocks * scale, 0, 0, Math.PI * 2);
      ctx.stroke();
    }} else if (shape === 'POLYGON' && regionState.vertices && regionState.vertices.length >= 3) {{
      // Draw AABB bounding box dashed line (Square base class of Polygon)
      let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
      for (const v of regionState.vertices) {{
        minX = Math.min(minX, v[0]);
        maxX = Math.max(maxX, v[0]);
        minZ = Math.min(minZ, v[1]);
        maxZ = Math.max(maxZ, v[1]);
      }}
      ctx.save();
      ctx.strokeStyle = 'rgba(137, 180, 250, 0.4)';
      ctx.setLineDash([4, 4]);
      ctx.strokeRect(cx + minX * scale, cy + minZ * scale, (maxX - minX) * scale, (maxZ - minZ) * scale);
      ctx.restore();

      // Draw polygon boundary stroke
      ctx.beginPath();
      for (let i = 0; i < regionState.vertices.length; i++) {{
        const v = regionState.vertices[i];
        const vx = cx + v[0] * scale;
        const vy = cy + v[1] * scale;
        if (i === 0) ctx.moveTo(vx, vy);
        else ctx.lineTo(vx, vy);
      }}
      ctx.closePath();
      ctx.stroke();

      // Vertex drag handles
      for (let i = 0; i < regionState.vertices.length; i++) {{
        const v = regionState.vertices[i];
        const vx = cx + v[0] * scale;
        const vy = cy + v[1] * scale;
        ctx.fillStyle = (draggingVertex === i) ? '#f9e2af' : '#a6e3a1';
        ctx.beginPath();
        ctx.arc(vx, vy, 6, 0, Math.PI * 2);
        ctx.fill();
        ctx.strokeStyle = '#11111b';
        ctx.lineWidth = 1.5;
        ctx.stroke();
      }}
    }}
    ctx.restore();
  }}

  // Bad location hazard runs (RLE Hilbert stream)
  if (showHazards && hazardRuns && hazardRuns.length > 0) {{
    ctx.fillStyle = 'rgba(243, 139, 168, 0.6)';
    for (const run of hazardRuns) {{
      for (let i = 0; i < run.len; i++) {{
        const pt = hilbertToXy(run.start + i, hazardBounds.order);
        const bx = hazardBounds.minX + (pt[0] / hazardBounds.gridSize) * (hazardBounds.maxX - hazardBounds.minX);
        const bz = hazardBounds.minZ + (pt[1] / hazardBounds.gridSize) * (hazardBounds.maxZ - hazardBounds.minZ);
        ctx.fillRect(cx + bx * scale - 2, cy + bz * scale - 2, 4, 4);
      }}
    }}
  }}
}}

// Helper to get current transformation parameters
function getCanvasTransform() {{
  const cx = canvas.width / 2 + canvasPanX;
  const cy = canvas.height / 2 + canvasPanY;
  const unit = regionState.unit || 'c';
  const unitMult = (unit === 'c' ? 16 : (unit === 'r' ? 512 : (unit === 'km' ? 1000 : 1)));
  const rBlocks = (regionState.radius || 256) * unitMult;
  let rxBlocks = (regionState.radiusX || regionState.radius || 256) * unitMult;
  let rzBlocks = (regionState.radiusZ || Math.round((regionState.radius || 256) / 2)) * unitMult;

  let maxSpan = Math.max(rBlocks, 4000);
  if (regionState.shape === 'RECTANGLE' || regionState.shape === 'ELLIPSE') {{
    maxSpan = Math.max(rxBlocks, rzBlocks, 4000);
  }} else if (regionState.shape === 'POLYGON' && regionState.vertices && regionState.vertices.length > 0) {{
    let maxV = 1000;
    for (const v of regionState.vertices) {{
      maxV = Math.max(maxV, Math.abs(v[0]), Math.abs(v[1]));
    }}
    maxSpan = Math.max(maxV, 4000);
  }}
  const baseScale = (Math.min(canvas.width, canvas.height) / 2) / (maxSpan * 1.35);
  const scale = baseScale * canvasZoom;
  return {{ cx, cy, scale }};
}}

// Canvas mouse interaction for zoom, pan, polygon dragging & coordinate tracking
canvas.addEventListener('wheel', (e) => {{
  e.preventDefault();
  const rect = canvas.getBoundingClientRect();
  const mouseX = e.clientX - rect.left;
  const mouseY = e.clientY - rect.top;

  const zoomFactor = e.deltaY < 0 ? 1.15 : (1 / 1.15);
  const newZoom = Math.max(0.1, Math.min(50.0, canvasZoom * zoomFactor));

  // Anchor world coordinate under mouse cursor
  canvasPanX = mouseX - canvas.width / 2 - (mouseX - canvas.width / 2 - canvasPanX) * (newZoom / canvasZoom);
  canvasPanY = mouseY - canvas.height / 2 - (mouseY - canvas.height / 2 - canvasPanY) * (newZoom / canvasZoom);
  canvasZoom = newZoom;

  drawMap();
}}, {{ passive: false }});

canvas.addEventListener('mousemove', (e) => {{
  const rect = canvas.getBoundingClientRect();
  const mx = e.clientX - rect.left;
  const my = e.clientY - rect.top;
  const {{ cx, cy, scale }} = getCanvasTransform();

  const worldX = Math.round((mx - cx) / scale);
  const worldZ = Math.round((my - cy) / scale);
  document.getElementById('hud-coords').textContent = `X: ${{worldX}}  Z: ${{worldZ}}`;

  if (isPanning) {{
    canvasPanX += (mx - panStartX);
    canvasPanY += (my - panStartY);
    panStartX = mx;
    panStartY = my;
    drawMap();
    return;
  }}

  if (draggingVertex >= 0 && regionState.vertices) {{
    regionState.vertices[draggingVertex] = [worldX, worldZ];
    const inputX = document.getElementById(`poly-vx-${{draggingVertex}}`);
    const inputZ = document.getElementById(`poly-vz-${{draggingVertex}}`);
    if (inputX) inputX.value = worldX;
    if (inputZ) inputZ.value = worldZ;
    syncPolygonAABB();
    drawMap();
    updateStagingDiff();
  }}
}});

canvas.addEventListener('mousedown', (e) => {{
  const rect = canvas.getBoundingClientRect();
  const mx = e.clientX - rect.left;
  const my = e.clientY - rect.top;

  // Middle-click, right-click, or Shift/Ctrl+Click triggers panning
  if (e.button === 1 || e.button === 2 || e.shiftKey || e.ctrlKey) {{
    isPanning = true;
    panStartX = mx;
    panStartY = my;
    return;
  }}

  if (regionState.shape !== 'POLYGON' || !regionState.vertices) return;
  const {{ cx, cy, scale }} = getCanvasTransform();

  for (let i = 0; i < regionState.vertices.length; i++) {{
    const v = regionState.vertices[i];
    const vx = cx + v[0] * scale;
    const vy = cy + v[1] * scale;
    const dist = Math.hypot(mx - vx, my - vy);
    if (dist <= 12) {{
      draggingVertex = i;
      return;
    }}
  }}
}});

canvas.addEventListener('contextmenu', (e) => {{
  e.preventDefault();
}});

window.addEventListener('mouseup', () => {{
  if (isPanning) {{
    isPanning = false;
  }}
  if (draggingVertex >= 0) {{
    draggingVertex = -1;
    renderPolygonVerticesList();
    syncPolygonAABB();
    drawMap();
    updateStagingDiff();
  }}
}});

// In Polygon.java, Polygon derives its radius, center, and AABB from vertices
function syncPolygonAABB() {{
  if (regionState.shape !== 'POLYGON' || !regionState.vertices || regionState.vertices.length < 3) return;
  let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
  for (const v of regionState.vertices) {{
    minX = Math.min(minX, v[0]);
    maxX = Math.max(maxX, v[0]);
    minZ = Math.min(minZ, v[1]);
    maxZ = Math.max(maxZ, v[1]);
  }}
  const halfX = (maxX - minX + 1) / 2;
  const halfZ = (maxZ - minZ + 1) / 2;
  const rBlocks = Math.max(halfX, halfZ);
  const unit = regionState.unit || 'c';
  const unitMult = (unit === 'c' ? 16 : 1);
  regionState.radius = Math.round(rBlocks / unitMult);
  regionState.centerRadius = 0;
  const radInput = document.getElementById('reg-radius');
  if (radInput) radInput.value = regionState.radius;
  updateUnitHints();
  const countEl = document.getElementById('poly-points-count');
  if (countEl && regionState.vertices) countEl.textContent = regionState.vertices.length;
}}

function renderPolygonVerticesList() {{
  const container = document.getElementById('polygon-vertices-list');
  const countEl = document.getElementById('poly-points-count');
  if (!container || !regionState.vertices) return;

  countEl.textContent = regionState.vertices.length;
  const canDelete = regionState.vertices.length > 3;

  container.innerHTML = regionState.vertices.map((v, i) => `
    <div style="display:flex; align-items:center; gap:6px; background:rgba(30,30,46,0.6); padding:4px 8px; border-radius:4px; border:1px solid rgba(255,255,255,0.06);">
      <span style="font-size:0.75rem; font-weight:600; color:var(--accent); min-width:24px;">P${{i + 1}}</span>
      <div style="display:flex; align-items:center; gap:4px; flex:1;">
        <span style="font-size:0.7rem; color:var(--subtext);">X:</span>
        <input type="number" id="poly-vx-${{i}}" class="control-input" style="padding:2px 4px; font-size:0.75rem; height:24px;" value="${{v[0]}}" oninput="updateVertexCoord(${{i}}, 0, this.value)">
      </div>
      <div style="display:flex; align-items:center; gap:4px; flex:1;">
        <span style="font-size:0.7rem; color:var(--subtext);">Z:</span>
        <input type="number" id="poly-vz-${{i}}" class="control-input" style="padding:2px 4px; font-size:0.75rem; height:24px;" value="${{v[1]}}" oninput="updateVertexCoord(${{i}}, 1, this.value)">
      </div>
      <button type="button" style="background:${{canDelete ? 'rgba(243,139,168,0.2)' : 'rgba(255,255,255,0.05)'}}; color:${{canDelete ? 'var(--red)' : 'var(--subtext)'}}; border:none; border-radius:3px; padding:2px 6px; font-size:0.75rem; cursor:${{canDelete ? 'pointer' : 'not-allowed'}};" title="${{canDelete ? 'Delete Point' : 'At least 3 vertices required'}}" ${{canDelete ? '' : 'disabled'}} onclick="removePolygonVertex(${{i}})">✕</button>
    </div>
  `).join('');
}}

function updateVertexCoord(index, axis, value) {{
  if (!regionState.vertices || !regionState.vertices[index]) return;
  const num = parseInt(value, 10);
  regionState.vertices[index][axis] = isNaN(num) ? 0 : num;
  syncPolygonAABB();
  drawMap();
  updateStagingDiff();
}}

function addPolygonVertex() {{
  if (!regionState.vertices) regionState.vertices = [];
  const n = regionState.vertices.length;
  let newX = 0, newZ = 0;
  if (n >= 2) {{
    // Midpoint between last vertex and first vertex to keep polygon closed nicely
    const last = regionState.vertices[n - 1];
    const first = regionState.vertices[0];
    newX = Math.round((last[0] + first[0]) / 2) + 200;
    newZ = Math.round((last[1] + first[1]) / 2) + 200;
  }} else if (n === 1) {{
    newX = regionState.vertices[0][0] + 1000;
    newZ = regionState.vertices[0][1] + 1000;
  }}
  regionState.vertices.push([newX, newZ]);
  renderPolygonVerticesList();
  syncPolygonAABB();
  drawMap();
  updateStagingDiff();
}}

function removePolygonVertex(index) {{
  if (!regionState.vertices || regionState.vertices.length <= 3) return;
  regionState.vertices.splice(index, 1);
  renderPolygonVerticesList();
  syncPolygonAABB();
  drawMap();
  updateStagingDiff();
}}

function handleRegionSelectChange(val) {{
  if (val === '__add_region__') {{
    const selectEl = document.getElementById('reg-profile-select');
    if (selectEl) selectEl.value = regionState.name;
    promptAddNewRegion();
    return;
  }}
  changeRegionProfile(val);
}}

function promptAddNewRegion() {{
  const input = prompt('Enter new region name (e.g. custom_region, vip_spawn):', 'custom_region');
  if (!input) return;

  let cleanName = input.trim();
  if (cleanName.endsWith('.yml')) {{
    cleanName = cleanName.substring(0, cleanName.length - 4);
  }}
  if (!/^[a-zA-Z0-9_.-]+$/.test(cleanName)) {{
    alert('Invalid region name. Use alphanumeric characters, dashes, and underscores only.');
    return;
  }}

  const fullPath = `definitions/regions/${{cleanName}}.yml`;
  if (regionProfiles[cleanName] !== undefined || configs[fullPath] !== undefined) {{
    alert(`A region named "${{cleanName}}" already exists.`);
    changeRegionProfile(cleanName);
    return;
  }}

  const template = `# RTP Region Definition: ${{cleanName}}
world: "[0]"
shape: CIRCLE
radius: 256c
centerRadius: 64c
center:
  x: 0
  z: 0
weight: 1.0
requirePermission: false
price: 0.0
version: "1.0"
`;
  configs[fullPath] = template;

  regionProfiles[cleanName] = {{
    name: cleanName,
    shape: 'CIRCLE',
    unit: 'c',
    radius: 256,
    centerRadius: 64,
    radiusX: 256,
    radiusZ: 128,
    vertices: []
  }};

  syncRegionProfiles();
  populateConfigSidebar();
  changeRegionProfile(cleanName);
}}

function removeCurrentRegion() {{
  const currentName = regionState.name;
  if (!currentName || currentName === 'default') {{
    alert('The default region configuration is protected and cannot be removed.');
    return;
  }}

  const confirmDel = confirm(`Are you sure you want to delete region "${{currentName}}"? This will remove its configuration file from staged changes.`);
  if (!confirmDel) return;

  delete regionProfiles[currentName];
  delete configs[`definitions/regions/${{currentName}}.yml`];
  delete configs[`regions/${{currentName}}.yml`];

  changeRegionProfile('default');
  syncRegionProfiles();
  populateConfigSidebar();
  updateStagingDiff();
}}

function syncRegionProfiles() {{
  if (!regionProfiles.default) {{
    regionProfiles.default = {{ name: 'default', shape: 'CIRCLE', unit: 'c', radius: 256, centerRadius: 64, radiusX: 256, radiusZ: 128, vertices: [] }};
  }}

  for (const fileName of Object.keys(configs)) {{
    let regionName = null;
    if (fileName.startsWith('definitions/regions/') && fileName.endsWith('.yml')) {{
      regionName = fileName.substring('definitions/regions/'.length, fileName.length - 4);
    }} else if (fileName.startsWith('regions/') && fileName.endsWith('.yml')) {{
      regionName = fileName.substring('regions/'.length, fileName.length - 4);
    }}
    if (regionName && !regionProfiles[regionName]) {{
      const yml = configs[fileName] || '';
      let shape = 'CIRCLE';
      const shapeMatch = yml.match(/^\\s*shape:\\s*["']?([A-Za-z0-9_]+)["']?/m);
      if (shapeMatch && shapeMatch[1] && shapeMatch[1] !== '@config') {{
        shape = shapeMatch[1];
      }}
      let radius = 256;
      let unit = 'c';
      const radMatch = yml.match(/^\\s*radius:\\s*([0-9]+)([a-zA-Z]*)/m);
      if (radMatch) {{
        radius = parseInt(radMatch[1], 10) || 256;
        if (radMatch[2]) unit = radMatch[2];
      }}
      let centerRadius = 64;
      const crMatch = yml.match(/^\\s*centerRadius:\\s*([0-9]+)/m);
      if (crMatch) {{
        centerRadius = parseInt(crMatch[1], 10) || 0;
      }}
      regionProfiles[regionName] = {{
        name: regionName,
        shape: shape,
        unit: unit,
        radius: radius,
        centerRadius: centerRadius,
        radiusX: radius,
        radiusZ: Math.round(radius / 2),
        vertices: []
      }};
    }}
  }}

  const selectEl = document.getElementById('reg-profile-select');
  if (selectEl) {{
    const curVal = regionState ? regionState.name : 'default';
    selectEl.innerHTML = '';

    const sortedNames = Object.keys(regionProfiles).sort((a, b) => {{
      if (a === 'default') return -1;
      if (b === 'default') return 1;
      return a.localeCompare(b);
    }});

    for (const name of sortedNames) {{
      const opt = document.createElement('option');
      opt.value = name;
      opt.textContent = name === 'default' ? 'default (Default Region)' : name;
      selectEl.appendChild(opt);
    }}

    const addOpt = document.createElement('option');
    addOpt.value = '__add_region__';
    addOpt.textContent = '➕ Add Region...';
    selectEl.appendChild(addOpt);

    selectEl.value = regionProfiles[curVal] ? curVal : 'default';
  }}

  const removeBtn = document.getElementById('btn-remove-region');
  if (removeBtn) {{
    const isDefault = !regionState || regionState.name === 'default';
    removeBtn.disabled = isDefault;
    if (isDefault) {{
      removeBtn.title = 'The default region is protected and cannot be removed';
      removeBtn.style.opacity = '0.4';
      removeBtn.style.cursor = 'not-allowed';
    }} else {{
      removeBtn.title = `Remove region "${{regionState.name}}"`;
      removeBtn.style.opacity = '1.0';
      removeBtn.style.cursor = 'pointer';
    }}
  }}
}}

function changeRegionProfile(profileKey) {{
  if (regionProfiles[profileKey]) {{
    regionState = regionProfiles[profileKey];
    document.getElementById('hud-region').textContent = regionState.name;
    document.getElementById('hud-shape').textContent = regionState.shape;
    document.getElementById('reg-shape').value = regionState.shape;
    document.getElementById('reg-radius').value = regionState.radius;
    document.getElementById('reg-center-radius').value = regionState.centerRadius;
    const unitEl = document.getElementById('reg-unit');
    if (unitEl) unitEl.value = regionState.unit || 'c';
    const selectEl = document.getElementById('reg-profile-select');
    if (selectEl && selectEl.value !== profileKey) selectEl.value = profileKey;

    const removeBtn = document.getElementById('btn-remove-region');
    if (removeBtn) {{
      const isDefault = regionState.name === 'default';
      removeBtn.disabled = isDefault;
      if (isDefault) {{
        removeBtn.title = 'The default region is protected and cannot be removed';
        removeBtn.style.opacity = '0.4';
        removeBtn.style.cursor = 'not-allowed';
      }} else {{
        removeBtn.title = `Remove region "${{regionState.name}}"`;
        removeBtn.style.opacity = '1.0';
        removeBtn.style.cursor = 'pointer';
      }}
    }}

    updateUnitHints();
    updateRegionShape(regionState.shape);
    drawMap();
    updateStagingDiff();
  }}
}}

function updateRegionShape(val) {{
  regionState.shape = val;
  document.getElementById('hud-shape').textContent = val;
  const isRectOrEllipse = (val === 'RECTANGLE' || val === 'ELLIPSE');
  const isPolygon = (val === 'POLYGON');
  const axesEl = document.getElementById('grp-rect-axes');
  const radEl = document.getElementById('grp-radius');
  const centerRadEl = document.getElementById('grp-center-radius');
  const polyPointsEl = document.getElementById('grp-polygon-points');

  if (axesEl) axesEl.style.display = isRectOrEllipse ? 'block' : 'none';
  if (radEl) radEl.style.display = (isRectOrEllipse || isPolygon) ? 'none' : 'block';
  if (centerRadEl) centerRadEl.style.display = (isPolygon) ? 'none' : 'block';
  if (polyPointsEl) {{
    polyPointsEl.style.display = isPolygon ? 'block' : 'none';
    if (isPolygon) {{
      if (!regionState.vertices || regionState.vertices.length < 3) {{
        regionState.vertices = [[-2000, 3000], [2000, 3000], [2000, -3000], [-2000, -3000]];
      }}
      renderPolygonVerticesList();
      syncPolygonAABB();
    }}
  }}
  drawMap();
  updateStagingDiff();
}}

function updateAxisX(val) {{
  regionState.radiusX = Math.max(1, parseInt(val, 10) || 1);
  updateUnitHints();
  drawMap();
  updateStagingDiff();
}}

function updateAxisZ(val) {{
  regionState.radiusZ = Math.max(1, parseInt(val, 10) || 1);
  updateUnitHints();
  drawMap();
  updateStagingDiff();
}}

function updateRadius(val) {{
  regionState.radius = Math.max(0, parseInt(val, 10) || 0);
  updateUnitHints();
  drawMap();
  updateStagingDiff();
}}

function updateCenterRadius(val) {{
  regionState.centerRadius = Math.max(0, parseInt(val, 10) || 0);
  updateUnitHints();
  drawMap();
  updateStagingDiff();
}}

function updateUnit(val) {{
  regionState.unit = val;
  updateUnitHints();
  drawMap();
  updateStagingDiff();
}}

function updateUnitHints() {{
  const unit = regionState.unit || 'c';
  const r = regionState.radius;
  const cr = regionState.centerRadius;
  let mult = 16;
  if (unit === 'b') mult = 1;
  else if (unit === 'r') mult = 512;
  else if (unit === 'km') mult = 1000;

  const hintR = document.getElementById('hint-radius');
  const hintCr = document.getElementById('hint-center-radius');
  if (hintR) hintR.textContent = `= ${{r * mult}} blocks`;
  if (hintCr) hintCr.textContent = `= ${{cr * mult}} blocks (inner deadzone)`;
}}

// ADR-034 Mathematical Validation & Polygon Non-Self-Intersection
const WORLD_BORDER_MAX = 29999984;

function validateGeometry(state) {{
  const warnings = [];
  if (state.centerRadius >= state.radius) {{
    warnings.push('Error: centerRadius must stay below outer radius or search space is empty');
  }}

  const maxBlock = state.radius * (state.unit === 'c' ? 16 : 1);
  if (maxBlock > WORLD_BORDER_MAX) {{
    warnings.push(`Error: radius (${{maxBlock}} blocks) exceeds vanilla world border boundary (${{WORLD_BORDER_MAX}})`);
  }}

  if (state.shape === 'POLYGON' && state.vertices && state.vertices.length >= 3) {{
    const n = state.vertices.length;
    // World border checks for vertices
    for (let i = 0; i < n; i++) {{
      const v = state.vertices[i];
      if (Math.abs(v[0]) > WORLD_BORDER_MAX || Math.abs(v[1]) > WORLD_BORDER_MAX) {{
        warnings.push(`Warning: vertex [${{v[0]}}, ${{v[1]}}] exceeds vanilla world border boundary`);
        break;
      }}
    }}

    // Check collinear adjacent edges
    for (let i = 0; i < n; i++) {{
      const p1 = state.vertices[i];
      const p2 = state.vertices[(i + 1) % n];
      const p3 = state.vertices[(i + 2) % n];
      const cross = (p2[0] - p1[0]) * (p3[1] - p1[1]) - (p2[1] - p1[1]) * (p3[0] - p1[0]);
      if (Math.abs(cross) < 1e-6) {{
        warnings.push(`Notice: vertices at index ${{i + 1}} are collinear (simplifiable per ADR-099)`);
      }}
    }}

    // ADR-034 non-self-intersection checker
    for (let i = 0; i < n; i++) {{
      const p1 = state.vertices[i];
      const p2 = state.vertices[(i + 1) % n];
      for (let j = i + 2; j < n; j++) {{
        if (i === 0 && j === n - 1) continue;
        const p3 = state.vertices[j];
        const p4 = state.vertices[(j + 1) % n];
        if (segmentsIntersect(p1, p2, p3, p4)) {{
          warnings.push(`Error: polygon edges self-intersect between segments [${{i}}-${{(i+1)%n}}] and [${{j}}-${{(j+1)%n}}] (ADR-034 violation)`);
          break;
        }}
      }}
    }}
  }}
  return warnings;
}}

function segmentsIntersect(p1, p2, p3, p4) {{
  function ccw(a, b, c) {{
    return (c[1] - a[1]) * (b[0] - a[0]) > (b[1] - a[1]) * (c[0] - a[0]);
  }}
  function onSegment(p, q, r) {{
    return q[0] <= Math.max(p[0], r[0]) && q[0] >= Math.min(p[0], r[0]) &&
           q[1] <= Math.max(p[1], r[1]) && q[1] >= Math.min(p[1], r[1]);
  }}
  const o1 = (p2[1] - p1[1]) * (p3[0] - p2[0]) - (p2[0] - p1[0]) * (p3[1] - p2[1]);
  const o2 = (p2[1] - p1[1]) * (p4[0] - p2[0]) - (p2[0] - p1[0]) * (p4[1] - p2[1]);
  const o3 = (p4[1] - p3[1]) * (p1[0] - p4[0]) - (p4[0] - p3[0]) * (p1[1] - p4[1]);
  const o4 = (p4[1] - p3[1]) * (p2[0] - p4[0]) - (p4[0] - p3[0]) * (p2[1] - p4[1]);

  if (((o1 > 0 && o2 < 0) || (o1 < 0 && o2 > 0)) && ((o3 > 0 && o4 < 0) || (o3 < 0 && o4 > 0))) {{
    return true;
  }}
  if (Math.abs(o1) < 1e-7 && onSegment(p1, p3, p2)) return true;
  if (Math.abs(o2) < 1e-7 && onSegment(p1, p4, p2)) return true;
  if (Math.abs(o3) < 1e-7 && onSegment(p3, p1, p4)) return true;
  if (Math.abs(o4) < 1e-7 && onSegment(p3, p2, p4)) return true;
  return false;
}}

// Client-side YAML diff generator comparing baseline config JSON with modified canvas/form state
function generateYamlDiff(fileName, baselineText, modifiedText) {{
  const baseLines = (baselineText || '').split('\\n');
  const modLines = (modifiedText || '').split('\\n');
  const diff = [];

  let hasDiff = false;
  let i = 0, j = 0;
  while (i < baseLines.length || j < modLines.length) {{
    const b = baseLines[i];
    const m = modLines[j];
    if (b === m) {{
      diff.push(`  ${{escapeHtml(b || '')}}`);
      i++; j++;
    }} else {{
      hasDiff = true;
      if (b !== undefined && (m === undefined || !modLines.slice(j).includes(b))) {{
        diff.push(`<span class="diff-del">- ${{escapeHtml(b)}}</span>`);
        i++;
      }} else if (m !== undefined && (b === undefined || !baseLines.slice(i).includes(m))) {{
        diff.push(`<span class="diff-add">+ ${{escapeHtml(m)}}</span>`);
        j++;
      }} else {{
        diff.push(`<span class="diff-del">- ${{escapeHtml(b)}}</span>`);
        diff.push(`<span class="diff-add">+ ${{escapeHtml(m)}}</span>`);
        i++; j++;
      }}
    }}
  }}

  if (!hasDiff) {{
    return `# No uncommitted modifications staged for ${{fileName}}`;
  }}
  return `# Staged YAML diff for ${{fileName}}:\\n` + diff.join('\\n');
}}

function escapeHtml(str) {{
  if (!str) return '';
  return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}}

function computeCurrentRegionYaml(region) {{
  const worldName = region.world || '[0]';
  const shape = region.shape || 'CIRCLE';
  const unit = region.unit || 'c';
  let yml = `# --- RTP Region Configuration ---\\n`;
  yml += `# Documentation: plugins/RTP/docs/admin/configuration/REGIONS.md\\n\\n`;
  yml += `world: "${{worldName}}"\\n`;
  yml += `worldBorderOverride: false\\n\\n`;
  yml += `shape:\\n`;
  yml += `  name: "${{shape}}"\\n`;
  yml += `  mode: "ACCUMULATE"\\n`;
  if (shape === 'RECTANGLE' || shape === 'ELLIPSE') {{
    yml += `  radiusX: ${{region.radiusX || region.radius}}${{unit}}\\n`;
    yml += `  radiusZ: ${{region.radiusZ || region.radius}}${{unit}}\\n`;
  }} else {{
    yml += `  radius: ${{region.radius}}${{unit}}\\n`;
    yml += `  centerRadius: ${{region.centerRadius}}${{unit}}\\n`;
  }}
  yml += `  centerX: ${{region.centerX || 0}}\\n`;
  yml += `  centerZ: ${{region.centerZ || 0}}\\n`;
  yml += `  weight: 1.0\\n`;
  yml += `  uniquePlacements: "auto"\\n`;
  yml += `  expand: false\\n`;
  if (shape === 'POLYGON' && region.vertices && region.vertices.length > 0) {{
    yml += `  vertices:\\n`;
    for (const v of region.vertices) {{
      yml += `    - [${{v[0]}}, ${{v[1]}}]\\n`;
    }}
  }}
  yml += `\\nvert: "@config"\\n`;
  yml += `requirePermission: "@config"\\n`;
  yml += `override: "default"\\n`;
  yml += `price: 0.0\\n`;
  yml += `cacheCap: "@config"\\n`;
  yml += `backlogCacheCap: "@config"\\n`;
  yml += `activeChunkCap: "@config"\\n`;
  yml += `spatialResolution: "@config"\\n`;
  yml += `version: "1.1"\\n`;
  return yml;
}}

function updateStagingDiff() {{
  const diffEl = document.getElementById('staging-diff');
  const validStatusEl = document.getElementById('validation-status');
  const warnings = validateGeometry(regionState);

  let warnText = '';
  if (warnings.length > 0) {{
    warnText = warnings.map(w => `<span class="${{w.startsWith('Error') ? 'diff-del' : (w.startsWith('Notice') ? 'diff-add' : 'diff-warn')}}" style="display:block;margin-bottom:4px;font-weight:bold;">⚠ ${{w}}</span>`).join('') + '\\n';
  }}

  // Update validation badge box
  if (validStatusEl) {{
    const hasErr = warnings.some(w => w.startsWith('Error'));
    validStatusEl.innerHTML = `
      ${{hasErr ? '❌ <span class="diff-del">Non-self-intersecting: VIOLATION (ADR-034)</span>' : '✅ <span class="diff-add">Non-self-intersecting: VALID</span>'}}<br>
      ${{warnings.some(w => w.includes('collinear')) ? '⚠ <span class="diff-warn">Collinear vertices: DETECTED</span>' : '✅ <span class="diff-add">Collinear vertices: SIMPLIFIED</span>'}}<br>
      ${{warnings.some(w => w.includes('world border')) ? '❌ <span class="diff-del">Within world border: BOUND EXCEEDED</span>' : '✅ <span class="diff-add">Within world border: YES</span>'}}
    `;
  }}

  const regionFileName = (regionState.name === 'default') ? 'definitions/regions/default.yml' : `definitions/regions/${{regionState.name}}.yml`;
  const baseline = defaultConfigs[regionFileName] || defaultConfigs['definitions/regions/default.yml'] || '';
  const modified = computeCurrentRegionYaml(regionState);

  // Cache in configs map
  configs[regionFileName] = modified;

  const diffText = generateYamlDiff(regionFileName, baseline.trim(), modified.trim());
  if (diffEl) diffEl.innerHTML = warnText + diffText;
}}

let currentSessionToken = (Math.random().toString(16).substring(2, 10) + Math.random().toString(16).substring(2, 10)).substring(0, 8);
let wsClient = null;

function commitChanges() {{
  const token = currentSessionToken;
  document.getElementById('apply-cmd').textContent = `/rtp editor apply ${{token}}`;
  document.getElementById('token-label').textContent = token;
  const overlay = document.getElementById('modal-overlay');
  if (overlay) overlay.style.display = 'flex';
}}

function hotApplyWebSocket() {{
  const wsStatus = document.getElementById('ws-apply-status');
  if (!wsClient || wsClient.readyState !== WebSocket.OPEN) {{
    if (wsStatus) {{
      wsStatus.style.display = 'block';
      wsStatus.style.color = 'var(--red)';
      wsStatus.textContent = '❌ WebSocket is not connected. Use command fallback below.';
    }}
    return;
  }}

  const regionTargetFile = (regionState.name === 'default') ? 'definitions/regions/default.yml' : `definitions/regions/${{regionState.name}}.yml`;
  const payload = {{
    action: 'apply',
    token: currentSessionToken,
    region: regionState.name,
    timestamp: Date.now(),
    files: {{
      [regionTargetFile]: computeCurrentRegionYaml(regionState)
    }}
  }};

  try {{
    wsClient.send(JSON.stringify(payload));
    if (wsStatus) {{
      wsStatus.style.display = 'block';
      wsStatus.style.color = 'var(--green)';
      wsStatus.textContent = '⚡ Commit transmitted to server! Awaiting confirmation...';
    }}
  }} catch (err) {{
    if (wsStatus) {{
      wsStatus.style.display = 'block';
      wsStatus.style.color = 'var(--red)';
      wsStatus.textContent = '❌ Send error: ' + err.message;
    }}
  }}
}}

function copyApplyCmd() {{
  const cmd = document.getElementById('apply-cmd').textContent;
  const copyBtn = document.getElementById('btn-copy-cmd');
  navigator.clipboard.writeText(cmd).then(() => {{
    if (copyBtn) {{
      const orig = copyBtn.textContent;
      copyBtn.textContent = 'Copied! ✓';
      copyBtn.style.background = 'var(--green)';
      copyBtn.style.color = 'var(--dark)';
      setTimeout(() => {{
        copyBtn.textContent = orig;
        copyBtn.style.background = '';
        copyBtn.style.color = '';
      }}, 2500);
    }}
  }}).catch(() => {{
    alert('Copied: ' + cmd);
  }});
}}

function resetStaging() {{
  regionState.radius = 256;
  regionState.centerRadius = 64;
  regionState.unit = 'c';
  regionState.shape = 'CIRCLE';
  regionState.vertices = [];
  document.getElementById('reg-radius').value = 256;
  document.getElementById('reg-center-radius').value = 64;
  const unitEl = document.getElementById('reg-unit');
  if (unitEl) unitEl.value = 'c';
  document.getElementById('reg-shape').value = 'CIRCLE';
  updateUnitHints();
  drawMap();
  updateStagingDiff();
}}

function exportPng() {{
  const canvas = document.getElementById('map-canvas');
  if (!canvas) return;
  const offCanvas = document.createElement('canvas');
  offCanvas.width = 3840;
  offCanvas.height = 2160;
  const octx = offCanvas.getContext('2d');
  octx.fillStyle = '#1e1e2e';
  octx.fillRect(0, 0, 3840, 2160);
  octx.drawImage(canvas, 0, 0, 3840, 2160);
  const link = document.createElement('a');
  link.download = `region_${{regionState.name}}_4k.png`;
  link.href = offCanvas.toDataURL('image/png');
  link.click();
}}

let hazardRuns = [];
let hazardBounds = {{ minX: -5000, minZ: -5000, maxX: 5000, maxZ: 5000, order: 7, gridSize: 128 }};

function decodeHilbertRle(base64Str) {{
  try {{
    const bin = atob(base64Str);
    let pos = 0;
    hazardBounds.order = bin.charCodeAt(pos++);
    hazardBounds.gridSize = 1 << hazardBounds.order;
    function readInt32() {{
      const v = (bin.charCodeAt(pos) << 24) | (bin.charCodeAt(pos+1) << 16) | (bin.charCodeAt(pos+2) << 8) | bin.charCodeAt(pos+3);
      pos += 4; return v;
    }}
    hazardBounds.minX = readInt32();
    hazardBounds.minZ = readInt32();
    hazardBounds.maxX = readInt32();
    hazardBounds.maxZ = readInt32();
    function readVarInt() {{
      let val = 0, shift = 0;
      while (pos < bin.length) {{
        const b = bin.charCodeAt(pos++);
        val |= (b & 0x7F) << shift;
        if ((b & 0x80) === 0) break;
        shift += 7;
      }}
      return val;
    }}
    const numRuns = readVarInt();
    const runs = [];
    let curStart = 0;
    for (let i = 0; i < numRuns; i++) {{
      const delta = readVarInt(); curStart += delta;
      const len = readVarInt();
      const cause = bin.charCodeAt(pos++);
      runs.push({{ start: curStart, len: len, cause: cause }});
    }}
    return runs;
  }} catch (e) {{ return []; }}
}}

function hilbertToXy(d, order) {{
  return hilbertToXY(d, 1 << order, 0);
}}

function derivePointEdgeChunks(radiusChunks) {{
  if (radiusChunks <= 0) return 1;
  const maxAllowedP = Math.floor((2 * radiusChunks) / 64);
  if (maxAllowedP < 1) return 1;
  const candidates = [1, 2, 4, 8, 16, 32];
  let chosen = 1;
  for (const c of candidates) {{
    if (c <= maxAllowedP && c <= 32) chosen = c;
  }}
  return chosen;
}}

function orientationFor(px, pz) {{
  const kX = (px >= 0) ? (px + 1) : -px;
  const kZ = (pz >= 0) ? (pz + 1) : -pz;
  const K = Math.max(kX, kZ);
  if (px === K - 1 && pz > -K) return 1;
  if (pz === K - 1 && px < K - 1) return (px === -K) ? 5 : 4;
  if (px === -K && pz < K - 1) return 5;
  return 0;
}}

function unapplyOrientation(x, y, n, o) {{
  const max = n - 1;
  switch (o % 8) {{
    case 0: return [x, y];
    case 1: return [y, x];
    case 2: return [y, max - x];
    case 3: return [max - x, y];
    case 4: return [max - x, max - y];
    case 5: return [max - y, max - x];
    case 6: return [max - y, x];
    case 7: return [x, max - y];
    default: return [x, y];
  }}
}}

function hilbertToXY(d, n, orientation = 0) {{
  let rx, ry, t = d;
  let x = 0, y = 0;
  for (let s = 1; s < n; s *= 2) {{
    rx = 1 & Math.floor(t / 2);
    ry = 1 & (t ^ rx);
    if (ry === 0) {{
      if (rx === 1) {{
        x = s - 1 - x;
        y = s - 1 - y;
      }}
      const tmp = x; x = y; y = tmp;
    }}
    x += s * rx;
    y += s * ry;
    t = Math.floor(t / 4);
  }}
  if (orientation !== 0) return unapplyOrientation(x, y, n, orientation);
  return [x, y];
}}

// Diagnostics Radar State & Dynamic Updaters
let currentMetrics = {{
  tps1m: 20.00, tps5m: 20.00, tps15m: 20.00,
  msptMean: 12.4, msptMax: 21.8,
  budgetUtil: 24.8,
  players: 42, playerCap: 150,
  heapUsedGb: 1.82, heapTotalGb: 4.00,
  platform: 'Paper / Folia Async',
  l1Kept: 16, l1Cap: 16,
  l2Cold: 64, l2Cap: 64,
  l3Backlog: 10000,
  loginReserve: 8, loginReserveCap: 8,
  queueDepth: 0,
  pendingTeleports: 0,
  latMean: 14.2, latP50: 9.5, latP75: 14.0, latP90: 22.5, latP95: 31.0, latP99: 48.2,
  latMin: 2.1, latMax: 76.4, latSamples: 1024, latTotal: 18490,
  stages: {{ shape: 18.4, chunk: 8.6, vert: 64.2, biome: 12.1, safety: 32.8, total: 8.73 }},
  rejections: {{ accepted: 1024, acceptedPct: 92.4, water: 48, lava: 12, claims: 8, border: 0, suffocation: 16 }},
  regions: [
    {{ name: 'default', world: '[0]', queue: 0, l1Kept: 16, l1Cap: 16, l2Cold: 64, l2Cap: 64, loginKept: 8, loginCap: 8, status: 'OPTIMAL' }},
    {{ name: 'nether_wastes', world: 'world_nether', queue: 0, l1Kept: 8, l1Cap: 8, l2Cold: 32, l2Cap: 32, loginKept: 0, loginCap: 0, status: 'OPTIMAL' }},
    {{ name: 'the_end', world: 'world_the_end', queue: 0, l1Kept: 8, l1Cap: 8, l2Cold: 32, l2Cap: 32, loginKept: 0, loginCap: 0, status: 'OPTIMAL' }}
  ],
  logs: [
    '[MemoryTracker Watchdog] 0 active chunk ticket leaks detected across all worlds.',
    '[Scheduler Heartbeat] Folia / Async worker pool operating within nominal bounds (0ms drift).',
    '[Off-Tick I/O Pool] Anvil / Linear region readers active: 2 threads (bounded off-tick).',
    '[RegionQueueManager] L1 Hot Queue pre-warmed and ready for instant dispatch.',
    '[ClaimIntegrations] 0 inline chunk stalls; cache hit ratio: 99.8%.'
  ]
}};

function updateDiagnosticsUI(metrics) {{
  if (!metrics) return;
  // Host
  setTxt('metric-tps', `${{metrics.tps1m.toFixed(2)}} / ${{metrics.tps5m.toFixed(2)}} / ${{metrics.tps15m.toFixed(2)}}`);
  setTxt('metric-mspt', `${{metrics.msptMean.toFixed(1)}} ms / ${{metrics.msptMax.toFixed(1)}} ms`);
  const msptBar = document.getElementById('metric-mspt-bar');
  if (msptBar) msptBar.style.width = Math.min(100, (metrics.msptMean / 50.0) * 100) + '%';
  setTxt('metric-budget', `${{metrics.budgetUtil.toFixed(1)}}%`);
  setTxt('metric-players', `${{metrics.players}} / ${{metrics.playerCap}} soft cap`);
  setTxt('metric-heap', `${{metrics.heapUsedGb.toFixed(2)}} GB / ${{metrics.heapTotalGb.toFixed(2)}} GB (${{Math.round((metrics.heapUsedGb/metrics.heapTotalGb)*100)}}%)`);
  const heapBar = document.getElementById('metric-heap-bar');
  if (heapBar) heapBar.style.width = Math.min(100, (metrics.heapUsedGb / metrics.heapTotalGb) * 100) + '%';
  setTxt('metric-platform', metrics.platform || 'Paper / Folia Async');

  // Queues
  setTxt('metric-l1', `${{metrics.l1Kept}} / ${{metrics.l1Cap}}`);
  setTxt('metric-l2', `${{metrics.l2Cold}} / ${{metrics.l2Cap}}`);
  setTxt('metric-l3', metrics.l3Backlog.toLocaleString());
  setTxt('metric-login-reserve', `${{metrics.loginReserve}} / ${{metrics.loginReserveCap}}`);
  setTxt('metric-queue-depth', metrics.queueDepth);
  setTxt('metric-pending-teleports', metrics.pendingTeleports);

  // Latencies
  setTxt('metric-lat-mean', `${{metrics.latMean.toFixed(1)}} ms`);
  setTxt('metric-lat-p50', `${{metrics.latP50.toFixed(1)}} ms`);
  setTxt('metric-lat-p75', `${{metrics.latP75.toFixed(1)}} ms`);
  setTxt('metric-lat-p90', `${{metrics.latP90.toFixed(1)}} ms`);
  setTxt('metric-lat-p95', `${{metrics.latP95.toFixed(1)}} ms`);
  setTxt('metric-lat-p99', `${{metrics.latP99.toFixed(1)}} ms`);
  setTxt('metric-lat-minmax', `${{metrics.latMin.toFixed(1)}} ms / ${{metrics.latMax.toFixed(1)}} ms`);
  setTxt('metric-lat-samples', `${{metrics.latSamples.toLocaleString()}} / ${{metrics.latTotal.toLocaleString()}} total`);

  // Stages
  if (metrics.stages) {{
    setTxt('metric-stage-shape', `${{metrics.stages.shape.toFixed(1)}} µs`);
    setTxt('metric-stage-chunk', `${{metrics.stages.chunk.toFixed(1)}} ms`);
    setTxt('metric-stage-vert', `${{metrics.stages.vert.toFixed(1)}} µs`);
    setTxt('metric-stage-biome', `${{metrics.stages.biome.toFixed(1)}} µs`);
    setTxt('metric-stage-safety', `${{metrics.stages.safety.toFixed(1)}} µs`);
    setTxt('metric-stage-total', `${{metrics.stages.total.toFixed(2)}} ms`);
  }}

  // Rejections
  if (metrics.rejections) {{
    setTxt('metric-rej-accepted', `${{metrics.rejections.accepted}} (${{metrics.rejections.acceptedPct.toFixed(1)}}%)`);
    setTxt('metric-rej-water', metrics.rejections.water);
    setTxt('metric-rej-lava', metrics.rejections.lava);
    setTxt('metric-rej-claims', metrics.rejections.claims);
    setTxt('metric-rej-border', metrics.rejections.border);
    setTxt('metric-rej-suffocation', metrics.rejections.suffocation);
  }}

  // Regions Table
  if (Array.isArray(metrics.regions)) {{
    const tbody = document.getElementById('region-metrics-tbody');
    if (tbody) {{
      tbody.innerHTML = metrics.regions.map(r => {{
        const l1Pct = r.l1Cap > 0 ? Math.round((r.l1Kept / r.l1Cap) * 100) : 0;
        const l2Pct = r.l2Cap > 0 ? Math.round((r.l2Cold / r.l2Cap) * 100) : 0;
        const badgeCls = (r.status === 'OPTIMAL' || r.status === 'OK') ? 'badge-ok' : (r.status === 'SATURATED' ? 'badge-warn' : 'badge-err');
        return `<tr>
          <td><b style="color:var(--accent);">${{escapeHtml(r.name)}}</b></td>
          <td>${{escapeHtml(r.world || 'world')}}</td>
          <td>${{r.queue || 0}}</td>
          <td><span style="color:var(--green);">${{r.l1Kept}} / ${{r.l1Cap}} (${{l1Pct}}%)</span></td>
          <td><span style="color:var(--accent);">${{r.l2Cold}} / ${{r.l2Cap}} (${{l2Pct}}%)</span></td>
          <td><span style="color:var(--mauve);">${{r.loginKept || 0}} / ${{r.loginCap || 0}}</span></td>
          <td><span class="telemetry-badge ${{badgeCls}}">${{escapeHtml(r.status || 'OK')}}</span></td>
        </tr>`;
      }}).join('');
    }}
  }}

  // Logs
  if (Array.isArray(metrics.logs)) {{
    const logEl = document.getElementById('telemetry-watchdog-log');
    if (logEl) logEl.textContent = metrics.logs.join('\\n');
  }}

  const updEl = document.getElementById('last-telemetry-update');
  if (updEl) updEl.textContent = 'Updated ' + new Date().toLocaleTimeString();
}}

function setTxt(id, val) {{
  const el = document.getElementById(id);
  if (el) el.textContent = val;
}}

function initWebSocket() {{
  const badge = document.getElementById('conn-badge');
  const host = window.location.host;
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  if (!host) {{
    if (badge) {{
      badge.textContent = '● STANDALONE FILE';
      badge.style.color = 'var(--subtext)';
    }}
    return;
  }}
  try {{
    wsClient = new WebSocket(`${{protocol}}//${{host}}/rtp-editor-ws`);
    wsClient.onopen = () => {{
      if (badge) {{
        badge.textContent = '● CONNECTED';
        badge.style.color = 'var(--green)';
        badge.style.borderColor = 'rgba(166, 227, 161, 0.4)';
      }}
    }};
    wsClient.onmessage = (event) => {{
      try {{
        const msg = JSON.parse(event.data);
        if (msg.type === 'scan_delta' && msg.rleBase64) {{
          hazardRuns = decodeHilbertRle(msg.rleBase64);
          drawMap();
        }} else if (msg.type === 'mutation_broadcast' || msg.type === 'config_update') {{
          if (msg.files) {{
            Object.assign(configs, msg.files);
            if (cfgEditor && configs[currentConfigFile]) {{
              cfgEditor.value = configs[currentConfigFile];
            }}
            updateStagingDiff();
          }}
        }} else if (msg.type === 'telemetry' || msg.type === 'metrics') {{
          if (msg.metrics) {{
            Object.assign(currentMetrics, msg.metrics);
            updateDiagnosticsUI(currentMetrics);
          }}
        }} else if (msg.type === 'apply_ack') {{
          const wsStatus = document.getElementById('ws-apply-status');
          if (wsStatus) {{
            wsStatus.style.display = 'block';
            wsStatus.style.color = msg.success ? 'var(--green)' : 'var(--red)';
            wsStatus.textContent = msg.success ? '✔ Hot-Apply successfully committed to server!' : ('❌ Error: ' + msg.error);
          }}
        }} else if (msg.type === 'reload') {{
          location.reload();
        }}
      }} catch (e) {{}}
    }};
    wsClient.onclose = () => {{
      if (badge) {{
        badge.textContent = '● DISCONNECTED';
        badge.style.color = 'var(--red)';
        badge.style.borderColor = 'rgba(243, 139, 168, 0.4)';
      }}
      setTimeout(initWebSocket, 5000);
    }};
    wsClient.onerror = () => {{
      if (badge) {{
        badge.textContent = '● STANDALONE';
        badge.style.color = 'var(--yellow)';
      }}
    }};
  }} catch (e) {{
    if (badge) {{
      badge.textContent = '● STANDALONE';
      badge.style.color = 'var(--yellow)';
    }}
  }}
}}

populateConfigSidebar();
selectConfig(currentConfigFile);
populateDocSidebar();
selectDoc(currentDocFile);
syncRegionProfiles();
changeRegionProfile('default');
updateDiagnosticsUI(currentMetrics);
setTimeout(resizeCanvas, 60);
initWebSocket();
</script>
</body>
</html>
"""
    return html


def main():
    print("Generating ADR-104 Web Editor application from repository sources...")
    html = generate_html()

    target_docs = DOCS_DIR / "editor" / "index.html"
    target_docs.parent.mkdir(parents=True, exist_ok=True)
    target_docs.write_text(html, encoding="utf-8")
    print(f"Generated: {target_docs} ({len(html):,} bytes)")

    print("Success: Web editor HTML generated from code and source configs.")


if __name__ == "__main__":
    main()
