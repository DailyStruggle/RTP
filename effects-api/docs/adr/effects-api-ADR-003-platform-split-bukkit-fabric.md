# effects-api-ADR-003 — In-module platform split: `effectsapi/common` (SPI) + `effectsapi/bukkit` + `effectsapi/fabric`

- **Status:** Accepted (2026-05-06; revised after user feedback the same day)
- **Supersedes:** —
- **Superseded by:** —
- **Related:**
  - `.junie/AGENTS.md` *Architecture Boundaries* (no platform imports in `rtp-api` / `rtp-core`; analogous principle applied to `effectsapi/common`).
  - `commands-api-ADR-001-brigadier-bridge.md` — **direct precedent**: `commands-api` keeps `common/`, `bukkit/`, `brigadier/` as **subpackages of one module** with `compileOnly` Spigot + `compileOnly` Brigadier; we mirror that exact shape here.
  - `rtp-fabric-ADR-001-fabric-multiversion-submodule-layout.md` (multi-MC layering — `effectsapi/fabric` only sees the common Fabric surface, not per-MC packets directly; concrete packet calls live in `rtp-fabric-common` / `RTPFabricMod`).
  - `rtp-fabric-ADR-007-mojmap-name-decoupling.md` (rationale for keeping `net.minecraft.*` calls behind a thin SPI).
  - `effects-api-ADR-001-glide-effect.md`, `effects-api-ADR-002-type-driven-reading-order.md` (per-effect contracts kept intact).
  - `docs/dev/scratch/CHECKLIST-fabric-startup-parity.md` Item 2 (this ADR unblocks it).

---

## Context

`effects-api` today is Bukkit-coupled at the **base class**: every effect extends `org.bukkit.scheduler.BukkitRunnable` and the abstract `Effect<T>` imports `org.bukkit.{Color, Location, NamespacedKey, Registry, Sound, entity.Entity}`. `EffectFactory` calls `Bukkit.getPluginManager()` / `Permission` / `PotionEffectType`. Every concrete effect under `LocalEffects/*` and every listener under `BukkitListeners/*` is Bukkit-typed.

Net effect on Fabric: classloading `EffectFactory.buildEffects(...)` triggers `NoClassDefFoundError` on `BukkitRunnable`. Fabric cannot adopt any `rtp.effect.*` permission node, so `effectParsing` is silently a no-op on Fabric (Item 2 of `CHECKLIST-fabric-startup-parity.md`).

Call surface is small and fully contained:

- `rtp-plugin/.../bukkit/effects/BukkitEffectsHandler.java` — 10 `EffectFactory.buildEffects(...)` call sites (one per lifecycle hook).
- `effects-api/.../commands/*` — Bukkit-side `/effects` admin command tree.
- No third-party in-tree consumers (`addons/RTP_Glide` does **not** import `effectsapi`).

### User constraint (revision driver, 2026-05-06)

> "try not to create more root level directories, we can do this within effects-api like we did with commands-api"

The earlier draft of this ADR proposed three sibling root modules (`effects-api`, `effects-api-bukkit`, `effects-api-fabric`). The revised decision below keeps everything inside the **existing** `effects-api` module, exactly mirroring how `commands-api` hosts `common/`, `bukkit/`, and `brigadier/` subpackages in one gradle module.

## Decision

Re-organise `effects-api` **internally** into three sibling subpackages, with no new gradle modules:

```
effects-api/                                       (existing module — unchanged at the gradle level)
  src/main/java/io/github/dailystruggle/effectsapi/
    common/                                        ← NEW — platform-neutral SPI + base types
      Effect.java                                  (moved; no longer extends BukkitRunnable)
      EffectFactory.java                           (moved; permission lookup via RTPPlayer)
      spi/EffectRuntime.java                       (NEW — schedule / playSound / particle / potion)
      spi/EffectTarget.java                        (NEW — wraps RTPPlayer + RTPLocation)
      configmodel/                                 (NEW — string-keyed POJOs for sound/particle/note/color)
    bukkit/                                        ← RENAMED from current top-level
      BukkitEffectRuntime.java                     (NEW — implements EffectRuntime via Bukkit)
      LocalEffects/{Sound,Particle,Note[, _1_12], Potion, Firework, Glide}Effect.java  (moved verbatim, retargeted onto EffectRuntime)
      BukkitListeners/{Firework,Glide}SafetyListener.java                              (moved verbatim)
      commands/* (Bukkit /effects admin tree)                                          (moved verbatim)
      events/* (Bukkit-typed events)                                                   (moved verbatim)
    fabric/                                        ← NEW
      FabricEffectRuntime.java                     (NEW — implements EffectRuntime via ServerLevel + clientbound packets)
      FabricSoundEffect.java
      FabricParticleEffect.java
      FabricTitleEffect.java
      FabricPotionEffect.java
      // FireworkEffect & GlideEffect deferred — see "Out of scope, Phase 2" below.
```

### Gradle wiring (mirrors `commands-api/build.gradle`)

A single `effects-api/build.gradle` keeps both platforms as `compileOnly` so neither leaks into runtime classpaths of the wrong adapter:

```gradle
plugins {
    // Required so the fabric subpackage can resolve net.minecraft.* at compile time.
    // Same precedent as :rtp-plugin (Loom + compileOnly Spigot in one module — see
    // rtp-plugin/build.gradle Phase-1 comment block).
    id 'fabric-loom'
}
ext {
    minecraftVersion = '1.21.1'   // mirrors rtp-fabric-common; bump in lockstep
    loaderVersion    = '0.16.5'
    fabricApiVersion = '0.115.0+1.21.1'
}
dependencies {
    // common/ + bukkit/ subpackages
    compileOnly 'org.spigotmc:spigot-api:1.20.1-R0.1-SNAPSHOT'
    testImplementation 'org.spigotmc:spigot-api:1.20.1-R0.1-SNAPSHOT'

    // fabric/ subpackage — Loom-managed Mojmap MC + Fabric loader/api as compileOnly
    minecraft "com.mojang:minecraft:${minecraftVersion}"
    mappings  loom.officialMojangMappings()
    modCompileOnly "net.fabricmc:fabric-loader:${loaderVersion}"
    modCompileOnly "net.fabricmc.fabric-api:fabric-api:${fabricApiVersion}"

    // Already present
    implementation project(':commands-api')
    implementation 'org.jetbrains:annotations:24.1.0'
}
```

`rtp-plugin` keeps its single `implementation project(':effects-api')` line — no consumer-side dependency change needed. `BukkitEffectsHandler` switches imports from `io.github.dailystruggle.effectsapi.LocalEffects.*` → `io.github.dailystruggle.effectsapi.bukkit.LocalEffects.*` (mechanical). `RTPFabricMod` gains a parallel `FabricEffectsHandler` (or inline branch) calling `EffectFactory.buildEffects(...)` against the new `fabric/` runtime.

### `EffectRuntime` SPI (initial surface, refined during implementation)

```java
package io.github.dailystruggle.effectsapi.common.spi;

public interface EffectRuntime {
    void schedule(Runnable task, long delayTicks);            // replaces Effect#runTask*
    void scheduleRepeating(Runnable task, long delayTicks, long periodTicks);
    void playSound(EffectTarget target, String soundKey, float volume, float pitch);
    void spawnParticle(EffectTarget target, String particleKey, int count,
                       double dx, double dy, double dz, double speed);
    void givePotion(EffectTarget target, String potionKey, int durationTicks, int amplifier);
    // Title / actionbar already routed through RTPPlayer (FabricRTPPlayer.sendTitle /
    // SendMessage.title on Bukkit), so not duplicated here.
}
```

`Effect<T>#run()` keeps its current shape; the only structural change is that subclasses receive an injected `EffectRuntime` (constructor or `EffectFactory#build` site) rather than calling `Bukkit.*` directly. `Effect` no longer extends `BukkitRunnable`; the only callers (`BukkitEffectsHandler` lines 192/306/…) currently do `effect.runTask(plugin)` and switch to `runtime.schedule(effect, 0)`.

## Consequences

### Positive
- **No new root-level directories** — honours the user's constraint and keeps the project structure aligned with `commands-api`'s precedent.
- Fabric gains a real `rtp.effect.*` lifecycle hook surface (unblocks `CHECKLIST-fabric-startup-parity.md` Item 2).
- `effectsapi/common` is consumable by addons that don't want a Bukkit dependency — same benefit `rtp-api` already provides.
- Threading boundary aligns with S-005 (the SPI is the single chokepoint for "what thread does this run on").
- Zero churn for in-tree addons — `RTP_Glide` doesn't touch the API today.

### Negative / costs
- One-time refactor: 8 effect classes + `EffectFactory` to thread `EffectRuntime`, plus mechanical package moves under `bukkit/`.
- `effects-api/build.gradle` gains the `fabric-loom` plugin and Loom config block. `rtp-fabric-ADR-002 §4 Build Discipline` (and `rtp-fabric/REQUIREMENTS.md` REQ-FABRIC-ARCH-003) currently restrict Loom to `rtp-fabric/**` and `rtp-plugin`; **this ADR widens that allow-list to also include `effects-api`** (rationale: same single-jar mixed-platform precedent as `rtp-plugin`). REQ-FABRIC-ARCH-003 and ADR-002 §4 are amended in lockstep with this ADR's acceptance.
- `effectsapi/bukkit` package move = ~15 file paths change. All consumers are in-tree; one `BukkitEffectsHandler` import-block edit covers them.
- Public-ish change: any out-of-tree addon that does `effect.runTask(plugin)` directly migrates to `runtime.schedule(effect, 0)` — CHANGELOG-flagged.

### Out of scope (Phase 2 follow-ups, separate ADR)
- `FabricFireworkEffect` — needs `FireworkRocketEntity` spawn + safety listener equivalent.
- `FabricGlideEffect` — needs elytra-equip + glide-state plumbing; no clean Fabric primitive without a mixin.
- `FabricEffectsAPIMainCommand` — admin command port; Bukkit-only is acceptable for Phase 1.

## Implementation checklist (will live in `docs/dev/scratch/CHECKLIST-effects-api-platform-split.md` once approved)

1. Update `effects-api/build.gradle`: apply `fabric-loom`, add Mojmap MC + Loader/API `modCompileOnly`, keep Spigot `compileOnly`. Confirm `:effects-api:build` still green before any code moves.
2. Create `effectsapi/common/spi/{EffectRuntime,EffectTarget}.java`.
3. Move `Effect.java` and `EffectFactory.java` into `effectsapi/common/`; de-Bukkit-ify them (drop `extends BukkitRunnable`, swap `org.bukkit.{Color,Location,NamespacedKey,Registry,Sound,Entity}` for neutral `String` keys + `RTPLocation`; replace `Bukkit.getPluginManager()` permission lookup with `RTPPlayer.getEffectivePermissions()`).
4. Move `LocalEffects/*`, `BukkitListeners/*`, `commands/*`, `events/*` into `effectsapi/bukkit/...` (verbatim except the package declaration). Add `BukkitEffectRuntime`.
5. Update `rtp-plugin/.../bukkit/effects/BukkitEffectsHandler.java`: 10 call sites switch from `effect.runTask(plugin)` to `runtime.schedule(effect, 0)`; import block updated to `effectsapi.bukkit.LocalEffects.*`.
6. Add `effectsapi/fabric/` with `FabricEffectRuntime` + Phase-1 effects (`Sound`, `Particle`, `Title`, `Potion`).
7. Wire `RTPFabricMod.onInitialize` to call `EffectFactory.buildEffects(...)` on each lifecycle hook (the seven `rtp.effect.*` perms — same shape as `BukkitEffectsHandler`).
8. CHANGELOG entry under the current unreleased version describing the in-module split + addon migration note.
9. Tests:
    - Existing `effects-api/src/test/...` tests stay where they are (they exercise `LocalEffects` which now live under `effectsapi.bukkit.*` — package-line update only).
    - Add a core test asserting `effectsapi.common.*` has no `org.bukkit.*` / `net.minecraft.*` references (bytecode/import scan).
    - Add a `FabricEffectRuntime#playSound` packet-shape smoke test (mirrors `FabricLegacyTextInteractiveTest` style).
10. Update `.junie/AGENTS.md` *Domain Analogies & Aliases* if needed (likely not — no new informal terms).

## Verification gates (must be green before submit)

- `.\gradlew :effects-api:build :rtp-plugin:compileJava` → BUILD SUCCESSFUL.
- `.\gradlew :effects-api:test` → all moved Bukkit tests pass.
- New core test `EffectsApiCommonNoPlatformImportsTest` (or equivalent bytecode assertion) passes for `effectsapi.common.*`.
- Manual smoke (deferred to admin): `/rtp` on Fabric with `effectParsing: true` and a sound configured under `rtp.effect.postteleport` produces audible feedback.
