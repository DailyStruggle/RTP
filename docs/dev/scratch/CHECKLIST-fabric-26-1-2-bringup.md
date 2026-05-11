# Fabric MC 26.1.2 Bring-up Checklist

**Effective Issue (rolling):** Make `rtp-fabric` work end-to-end on the deobfuscated MC 26.1.2 runtime, where `class_NNNN` intermediary aliases (baked into `rtp-fabric-common`'s Loom-remapped bytecode) are not present and any class linkage that resolves them fails with `NoClassDefFoundError`.

**Mode:** `[CODE]`

**Strategy (chosen by user):** Per-version method/factory registration. `rtp-fabric-common` stays NM-type-free at execution paths reachable on 26.1.2, and per-version modules (built with Loom plugins matching their MC's mapping regime) ship typed implementations registered through `FabricVersionAdapter` SPI hooks.

---

## Phase 1 — Startup linkage (DONE)

- [x] 1. Pre-probe `BuiltInRegistries` + intermediary `class_7923` before typed registry walk in `FabricServerAccessor.buildBlockTagSnapshot` — graceful empty snapshot when intermediary is absent.
- [x] 2. Reflection-only fallback in `buildBlockTagSnapshotReflectively` (resolves all NM types from the actual runtime classes of registry instances; only `BuiltInRegistries` loaded by name).
- [x] 3. Defer block-tag snapshot until `SERVER_STARTED` (registries weren't populated during `onInitialize`).
- [x] 4. Defer entire `setupDatabase` + `setupEffects` to `SERVER_STARTED` (was running too early through `Configs.reloadConfigs`).
- [x] 5. Replace `CommandRegistrationCallback.EVENT.register(lambda)` with `Proxy.newProxyInstance` against the reflectively-loaded SAM in `FabricCommandRegistrar` — synthetic lambda no longer pins `class_7157`/`class_5364`.
- [x] 6. Replace typed `instanceof` patterns in `FabricBrigadierSourceBridge` (`CommandSourceStack`, `ServerPlayer`) with reflective `getEntity`/`getUUID` dispatch — `requires()` predicate no longer trips `class_2168`/`class_3222`.

## Phase 2 — Player wrapper per-version (DONE)

- [x] 7. SPI extension on `FabricVersionAdapter`: `createPlayer(Object)` + `rebindPlayer(RTPPlayer, Object)` (default null / no-op).
- [x] 8. `FabricServerAccessor`: switch `playersById`/`playersByName` to `Map<UUID, RTPPlayer>`, route `registerPlayerObject` through adapter, fall back to legacy typed `registerPlayer(ServerPlayer)` for 1.20/1.21.
- [x] 9. New `V26_1_R1FabricRTPPlayer` (in `rtp-fabric-v26_1_R1` module — Loom unobfuscated, no intermediary remap): identity, name, ops.json + fabric-permissions-api perms, system-chat sink, reflective teleport ladder, dimension-key resolution.
- [x] 10. `V26_1_R1FabricVersionAdapter` overrides `createPlayer`/`rebindPlayer` to instantiate `V26_1_R1FabricRTPPlayer` directly.
- [x] 11. Lazy-resolve fallback in `getPlayer(UUID)`/`getPlayer(String)`/`getSender(UUID)` walking `MinecraftServer.getPlayerList()` reflectively when JOIN-event registration hasn't fired yet.
- [x] 12. Build-system fix: post-process v26_1_R1 bytecode into the produced jar via `java.util.zip` after `remapJar`, so Loom's intermediary remap doesn't rewrite Mojang names to `class_NNNN` aliases.

## Phase 3 — World wrapper per-version (IN PROGRESS — current task)

- [x] 13. Persist this checklist to `docs/dev/scratch/CHECKLIST-fabric-26-1-2-bringup.md`.
- [x] 14. Extend `FabricVersionAdapter` SPI: `createWorld(Object) -> RTPWorld<?>` (default null).
- [x] 15. `FabricServerAccessor.registerWorldObject(Object)`: route through `adapter.createWorld(level)` first; fall back to legacy `registerWorld(ServerLevel)` reflectively for 1.20/1.21. Maps widened to `RTPWorld<?>`.
- [x] 16. Slim `V26_1_R1FabricRTPWorld` covering minimum surface for `/rtp` end-to-end (chunk-system stubbed with one-time WARNINGs — Phase 3.5 follow-up):
    - `name()` (reflective `dimension().location().toString()`)
    - `id()` (UUID derived from name hash, matching `FabricRTPWorld`'s convention)
    - `world()` returns the live `ServerLevel` (typed in v26 module — links cleanly)
    - `getMaxHeight()` / `getMinHeight()` (reflective)
    - `getSeed()` (reflective)
    - `isVanilla()` true
    - `isInactive()` (reflective; defensive false on miss)
    - `getChunkAt(cx,cz)` (reflective `getChunk(cx, cz, FULL, true)` on the server thread via `MinecraftServer#submit`, returning a `CompletableFuture<Long>` packed-key)
    - `getChunkAtAsync(cx,cz)` (delegate to `getChunkAt` for now)
    - `setForceLoadedImpl(cx,cz,bool)` (reflective `setChunkForced`)
    - `getServerForceLoadedCount()` (reflective `getForcedChunks().size()`)
    - `getCachedChunk(key)` (return null — kept-cache miss falls back through L2 path)
    - `keepChunkAt(cx,cz)` / `forgetChunkAt(cx,cz)` (no-op for now, log once)
    - `forgetChunks()` (no-op)
    - `getBiome(x,y,z)` (reflective via biome registry)
    - `platform(loc)` (no-op)
    - `save()` (reflective `save(null, false, false)` or no-op)
- [x] 17. `V26_1_R1FabricVersionAdapter.createWorld(Object)` — instantiate `V26_1_R1FabricRTPWorld`.
- [x] 18. Build verified: `:rtp-plugin:remapJar` BUILD SUCCESSFUL, log shows `Merged 5 v26_1_R1 adapter classes` (was 4); extracted jar confirms `V26_1_R1FabricRTPWorld.class` carries Mojang `ServerLevel` name (no `class_3218`).
- [ ] 18b. End-to-end runtime verification on user's 26.1.2 server: `/rtp` no longer NPEs at `RTPCmd.compute:348`. Expect next failure to surface from chunk-system stubs.

## Phase 3.5 — Port chunk-system properly to v26 world (DONE)

Implemented via direct typed Mojang calls in the v26 module (no reflection — Loom 1.15 unobfuscated config means `ServerLevel`/`ChunkAccess`/`ChunkStatus` link cleanly on the deobf 26.1.2 runtime).

- [x] 25. `getChunkAt(cx,cz)` — `MinecraftServer#submit` → `level.getChunk(cx,cz,ChunkStatus.FULL,true)` returning packed-key Long; populates the wrapper cache. S-005: dispatch hops to server thread.
- [x] 26. `getChunkAtAsync(cx,cz)` — single-chunk `ChunkSet` with `done` future completed (mirrors common's pattern; releases dependent CF graphs for GC).
- [x] 27. `setForceLoadedImpl` — `level.setChunkForced(cx,cz,bool)` via `MinecraftServer#submit`.
- [x] 28. `getServerForceLoadedCount` — return our own `numForceLoaded()` (vanilla 26.1.2 has no public per-world accessor for this).
- [x] 29. `getBiome(x,y,z)` — typed `level.getBiome(BlockPos).unwrapKey().identifier()` with registry-reverse-lookup fallback.
- [x] 30. `keepChunkAt`/`forgetChunkAt` — route through inherited `setForceLoaded` ref-counted ticket map; `forgetChunkAt` also drops the cached wrapper.
- [x] 30b. New live-mode `V26_1_R1FabricRTPChunk` mirroring common's `FabricRTPChunk` live-path (block-state lookups, sky-light, surface height, biome). Anvil-mode constructor omitted; tracked in Phase 5/6.
- [x] 30c. SPI extension `createNativeWorldBorder(Object) -> WorldBorder` in `FabricVersionAdapter`; `FabricServerAccessor.createNativeWorldBorder` routes through the adapter first (typed `level.getWorldBorder()` chain), falls back to the legacy `instanceof FabricRTPWorld` path. Resolves the `border == null` PregenTask spam on 26.1.2.

## Phase 4 — Reflective NM-method patches → typed adapter overrides (DONE)

- [x] 19a. SPI extension `getServerThread(Object) -> Thread` (default null) on `FabricVersionAdapter`. v26 adapter overrides with direct `MinecraftServer#getRunningThread()`. `FabricScheduler.serverRunningThread` and `FabricServerAccessor.isPrimaryThread` route through adapter first; reflective `getMethod("getRunningThread")` retained as fallback for adapters that don't override (1.20/1.21 family — those bytecode descriptors link cleanly there).
- [x] 19b. SPI extension `dispatchConsoleCommand(Object, String) -> boolean` (default false). v26 adapter overrides with direct `server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command)`. `FabricServerAccessor.FabricConsoleSender#performCommand` routes through adapter first; reflective `getCommands` / `createCommandSourceStack` / `performPrefixedCommand` lookups retained as fallback.
- [x] 19c. SPI extension `resolveSenderUuid(Object) -> UUID` on `FabricVersionAdapter` (default null). `FabricBrigadierSourceBridge#resolveSenderUuid` routes adapter-first; reflective `getEntity`/simple-name-`ServerPlayer`/`getUUID` walk retained as fallback for adapters that don't override.
- [x] 20. v26_1_R1 typed override — direct `CommandSourceStack#getEntity()` + `instanceof ServerPlayer` + `Entity#getUUID()`. Bytecode lives in the v26 module so its constant pool resolves cleanly on the deobf 26.1.2 runtime.

## Phase 5 — Effects-api per-version (DEFERRED — see investigation notes)

Investigated 2026-05-09 evening. Scope is wider than originally framed: the leak chain reaches into `effects-api/` module, not just `rtp-plugin`/`rtp-fabric-common`. Migration requires adding per-version SPI hooks for the entire effects wiring path and likely mirroring `FabricEffectRuntime` + `FabricEffectsInitializer` into `rtp-fabric-v26_1_R1/`. Both are non-fatal soft-fails today; effects + post-teleport title splash are silently disabled on 26.1.2 but `/rtp` core path is unaffected.

Leak sites (all caught by surrounding try/catch — non-blocking):
- `RTPFabricMod.onInitialize` SERVER_STARTED handler → `FabricEffectsHandler.setupEffects(server)` → `class_7923` (BuiltInRegistries via FabricValueCoercer registry walk).
- `FabricEffectRuntime.bindServer` linkage trips on `class_3222` (ServerPlayer), `class_2596` (Packet), `class_1291` (MobEffect), `class_2400` (ParticleOptions) baked into `effects-api/fabric/*.class` constant pools.
- `RTPFabricMod` post-teleport title/actionbar hook (lines 176+) — `instanceof FabricRTPPlayer fp` + `fp.handle()` returning `ServerPlayer`.
- `V26_1_R1FabricVersionAdapter.installEffectsDispatchers` → `class_2596` (legacy effects-api install path; v26 module's `V26_1_R1FabricEffectDispatchers` references intermediary-leaked types from `effects-api`).

Migration plan (when picked up):
- [x] 21. Add SPI methods to `FabricVersionAdapter`: `installEffectsWiring(Object server) -> boolean`, `dispatchTitle(Object player, String title, String subtitle, int fadeIn, int stay, int fadeOut) -> boolean`, `dispatchActionbar(Object player, String text) -> boolean`. All return `false` by default so 1.20/1.21 fall through to existing typed paths. — appended at `FabricVersionAdapter.java` lines 413-475 (after `resolveSenderUuid`); lint clean; defaults `false` so 1.20/1.21 adapters need no change.
- [ ] 22. **REFRAMED 2026-05-09 evening — superseded by obf/unobf split (option C).** Original plan (mirror typed effects-api/fabric bodies into `rtp-fabric-v26_1_R1/`) was sized at "~2 files, 400-700 LOC". Investigation under item 22 found the leak surface covers the entire `effects-api/fabric/` tree (≥12 files, ~1100-1500 LOC) because `FabricEffectsInitializer.registerAll()` instantiates `FabricSoundEffect`/`FabricParticleEffect`/`FabricTitleEffect`/`FabricPotionEffect` and each of those carries NM-type imports (Holder, BuiltInRegistries, ServerPlayer, ParticleOptions, ClientboundSetTitleTextPacket, …) in its constant pool. A v26-only mirror would duplicate ~95% byte-for-byte against `effects-api/fabric/` and be deleted wholesale when option (C) lands. With MC 26.2 imminent (tomorrow), the cost-benefit on a per-version mirror collapses. **User decision (2026-05-09): pivot to option C — split fabric targets by obfuscation regime via `rtp-fabric-common-unobf`.** Items 22-25 below are deferred pending the C ADRs (see new Phase 5C below). Re-pickup criteria: ADRs approved + `rtp-fabric-common-unobf` Gradle module skeleton landed.
- [ ] 23. (deferred under Phase 5C) `V26_1_R1FabricVersionAdapter` overrides the new SPI methods with direct typed wiring (skip the intermediary-leaked common path entirely on 26.1.2).
- [ ] 24. (deferred under Phase 5C) Refactor `FabricEffectsHandler.setupEffects` and `RTPFabricMod`'s post-teleport title hook to short-circuit when adapter handled it; otherwise fall through to existing typed path.
- [ ] 25. (deferred under Phase 5C) Logged in `POTENTIAL_BUGS.md` already (class_2596 / class_7923 entries) for cross-reference.

Estimated cost (original 22): 800-1500 LOC across 8-12 files in 3 modules — invalidated by investigation; see Phase 5C for new sizing.

## Phase 5C — Fabric obf/unobf module split (NEW — D-005 proposal pending)

Authored 2026-05-09 evening. Triggered by item 22 scope discovery + MC 26.2 imminence. Requires written proposal + ADR(s) before any code per Rule D-005.

Goal: factor a Mojmap-unobfuscated common layer for the deobf MC 26.x runtime family so per-version modules stay thin and the `effects-api/fabric/*` linkage hazard is solved structurally rather than per-version.

Proposed structure (target shape — pending approval):
- **`rtp-fabric-common`** — current module, intermediary-bearing (Loom remap to `class_NNNN`). Continues to serve 1.20 → 1.21.x. Unchanged on the wire from 1.20/1.21's perspective.
- **`rtp-fabric-common-unobf`** *(NEW)* — Loom 1.15 unobfuscated config (mirror of `rtp-fabric-v26_1_R1`'s build.gradle: `net.fabricmc.fabric-loom` plugin id, no `mappings` line, plain `implementation`/`compileOnly`). Carries Mojmap-named bytecode that links cleanly on any deobf 26.x runtime. Hosts the typed mirrors of:
  - effects-api fabric layer (FabricEffectRuntime/FabricEffectsInitializer/FabricValueCoercer/FabricRegistryCompat + 4 LocalEffects + 4 enums)
  - any other cross-26.x typed code currently duplicated in `rtp-fabric-v26_1_R1/`
- **`rtp-fabric-v26_1_R1`** — slims down. Keeps only 26.1-specific deltas (chunk-system shape, ticket-radius API surface, version-specific dispatch overloads).
- **`rtp-fabric-v26_2_R1`** *(future)* — when MC 26.2 lands, sits next to `v26_1_R1` and depends on the same `rtp-fabric-common-unobf`. Only carries 26.2 deltas.

Bootstrap dispatch:
- `RTPFabricMod` already resolves `FabricVersionAdapter` by FQN string at server start (per rtp-fabric-ADR-001). The same string-FQN dispatch can pick a "common-unobf" path on deobf runtimes, falling back to the existing `rtp-fabric-common` path on intermediary-bearing runtimes. No new SPI required beyond item 21's three methods.

Required ADRs:
- `effects-api/docs/adr/effects-api-ADR-NNN-fabric-obf-unobf-split.md` — rationale for splitting the fabric platform layer of effects-api between obf and unobf consumers.
- `rtp-fabric/docs/adr/rtp-fabric-ADR-NNN-obf-unobf-common-split.md` — rationale for the new `rtp-fabric-common-unobf` Gradle module + dispatch policy.

D-005 proposal contents (still drafting):
1. Affected modules: `effects-api`, `rtp-fabric-common` (no API change), `rtp-fabric-common-unobf` (new), `rtp-fabric-v26_1_R1` (slim down), `rtp-plugin/.../RTPFabricMod` (dispatch).
2. Before/after structure: see "Proposed structure" above.
3. REQ refs: S-005 (no chunk I/O on main thread — preserved; nothing in effects path touches chunks), S-006 (API-before-core null guards — unchanged), S-004 (failure surfacing — preserved through the existing dispatcher try-blocks).
4. Risks: Loom config drift between two common modules; delivered-jar bytecode-merge step (mirror of v26_1_R1's `remapJar` post-process) needs extension; cross-module compilation order must be stable in `settings.gradle` composite.
5. Trade-offs: ~1 session of upfront ADR + module wiring vs. ~1100-1500 LOC of throwaway duplicate per deobf MC version.

Open items before implementing 5C:
- [x] 5C.1 Draft `effects-api-ADR-006-fabric-obf-unobf-split.md` and submit for user review. — created at `effects-api/docs/adr/effects-api-ADR-006-fabric-obf-unobf-split.md` (182 lines); status `Proposed (2026-05-09)`; companion `rtp-fabric-ADR-009` referenced as mandatory pair.
- [ ] 5C.2 Draft `rtp-fabric-ADR-NNN-obf-unobf-common-split.md` and submit for user review.
- [ ] 5C.3 Draft Gradle skeleton for `rtp-fabric-common-unobf` (build.gradle + `settings.gradle` line + bytecode-merge step in `:rtp-plugin:remapJar`).
- [ ] 5C.4 Migrate `effects-api/fabric/*` typed bodies into `rtp-fabric-common-unobf` (vs. duplicating — design call inside ADR-NNN).
- [ ] 5C.5 Wire `RTPFabricMod` to dispatch to obf vs. unobf bootstrap by runtime mapping check.
- [ ] 5C.6 Implement Phase 5 items 23/24 against the new common-unobf module (replaces the deferred v26-only version).
- [ ] 5C.7 Cross-reference POTENTIAL_BUGS.md class_2596 / class_7923 entries (item 25) on close.

## Phase 6 — Other version modules (DEFERRED)

- [ ] 23. Decide whether to leave `rtp-fabric-v1_20_R1`, `rtp-fabric-v1_21_R1`, `rtp-fabric-v1_21_R5`, `rtp-fabric-v1_21_R11` falling back to legacy typed `registerWorld(ServerLevel)` (their bytecode links fine on intermediary-bearing runtimes) or port them to the new SPI for consistency.

## Phase 7 — Title/actionbar packet sinks (DEFERRED)

- [ ] 24. Port title/subtitle/actionbar from legacy `FabricRTPPlayer` to `V26_1_R1FabricRTPPlayer`. Currently silent no-op; not a blocker.

---

## Status Log

- 2026-05-09: Checklist persisted; starting Phase 3.
- 2026-05-09: Phase 3.5 complete. `V26_1_R1FabricRTPWorld` rewritten with direct typed chunk-system calls; new `V26_1_R1FabricRTPChunk` for live-mode block queries; new SPI hook `createNativeWorldBorder(Object)` lets v26 supply a real worldborder. Build green; jar contains 6 v26 classes (was 5).
