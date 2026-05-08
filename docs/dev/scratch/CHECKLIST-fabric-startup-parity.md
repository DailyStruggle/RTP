# CHECKLIST — Fabric ⇄ Bukkit startup parity

**Effective Issue:** Bring Fabric `RTPFabricMod.onInitialize` registrations to parity with `BukkitEffectsHandler.setupEffects` (Bukkit-side hub registered at plugin enable).
**Mode:** [CODE] (per-item; some items deferrable)
**Created:** 2026-05-06
**Working note — delete on full completion.**

---

## Already at parity ✅
- [x] A. Post-teleport `consoleCommands` / `playerCommands` dispatch — RTPFabricMod.java `teleportPostActions` callback (commit / current diff).
- [x] B. Pipeline registration, `FabricEventBridge`, world/server accessors, command tree (pre-existing).

---

## High value — user-visible ⚠️

- [x] 1. **Title / subtitle / actionbar on post-teleport**
  - Bukkit reads `MessagesKeys.title`, `subtitle`, `fadeIn`, `stay`, `fadeOut`, `actionbar` from the `teleportPostActions` hook and calls `SendMessage.title(...)` / `SendMessage.actionbar(...)`.
  - On Fabric these config keys are silent no-ops.
  - **Plan:**
    - [x] 1a. `FabricRTPPlayer.sendTitle` added (ClientboundSetTitlesAnimationPacket + SetSubtitleTextPacket + SetTitleTextPacket via `connection.send`, FabricLegacyText-parsed).
    - [x] 1b. `FabricRTPPlayer.sendActionbar` added (ClientboundSetActionBarTextPacket via `connection.send`).
    - [x] 1c. New `teleportPostActions` branch in `RTPFabricMod` resolves `MessagesKeys.title/subtitle/fadeIn/stay/fadeOut/actionbar`, hops to `RTP.scheduler.runTask`, and dispatches.
    - [x] 1d. `:rtp-plugin:compileJava` green (BUILD SUCCESSFUL). Manual /rtp verification deferred to admin smoke-test.

---

## Medium value — `effectParsing` gated 🟡

- [ ] 2. **`EffectFactory` per-stage hooks** — Bukkit registers seven lifecycle effect dispatch points keyed on permissions:
  - `rtp.effect.presetup` (`setupPreActions` + `loadPreActions`)
  - `rtp.effect.postsetup` (`setupPostActions`)
  - `rtp.effect.postload` (`loadPostActions`)
  - `rtp.effect.preteleport` (`teleportPreActions`)
  - `rtp.effect.postteleport` (`teleportPostActions`)
  - `rtp.effect.cancel` (`RTPTeleportCancel.postActions`)
  - `rtp.effect.queuepush` / `rtp.effect.queuepop` (`Region.onPlayerQueuePush` / `onPlayerQueuePop`)
  - **Recommendation: defer.** Current `Effect` implementations extend `BukkitRunnable` and depend on `org.bukkit.*` (sounds, particles, fireworks, note blocks). Porting requires a Fabric-side `EffectFactory` rewrite against `ServerLevel`/packets — ADR-scale work, not in current scope.
  - [ ] 2a. Document non-parity in `rtp-fabric/docs/` (or a new ADR row) so admins know `effectParsing` is a Bukkit-only knob on Fabric.

---

## Low value — not portable 🟦

- [x] 3. **Bukkit events** (`PreSetupTeleportEvent`, `PostTeleportEvent`, `TeleportCancelEvent`, `PlayerQueuePushEvent`, …) — third-party Bukkit-plugin surface, no Fabric meaning. No action needed; Fabric mods attach to the same `TeleportPipelineTask.*Actions` / `Region.onPlayerQueue*` lists directly.

---

## Investigation 🔍

- [ ] 4. **`miscAsyncTasks` drain on Fabric**
  - Bukkit drains `RTP.getInstance().miscAsyncTasks` from a recurring async task started in `RTPBukkitPlugin`. Need to confirm Fabric does (or does not) drain the same queue.
  - [ ] 4a. `search_project` for `miscAsyncTasks` in `rtp-plugin/.../fabric/` and `rtp-fabric/`.
  - [ ] 4b. If not drained: register an async repeating task in `RTPFabricMod.onInitialize` that polls and runs queued runnables (avoid blocking, S-005 unrelated since these are pre-async by design).
  - [ ] 4c. Verify by adding a probe runnable and checking it executes after one tick.

---

## Submit checklist
- [ ] All checked items independently verified (compile + targeted run/test).
- [ ] CHANGELOG.md entry under current unreleased version (parity bullet).
- [ ] Delete this scratch file once Items 1 and 4 are merged; leave Item 2 as a follow-up entry in `docs/dev/POTENTIAL_BUGS.md` or a dedicated ADR if pursued.
