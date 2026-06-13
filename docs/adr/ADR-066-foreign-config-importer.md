# ADR-066 - Foreign Config Importer (`rtp config import <plugin>`)

**Status:** Accepted
**Date:** 2026-06-13

## Context

Operators evaluating RTP against competing random-teleport plugins (BetterRTP, EzRTP, JakesRTP, and others) face a switching cost: every region center, radius, cooldown, economy price, and biome/block filter has to be re-authored by hand in RTP's config tree. RTP currently has **no foreign-config import path**. The only migration machinery that exists is internal: locale-file migration in `ConfigParser` and `teleportData.yml` migration in `RTP.java`. There is nothing that ingests an admin's existing third-party config.

This ADR scopes a **one-shot migration aid**, not a runtime compatibility layer. It is deliberately distinct from the separately-roadmapped *BetterRTP API compatibility shim* (which keeps emulating a competitor's public API at runtime to absorb the inventory-GUI menu ecosystem). The importer reads a competitor's YAML once, writes RTP's own files, and then RTP owns the config; the competitor plugin need not be installed, enabled, or linked.

Two facts establish that the importer is platform-neutral and can therefore live in `rtp-core` rather than a Bukkit-family module:

- **In-house YAML parser.** `RtpYamlConfig` / `RtpYamlSection` (`rtp-core/.../common/configuration/yaml/`) parse and write YAML with no `org.bukkit.*` coupling; `ConfigParser` already uses them. Reading a competitor's YAML off disk needs no platform API.
- **Polygon shape.** `Polygon extends Square` (`rtp-core/.../selectors/memory/shapes/Polygon.java`, ADR-034) can replicate any region outline beyond circle/square/rectangle as an admin-authored vertex list, so a competitor's arbitrary region shapes are translatable.

This crosses module boundaries and introduces a new command, so it is D-005-gated and recorded here before implementation.

## Decision

Add a one-shot, explicit, non-destructive foreign-config importer behind a new command verb, built on a generic source seam.

### Command grammar (parallel to `rtp prefab`)

- `rtp config import` - no source argument: **auto-detect**. Probe the registered sources' expected on-disk locations. If exactly one source's config is present, proceed against it. If zero or more than one are present, list the candidates and require an explicit source (no silent guess).
- `rtp config import <plugin>` - explicit source; **dry-run preview** (default). Lists every key it would map, every approximation, every deferred mapping, and every dropped key. Writes nothing.
- `rtp config import <plugin> confirm` - performs the writes after the preview, mirroring the `prefab` `apply` -> `confirm` two-phase UX. Every RTP file touched is backed up via the existing `prefab` `<file>.yml.bak.<epochMillis>` mechanism, pruned by `performance.yml#prefab.bakRetention`.
- `<plugin>` is a `CommandParameter` whose suggestions come from the registered importer keys (commands-api wire grammar; bare `config` subcommand -> `import` -> typed/literal source; no free positionals).
- Sources at launch: `betterrtp`, `ezrtp`, `jakesrtp`, extensible to any viable competitor.

### Seam shape

- A `ConfigImporter` SPI keyed by source name, declaring: `sourceName()`, a detection probe (expected on-disk path(s)), `preview()` returning a list of mapping outcomes, and `apply()` performing the backed-up writes.
- A registry resolves importers by key and powers auto-detection.
- `BetterRtpConfigImporter` is the first concrete implementation; `ezrtp` / `jakesrtp` follow the same contract.

### Module placement

- **`rtp-core`** holds the `ConfigImporter` SPI + registry, the `RtpYamlConfig`-based readers, and the mapping/translation logic. It is platform-neutral because it only reads foreign YAML off disk and writes RTP's own config through existing core machinery - there is no `org.bukkit.*` dependency.
- The platform adapter / `rtp-plugin` contributes only the thin command-surface binding (locating competitor plugin directories, dispatching the verb) if any platform-specific path resolution is required.

### Translation contract

The importer targets **each competitor's latest config schema**. Translation is honest and lossy: every approximation and every dropped key is logged (S-004-style audit), never silently swallowed. Each mapped key is classified as one of:

- `MAPPED` - clean 1:1 (or near-1:1) translation.
- `APPROXIMATED` - semantics differ; logged with the approximation made.
- `DEFERRED` - recognized, but the RTP target feature has not landed yet (see sequencing rule).
- `DROPPED` - genuinely meaningless under RTP (e.g. internal competitor bookkeeping); reported, not carried.

Known mappings (BetterRTP as the worked example):

| Foreign key | RTP target | Outcome |
|-------------|-----------|---------|
| `Shape: square` / `circle` / `rectangle` | matching RTP shape | MAPPED |
| arbitrary / custom outlines | `Polygon` (ADR-034) vertex list | APPROXIMATED |
| `CenterX` / `CenterZ` | region `center` | MAPPED |
| `MaxRadius` / `MinRadius` | region `radius` / `minRadius` | MAPPED |
| `Cooldown` / `Delay` | `teleportCooldown` / `teleportDelay` (seconds) | MAPPED |
| `MaxAttempts` | `performance.yml#maxAttempts` (default 32) | MAPPED |
| `PreloadRadius` | `performance.yml#viewDistanceSelect` / `viewDistanceTeleport` | MAPPED |
| per-world enable list | worlds / regions | MAPPED |
| `Price` / economy | `economy.yml` | MAPPED |
| biome / block blocklists | `safety.yml` filters | APPROXIMATED (key shape differs) |
| `SetAsRespawn` | persistent spawn-anchor flag (parity feature) | DEFERRED until parity lands |
| `LockAfter` | cooldown usage cap + reset window (parity feature) | DEFERRED until parity lands |

### Sequencing rule for parity-dependent keys

`SetAsRespawn` and `LockAfter` map onto RTP features that are planned for parity but not yet implemented. The importer maps a foreign key only if its RTP target exists:

- While the target feature is absent, the importer reports the key as `DEFERRED` ("recognized, target not yet available"), not `DROPPED`.
- When each parity feature ships, its importer row flips from `DEFERRED` to `MAPPED` in the same change - no permanent unmapped residue.

This lets the seam ship first and the two mappings activate as their targets arrive, without blocking this ADR on the parity work.

### Safety / non-destructiveness

- Explicit command only; never a silent startup auto-overwrite.
- Dry-run preview is the default; writes require `confirm`.
- Every touched RTP file is backed up (reusing the `prefab` backup + retention machinery).
- Refuse to clobber a customized RTP config without explicit confirmation.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Silent auto-import on startup | Destructive and surprising; can overwrite a populated config without consent. Violates the non-destructive constraint. |
| Live competitor API shim (read competitor files / emulate its API at runtime) | Different goal (absorbing the menu-plugin ecosystem), heavier, and a permanent runtime coupling. Tracked as its own ROADMAP item; this importer is a one-shot aid. |
| Importer in `rtp-plugin` / an addon | Unnecessary now that file-only reading + the in-house YAML parser prove platform-neutrality. `rtp-core` keeps a single implementation shared across every platform. |
| BetterRTP-only, no generic seam | Operators migrate from several plugins; a per-source seam (EzRTP, JakesRTP, ...) costs little extra and avoids a rewrite for the second source. |
| Drop unsupported keys silently | Dishonest about lossiness; operators must know what was not carried over. Hence the logged `APPROXIMATED` / `DEFERRED` / `DROPPED` classification. |

## Consequences

- **Positive:** One cross-platform importer serves all competitors; materially lowers the switching cost; reuses the `prefab` backup machinery and the `Polygon` shape; no runtime dependency on any competitor plugin; honest, auditable translation.
- **Negative / Trade-offs:** The two parity-dependent keys cannot transfer until `SetAsRespawn` / `LockAfter` land. Auto-detection needs a clear "pick one" UX when multiple sources are present.
- **Schema drift is a non-issue in practice.** The "track each competitor's evolving schema" concern is largely moot for the launch sources: BetterRTP and EzRTP have not seen a release in years, so their config schemas are effectively frozen and the importer can pin a stable mapping. EzRTP is the only mild caveat (it models faction/claim-anchored centers differently), but its schema is likewise dormant, so a one-time mapping is sufficient.

## References

- ROADMAP: "BetterRTP config importer (one-shot migration aid)" and the parity items "cooldown usage cap (`LockAfter`)" and "persist RTP destination as a permanent spawn anchor (`SetAsRespawn`)" in [`docs/dev/ROADMAP.md`](../dev/ROADMAP.md).
- In-house YAML parser: `RtpYamlConfig` / `RtpYamlSection` (`rtp-core/.../common/configuration/yaml/`); see [ADR-025](ADR-025-replace-simpleyaml-with-internal-snakeyaml-wrapper.md).
- Region shapes / Polygon: [ADR-034](ADR-034-memory-shape-catalog.md).
- Backup + two-phase `apply`/`confirm` UX prior art: the `rtp prefab` flow and `performance.yml#prefab.bakRetention`.
- Distinct runtime shim: the "BetterRTP API compatibility shim" ROADMAP item; external-hook policy [ADR-026](ADR-026-external-hook-api-surface.md).
- Process: D-005 (Propose Before Implementation).
