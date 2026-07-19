# Start Here — Server Administrators

**Current Plugin Version:** `3.0.0-beta.1`

This page guides server operators through the RTP documentation in the recommended reading order.
If you install, configure, or maintain RTP on a Bukkit/Spigot/Paper/Folia server, this is your entry point.

---

## Minimum Operational Requirements

RTP trades memory for tick time. The pre-warmed location queue, per-region spatial memory, and the Anvil pre-filter caches all consume heap so that teleports resolve without loading chunks on demand. On a heap-starved host the JVM spends its time garbage-collecting and the server appears to hang - that is an under-provisioned deployment, not a plugin fault.

- **Java 21+** (hard requirement).
- **Heap headroom for the caches.** `cacheCap` and `activeChunkCap` (in your region files) are *absolute* per-region caps: each region costs a fixed amount of heap (roughly `cacheCap + activeChunkCap` cached entries), so total cache memory scales with your region count, not with radius. Radius changes only the size of the per-region spatial memory (the `MemoryShape` land/bad-sector map), not the queue or ticket pool. RTP ships a heap-pressure gate (`performance.yml` -> `maxHeapPercent`, default 85%) that pauses *background cache generation* and logs a warning while the heap is under pressure; already-cached teleports keep serving. A gate that trips persistently means the host cannot afford the configured `cacheCap` - lower it or raise `-Xmx`.

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

### 7. [FAILURE_MODES.md](admin/FAILURE_MODES.md)
Catalog of every known failure mode with detection signals and defined system responses.
Companion to the Runbook — use this when you know *what* failed but not *why*.

### 8. [HAZARDS.md](admin/HAZARDS.md)
Hazard register with severity ratings and mitigations for risks inherent to the plugin's design.
Useful for capacity planning and risk assessment before deploying in production.

---

## Also Useful

- [CONCEPTS.md](dev/CONCEPTS.md) — plain-language explanation of how RTP's queue, shapes, and teleport pipeline work. Helpful for understanding *why* certain config values behave the way they do.
- [docs/adr/](adr/README.md) — architecture decision records explaining key design choices and the alternatives that were rejected.
