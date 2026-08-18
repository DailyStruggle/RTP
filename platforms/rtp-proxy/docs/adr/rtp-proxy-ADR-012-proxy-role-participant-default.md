# rtp-proxy-ADR-012 - Proxy Role: Participant Default, Router Opt-Out

**Status:** Proposed
**Date:** 2026-05-18
**Refines:** [ADR-036](../../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md), [rtp-proxy-ADR-006](rtp-proxy-ADR-006-velocity-bootstrap.md)
**Depends on:** [rtp-proxy-ADR-001](rtp-proxy-ADR-001-spi-shape.md), [rtp-proxy-ADR-002](rtp-proxy-ADR-002-network-yml-schema.md), [rtp-proxy-ADR-013](rtp-proxy-ADR-013-proxy-accessor-registration.md)

## Context

Two viable architectures fit the proxy adapter shape implied by ADR-006 and `MULTI_SERVER_PLAN.md`:

- **Participant.** The proxy is a full peer in the network: it publishes its own `ProxyHeartbeat`, runs the `BackendSelector` locally, holds an active `NetworkTransport` connection (Redis pub/sub subscriber or SQL polling thread), originates `/rtp` requests as a first-class citizen, and participates in the network wait queue / fairness accounting.
- **Router.** The proxy is a thin redirector: it intercepts `ServerPreConnectEvent`, redeems a `ReservationToken` that a backend already produced, and forwards the player. It does not publish heartbeats, does not run a selector, and the only state it holds is the active token.

Both have legitimate operator deployments:

- Participant fits networks where the proxy is operationally co-equal with backends (hub + backends on the same LAN, single ops team, shared monitoring).
- Router fits networks where the proxy is operated by a different team than the backends (managed-hosting, multi-tenant) and the operator wants the proxy's role narrowed to the one thing only the proxy can do (cross-server transfers).

The earlier `PROPOSAL-velocity-redis-startup.md` and rev 1 of `PROPOSAL-sql-binding-first.md` drifted between the two without naming them. This ADR fixes the default and names the toggle.

## Decision

The two architectures are **toggleable** per deployment. **Participant is the default.**

### Implementation: implicit-via-wiring

The toggle is **not** a new config knob in `network.yml`. It is implicit in which SPI components the proxy adapter (`rtp-proxy-velocity`, future `rtp-proxy-bungee`) registers during bootstrap:

| Component registered? | Participant | Router |
|---|---|---|
| `NetworkTransport` open + connected | Yes | Yes |
| `ServerPreConnectEvent` interceptor | Yes | Yes |
| `ReservationClient` (token redeem at connect boundary) | Yes | Yes |
| `ProxyStatePublisher` (proxy heartbeat) | **Yes** | No |
| Local `BackendSelector` instance | **Yes** | No |
| `RtpDispatcher` wired to selector + transport | **Yes** | No |
| `/rtp` Brigadier command on the proxy | **Yes** | No (or read-only `/rtp test`) |
| Trigger sources (join / event / command on proxy) | **Yes** | No |

The default Velocity adapter wires the participant set. A future operator-facing knob, if added, would only need to skip the four `**Yes**`-marked components to produce the router shape.

### Operator visibility

Because the toggle has no `network.yml` representation, the adapter shall emit a single startup INFO line listing the wired components:

```
[RTP] Network mode enabled (role=PROXY_VELOCITY, participant): transport=sql, selector=weightedAverage, heartbeat=1000ms, /rtp on proxy
```

The same line in router mode reads:

```
[RTP] Network mode enabled (role=PROXY_VELOCITY, router): transport=sql, no proxy heartbeat, no proxy selector
```

This is the discoverability affordance the alternative (an explicit `proxy.role:` config key) was the only argument for. The log line is cheap, never wrong, and not subject to operator drift.

### Mixed-mode networks are well-defined

Multi-proxy networks may mix participant and router proxies in the same network. The participant proxies appear in `rtp_network_proxies` / `rtp:proxy:*`; the router proxies do not. `BackendSelector` decisions are made from the snapshot of `rtp_network_backends` only and therefore do not depend on proxy mode at all. Reservation-token redemption is identical in both modes (a redeemed token is a redeemed token).

The two interaction points worth pinning:

1. **`/rtp` origin.** A `/rtp` issued on a router-mode proxy has no local dispatcher; it requires either (a) the player to be on a backend already (where the backend's `/rtp` handles it normally), or (b) the router to forward the request to a participant proxy. Phase 2b ships with (a) only; (b) is out of scope and called out in the *Open questions* section.
2. **Wait-queue fairness.** Both modes feed the same network wait queue (REQ-RTP-NET-008). The queue is a database/Redis row, not a proxy-local list; routers contribute by enqueuing on token-redeem.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Explicit `proxy.role: participant \| router` in `network.yml` | Adds one more knob operators can mis-set; participant default is the right answer for >90% of operators and the startup log line covers visibility. The knob remains a reversible future addition if mixed-mode operators ask for it. |
| Router as the default | Reframes the proxy as "always thin"; loses the natural `/rtp` origin on the proxy and the proxy-as-peer monitoring story. Participant default matches `MULTI_SERVER_PLAN.md` line 73's "ordinary participants" framing. |
| Per-adapter default (e.g. Velocity = participant, Bungee = router) | Confusing; operators should not need to know which proxy software changes the default. |
| Force one mode network-wide (no mix) | Removes a useful deployment shape (single-tenant participant + multi-tenant router fronting it) for no implementation benefit. |

## Consequences

- **Positive:** participant default ships the richest UX (`/rtp` on the proxy, full network observability) by default. Router mode is reachable purely by trimming the wiring list, with no new config schema. Mixed-mode networks are explicitly well-defined.
- **Negative:** because the toggle has no config representation, an addon or test that wants the router shape has to construct the adapter without those four components rather than flip a flag. Mitigated by exposing a small `RtpProxyBootstrap` builder in `rtp-proxy-common` for test fixtures (deferred to the Phase 2b code-landing turn).
- **Neutral:** ADR-006 (Velocity bootstrap) and ADR-002 (network.yml schema) are unchanged by this ADR. ADR-013 (proxy accessor) is the registration mechanism; this ADR is the policy default.

## References

- ADR-036 (umbrella); `MULTI_SERVER_PLAN.md` Phase 2 and *Proxy roles* subsection (added 2026-05-18).
- ADR-006 (`rtp-proxy-velocity` bootstrap; Accepted 2026-05-18).
- ADR-013 (proxy-accessor registration).
- `docs/dev/scratch/PROPOSAL-sql-binding-first.md` rev 2 section 1 Axis B.
