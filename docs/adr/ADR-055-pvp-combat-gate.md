# ADR-055 - Optional PvP / combat-tag pre-flight gate

**Status:** Accepted
**Date:** 2026-05-30 (wiring landed 2026-05-31)

## Context

`/rtp` can be abused to escape mid-fight: a losing player teleports away the instant combat turns against them. Competing plugins address this; EzRTP 3.3.0 shipped "PvP-Tag Integration" as a soft-depend on external combat-tag plugins (PvPManager, SimpleCombatLog) with two knobs (`cancel-countdown-on-pvp-tag`, `cancel-queued-on-pvp-tag`) and locale messages. It performs no native damage tracking, so it does nothing without a third-party combat-tag plugin installed.

RTP's [ROADMAP](../dev/ROADMAP.md) Tier 2 already scoped an "Optional PvP / combat-tag check": off by default, native damage tracking plus an external soft-depend path, gated at the `/rtp` pre-dispatch surface with an S-004 audit on refusal. This ADR records the design before implementation (Rule D-005), delivered incrementally - this increment is the platform-agnostic foundation (rtp-api SPI + rtp-core native fallback + gate evaluator + config); platform damage listeners, command/execution wiring, and the PvPManager/SimpleCombatLog adapters follow.

## Decision

Introduce an optional combat gate built on the same replaceable-provider pattern as the biome/anvil pre-filter:

1. **`rtp-api` SPI.** `PvPCombatStateRegistry` (single-binding `bind`/`current`/`clear`, mirroring `AnvilPrefilterRegistry`) with a `Provider#isInCombat(UUID)` functional interface, reachable via `RTPAPI.hooks().pvpCombatState()`. A `PvPCombatAction` enum (`ALLOW`, `DENY`, `DELAY`, `CANCEL`) enumerates the response and parses case-insensitively, failing safe to `DENY`.
2. **Native fallback (`rtp-core`).** `NativePvPCombatTracker` keeps `uuid -> last-PvP-damage-ms` and answers `isInCombat` against a configurable window. It holds no platform types and is unit-testable with an injected clock. Platform damage listeners feed it via the `PvPGate.nativeTracker()` singleton, stamping both victim and aggressor as configured.
3. **`PvPGate` evaluator (`rtp-core`).** Central decision point consulted at the `/rtp` pre-dispatch surface (before queue enrolment) *and* immediately before the destination is applied (execution prefilter). It reads `safety.yml`, picks the combat-state authority per `pvpSource` (`AUTO`/`NATIVE`/`EXTERNAL`), and returns the configured `PvPCombatAction`. A throwing external provider is audited at WARNING and treated as not-in-combat (REQ-RTP-S-004); the gate never blocks a teleport because an integration broke.
4. **Config.** New `safety.yml` keys: `pvpCheckEnabled` (default false), `pvpCombatTagSeconds` (15), `pvpOnCombat` (DENY), `pvpSource` (AUTO), `pvpTagVictim` (true), `pvpTagAggressor` (true), mirrored into every shipped locale via the locale TSV pipeline.
5. **External replacement.** Combat-tag plugins (PvPManager, SimpleCombatLog, CombatLogX) bind their own `Provider`, replacing the native check exactly as an external biome/anvil pre-filter replaces the built-in one - soft-depend adapters gated on plugin presence (catalog row in `EXTERNAL_HOOKS.md` per ADR-026).

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| External-only (EzRTP's approach) | Does nothing without a third-party plugin; RTP's native fallback works out of the box. |
| Region verifier (`RegionVerifierRegistry`) | Verifiers are per-coordinate, not per-player; combat state is a property of the requester, not the destination. |
| Hard-coded "deny on combat" | Operators want graduated responses (allow-and-audit, delay, cancel); hence the enum. |
| Raw scheduled executor for tag expiry | Forbidden on backend JVMs; the tracker prunes lazily on read, no background thread needed. |

## Consequences

- **Positive:** Feature-parity with EzRTP plus a zero-dependency native path; one replaceable seam consistent with the existing hook architecture; safe failure mode; off by default so no behavior change for existing servers.
- **Negative / Trade-offs:** Native tracking only sees damage the platform listener observes (direct melee/projectile PvP, incl. projectile shooter and primed-TNT igniter), not damage routed through other plugins - operators wanting authoritative combat state should bind an external provider. The pre-dispatch gate refuses a *new* `/rtp` for a combat-tagged player; aborting an already-running countdown/queued teleport mid-flight (the full `CANCEL`/`DELAY` semantics) is a later increment, so at the pre-dispatch surface `DENY`/`CANCEL`/`DELAY` all collapse to "refuse this request".

## Implementation status (2026-05-31)

The gate is no longer inert. Wiring landed across all in-scope platforms:

- **Command pre-dispatch** (`rtp-core`): `RTPCmd.compute` consults `PvPGate.evaluate(senderId)` before enrolling the requester, refusing with the configurable `messages.yml#pvpInCombat` string and a REQ-RTP-S-004 WARNING audit on a non-`ALLOW` action; `ALLOW`-and-in-combat is logged for operator visibility. Skipped for the console sender and cross-player (`player=`) targeting.
- **Native damage listeners**: Bukkit/Paper/Folia `OnPlayerCombatTag` (`EntityDamageByEntityEvent`) and Fabric `FabricEventBridge` (`ServerLivingEntityEvents.AFTER_DAMAGE`, reflection-guarded) stamp `PvPGate.nativeTracker()` for player-vs-player damage per `pvpTagVictim`/`pvpTagAggressor`. Both short-circuit when the gate is disabled.
- **Session hygiene**: `OnPlayerQuit` (Bukkit) and the Fabric disconnect handler clear the tracker on disconnect.
- **Config / locale**: new baseline `messages.yml#pvpInCombat`, propagated to every shipped locale via the locale TSV pipeline.
- **Tests**: `RTPCmdPvPGateTest` (pre-dispatch wiring) on top of the existing `PvPGateTest` / `NativePvPCombatTrackerTest`.

## References

- [ROADMAP Tier 2 - Optional PvP / combat-tag check](../dev/ROADMAP.md)
- [ADR-026 - external hook API surface](ADR-026-external-hook-api-surface.md), [EXTERNAL_HOOKS.md](../dev/EXTERNAL_HOOKS.md)
- `AnvilPrefilterRegistry` (the replaceable-provider precedent), REQ-RTP-S-004, REQ-RTP-F-013
- Code: `rtp-api/.../hooks/PvPCombatStateRegistry.java`, `PvPCombatAction.java`; `rtp-core/.../common/pvp/{NativePvPCombatTracker,PvPGate}.java`
