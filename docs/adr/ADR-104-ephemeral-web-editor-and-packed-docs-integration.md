# ADR-104 — Ephemeral Web Editor and In-Game Packed Docs Integration

**Status:** Accepted
**Date:** 2026-10-03
**Authors:** RTP Core Team
**Extends:** [ADR-045](ADR-045-rtp-docs-menu-consumer.md) (`/rtp docs` Menu Consumer), [ADR-064](ADR-064-config-node-in-game-hover-doc-sync.md) (Config In-Game Hover Sync), [ADR-086](ADR-086-external-web-map-integration.md) (External Web Map Integration), [ADR-099](ADR-099-visual-region-creation-and-interactive-cartography-bridges.md) (Visual Region Creation and Cartography Models)
**Related REQs:** S-004, S-005, S-006, S-007, REQ-RTP-F-013

---

## 1. Context and Problem Statement

Server operators and addon developers face several discoverability and usability barriers:
1. **The In-Game Documentation Vacuum:** Operators have tactile in-game levers via `/rtp admin`, but no interactive way to read bundled operator manuals without alt-tabbing to external browsers or reading raw YAML.
2. **The Simultaneous View & Edit Dilemma:** Opening an in-game inventory or book menu locks the client's screen. An operator cannot read an in-game manual while simultaneously adjusting values in the in-game config editor, or they are forced to juggle commands blindly.
3. **Remote Hosting Constraints:** Most servers run on third-party hosts or remote friend machines where inbound ports (for embedded HTTP daemons) are blocked or require complex port-forwarding. Furthermore, embedded HTTP listeners introduce severe network attack surface, CSRF, and authentication vulnerabilities.
4. **Drift Between Shipped Plugin & Web Docs:** Online documentation on GitHub or the project website reflects the latest trunk commit, which may differ from the specific release version deployed on the server.

---

## 2. Decision Drivers

- **Zero Inbound Attack Surface:** No embedded HTTP daemons or listening network sockets inside the server JVM.
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
3. **Bi-directional Synchronization & Back-Propagation:**
   - **Outbound Web Commits:** If an ephemeral WebSocket/long-poll channel is open between server and byte store, clicking **"Hot-Apply"** in the browser transmits the mutation directly, hot-reloading server regions without requiring any manual command pasting.
   - **Command Fallback (`/rtp editor apply <token>`):** In offline, restricted, or disconnected environments, clicking "Save" generates the `/rtp editor apply <token>` command for copy-pasting into chat or console.
   - **In-Game Back-Propagation:** When mutations occur in-game (e.g., via `/rtp region set`, WorldEdit selection `/rtp region fromselection`, or in-game book GUI), the server broadcasts a delta to the active web session, updating the browser canvas and staging diff in real time to prevent stale-overwrite conflicts.
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
     - Rather than shipping high-overhead block-level voxel data, the visual editor renders a map of actually generated land using **chunk-resolution biome data** derived from generated chunks on disk (`.mca` presence and cached NBT biomes, adhering to S-005).
     - Biome data is encoded at native chunk granularity ($16 \times 16$ block column per sample), which reflects real generation bounds without wasting bandwidth on redundant block-level geography.
     - **Throttled Progressive Streaming (> 5 FPS Budget):** To prevent client-side UI freezes and ensure the interactive canvas and staging diff stay strictly above $5\text{ FPS}$ during heavy streaming across large worlds, biome chunk batches are sent incrementally ("a little at a time") over WebSocket / long-poll frames, budgeted against client frame rendering and browser event loop times.
     - Provides a lightweight 2D cartography backdrop resembling BlueMap / Dynmap explored-terrain boundaries or in-game biome maps, allowing operators to visually align RTP region radii, donut centers, and polygon vertices against pregenerated world borders.
   - **Candidate Dispersion Heatmap (`/rtp visualization heatmap`):** Audits teleport distribution and confirms uniform Archimedean spiral dispersion.
   - **Spiral Walk Tracer (`/rtp visualization walk-path`):** Verifies 1D spiral curve coverage across custom polygon geometry (ADR-001).

### 4.4 Local Self-Contained HTML Production (`file:///` Portable Air-Gap Fallback) & Template SSOT
In environments where outbound internet access is restricted, the byte store is unreachable, or the operator prefers working completely locally:
- `/rtp editor local` (or `/rtp docs export`) produces a standalone, self-contained single-file HTML bundle directly under `<dataFolder>/editor/index.html`.
- The HTML bundle embeds the entire documentation set and the reactive configuration editor along with active config JSON directly in a `<script>` data block.
- **Single Source of Truth (SSOT) & MkDocs Build Pipeline:**
  - The canonical web editor application template source code resides exclusively under `docs/editor/index.html` within the tracked documentation tree.
  - The `site/` folder is a purely generated, gitignored output artifact produced by `mkdocs build` (`mkdocs.yml` specifies `docs_dir: docs`). Source edits shall never target `site/`.
  - For runtime plugin operations, the single-file HTML template is packaged as a resource (`editor/index.html`) within the plugin jar distribution. At runtime, `EditorSessionManager` loads this packed template and hydrates placeholder tokens (`/*__CONFIG_DATA__*/`, `/*__DOCS_DATA__*/`, `/*__TELEMETRY_SNAPSHOT__*/`) to produce `<dataFolder>/editor/index.html` dynamically, eliminating duplicate hardcoded HTML markup in compiled Java classes.
- **Zero server network listener required:** The operator simply downloads `index.html` via SFTP/FTP/control panel or opens it on their local machine via `file:///`.
- Changes made in the offline editor generate a copy-pasteable export snippet or an `/rtp editor apply <checksum>` token using an in-place file swap (`<dataFolder>/editor/pending.json`).

### 4.5 Intelligent Configuration Discovery & Hybrid Semantic Search Engine
To surpass the in-game book editor's basic substring search (`ConfigSearchResultsBuilder`) and solve config discoverability across dozens of configuration files and hundreds of keys:
1. **Multi-Dimensional Discovery Corpus:**
   - Search indexes three distinct semantic dimensions simultaneously:
     - **AST Nodes & Keys:** Exact and partial config paths across all loaded files (`configs`), e.g., `shape.centerRadius`, `economy.price`, `teleport-delay`.
     - **Values & Enum Identifiers:** Configured scalar values and enum options, e.g., `ACCUMULATE`, `LINEAR`, `world_nether`, `true`.
     - **Contextual Schema & Manual Prose:** Canonical descriptions, units, valid ranges, and gotchas extracted in `docTrackerDb` and bundled markdown manuals.
2. **Relevance Scoring & Fuzzy Matching:**
   - Evaluates search candidates against a multi-tier relevance scoring matrix:
     - Exact key name match (100 points).
     - Domain thesaurus / synonym match (80 points).
     - Prefix / substring key match (60 points).
     - Fuzzy Levenshtein match with typo tolerance (40 points).
     - Enum / scalar value match (30 points).
     - Documentation title or prose match (15 points).
3. **Baked-In Domain Thesaurus (Deterministic Offline Baseline):**
   - A curated domain dictionary is embedded directly in the web editor template, mapping player and operator vernacular to concrete configuration targets (e.g., `"money"`, `"cost"`, `"fee"` $\rightarrow$ `price`, `economy.yml`; `"height"`, `"altitude"` $\rightarrow$ `minY`, `maxY`, `vert`; `"lag"`, `"tps"` $\rightarrow$ `cacheCap`, `backlogCacheCap`, `activeChunkCap`).
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

---

## 5. Consequences

### Positive
- **Simultaneous Read & Edit:** Solves the screen-lock limitation of in-game Minecraft menus.
- **Zero Firewall / Port Hassles:** 100% outbound HTTPS; runs on any shared host or personal PC.
- **Air-Gapped Reliability:** In-game book viewer works fully offline when internet or web browser is unavailable.
- **High Usability:** No tedious manual typing of config paths or parameters.

### Negative / Trade-offs
- Requires external byte store endpoint for ephemeral session transport. If external network is down, operators fall back to the in-game book editor (`/rtp admin`) and in-game packed docs (`/rtp docs`).
