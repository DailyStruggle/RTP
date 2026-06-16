# Documentation Map

Flat one-line catalog of every normative doc. Cheapest first-fetch for an agent that doesn't yet know which file to open. For task-based routing use [`dev/INDEX.md`](dev/INDEX.md).

## Top-level entry points
- [`FOR_ADDON_DEVELOPERS.md`](FOR_ADDON_DEVELOPERS.md) — router for addon authors (depends only on `rtp-api`).
- [`ADDON_QUICKSTART.md`](ADDON_QUICKSTART.md) — one-page "register a custom shape in ~20 lines" addon tutorial.
- [`FOR_CONTRIBUTORS.md`](FOR_CONTRIBUTORS.md) — router for core contributors.
- [`FOR_SERVER_ADMINS.md`](FOR_SERVER_ADMINS.md) — router to `admin/`.

## Engineering (`dev/`)
- [`dev/INDEX.md`](dev/INDEX.md) — task → file+anchor router.
- [`dev/REQUIREMENTS.md`](dev/REQUIREMENTS.md) — absolute laws (REQ-*, S-00x). Normative.
- [`dev/ARCHITECTURE.md`](dev/ARCHITECTURE.md) — module layout, import boundaries.
- [`dev/DESIGN.md`](dev/DESIGN.md) — threading, `MemoryTracker`, chunk reservation.
- [`dev/GLOSSARY.md`](dev/GLOSSARY.md) — canonical domain terms.
- [`dev/RULES.md`](dev/RULES.md) — requirement/ADR authoring style.
- [`dev/TRACEABILITY.md`](dev/TRACEABILITY.md) — REQ ↔ class ↔ test.
- [`dev/CONCEPTS.md`](dev/CONCEPTS.md) — spiral math and selection concepts.
- [`dev/LESSONS_LEARNED.md`](dev/LESSONS_LEARNED.md) — dated pitfalls.
- [`dev/COVERAGE_PLAN.md`](dev/COVERAGE_PLAN.md) — JaCoCo targets.
- [`dev/MULTI_PLATFORM_PLAN.md`](dev/MULTI_PLATFORM_PLAN.md) — Fabric frontier.
- [`dev/MULTI_SERVER_PLAN.md`](dev/MULTI_SERVER_PLAN.md) — proxy / multi-server (Velocity, BungeeCord) roadmap; D-005 gated.
- [`dev/METRICS_PLAN.md`](dev/METRICS_PLAN.md) — runtime metrics SPI (TPS / MSPT / heap / queue / pipeline); implementation eligible.
- [`dev/ROADMAP.md`](dev/ROADMAP.md) — forward-looking work.
- [`dev/PUBLISHING.md`](dev/PUBLISHING.md) — publishing `rtp-api`/`rtp-core` for out-of-repo addons (JitPack active; Maven Central how-to).
- [`dev/STAKEHOLDERS.md`](dev/STAKEHOLDERS.md) — roles.

## Decisions (`adr/`)
- [`adr/README.md`](adr/README.md) — ADR index.
- [`adr/ADR-TEMPLATE.md`](adr/ADR-TEMPLATE.md) — template.
- `ADR-001` Archimedean spiral 1D mapping.
- `ADR-002` H2/SQLite over flat-file cache.
- `ADR-003` `rtp-plugin` bridge module.
- `ADR-004` Count-Bound TaskPipe on Folia.
- `ADR-005` PaperLib removal.
- `ADR-006` Async queue pre-generation.
- `ADR-007` Per-user isolated queues.
- `ADR-008` Memory tracker / active GC.
- `ADR-009` Configurable spatial distributions.
- `ADR-010` Versioned platform adapter submodules.
- `ADR-011` `rtp-api` separate module.
- `ADR-012` Chunk reservation abstraction.
- `ADR-013` Addons as external Gradle projects.
- `commands-api-ADR-001` Brigadier bridge via `commands-api`.
- `ADR-015` Stale-chunk guard / Count-Bound pipes.
- `ADR-016` Anvil subsystem.
- `ADR-017` Block tags and state predicates in safety lists.
- `ADR-018` `AGENTS.md` public-release structure.
- `ADR-019` Claim plugin integrations folded into plugin.

## Architecture slices (`architecture/`)
- [`architecture/01-teleport-execution-pipeline.md`](architecture/01-teleport-execution-pipeline.md)
- [`architecture/02-budgeted-cache-generator.md`](architecture/02-budgeted-cache-generator.md)
- [`architecture/03-chunk-ticket-lifecycle.md`](architecture/03-chunk-ticket-lifecycle.md)
- [`architecture/04-active-gc-sweep.md`](architecture/04-active-gc-sweep.md)
- [`architecture/05-scan-task-crawler.md`](architecture/05-scan-task-crawler.md)
- [`architecture/06-plugin-setup-lifecycle.md`](architecture/06-plugin-setup-lifecycle.md)
- [`architecture/07-rtp-command-region-selection.md`](architecture/07-rtp-command-region-selection.md)
- [`architecture/08-location-selection-per-attempt.md`](architecture/08-location-selection-per-attempt.md)
- [`architecture/09-configuration-load-and-reload.md`](architecture/09-configuration-load-and-reload.md)
- [`architecture/10-shutdown-and-flush-lifecycle.md`](architecture/10-shutdown-and-flush-lifecycle.md)

## Operator-facing (`admin/`)
- [`admin/proxies/INDEX.md`](admin/proxies/INDEX.md) — proxy-mode admin docs (stub; populated as multi-server plan lands).
- [`admin/QUICK_START.md`](admin/QUICK_START.md)
- [`admin/CONFIGURATION.md`](admin/configuration/CONFIGURATION.md)
- [`admin/COMMANDS.md`](admin/COMMANDS.md)
- [`admin/HAZARDS.md`](admin/HAZARDS.md) — failure modes + mitigations (absorbs `FAILURE_MODES.md`).
- [`admin/FAQ.md`](admin/FAQ.md)
- [`admin/RUNBOOK.md`](admin/RUNBOOK.md)
- [`admin/MIGRATION.md`](admin/MIGRATION.md)
