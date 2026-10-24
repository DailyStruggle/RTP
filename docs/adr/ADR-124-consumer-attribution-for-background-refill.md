# ADR-124 - Consumer Attribution for Constrained Background Refill

**Status:** Proposed. Builds on [ADR-087](ADR-087-adaptive-compute-governor.md) (per-region plan and quota, constrained allocation, observe-only mode). Split out of ADR-087 (former section 4.6) on 2026-10-23. Gated on go/no-go 1 (section 7): it ships only if constrained refill is common enough to matter. Nothing is implemented.
**Date:** 2026-10-23

## Context

ADR-087 sizes RTP's total background refill rate $r^{*}$ and, when $r^{*}$ does not cover every region's deliverable demand $\bar d_r$, divides it in proportion to that demand (ADR-087 section 4.3, item 1, constrained allocation). Proportional sharing never clips the busiest region first, but it measures demand in locations, not in people: a region drained by one player's repeated teleports and a region drained at the same rate by 40 players get the same share. Under constraint the 40 players and the one player each see their region's ready cache refill at the same speed.

Refill is constrained when a server-wide capacity limit binds through $r^{*}$: $B_{\text{cpu}}$ on single-loop hosts with a fresh MSPT reading, and the async limiter ($f_A < 1$, process CPU breach) on every platform, Folia included. How often that happens is not known; ADR-087's observe-only mode measures it (section 7).

## Decision

While background refill is constrained, share it per player, not per teleport or per RTP region: a region used by many players is not penalised for being popular, and a region driven by one player's repeated teleports refills at one player's weight. Per-player rate policy (cooldowns, prices, permissions) stays operator policy outside the governor (section 4).

This applies only while $r^{*} < \sum_r \bar d_r$; otherwise every region gets its deliverable demand and this ADR has no effect. The per-region cadence limit is already in $\bar d_r$ and is not shared. It shapes background refill and reporting only: it never touches a waiting player's request, never adds a cooldown or message, and never changes the order of any queue (ADR-087 principle 4, invariant I4).

It adds no invariant of its own. ADR-087 I4 (player-facing work unchanged) and I11 (delivery within the plan) cover it; the allocation below only changes the plan $x_r$ that I11 checks against.

### 1. Consumer ledger (`ConsumerLedger`)

- **Per RTP region:** distinct active players $n_r$ who consumed locations there in the last 10 minutes (minimum 1). This is the only ledger value the control law reads.
- **Per player (telemetry only):** attributed cost $a_p = \tfrac{1}{2}\left(a_p^{30\,\text{s}} + a_p^{10\,\text{min}}\right)$, the mean of two EWMAs of RTP milliseconds per minute charged to that player's requests, with half-lives of 30 s and 10 minutes. Charges cover async selection, sync stages, interactive chunk loads and placement-free work run on the player's entity scheduler ([ADR-123](ADR-123-folia-tick-region-allocation.md) section 2), plus one measured unit cost ($\Delta T_{\text{async}} + \Delta T_{\text{tick}}$) per cached location the player consumed, which is the refill they caused. It feeds `/rtp info` "top consumers" and the input of any future rate policy (section 4), never a decision of the governor.
- Keyed by UUID, held in memory only, bounded to 4096 entries (least recently active evicted), expired after 30 minutes idle and cleared on reload. It is never persisted, and there are no per-player metric labels.
- **No exemption permission.** Attribution changes no player's service, so there is nothing to exempt anyone from. An administrator testing or pregenerating counts as one player in $n_r$, like anyone else; pregeneration does not consume cached locations and does not enter $n_r$.

### 2. Background refill across RTP regions

This replaces ADR-087's proportional constrained allocation. When $r^{*}$ does not cover demand, the rate is divided by weighted max-min fairness (water-filling) with weights $n_r$:

$$x_r = \min\left(\bar d_r,\; n_r \cdot \theta\right), \qquad \theta \text{ chosen so that } \sum_r x_r = r^{*}$$

A region used by 40 players gets up to 40 times the share of a region used by one player, so the main region is not penalised for being popular. Every use is linear in the weight; super-linear weighting is rejected (Alternatives). ADR-087's quota $u_r$ (section 4.3, item 1) enforces $x_r$. Hot quotas (ADR-087 section 4.3, item 6) use the same capped weights $\min(\bar d_r, n_r \theta)$ instead of raw demand.

**What a heavy consumer feels.** A region driven by one player's repeated teleports refills at one player's weight, so that player more often finds the region's ready cache empty and is served from a cold or backlog location or by live selection. That is cache state, not a throttle: those paths run at the configured player-facing budgets and in their existing order. Nothing else changes for them.

**Evasion.** Alternate accounts raise $n_r$ for the region they use. The worst case is that the region is weighted as if those accounts were real players, still capped at its deliverable demand $\bar d_r$; no other region is starved, because water-filling serves every unsatisfied region the same per-player share.

### 3. Billing, not thread placement

On Folia, placement-free work that needs a tick thread runs on the consumer's entity scheduler (ADR-123 section 2), so its tick cost lands in the consumer's own region. Chunk-bound work always runs where its chunk is; its cost appears in the consumer's ledger entry, while the tick time itself is steered by ADR-123. Without ADR-123 the ledger still charges chunk-bound work to the consumer; only where it runs differs.

### 4. Out of scope: per-player rate policy

When one player's request rate outruns refill, the right tool is a cooldown, not reordering. A cooldown is visible (a configurable message, S-007), it reduces incoming work instead of shuffling the same work, and how often a player may teleport is the operator's decision. An optional dynamic cooldown that lengthens with load is therefore left to a separate, opt-in decision outside `autoTune`, with this ADR's telemetry ($n_r$, $a_p$, refill versus consumption per region) as its input. Constraints recorded for that decision:

- **Fix the cooldown when the teleport completes.** Today the remaining time is recomputed on every check from the current `RTP.getCooldown(...)` minus the time since the last teleport (`RTPCmd`, with the shared last-teleport time from `RTPNetworkManager` / `RedisManager`). A load-dependent value recomputed that way would make a player's remaining time jump; storing an expiry, or the cooldown in force at teleport time, avoids it and changes the shared key format.
- **Drive it from location supply** (consumption versus refill per region, cache depletion), which a cooldown can fix, not from MSPT, which the background governor and the hard gates already handle.
- **Bound it**: the configured cooldown is the floor, an operator-set maximum the ceiling; existing bypass permissions apply, and the message shows the effective value.

### 5. Operator surface and visibility

No new key; it is active under ADR-087's `autoTune: true` once shipped.

| Key | With `autoTune: true` | With `autoTune: false` |
|-----|-----------------------|------------------------|
| (none) | Constrained background refill share weighted by distinct players per region; waiting players unaffected | Today's per-region refill |

- **`/rtp info`** (strings in `messages.yml`, REQ-RTP-F-013): the top RTP regions by refill share ($n_r$, demand, allocation) and the top consumers by attributed cost, so an operator can see who drives RTP's load and decide whether a cooldown or price is warranted.
- **Metrics** (`metrics-api`): `rtp_governor_consumer_cost_p99_ms`, `rtp_governor_region_refill_share` (per RTP region). There are no per-player labels, which keeps cardinality bounded and player identifiers out of metrics.

### 6. Implementation layout

- `ConsumerLedger` (ADR-087 governor package) receives charges tagged with the requesting player's UUID from `SelectionAPI`, `TeleportPipelineTask` and `RegionThreadBudget`, and consumption events per RTP region. It exposes $n_r$ to the governor and $a_p$ to `/rtp info`. No queue reads it.
- Water-filling for refill and hot quotas is a pure function next to `HotBudgetAllocator#computeQuotas`, replacing the proportional allocation function of ADR-087 behind the same signature.
- The ledger holds no chunk tickets and creates no threads (F-001); it is reset with the governor on reload.

### 7. Verification and evidence

- **Attribution tests** (pure, injected clock): water-filling never gives a region more than $\bar d_r$ and gives every unsatisfied region the same per-player share; a region with $n$ players gets $n$ times a one-player region's share when both are unsatisfied; with all $n_r$ equal the result equals max-min fairness, and the sum equals $r^{*}$; attribution is active on Folia when $f_A < 1$; the ledger never exceeds 4096 entries and entries expire.
- **Design behaviour test** (ADR-087 control law with attribution): process CPU above $T_{\text{proc}}$ on two evaluations, with RTP async CPU at least 10 % of process CPU time, cuts $\ell_A$, and the cut lowers $r^{*}$, then the regions with the lowest $n_r$ first.
- **I4 and I11** (ADR-087): the existing checks run with attribution enabled; player-facing budgets and order are identical, and delivery stays within the water-filled plan.
- **Observe-only go/no-go**, run in ADR-087's observe-only mode and recorded here before shipping:
  1. **Is refill constrained?** The share of evaluations with $r^{*} < \sum_r \bar d_r$, and in those, how unequal $n_r$ is across regions with demand. If refill is rarely constrained, or $n_r$ rarely differs between constrained regions, the proportional allocation already gives the same answer and this ADR is not built; the ledger may still ship as telemetry for the cooldown decision (section 4).
- **Revision rule**: ADR-087's applies. Numeric constants (10-minute window, half-lives, 4096 entries, 30-minute expiry) are initial values, revisable under that rule.

### 8. Rollout

1. **Observe.** The ledger and the would-be water-filled allocation are computed and published in ADR-087's observe-only mode, next to the proportional allocation actually used. Run go/no-go 1.
2. **Actuate.** After ADR-087 rollout step 2 (pulse budget and quota), and only if go/no-go 1 passes, the water-filled allocation replaces the proportional one. Revertible by `autoTune: false`.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **Keep ADR-087's proportional allocation** | Kept unless go/no-go 1 shows constraint is common and $n_r$ differs; otherwise a one-player region refills like a 40-player region at the same consumption rate. |
| **Order waiting players by attributed cost (lightest first, with aging)** | The delay is invisible to the player and reads as lag; it reshuffles the same work instead of reducing it; it puts per-player rate policy inside the performance layer; and with per-request aging bounds it needed its own proof of bounded wait. A cooldown does the job openly (section 4). |
| **Dynamic cooldown inside the governor** | Cooldowns are player-visible policy, not background tuning. Left to a separate opt-in decision with this ADR's telemetry as input (section 4). |
| **Super-linear consumer weighting ($n_r^{\gamma}$ or $a_p^{\gamma}$, $\gamma > 1$)** | Charges heavy consumers more than they used. Paying more is a policy choice that belongs to cooldowns and prices. |
| **Attribute cost per teleport or per RTP region** | Penalises popular regions and the many players using them: a region used by 40 players would be throttled like one player's spam. Per-player weighting keeps the cost on the individual heavy consumer. |
| **Specify this inside ADR-087** | It changes behaviour only under constraint, needs its own evidence, and adds player data handling; keeping it apart lets ADR-087 be accepted or rejected on its own. |

## Consequences

- **Positive:**
  - Under constraint, a popular region keeps refill in proportion to the people using it, and one player's repeated teleports cannot claim a 40-player region's share.
  - Operators can see who drives RTP's load, which informs cooldown or price decisions without RTP making them.
- **Negative / Trade-offs:**
  - A player who drives a region alone meets cold, backlog and on-demand paths more often under sustained load.
  - The ledger holds player UUIDs in memory for up to 30 minutes; nothing is persisted or exported per player.
  - Alternate accounts can raise a region's refill weight, at most to its deliverable demand.
  - One more component whose effect is visible only under constraint, so it is hard to observe on a healthy server; `/rtp info` shows both the allocation in force and its inputs.

## References

- [ADR-087](ADR-087-adaptive-compute-governor.md): Adaptive Compute Governor (plan, quota, constrained allocation)
- [ADR-123](ADR-123-folia-tick-region-allocation.md): Folia tick-region allocation (consumer's entity scheduler for placement-free work)
- [ADR-078](ADR-078-composable-cache-pipeline-stages.md): `HotBudgetAllocator` (hot quotas)
