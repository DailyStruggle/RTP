# ADR-104 — Ephemeral Web Editor and In-Game Packed Docs Integration

**Status:** Accepted
**Date:** 2026-10-03
**Authors:** RTP Core Team
**Extends:** [ADR-045](ADR-045-rtp-docs-menu-consumer.md) (`/rtp docs` Menu Consumer), [ADR-064](ADR-064-config-node-in-game-hover-doc-sync.md) (Config In-Game Hover Sync), [ADR-086](ADR-086-external-web-map-integration.md) (External Web Map Integration), [ADR-099](ADR-099-visual-region-creation-and-interactive-cartography-bridges.md) (Visual Region Creation and Cartography Models)
**Related REQs:** S-004, S-005, S-006, S-007, REQ-RTP-F-013
**Extended by:** [ADR-106](ADR-106-shape-curve-helpers-and-signed-editor-channel.md) (Shape Curve Helpers, Curve-Space Run Layers, and the Signed Two-Way Editor Channel)

---

## 1. Context and Problem Statement

Server operators and addon developers face several discoverability and usability barriers:
1. **The In-Game Documentation Vacuum:** Operators have tactile in-game levers via `/rtp admin`, but no interactive way to read bundled operator manuals without alt-tabbing to external browsers or reading raw YAML.
2. **The Simultaneous View & Edit Dilemma:** Opening an in-game inventory or book menu locks the client's screen. An operator cannot read an in-game manual while simultaneously adjusting values in the in-game config editor, or they are forced to juggle commands blindly.
3. **Remote Hosting Constraints:** Most servers run on third-party hosts or remote friend machines where inbound ports (for embedded HTTP daemons) are blocked or require complex port-forwarding. Furthermore, embedded HTTP listeners introduce severe network attack surface, CSRF, and authentication vulnerabilities.
4. **Drift Between Shipped Plugin & Web Docs:** Online documentation on GitHub or the project website reflects the latest trunk commit, which may differ from the specific release version deployed on the server.

---

## 2. Decision Drivers

- **Zero Inbound Attack Surface:** No embedded HTTP daemons and no externally reachable listening sockets inside the server JVM. The only listener is the local-export loopback channel (section 4.6): bound to the loopback interface, token-gated, Origin- and Host-checked, and alive only as long as its live feed.
- **Zero-Typing Philosophy:** Operators should not be forced into tedious command typing; interactions should be click-driven in-game and visual in the browser.
- **Offline / Air-Gapped Autonomy:** Packed documentation bundled inside the plugin jar must remain 100% accessible in-game even without internet connectivity.
- **Simultaneous Read & Edit:** Allow operators on remote servers to view full documentation side-by-side with an interactive configuration editor.
- **Strict Concurrency Safety (S-005):** Zero main-thread blocking, zero synchronous network I/O, zero synchronous disk I/O.

---

## 3. Considered Options

- **Option 1: Embedded HTTP Server (Self-Hosted Web Panel).**
  *Rejected:* Requires port forwarding, blocked by shared hosts, introduces security vulnerabilities and memory bloat.
- **Option 2: Chat-Based Manuals & Raw Command Hints.**
  *Rejected:* Cluttered chat logs, poor formatting, operators dislike typing long commands.
- **Option 3: Hybrid Architecture — In-Game Packed Books (ADR-045) + Outbound Ephemeral Web Editor (ADR-104).**
  *Accepted:* Modeled after spark and LuckPerms. Fully offline in-game book lowering via `rtp-core` combined with an outbound ephemeral web editor session hosted on the official static documentation site.

---

## 4. Architectural Design

```
+-----------------------------------------------------------------------------------------------------------------+
| PRIMARY ENVIRONMENT: Minecraft Client (Screen 1)                                                                |
|                                                                                                                 |
|  +--------------------+       Click [📖 Manuals]       +-----------------------+                                |
|  |     /rtp admin     | -----------------------------> |   /rtp docs (Book)    |                                |
|  | (AdminPanelBuilder)|                                | (Packed Markdown)     |                                |
|  +--------------------+                                +-----------------------+                                |
|            |                                                                                                    |
|            | Click [🌐 Web Editor]                                                                              |
|            v                                                                                                    |
|  +-----------------------------------------------------------------------------+                                |
|  | Chat Link: https://dailystruggle.github.io/RTP/editor#<session-token>       |                                |
|  +-----------------------------------------------------------------------------+                                |
+---------------------------------------------------|-------------------------------------------------------------+
                                                    | Opens on Adjacent Screen (Split Screen: Game + Web)
                                                    v
+-----------------------------------------------------------------------------------------------------------------+
| SECONDARY ENVIRONMENT: Dedicated Web Panel (Screen 2 / Browser)                                                 |
|                                                                                                                 |
|  +----------------------------------------------------+------------------------------------------------------+  |
|  | PRIMARY WORKSPACE: INTERACTIVE CARTOGRAPHY & MAP   | SIDEBAR: CONFIG STAGING DIFF & INSPECTOR             |  |
|  | - 2D Vector / Raster Cartography (ADR-086, ADR-099)| - Live YAML staging diff (delta review before apply) |  |
|  | - Visual Polygon vertex dragging & simplification  | - Instant mathematical & schema validation           |  |
|  | - Donut (centerRadius) & Box boundary gizmos       | - Contextual packed docs hover & schema hints        |  |
|  +----------------------------------------------------+------------------------------------------------------+  |
|                           ▲                                                          │                          |
|   Live Server Changes     │ (Bi-directional WebSocket/Long-poll sync)                │ Click [Hot-Apply]        |
|   Back-Propagate to Web   │                                                          v                          |
|  +-----------------------------------------------------------------------------------------------------------+  |
|  | Outbound Sync: Immediate WebSocket Commit OR Copy Fallback: /rtp editor apply <token>                     |  |
|  +-----------------------------------------------------------------------------------------------------------+  |
+---------------------------------------------------|-------------------------------------------------------------+
                                                    | Apply Trigger (WebSocket or Paste Command)
                                                    v
+-----------------------------------------------------------------------------------------------------------------+
| SERVER VERIFICATION & COMMIT PIPELINE                                                                           |
|                                                                                                                 |
|  1. Async fetch/receive session payload via outbound HTTPS/WebSocket from byte store                            |
|  2. Validate payload SHA-256 integrity and authorization token                                                  |
|  3. Validate AST against Configuration schema & geometry rules (ADR-034 non-self-intersection)                  |
|  4. Off-thread disk backup of affected files                                                                    |
|  5. Atomic in-memory config swap + disk flush on async worker                                                   |
|  6. Notify operator with exact diff summary (S-004) and broadcast mutation to active web sessions (sync)        |
+-----------------------------------------------------------------------------------------------------------------+
```

### 4.1 In-Game Packed Documentation Lowering (ADR-045 Execution)
- Markdown files packed in `docs/admin/` and `docs/*.md` are read as resources on plugin startup asynchronously.
- `MarkdownToMenuModel` parses headers (`#`, `##`), bullet lists, inline codes, and links into structured `MenuModel` book pages.
- Headless, zero-dependency parser adhering to Parchment-safe dark contrast standards (Rule ADR-045).
- Each page features an interactive link button (`MenuAction.OpenExternalUrl`) targeting the matching online MkDocs anchor.

### 4.2 Ephemeral Web Editor Session Lifecycle
1. **Creation (`/rtp editor` or `/rtp admin` click):**
   - Collects active config objects (`config.yml`, `regions/*.yml`, `messages.yml`).
   - Collects schema field descriptions, packed documentation index, and active region geometric definitions (ADR-099).
   - Generates a cryptographically random session token (128-bit hex).
   - Bundles payload into gzip-compressed JSON.
   - Dispatches outbound HTTPS `POST` to configured byte store (e.g., `bytebin.lucko.me` or official RTP byte service).
   - Emits clickable chat component to operator with the direct editor URL.
2. **Editing & Web Panel Workspace (Separation of Concerns):**
   - Static client-side app at `https://dailystruggle.github.io/RTP/editor` decrypts/reads payload.
   - **Primary Workspace (Interactive Cartography & Region Editor):**
     - Renders an interactive 2D vector canvas displaying world coordinate grids, world borders, and existing region boundaries (ADR-086, ADR-099).
     - Provides interactive handles for dragging polygon vertices, resizing donut radii (`radius`, `centerRadius`), and drawing bounding boxes.
     - Live vertex simplification (ADR-099) automatically prunes redundant collinear vertices off-tick.
   - **Sidebar (Configuration Staging Diff & Validation Inspector):**
     - Rather than raw text boxes, displays a live staging diff of proposed YAML mutations across all modified region and global files.
     - Performs real-time geometric validation (ADR-034 non-self-intersection checks) and warns if shapes breach world borders or overlap restricted regions.
     - Provides contextual hover cards linked to version-matched packed documentation and schema comments (ADR-064).
   - **Context-Tracking Documentation Drawer in Config Editor:**
     - The configuration workspace features a dynamic documentation side-panel that actively tracks whatever configuration key, field, or text cursor position is focused.
     - **Rich Markdown-to-HTML Sourcing:** Rather than echoing redundant inline YAML comments that are already visible onscreen in the code editor, the documentation drawer compiles and renders rich HTML directly from the authoritative Markdown reference manuals (`docs/admin/configuration/*.md`, etc.).
     - Selecting a field immediately displays structured HTML including parameter breakdowns, valid ranges/types, units of measurement, defaults, operator advice, gotchas, visual examples, and deep links to related ADRs and guides.
     - **Copy/Paste Defaults & Materialization:**
       - Provides a one-click **"Copy Default"** action for any focused key or block to instantly copy its canonical YAML snippet to the clipboard or insert it into the active editor buffer.
       - Features an inline **"Turn into Local Configuration" / "Materialize `@config`"** action: when editing a region or world configuration file that references global defaults via inheritance tokens (e.g. `shape: "@config"`, `vert: "@config"`, `price: "@economy"` per ADR-073), clicking the button unpacks and inlines the exact default section/scalar into the local file, allowing immediate custom editing without manually looking up or drafting the schema.
     - Eliminates manual search friction and screen space underutilization in wide-screen browser panels by providing thorough, website-quality documentation side-by-side with the editor.
   - **Inline Syntax, Schema Diagnostics & Code Editor Ergonomics:**
     - The configuration code editor provides a line-number gutter kept aligned with the editor (including its horizontal scrollbar) and client-side schema diagnostics debounced to 150 ms after typing stops, without external npm or CDN dependencies (preserving single-file air-gapped `file:///` compliance).
     - **Schema Validation & Gutter Diagnostics:** The active YAML buffer is checked against:
       - the server's registered shapes and vertical adjustors with typed parameters (session payload `schema`: `{shape|vert: {NAME: {key: {type, default, options}}}}`, the type taken from each implementation's default value as `integer | number | boolean | enum | list | string`; includes add-on and Chunky shapes; built-in names until a session loads), validated by block (`shape.name` vs `vert.name`) and for any key annotated `@source: shape|vert`. Parameter types and enum options drive the value checks inside shape / vert blocks; comment annotations override them;
       - the shipped files' `@type` / `@options` / `@range` annotations (enums, booleans, numeric ranges);
       - duplicate keys at any depth, unknown keys inside shape / vert blocks and at the top level of single-instance files, and unrecognised unit suffixes. A `POLYGON` block warns on `radius`, `centerRadius`, `centerX` and `centerZ`, which a polygon infers from its vertices.
     - Errors and warnings are marked in the gutter (escaped hover text, every message per line), listed in the doc tracker, and summarised for every staged file in the staging diff. Committing with schema errors requires explicit confirmation.
     - **Unit Slider & Conversion Chips:** When the cursor is on a spatial value (`b`, `c`, `r`, `km`, `mi`, e.g. `256c`, `5r`), an in-editor chip and the doc tracker show the block conversion (e.g. $256\text{c} = 4{,}096\text{ blocks}$, $5\text{r} = 2{,}560\text{ blocks}$). A slider rewrites the value in its unit, and converter badges rewrite it in another unit where the conversion is exact. Duration suffixes (`s`, `m`, `h`, `d`, `t`, `ms`) are not treated as distances.
   - **Visual Polygon Edge Constraints & Snapping Controls:**
     - The Visual Region Editor canvas provides snap-to-grid controls (Free 1-block, Chunk $16 \times 16$, and Region File $512 \times 512$). With a polygon active, the grid is drawn anchored at world $(0, 0)$.
     - Polygon vertices are absolute world block coordinates, drawn in the same frame as the region-file land layer. During vertex dragging they snap to the active grid, so polygon edges align with Anvil/Linear region-file and chunk boundaries without manual coordinate entry. The pointer readout shows world coordinates.
     - A polygon's bounding square (radius and centre) is derived from its vertices and never stored or written to YAML.
     - Canvas geometry is written into the region file's existing `shape:` block, and only after the canvas geometry changes: `name` and the geometry keys the shape takes (registry parameters; `radius` / `centerRadius` for radial shapes, `radius` / `radius2` semi-axes for `ELLIPSE`, `width` / `height` full extents for `RECTANGLE`, `vertices` for `POLYGON`) are set in place. Geometry keys the shape does not take are removed, and every other key and comment is kept. An inherited `shape: "@config"` becomes a local block only after a canvas edit. Geometry typed in the config editor becomes the canvas model, so staging never writes stale values back.
     - The view scale is fixed between fits: dragging vertices or editing the shape shall not rescale the map. Only Reset View and switching region refit and recentre on the region. Wheel zoom is proportional to scroll distance, and middle-button panning suppresses the browser's autoscroll.
   - **Bi-directional Focus & Location Synchronization:**
     - Provides bi-directional navigation between the Visual Region Editor and the YAML Config workspace:
     - `[Jump to YAML ↗]` writes the region's current (possibly unsaved) geometry into `definitions/regions/<name>.yml`, opens it in `#panel-configs`, and selects the selected vertex's `- [x, z]` entry, or the top-level `shape:` key when no vertex is selected (resolved through the YAML structure, never a text search).
     - Conversely, placing the cursor within a region definition (`definitions/regions/<name>.yml` or `regions/<name>.yml`) in `#panel-configs` surfaces a `[View on Map 🗺️]` action that switches to `#panel-regions` and loads that region through the normal region selector, centred and active. Other files show no map action.
     - The doc tracker resolves a key by its enclosing block (`shape.name` and `vert.name` show different docs).
3. **Bi-directional Synchronization & Back-Propagation:**
   - **Signed two-way channel ([ADR-106](ADR-106-shape-curve-helpers-and-signed-editor-channel.md) section 5):** `/rtp editor` opens a channel on a WebSocket relay (bytesocks, default `https://bytesocks.lucko.me`, configurable next to the bytebin URL), joins it outbound, and puts `channel: {relay, id, pluginKey}` in the snapshot. Every message is signed (`{msg, signature}`, SHA256withRSA). The plugin acts on page edits only from browser keys the operator has trusted in game (`/rtp editor trust <nonce>`). If the relay can't be created, the session is snapshot-only and the reason is logged (S-004).
   - **Outbound Web Commits:** With the channel connected and the browser key trusted, clicking **"Hot-Apply"** in the browser transmits the mutation directly (`apply` / `apply_ack`, bytebin hand-off above the 32 KiB frame cap), hot-reloading server regions through the same validation pipeline as `/rtp editor apply` without any manual command pasting.
   - **Command Fallback (`/rtp editor apply <token>`):** In offline, restricted, or disconnected environments, clicking "Save" generates the `/rtp editor apply <token>` command for copy-pasting into chat or console.
   - **In-Game Back-Propagation:** When mutations occur in-game (e.g., via `/rtp region set`, WorldEdit selection `/rtp region fromselection`, or in-game book GUI), the server broadcasts a delta to the active web session, updating the browser canvas and staging diff in real time to prevent stale-overwrite conflicts. Curve changes (`curve`, for example after `expand`), hazard run deltas (`hazard-delta`) and focus-driven land bins (`land`) travel over the same channel (ADR-106 section 5.3).
4. **Commit & Verification (`/rtp editor apply <token>` or Direct WebSocket Push):**
   - RTP worker thread receives payload, verifies token authorization, compares schema diffs, backs up dirty files, updates config instances, and triggers hot-reload.

### 4.3 Bandwidth-Efficient Progressive Rendering & Visualization Integration
To provide visual parity with in-game `/rtp visualization` commands (ADR-046, ADR-086, ADR-089) without saturating network bandwidth or JVM memory:
1. **Two-Tier Progressive Rendering:**
   - **Continuous Live Vector & Diff Stream (Tier 1):**
     - Geometry boundaries (polygons, donuts, boxes) render locally on the client's HTML5 vector canvas at $60\text{ FPS}$ with zero server traffic.
     - Spatial hazard topologies and biome indices from `MemoryShape` are streamed as compact Run-Length Encoded (RLE) Hilbert chunks ($< 5\text{ KB}$ per delta).
     - When scanning (`/rtp scan`) or pre-generating runs, the server broadcasts lightweight coordinate run deltas rather than re-rendering raster tiles.
   - **On-Demand High-Resolution Export (Tier 2):**
     - Operators can trigger high-resolution raster generation via the browser interface (e.g. $4096 \times 4096$ PNG/WebP exports matching `/rtp visualization export pipeline`).
     - Rendered asynchronously off-tick via `maps-api` and downloaded directly as an archival cartography artifact.
2. **Integrated Visualization Overlays:**
   - **Hazard & Rejection Overlay (`/rtp visualization bad-locations`):** Visualizes rejected candidate chunks (oceans, steep terrain, claim exclusions) to guide boundary placement.
   - **Pregenerated Land Map via Chunk-Resolution Biome Data (`/rtp visualization biomes`):**
     - Rather than shipping high-overhead block-level voxel data, the visual editor renders a map of actually generated land using **chunk-resolution biome data** read from region files on disk (S-005: no chunk loads). Land data exists to decide where a region goes, so it covers the whole world and shall never be clipped to a configured region's bounds. Export generation shall read no region file; the world-wide survey and its tiling are specified in section 4.6.
     - Biome data is encoded at native chunk granularity ($16 \times 16$ block column per sample), which reflects real generation bounds without wasting bandwidth on redundant block-level geography.
     - **Throttled Progressive Streaming (> 5 FPS Budget):** To prevent client-side UI freezes and ensure the interactive canvas and staging diff stay strictly above $5\text{ FPS}$ during heavy streaming across large worlds, biome chunk batches are sent incrementally ("a little at a time") over WebSocket / long-poll frames, budgeted against client frame rendering and browser event loop times.
     - Provides a lightweight 2D cartography backdrop resembling BlueMap / Dynmap explored-terrain boundaries or in-game biome maps, allowing operators to visually align RTP region radii, donut centers, and polygon vertices against pregenerated world borders.
   - **Landing Heatmap (`/rtp visualization heatmap`):** Successful teleport landings counted per chunk (`LandingHeatmap`, in memory, bounded per region) audit the real distribution. The export carries the per-region total; per-chunk counts stream as live heat tiles (section 4.6).
   - **Spiral Walk Tracer (`/rtp visualization walk-path`):** Verifies 1D spiral curve coverage across custom polygon geometry (ADR-001).
     - The walk-path payload shall declare `units: "block"` and carry `points`: a seed polyline of absolute block coordinates (chunk centres) sampled at even intervals along the full curve range, at most 2,048 vertices. The seed is drawn until full-resolution path tiles (section 4.6) cover the view; it is not the path's final resolution. The export carries no other path data: the full path is produced over time as live tiles.
     - The hazard RLE payload shall declare `units: "chunk"`; the client scales its bounds to blocks before drawing.
     - The client shall draw server-computed layers only for the geometry they were computed from. The page carries no built-in curve model: curve code comes only from the payload, as each shape's `toJavaScript()` helper, run in a sandboxed iframe and verified against a 256-sample hash (ADR-106 section 4). A region drawn from its helper carries no walk-path seed and no 2D hazard RLE; its hazards are curve-space runs (ADR-106 section 4.7). For shapes without a verified helper, after a local shape edit the page requests the walk path of the edited geometry over the channel (section 4.6 item 6), and without the channel it states that the path shows applied geometry only.
     - Shape names, their parameters and their types come only from the payload's `schema` (the registered shape and vertical-adjustor factories, add-on shapes included); the page shall hold no built-in shape list, and without a session it skips name checks.

### 4.4 Local Self-Contained HTML Production (`file:///` Portable Air-Gap Fallback) & Template SSOT
In environments where outbound internet access is restricted, the byte store is unreachable, or the operator prefers working completely locally:
- `/rtp editor local` (or `/rtp docs export`) produces a standalone, self-contained single-file HTML bundle directly under `<dataFolder>/editor/index.html`.
- The HTML bundle embeds the reactive configuration editor and, in a `<script>` data block, the session payload: active configs, the plugin's documentation set, shipped default configs and field docs.
- **Single Source of Truth (SSOT) & MkDocs Build Pipeline:**
  - The canonical web editor application template source code resides exclusively under `docs/editor/index.html` within the tracked documentation tree.
  - The `site/` folder is a purely generated, gitignored output artifact produced by `mkdocs build` (`mkdocs.yml` specifies `docs_dir: docs`). Source edits shall never target `site/`.
  - For runtime plugin operations, the single-file HTML template is packaged as a resource (`editor/index.html`) within the plugin jar distribution. At runtime, `EditorSessionManager` loads this packed template and inserts one inert `<script type="application/json" id="rtp-embedded-payload">` block before `</head>` carrying configs, telemetry, region models, schema, live docs and the packaged editor data (every `<` escaped), producing `<dataFolder>/editor/index.html` without duplicate HTML markup in compiled Java classes. A missing template shall fail the export with a logged error (S-004), never a reduced fallback page.
  - The template shall bundle no configs, docs, field docs, search thesaurus, region geometry or demo telemetry; everything it shows comes from the payload. Telemetry cards show `-` and the default region stays shapeless until the payload or live feed supplies them. `scripts/generate_web_editor.py` generates `docs/editor/editor-data.json` (`shipped` default configs, `docTracker` field docs and `synonyms`, copied from the hand-maintained `docs/editor/search-synonyms.json`), packaged beside the template as `editor/editor-data.json` and sent in every payload; `--check` fails when it is stale. Markup and scripts are edited in `docs/editor/index.html` directly.
- **No reachable server listener required:** The operator simply downloads `index.html` via SFTP/FTP/control panel or opens it on their local machine via `file:///`. The optional loopback channel (section 4.6) only adds two-way features when the browser runs on the server machine.
- **Same snapshot, same protocol ([ADR-106](ADR-106-shape-curve-helpers-and-signed-editor-channel.md)):** the embedded payload uses exactly the uploaded snapshot format, including `curveCode`, per-region `curve` and `hazardRuns`, so a `file:///` copy draws the full path offline. Its `channel` block names the loopback address as the relay, and the page runs the same signed handshake and messages as a hosted session. A local session is therefore a faithful test of the hosted flow.
- Changes made in the offline editor generate a copy-pasteable export snippet or an `/rtp editor apply <checksum>` token using an in-place file swap (`<dataFolder>/editor/pending.json`).

### 4.5 Intelligent Configuration Discovery & Hybrid Semantic Search Engine
To surpass the in-game book editor's basic substring search (`ConfigSearchResultsBuilder`) and solve config discoverability across dozens of configuration files and hundreds of keys:
1. **Multi-Dimensional Discovery Corpus:**
   - Search indexes three distinct semantic dimensions simultaneously:
     - **AST Nodes & Keys:** Exact and partial config paths across all loaded files (`configs`), e.g., `shape.centerRadius`, `economy.price`, `teleport-delay`.
     - **Values & Enum Identifiers:** Configured scalar values and enum options, e.g., `ACCUMULATE`, `LINEAR`, `world_nether`, `true`.
     - **Contextual Schema & Manual Prose:** Canonical descriptions, units, valid ranges, and gotchas extracted in `docTrackerDb` and the markdown manuals, both received in the session payload.
2. **Relevance Scoring & Fuzzy Matching:**
   - Evaluates search candidates against a multi-tier relevance scoring matrix:
     - Exact key name match (100 points).
     - Domain thesaurus / synonym match (80 points).
     - Prefix / substring key match (60 points).
     - Fuzzy Levenshtein match with typo tolerance (40 points).
     - Enum / scalar value match (30 points).
     - Documentation title or prose match (15 points).
3. **Packaged Domain Thesaurus (Deterministic Offline Baseline):**
   - A curated domain dictionary (docs/editor/search-synonyms.json, sent as synonyms in every payload via editor-data.json) maps player and operator vernacular to concrete configuration targets (e.g., `"money"`, `"cost"`, `"fee"` $\rightarrow$ `price`, `economy.yml`; `"height"`, `"altitude"` $\rightarrow$ `minY`, `maxY`, `vert`; `"lag"`, `"tps"` $\rightarrow$ `cacheCap`, `backlogCacheCap`, `activeChunkCap`).
   - Ensures instant, zero-latency ($< 2\text{ ms}$) search responses even when running completely offline in air-gapped environments (`file:///`).
4. **Progressive Remote Query Expansion (Online Mode):**
   - When the client browser is connected to the internet (`navigator.onLine`), queries are debounced ($250\text{ ms}$) and optionally enriched via remote linguistic query expansion (e.g. Datamuse API `ml=` / "means like").
   - Discovered semantic associations expand the search horizon for emerging slang, translations, and non-technical phrasing.
5. **Strict Remote Sanitization & Injection Defense (Code, DOM, Database & Prompt Injection):**
   - In accordance with repository security and data-boundary standards, all responses and tokens received from remote web endpoints are treated as untrusted data:
     - **Code & Script Injection Prevention:** Remote strings are never passed to dynamic evaluation sinks (`eval()`, `new Function()`, `setTimeout(string)`, `setInterval(string)`), never executed as JavaScript, and never attached to inline event handlers.
     - **DOM & Cross-Site Scripting (XSS) Prevention:** Remote search terms are used strictly as passive comparison needles against existing local AST keys. Any dynamic rendering of returned terms or query labels is strictly HTML-escaped (`escapeHtml()`) before DOM insertion, preventing script tag injection, iframe breakout, or attribute escape vectors.
     - **Database & Query Injection Prevention:** Ingested remote terms are restricted to matching a strict alphanumeric identifier regex (`^[a-z][a-z\-]{1,23}$`). Any input containing SQL delimiters (quotes, semicolons, dashes `--`, comments `/* */`, union/select statements) or YAML/JSON meta-characters (`{`, `}`, `[`, `]`, `:`, `"`, `'`) is rejected and dropped immediately, ensuring remote data can never pollute persistent storage, database migration scripts, or configuration ASTs.
     - **Silent Fail-Closed Execution:** Any network timeout, HTTP error, malformed JSON, or CORS restriction is silently trapped without interrupting or stalling local search operations.

### 4.6 Local Live Feed & Biome Distribution Sources
A local export is a snapshot; operators placing regions need land data where no teleport has sampled yet, and data that changes as the server runs. Data that grows over time shall be produced by the live feed, never by export generation, and shall be shipped in one compact format end to end.
1. **Bin format (`BiomeBinCodec`, shared by store, wire and page):**
   - Outer layer: one bin per region file (32 x 32 chunks), keyed by its square-spiral ring index around region file (0, 0); keys are dense and nearest-first order is key order.
   - Inner layer: an order-5 Hilbert curve over the bin's 1,024 chunks; cells are `(value, length)` unsigned-varint runs along the curve covering all 1,024 cells. Value 0 is unknown.
   - Detail levels are aligned curve stretches: level 0 = 1 sample per bin, level 1 = 64 samples (4 x 4 chunk stretches), level 2 = every chunk. A coarse sample fills its stretch; finer samples overwrite it in place, so every level uses the same encoding (~50-150 bytes per bin).
   - The page keeps the same bytes (`Uint8Array`), decodes a bin only to paint it into a group atlas (16 x 16 bins per canvas, each bin at the power-of-two pixel size of its screen size, at most 32) cached while on screen, and computes histograms from runs. Atlases are painted under a 12 ms per-frame budget, view centre first, so a whole-world view fills progressively instead of blocking the page. Shared vectors in `BiomeBinCodecTest` and the page decoder keep both sides identical.
2. **World biome store (`WorldBiomeStore`):**
   - One store per world, at most 65,536 bins and 4 sample-Y layers. Each layer records its detail level; each bin records the `.mca` modification time it was read at.
   - Lookups take the nearest stored layer within 16 blocks of the requested Y and report the Y used; a request with no layer in tolerance starts a survey at that Y.
   - Persisted per world as `<dataFolder>/cache/biomes/<world>.rbs` (gzip, atomic replace), saved periodically and when the feed stops. A changed `.mca` mtime keeps the bin drawn but marks it for the full-detail pass only. A corrupt or foreign file is logged and ignored (S-004).
3. **World land survey (`WorldLandSurvey`):**
   - One survey per loaded world with configured regions at Y 64, covering **every region file the world has on disk** (`RTPWorld.listRegionFiles()`, directory listing only, off-tick), nearest the origin first. An adapter that cannot list falls back to probing a disc of 64 region files around the origin. At most 16,384 region files per world; reaching the cap is reported as truncated.
   - Three passes over the whole world, level 0 then 1 then 2, so the map fills first and sharpens later. Bins already at a level with an unchanged mtime are skipped without a read. A file whose centre chunk is not generated is sampled at level 1 in the first pass.
   - Reads go through `RTPWorld.sampleBiomesInRegionFile(rcx, rcz, y, indices)`: the adapter reads only the region header and the sampled chunks' sectors (`AnvilRegionSampler`), disk only, off the tick thread (S-005). The per-tick budget counts chunk decodes (2,048) and bin visits (4,096), not files (rule F-002).
   - Viewport requests (item 6) queue up to 1,024 visible bins that are taken to full detail before the world pass continues.
   - Biome ids are normalised to namespaced lowercase (`minecraft:plains`, `iris:ash`).
4. **Live feed (`EditorLiveFeed`, `/rtp editor local` and the, per-world palettes, per-region rows (session-unique id, path progress, landing total, learned biome histogram) and the version of every tile group.
   - Tile groups are location-keyed and rewritten only when their content changed, written before the head lists their new version:
     - Land: `live/land-<w>_<y>_<gx>_<gz>.js`, the stored bins of 16 x 16 region files, `[rx, rz, level, base64Runs]`.
     - Hazards: `live/hazard-<r>_<gx>_<gz>.js`, the region's learned bad-location memory per chunk (`FailTypes` ordinal + 1), rescanned round-robin from memory (`HazardTiles`).
     - Landings: `live/heat-<r>_<gx>_<gz>.js`, bucket `1 + floor(log2(count))` per chunk from `LandingHeatmap`.
     - Walk path: `live/path-<r>_<tx>_<tz>.js` per 32 x 32 chunk tile (each chunk's curve location, `-1` off the curve), named by immutable `live/path-N.js` index batches.
   - Region ids: a new or reshaped region (new shape object or range) gets a new id and restarts its path and hazard producers; the old id's files are deleted, and the page drops ids that leave the head.
   - The page re-injects `feed.js?seq=` as a script tag - the only cross-file read browsers permit from `file://` - and loads only groups that intersect its viewport and are newer than what it loaded (at most 8 requests in flight). Feed files contain passive JSON only.
   - The feed shall stop 30 minutes after the export, on plugin disable, when superseded by a newer export, or after 5 consecutive write failures; every stop is logged with its reason and the final head tells the page why (S-004). Directory preparation deletes only files matching the feed's own names.
5. **Export embedding:**
   - Every export embeds `worldBiomes`, the bins already in the store, nearest the origin first, so the page opens with the map filled. Local exports embed stored detail up to ~4 MB of base64 per world; online byte-store sessions (immutable, no live feed) embed each bin collapsed to one run, at most 16,384 bins per world.
   - Region models carry the export-time hazard stream, the learned biome histogram, the landing total and the walk-path seed only. Regions whose shape ships a curve helper carry `curve` and curve-space `hazardRuns` instead of the seed and the hazard stream (ADR-106 section 4.4); hosted sessions receive live updates over the relay channel (ADR-106 section 5).
6. **Loopback channel (`EditorLoopbackChannel`, local exports):** carries the signed editor protocol of [ADR-106](ADR-106-shape-curve-helpers-and-signed-editor-channel.md) section 5 through `LoopbackTransport`: the same envelope, handshake, trust and message types as the hosted relay, with the 32 KiB frame cap. The rules below stay as the socket's own defence in depth.
   - A non-blocking RFC 6455 listener bound to the loopback interface on an ephemeral port, driven by an `RTP.scheduler` timer (no own threads), opened per export and stopped with the feed. The export embeds its `ws://127.0.0.1:<port>/rtp-editor-ws?token=<128-bit hex>` address.
   - Handshake rules: loopback peer, loopback `Host`, Origin absent / `null` / localhost only, constant-time token compare, at most 4 clients; masked text frames up to 256 KiB, a 2 MiB outbound cap per client.
   - Server to page: each feed head is pushed as `{type: "feed"}`, so updates arrive without waiting for the next poll.
   - Page to server: `{type: "focus", world, y, minRx, minRz, maxRx, maxRz}` (debounced view rectangle) prioritises those bins; `{action: "apply", token, files}` Hot-Applies through the same validation pipeline as `/rtp editor apply` and is acknowledged with `apply_ack`; `{type: "walkpath", region, sig, shape: {name, key: YAML scalar, vertices}}` asks for the walk path of edited geometry (`EditorWalkPathPreview`).
   - Walk-path previews: the shape is built from the registered factory prototype (clone, scalar settings, polygon vertices) as a region file would build it, with no world access, and answered to the requesting client only as `{type: "walkpath", region, sig, data}` (the 2,048-point seed) or `{..., error}`. At most 4 per feed tick, newest request per client and region; messages up to 64 KiB, 1,024 vertices, scalar values only. The page keeps a reply only while its geometry still matches `sig`.
   - Without the channel (browser on another machine, channel failed to open: logged), the page works one-way from `feed.js`.
7. **Walk path tiles (`WalkPathTiles`):** skipped, together with `HazardTiles`, for regions whose curve helper the page reports verified (ADR-106 section 4.7); they remain the local fallback for every other region.
   - One producer per configured region with a curve-backed shape. Tiles covering the shape's chunk bounds are computed nearest the shape centre first, 16 per tick, using `MemoryShape.xzToLocation` / `locationToXZ` only (pure math, no world access). A chunk is on the curve when its location is in range and maps back to it, or to a direct neighbour.
   - Capped at 4,096 tiles (4.2M chunks) per region; reaching the cap is reported as truncated.
   - The page joins each on-curve chunk to the chunk holding the next curve location, so the path is exact at any zoom without the browser holding the whole curve.
8. **Biome distribution source order (Visual Region Editor):**
   1. Biomes learned by the region's own teleport attempts (`MemoryShape` biome cache; refreshed from the live feed), when present.
   2. Otherwise, chunks of the loaded land bins that fall inside the current, possibly unsaved, shape - at the detail each bin reached, coarse bins labelled as estimates - recomputed (debounced 200 ms, cached per shape and store version) on every shape edit and land update, labelled with the chunk count and the share of the shape's area covered.
   3. Otherwise, when nothing inside the shape is generated, the distribution of all surveyed land of the world, labelled as such, to guide placement.
   - The source line shall always state which source is shown; an empty list states which inputs were missing.

---

## 5. Consequences

### Positive
- **Simultaneous Read & Edit:** Solves the screen-lock limitation of in-game Minecraft menus.
- **Zero Firewall / Port Hassles:** 100% outbound HTTPS; runs on any shared host or personal PC. The loopback channel is never reachable from another machine.
- **Hosted live updates (ADR-106):** hosted sessions get the complete walk path and hazards from the snapshot at any zoom, plus live curve changes, hazard deltas, focus-driven land detail, walk-path previews and Hot-Apply over the signed relay channel, with no inbound listener.
- **Fast, persistent land map:** coarse-first passes and sampled region reads fill the world map in minutes; the persisted store makes re-exports and restarts re-read only changed region files.
- **Air-Gapped Reliability:** In-game book viewer works fully offline when internet or web browser is unavailable.
- **High Usability:** No tedious manual typing of config paths or parameters.

### Negative / Trade-offs
- Requires external byte store endpoint for ephemeral session transport. If external network is down, operators fall back to the in-game book editor (`/rtp admin`) and in-game packed docs (`/rtp docs`).
- Hosted live updates depend on a third-party WebSocket relay (`bytesocks.lucko.me` by default, configurable), as uploads depend on bytebin. If the relay can't be reached, the session stays snapshot-only with a logged reason and a page badge. Each new browser key needs one in-game trust action (ADR-106 section 5.2).
- The local `feed.js` files (section 4.6) exist only for local exports opened from the server's own `editor/` folder; a copied or downloaded `index.html` shows WAITING FOR FEED and stays a snapshot (still drawing verified curves from its embedded helpers). The loopback channel additionally needs the browser on the server machine.
- The survey samples biomes at a fixed Y (64 by default), not the surface heightmap; mountain chunks may report cave biomes. Ungenerated land carries no biome data.
- Until the full-detail pass reaches a bin, its biomes are estimates from 1 or 64 samples per region file.
- The loopback channel is a listening socket, a deliberate exception to Option 1's rejection, bounded to loopback, a per-export token and the feed's lifetime.
- Disk costs: the biome store (~1-3 MB per world, gzip) persists under `cache/biomes/`; live tiles in `editor/live/` (land groups, overlay groups, up to ~4,096 path tiles per region) are deleted when the next export prepares the directory. Landing counts are in memory only and reset on restart.
