# ADR-043 — `rtp.personalqueue` Permission Semantics: Bucket-Only Opt-In

**Status:** Accepted
**Date:** 2026-05-14
**Supersedes (operational details of):** [ADR-007](ADR-007-per-user-isolated-queues.md)

## Context

[ADR-007](ADR-007-per-user-isolated-queues.md) established the existence of per-user isolated queues alongside the global region queue, gated by a permission node, as anti-flood / priority-tier insurance for trusted players. It did not pin down the *operational* meaning of the gating permission, and the resulting implementation conflated three distinct concerns under a single `RegionQueueManager.queue(UUID)` entry point:

1. **Open a per-player coordinate bucket** — `perPlayerLocationQueue.putIfAbsent(uuid, new ConcurrentLinkedQueue<>())`. The opt-in proper: future pregen output for this region MAY earmark a coordinate into this bucket instead of the shared `keptLocations`.
2. **Mark the player as awaiting a teleport** — `RTP.getInstance().queuedPlayers.add(uuid)`. The global flag consulted by `/rtp info`, the death/respawn cancel paths, and (planned) cross-server reservation token logic per `MULTI_SERVER_PLAN.md` REQ-RTP-NET-008/011/012/014.
3. **Enroll the player in the per-region teleport waitlist** — `playerQueue.add(uuid)`. The FIFO drained by `Region.execute`, which pairs each head UUID with the next available coordinate and runs `TeleportPipelineTask` (i.e. actually teleports the player).

The three `OnPlayer*` listeners that gate on `rtp.personalqueue` (`OnPlayerJoin`, `OnPlayerRespawn`, `OnPlayerChangeWorld`) call `region.queue(uuid)` and therefore receive all three side effects. The permission's documentation in `plugin.yml` describes it as *"reserve a next location for this player"* — i.e. concern (1) only. Concerns (2) and (3) are unintended; they leak the player into the teleport waitlist and the global awaiting-teleport flag despite the player not having requested a teleport. The only reason ops do not visibly teleport on join today is the defensive guard in `Region.execute` that discards waitlist entries with no `TeleportData` — that is not the contract, it is a backstop.

This ADR pins the intended semantics of `rtp.personalqueue` and the operational boundary between the three concerns. It supersedes the operational details of ADR-007 (the *rationale* in ADR-007 — anti-flood, priority tiering — remains the authoritative *why*).

## Decision

### Permission contract

`rtp.personalqueue` is an **opt-in for per-player coordinate reservation only**. Granting it to a player `P` in a region `R` shall:

- Cause subsequent pregeneration output for `R` to be eligible for earmarking into a coordinate bucket dedicated to `P`, instead of (or in addition to) the shared `keptLocations` pool.
- Cause `P`'s next `/rtp` (or on-event teleport) in `R` to draw from `P`'s bucket before falling back to the shared pool, per the existing `RegionQueueManager.poll(uuid)` priority order.

Granting `rtp.personalqueue` to `P` shall **not**:

- Enroll `P` in any teleport waitlist.
- Cause `P` to be teleported by any listener event (join, respawn, world change, move).
- Add `P` to `RTP.getInstance().queuedPlayers`, the global awaiting-teleport flag.
- Reserve a network-mode reservation token (per ADR-036 / `MULTI_SERVER_PLAN.md`) on `P`'s behalf.

### Permission resolution

`rtp.personalqueue` is treated as an **op-provided permission**. Direct `player.hasPermission("rtp.personalqueue")` is the canonical gate. Operators may receive it through their server's permission manager's op-default short-circuit (e.g. Paper + LuckPerms resolving unset `default: false` against op state); this is acceptable behaviour for this node and does not require the `ParsePermissions.getEffectivePermissions()` routing used by `rtp.onevent.*` (see CHANGELOG `[3.0.0-beta.2]` "On-event teleports granted to operators by default"). Rationale: the side effects of an op silently receiving a personal bucket are bounded (one additional bucket per online op, one earmarked coordinate, no teleport, no cooldown drain, no log line), whereas the side effects of an op silently auto-teleporting on every join were not.

### Bucket lifecycle

The personal bucket for `P` in `R` is keyed by `P`'s UUID inside `R.queueManager.perPlayerLocationQueue` and shall obey the following lifecycle:

- **Open** — on any event that establishes `P` as a current participant in `R` for whom `rtp.personalqueue` resolves true. The current event set is `PlayerJoinEvent` (and the Fabric equivalent), `PlayerRespawnEvent`, and `PlayerChangedWorldEvent`. The open operation shall be idempotent for already-open buckets.
- **Fill** — by the region's pregen pipeline (`RegionCacheTask`), driven by a per-uuid in-flight guard so a single open bucket attracts at most one in-flight reservation at a time. The fill path is internal scheduling; this ADR does not mandate push-on-open vs. demand-pull, but mandates that it MUST exist (a permanently empty bucket is a defect, not a feature).
- **Drain** — by `RegionQueueManager.poll(uuid)` when `P` initiates a teleport, before falling back to `keptLocations`. Unchanged from current behaviour.
- **Close** — on `P` disconnecting (`PlayerQuitEvent` or platform equivalent) from `R`'s backend, or on `P`'s permission for `R` being revoked (best-effort, on next reload). Close shall return all unclaimed coordinates in the bucket to `unkeptLocations` (closing reservations first) so the pregen budget is not stranded by player churn.

A bucket leaked across a session boundary (player disconnects, bucket remains, the reserved coordinate's chunk ticket remains active) constitutes an `S-002`-adjacent defect (force-loaded chunks held without a live consumer).

### API shape

`RegionQueueManager.queue(UUID)` shall be retired in favour of a decomposed surface that maps one-to-one to the three concerns above:

- `openPersonalQueue(UUID)` — concern (1). Public. Called by listeners.
- `closePersonalQueue(UUID)` — inverse of (1). Public. Called by the disconnect listener.
- `requestTeleport(UUID)` — concerns (2) + (3) bundled, since they are coextensive in current code (every "I want to teleport now" intent sets both). Public, but called only from teleport-initiating code paths (`RTPCmd`, `OnEventTeleports.teleportAction`, the network-mode arrival path).

`Region.queue(UUID)` (the plugin-facing facade) shall mirror the same split. The old single-method form is removed in the same beta cycle; carrying a misleading name forward as a deprecated shim risks new call sites picking the wrong semantics.

### Default DB persistence

`enqueuePlayerLocation` already writes the earmarked coordinate to `databaseAccessor` keyed by `(region.name, uuid)` per ADR-002. After the split, hydration on `openPersonalQueue(uuid)` may re-attach coordinates that were saved before the player's previous disconnect — this is a soft requirement (`SHOULD`), not part of the bucket lifecycle's hard contract, because the existing hash-keyed cache invalidation rules from ADR-022 already cover the staleness case.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep the bundled `queue(UUID)` and document the side effects | Documentation cannot fix a misnamed method; new callers will keep picking the wrong semantics. The op short-circuit issue is incidental; the API design is the root cause. |
| Route `rtp.personalqueue` through `ParsePermissions.getEffectivePermissions()` like `rtp.onevent.*` | Side effects of an op silently receiving a personal bucket are bounded and harmless; the routing cost is a per-join effective-permission scan with no commensurate benefit. Explicitly rejected by the requesting user. |
| Auto-enroll the head of `playerQueue` into a personal bucket on the fly (no separate opt-in) | Conflates "this player is currently teleporting" with "this player is privileged" — the priority tier from ADR-007 disappears. Equivalent to today's bug. |
| Tear down the personal bucket on every `Region.execute` drain instead of on disconnect | Defeats the priority-tier guarantee: the bucket would only ever hold one coordinate and the player would compete with the shared pool for the next one. Equivalent to no personal queue. |
| Make `rtp.personalqueue` a default-true permission for ops via `plugin.yml` `children:` instead of the runtime op short-circuit | Doable but unnecessary; the runtime short-circuit already provides the same behaviour for the same set of users, and `plugin.yml` declarations do not propagate to platforms (Fabric) that do not read it. |

## Consequences

- **Positive:**
  - The contract of `rtp.personalqueue` matches its `plugin.yml` description, eliminating the latent "ops get added to the teleport waitlist on every join" behaviour drift.
  - `playerQueue` and `RTP.getInstance().queuedPlayers` regain their literal meaning ("currently waiting for a coordinate"), which is a prerequisite for ADR-036 (network mode) treating `queuedPlayers` / `playerQueue` as the authoritative source for `REQ-RTP-NET-008` (network wait queue) and `REQ-RTP-NET-011/012/014` (reservation tokens).
  - The personal-queue feature becomes meaningfully implemented end-to-end (today, the bucket is created but no production code path fills it; see audit notes in the referenced issue).
  - The bucket-lifecycle close-on-quit step closes a latent `S-002`-adjacent leak (chunk reservations stranded in disconnected players' buckets).

- **Negative / Trade-offs:**
  - Public-API change. `Region.queue(UUID)` is removed; addons that called it inherit a compile error in the same beta cycle. The renaming surfaces the bug at every existing call site, which is the desired outcome.
  - One additional listener (`PlayerQuitEvent` / platform equivalent) per backend to hold the bucket-close contract. Negligible runtime cost.
  - The pregen wiring (`RegionCacheTask`) gains a per-uuid in-flight guard. Negligible state, but it must be threaded through Folia's region-scheduler invariants — covered by the existing `inFlightCalculations` discipline.

## References

- Implementing classes (post-decision): `RegionQueueManager` (decomposed API), `Region` (facade mirror), `RegionCacheTask` (per-uuid fill wiring), `OnPlayerJoin` / `OnPlayerRespawn` / `OnPlayerChangeWorld` / `OnPlayerQuit` (listeners), and the Fabric equivalents under `rtp-fabric/.../events/`.
- Related ADRs: [ADR-006](ADR-006-async-queue-pre-generation.md) (pre-gen rationale), [ADR-007](ADR-007-per-user-isolated-queues.md) (priority-tier rationale, operationally superseded here), [ADR-002](ADR-002-h2-sqlite-over-flat-file-cache.md) (DB hydration of earmarked coordinates), [ADR-022](ADR-022-shape-cache-key-seed-plus-config-hash.md) (cache staleness on config change), [ADR-036](ADR-036-network-mode-multi-server-multi-proxy.md) (`playerQueue` / `queuedPlayers` as the network wait queue substrate).
- Requirements: `REQ-RTP-NF-002` (resource starvation prevention, from ADR-007), `REQ-RTP-S-001` (permission-based access control, from ADR-007), `REQ-RTP-S-002` (no permanently force-loaded chunks — invoked by the close-on-quit requirement).
- Glossary: *"per-player queue" / "personal queue"* alias in [`.junie/AGENTS.md`](../../.junie/AGENTS.md) (Domain Analogies & Aliases table) points at `perPlayerLocationQueue` + `playerQueue`; that alias's prose must be updated to reflect the post-split semantics (the alias entry now points at `perPlayerLocationQueue` for the bucket and at `playerQueue` for the teleport waitlist, as two separate concepts).
- Issue trail: `docs/dev/POTENTIAL_BUGS.md` — pre-split entry recording the conflation (to be removed after this ADR's implementation lands, per `POTENTIAL_BUGS.md` hygiene rules).
