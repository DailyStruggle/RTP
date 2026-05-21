# rtp-proxy-ADR-002 — Canonical `network.yml` Schema

**Status:** Accepted
**Accepted:** 2026-05-14 (amended 2026-05-18)
**Date:** 2026-05-13
**Refines:** [ADR-036 - Network Mode (Multi-Server, Multi-Proxy)](../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md)
**Depends on:** [rtp-proxy-ADR-001](rtp-proxy-ADR-001-spi-shape.md)

## Amendments

- **2026-05-18** - Validation Rules tightened:
  - `role: auto` no longer probes the classpath via `Class.forName`. Resolution is delegated to the registered `RTPProxyAccessor` (see [rtp-proxy-ADR-013](rtp-proxy-ADR-013-proxy-accessor-registration.md)).
  - `network.proxyId` empty or missing when role resolves to proxy is now an explicit `fail-fast`.
  - `network.role` is declared proxy-side-only; backend `network.yml` ignores the key.
  - Authority for picking participant-vs-router wiring split out to [rtp-proxy-ADR-012](rtp-proxy-ADR-012-proxy-role-participant-default.md).

## Context

`MULTI_SERVER_PLAN.md` mandates a **single `network.yml`** authored once and copied byte-for-byte to every backend and every proxy; per-host divergence is limited to `network.serverId` (backends) and `network.proxyId` (proxies). This is the load-bearing assumption behind:

- REQ-RTP-NET-002 (no-op contract: identical config with `enabled:false` produces today's behaviour byte-for-byte).
- REQ-RTP-NET-003 (single distribution artifact).
- REQ-RTP-PROXY-008 (disabled-mode no-op on the proxy side).
- REQ-RTP-PROXY-COMMON-006 (single schema, no vendor-only keys).

Without a pinned schema, every binding (Redis/Postgres/SQL/in-memory) and every adapter (Velocity/Bungee) would invent their own keys, breaking the "copy-paste everywhere" goal and creating an implicit per-host configuration matrix.

## Decision

`rtp-proxy-common` owns the canonical `network.yml` schema, defined and validated in `io.github.dailystruggle.rtp.proxy.common.config`. The schema is **closed** — unknown top-level keys fail validation; unknown nested keys log a warning under their parent's namespace and are ignored.

### Top-Level Shape

```yaml
network:
  enabled: false                # REQ-RTP-NET-002 release gate
  schemaVersion: 1              # REQ-RTP-NET-009
  serverId:  "<backend-only>"   # required when enabled on a backend
  proxyId:   "<proxy-only>"     # required when enabled on a proxy
  role: auto                    # auto | backend | proxy (detection override)
  secretEnv: RTP_NET_SECRET     # D4

transport:
  type: in-memory               # in-memory | redis | postgres | generic-sql | plugin-message
  url: ""                       # binding-specific connection string
  poolSize: 4
  connectTimeoutMs: 2000
  readTimeoutMs: 5000

heartbeat:
  intervalMs: 1000              # backend + proxy
  staleAfterMs: 5000

reservation:
  ttlMs: 30000                  # REQ-RTP-NET-011
  claimReanimateMs: 5000        # REQ-RTP-NET-014 orphan reclaim

loadBalancer:                   # consumed by rtp-proxy-ADR-004 selector
  maxRetries: 3
  attemptTimeoutMs: 1500
  cooldownMs: 2000
  staleAfterMs: 5000
  weights:                      # weight_i in the score formula
    mspt: 1.0
    tps1m: 0.5
    queueDepth: 0.5
    pendingTeleports: 0.5
    chunkLoadBacklog: 0.25
    memoryTrackerEntries: 0.25
    heapUsedRatio: 0.25
    databaseLatencyMs: 0.1
    playerCount: 0.0            # published but unused in v1
  curves:                       # curve_i and params per metric
    mspt:       { type: exponential, k: 4.0 }
    tps1m:      { type: linear }
    queueDepth: { type: power,       p: 1.5 }
    # … remainder default to linear if omitted
  backends: {}                  # per-backend overrides: <serverId>: { weight: 1.0 }

messages:
  routing:    "rtp.network.routing"
  queued:     "rtp.network.queued"
  failed:     "rtp.network.failed"
  reanimated: "rtp.network.reanimated"

triggers:
  command: { enabled: true }
  join:    { enabled: false, regionKey: "default" }
  event:   { enabled: false }
```

### Validation Rules

1. `enabled: false` (default) → every other key is **structurally validated** (so a later flip to `true` cannot fail silently) but no listener, transport, or heartbeat is opened (REQ-RTP-PROXY-008).
2. `role` (**proxy-side-only**; on a backend the key is read but ignored, `role` is fixed to `backend` by which jar got loaded):
   - `auto` -> resolved by consulting the registered `RTPProxyAccessor.role()` (rtp-proxy-ADR-013). Each proxy adapter (`rtp-proxy-velocity`, future `rtp-proxy-bungee`) registers a hard-coded `Role` during its bootstrap event BEFORE the config loader runs. `rtp-proxy-common` performs no classpath probing. This mirrors the `RTPServerAccessor` registration pattern in `rtp-core`.
   - `backend` requires `serverId`, forbids `proxyId`.
   - `proxy` requires `proxyId`, forbids `serverId`. Empty or missing `proxyId` when role resolves to proxy is a `fail-fast`: refuse to enable network mode, log a configurable WARNING, leave the proxy running.
3. `schemaVersion` must equal a value the running binary supports; mismatches refuse to enable network mode (REQ-RTP-NET-009).
4. `transport.type: plugin-message` shall emit a startup `WARNING` on every host and shall refuse to enable outside a developer profile (REQ-RTP-PROXY-BUNGEE-005; mirrored for Velocity).
5. `secretEnv` names an environment variable. If unset while `enabled: true`, startup fails with a configurable message (D4, REQ-RTP-PROXY-007).
6. `loadBalancer.curves.<metric>.k ∈ [0.1, 20]`, `p ∈ [0.1, 8]`, `threshold ∈ [0.0, 1.0]` (per ADR-036).
7. Weights are non-negative; all-zero weight is allowed and degenerates to round-robin tied by `serverIdAsc`.
8. Top-level keys outside `{network, transport, heartbeat, reservation, loadBalancer, messages, triggers}` fail validation; this prevents adapters from extending the schema (REQ-RTP-PROXY-COMMON-006).

### Per-Host Difference Whitelist

The **only** keys permitted to differ across the network deployment are:

- `network.serverId` (backends only).
- `network.proxyId` (proxies only).
- `network.role` if explicitly overridden from `auto`.
- `loadBalancer.backends.<serverId>.weight` may be set on proxies only (no-op on backends).

A startup sanity check hashes the remaining keys; mismatches are logged at `WARNING` with a delta, but do not refuse boot (operator may be mid-rollout).

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Split `network.yml` (backend variant + proxy variant) | Doubles the operator surface and the copy-paste error rate; defeats "one file" headline goal. |
| Embed in `config.yml` | Couples network rollout to single-server config migrations; harder to gate behind `enabled:false`. |
| Open schema (warn-only on unknown keys) | Lets adapters smuggle vendor keys, breaks REQ-RTP-PROXY-COMMON-006. |
| Per-binding sub-schemas under `transport.redis.*`, `transport.postgres.*` | Cleaner namespacing but requires multi-file documentation; deferred to v2 if binding-specific knobs proliferate. |

## Consequences

- **Positive:** one validated source of truth; copy-paste deploy works; `enabled:false` parity test (REQ-RTP-NET-002) reduces to a structural-validation check.
- **Negative:** closed-schema policy means every new knob requires a `schemaVersion` bump or a default-tolerated optional field; we accept this cost as the price of REQ-RTP-NET-009 negotiability.

## References

- ADR-036 (umbrella), `MULTI_SERVER_PLAN.md` *Config* section.
- `REQ-RTP-NET-001…003`, `-009`, `-010`, `-011`.
- `REQ-RTP-PROXY-006`, `-008`, `-009`; `REQ-RTP-PROXY-COMMON-006`.
