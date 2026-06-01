# effects-api-ADR-001 — Glide Effect in `effects-api`

*(Renumbered from project-wide ADR-029 on 2026-05-05 when subproject ADRs were given per-directory numbering. Prior commits and historical references may still say "ADR-029".)*

**Status:** Accepted
**Date:** 2026-05-05
**Implemented:** 2026-05-05 — `effects-api/src/main/java/io/github/dailystruggle/effectsapi/LocalEffects/GlideEffect.java` + `enums/GlideTypeNames.java` + `BukkitListeners/GlideSafetyListener.java` + `events/{PlayerGlideEvent,PlayerLandEvent}.java`; registered in `EffectFactory` and wired through `EffectsAPI.init/disable`.

## Context

The `addons/RTP_Glide` project demonstrates a popular post-teleport behaviour:
after a random teleport, the player is placed at altitude with elytra-style
glide enabled, descends under control, and lands safely. It currently lives as
an external addon (`addons/RTP_Glide`) with its own `Configs`, `Worlds`,
`SetupGlide` task, and `PlayerGlideEvent` / `PlayerLandEvent` events.

`effects-api` already standardises post-teleport effects (`Effect`,
`EffectFactory`, plus `LocalEffects/*` for `Firework`, `Particle`, `Potion`,
`Sound`, `Note`). Glide is a natural peer to those effects: per-player,
configurable, fired at the end of a teleport, and platform-agnostic at the API
layer. Folding it into `effects-api` lets every consumer of `effects-api`
(plugin, addons, future platforms) opt into glide via configuration without
shipping a separate jar, and gives us a single owner for the
glide-and-fireworks safety interaction (a glider holding a firework rocket in
vanilla Minecraft accelerates dramatically — undesirable as a default).

Two knobs are required by the issue:

1. A landing-time budget — how long the glide is allowed to last before the
   effect is forcibly ended (player removed from glide, safely placed).
2. Whether firework rockets may be used during the glide window. The default
   is `false`, both to prevent abuse (free-flight) and because the surrounding
   chunks may still be settling after the teleport.

This ADR documents the integration plan only; it is not an implementation.
Per Rule D-005, the actual cross-module change requires explicit approval
before code lands.

## Decision

We will introduce a `GlideEffect` as a first-class entry in
`effects-api`, alongside the existing local effects, with the following shape.

### Module placement

- New class: `effects-api/src/main/java/io/github/dailystruggle/effectsapi/LocalEffects/GlideEffect.java`,
  extending `Effect`.
- Registered in `EffectFactory` so any `effects.yml`-style configuration can
  reference it by name (`glide:`).
- New command: `effects-api/.../commands/GlideCommand.java`, mirroring
  `FireworkCommand` etc., for `/effectsapi glide ...` administration.
- New listener (optional, for safety): extend the pattern of
  `BukkitListeners/FireworkSafetyListener` to police the glide-vs-rocket rule
  (`GlideSafetyListener`), gated by the `allowFireworks` setting.
- The existing `addons/RTP_Glide` becomes deprecated once parity is reached;
  its `PlayerGlideEvent` / `PlayerLandEvent` are migrated into
  `effects-api` (or kept as thin re-exports during a transition window).

No `rtp-core` or `rtp-api` changes are required — `effects-api` is the correct
home (see Architecture Boundaries §3 in `.junie/AGENTS.md`).

### Configuration surface (per-effect block)

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `landingTimeout` | int (ticks) | `Integer.MAX_VALUE` | Maximum duration of the glide before forced landing. The effect ends earlier if the player lands naturally, logs out, or is teleported again. |
| `allowFireworks` | boolean | `false` | If `false`, firework-rocket use is suppressed for the gliding player for the duration of the effect. If `true`, rockets behave vanilla. |
| `placeOnShutdown` | boolean | `true` | If `true`, on server shutdown / plugin disable, any player still gliding is placed at the highest safe block below them (see *Shutdown handling*). If `false`, the player logs out mid-air. |
| `shutdownPlatformMaterial` | string (Material name) | `STONE` | Block used to synthesize an emergency 3×3 platform when no safe block exists below the player at shutdown. Only `AIR`/`CAVE_AIR`/`VOID_AIR` cells are replaced; existing terrain is never overwritten. |
| `startHeight` | int (blocks above destination) | inherited from current addon default | Height at which glide begins. Documented here for completeness; not the focus of this ADR. |
| `world` filter | list/string | `*` | World allow-list, mirroring other effects. |

`landingTimeout` is expressed in server ticks for consistency with the rest of
`effects-api`. The `Integer.MAX_VALUE` default reproduces the addon's current
"glide until the player actually lands" behaviour while still giving operators
a single knob to clamp pathological cases (e.g. void-world misconfiguration,
infinite hover via creative flight).

### Behavioural contract

- On teleport completion, if a `GlideEffect` is configured for the
  player/world/permission scope, `effects-api` applies it after any other
  positional effects (so particles/sound at destination still fire).
- The effect schedules a per-player end-of-glide watchdog with delay
  `landingTimeout`. Folia note: the watchdog must use the player's
  `EntityScheduler`, never a region thread bare delay (see `.junie/AGENTS.md`
  → *Folia Threading*). On Spigot/Paper a normal scheduled task is fine.
- The watchdog cancels itself when (a) the player touches ground, (b) the
  player disconnects, (c) another teleport occurs, or (d) the timeout
  elapses, whichever comes first. On (d), the player is removed from glide
  and a safety check (re-using existing safety predicates) confirms the
  current block is safe; if not, the player is teleported to the last known
  safe location from the pipeline (S-001, S-004 — failures are surfaced via
  `RTP.log`, never swallowed).
- If `allowFireworks == false`:
  - The player's firework-rocket use is suppressed by cancelling
    `PlayerInteractEvent` (right-click with `FIREWORK_ROCKET`) and the
    boost path on the platforms that expose it. Cancellation is silent;
    no chat spam.
  - On Folia, the listener must respect region ownership before mutating
    inventory.
- If `allowFireworks == true`, no listener is registered for that player;
  rockets behave vanilla.
- Events: fire `PlayerGlideEvent` at start and `PlayerLandEvent` at end
  (migrated from the addon) so other addons can hook in.

### Shutdown handling

When the server stops (or the plugin is disabled) while a player still has an
active glide effect, the player would otherwise log out mid-air and rejoin
falling — at best annoying, at worst a death on next login if the chunk below
has changed. On `onDisable` / shutdown the `GlideEffect` subsystem shall, for
every active glider:

1. Cancel the landing-timeout watchdog.
2. Resolve the **highest safe block at or below** the player's current X/Z in
   the player's current world, using the same safety predicates the teleport
   pipeline uses (S-001 — no unsafe destinations). Concretely: walk down from
   the player's current Y to the world's min build height, returning the
   first block whose top face passes the standard safety check
   (solid + non-hazard + breathable head clearance), preferring the player's
   current chunk so no new chunks are loaded.
3. Place the player there synchronously via the platform adapter's teleport
   abstraction, remove the gliding state, and fire `PlayerLandEvent`
   with a `reason = SHUTDOWN` discriminant.
4. If no safe block is found in the column (e.g. void world, fully hazardous
   column, lava/water column with no solid top), fall back to **platform
   construction**: locate the first non-air block below the player (the
   topmost non-air block in the column at or below the player's current Y;
   if the entire column is air down to min build height, use min build
   height itself), and synthesize a small platform one block above it before
   placing the player. Specifically:
   - Place a 3×3 square of a configurable solid material (default
     `STONE`) one Y above that first non-air block, only replacing
     `AIR`/`CAVE_AIR`/`VOID_AIR` (never overwrite existing terrain or player
     builds — S-001 spirit: do not damage what is already there).
   - Ensure the two blocks above the platform centre are air (carve only
     `AIR`-class; if a non-air block occupies head clearance, abort the
     platform attempt and log per the next bullet rather than break terrain).
   - Teleport the player onto the platform centre and fire
     `PlayerLandEvent(reason = SHUTDOWN_PLATFORM)`.
   - The platform is **not** tracked or cleaned up later; it is a
     deliberate, audit-logged side effect of an emergency landing. The
     audit row in `rtp test full` records the world, coordinates, and
     material used so operators can find and remove it if desired.
   - Material is configurable via `shutdownPlatformMaterial` (see config
     table below).
5. If even the platform fallback cannot run (e.g. current chunk not loaded
   at shutdown — see S-005 constraint below — or head clearance blocked by
   non-air), log at `WARNING` via `RTP.log` (S-004 — never silent) and
   leave the player at their current position; the operator sees the audit
   row in `rtp test full`.

Constraints:

- **No chunk loading on shutdown** (S-005). The column scan must use only
  already-loaded chunks; if the player's current chunk is somehow not loaded
  at shutdown, log and skip rather than force-load.
- On Folia, the placement uses the player's `EntityScheduler` if the
  scheduler is still accepting tasks during shutdown; otherwise fall back to
  the platform adapter's synchronous teleport hook reserved for shutdown
  (mirrors how other `effects-api` listeners drain on disable).
- Spigot/Paper run inside the main-thread `onDisable`, so the placement is a
  direct `player.teleport(loc)` after the column scan.
- Bounded work: at most one column scan per active glider, capped by the
  world height. Total shutdown overhead is O(active gliders × worldHeight),
  which is negligible.

Configuration:

- `placeOnShutdown` (boolean, default `true`) — operators who prefer the
  vanilla "log out where you are" behaviour can disable the shutdown
  placement per effect block.

### Failure & safety

- S-004: any failure in the glide pipeline (timeout fallback fails, player
  unloads mid-glide, scheduler refuses task) logs at `WARNING` via
  `RTP.log` and ends the effect deterministically. No silent return paths.
- S-005: glide does not load chunks. Destination chunks were already loaded
  by the teleport pipeline. The watchdog must not call `getChunkAt`
  synchronously — if a chunk check is needed at landing, route through the
  platform adapter's async abstraction.
- REQ-RTP-F-013: any user-facing message ("you cannot use rockets while
  gliding", landing fallback notice if we choose to send one) lives in
  `messages.yml` under new keys; nothing hardcoded.
- The firework suppression path is a pure cancel-event listener and adds no
  reflection — it does not require an `EXTERNAL_HOOKS.md` entry.

### Testing

- Unit test `GlideEffectConfigTest` — defaults parse to
  `landingTimeout = Integer.MAX_VALUE`, `allowFireworks = false`.
- Behavioural test (existing Folia dispatch test pattern, see
  `FoliaDispatchTest`) — watchdog uses the entity scheduler on Folia.
- Listener test — `PlayerInteractEvent` with `FIREWORK_ROCKET` is cancelled
  when `allowFireworks == false` and the player has an active glide effect;
  not cancelled otherwise.
- `TRACEABILITY.md` rows added for the glide-related REQs once they are
  authored (separate from this ADR).

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep `RTP_Glide` as an external addon indefinitely | Forces every operator who wants glide to install a separate jar; firework-vs-glide safety logic stays out of `effects-api` and is duplicated by anyone re-implementing it; addon currently misses Folia entity-scheduler nuance. |
| Put glide directly in `rtp-core` | Violates Architecture Boundaries §2 (no per-effect logic in core). Glide is an effect, not part of region/queue/spiral concerns. |
| No timeout (rely on natural landing only) | Reproduces a real bug from the addon: in void worlds, misconfigured `startHeight`, or creative-flight players, glide never ends and the watchdog leaks. `Integer.MAX_VALUE` default preserves "effectively unlimited" while giving operators a clamp. |
| Default `allowFireworks = true` (vanilla) | Trivially exploitable: a player who carries rockets gets a free, fast, configurable glide jump from anywhere `/rtp` lands them. Default-deny is safer; operators can opt in per world / per permission. |
| Implement firework suppression by clearing the rocket from inventory | Destructive and surprising; players lose items. Cancelling the interact event is reversible and observable. |

## Consequences

- **Positive:**
  - Glide becomes a first-class, documented effect with the same configuration
    ergonomics as `firework`, `particle`, `potion`, `sound`, `note`.
  - The glide-vs-rocket safety interaction has one owner.
  - Operators get a single timeout knob to bound pathological cases.
  - Addon authors can subscribe to `PlayerGlideEvent` / `PlayerLandEvent`
    from a stable module (`effects-api`) instead of an addon jar.
- **Negative / Trade-offs:**
  - Migration cost: `addons/RTP_Glide` users need a deprecation window and
    config-shape note in `CHANGELOG.md`.
  - Adds a per-player listener on platforms where `allowFireworks == false`;
    listener overhead is O(active gliders), bounded.
  - Folia entity-scheduler usage adds platform-specific code paths in the
    `effects-api` Spigot listener layer — acceptable, mirrors what
    `FireworkSafetyListener` already does.

## References

- Existing addon: `addons/RTP_Glide/src/main/java/io/github/dailystruggle/rtp_glide/`
  (especially `Tasks/SetupGlide.java`, `Listeners/OnRandomTeleport.java`,
  `customEvents/PlayerGlideEvent.java`, `customEvents/PlayerLandEvent.java`).
- Effect framework: `effects-api/src/main/java/io/github/dailystruggle/effectsapi/Effect.java`,
  `EffectFactory.java`, `LocalEffects/FireworkEffect.java`,
  `BukkitListeners/FireworkSafetyListener.java`.
- Safety rules: `.junie/AGENTS.md` → *Prohibition Requirements (S-00x)*,
  in particular S-004 (no silent failure) and S-005 (no main-thread chunk I/O).
- Folia threading constraints: `.junie/AGENTS.md` → *Folia Threading*.
- Message configurability: REQ-RTP-F-013 in `docs/dev/REQUIREMENTS.md`.
- Architectural placement: `.junie/AGENTS.md` → *Architecture Boundaries*.
