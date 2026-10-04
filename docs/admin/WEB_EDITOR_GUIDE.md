# Web Workspace & Visual Editor Guide

**Current Plugin Version:** `@version@`
**Architecture Record:** [ADR-104](../../adr/ADR-104-ephemeral-web-editor-and-packed-docs-integration.md) (Ephemeral Web Editor and In-Game Packed Docs Integration)
**Related Prohibitions:** S-004 (no silent failures), S-005 (zero main-thread chunk/network I/O), S-006 (fail-closed API contract)

The LeafRTP Web Workspace is a browser-based visual cartography and configuration staging application (`docs/editor/` or hosted `/editor/`). It allows server operators to inspect engine diagnostics, adjust region boundary geometry, and configure plugin YAML with live documentation hints on a secondary monitor while retaining active gameplay on their primary display.

---

## 1. Problem Statement: The Simultaneous View & Edit Dilemma

Traditional server management confronts operators with screen conflicts and accessibility hurdles:
- **Screen Lock:** Opening in-game books (`/rtp docs`, ADR-045) or inventory menus locks the Minecraft client screen. Operators cannot comfortably cross-reference documentation while tweaking complex configuration files or command arguments.
- **Port-Forwarding & Remote Hosting Restrictions:** Running an embedded HTTP listener inside the Minecraft JVM requires inbound firewall ports, which are frequently blocked by shared hosting environments or home NATs. Embedded servers also introduce network attack surfaces (e.g. CSRF, unauthenticated sockets).
- **Geometric Complexity:** Manually hand-typing coordinates for multi-vertex polygons (`POLYGON`) or inner exclusion rings (`centerRadius`) is error-prone and unintuitive.

LeafRTP resolves these issues using an **outbound ephemeral session architecture** modeled after `spark` and `LuckPerms`, paired with offline in-game book lowering.

---

## 2. Architecture & Security Model

```
+---------------------------------------------------------------------------------------------------+
| PRIMARY SCREEN: Minecraft Client (In-Game)                                                        |
|                                                                                                   |
|   /rtp editor (or /rtp admin -> [🌐 Web Editor])                                                  |
|       │                                                                                           |
|       ▼                                                                                           |
|   Emits ephemeral link: https://dailystruggle.github.io/RTP/editor#<128-bit-token>                |
+---------------------------------------------------------------------------------------------------+
                                        │
                                        │ (Click link, opens on adjacent display)
                                        ▼
+---------------------------------------------------------------------------------------------------+
| SECONDARY SCREEN: Dedicated Web Workspace (Browser)                                               |
|                                                                                                   |
|   ┌───────────────────────────────────────────────┬───────────────────────────────────────────┐   |
|   │ 🗺 2D Vector Cartography Canvas               │ 📋 Staging Diff & Validation Inspector    │   |
|   │ - Polygon vertex dragging & simplification    │ - Live YAML mutations across files        │   |
|   │ - Donut (centerRadius) & Box boundary gizmos  │ - ADR-034 non-self-intersection guard     │   |
|   │ - Live Biome & Bad-Location Overlays          │ - Context-tracking schema documentation   │   |
|   └───────────────────────────────────────────────┴───────────────────────────────────────────┘   |
|                                       │                                                           |
|                                       │ Click [⚡ Hot-Apply / Copy]                               |
|                                       ▼                                                           |
|   Outbound WebSocket commit OR Copy-to-clipboard: /rtp editor apply <token>                      |
+---------------------------------------------------------------------------------------------------+
                                        │
                                        │ (WebSocket push or operator pastes /rtp editor apply <token>)
                                        ▼
+---------------------------------------------------------------------------------------------------+
| SERVER VERIFICATION & COMMIT PIPELINE                                                             |
|                                                                                                   |
|   1. Async fetch/receive session payload via outbound HTTPS/WebSocket (S-005)                     |
|   2. Validate payload SHA-256 integrity and authorization token                                   |
|   3. Validate geometry invariants (ADR-034 non-self-intersection & world border bounds)           |
|   4. Off-thread disk backup of affected configuration files (.bak)                                |
|   5. Atomic in-memory config swap and disk flush on async worker                                  |
|   6. Notify operator in chat with exact diff summary (S-004)                                      |
+---------------------------------------------------------------------------------------------------+
```

### Security Safeguards
1. **Zero Inbound Attack Surface:** The server opens no listening network sockets, HTTP daemons, or open ports. All network traffic is outbound HTTPS/WebSocket to the ephemeral byte store.
2. **Cryptographic Tokens:** Sessions use cryptographically random 128-bit hex tokens (`SecureRandom`). Tokens expire automatically and are invalidated once applied.
3. **Fail-Closed Geometry Validation:** Proposed region boundaries are validated against ADR-034 non-self-intersection rules and world borders before any config file is updated on disk.
4. **Non-Destructive Backups:** The server automatically writes `.bak` copies of all affected configuration files prior to applying modifications.

---

## 3. Recommended Dual-Screen Setup

For the optimal workflow, configure your physical workspace as follows:

| Display | Environment | Role |
|---------|-------------|------|
| **Screen 1 (Main)** | Minecraft Client (Full-screen or Borderless) | In-game navigation, player perspective testing, and immediate execution of `/rtp` or `/rtp apply`. |
| **Screen 2 (Adjacent)** | Web Browser (`docs/editor/` or hosted `/editor/`) | Interactive 2D cartography canvas, live staging diff inspector, and contextual YAML schema documentation. |

---

## 4. Step-by-Step Operator Workflow

### Step 1: Initiate Session
Run the editor command in-game:
```text
/rtp editor
```
*(Alternatively, execute `/rtp admin` and click the **[🌐 Web Editor]** button in the administration panel).*

The server asynchronously gathers active configuration models, active region geometries, and documentation schemas, dispatches an outbound payload, and emits a clickable chat link:
```text
[RTP] Ephemeral editor session generated:
https://dailystruggle.github.io/RTP/editor#e4d2a90f1b2c3d4e
```

### Step 2: Open Workspace & Edit Region Geometry
Click the link to open the workspace in your browser on Screen 2.

- **Panel 1: 🗺 Visual Region Editor**
  - **Pan & Zoom:** Navigate around world coordinate origins (X: 0, Z: 0).
  - **Drag Vertices:** Click and drag polygon vertices to adjust region perimeters. Redundant collinear points are simplified dynamically (ADR-099).
  - **Adjust Donut Rings:** Modify the outer `radius` or inner `centerRadius` (deadzone exclusion).
  - **Visualization Layers:** Toggle overlays for chunk-resolution pregenerated land (biome data streamed progressively to maintain > 5 FPS), rejected candidate blocks (`bad-locations`), and 1D Archimedean spiral walk traces (ADR-001).

- **Panel 2: 📊 Diagnostics & Telemetry**
  - Monitor real-time L1 hot chunk queue depth (active tickets), L2 cold queue depth, and L3 backlog binned cache.
  - Check `MemoryTracker` watchdog status and active worker thread counts.

- **Panel 3: ⚙ Config & Prefabs**
  - Edit `config.yml`, `messages.yml`, or `regions/*.yml` directly.
  - The **Contextual Doc Tracker** drawer tracks your cursor in real time, displaying parameter definitions, default values, units, and links to relevant documentation and ADRs.

- **Panel 4: 📖 Shipped Docs**
  - Read bundled operator runbooks and guides directly inside the web workspace.

### Step 3: Inspect the Staging Diff
Before committing changes, review the **📋 Staging Diff Inspector** sidebar on the right of the workspace.
Every modification is staged as an explicit delta:
```yaml
# Staged deltas for definitions/regions/default.yml:
-   radius: 256c
+   radius: 300c
-   centerRadius: 64c
+   centerRadius: 80c
```
The **Math Invariant Guard** confirms that all geometric shapes satisfy ADR-034 requirements (non-self-intersecting polygons, valid radii, coordinates within world borders).

### Step 4: Apply Changes
Click **[⚡ Hot-Apply / Copy]** in the top navigation bar.
1. **Direct Outbound Apply (Connected Mode):** If a bi-directional WebSocket session is active between the server and byte store, the changes commit directly. The server reloads regions instantly and logs a diff summary in chat.
2. **Command Fallback (Token Mode):** If working in a disconnected or air-gapped environment, the workspace displays a copyable token command:
   ```text
   /rtp editor apply token=<token>
   ```
   Paste this command into your Minecraft chat or console to execute the atomic commit.

---

## 5. Air-Gapped & Local Offline Mode (`file:///`)

For environments without outbound internet connectivity or where external byte stores are unreachable (ADR-104 §4.4):

1. Generate a self-contained offline HTML bundle via console or chat:
   ```text
   /rtp editor local
   ```
   *(or `/rtp docs export`)*
2. The server outputs a single standalone file at:
   ```text
   plugins/RTP/editor/index.html
   ```
3. Copy or open this file directly in any browser using standard `file:///` protocols.
4. The local bundle contains all documentation, the 2D vector canvas engine, and active configurations embedded directly in client-side data structures with zero external requests.
5. Exported changes generate a pending JSON swap file or copyable `/rtp editor apply <checksum>` token.

---

## 6. Troubleshooting & Common Operational Errors

| Symptom | Cause | Resolution |
|---------|-------|------------|
| **`/rtp editor` logs connection timeout** | Server outbound HTTPS traffic blocked by host firewall. | Whitelist outbound HTTPS connections to `bytebin.lucko.me` or generate the local offline export via `/rtp editor local`. |
| **"Invalid or expired session token" upon `/rtp editor apply`** | Token expired (exceeded TTL) or was already committed. | Re-run `/rtp editor` in-game to issue a fresh session token. |
| **"Self-intersecting polygon boundary" warning in browser** | Vertex dragging created an invalid complex polygon. | Reposition vertices so boundary edges do not cross each other; verify the Math Invariant Guard displays green checks. |
| **Browser canvas appears blank** | Hardware acceleration disabled or canvas size zeroed. | Resize browser window or click the **🗺 Visual Region Editor** tab to trigger automatic canvas resize. |

---

## Related Documentation

- [`FOR_SERVER_ADMINS.md`](../FOR_SERVER_ADMINS.md) — Server administrator onboarding and reading order.
- [`QUICK_START.md`](QUICK_START.md) — Fast 10-minute setup guide.
- [`COMMANDS.md`](COMMANDS.md) — Full command reference for `/rtp editor`, `/rtp editor apply`, and `/rtp docs`.
- [`RUNBOOK.md`](RUNBOOK.md) — Incident response and operational troubleshooting.
- [`CONFIGURATION.md`](configuration/CONFIGURATION.md) — Complete configuration parameter reference.
- [`ADR-104`](../../adr/ADR-104-ephemeral-web-editor-and-packed-docs-integration.md) — Architectural design record for ephemeral web editor and in-game docs.
