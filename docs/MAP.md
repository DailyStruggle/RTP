# Documentation Map

Flat one-line catalog of every normative doc. Cheapest first-fetch for an agent that doesn't yet know which file to open. For task-based routing use [`dev/INDEX.md`](dev/INDEX.md).

## Top-level entry points
- [`FOR_ADDON_DEVELOPERS.md`](https://github.com/dailystruggle/RTP/blob/V3/docs/FOR_ADDON_DEVELOPERS.md) — router for addon authors (depends only on `rtp-api`); not bundled in the jar, read it in the public repository.
- [`ADDON_QUICKSTART.md`](ADDON_QUICKSTART.md) — one-page "register a custom shape in ~20 lines" addon tutorial.
- [`FOR_CONTRIBUTORS.md`](FOR_CONTRIBUTORS.md) — router for core contributors.
- [`FOR_SERVER_ADMINS.md`](FOR_SERVER_ADMINS.md) — router to `admin/`.

## Engineering (`dev/`)
- [`dev/INDEX.md`](dev/INDEX.md) — task → file+anchor router.
- [`dev/ADDON_MENUS.md`](dev/ADDON_MENUS.md) — build a destination menu/GUI on `rtp-api`.
- [`dev/ADDON_CROSS_SERVER.md`](dev/ADDON_CROSS_SERVER.md) — offer remote (network-mode) destinations from an addon.
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
- [`adr/README.md`](adr/README.md) — ADR index (authoritative, with statuses and subproject ADRs).
- [`adr/ADR-TEMPLATE.md`](adr/ADR-TEMPLATE.md) — template.
- `ADR-000` Development workflow (meta).
- `ADR-001` Archimedean spiral 1D mapping.
- `ADR-002` H2/SQLite over flat-file cache.
- `ADR-003` `rtp-plugin` bridge module.
- `ADR-004` Count-Bound TaskPipe on Folia.
- `ADR-005` PaperLib removal.
- `ADR-006` Async queue pre-generation.
- `ADR-007` Per-user isolated queues (operational details superseded by ADR-043).
- `ADR-008` Memory tracker / active GC.
- `ADR-009` Configurable spatial distributions.
- `ADR-010` Versioned platform adapter submodules.
- `ADR-011` `rtp-api` separate module.
- `ADR-012` Chunk reservation abstraction.
- `ADR-013` Addons as external Gradle projects (partially superseded by ADR-019).
- `ADR-015` Stale-chunk guard / Count-Bound pipes.
- `ADR-016` Anvil subsystem.
- `ADR-017` Block tags and state predicates in safety lists.
- `ADR-018` `AGENTS.md` public-release structure.
- `ADR-019` Claim plugin integrations folded into plugin.
- `ADR-020` Language bootstrap and locale-aware ConfigParser.
- `ADR-021` Legacy MC and Java support out of scope.
- `ADR-022` Shape cache key: seed + config hash.
- `ADR-023` Login reserve cache for join-time RTP.
- `ADR-024` RTP lite assembly variant.
- `ADR-025` Replace SimpleYaml with internal SnakeYAML wrapper (proposed).
- `ADR-026` External hook API surface.
- `ADR-028` L3 backlog cache (`backlogLocations`).
- `ADR-032` Teleport pipeline latency histogram (proposed).
- `ADR-033` NeoForge platform in scope.
- `ADR-034` Memory shape catalog and polygon shape.
- `ADR-035` Interactive menus via written book (proposed).
- `ADR-036` Network mode: multi-server, multi-proxy RTP.
- `ADR-037` Harden RTP config commands.
- `ADR-038` `/rtpadmin` setup wizards (proposed).
- `ADR-039` `/rtpadmin` diagnostic surfaces (proposed).
- `ADR-040` Cross-backend metric time-series publication (proposed).
- `ADR-041` `/rtp config` command and save implementation (proposed).
- `ADR-042` YAML comment preservation (block-only).
- `ADR-043` `rtp.personalqueue` permission semantics (supersedes ADR-007 operational details).
- `ADR-044` Command-tree menu reflector.
- `ADR-045` RTP docs menu consumer.
- `ADR-046` `maps-api` module for runtime cartography.
- `ADR-047` Declarative chart composition bridge.
- `ADR-048` Menu page builders behind `RTPServerAccessor` (proposed).
- `ADR-049` Network-mode platform-neutral lift; `PlayerLifecycleHook` SPI.
- `ADR-050` Concrete menu commands supersede tokens.
- `ADR-051` Two-tier API extension model.
- `ADR-052` Outcome metrics and cause-tagged bad locations.
- `ADR-053` Pipeline latency percentiles and slow-teleport audit.
- `ADR-054` RTPRunnable self-scheduling thread routing.
- `ADR-055` PvP combat gate.
- `ADR-056` Bare `/rtp` root action.
- `ADR-057` Platform-agnostic addon SPI (`RTPAddon` + `AddonRegistry`).
- `ADR-058` Region-specific schematic paste.
- `ADR-059` Relative ground-distance safety predicate (proposed).
- `ADR-060` Emergency-platform block-restoration timeout.
- `ADR-061` Open-core dual licensing.
- `ADR-062` Biome-probability weighting for location selection.
- `ADR-063` Biome-first menu and auto-region by biome.
- `ADR-064` Config-comment format: summary line as menu hover.
- `ADR-065` World-override regions and the `/rtp` world menu.
- `ADR-066` Foreign config importer.
- `ADR-067` Adaptive scan rate and `.mca`-header generation check (proposed).
- `ADR-068` Cross-server persisted teleport limits.
- `ADR-069` Claim integrations extracted to bundled addon.
- `ADR-070` Platform-neutral `/rtp` command root (`CoreRtpRoot`).
- `ADR-071` Config organization and discoverability (proposed).
- `ADR-072` Teleport view-distance clamp and steady restore.
- `ADR-073` Config default inheritance via `@<file>` references (proposed).
- `ADR-074` Operator-facing throughput and cost metrics (proposed).
- `ADR-075` Platform-neutral player-move event SPI.
- `ADR-076` Config folder consolidation.
- Subproject ADRs (`commands-api`, `effects-api`, `maps-api`, `metrics-api`, `rtp-fabric`, `rtp-neoforge`, `rtp-proxy`, addons) — see the *Subproject ADRs* table in [`adr/README.md`](adr/README.md).

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

## Public site - narrative pages (`site/`)
- [`site/README.md`](https://github.com/dailystruggle/RTP/blob/V3/docs/site/README.md) — purpose + conventions for the narrative/non-functional page set (repo-only; not published to the site).
- [`site/index.md`](site/index.md) — home / landing page.
- [`site/why.md`](site/why.md) — motivation and the distribution algorithm.
- [`site/intended-usage.md`](site/intended-usage.md) — the region mental model and recommended workflow.
- [`site/what-not-to-do.md`](site/what-not-to-do.md) — anti-patterns (break-entirely vs. slow).

## Operator-facing (`admin/`)
- [`admin/proxies/INDEX.md`](admin/proxies/INDEX.md) — proxy-mode admin docs (stub; populated as multi-server plan lands).
- [`admin/QUICK_START.md`](admin/QUICK_START.md)
- [`admin/CONFIGURATION.md`](admin/configuration/CONFIGURATION.md)
- [`admin/COMMANDS.md`](admin/COMMANDS.md)
- [`admin/HAZARDS.md`](admin/HAZARDS.md) — failure modes + mitigations (absorbs `FAILURE_MODES.md`).
- [`admin/FAQ.md`](admin/FAQ.md)
- [`admin/RUNBOOK.md`](admin/RUNBOOK.md)
- [`admin/MIGRATION.md`](admin/MIGRATION.md)
