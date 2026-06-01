# rtp-proxy-ADR-003 — Reference `InMemoryNetworkStateBinding`

**Status:** Accepted
**Accepted:** 2026-05-14
**Date:** 2026-05-13
**Refines:** [ADR-036](../../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md)
**Depends on:** [rtp-proxy-ADR-001](rtp-proxy-ADR-001-spi-shape.md), [rtp-proxy-ADR-002](rtp-proxy-ADR-002-network-yml-schema.md)

## Context

`MULTI_SERVER_PLAN.md` lists `InMemoryNetworkStateBinding` as one of four `NetworkTransport` implementations and as the **default when `network.enabled:false`**. It is also the only binding suitable for:

- Unit tests of `RtpDispatcher`, `BackendSelector`, and `ReservationClient` without standing up Redis/Postgres.
- The Phase 1 no-op contract test that validates REQ-RTP-NET-002 (byte-identical behaviour vs. today's single-server build).
- Reproducer harnesses for multi-proxy idempotency edge cases (claim races, orphan reanimation) without external infrastructure.

It is **not** suitable for production: state lives in-process; multi-host deployments would each see a different world.

## Decision

Ship a `InMemoryNetworkStateBinding implements NetworkTransport` in `rtp-proxy-common`, package `io.github.dailystruggle.rtp.proxy.common.transport.memory`. Characteristics:

- **Single-process scope.** Backed by `ConcurrentHashMap` for backend/proxy heartbeats, `ConcurrentHashMap<String, ReservationToken>` for tokens, and a `CopyOnWriteArrayList<Consumer<BackendHeartbeat>>` for subscribers.
- **Async semantics preserved.** Every SPI call returns a `CompletableFuture` completed on a dedicated `ForkJoinPool` (size 2) — never the caller's thread — so consumers exercising async hops behave identically to a real binding.
- **Deterministic test mode.** A `setClock(Supplier<Instant>)` hook lets tests pin time for TTL expiry and `claimReanimateMs` cases; production callers never touch it.
- **Atomic claim primitive.** `claim` uses `tokens.compute(tokenId, …)` to enforce row-count atomicity (PENDING→CLAIMED transitions on a single AtomicReference field of `ReservationToken`).
- **Heartbeat fan-out.** `publishProxyHeartbeat` / publish-backend-heartbeat dispatches on the same executor; subscribers see events in publish order per subscription.
- **Bounded by config.** `transport.poolSize`, `connectTimeoutMs`, `readTimeoutMs` from ADR-002 are honored as: pool sizing, fast-fail on `compute` re-entry, and a synthetic timeout on `readSnapshot` (used to verify dispatcher timeout handling).

### Disabled-Mode Contract

When `network.enabled: false`:

- The binding **is not instantiated**.
- `RtpDispatcher` is not wired.
- No `proxy_state` / `backend_state` rows are written anywhere.
- This is the REQ-RTP-NET-002 invariant. A Phase 1 test (`ReqRtpNet002NoopParityTest`) loads a real `network.yml` with `enabled:false` and asserts zero observable side effects vs. a control run with the section absent.

### Test Affordances (non-production API)

Behind `@VisibleForTesting`:

- `dumpTokens()` → snapshot of the reservation table.
- `failNextClaim(reason)` → forces the next `claim()` to complete exceptionally; used to verify capped-retry chain behaviour.
- `partition(serverId)` → drops subscriber notifications for one backend, simulating a network blip (orphan reanimation harness).

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Synchronous in-memory binding | Lets tests pass that would deadlock against a real async binding; defeats the SPI's threading discipline. |
| Use the `plugin-message` binding for tests | Requires a live proxy + backend; not suitable for unit tests. |
| Single shared map across JVMs via memory-mapped file | Reinvents Redis poorly; the right answer for cross-host tests is the real Redis binding (Phase 2). |

## Consequences

- **Positive:** Phase 1 is testable end-to-end (dispatcher + selector + reservation client + transport) on a laptop. The no-op contract is mechanically verifiable.
- **Negative:** Test authors may be tempted to ship in-memory in production. Guard: a `WARNING` log on enable (`InMemoryNetworkStateBinding active — not for multi-host deployments`) and explicit documentation in `docs/admin/proxies/CONFIGURATION.md` (when authored).

## References

- ADR-036; `MULTI_SERVER_PLAN.md` Phase 1.
- `REQ-RTP-NET-002` (no-op gate), `-007` (non-blocking).
- `REQ-RTP-PROXY-COMMON-007` (transport pluggability), `REQ-RTP-PROXY-008` (disabled no-op).
