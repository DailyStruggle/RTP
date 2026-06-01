# NeoForge — Loose Planning Notes

> **Status:** Pre-proposal scratch. **Not** a plan, **not** an ADR, **not** a commitment. These notes exist so we don't have to re-derive the landscape every time NeoForge comes up. Anything here that turns into actual work must go through a D-005 proposal first and (likely) a formal ADR + an entry in `MULTI_PLATFORM_PLAN.md`.
>
> **Current scope reminder:** NeoForge is **out of scope** until `rtp-fabric` stabilizes (Phase 4 per `MULTI_PLATFORM_PLAN.md` / *Current Development Focus*). Do not start implementation work without explicit user approval.

---

## 1. Why NeoForge, and why now (eventually)

- **Audience.** NeoForge is the active successor to legacy Forge and currently the dominant modded-Java platform on 1.20.4+. Large-modpack servers (technical/exploration packs) are exactly the user profile that benefits most from a bounded-distribution RTP.
- **Strategic ordering.** Fabric first (in flight), NeoForge second. Reasons:
  - `rtp-fabric`'s obf/unobf carrier split and Mojmap-decoupling work ([rtp-fabric-ADR-007](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-007-mojmap-name-decoupling.md), [rtp-fabric-ADR-009](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md)) directly informs NeoForge's mappings problem.
  - The anvil-prefilter parity work ([rtp-fabric-ADR-005](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-005-anvil-prefilter-parity.md)) and non-blocking chunk generation ([rtp-fabric-ADR-008](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-008-non-blocking-chunk-generation.md)) are the same problems on NeoForge, with slightly different APIs.
- **Forge (legacy ≤1.20.1).** Out of scope. Sunsetting. If we ever feel pressure here, address via a NeoForge backport, not a parallel module tree.

## 2. API surface differences from Fabric (the part that matters for adapter design)

These are the things that make NeoForge a genuinely distinct adapter, not a Fabric reskin:

- **Event bus.** NeoForge uses its own annotation-driven event bus (`@SubscribeEvent`, `IEventBus`, mod bus vs game bus split). Fabric uses callback registration (`ServerLifecycleEvents.SERVER_STARTED.register(...)`). Wiring point in `FabricVersionAdapter` is not directly portable.
- **Registries.** NeoForge has its own `DeferredRegister<T>` / `Holder<T>` flow on top of the vanilla registry. We don't register much (no blocks/items), so this is mostly relevant for command registration and tag access.
- **Command registration.** NeoForge fires `RegisterCommandsEvent` on the mod bus; Fabric uses `CommandRegistrationCallback`. Both terminate in vanilla `Brigadier`, so `commands-api` Brigadier bridge ([commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md)) is reusable; only the *registration trampoline* differs.
- **Mod metadata.** `META-INF/neoforge.mods.toml` vs `fabric.mod.json`. Trivial.
- **Mappings.** NeoForge is Mojmap-at-runtime (same as modern Fabric without Yarn). The Mojmap-decoupling discipline from `rtp-fabric-ADR-007` carries over: keep `rtp-core` / `rtp-api` free of NM-typed surfaces; isolate NM in a per-version carrier.
- **Threading.** NeoForge servers are single-main-thread, same as vanilla / Fabric (no Folia-style regions). All S-005 reasoning carries over unchanged. **No region-ownership checks needed.** Bukkit's `Entity#teleport` semantics don't exist; we'd be calling `Entity.teleportTo` / the `TeleportTransition` API directly.
- **Chunk tickets.** Same vanilla `DistanceManager` / `ChunkHolder` substrate as Fabric. The non-persistent ticket work ([rtp-fabric-ADR-003](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-003-non-persistent-chunk-tickets.md)) and ticket-radius work ([rtp-fabric-ADR-006](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-006-ticket-radius-and-non-expiring-type.md)) should port near-verbatim; if anything, NeoForge sometimes provides slightly friendlier accessors.

## 3. What we can reuse from `rtp-fabric` (high)

- `rtp-core`, `rtp-api`, `rtp-anvil`, `commands-api`, `effects-api` — all platform-neutral. Reused 1:1.
- The **obf/unobf carrier split** ([rtp-fabric-ADR-009](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md)) pattern. NeoForge is Mojmap-at-runtime for 1.20.4+, so we may not need an obf carrier — but the **structural separation** (NM-typed surfaces in a per-version carrier; deobf top-level) still applies, because runtime class names will shift across MC versions and we don't want `rtp-core` linked to a specific MC rev.
- The **multi-version submodule layout** ([rtp-fabric-ADR-001](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-001-multiversion-submodule-layout.md)): per-MC-version carriers (`rtp-neoforge-v1_20_R1`, `rtp-neoforge-v1_21_R1`, …) dispatched by a `NeoForgeVersionAdapter`. Mirror the rtp-fabric naming.
- **Anvil prefilter** ([rtp-fabric-ADR-005](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-005-anvil-prefilter-parity.md)) — same `.mca` substrate, same code. No work.
- **Non-blocking chunk generation** ([rtp-fabric-ADR-008](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-008-non-blocking-chunk-generation.md)) — same vanilla async generation primitives.
- **Typed block-tag snapshot** ([rtp-fabric-ADR-010](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-010-typed-block-tag-snapshot.md)) — same registry concept; the snapshot reader will adapt cleanly.

## 4. What we cannot reuse (must rewrite per platform)

- The platform entry point (`@Mod`-annotated class vs Fabric's `ModInitializer`).
- Event wiring (mod bus / game bus subscribe vs Fabric callbacks).
- Command registration trampoline.
- Build system: NeoForge uses **NeoGradle** (or **ModDevGradle** / **ModsDotGroovy** depending on version) instead of Fabric Loom. Toolchain choice will need a decision (see open questions).
- The S-006 entry-point guarantees — same shape, different listener type.

## 5. Module layout sketch (illustrative, not approved)

```
rtp-neoforge/
  docs/
    adr/                      # per-subproject ADRs, restart at 001 per AGENTS.md self-update rules
  rtp-neoforge-common/        # Mojmap, no per-version NM (mirrors rtp-fabric-common-unobf in spirit)
  rtp-neoforge-v1_20_R1/      # per-MC carrier (NeoForge supports 1.20.4+; numbering follows existing convention)
  rtp-neoforge-v1_21_R1/
  rtp-neoforge-v1_21_R11/     # if/when we add later MC revs
```

Open: whether to additionally split a `*-unobf` carrier the way Fabric does. NeoForge being Mojmap-at-runtime suggests **no**, but if NeoForge ever ships an SRG/Mercury-mapped intermediate (it has experimented), revisit.

## 6. Threading & S-00x mapping

| Rule  | NeoForge implication                                                                           |
|-------|------------------------------------------------------------------------------------------------|
| S-001 | Same as Fabric — block-safety logic lives in `rtp-core`. No fork-API second-check.            |
| S-002 | Same. `DistanceManager` tickets via the non-persistent ticket pattern (`rtp-fabric-ADR-003`). |
| S-003 | Claim plugins effectively don't exist on NeoForge; the few that do are mod-side (FTB Chunks, etc.). Treat as a reflection-gated hook entry per [ADR-026](../adr/ADR-026-external-hook-api-surface.md) if/when demand appears. |
| S-004 | Same; `FailTypes.nullChunk` attribution path is platform-neutral.                              |
| S-005 | **Critical.** NeoForge single-main-thread; `ServerLevel#getChunk(int, int, ...)` is sync by default. Adapter must route through the same async-generation pattern used in `rtp-fabric`. The Fabric `FabricWorld.getChunkAt` regression (S-005 blocker called out in *Current Development Focus*) must **not** be re-introduced here. |
| S-006 | Same — `IllegalStateException` on early API use, not null/no-op.                              |
| S-007 | Same — `messages.yml` already covers this; no platform work.                                  |

## 7. FTB Chunks / claim mods — soft-depend, not adapter scope

Mod-side land protection (FTB Chunks, OpenPartiesAndClaims, Argonauts, etc.) is the rough equivalent of Bukkit claim plugins. Per [ADR-019](../adr/ADR-019-claim-plugin-integrations-folded-into-plugin.md) and S-003, integrations are folded into the plugin via reflection / soft API. Same playbook for NeoForge: catalog any hook in [`EXTERNAL_HOOKS.md`](EXTERNAL_HOOKS.md) per [ADR-026](../adr/ADR-026-external-hook-api-surface.md). No claim-mod code inside the pipeline.

## 8. Build / toolchain open questions

- **Gradle plugin:** NeoGradle vs ModDevGradle. ModDevGradle is the newer official path (2024+) and is closer to Loom in spirit; lean toward ModDevGradle pending a quick spike.
- **Java level:** NeoForge tracks vanilla — Java 21 for 1.20.5+, Java 21+ for 1.21+. Matches our REQ-RTP-SYS-001 baseline.
- **Run config:** NeoForge dev-launch uses its own runtime; need to verify `run_test` and the existing IntelliJ run-configs can drive a NeoForge dev server. Likely requires a new `.run` XML per per-version submodule.
- **Lite-jar matrix:** Each NeoForge carrier added expands the assembly matrix ([ADR-024](../adr/ADR-024-rtp-lite-assembly-variant.md)). Decide whether NeoForge ships only the full jar initially.

## 9. Risks / gotchas to expect

- **Mappings drift across MC revs.** Mojmap class/field/method names move between 1.20.4 → 1.21 → 1.21.x. Per-version carriers absorb this; do not let names leak into `rtp-core`.
- **NeoForge "mod loading phases."** Some APIs (registries, tags) are only safe to touch after specific phases. The S-006 guarantee is friendly to this — we already throw on early use.
- **AccessTransformers / Mixins.** Hopefully unnecessary. If we need either, that's a red flag — re-examine whether the public API suffices first.
- **CompletableFuture interop with the server thread executor.** NeoForge's `ServerLevel` executor (`server::tell` / `MinecraftServer#submit`) is the safe sink for "back to main thread"; codify in the platform adapter the same way Fabric does.
- **Folia analogue.** None. Do not invent one. If NeoForge ever ships region-threading, treat as a new platform, not a flag.
- **`Entity#teleportTo` semantics change across MC versions** (notably 1.21.x's `TeleportTransition` rework). Carrier per-version absorbs this.

## 10. Minimum-viable scoping (when work actually starts)

In order, do not skip:

1. **D-005 proposal** referencing this notes file.
2. **ADR**: *NeoForge platform in-scope* (mirror [rtp-fabric-ADR-002](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md)), under `rtp-neoforge/docs/adr/rtp-neoforge-ADR-001-…`.
3. **`MULTI_PLATFORM_PLAN.md`** phase rows for NeoForge (Phases 0–4 mirror layout).
4. **Module skeleton** + dev-launch + a single `/rtp` round-trip on the default world before any optimization or anvil work.
5. **REQ-traceable tests** for S-005 and S-006 first (`ReqRtpNeoforgeS005ChunkLoadingTest`, `ReqRtpNeoforgeS006EarlyApiTest`).
6. Only then: anvil parity, ticket parity, the multi-version carrier split.

## 11. Out of scope for these notes (deliberate)

- Forge (legacy) bring-up.
- Sponge.
- Bukkit-via-Mohist / Magma / Arclight (hybrid servers). If demand appears, treat as a `rtp-paper` runtime-compatibility issue, **not** a new adapter — they ship a Paper API surface.
- Cross-server (Velocity/Bungee) interaction. Tracked separately in [`MULTI_SERVER_PLAN.md`](MULTI_SERVER_PLAN.md).
- Whether NeoForge should share a carrier directory tree with Fabric. Tempting; almost certainly a trap (build-system divergence, dependency graph divergence). Notes say no; revisit only with hard evidence.

---

*Last touched: 2026-05-11. Owner: unassigned. Delete or fold into a real plan/ADR once NeoForge work is actually approved.*
