# rtp-proxy-ADR-015 - Shared Network Waitlist and Dynamic Batched Dispatch

**Status:** Accepted (2026-05-21; Slice 1 Slice 2 landed 2026-05-21)
**Refines:** [rtp-proxy-ADR-014](rtp-proxy-ADR-014-backend-owned-rtp-with-network-queue.md), [rtp-proxy-ADR-001](rtp-proxy-ADR-001-spi-shape.md)
**Depends on:** [ADR-036](../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md), [REQ-RTP-NET-015](../../../docs/dev/REQUIREMENTS.md)
**Tracking:** [`docs/dev/scratch/CHECKLIST-network-waitlist.md`](../../../docs/dev/scratch/CHECKLIST-network-waitlist.md)

## Context

ADR-014 (L6) defined the cross-server `/rtp` dispatch path:

```
lobby /rtp -> NetworkRouter -> NetworkRequestQueue (Redis BLPOP)
            -> proxy DefaultRtpDispatcher -> ReservationClient.claim
            -> transferPlayer -> backend redeemReserved
```

Today, when `DefaultRtpDispatcher` cannot place the player on a backend right now (every peer is kill-switched, the requested region has no peer with `networkKeptCount > 0`, the rate limit is exhausted, or `BackendSelector` returns no qualifying backend), the request resolves as `FAILED` and the lobby surfaces a configurable terminal message. The player must re-invoke `/rtp` until a backend is free.

This contradicts the per-backend `/rtp` UX, where `RegionQueueManager.playerQueue` notifies the player ("you are #N in queue"), locks further `/rtp` commands for that UUID until served, and drains in a bounded batch per `QueueTask` pulse. The local UX is the contract operators expect; the network UX should mirror it.

Two cross-cutting constraints distinguish the network case from the single-backend case:

1. **The waitlist is shared across proxies.** Multiple proxies (REQ-RTP-NET-014) read the same coordination store; a player who enrols through `proxy-1` must be removable by `proxy-2` if `proxy-2` is the one that learns about the disconnect.
2. **Available capacity is dynamic and per-backend.** Each backend publishes `BackendHeartbeat.networkKeptCount` once per heartbeat interval; a drain pulse should take exactly as many envelopes from the head of the waitlist as the qualifying backends can absorb right now (sum of their kept counts), no more, no less. A fixed batch-size knob would either starve the waitlist or oversubscribe a backend whose reserve was just drained.

## Decision

Introduce a new sibling SPI to `NetworkRequestQueue`, named `NetworkWaitlist`, alongside two implementations:

- `InMemoryNetworkWaitlist` - reference impl, single-JVM, devstack / unit tests / `transport.kind = memory` fallback.
- `RedisNetworkWaitlist` - production impl, atomic via Lua scripts. **Landed in Slice 3b (2026-05-21)**: seven Lua scripts (`waitlist_enrol`, `waitlist_peek`, `waitlist_drain_batch`, `waitlist_position`, `waitlist_remove_uuid`, `waitlist_reap`, `waitlist_refresh_ttl`) under `src/main/resources/redis/`, each with a SHA1 sidecar verified at load (LF-normalized). Keyspace: `rtp:net:waitlist:list` (FIFO LIST<json>), `rtp:net:waitlist:uuids` (SET for O(1) duplicate guard and point-remove), `rtp:net:waitlist:cids` (SET for correlationId idempotency). Drain is two-step under the `WaitlistLeaderLease` single-drainer guarantee: `waitlist_peek` (non-mutating LRANGE) followed by client-side per-backend allocation followed by `waitlist_drain_batch` (atomic LREM per allocated correlationId). Verified by no-Redis `RedisNetworkWaitlistScriptLoadTest` (sidecar parity) and opt-in `RedisNetworkWaitlistIT` (12 cases, `RTP_REDIS_IT=true` gated).

The dispatcher rewiring (`DefaultRtpDispatcher` parks unservable envelopes), the proxy-side drainer with leader-lease election, and the lobby-side notify + command-lock + quit-removal wiring are **Slices 2-4** of the checklist and are described here at interface level only - their concrete implementation slices will not change the SPI shape this ADR introduces.

### SPI Shape (Slice 1, this ADR)

```java
public interface NetworkWaitlist {
    enum EnrolOutcome { ACCEPTED, REJECTED_FULL, REJECTED_DUPLICATE, REJECTED_CLOSED }
    enum CancelReason { PLAYER_DISCONNECT, TTL_EXPIRED, EXPLICIT_REQUEST,
                        BACKEND_UNAVAILABLE, TRANSPORT_ERROR }

    record WaitEnvelope(UUID playerId, UUID correlationId,
                        Optional<String> regionKey, Optional<String> serverHint,
                        String originServerId, long enrolledAtMs) { }

    CompletableFuture<EnrolOutcome>                       enrol(WaitEnvelope env);
    CompletableFuture<Map<String, List<WaitEnvelope>>>    drainBatch(Map<String,Integer> perBackendCaps, int globalCap);
    CompletableFuture<Optional<Integer>>                  position(UUID playerId);
    CompletableFuture<Boolean>                            remove(UUID playerId, CancelReason reason);
    CompletableFuture<Integer>                            reap(Duration maxAge);
    CompletableFuture<Integer>                            size();
}
```

Five design points:

1. **Sibling, not subtype.** `NetworkRequestQueue` is BLPOP-shaped (one dequeue per call, no random access). The waitlist needs position-by-UUID, point-remove-by-UUID, and batch-drain-with-per-backend-caps - none of which `NetworkRequestQueue` exposes. A new SPI is cheaper than overloading the existing one and leaves ADR-014's hot path unmodified.
2. **At-most-one entry per `playerId`.** `enrol` rejects with `REJECTED_DUPLICATE` if the UUID already has a live entry under a different `correlationId`. This is the contract the lobby-side command lock depends on - "is the player on the waitlist?" reduces to "does `position(uuid)` resolve non-empty?".
3. **Idempotency by `correlationId`.** A replay of the same envelope resolves `ACCEPTED` without a second insert. The lobby may retry an `enrol` call after a transient transport error without risking a duplicate-rejection on the second try.
4. **`drainBatch` sized by the caller.** The SPI does not know what `networkKeptCount` is, what a heartbeat is, or what a `BackendSelector` is. The drainer passes a `Map<serverId, cap>` snapshot computed from the latest heartbeats; the SPI's job is FIFO-preserving allocation across that map, head-blocking when no backend qualifies for the head envelope's pinned region. The `globalCap` argument is a defence-in-depth ceiling (default = sum of per-backend caps).
5. **`remove` is point-keyed by `playerId`, not `correlationId`.** The lobby's `PlayerQuitEvent` knows the UUID; it does not necessarily know which correlationId was active. Keying remove by UUID matches the disconnect-correction signal the operator gave during proposal.

### In-Memory Reference Impl

`InMemoryNetworkWaitlist` backs the SPI with a `LinkedHashMap<UUID, WaitEnvelope>` for O(1) duplicate-rejection, O(1) point-remove, and FIFO `position` / `drainBatch` traversal. All access is serialized through a single-thread executor (the same pattern `InMemoryNetworkRequestQueue` uses), so every public method is atomic from the caller's perspective. `maxSize` is a constructor knob (default `1024`); over-capacity enrolments resolve `REJECTED_FULL` rather than throwing or silently dropping - this preserves S-004 attribution at the SPI boundary.

The reference impl's `drainBatch` selection is deterministic (smallest-keyed `serverId` with cap > 0 wins each round). Production impls are free to choose differently (round-robin, weighted, region-pinned). Determinism in the reference impl is a test-stability decision, not a public contract.

### Status Coverage (Slice 2, landed)

ADR-014's `NetworkRequestQueue.QueueState` was extended with a new `WAITLISTED` value (rather than reusing `QUEUED` as the Slice 1 ADR draft suggested) so the lobby-side `NetworkStatusCache` (Slice 4) can distinguish "parked on the shared cross-proxy waitlist, awaiting capacity" from "claimed and dispatched into the per-backend queue". `QUEUED` retains its ADR-014 meaning ("envelope on the per-backend FIFO awaiting `dequeueReady`"). The new value is documented in `NetworkRequestQueue.QueueState` and emitted by `DefaultRtpDispatcher` via `StatusSink` whenever the no-backend branch parks onto a configured `NetworkWaitlist`.

### Total-Failure Backoff (Slice 2, landed)

When `NetworkWaitlistDrainer` drains a batch and *every* drained envelope fails dispatch (no backend qualified, all kill-switched mid-flight, transport partition), the network is unable to serve right now. The drainer responds with:

1. `NetworkWaitlist.refreshAllTtl()` - refreshes every live entry's `enrolledAtMs` to the current wall clock so the subsequent `reap(maxAge)` sweep does not silently age entries out due to time spent waiting on a dead network.
2. A pause window (`pauseFor`, default 2 s) during which subsequent `runPulse()` calls short-circuit to `SKIPPED_PAUSED` rather than hammer a known-bad network.

This is the "if it fails on all servers in order of priority, refresh all TTL in the queue and pause" semantic the issue requested.

### Leader Election (Slice 2, landed; Redis impl Slice 3a, landed)

`WaitlistLeaderLease` is a sibling SPI to `NetworkWaitlist`. The reference impl `AlwaysLeaderLease` is sufficient for single-proxy and in-memory devstack deployments; `RedisLeaderLease` (Slice 3a, landed 2026-05-21) uses `SET key holderId NX PX <ms>` for first acquire, `SET XX PX` for same-holder re-extend (idempotent per SPI contract), and a compare-and-DEL Lua for `release` so a stale holder cannot stomp a successor's lease after TTL handover. Verified by opt-in `RedisLeaderLeaseIT` (8 cases, `RTP_REDIS_IT=true` gated).

### Lobby-side UX (Slice 4, landed 2026-05-21)

The lobby (`rtp-plugin`) consumes the proxy-emitted `WAITLISTED` state via three new pieces wired into `NetworkModeBootstrap`:

- `NetworkStatusCache.QueueStatus.State.WAITLISTED` was added alongside the existing `QUEUED`/`ROUTING`/`RESERVED`/`TRANSFERRING`/terminal values. The bootstrap-side adapter at `NetworkModeBootstrap` line ~360 was already `State.valueOf(proxyStatus.state().name())`, so proxy `WAITLISTED` rows now flow through without an explicit branch. A new `QueueStatus.nonTerminal()` helper centralizes the truth table used by the guard.
- `NetworkWaitlistGuard implements Predicate<CommandSender>` is registered on `RTPCmdBukkit` via `addSenderCheck(...)`. While the caller's cache entry is non-terminal (any of `QUEUED`, `WAITLISTED`, `ROUTING`, `RESERVED`, `TRANSFERRING`), `/rtp*` invocations short-circuit with a configurable message resolved from `MessagesKeys.alreadyQueued` (placeholder `[position]`). See "Locale and Message Coverage (Slice 5)" below.
- `NetworkWaitlistNotifier` runs on `RTP.scheduler.runTaskTimerAsynchronously` at the same cadence as the existing status-cache poll. Per pulse it walks the cache snapshot, emits `msgNetworkQueued`-style messages for `WAITLISTED` rows, and dedupes per-UUID by `(body, lastEmittedAtMs)` so the player only sees a new line when their position changes or the configured `network.waitlist.notifyIntervalSeconds` (default 5) elapses. Dedup entries for players evicted from the cache or transitioned out of `WAITLISTED` are dropped on the next pulse.
- `NetworkWaitlistQuitListener implements Listener` is registered alongside the existing `JoinTriggerSource` (sibling `registerWaitlistQuitListener(plugin)` method). On `PlayerQuitEvent` it calls `NetworkRequestQueue.cancel(uuid, PLAYER_DISCONNECT)`; that SPI op is already idempotent and safe when there is no live entry. Failures are logged via `RTP.log` per S-004 but never propagate out of the event dispatch.

The whole UX surface is gated on `network.enabled = true` and on `boot()` reaching status-cache wiring; on single-server installs (or when the bootstrap's no-op path is taken) the guard is null, the notifier is null, the listener is null, and the existing `/rtp` path is byte-for-byte unchanged.

Verified by `ReqRtpNet015NetworkWaitlistTest` (Slice 4 REQ-traceable suite: state truth table, guard rendering, notifier dedup / interval / position-change / cache-eviction / state-transition, quit-listener constructor contract, and a pinned `name()`-mapping sanity test guarding against silent enum drift between the proxy and lobby state enums).

### Locale and Message Coverage (Slice 5, landed 2026-05-21)

The user-facing message keys land as three new `MessagesKeys` enum entries (`alreadyQueued`, `networkTimedOut`, `waitlistFull`); the 4th planned key `networkQueued` already existed from a prior pass. Baseline `messages.yml` rows added with English values and leading comment blocks. `NetworkWaitlistGuard.formatMessage(...)` and `NetworkWaitlistNotifier.renderBody(...)` now resolve their templates from `RTP.configs.getParser(MessagesKeys.class).getConfigValue(...)`, with hardcoded English defaults retained only as a fallback for early-bootstrap / test contexts. The previous dual constants for "position known" vs "position unknown" collapsed to a single `[position]`-substitution path (empty substitution when the proxy has not yet assigned one). Locale TSV pipeline (`scripts/locale-files-to-csv.ps1` -> `scripts/reconcile-locale-csvs.ps1` -> `scripts/locale-files-from-csv.ps1`) was run end-to-end so every shipped locale carries the three new keys identity-seeded from English per `TRANSLATION_GUIDE.md` section 8; native-speaker review remains a routine locale follow-up.

## Consequences

**Positive.**

- The cross-server UX matches the per-backend UX one feature at a time (notify, lock, batch). Operators do not have to learn two divergent queue models.
- The waitlist is additive: when `network.mode = off` or `transport.kind = memory` and no drainer is started, the SPI is unreferenced and the ADR-014 hot path is byte-for-byte unchanged.
- `BackendHeartbeat.networkKeptCount` (added in ADR-014) becomes the load signal for batch sizing without any new wire-format addition; the heartbeat is already polled by `BackendSelector`.
- Per-player point-remove on `PlayerQuitEvent` removes a known operational sharp edge: under ADR-014 alone, a player who quits before their reservation lands leaks a `ReservationToken` until the proxy reaper expires it. The waitlist's point-remove path closes that window for the parked-but-not-yet-dispatched case.

**Negative.**

- One more SPI surface (`NetworkWaitlist`) and one more proxy daemon (`NetworkWaitlistDrainer`, Slice 2) for operators to learn. Mitigated by the in-memory impl being the devstack default and the production Redis impl deferring to a later slice.
- Two cooperating data structures (`NetworkRequestQueue` and `NetworkWaitlist`) instead of one. The dispatcher must choose at request time which to write to; the choice is `try-claim, on-no-backend write-waitlist`, but the policy is now policy-in-code rather than policy-in-config. Justified by the SPI mismatch above.
- The Redis impl (landed Slice 3b, 2026-05-21) introduces seven Lua scripts and the `rtp:net:waitlist:*` keyspace. Script identity is pinned by checked-in `.sha1` sidecars per the project's existing convention; a whitespace edit that fails to regenerate the sidecar refuses to enable at startup.

**Risk-mitigated.**

- A single-backend network sees no behavior change: the lobby still dispatches every request via the existing FIFO, and the waitlist remains empty.
- The `maxSize` cap prevents an unbounded-growth pathology when every backend is down for an extended outage; over-capacity enrolments fail loudly (`REJECTED_FULL`) rather than silently consuming proxy memory.

## Alternatives Considered

- **Extend `NetworkRequestQueue` with a `parkUnready` operation.** Rejected: would force every existing impl (`InMemoryNetworkRequestQueue`, `RedisNetworkRequestQueue`, `SqlNetworkRequestQueue`) to grow position-query and point-remove semantics they do not need for the FIFO path, doubling each impl's surface area.
- **Per-backend wait queues (one queue per backend serverId).** Rejected: a player who requested region `mining` and is parked behind backend-A's queue cannot benefit when backend-B suddenly frees a `mining` slot. A single FIFO with caller-supplied per-backend caps preserves cross-backend fairness.
- **In-process per-lobby waitlist (no shared store).** Rejected by the operator during proposal: lobbies are not the authority on which proxy will eventually serve the player, and a lobby restart would forget the waitlist. The shared-store model is the explicit ask.

## References

- [`docs/dev/scratch/CHECKLIST-network-waitlist.md`](../../../docs/dev/scratch/CHECKLIST-network-waitlist.md) - slice plan.
- [REQ-RTP-NET-015](../../../docs/dev/REQUIREMENTS.md) - shared network waitlist requirement.
- [rtp-proxy-ADR-014](rtp-proxy-ADR-014-backend-owned-rtp-with-network-queue.md) - cross-server dispatch baseline (refined here).
- [`rtp-proxy-common/src/main/java/.../spi/NetworkWaitlist.java`](../../rtp-proxy-common/src/main/java/io/github/dailystruggle/rtp/proxy/common/spi/NetworkWaitlist.java) - SPI source.
- [`rtp-proxy-common/src/main/java/.../transport/memory/InMemoryNetworkWaitlist.java`](../../rtp-proxy-common/src/main/java/io/github/dailystruggle/rtp/proxy/common/transport/memory/InMemoryNetworkWaitlist.java) - reference impl.
- [`rtp-proxy-common/src/test/java/.../transport/memory/InMemoryNetworkWaitlistTest.java`](../../rtp-proxy-common/src/test/java/io/github/dailystruggle/rtp/proxy/common/transport/memory/InMemoryNetworkWaitlistTest.java) - SPI conformance tests.
