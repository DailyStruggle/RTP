# ADR-093 - Declarative Scripted Actions via Core Confinement, Subspace Placement, and Minecraft Command Lifecycle

**Status:** Proposed
**Date:** 2026-09-09
**Target:** the release after the current spatial-memory line. The in-flight version is scoped to spatial updates (ADR-085, ADR-088, ADR-092); no part of this record - including the MVP in sections 1 through 8 - is implemented in that version.

## Context

Server operators frequently seek to orchestrate multi-player spatial events, such as 1v1 PvP duels, party wilderness excursions, battle-royale sudden-death zones, boss encounters, and scavenger hunts. 

### Market Precedent and Architectural Deficiencies
Existing market solutions handle these gameplay requirements through fragmented and rigid mechanisms:
1. **Parallel Area Models (JustRTP):** Competitor plugins like JustRTP introduce ad-hoc secondary subsystems (`/rtpzone`), duplicating configuration schemas, caches, geometric lookups, and commands distinct from standard RTP. Region safety optimizations (such as Anvil pre-filtering and biome blacklists) are not shared, resulting in duplicated maintenance and diverging behavior.
2. **Brute-Force Chunk Stacking:** When teleporting parties or duels, competitors either stack participants onto the exact same block coordinate (causing suffocation, collision desync, and instant friendly-fire) or execute synchronous live-world searches (`world.getHighestBlockAt()`) around player 1, generating severe MSPT spikes on Paper and crashing regional tick budgets on Folia.
3. **Movement Veto Rubberbanding:** Confinement is typically delegated to Bukkit's `PlayerMoveEvent.setCancelled(true)` or external plugins (WorldGuard). Event cancellation snaps the client back, causing visual jitter, anti-cheat false positives (GrimAC, Vulcan), and platform lock-in (it cannot run on Fabric or NeoForge, where movement cannot be vetoed via Bukkit events).
4. **Hardcoded Game Logic:** Competitor implementations hardcode specific commands or game modes in Java, preventing operators from defining novel rules without commissioning custom plugins.

### The Opportunity in RTP
LeafRTP already possesses the core mathematical and concurrency primitives required to solve these problems without bloat:
- **Spatial Memory (`MemoryShape`):** constant-time pre-warmed candidate pools with zero-allocation mathematical boundary containment (`shape.contains(x, z)`), evaluated live against current shape bounds.
- **Two-Stage Subspace Candidate Selection:** Resolves anchor positions from pre-warmed queues, applies a chunk-granularity pre-filter against `MemoryShape`, and screens standable block columns via `CandidateValidator` to guarantee minimum player separation and elevation tolerance off-tick with fail-closed capacity guarantees. Prototyped in `LeafRTPGroupAddon`; promoted to core by this ADR.
- **Platform-Neutral Move Signal (ADR-075):** Block-granularity, opt-in player movement subscription across Paper, Folia, Fabric, and NeoForge.
- **Folia-Safe Command Dispatch (`CommandEffect` / `HandleRegistry`):** Safely dispatches console and player commands across regional threads and entity schedulers.

Rather than authoring separate bespoke game plugins or accreting hardcoded game primitives, all multi-player, arena, and event orchestration can be generalized into a single declarative abstraction: **Scripted Actions**.

### Design Intent: Capability, Not Catalogue
Competitors ship minigames. RTP shall ship the *capability* to place, confine, gate, and dispatch, and shall not ship, name, or maintain any specific game. No duel, royale, boss, or hunt logic exists in Java. The duel example in this ADR is documentation, not a shipped feature. Any rule RTP cannot express declaratively is delegated to vanilla commands or to a registered external predicate, never absorbed as a new engine primitive.

## Decision

Fold group subspace placement and region confinement (tethering) into RTP as foundational engine primitives, exposed to operators via declarative action definitions under `definitions/actions/*.yml` and to developers via bi-directional callback hooks on `rtp-api`.

To preserve engine simplicity and avoid the "slippery slope" of perpetual feature requests for custom game mechanics, RTP shall provide zero hardcoded gameplay primitives (no bespoke inventory saving or gamemode toggling). Instead, the engine provides:
1. Spatial placement and boundary containment.
2. Gated action execution evaluated against extensible predicates.
3. Automated RTP-managed scoreboards for state queries.
4. Fault-tolerant command dispatch that guarantees lifecycle completion.

### Scope Partition: MVP vs Deferred
This ADR is delivered in two slices. Sections 1 through 8 define the **MVP** and are normative. Section 9 enumerates **deferred components** that shall not be implemented under this ADR; each requires its own justification before adoption.

| Concern | MVP | Deferred (section 9) |
|---------|-----|----------------------|
| Placement | Multi-entity subspace placement, separation, elevation tolerance | Team-aware / asymmetric placement profiles |
| Confinement | `SUBSPACE`, `REGION`, `LEASH` boundaries; safe pull-back | Dynamic shrinking boundaries (sudden-death) |
| Gates | `scoreboard`, `spatial`, `time`, `predicate` | `tag`, `roster`, `permission`, `gamemode`, `chance`, `environment` |
| Dispatch | `CONSOLE`, `PLAYER`, `ACTION`, `FOR_EACH`; guarded, non-fatal | Nested sub-action invocation, delayed / scheduled steps |
| Commands | Startup-only alias registration | Runtime re-registration, dynamic argument suggestions |
| Lifecycle | `onStart`, `onBoundaryViolation`, `onExpire`, `onDeath` | `onTick`, `onKill`, `onJoin`, `onQuit` |
| State | RTP-managed scoreboards + startup orphan reaper | Cross-restart session resumption |

### 1. Architectural Boundary: Core Engine vs. Context Providers
The division of responsibility is strictly partitioned:
- **RTP Core Owns:**
  - Multi-entity subspace placement with verified minimum separation and elevation tolerance.
  - Mathematical boundary containment (`shape.contains(x, z)` or radial/subspace leash).
  - Safe pipeline pull-backs (S-001 through S-005) on boundary violations.
  - Lifecycle dispatch, gate evaluation, and fault-tolerant command execution (`CONSOLE`, `PLAYER`, `ACTION`, `FOR_EACH`).
  - Automated namespaced scoreboards for session metadata and state inspection.
  - Opt-in movement monitoring via ADR-075.
- **Addons / External Callers Own (Context Providers):**
  - Collecting participant UUIDs (e.g. from party rosters, faction memberships, queue systems, or command arguments).
  - Invoking the action by identifier: `RTP.actions().trigger(actionId, participants, context)`.
  - Addons require zero custom chunk loading, safety math, or movement listeners.

### 2. Fault-Tolerant, Non-Fatal Command Dispatch
In a script execution pipeline, individual command failures (such as a syntax error, an unknown item ID, or a command unregistered on a particular modded server) **must never halt or abort script execution**.

- **Guarded Execution:** Every command in a lifecycle phase executes in an isolated protected block. If a command throws an exception or returns a failure status, RTP logs a warning (`Level.WARNING`) for operator auditing and immediately proceeds to the next command.
- **Guaranteed Lifecycle Completion:** Script errors shall never leave participants in an inconsistent or orphaned state. Confinement timers, boundary checks, and teardown disarms run unconditionally to completion (upholding S-004).

### 3. Action Gates: Declarative Predicate Engine (MVP: four gate types)
Rather than encoding domain-specific game rules into Java, action execution blocks can define **Gates** (pre-conditions). A gate must evaluate to `true` for its enclosed commands to execute. Gates within one block are conjunctive; an unrecognized gate key fails closed (block skipped, warning logged).

The MVP gate set is deliberately minimal. Anything expressible as `execute if ...` inside a `CONSOLE` step is not a gate, and anything requiring external plugin state is a `predicate`.

1. **Scoreboard Gates (`scoreboard`):** Compares an entity or session score against an objective (e.g. `objective: "rtp_violations"`, `matches: "< 3"` or `range: "1..5"`).
2. **Spatial & Boundary Gates (`spatial`):** Validates geometric status relative to session anchor and boundaries:
   - `withinBoundary: true|false` (inside or outside configured region/subspace).
   - `distance: "<= 64"` (Euclidean or Chebyshev radial distance from anchor).
   - `elevationDelta: "<= 8"` (vertical distance relative to anchor or partner).
3. **Temporal & Phase Gates (`time`):** Checks elapsed or remaining duration:
   - `elapsed: ">= 30s"` (time since session start).
   - `remaining: "<= 10s"` (countdown threshold).
4. **External API Gates (`predicate`):** Third-party plugins register custom programmatic predicates via `rtp-api`, evaluating conditions RTP shall never model natively (economy balance, territory ownership, skill level, party state). This is the designated escape hatch: growth happens in caller code, not in the engine.

Comparison expressions (`"< 3"`, `"1..5"`, `">= 30s"`) share one parser across all gate types. No boolean operators, arithmetic, or variable binding are provided; the DSL is a predicate list, not a language.

### 4. RTP-Managed Scoreboards for Universal State Inspection
On modded platforms (Fabric, NeoForge) or lightweight vanilla environments, authoring custom data packs or Java addons to bridge game state is high friction. RTP eliminates this barrier by automatically initializing and maintaining namespaced dummy scoreboards:

- `rtp_session_id`: Hash of the active session token.
- `rtp_time_left`: Remaining seconds before session expiration.
- `rtp_violations`: Counter incremented automatically on each boundary breach.
- `rtp_in_bounds`: Binary flag (`1` inside boundary, `0` in violation).
- `rtp_dist_sq`: Squared distance from session anchor.
- `rtp_alive`: Count of living participants in the session.

These scoreboards are accessible out of the box to vanilla target selectors (`@a[scores={rtp_violations=..2}]`), Brigadier commands (`/execute if score ...`), datapacks, and modded command systems without requiring external bridging addons. Ephemeral session scores are pruned upon session disarm.

**Startup orphan reaper (MVP, required).** Disarm is precisely the path that does not run when a server crashes mid-session. On plugin enable, RTP shall sweep and remove all `rtp_session_*` tags and all ephemeral `rtp_*` session objectives that reference no live session, before any action may be triggered. Without this, a crash strands participants with residual tags that later selectors match.

**Placeholder escaping (MVP, required).** Lifecycle placeholders (`[player]`, `[violator]`, `[winner]`, `[victim]`, `[session_id]`) resolve to UUIDs or sanitized selectors only, never to raw display names. Operator-authored strings run at console privilege; unsanitized player-controlled substrings would be a command-injection surface.

### 5. Configuration Schema: `definitions/actions/*.yml`
Definitions are managed under `definitions/actions/` via `MultiConfigParser`.

**Alias registration is startup-only.** Action definitions are parsed once during plugin enable, and aliases are registered into the Brigadier bridge in the same pass, before the command tree is frozen. `/rtp reload` re-reads placement, confinement, gate, and lifecycle bodies of existing actions, but shall not add, remove, or rename command aliases; alias changes require a server restart and shall log an informational notice on reload. This removes the entire class of re-registration, stale-node, and permission-desync failures across the Paper/Folia, Fabric, and NeoForge command bridges.

The example below is illustrative documentation of what operators can author, not a shipped game mode:

```yaml
alias: "duel"                        # Registered at startup: /duel <target>
permission: "rtp.action.duel"
description: "Challenge another player to a confined arena duel"

# Spatial placement profile
placement:
  region: "pvp_world"                # Inherits parent region memory and rules
  profile: "duel"                    # Subspace profile (from group engine)
  parameters:
    subspaceChunkRadius: 4           # 4x4 chunk arena footprint
    minSeparation: 32                # 32 blocks minimum separation between opponents
    elevationTolerance: 8            # Maximum 8 blocks height difference

# Confinement rules
confinement:
  boundary: SUBSPACE                 # SUBSPACE, REGION, or LEASH
  duration: 5m                       # Automatic match timeout

# Lifecycle script execution
lifecycle:
  onStart:
    # 1. Tag participants with session token
    - FOR_EACH:
        CONSOLE: "tag [player] add rtp_session_[session_id]"

    # 2. Store original gamemodes to dummy scoreboard using vanilla command blocks
    - CONSOLE: "scoreboard objectives add rtp_gm dummy"
    - CONSOLE: "execute as @a[tag=rtp_session_[session_id]] store result score @s rtp_gm run data get entity @s playerGameType"

    # 3. Equip participants and notify
    - CONSOLE: "gamemode adventure @a[tag=rtp_session_[session_id]]"
    - CONSOLE: "give @a[tag=rtp_session_[session_id]] iron_sword 1"
    - CONSOLE: "title @a[tag=rtp_session_[session_id]] title {\"text\":\"DUEL START!\",\"color\":\"red\"}"

  onBoundaryViolation:
    # Gate 1: First 2 violations trigger warnings and safe pull-back
    - gate:
        scoreboard:
          objective: "rtp_violations"
          matches: "< 2"
      run:
        - ACTION: PULL_BACK          # Safe pull-back from verified queue
        - FOR_EACH:
            PLAYER: "title [violator] subtitle {\"text\":\"Return to the arena! Warning issued.\",\"color\":\"yellow\"}"

    # Gate 2: Third violation triggers elimination and match forfeiture
    - gate:
        scoreboard:
          objective: "rtp_violations"
          matches: ">= 2"
      run:
        - CONSOLE: "broadcast &c[violator] was disqualified for fleeing the arena!"
        - ACTION: PULL_BACK
        - ACTION: DISARM

  onExpire:
    # Restore original gamemodes from scoreboard
    - CONSOLE: "execute as @a[tag=rtp_session_[session_id]] if score @s rtp_gm matches 0 run gamemode survival @s"
    - CONSOLE: "execute as @a[tag=rtp_session_[session_id]] if score @s rtp_gm matches 1 run gamemode creative @s"
    - CONSOLE: "execute as @a[tag=rtp_session_[session_id]] if score @s rtp_gm matches 2 run gamemode adventure @s"
    - CONSOLE: "execute as @a[tag=rtp_session_[session_id]] if score @s rtp_gm matches 3 run gamemode spectator @s"
    - FOR_EACH:
        CONSOLE: "tag [player] remove rtp_session_[session_id]"
        CONSOLE: "spawn [player]"
    - ACTION: DISARM

  onDeath:
    - CONSOLE: "broadcast &6[winner] &ehas defeated &c[victim] &ein a duel!"
    - CONSOLE: "give [winner] diamond 3"
    - ACTION: DISARM
```

### 6. Bi-Directional Callback Hooks (`rtp-api`)
For Java developers desiring programmatic control:
- **Accepting Inbound Callbacks:** External plugins can register programmatic dynamic boundaries, custom gate predicates, and victory conditions via `RTPHooks.confinement()`.
- **Handing Off Outbound Callbacks:** When a confinement event occurs, RTP hands a `ConfinementContext` and `ConfinementController` to registered listeners, allowing them to inspect player state and trigger outcomes (`pullBack()`, `eliminate()`, `disarm()`, `cancelEvent()`).

### 7. Cross-Platform Confinement via Safe Pull-Back
Enforcement strictly avoids packet-cancellation movement vetoes. On boundary violation, the player is relocated to a pre-verified, safe interior destination supplied by the parent region or local subspace validator. This operates identically on Paper, Folia (routing through entity schedulers), Fabric, and NeoForge without rubberbanding.

### 8. Live Boundary Evaluation Under `expand: true`
Confinement checks delegate directly to `MemoryShape.contains(x, z)` on every evaluation rather than caching a snapshot of the shape at session start. `MemoryShape` already accounts for `expand` in its bounds check, so a growing region yields a correct, monotonically widening containment result with no additional session state, no snapshot invalidation, and no divergence between confinement geometry and placement geometry. `LEASH` confinement is unaffected: it is a radial test against the session anchor and is independent of region growth.

### 9. Deferred Components (not implemented under this ADR)
The following are recorded so their absence is intentional rather than accidental. Each shall require its own ADR or requirement before adoption, and each shall be justified against the capability-not-catalogue rule.

1. **Additional gate types:** `tag`, `roster`, `permission`, `gamemode`, `chance`, `environment`. All are expressible in MVP via `execute if` inside a `CONSOLE` step or via a registered `predicate`; native support is a convenience, not a capability.
2. **Runtime alias mutation:** adding or renaming command aliases without restart, and dynamic argument suggestion providers.
3. **Additional lifecycle phases:** `onTick`, `onKill`, `onJoin`, `onQuit`.
4. **Dispatch composition:** nested sub-action invocation and delayed or scheduled steps. Both introduce recursion and scheduling semantics that the MVP predicate list deliberately excludes.
5. **Dynamic shrinking boundaries** (battle-royale sudden death) and team-aware asymmetric placement profiles.
6. **Cross-restart session resumption.** MVP sessions are ephemeral; the startup reaper cleans them rather than restoring them.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **Hardcoding Inflexible Convenience Primitives (`PRESERVE_GAMEMODE`, `INVENTORY_BACKUP`)** | A slippery slope to adopting ongoing maintenance responsibility for endless edge cases, modded inventory formats, and bespoke feature requests ("it's easier for you than for me"). Delegating state storage to Minecraft's native `/scoreboard` and gated predicates preserves engine purity. |
| **Aborting Script Execution on Command Error** | Strands players in inconsistent states (unreleased confinement, un-restored gamemodes, lingering tags) if a single command fails. Guarded dispatch ensures lifecycle teardown always completes (S-004). |
| **Dedicated "Minigame Addon" per Game Mode (DuelAddon, RoyaleAddon)** | Creates fragmented codebases for identical spatial and confinement math. Declarative configuration over shared primitives eliminates 95% of custom Java code. |
| **Bespoke Parallel Zone Engine (`/rtpzone`)** | Duplicates region storage, bypasses `MemoryShape` caching, and forces operators to configure the same world twice. |
| **Movement-Veto via `PlayerMoveEvent.setCancelled(true)`** | Causes rubberbanding desyncs, anti-cheat triggers, and cannot function on Fabric/NeoForge where event vetoing is unavailable. |
| **Bespoke Internal Player State Storage (Java Map serialization)** | Re-invents Minecraft's built-in `/scoreboard` and `/execute store` mechanics. Both approaches carry equivalent crash exposure, which is why the startup orphan reaper is mandatory in either design; scoreboard objectives win on being native, transparent, and debuggable in-game with no bespoke serialization format to version. |
| **Shipping all nine gate types in the first release** | Front-loads the accretion the ADR exists to prevent: nine evaluation contexts to test, document, and support permanently, when four cover the motivating cases and `predicate` absorbs the rest. Deferred to section 9. |
| **Snapshotting region shape at session start** | Unnecessary session state. `MemoryShape.contains(x, z)` already accounts for `expand` in its bounds check, so live delegation is correct by construction and keeps confinement geometry identical to placement geometry. |
| **Runtime alias registration on `/rtp reload`** | Command re-registration is the historical failure point across the Paper/Folia, Fabric, and NeoForge bridges (stale Brigadier nodes, permission desync, orphaned tab completion). Startup-only registration eliminates the class entirely at the cost of a restart for alias changes. |

## Consequences

- **Positive:**
  - Operators author arbitrary multi-player spatial flows purely in YAML, with no Java and no per-game plugin, while RTP ships no game.
  - Zero maintenance burden for arbitrary gameplay mechanics: four gates, native scoreboards, and the `predicate` escape hatch push growth into caller code instead of the engine.
  - Fault-tolerant dispatch guarantees that participants are never trapped or orphaned due to command syntax errors.
  - Native scoreboards provide immediate, zero-friction integration for modded platforms (Fabric/NeoForge) and vanilla command blocks alike.
  - Unified spatial model: arenas automatically inherit Anvil pre-filtering, safety validations, and `expand: true` boundaries.
  - Zero main-thread chunk I/O or MSPT lag spikes during group teleports.
  - True cross-platform parity across Paper, Folia, Fabric, and NeoForge.
  - Addons become lightweight context providers (collecting players and delegating to actions).
- **Negative / Trade-offs:**
  - Pull-back enforcement is a visible relocation rather than an invisible wall; operators must size arena boundaries appropriately to prevent disorienting players.
  - Advanced state preservation relies on vanilla Minecraft command syntax (`/execute store`, `/scoreboard`), which requires familiarity with vanilla command scripting.
  - Vanilla NBT paths used by operator scripts (for example `data get entity @s playerGameType`) are version-sensitive. RTP does not own or shim these paths; scripts relying on them can break across Minecraft versions and across the obf/unobf Fabric carriers.
  - Adding or renaming an action alias requires a server restart (section 5).
  - Scripted actions make RTP a soft dependency for gameplay: a config error can now break player-visible game flow rather than only teleport quality.
  - The deferred set (section 9) means some operator requests are answered with "write it as a vanilla command or a `predicate`" rather than a native gate. This is the intended cost of keeping the engine lean.

## References

- [ADR-001](ADR-001-archimedean-spiral-1d-mapping.md) - Archimedean Spiral 1D Mapping.
- [ADR-026](ADR-026-external-hook-api-surface.md) - External Hook API Surface.
- [ADR-075](ADR-075-platform-neutral-player-move-event-spi.md) - Platform-Neutral Player-Move Event SPI.
- [ADR-076](ADR-076-config-folder-consolidation.md) - Config Folder Consolidation (`definitions/` hierarchy).
- [`leafrtp-group-addon-ADR-001`](../../addons/LeafRTPGroupAddon/docs/adr/leafrtp-group-addon-ADR-001-subspace-group-teleport.md) - Multi-Entity Subspace Teleportation.
- [`leafrtp-tether-addon-ADR-001`](../../addons/LeafRTPTetherAddon/docs/adr/leafrtp-tether-addon-ADR-001-cross-platform-region-confinement.md) - Cross-Platform Region Confinement.
- [`effects-api-ADR-007`](../../api/effects-api/docs/adr/effects-api-ADR-007-command-effect-and-death-stage.md) - Command Effect and Death Stage in the Effects Engine.
