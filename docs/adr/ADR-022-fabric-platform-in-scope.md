# ADR-022 — Fabric Platform Is In Scope (Single-JAR Multi-Loader Packaging)

**Status:** Accepted
**Date:** 2026-04-30
**Last revised:** 2026-04-30 (packaging decision added)

## Context

RTP has historically targeted Bukkit-derived server software only (Spigot, Paper, Folia), as recorded in `docs/dev/REQUIREMENTS.md §0` and `REQ-RTP-SYS-002`. Fabric work has nonetheless been carried in `docs/dev/MULTI_PLATFORM_PLAN.md` as an "active frontier" with a partial skeleton, and the project's `AGENTS.md` *Current Development Focus* names Fabric as the current priority. This contradiction has been a steady source of confusion for contributors.

Four facts inform this decision:

1. **Abstraction sufficiency.** The April 2026 gap analysis (recorded in `MULTI_PLATFORM_PLAN.md` *What Does NOT Need to Change*) concluded that `RTPServerAccessor`, `RTPWorld`, `RTPPlayer`, `RTPScheduler`, and the `rtp-core` `DatabaseHandler` are sufficient for full Fabric support — the remaining work is implementation in a Fabric adapter, not new `rtp-api` interfaces.
2. **Outstanding gaps are concrete.** Two critical gaps (S-005 violation in `FabricRTPWorld.getChunkAt`, null `getLocationGenerator()` in `FabricServerAccessor`), four high-severity gaps (scheduler, database, permissions, events), and one build-system blocker (Loom integration) are itemized with acceptance gates in `MULTI_PLATFORM_PLAN.md` Phase 2.
3. **Command-system reuse path exists.** ADR-014 already commits to a Brigadier bridge inside `commands-api`, so Fabric does not require duplicating command logic.
4. **Distribution constraint.** RTP ships through a single resource page on BuiltByBit, which awards one primary download per version entry. Shipping two binaries (one Bukkit, one Fabric) splits the release surface, the review thread, and the support workflow. The project's runtime-dispatch pattern in `rtp-plugin` (Spigot vs. Paper vs. Folia adapter selected at startup) already proves the *single binary, multiple runtimes* model is viable for Bukkit-family servers; extending it to Fabric is mechanically the same step at the entry-point layer, with Loom-scoping discipline as the only new engineering risk.

These conditions make Fabric distinct from the legacy-MC question that ADR-021 closed: legacy MC would require backporting modern APIs and reopening previously-closed decisions (PaperLib, ADR-005); Fabric requires only forward implementation against the current abstractions and a packaging convention proven in production by Geyser, LuckPerms, and ViaVersion.

## Decision

### 1. Scope

Fabric is **a first-class, in-scope target platform** for RTP, alongside Spigot, Paper, and Folia. The `rtp-fabric` adapter shall be developed against the existing `rtp-api` and `rtp-core` abstractions without introducing legacy shims and without backporting Fabric-specific patterns into `rtp-core` or `rtp-api`.

This decision **supersedes the relevant clause** of `docs/dev/REQUIREMENTS.md §0 Out of Scope` (the *Non-Bukkit platforms* bullet, only insofar as it names Fabric) and broadens `REQ-RTP-SYS-002` to include Fabric. Forge, NeoForge, and other non-Fabric mod loaders remain out of scope and are deferred to Phase 4 of the multi-platform plan.

### 2. Packaging — Single JAR, Multi-Loader Bootstrap

RTP shall ship as a **single JAR** that loads on both Bukkit-family servers and Fabric servers. This follows the established multi-loader bootstrap pattern used by Geyser, LuckPerms, ViaVersion, and Floodgate.

Concretely:

- `rtp-plugin` becomes the **multi-loader bootstrap module**. It contains two entry-point classes in disjoint packages:
  - `io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin` — extends `JavaPlugin`, declared in `plugin.yml`. Dispatches to `rtp-spigot` / `rtp-paper` / `rtp-folia` via the existing classpath-probe runtime selection.
  - `io.github.dailystruggle.rtp.fabric.RTPFabricMod` — implements `ModInitializer`, declared in `fabric.mod.json`. Dispatches to `rtp-fabric/rtp-fabric-common`.
- The two entry-point classes **shall not reference each other** and **shall not share any reachable class outside `rtp-core` / `rtp-api` / `commands-api` / `effects-api`**. The classloader on each runtime resolves only its own entry point's transitive closure; the other platform's classes are inert bytecode.
- Both `plugin.yml` and `fabric.mod.json` ship at the JAR root. Each loader ignores the metadata file it does not recognize.
- `rtp-fabric/rtp-fabric-common` continues to own all Fabric-platform glue (server accessor, world, player, scheduler, database handler, event bridge, permission resolver). It is consumed by `rtp-plugin` as a project dependency; only the entry-point class lives in `rtp-plugin`.

### 3. Build Discipline

- **Loom application.** `fabric-loom` shall be applied to `rtp-plugin` and to `rtp-fabric/**`. It shall **not** be applied at the root, to `rtp-core`, to `rtp-api`, to `commands-api`, to `effects-api`, or to any Bukkit-family adapter (`rtp-spigot`, `rtp-paper`, `rtp-folia`).
- **Remap scoping.** Loom's `remapJar` task shall include only `io/github/dailystruggle/rtp/fabric/**` and the classpath contribution of `rtp-fabric/rtp-fabric-common`. Bukkit-family classes shall be excluded from remap so they retain Spigot/Paper-mapped bytecode.
- **Mappings.** `loom.officialMojangMappings()` for Fabric classes. Yarn is reserved for re-evaluation if the community requests it.
- **Dual-runtime end-to-end smoke test.** A CI job shall load the produced JAR on both a Paper test server and a Fabric test server and assert that `/rtp` (or the equivalent command tree) executes end-to-end on each. This guards against future Loom regressions in remap scoping, which is the only single-point-of-failure introduced by this packaging. The gate is anchored at **Phase 2 Step H** (stabilization), not Phase 1: until Steps A–G land, `RTPFabricMod.onInitialize()` is a placeholder and there is no Fabric functionality to validate end-to-end, so a Phase 1 anchor would be trivially passing and uninformative. Phase 1 retains structural acceptance gates only (build green, single JAR contains both metadata files, `fabric.mod.json` schema-valid).

### 4. Architectural Invariants Preserved

The following hard lines remain unchanged and shall be enforced by existing ArchUnit guards:

- `rtp-core`, `rtp-api`, `commands-api`, `effects-api` shall contain zero platform imports.
- `rtp-spigot`, `rtp-paper`, `rtp-folia` shall contain Bukkit-family imports only.
- `rtp-fabric/**` shall contain Fabric imports only and shall not import `org.bukkit.*`.
- The `RTPBukkitPlugin` package and the `RTPFabricMod` package within `rtp-plugin` shall not import each other and shall not transitively reach the other platform's classes.

### 5. Implementation Order

The phased implementation order, acceptance gates, and risk mitigations are recorded in `docs/dev/MULTI_PLATFORM_PLAN.md` (Phase 0 through Phase 3). Implementation shall not skip the safety-critical step (S-005 fix in `FabricRTPWorld.getChunkAt`); that step must be the first Fabric code change after Phase 1 build closure.

### 6. Ownership

A **named maintainer** shall own the Fabric adapter end-to-end (build, mappings, CI toolchain, S-00x proofs, ongoing maintenance). Public beta release (Phase 3) is gated on this ownership being recorded in `MULTI_PLATFORM_PLAN.md`.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep Fabric as an "experimental, unsupported frontier" indefinitely | The current half-in-scope status causes contributor confusion and leaves a known S-005 violation unattributed in the requirements. The gap analysis already proved the architecture supports Fabric. |
| Add Fabric *and* Forge to scope simultaneously | Doubles the test matrix and tooling burden while Fabric's own gaps are still open. Forge can be re-evaluated once Fabric is stable (Phase 4). |
| Ship two separate binaries (`rtp-<v>.jar` and `rtp-fabric-<v>.jar`) | Splits the BuiltByBit release surface (one primary download per version entry), the review thread, and the support workflow. Code reuse is unaffected — both binaries would draw from the same `rtp-core` / `rtp-api` source — but the distribution overhead is permanent. The ~1–2 MB of inert Fabric bytecode on Bukkit installs (and vice-versa) is an acceptable cost. |
| Cardboard / Banner-style Bukkit-on-Fabric bootstrap | Pushes the integration burden onto end users and re-introduces the S-005 risks Step A is specifically designed to eliminate natively. RTP would still be a Bukkit plugin pretending to run on Fabric, not a first-class Fabric mod. |
| Adopt Architectury now to anticipate Forge | Adds a heavyweight dependency before its second consumer exists. Architectury also produces N binaries from one source tree, not one binary from many — it solves a different problem. Re-evaluate at Phase 4. |
| Introduce a new `rtp-api` abstraction (e.g., `RTPPermissionProvider`) before adopting Fabric | The April 2026 gap analysis showed no new abstractions are required. Premature abstraction would expand `rtp-api`'s public surface without proven need. The permission-provider abstraction is deliberately deferred until the soft-depend pattern is proven in `rtp-fabric` Step F. |

## Consequences

- **Positive:**
  - Resolves the contradiction between `REQUIREMENTS.md §0` and `MULTI_PLATFORM_PLAN.md` / `AGENTS.md`.
  - Gives Fabric work the same safety guarantees as the Bukkit-family adapters: every step has an acceptance gate against the existing S-00x regression guards.
  - Preserves the "no Bukkit imports in `rtp-core`/`rtp-api`" rule and the "no platform logic in core" rule — Fabric implements interfaces, it does not extend them.
  - One JAR per release: one BuiltByBit version entry, one GitHub release asset, one `rtp test full` invocation per dev-server smoke test.
  - Provides a citable, structured answer for community questions about Fabric support.
- **Negative / Trade-offs:**
  - Expands the supported-platform matrix; CI and release artifacts must include `:rtp-fabric:rtp-fabric-common` and the Fabric smoke-test job.
  - Adds Loom to `rtp-plugin`'s build alongside the existing Shadow plugin. Remap scoping (the include rule above) is the only invariant the build must preserve; Loom version is pinned for this reason.
  - Bukkit-family installs ship with `~1–2 MB` of inert Fabric bytecode (and vice-versa). Acceptable; no class on the foreign code path is ever resolved by the runtime classloader.
  - `REQ-RTP-NF-003` ("the plugin entry point shall not contain business logic") now applies to *each* entry point in `rtp-plugin`, not just one. Each remains a pure dispatcher.
  - Public commitment to maintenance: until a named owner is recorded, beta release is gated.
- **Guardrails preserved:**
  - Do not backport Fabric-specific patterns into `rtp-core` or `rtp-api`.
  - Do not introduce new `rtp-api` interfaces speculatively for Fabric — the gap analysis says none are needed.
  - The `RTPPermissionProvider` abstraction remains deferred until Step F proves the pattern.
  - The two entry-point packages in `rtp-plugin` shall remain disjoint; an ArchUnit rule shall enforce this.

## References

- `docs/dev/REQUIREMENTS.md §0` (scope), `REQ-RTP-SYS-002`, `REQ-RTP-NF-003` (entry-point isolation, applied per-entry-point under this ADR)
- `docs/dev/MULTI_PLATFORM_PLAN.md` — phased plan, abstraction gap summary, acceptance gates, single-JAR bootstrap sub-step in Phase 1
- ADR-005 — PaperLib removal (preserves the "no async-chunk shim" stance Fabric must respect via Step A)
- ADR-010 — Versioned platform adapter submodules (Fabric is initially version-agnostic; version shims deferred)
- ADR-014 — Brigadier bridge via `commands-api` (Fabric reuses, does not duplicate)
- ADR-018 — `AGENTS.md` public-release structure (compatible with single-JAR multi-loader)
- ADR-021 — Legacy Minecraft and Java support out of scope (untouched by this ADR; Fabric ≠ legacy)
- REQ-RTP-S-005 — No synchronous chunk I/O on the main thread (Step A acceptance gate)
- REQ-RTP-S-006 — No undefined behaviour on early API access (Step B acceptance gate)
- External precedent: Geyser, LuckPerms, ViaVersion, Floodgate — single-JAR multi-loader bootstrap in production.
