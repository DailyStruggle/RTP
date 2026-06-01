# Start Here — Server Administrators

**Current Plugin Version:** `3.0.0-beta.1`

This page guides server operators through the RTP documentation in the recommended reading order.
If you install, configure, or maintain RTP on a Bukkit/Spigot/Paper/Folia server, this is your entry point.

---

## Recommended Reading Order

### 1. [QUICK_START.md](admin/QUICK_START.md)
Get the plugin installed and your first region running in under 10 minutes.
Covers installation, the default region, adding a nether region, and essential permissions.

### 2. [COMMANDS.md](admin/COMMANDS.md)
Every `/rtp` subcommand, parameter, and permission node with examples.
Reference this any time you are unsure of command syntax or available options.

### 3. [CONFIGURATION.md](admin/CONFIGURATION.md)
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
