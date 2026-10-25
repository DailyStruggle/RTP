# Start Here — Server Administrators

**Current Plugin Version:** `@version@`

This page guides server operators through the RTP documentation in the recommended reading order.
If you install, configure, or maintain RTP on a Paper, Spigot, Folia, Fabric, or NeoForge server, this is your entry point.

---

## Minimum Operational Requirements

LeafRTP runs on any **Java 17+** server out of the box with a small, bounded memory footprint, and scales cleanly from a casual 4 GB survival server up to multi-proxy networks. Pre-warmed destination queues, spatial rejection memory, and off-tick region file pre-filtering keep candidate checks off the tick thread and avoid the unbounded reroll loops that cause GC churn in naive implementations. On Paper and Folia, chunk loads go through the async API; on Spigot, a candidate the pre-filter cannot answer (ungenerated terrain, unknown data version) costs one on-tick chunk load.

- **Java 17+** (hard requirement), or newer where Minecraft needs it: Java 21 from 1.20.5, Java 25 on 26.x. Supported versions: Paper and Spigot 1.18+, Folia 1.21.11+, Fabric 1.20.1 and 1.21+, NeoForge 1.21.1+, up to 26.3; bug reports are accepted for 1.21.11 through the latest release ([support matrix](dev/SUPPORT_MATRIX.md)). Folia is experimental and only its current builds are maintained, so older Folia is not supported.
- **Small, predictable memory footprint.** `cacheCap` and `activeChunkCap` (in region files) are *absolute* per-region bounds. Cached entries are lightweight coordinate descriptors; destination chunks are kept in memory only while in the hot L1 queue. Total cache memory scales with configured region counts, not world radius, and spatial memory uses compressed bit-segments to record rejected terrain without storing chunk objects. An active heap-pressure gate (`performance.yml` -> `maxHeapPercent`, default 85%) throttles background pre-warming under memory pressure while pre-cached teleports continue serving instantly. *(Note: the in-repo stress harness and devstack benchmarks run on a Ryzen 9 3900X / Threadripper test rig with 16 GiB heap to find saturation ceilings—a normal single-region survival server needs only a fraction of that.)*

---

## Recommended Reading Order

### 1. [QUICK_START.md](admin/QUICK_START.md)
Get the plugin installed and your first region running in under 10 minutes.
Covers installation, the default region, adding a nether region, and essential permissions.

### 2. [COMMANDS.md](admin/COMMANDS.md)
Every `/rtp` subcommand, parameter, and permission node with examples.
Reference this any time you are unsure of command syntax or available options.

### 3. [CONFIGURATION.md](admin/configuration/CONFIGURATION.md)
Every configuration key across all files (`config.yml`, `performance.yml`, `safety.yml`, `economy.yml`, region files, world files) with type, default value, and description.

### 4. [FAQ.md](admin/FAQ.md)
Answers to the most common questions: biome filtering, economy integration, claim plugin setup, performance tuning, and more.

### 5. [MIGRATION.md](admin/MIGRATION.md)
Step-by-step upgrade instructions when moving between major versions or importing from another RTP plugin.
Read this before upgrading an existing server.

### 6. [RUNBOOK.md](admin/RUNBOOK.md) & [WEB_EDITOR_GUIDE.md](admin/WEB_EDITOR_GUIDE.md)
Incident response for common operational problems and guide for the dual-screen visual web workspace (`/rtp editor`).
Covers symptom diagnosis, 2D vector cartography editing, live staging diffs, and atomic commits.

---

## Also Useful

- [Intended usage](site/intended-usage.md) — plain-language explanation of how RTP's regions, queue, shapes, and teleport pipeline work. Helpful for understanding *why* certain config values behave the way they do.
- [Why LeafRTP exists](site/why.md) — the motivation and the distribution algorithm behind the plugin.
- **Developer / internals reference:** [HAZARDS.md](dev/HAZARDS.md) — engineering hazard register, failure-mode catalog, detection signals, and system mitigations for capacity planning and deep diagnostics.
