# Start Here — Server Administrators

**Current Plugin Version:** `@version@`

This page guides server operators through the RTP documentation in the recommended reading order.
If you install, configure, or maintain RTP on a Paper, Spigot, Folia, Fabric, or NeoForge server, this is your entry point.

---

## Minimum Operational Requirements

RTP is engineered for zero main-thread tick impact and deterministic memory management. Pre-warmed destination queues, spatial rejection memory, and off-tick region file pre-filtering eliminate synchronous chunk loads and avoid the unbounded reroll loops that cause GC churn in naive implementations. Memory usage is bounded and tracked by an active ticket reaper.

- **Java 21+** (hard requirement).
- **Predictable memory footprint.** `cacheCap` and `activeChunkCap` (in region files) are *absolute* per-region bounds. Cached entries are lightweight coordinate descriptors; destination chunks are kept in memory only while in the hot L1 queue. Total cache memory scales with configured region counts, not world radius. Spatial memory uses compressed bit-segments to record rejected terrain without storing chunk objects. RTP includes an active heap-pressure gate (`performance.yml` -> `maxHeapPercent`, default 85%) that throttles background pre-warming under memory pressure while pre-cached teleports continue serving instantly.

**Reference environment (measured, not a certified minimum).** The in-repo devstack and stress harness run on a **Ryzen 9 3900X with 16 GiB allocated to the server**. Treat this as a known-good baseline rather than a floor - a single small region needs far less. A properly-sourced minimum (heap per region at a given `cacheCap`) is planned once opt-in telemetry lands to measure real deployments; until then, provision generously and watch the console for `maxHeapPercent` warnings.

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
Step-by-step upgrade instructions when moving between major versions.
Read this before upgrading an existing server.

### 6. [RUNBOOK.md](admin/RUNBOOK.md)
Incident response for common operational problems: TPS drops, players landing in unsafe locations, plugin failing to enable, fill task stalls, and more.
Each entry follows the pattern: **Symptom → Diagnosis → Resolution**.

### 7. [HAZARDS.md](admin/HAZARDS.md)
Hazard register with severity ratings and mitigations for risks inherent to the plugin's design (it also absorbs the former failure-mode catalog: detection signals and defined system responses).
Companion to the Runbook — use this when you know *what* failed but not *why*, and for capacity planning and risk assessment before deploying in production.

---

## Also Useful

- [Intended usage](site/intended-usage.md) — plain-language explanation of how RTP's regions, queue, shapes, and teleport pipeline work. Helpful for understanding *why* certain config values behave the way they do.
- [Why LeafRTP exists](site/why.md) — the motivation and the distribution algorithm behind the plugin.
