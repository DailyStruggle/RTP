# effects-api-ADR-006 — Fabric obf/unobf split: relocate `effectsapi/fabric/*` to a Mojmap-unobfuscated common layer

- **Status:** Accepted (2026-05-09); section *`effects-api/build.gradle` change* and section *Implementation checklist* step 2–4 amended in-place 2026-05-09 to reflect the **nested sibling Gradle module** mechanism (`effects-api/effects-api-fabric-unobf/`) instead of a second source set inside `effects-api`. See *Amendment 2026-05-09 — sibling module, not source set* below for rationale.
- **Supersedes:** —
- **Superseded by:** —
- **Related:**
  - `effects-api-ADR-003-platform-split-bukkit-fabric.md` — established the in-module `common/ + bukkit/ + fabric/` split. This ADR refines the `fabric/` half **without** touching `common/` or `bukkit/`.
  - `effects-api-ADR-004-value-coercer-spi.md` — `FabricValueCoercer` is one of the leak sites this ADR moves.
  - `rtp-fabric-ADR-001-multiversion-submodule-layout.md` — multi-MC submodule pattern; this ADR adds an obf/unobf axis orthogonal to the per-MC axis.
  - `rtp-fabric-ADR-002-platform-in-scope.md section 4 Build Discipline` — the Loom allow-list. This ADR keeps `effects-api`'s existing Loom plugin (granted by ADR-003) but narrows what `effectsapi/fabric/*` is responsible for.
  - `rtp-fabric-ADR-007-mojmap-name-decoupling.md` — rationale for keeping `net.minecraft.*` calls behind a thin SPI; this ADR is the concrete consequence on the deobf MC 26.x runtime family.
  - **Companion (mandatory pair):** `platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md` — the corresponding Gradle/module decision in `platforms/rtp-fabric/`. Both ADRs must be accepted together.
  - `docs/dev/scratch/CHECKLIST-fabric-26-1-2-bringup.md` Phase 5 / Phase 5C — the bring-up work this ADR unblocks.
  - `.junie/AGENTS.md` *Architecture Boundaries*, *Pre-Flight Checklist*, *Propose Before Implementation (Rule D-005)*.

---

## Context

`effects-api-ADR-003` introduced an in-module `common/ + bukkit/ + fabric/` split and added the `fabric-loom` plugin to `effects-api/build.gradle` so the `fabric/` subpackage could resolve `net.minecraft.*` at compile time. The `fabric/` subpackage is currently compiled against **intermediary** mappings (Loom default for the 1.20/1.21 family that `rtp-fabric-common` targets). Loom rewrites every `net.minecraft.*` reference in the resulting bytecode to `net.minecraft.class_NNNN` form so that, at runtime, the Fabric loader's intermediary→named remap can re-link those references against the running MC version's mojmap (or yarn) names.

That contract holds on every MC version whose Fabric loader injects an intermediary→runtime remap step. It **does not** hold on a deobfuscated runtime (a Fabric server jar that is itself already in mojmap form, e.g. the dev/deobf builds the rtp team is testing for MC 26.1.2). On a deobf runtime, `class_NNNN` constant-pool entries point at classes that simply do not exist; the JVM throws `NoClassDefFoundError` at first link.

The `rtp-fabric-v26_1_R1` submodule already documents and works around this by configuring Loom 1.15 in **unobfuscated** mode (`rtp-fabric-ADR-007`), so its bytecode references mojmap names directly and links cleanly on a deobf 26.x runtime. But `effectsapi/fabric/*` is compiled by the `effects-api` module under intermediary Loom and is on the classpath of every Fabric runtime, including deobf 26.x. The 26.1.2 bring-up checklist (Phase 5, item 22) confirms the linkage hazard:

- `FabricEffectRuntime.bindServer(server)` — `class_3222` (`ServerPlayer`), `class_2596` (`Packet`), `class_1291` (`MobEffect`), `class_2400` (`ParticleOptions`).
- `FabricValueCoercer` registry walk — `class_7923` (`BuiltInRegistries`).
- `FabricEffectsInitializer.registerAll()` instantiates the four concrete effect classes; each of `FabricSoundEffect`, `FabricParticleEffect`, `FabricTitleEffect`, `FabricPotionEffect` independently leaks its own NM imports (`Holder`, `BuiltInRegistries`, `SoundEvent`, `SoundSource`, `ServerLevel`, `ParticleOptions`, `ParticleType`, `ClientboundSetTitleTextPacket`, `ClientboundSetSubtitleTextPacket`, `ClientboundSetTitlesAnimationPacket`, …).

Net effect on deobf MC 26.1.2 today: `RTPFabricMod.onInitialize` swallows the link error during `SERVER_STARTED → FabricEffectsHandler.setupEffects(server)` and `/rtp` continues to work, but every `rtp.effect.*` lifecycle hook silently no-ops, and the post-teleport title/actionbar splash never reaches the client.

### What we considered first, and why it was discarded

The Phase 5 checklist's original framing of item 22 was "mirror `FabricEffectRuntime` + `FabricEffectsInitializer` typed bodies into `rtp-fabric-v26_1_R1/`". Investigation under that item established that the leak surface covers the **entire** `effectsapi/fabric/` tree (~12 files, ~1100–1500 LOC), because every concrete effect class is independently NM-typed and `FabricEffectsInitializer.registerAll()` instantiates them all. A v26-only mirror would duplicate ~95% byte-for-byte against `effectsapi/fabric/` and be deleted wholesale once a structural fix landed.

MC 26.2 is expected within ~24 h of this ADR's drafting. Any per-version mirror would have to be re-mirrored for 26.2 (and 26.3, and onward) until a structural fix arrives, paying the duplicate cost N times. The structural fix — split the fabric platform layer of `effects-api` between obf and unobf consumers — is therefore strictly cheaper from N=2 onward, and the unobf carrier can be reused by the corresponding `rtp-fabric` module split (`rtp-fabric-ADR-009`).

### Constraint inherited from ADR-003

> "try not to create more root level directories, we can do this within effects-api like we did with commands-api"

This ADR honours that constraint: the split is **internal to the `effects-api/` subtree** and adds no new root-level Gradle modules. Per the 2026-05-09 amendment below, the unobf carrier lives in a *nested* sibling Gradle module `effects-api/effects-api-fabric-unobf/` — exactly mirroring the `platforms/rtp-fabric/rtp-fabric-common-unobf/` precedent established by `rtp-fabric-ADR-009`. No new top-level root directory is introduced; from the repo-root perspective the only change is one new nested module under the existing `effects-api/` directory.

## Decision

Split `effectsapi/fabric/*` along the obf/unobf axis by relocating its current contents to a new sibling subpackage compiled under unobfuscated Loom, and replacing the existing intermediary-Loom subpackage with a thin obf-runtime adapter that delegates to it through the existing `EffectRuntime` SPI from ADR-003.

### Target package shape

Per the 2026-05-09 amendment, the unobf carrier lives in a nested sibling Gradle module rather than a sibling source set. The package layout is otherwise unchanged from the original decision:

```
effects-api/                                       (existing module — KEPT, intermediary-Loom)
  build.gradle                                     (UNCHANGED w.r.t. Loom config; only adds a
                                                    runtime-only `compileOnly project(':effects-api:effects-api-fabric-unobf')`
                                                    edge IF effects-api itself ever needs to name the unobf carrier — it does not at landing.)
  src/main/java/io/github/dailystruggle/effectsapi/
    common/                                        ← UNCHANGED — platform-neutral SPI + base types (ADR-003)
    bukkit/                                        ← UNCHANGED (ADR-003)
    fabric/                                        ← THINNED — obf-runtime adapter only
      FabricEffectRuntime.java                     (kept; intermediary-Loom-compiled; the on-classpath
                                                    surface for 1.20 / 1.21.x runtimes that DO have an
                                                    intermediary→named remap step. Implementation
                                                    becomes a thin `EffectRuntime` adapter that calls
                                                    the same NM types it does today, but its
                                                    constant-pool entries are intermediary class_NNNN
                                                    refs that the Fabric loader rewrites at runtime.)
      // (initializer + value coercer + concrete effects move to the unobf sibling module below)
  effects-api-fabric-unobf/                        ← NEW — nested sibling Gradle module, unobfuscated 1.15 Loom
    build.gradle                                   (mirrors rtp-fabric-v26_1_R1 / rtp-fabric-common-unobf:
                                                    `id 'net.fabricmc.fabric-loom'`, MC 26.1.2, no `mappings`
                                                    line, Java 25 toolchain. See section build wiring below.)
    src/main/java/io/github/dailystruggle/effectsapi/fabric_unobf/
      FabricEffectRuntimeUnobf.java                (mirror of fabric/FabricEffectRuntime — same source
                                                    body, compiled under unobfuscated Loom so the NM
                                                    constant-pool refs survive on deobf 26.x)
      FabricEffectsInitializer.java                (moved from effects-api/.../fabric/)
      FabricValueCoercer.java                      (moved from effects-api/.../fabric/)
      FabricRegistryCompat.java                    (moved from effects-api/.../fabric/)
      LocalEffects/FabricSoundEffect.java          (moved from effects-api/.../fabric/)
      LocalEffects/FabricParticleEffect.java       (moved from effects-api/.../fabric/)
      LocalEffects/FabricTitleEffect.java          (moved from effects-api/.../fabric/)
      LocalEffects/FabricPotionEffect.java         (moved from effects-api/.../fabric/)
      LocalEffects/enums/FabricSoundKeys.java      (moved from effects-api/.../fabric/)
      LocalEffects/enums/FabricParticleKeys.java   (moved from effects-api/.../fabric/)
      LocalEffects/enums/FabricTitleKeys.java      (moved from effects-api/.../fabric/)
      LocalEffects/enums/FabricPotionKeys.java     (moved from effects-api/.../fabric/)
```

The Java package FQN of the relocated classes (`io.github.dailystruggle.effectsapi.fabric_unobf.…`) is identical regardless of which Gradle module compiles them — consumers (e.g. `rtp-fabric-common-unobf`) name the relocated symbols by their package FQN, not by their host module.

The two subpackages share **source-level** intent (every type in `fabric_unobf/` has the same source body its `fabric/` predecessor had at the time of relocation) but differ in their **bytecode** form because they are compiled under different Loom configurations:

| Carrier | Host module | Loom config | Constant-pool form | Loads on |
|---|---|---|---|---|
| `effectsapi.fabric.*`        | `effects-api`                     | intermediary (existing ADR-003 setup)                     | `net/minecraft/class_NNNN`                             | 1.20 / 1.21.x runtimes; **fails** on deobf 26.x |
| `effectsapi.fabric_unobf.*`  | `effects-api/effects-api-fabric-unobf` | unobfuscated 1.15 (mirrors `rtp-fabric-v26_1_R1` / `rtp-fabric-common-unobf`) | mojmap (`net/minecraft/server/level/ServerPlayer`, …) | deobf 26.x runtimes; **fails** on 1.20 / 1.21.x intermediary runtimes |

Each carrier is on the classpath of every Fabric runtime; correctness comes from **only naming the appropriate carrier from runtime-selected dispatch code**, never from both at once. Naming a class triggers loading; we must keep the unsafe carrier name out of any code path the runtime actually executes on the wrong side.

### Dispatch policy

`RTPFabricMod` already resolves a per-version `FabricVersionAdapter` by FQN string at server start (per `rtp-fabric-ADR-001`). The same string-FQN dispatch picks the correct effects carrier at runtime:

```
RTPFabricMod.onInitialize
  └─ resolve FabricVersionAdapter for the running MC version
       ├─ adapter.installEffectsWiring(server) returns true
       │     → adapter chose its own carrier (e.g. V26_1_R1FabricVersionAdapter
       │       names FabricEffectRuntimeUnobf via the rtp-fabric-common-unobf
       │       module from ADR-009)
       │     → fabric/ carrier is never named on this JVM
       └─ adapter.installEffectsWiring(server) returns false
             → fall through to FabricEffectsHandler.setupEffects(server),
               which names fabric/FabricEffectRuntime (intermediary form)
             → fabric_unobf/ carrier is never named on this JVM
```

The three SPI methods added in Phase 5 item 21 (`installEffectsWiring`, `dispatchTitle`, `dispatchActionbar` on `FabricVersionAdapter`) are the single chokepoint. The unobf adapter named on a 1.20/1.21 runtime is only reachable via per-version adapters that themselves don't load on those runtimes (because they're compiled in `rtp-fabric-v26_1_R1`/`v26_2_R1`/…, which are absent from the runtime classpath of 1.20/1.21 servers — see `rtp-fabric-ADR-009`). The obf adapter named on a 26.x deobf runtime is only reachable when the v26 version-adapter returns `false` from `installEffectsWiring`, which it never will once ADR-009 lands.

### Amendment 2026-05-09 — sibling module, not source set

The original draft proposed a `fabricUnobf` *source set* inside `effects-api/build.gradle`. Implementation on 2026-05-09 disproved that approach: `fabric-loom` owns the project's MC + mappings configuration at the project level (single `loom` extension, single `minecraft "com.mojang:minecraft:…"` coordinate, single `mappings` mode). Two source sets in the same Loom-configured project share that one regime; there is no supported Loom 1.15 facility to compile `main/` under intermediary mappings *and* `fabricUnobf/` under unobfuscated mappings within the same Gradle invocation. Attempting it either silently compiles both source sets under intermediary (defeating the entire split) or fails Loom configuration outright.

The structurally correct mechanism is the **same precedent `rtp-fabric-ADR-009` set in lockstep**: a separate Gradle module with its own Loom plugin instance, its own MC pin, and no `mappings` line. To honour ADR-003's "no new root directories" constraint, the new module is *nested* under `effects-api/` rather than promoted to a top-level repo-root sibling. Same nesting pattern as `platforms/rtp-fabric/rtp-fabric-common-unobf/`. Net repo-root delta: zero new top-level directories.

### `effects-api/effects-api-fabric-unobf/build.gradle` shape

Mirrors `platforms/rtp-fabric/rtp-fabric-common-unobf/build.gradle` (and therefore `platforms/rtp-fabric/rtp-fabric-v26_1_R1/build.gradle`) including the **Java 25** toolchain pin, for the same reason recorded in `rtp-fabric-ADR-009`: `com.mojang:minecraft:26.1.2` is Java-25 bytecode and Loom reads it at *configuration* time, so Java 21 fails with `UnsupportedClassVersionError` before any source compiles.

```groovy
plugins {
    id 'net.fabricmc.fabric-loom'
}

ext {
    minecraftVersion       = '26.1.2'
    loaderVersion          = '0.18.4'
    fabricApiVersion       = '0.143.5+26.1'
    permissionsApiVersion  = '0.3.1'
}

java {
    toolchain {
        // Java 25 — required because com.mojang:minecraft:26.1.2 is Java-25
        // bytecode and Loom reads it at configuration time.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    minecraft "com.mojang:minecraft:${minecraftVersion}"
    // No `mappings` line — 26.1+ ships deobfuscated.
    implementation "net.fabricmc:fabric-loader:${loaderVersion}"
    implementation "net.fabricmc.fabric-api:fabric-api:${fabricApiVersion}"

    // The unobf carrier names types from effects-api's common/ SPI
    // (Effect, EffectFactory, EffectRuntime). compileOnly keeps it off
    // this module's runtime classpath; consumers (rtp-fabric-common-unobf)
    // bring effects-api in via their own classpath.
    compileOnly project(':effects-api')
    compileOnly project(':rtp-api')
    compileOnly "me.lucko:fabric-permissions-api:${permissionsApiVersion}"
}
```

The Loom plugin pin, MC version, loader version, and fabric-api version are intentionally identical to `rtp-fabric-v26_1_R1` and `rtp-fabric-common-unobf`. Drift between the three is the same maintenance hazard already called out in `rtp-fabric-ADR-009` *Negative / costs*; the same `gradle.properties` follow-up will share pins across all three.

### `settings.gradle` change

One line added (alphabetically adjacent to the existing `:effects-api` include):

```
include ':effects-api:effects-api-fabric-unobf'
```

### `effects-api/build.gradle` — unchanged

`effects-api`'s own Loom configuration is **not** modified by this ADR. Its existing intermediary-mappings Loom setup (`loom.officialMojangMappings()`, MC 1.21.1) continues to compile `effectsapi/common/`, `effectsapi/bukkit/`, and the thinned `effectsapi/fabric/` carrier. No `compileOnly project(':effects-api:effects-api-fabric-unobf')` edge is added — `effects-api` does not name the unobf carrier directly; only `rtp-fabric-common-unobf` does.

### What this ADR explicitly does not change

- `effectsapi/common/*` — untouched. The `EffectRuntime` SPI surface from ADR-003 is sufficient.
- `effectsapi/bukkit/*` — untouched.
- The `rtp.effect.*` permission lifecycle, the seven hook points, the `Effect<T>` base class, `EffectFactory.buildEffects` — untouched.
- `rtp-plugin/.../bukkit/effects/BukkitEffectsHandler` — untouched.
- `addons/RTP_Glide` — untouched.
- The intermediary `fabric/FabricEffectRuntime` carrier is *kept*, not deleted. Removing it would break the 1.20 / 1.21.x classpath story; we only thin its responsibilities by moving the initializer/coercer/concrete-effect siblings out.

## Consequences

### Positive

- Resolves the Phase 5 / item 22 linkage hazard structurally for every deobf 26.x runtime (26.1.2 today, 26.2 tomorrow, 26.3+ onward), once.
- No throwaway code: every line written under `fabric_unobf/` survives the next MC bump because nothing in it is version-specific.
- Honours ADR-003's "no new root directories" constraint — the split is internal to `effects-api/`.
- Pairs cleanly with `rtp-fabric-ADR-009`'s `rtp-fabric-common-unobf` module: the unobf carrier in `effects-api/` is consumed by the unobf carrier in `platforms/rtp-fabric/`, and per-version v26.x modules thin out to version-specific deltas only.
- Aligns with S-005 (no chunk I/O on main thread — preserved; nothing in the effects path touches chunks), S-006 (API-before-core null guards — unchanged; the SPI fall-through path in ADR-003 already throws `IllegalStateException` when called before core loads), and S-004 (failure surfacing — preserved through the existing dispatcher try-blocks; no silent swallow added).

### Negative / costs

- One-time relocation: ~12 files move from `effectsapi/fabric/` to `effectsapi/fabric_unobf/`, plus one new file (`FabricEffectRuntimeUnobf` — the mojmap-bytecode mirror of `FabricEffectRuntime`). The source bodies are identical at relocation time; subsequent edits must be made in lockstep until/unless a future ADR collapses the two carriers behind a shared common ancestor. CHANGELOG-flagged.
- One new nested Gradle module (`effects-api/effects-api-fabric-unobf`) and the corresponding `settings.gradle` include. Build-graph leaf-count grows by one. The new module uses the same Java 25 + Loom 1.15 toolchain as `rtp-fabric-v26_1_R1` and `rtp-fabric-common-unobf`; contributors without JDK 25 will see this module fail toolchain provisioning (the rest of `effects-api` remains on Java 21). Loom config drift between this module and the two existing unobf modules is a maintenance hazard; mitigation is the shared `gradle.properties`/`versions.gradle` follow-up already recorded in `rtp-fabric-ADR-009` *Negative / costs*.
- The `effects-api` jar now contains two carriers for the fabric platform. This is an allowed jar-content change under `rtp-fabric-ADR-002 section 4 Build Discipline`'s existing exception for `rtp-plugin` and (post-ADR-003) `effects-api`. No third-party addon's classpath grows by more than the difference between one and two compiled subpackages; addons that did `import io.github.dailystruggle.effectsapi.fabric.*` continue to work — the public-ish symbols (concrete effect names) move under `fabric_unobf/`, but no in-tree addon imports them today (verified for ADR-003) and a CHANGELOG migration note covers out-of-tree consumers.
- The dispatch policy relies on the runtime never naming the wrong carrier. A regression test (bytecode/import scan, mirror of the `EffectsApiCommonNoPlatformImportsTest` proposed in ADR-003) is required to assert that no class under `effectsapi/common/*` or `effectsapi/bukkit/*` references either fabric carrier, and that nothing under `effectsapi/fabric/` references `effectsapi/fabric_unobf/*` or vice versa.

### Out of scope (separate follow-ups)

- Collapsing `fabric/` and `fabric_unobf/` behind a shared common ancestor (e.g. extracting the source bodies to a `*.java.in` template and generating both subpackages from it). Considered, deferred: the duplication is small, low-churn, and the readability cost of source generation outweighs the maintenance savings until/unless a third Loom regime appears. Re-evaluate when MC introduces a fourth mapping family.
- Same-shape obf/unobf split for `commands-api/brigadier/*`. Out of scope here; record under `commands-api/docs/adr/` if/when a deobf 26.x runtime exposes the same hazard there.
- Backporting the unobf carrier to provide deobf-runtime support for 1.20 / 1.21.x dev environments. Not a requirement today; the unobf carrier targets the deobf 26.x family only.

## Implementation checklist (will live under Phase 5C of `docs/dev/scratch/CHECKLIST-fabric-26-1-2-bringup.md` once approved)

1. Land **the companion ADR** `platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md` first (or in lockstep). This ADR is meaningless without a consumer — `rtp-fabric-common-unobf` is the consumer. *(Done 2026-05-09.)*
2. Add `':effects-api:effects-api-fabric-unobf'` to `settings.gradle`. Create `effects-api/effects-api-fabric-unobf/build.gradle` per the *build.gradle shape* section above (unobfuscated 1.15 Loom, Java 25 toolchain, no `mappings` line). Confirm `.\gradlew :effects-api:effects-api-fabric-unobf:build` is BUILD SUCCESSFUL on an empty source tree.
3. Relocate `FabricEffectsInitializer`, `FabricValueCoercer`, `FabricRegistryCompat`, and the four `LocalEffects/Fabric*Effect` classes (plus their four enum siblings) from `effects-api/src/main/java/.../effectsapi/fabric/` to `effects-api/effects-api-fabric-unobf/src/main/java/.../effectsapi/fabric_unobf/`. Java `package` declaration updated to `io.github.dailystruggle.effectsapi.fabric_unobf[.LocalEffects[.enums]]`; source body otherwise unchanged at relocation time.
4. Add `FabricEffectRuntimeUnobf` under `effectsapi/fabric_unobf/` (in the new nested module) — same source body as the existing `FabricEffectRuntime`, package declaration changed, compiled under the unobf module so its NM constant-pool refs survive on deobf 26.x.
5. Keep `effectsapi/fabric/FabricEffectRuntime` as the obf-runtime carrier; thin it to the minimum required for `FabricEffectsHandler.setupEffects(server)` to keep working on 1.20 / 1.21.x. (Per-effect classes move out; the obf carrier no longer instantiates them. The obf path on 1.20/1.21 will route through the unobf-side initializer + concrete effects via the `rtp-fabric-common-unobf` module loaded under intermediary remap — see ADR-009 for the loader-level dispatch detail.)
6. Add a regression test `EffectsApiFabricCarriersDisjointTest` (bytecode/import scan): asserts that `effectsapi/common/*` and `effectsapi/bukkit/*` reference neither carrier; that `effectsapi/fabric/*` does not reference any class under `effectsapi/fabric_unobf/*`; and vice versa.
7. CHANGELOG entry under the current unreleased version describing the obf/unobf split + addon migration note (any addon importing `effectsapi.fabric.{LocalEffects,FabricEffectsInitializer,FabricValueCoercer,FabricRegistryCompat}.*` updates the import to `effectsapi.fabric_unobf.…`).
8. Cross-reference `docs/dev/POTENTIAL_BUGS.md` rows for `class_2596` / `class_7923` (already logged) when closing the work.
9. Update `.junie/AGENTS.md` *Domain Analogies & Aliases* if a new informal alias emerges (likely "obf carrier" / "unobf carrier" — add only if it appears in chat/issues, per the *Self-Updating Protocol*).
10. Update `docs/dev/TRACEABILITY.md` if any moved class is referenced by a REQ-* row (mechanical path update; no requirement-text change).

## Verification gates (must be green before submit of the implementation PR)

- `.\gradlew :effects-api:build :rtp-plugin:compileJava` → BUILD SUCCESSFUL.
- `.\gradlew :effects-api:test` → existing tests + new `EffectsApiFabricCarriersDisjointTest` pass.
- `.\gradlew :rtp-fabric:rtp-fabric-v26_1_R1:build` → BUILD SUCCESSFUL (depends on ADR-009's module landing first).
- Manual smoke (deferred to admin, after ADR-009 ships): `/rtp` on a deobf MC 26.1.2 server with `effectParsing: true` and a sound configured under `rtp.effect.postteleport` produces audible feedback; the post-teleport title splash reaches the client.
- Manual regression: `/rtp` on a 1.21.1 (intermediary) runtime continues to fire effects (the obf path is unchanged from the consumer's perspective).
