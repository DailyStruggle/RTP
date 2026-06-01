# rtp-proxy-ADR-004 — Weighted-Average `BackendSelector`

**Status:** Accepted
**Accepted:** 2026-05-14
**Date:** 2026-05-13
**Amended:** 2026-05-22 (curve catalogue + configurable scoring table landed in `rtp-proxy-common.selector`)
**Amended:** 2026-05-22 (region-pair scoring; lobby and proxy unified on `RegionAwareSelector`)
**Refines:** [ADR-036](../../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md)
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

## Implementation (2026-05-22)

The 2026-05-22 amendment landed the full curve catalogue and the configurable scoring table:

- `selector/curve/Curve` SPI + `Curves` catalogue: `linear`, `exponential(k)`, `logarithmic(k)`, `sigmoid(k, x0)`, `step(threshold)`, `power(p)`. Parameter bounds (`k in [0.1, 20]`, `p in [0.1, 8]`, `x0`/`threshold in [0, 1]`) are enforced via clamping; out-of-range inputs are clamped to `[0, 1]`.
- `selector/MetricInput` enum (one normalized extractor per input). Supersedes the v1 hardcoded four-term sum:
  - Original ADR set: `mspt` (normalized by 50 ms), `queueDepth` (by `max(1, softCap)`), `heapUsed` (by `heapMax`), `playerCount` (by 100).
  - Added on 2026-05-22: `heapFree` (alias for the used-ratio expressed as "remaining capacity"), `keptCount` (inverted, normalized by 64), `tps` (derived from `mspt` as `1 - min(20, 1000/mspt) / 20`; `BackendHeartbeat` does not yet publish TPS directly).
- `selector/ScoringTerm` record `(MetricInput input, double weight, Curve curve)` is the row of the operator-facing scoring table.
- `LoadBalancerConfig` carries a `List<ScoringTerm> terms` field. When empty (e.g. when constructed via the pre-curve six-arg legacy constructor), the compact constructor synthesizes a `linear` term per non-zero legacy weight, preserving binary and behavioural compatibility for every existing call site (`LoadBalancerConfig.defaults()` and the existing test suite).
- `LoadBalancerConfigYaml.fromMap(Map<String, Object>)` parses both the new `loadBalancer.terms` sub-configuration block (nested `curve:` sub-map, no inline-brace YAML) and the legacy flat-weight keys. Unknown `input` or `curve.type` values raise `IllegalArgumentException` with a descriptive message instead of silently degrading.
- Operator documentation: commented-out reference block in `rtp-proxy-velocity/src/main/resources/network-proxy.yml`. The Velocity bootstrap still passes `LoadBalancerConfig.defaults()` at the time of this amendment; wiring the YAML loader through `RtpVelocityPlugin` is a follow-up tracked outside this ADR.

Coverage: `CurvesTest`, `WeightedAverageBackendSelectorCurvesTest`, `LoadBalancerConfigYamlTest` (all in `rtp-proxy-common`). Existing `WeightedAverageBackendSelectorTest`, `BackendSelectorRegionFilteringTest`, `BackendSelectorServerIdFilterTest` remain green via the legacy-weight synthesis path.

## Region-Pair Scoring (2026-05-22)

The original ADR scored backends. In practice the no-region `/rtp` entry point (blank command on a lobby; proxy-side join with no `regionKey` hint) needs `(serverId, regionKey)`, not just `serverId`. Before this amendment that decision lived in a separate "v1 most-kept" picker (`PeerRegionRegistry.pickMostKept`, hardcoded "max `regionKeptCounts` wins, lex tiebreak"). That forked the load-balancing logic across lobby and proxy.

`RegionAwareSelector` unifies both call sites on one scoring path:

- The candidate set is every `(b, r)` pair surfaced by the snapshot (`b.regions()` falling back to `regionsAvailable` falling back to the keys of `regionKeptCounts`).
- Filtering matches the backend-scoped selector: staleness, `pluginState == READY`, `acceptingRequests`, `!killSwitch`, optional `excludedServerId` (lobby self), optional `worldKey` constraint. A pinned `request.regionKey` collapses the per-backend region loop to that single key.
- Scoring: `score(b, r) = ( Σ_i weight_i * curve_i( input_i.normalize(b, r) ) ) / backendWeight(b)`. The `Σ_i` runs over `LoadBalancerConfig.terms()` plus, when no explicit `KEPT_REGION` term is present and `regionScarcityWeight > 0`, the *synthesized* region-scarcity term.
- Tie-break: `score` asc, `serverId` asc, `regionKey` asc.

### Synthesized Region-Scarcity Term

A new `MetricInput.KEPT_REGION` extracts `1 - min(1, regionKeptCounts[r] / 64)`. Operators may add it as an explicit `ScoringTerm` and pick any curve. When they don't, the config synthesizes one with weight `regionScarcityWeight` (default `1.0`) and curve `exponential(k=5)`. The exponential default is deliberate: a region 90% empty contributes ~0.85 to the score while a region only 50% empty contributes ~0.07, "drastically prioritising warm regions when shallow". An operator who wants a different shape adds an explicit `KEPT_REGION` term and the synthesized one steps aside (one knob per axis; no special-case curve config field, every input gets its curve through `ScoringTerm` like all the others).

### Where The Selector Is Used

- **Lobby-side** (`PeerRegionRegistry.pickMostKept`): the blank `/rtp` entry from a lobby. Excludes `localServerId` (so the lobby doesn't pick itself), applies the existing `localDecrements` bookkeeping via the `PostScoreAdjust` hook.
- **Proxy-side fallback**: when `RtpDispatcher` receives an `RtpRequest` with no `regionKey` (e.g. a join-time RTP that the lobby did not pre-decide), the dispatcher calls `RegionAwareSelector` to produce `(serverId, regionKey)` and uses both for the reservation. The existing `WeightedAverageBackendSelector.choose` path remains the entry point for requests that already carry a `regionKey` or a `serverIdFilter`; the two coexist by design.

### Region == Server Collapse

In typical deployments each backend hosts one region (often named after the server). The pair-wise sum collapses to "per-backend score plus a constant", and the `(serverId asc, regionKey asc)` tiebreak preserves the determinism of the legacy backend-only path. Multi-region backends are handled the same way: each `(b, r)` is scored independently, so a backend with two regions does not unfairly outweigh a peer with one.

### Why Not Fork Lobby And Proxy

Operators tune curves and weights in `network.yml` once. The lobby and proxy disagree only when they observe different snapshots (different Redis read instants), which self-corrects on the next heartbeat tick. Forking would mean two config blocks, two tuning passes, and inevitable divergence.

### Coverage

`RegionAwareSelectorTest` (12 cases): defaults pick the warmest region; empty-pool penalty under the exponential default; pinned regionKey collapse; self-exclusion; killSwitch/STARTING/draining filter; staleness filter; MSPT term overriding scarcity when weighted high; explicit `KEPT_REGION` term suppressing the synthesized one; `regionScarcityWeight=0` disabling the bias; per-(backend, region) pair-wise scoring; `backendWeights` divisor; `PostScoreAdjust` hook (the lobby decrements analog). `LobbyModeTest` (18 cases) covers the lobby integration path through `PeerRegionRegistry.pickMostKept` and remains green byte-identical to the legacy "most-kept" behaviour with default config.

## References

- ADR-036 *Load Balancer* section.
- `REQ-RTP-NET-010` (configurable LB policy).
- `REQ-RTP-PROXY-COMMON-002…004`, `-008`.
