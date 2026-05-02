# CHECKLIST — Fabric multi-version restructure

Effective Issue: split `rtp-fabric` into per-MC submodules (`rtp-fabric-<ver>`) mirroring the Bukkit-family pattern, fixing the runtime `NoClassDefFoundError: net.minecraft.class_2561` and supporting MC 1.20 → 26.2.
Mode: [CODE]
User-approved scope: skeleton-first; fully implement `26_2`; other 9 versions are fail-loud skeletons; ADR-022 rewritten in place; 10 modules with underscored names; Loom matched per MC line.

Module set (10):
- 1_20, 1_21, 1_21_1, 1_21_2, 1_21_4, 1_21_5, 1_21_8, 1_21_11, 26_1, 26_2

- [x] 1. Rewrite `docs/adr/ADR-022-fabric-platform-in-scope.md` in place — replace single-Fabric-module assumption with multi-version structure mirroring Bukkit-family. **DONE 2026-05-01** — see `docs/adr/ADR-022-fabric-platform-in-scope.md` (Last revised: 2026-05-01).
- [ ] 2. Refactor `rtp-fabric/rtp-fabric-common` to abstract-only surface:
  - [ ] 2a. Identify MC-mapping-touching files and stage them for per-version move.
  - [ ] 2b. Promote `FabricServerAccessor` → `AbstractFabricServerAccessor` (abstract methods for biome resolver, `Component` send, `ServerLevel`/`ServerPlayer` access, world-border native wrap, command-source UUID resolution).
  - [ ] 2c. Promote `FabricEventBridge` / `FabricRTPWorld` / `FabricRTPPlayer` similarly where MC-symbol divergence forces it; keep `FabricScheduler` in common (only `MinecraftServer.execute`).
  - [ ] 2d. Move existing `rtp-fabric-common/src/test/java` tests into `rtp-fabric-1_21_1` (the version they were pinned against).
- [ ] 3. Scaffold 10 per-version modules:
  - [ ] 3a. Create directory + `build.gradle` for each (Loom version + Mojmap + Fabric API pinned per MC line).
  - [ ] 3b. Add fail-loud `FabricServerAccessor<ver>` (extends abstract, throws `UnsupportedOperationException` everywhere except a `getMinecraftVersion()` discriminator).
  - [ ] 3c. Wire all 10 into `settings.gradle`.
  - [ ] 3d. Wire all 10 into `rtp-plugin/build.gradle` dependencies.
- [ ] 4. Fully implement `rtp-fabric-26_2`:
  - [ ] 4a. Concrete `FabricServerAccessor26_2`, `FabricRTPWorld26_2`, `FabricRTPPlayer26_2`, `FabricEventBridge26_2`, `RTPCmdFabric26_2` — all the MC-symbol-touching code currently in common, retargeted to MC 26.2 mappings.
- [ ] 5. Version dispatch in `RTPFabricMod#onInitialize`: `SharedConstants.getCurrentVersion().getName()` → `Class.forName` to instantiate the right `FabricServerAccessor<ver>`. Identical pattern to `RTPBukkitPlugin` provider selection.
- [ ] 6. Build: `.\gradlew :rtp-plugin:remapJar` + `:rtp-plugin:shadowJar` succeeds for all 10 modules.
- [ ] 7. Verify `26_2` deployment works on a 26.2 Fabric server (smoke instructions in submit; no live runtime in this session).
- [ ] 8. Cleanup: delete this checklist after submit.
