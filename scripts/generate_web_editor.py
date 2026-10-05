#!/usr/bin/env python3
"""Generates the ADR-104 Web Editor's plugin-side data file from repository sources.

Reads:
- Shipped baseline configurations from `rtp-plugin/src/main/resources/`
- Configuration reference tables from `docs/admin/configuration/*.md`
- The hand-maintained search thesaurus `docs/editor/search-synonyms.json` (concept -> config keys)

Writes `docs/editor/editor-data.json` ({shipped, docTracker, synonyms}), which rtp-core packs next to the
editor template; the plugin sends it in every session payload. `docs/editor/index.html` carries no
bundled data and is never touched. `--check` exits 1 when the data file is stale.
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
- **`POLYGON`**: Arbitrary closed polygon boundary defined by `vertices` as Chunky-style `- [x, z]` pairs (e.g. `- [-125c, 187c]`; unitless = chunks).
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
            "meta": "Type: List of [X, Z] pairs (unit suffix per coordinate, unitless = chunks) | Min: 3 vertices | Docs: REGIONS.md",
            "defaultVal": "[[-2000b, 3000b], [2000b, 3000b], [2000b, -3000b], [-2000b, -3000b]]",
            "defaultSnippet": """vertices:
  - [-2000b, 3000b]
  - [2000b, 3000b]
  - [2000b, -3000b]
  - [-2000b, -3000b]""",
            "markdown": """Ordered boundary coordinate pairs defining an arbitrary closed polygon (when `shape: POLYGON`).

### Format
Uses the same bracketed pair syntax as Chunky `/chunky shape polygon`, one `[x, z]` pair per list item:
```yaml
vertices:
  - [-125c, 187c]
  - [2000b, 3000b]
  - [10, -4]
```
The inline form `vertices: [[-125c, 187c], [2000b, 3000b], [10, -4]]` is equivalent.

### Units
- Each coordinate takes a spatial suffix: `b` blocks, `c` chunks, `r` regions, `m`, `km`, ...
- A coordinate **without a suffix is in chunks** (`[10, -4]` = `[10c, -4c]`). Chunky block coordinates need the `b` suffix (`3000b` = 187.5 chunks, rounded to `188c`).

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


EDITOR_DATA = DOCS_DIR / "editor" / "editor-data.json"
SEARCH_SYNONYMS = DOCS_DIR / "editor" / "search-synonyms.json"


def load_synonyms(path: Path = SEARCH_SYNONYMS) -> dict[str, list[str]]:
    """Search thesaurus {concept: [config keys]}; lower-case concepts, string lists only."""
    if not path.is_file():
        return {}
    raw = json.loads(path.read_bytes().decode("utf-8"))
    if not isinstance(raw, dict):
        raise ValueError(f"{path} must be a JSON object of concept -> [config keys]")
    out: dict[str, list[str]] = {}
    for concept, keys in raw.items():
        if not isinstance(keys, list) or not all(isinstance(k, str) for k in keys):
            raise ValueError(f"{path}: '{concept}' must map to a list of strings")
        out[concept.lower()] = keys
    return out


def generate_data() -> str:
    """Returns editor-data.json: {shipped: {file: text}, docTracker: {key: entry}, synonyms: {concept: [keys]}}."""
    configs, doc_tracker = load_configs()
    data = {"shipped": configs, "docTracker": doc_tracker, "synonyms": load_synonyms()}
    return json.dumps(data, indent=1, ensure_ascii=False) + "\n"


def read_data(path: Path = EDITOR_DATA) -> str:
    # Bytes round-trip: text-mode IO would rewrite LF as CRLF on Windows.
    return path.read_bytes().decode("utf-8") if path.is_file() else ""


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    current = read_data()
    data = generate_data()

    if "--check" in args:
        if data != current:
            print(f"Stale: {EDITOR_DATA} differs from repository sources; run without --check.")
            return 1
        print(f"Up to date: {EDITOR_DATA}")
        return 0

    if data == current:
        print(f"Up to date: {EDITOR_DATA}")
        return 0
    EDITOR_DATA.write_bytes(data.encode("utf-8"))
    print(f"Wrote {EDITOR_DATA} ({len(data):,} chars)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
