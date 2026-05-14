# rtp-proxy-ADR-004 — Weighted-Average `BackendSelector`

**Status:** Proposed
**Date:** 2026-05-13
**Refines:** [ADR-036](../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md)
**Depends on:** [rtp-proxy-ADR-001](rtp-proxy-ADR-001-spi-shape.md), [rtp-proxy-ADR-002](rtp-proxy-ADR-002-network-yml-schema.md)

## Context

ADR-036 locks the **single configurable strategy** principle: no discrete `strategy: leastLoad | roundRobin | random` enum. Instead, every candidate backend is scored by a weighted sum of normalised, curved metric values; lowest score wins. This ADR pins the reference selector that lives in `rtp-proxy-common.selector` and implements `REQ-RTP-PROXY-COMMON-002…004` and `REQ-RTP-NET-010`.

## Decision

### Scoring Formula

For each backend `b` not filtered out:

```
score(b) = ( Σ_i  weight_i * curve_i( normalize_i( metric_i(b) ) ) ) / backendWeight(b)
```

- `weight_i` from `loadBalancer.weights.<metric>` (ADR-002). Zero weights skip the term entirely.
- `normalize_i` maps the raw metric to `[0.0, 1.0]` against a per-metric reference scale (e.g., `mspt / 50.0`, `queueDepth / max(1, softCap)`, `heapUsed / heapMax`).
- `curve_i` is one of `{linear, exponential, logarithmic, sigmoid, step, power}` with parameter bounds `k ∈ [0.1, 20]`, `p ∈ [0.1, 8]`, `threshold ∈ [0.0, 1.0]`.
- `backendWeight(b) = max(0.01, loadBalancer.backends.<serverId>.weight ?? 1.0)` — higher means preferred (divides final score).

Lowest score wins. Ties broken by `serverIdAsc`.

### Candidate Filtering (pre-scoring)

A backend is excluded **before** scoring if any of:

1. `lastSeenEpochMs` older than `snapshot.timestamp - loadBalancer.staleAfterMs` (REQ-RTP-PROXY-COMMON-003).
2. `pluginState != READY` or `acceptingRequests == false` (availability axis).
3. `request.regionKey` not in `regionsAvailable[]` (when specified).
4. `request.worldKey` not in `worldsLoaded[]` (when specified).
5. `schemaVersion` outside the supported range (REQ-RTP-NET-009).
6. Backend is in the per-proxy **cooldown** map (recent rejection, until `cooldownMs` elapsed).

If filtering leaves zero candidates, `choose` returns `Optional.empty()` and the dispatcher decides queue-vs-fail.

### Hot-Spot Avoidance: `recentPicks`

Per-proxy decaying counter (`halflife = 10s` default) added to each backend's score:

```
score(b) += recentPicksWeight * decay(recentPicks[b], halflife)
```

Local-only by default (REQ-RTP-PROXY-COMMON-008). A `recentPicks.mode: shared` config knob is **reserved** for v2 but unimplemented in v1 (would add one transport round-trip per pick).

### Fallback Chain (Capped-Retry)

Owned by `ReservationClient` (ADR-001) but parameterised here:

- `maxRetries: 3` — total attempts including the first.
- `attemptTimeoutMs: 1500` — per-attempt deadline; on timeout, backend enters `cooldownMs` and the next-best candidate is tried.
- `cooldownMs: 2000` — hysteresis after rejection/timeout/claim-race loss.
- **Score-sticking** — within one in-flight request, the snapshot is read once; subsequent retries score against the same snapshot to avoid mid-flight oscillation (Linux CFS-inspired).
- On exhaustion: `Failed(reason=NO_BACKEND, messageKey="rtp.network.failed")`.

### Determinism & Testability

- `choose` is a pure function of `(RtpRequest, NetworkSnapshot, LoadBalancerConfig, recentPicksSnapshot)`. The selector exposes a `score(b, snapshot)` debug accessor for golden-file tests.
- Curve plots are rendered for the admin docs via `scripts/lb-curve-plot.py`; output lands under `docs/admin/proxies/LOAD_BALANCING.md` (Phase 3).

### `playerCount` in v1

`weight: 0.0` by default. The metric is **published** in telemetry so post-Phase-2 analysis can re-evaluate, but it does not influence selection in v1. Justification: player count is a lagging indicator vs. mspt/queueDepth and can mask hot spots when several players join the same backend simultaneously.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Discrete strategy enum (`leastLoad`, `roundRobin`, `random`) | Forces a config-time choice between objectives that should compose; operators want hybrid behaviour. |
| Lexicographic ordering (sort by mspt, then queueDepth, then …) | Brittle near ties; can flap on noise; no smooth fallback. |
| Reinforcement-learning selector | Out of scope; non-deterministic; impossible to reason about in incidents. |
| Power-of-two-choices random sampling | Simple but ignores `regionsAvailable[]` and worldsLoaded[]; cannot satisfy REQ-RTP-NET-005 cleanly. |

## Consequences

- **Positive:** one selector to test, document, and tune. Operators get full curve control without code changes.
- **Negative:** more knobs to understand. Mitigation: the bundled defaults (ADR-002) are tuned for the headline goal; the admin docs ship rendered curve plots in Phase 3.

## References

- ADR-036 *Load Balancer* section.
- `REQ-RTP-NET-010` (configurable LB policy).
- `REQ-RTP-PROXY-COMMON-002…004`, `-008`.
