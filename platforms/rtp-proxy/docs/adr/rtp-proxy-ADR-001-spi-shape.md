# rtp-proxy-ADR-001 — `rtp-proxy-common` SPI Shape

**Status:** Accepted
**Accepted:** 2026-05-14
**Date:** 2026-05-13
**Refines:** [ADR-036 — Network Mode (Multi-Server, Multi-Proxy)](../../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md)

## Context

ADR-036 ratified the existence of `rtp-proxy-common` as the vendor-neutral scaffolding consumed by `rtp-proxy-velocity` (Phase 2) and `rtp-proxy-bungee` (Phase 3). Before any of those adapters can be opened, the shared SPI must be pinned so that:

- Velocity- and Bungee-side glue can be authored against a stable contract.
- The reference `InMemoryNetworkStateBinding` (Phase 1) and `RedisNetworkStateBinding` (Phase 2) can implement a single `NetworkTransport` interface.
- The umbrella requirement `REQ-RTP-PROXY-001` (*adapter SPI conformance*) is testable.
- The `REQ-RTP-PROXY-COMMON-001` constraint that `rtp-proxy-common` import no proxy-vendor class is enforceable at the package boundary.

Without this ADR, every other Phase 1 ADR (`-002` schema, `-003` in-memory binding, `-004` selector) and every Phase 2/3 adapter ADR would have to re-litigate the same interface decisions.

## Decision

`rtp-proxy-common` exposes the following five SPI surfaces. Each is an interface (not an abstract class) in package `io.github.dailystruggle.rtp.proxy.common.spi`. Reference implementations live in sibling packages and may be replaced by adapters or addons.

### 1. `RtpDispatcher`

Single entry point invoked by trigger sources (command, join, event). Owns the end-to-end "route → reserve → transfer" lifecycle.

```
CompletableFuture<DispatchOutcome> dispatch(RtpRequest request);
```

- Pure async. No blocking call on any thread.
- `RtpRequest` is an immutable record: `playerId`, `triggerType`, `regionKey?`, `worldKey?`, `originServerId?`, `correlationId`.
- `DispatchOutcome` is a sealed result: `Routed(serverId, tokenId)`, `Queued(positionHint)`, `Failed(reason, messageKey)`.

### 2. `BackendSelector`

Pure-function backend chooser. **No I/O** during evaluation (REQ-RTP-PROXY-COMMON-002).

```
Optional<String> choose(RtpRequest request, NetworkSnapshot snapshot);
```

- `NetworkSnapshot` is an immutable view of the latest `backend_state` rows the proxy has observed (assembled by the transport, **not** fetched by the selector).
- Stale-row filtering (`loadBalancer.staleAfterMs`) happens inside `choose`, against `snapshot.timestamp`.
- Returns `Optional.empty()` when no candidate qualifies; the dispatcher decides whether to queue or fail.

### 3. `NetworkTransport`

The pluggable shared-store binding (Redis / Postgres / generic-SQL / in-memory / plugin-message). Async-only.

```
CompletableFuture<NetworkSnapshot> readSnapshot();
CompletableFuture<ReservationToken> claim(String serverId, String playerId, Duration ttl);
CompletableFuture<Void> release(String tokenId, ReleaseReason reason);
CompletableFuture<Void> publishProxyHeartbeat(ProxyHeartbeat row);
Subscription subscribeBackendHeartbeats(Consumer<BackendHeartbeat> sink);
void close();
```

- Listener callbacks (`subscribeBackendHeartbeats`) may arrive on arbitrary transport threads; the contract **requires** the consumer to hop to `RTP.scheduler` (or the host proxy's async scheduler) before touching world/player state. The SPI documents this; it does not enforce it (cost).
- `claim` is the atomic `PENDING→CLAIMED` primitive (REQ-RTP-PROXY-004). Implementations must guarantee row-count atomicity per ADR-036's multi-proxy idempotency contract.

### 4. `ProxySender`

Vendor-neutral wrapper over the host proxy's "send player to backend" capability.

```
CompletableFuture<TransferOutcome> sendTo(UUID playerId, String serverId, ReservationToken token);
boolean isConnected(UUID playerId);
void sendMessage(UUID playerId, MessageKey key, Map<String, String> placeholders);
```

- `sendTo` adapts to `ServerPreConnectEvent` (Velocity) or `ServerConnectEvent` (Bungee). The adapter resolves the `serverId` against its local server registry.
- `sendMessage` routes through the configurable-messaging layer (REQ-RTP-PROXY-006) — never a hardcoded literal.

### 5. `ReservationClient`

Convenience helper that composes `BackendSelector` + `NetworkTransport.claim` with the capped-retry chain (REQ-RTP-PROXY-COMMON-004).

```
CompletableFuture<ClaimResult> obtain(RtpRequest request);
```

- Implements the `maxRetries / attemptTimeoutMs / cooldownMs` policy.
- On claim-race loss (row-count = 0), retries against the next-best backend from the same snapshot up to the cap, then surfaces `Failed(reason=CLAIM_RACE, …)` so the dispatcher can resolve a configurable message.

## Package Boundary Rules

| Package | May import |
|---------|-----------|
| `io.github.dailystruggle.rtp.proxy.common.spi` | `rtp-api`, JDK only |
| `io.github.dailystruggle.rtp.proxy.common.selector` | `spi`, `rtp-api`, JDK |
| `io.github.dailystruggle.rtp.proxy.common.reservation` | `spi`, `rtp-api`, JDK |
| `io.github.dailystruggle.rtp.proxy.common.trigger` | `spi`, `rtp-api`, JDK |
| `io.github.dailystruggle.rtp.proxy.common.config` | `spi`, `rtp-api`, JDK |
| (any package in `rtp-proxy-common`) | **Never:** `com.velocitypowered.*`, `net.md_5.bungee.*`, `org.bukkit.*`, `net.minecraft.*`, `net.fabricmc.*` |

A package-level architecture test (Phase 1 deliverable) enforces the prohibition above.

## Threading Contract

- All SPI methods that return `CompletableFuture` shall complete on a transport-owned executor; consumers must `.thenAcceptAsync(…, hostScheduler)` to re-enter proxy-managed threads.
- No SPI method shall be marked `synchronized`. No SPI method shall block (`Future.get`, `CountDownLatch.await`, etc.).
- Listener subscriptions (`subscribeBackendHeartbeats`) deliver events serially **per subscription** but make no ordering guarantee across subscriptions.

## Versioning

- All wire-format records carry `schemaVersion : int`. Mismatched payloads are rejected (REQ-RTP-PROXY-007).
- SPI source compatibility is broken only via a superseding ADR. Adding a default method to an interface is permitted within a minor bump; removing or renaming a method requires a major bump and a deprecation cycle of at least one release.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| One fat `ProxyAdapter` interface | Forces unrelated concerns (transfer + transport + selection) onto adapter authors; impossible to mock granularly in tests. |
| Abstract classes instead of interfaces | Locks single-inheritance; blocks future composition by addons. |
| Reactive Streams (`Publisher<T>`) for heartbeat subscription | Adds a dependency surface (`org.reactivestreams`) to a vendor-neutral module; `Consumer<T>` + `Subscription` cancel-handle is sufficient for the v1 cadence (1s heartbeat). |
| Sync `claim(...)` returning the token | Forces every adapter onto its own async wrapper; violates REQ-RTP-PROXY-COMMON-001's purity in spirit. |
| Per-proxy SPI in each adapter module | Duplicates contracts; defeats the "extend `commands-api`, don't fork" principle the Architecture Boundaries enshrine. |

## Consequences

- **Positive:**
  - Velocity and Bungee adapters can be authored in parallel against a frozen contract once this ADR is Accepted.
  - The in-memory binding (Phase 1) becomes a drop-in for tests and for the `network.enabled:false` no-op contract (REQ-RTP-NET-002).
  - Architecture-boundary linting is a single rule: "no vendor imports under `rtp-proxy-common`".
- **Negative / Trade-offs:**
  - Five interfaces is more surface than one fat adapter; reviewers must understand the separation.
  - Async-only is strictly enforced — adapter authors who reach for `.get()` for "just this once" must be caught in review (the SPI cannot mechanically forbid it).
  - Thread-hop responsibility sits with the consumer, not the SPI. Mis-implementing this on Folia would violate S-005; the umbrella ADR-036 already lists this as a top risk.

## References

- Umbrella: [`docs/adr/ADR-036`](../../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md)
- Plan: [`docs/dev/MULTI_SERVER_PLAN.md`](../../../../docs/dev/MULTI_SERVER_PLAN.md)
- Requirements: [`rtp-proxy/REQUIREMENTS.md`](../../REQUIREMENTS.md) (`REQ-RTP-PROXY-001…011`), [`rtp-proxy-common/REQUIREMENTS.md`](../../rtp-proxy-common/REQUIREMENTS.md) (`REQ-RTP-PROXY-COMMON-001…008`)
- Network REQs: [`docs/dev/REQUIREMENTS.md section 1.6`](../../../../docs/dev/REQUIREMENTS.md) (`REQ-RTP-NET-007`, `-009`, `-010`, `-012`, `-014`)
- Architecture Boundaries: [`.junie/AGENTS.md`](../../../../.junie/AGENTS.md)
