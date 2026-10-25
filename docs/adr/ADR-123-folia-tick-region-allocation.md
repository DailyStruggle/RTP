# ADR-123 - Folia Tick-Region Allocation: Work Placement, Probed Shares, Steering and Lending

**Status:** Accepted (2026-10-24). Builds on [ADR-087](ADR-087-adaptive-compute-governor.md) (governor, `ComputeBudget` snapshot, AIMD limiter, observe-only mode) and [ADR-112](ADR-112-per-region-work-division-on-folia.md). Split out of ADR-087 (former section 4.5) on 2026-10-23. Work placement (section 2, rollout step 1) does not depend on the governor and may land first. Shares and steering are gated on go/no-go 1 and 2, lending on go/no-go 3 and the Folia differential benchmark (section 12).
**Date:** 2026-10-23

## Context

ADR-112 caps RTP's sync work at one `syncAllottedTime` ($S_{\text{cfg}}$) per region thread per 50 ms. In its own stress run one tick region fell to about 1.4 TPS while the mean stayed at 18.9. Chunk-bound work clustering where the candidates are is one possible cause. RTP's tick-thread work also concentrates for reasons unrelated to any chunk:

- **Placement-free work pinned to one thread.** Location-less `RTPScheduler#runTask`, `#runTaskLater` and `#runTaskTimer` go to Folia's global region scheduler (`FoliaSchedulerImpl`). That includes the per-tick `SyncTaskProcessing` pulse, the scan batch hand-off in `ScanTask`, fill deadlines and failsafes in `RegionCacheTask`, action starts, cancellations and teardown reports. However many tick regions exist, all of it lands on one serial queue.
- **Inline continuations.** Non-`*Async` `CompletableFuture` stages run on the thread that completes the future. ADR-112 decision 1 hands `FoliaRTPWorld#getChunkAt` completions to the async scheduler, but two paths remain. A future completed inside a region task (for example a live-check result in `QueueTask` or `PregenTask`) runs every dependent stage inline on that region thread, so placement-free selection and bookkeeping are charged to whichever region happened to own the candidate. The hand-off also falls back to inline when the async scheduler rejects. Either way "async" work maps onto the finite set of regions that complete RTP's results.
- **New regions start from zero.** `RegionThreadBudget` is a per-tick-thread bucket (`ThreadLocal`) that starts full on first use and keeps no per-region history. A newly created region has no estimator or limiter state, and Folia's rolling TPS window for it starts empty. RTP's spend and the readings are least reliable exactly when RTP is creating regions.

Which of these produced the 1.4 TPS region is not yet established; section 12 adds the diagnostic that separates them. The ADR-112 cap applies only to work inside its spans, and it is uniform. A region at 45 ms of its own load pays the same as one at 5 ms, so its players pay for background work that idle regions could have absorbed. Those players did not request the teleports: the cost is socialised onto bystanders instead of falling on the players and RTP regions that consume it. Work clustered in one region also drains at one budget per tick while other regions have headroom and nothing to do, so it finishes later than it needs to.

ADR-087 decides how much background work RTP does in total. On a single-loop host every RTP millisecond lands on the one tick thread, so that is the only question. On Folia the question is also **where**: tick regions run in parallel on a fixed pool of tick threads, each with its own 50 ms tick.

## Decision

Put each kind of RTP tick work on the thread that should pay for it, size each tick region's share by probing, move background work towards regions that take it without lateness, and let those regions borrow budget that busy regions leave unused. Every input comes from public Folia API; no internals are read.

How much of this ADR is needed depends on which Context mechanism produced the low-TPS region. The concentration diagnostic (section 12, go/no-go 1) answers that before anything beyond placement actuates (section 13).

Notation: $k$ = a Folia **tick region** (the server's unit of parallel ticking, unrelated to RTP regions), tracked as a cell group (section 3); $S_{\text{cfg}}$ = configured `syncAllottedTime`. Other symbols are ADR-087's.

### 1. Principles and invariants

1. **Never more aggressive than today, in total and per thread.** RTP's sync spend per 50 ms stays within today's ADR-112 bound of one $S_{\text{cfg}}$ per tick thread running RTP work, both server-wide (the pool $\Pi$, section 8) and on each thread (the outer thread bucket, section 3). The one exception is a loan: a region may exceed its own value by borrowing pool budget that other regions left unused, up to $\min(2 \cdot S_{\text{cfg}},\; 25\text{ ms})$, and only on a tick thread that has run RTP work for no other region in the last second. Regions without RTP work that share that thread can be delayed by at most the loan itself (section 8). This refines ADR-087 principle 2 and is the only exception to ADR-087 invariant I2.
2. **Background tick work prefers tick regions with headroom and few or no players.** A design aim, measured by $J$ and $E$ (section 9), not a guarantee.
3. **Player-facing work is untouched.** ADR-087 principle 4 and invariant I4 apply unchanged: interactive work is charged but never deferred, scaled or moved.

Invariant IDs are shared with ADR-087 so that references stay stable. This ADR owns I1 and I9; each has a mechanical check in section 12. On acceptance they are added to `REQUIREMENTS.md` (IDs assigned then, phrased with *shall*) and traced in `TRACEABILITY.md`.

| ID | Invariant | Principle |
|----|-----------|-----------|
| I1 | On Folia with `autoTune` enabled, a background sync task is admitted only while RTP's sync charge in the current 50 ms (interactive and background) is within $\Pi \le S_{\text{cfg}} \cdot \Theta$ server-wide and within $S_{\text{cfg}}$ on its tick thread, or within $\min(2 \cdot S_{\text{cfg}}, 25\text{ ms})$ on a thread while it carries a loan. As today, interactive work is charged but never deferred, and the ADR-112 one-task floor is outside the bound. The bound is stated on the outer thread bucket and the pool, not on cell groups, so it holds whatever the group bookkeeping does. | 1 |
| I9 | Steering defers a background candidate for at most the aging bound (60 s or 8 deferrals); an aged candidate is then taken first in its region's next turn, within that turn's quota $u_r$ (ADR-087 I11), and none is dropped. Time during which a hard gate (ADR-087 I7) pauses background work, the circuit breaker holds the candidate's region, or the region's quota is zero does not count towards the bound. | 2 |

### 2. Work placement classes

Only some RTP tick work is tied to a chunk. Every stage that needs a tick thread declares one class, and the class decides where it may run:

| Class | Examples | Runs on | Who pays |
|-------|----------|---------|----------|
| Chunk-bound | Live block checks, stale-chunk guard, ticket activation, arrival platform | The region that owns the chunk (S-005, Folia threading contract). It cannot move; only **when** it runs is steered. | That region's share (fairness rules 1-2) |
| Entity-bound | Teleport, fall-distance reset, bed spawn, player-sender commands | The player's entity scheduler, which follows the player across regions | The player's region, the player's own cost |
| Global-bound | Console commands, world-global state that the API places on the global region | The global region scheduler | A budgeted global share, charged in the ledger as region $k = \text{global}$ |
| Placement-free | Future continuations, cache and queue bookkeeping, deadlines and failsafes that only complete a future or flip a flag, scan batch hand-offs, pulse stages that touch no world state | Async by default. If an API requires a tick thread, the requesting player's entity scheduler, so the consumer pays. Unowned work goes to the global share. | The consumer, or the shared pool |

- **Results leave the region thread before dependents run.** A region-task body that completes a future completes it through RTP's async executor, as `FoliaRTPWorld` already does for chunk futures (ADR-112 decision 1). Dependents are placement-free unless they touch the chunk, and then re-enter through `RTPScheduler#runTask`, inside a `RegionThreadBudget` span, so they are charged.
- **Unclassified work keeps today's placement** and is charged to the global share, so a missed stage is visible in the ledger rather than silent.
- **Moving placement-free work onto the consumer's thread is the only cross-thread move.** Chunk-bound work can never follow the consumer (Alternatives).
- **Placement changes where work runs, not how much.** It is independent of `autoTune` and of the governor (rollout step 1). Placement-free work moved onto a consumer's entity scheduler is small by construction; work that is not small belongs async.

### 3. Tick-region state without region identity

Public API cannot enumerate tick regions or name the one a thread is ticking. It can only answer `Bukkit.isOwnedByCurrentRegion` on a tick thread. RTP therefore keeps per-region state (bucket, limiter, ledger, loan) per **cell group**: ADR-112 cells that a slice has observed on the same tick region. Every per-region quantity in this ADR, including $J$ and $E$, is per group, and $k$ denotes a group.

- **Resolution.** At slice start, `RegionThreadBudget` tests the slice's cell first against its last group's representative, then against the other live groups in the slice's world, nearest first, and joins the first match. With no match, the cell forms a new group. Live groups are those with queued work or charges in the last 60 s, which bounds the tests. The result is cached until the slice ends.
- **Merge probe.** First-match resolution never sees a second match, so merges are detected separately: after resolving, each slice tests one further live group's representative, round-robin over the live groups of its world. A match merges the two groups. A merge is therefore found within as many slices of the merged region as there are live groups, at one extra test per slice.
- **Splits and merges are conservative.** A cell whose last group no longer matches has split away; its new group starts at the lower of the old group's limit and the new-group prior. Merged groups keep the lower limit and the sum of their ledgers. A representative that unloads fails its test, which only causes a split. A false split or an undetected merge leaves one tick region with two groups and two shares; the outer thread bucket (below) keeps that region's spend within $S_{\text{cfg}}$ per thread, so the error costs accuracy of the ledger, not budget.
- **Token state: group bucket inside a thread bucket.** The ADR-112 bucket is a `ThreadLocal` per tick thread, and a thread ticks different regions over time. Kept per thread only, tokens earned while ticking a quiet region would be spent in a busy one, and the ledger would not be per region. With `autoTune: true` each group has its own bucket ($S_k$ plus any loan), and the per-thread bucket stays as an **outer bucket** of $S_{\text{cfg}}$ per 50 ms. A task runs only if both have tokens and is charged to both. The outer bucket rises to $S_k + e_k$ only while the thread has run RTP work for no group other than the borrower $k$ in the last second (section 8). This keeps today's per-thread bound (I1) whatever the group bookkeeping does. With `autoTune: false` only the per-thread bucket exists, unchanged.
- **New regions do not start from zero.** A group formed for the first time starts its limiter at $S_{\text{cfg}} / 2$ and grows by the ADR-087 section 4.4 recovery rule, not from a full bucket. A region's interval readings are `NEVER_SAMPLED` until it is older than the reading's window; slice dispatch lag and the sampler's interval readings need no window and apply at once. ADR-087 section 3.2 permits still bound how many regions RTP creates.

### 4. Fairness rule (who pays)

1. **A teleport pays where it lands.** Interactive work (arrival chunks, the live check for a waiting player, teleport stages) is charged to the tick region it runs on. It cannot move to another region and is never deferred (ADR-087 principle 4). It uses that region's share first, so background work there yields to it.
2. **Background work is a shared cost.** Background work has no requesting player. Steering (section 7) defers it in regions with little unused share, and prefers regions with few players, so the busiest region is expected to carry the least. This is a design aim measured by $J$ and $E$ (section 9), not an invariant.
3. **No background candidate is starved by steering.** Deferral is bounded by aging (section 7, I9), so steering delays no candidate beyond a fixed time; the hard gates and the quota still apply to it.
4. **Lending redistributes and never adds.** Only budget that throttled regions did not use is lent. RTP's server-wide sync spend never exceeds the pool $\Pi$ (section 8), which is today's ADR-112 bound, and each thread stays within its outer bucket (I1).

### 5. Per-region signals

- **Cost ledger** $c_k^{i}$, $c_k^{b}$: RTP's interactive and background sync milliseconds per tick on region $k$, including the global region. This is an EWMA of `RegionThreadBudget` charges aggregated from ADR-112 cells, split by placement class. It is exact for every span, because RTP times its own work.
- **Queued work** $q_k$: background milliseconds queued for $k$ (cell FIFO lengths times the EWMA of measured tick cost per background cell task).
- **Overload**: `regionTps1m`, `regionMspt` (the binding's windowed mean tick interval, above 50 ms), slice dispatch lag. These use the public API and are always available.
- **Tick time by placement class**: budget spans around chunk-bound, entity-bound, global-bound and placement-free tick work, including the global region. Ledger input and diagnostic.
- **Entity-scheduler sampler** (`EntitySchedulerSampler`, rtp-folia). Every 2 s a **sampling round** picks at most 64 online players, rotating so that every online player is sampled once per **rotation** of $\lceil N_{\text{online}} / 64 \rceil$ rounds. RTP starts a task on each sampled player's `Entity#getScheduler()` with `runAtFixedRate` and a period of 1 tick, and cancels it after 10 runs. The task runs on whichever region owns the player. It records:
  - **Population** $P_k$, on the first run: for the cells with pending RTP work, `Bukkit.isOwnedByCurrentRegion(world, cx, cz)` tells whether the cell shares this player's tick region. Cells are checked in one batch. $P_k$ is the number of players found in $k$ over the last complete rotation, so it counts every online player once rather than a sample; it lags by up to one rotation (2 s per 64 players).
  - **Tick intervals**: `System.nanoTime()` on every run; the gap between consecutive runs is that region's tick interval. A gap above 60 ms (50 ms plus 10 ms jitter allowance) is an **overrun**. Two or more overruns in one 10-run window, or any single gap above 100 ms, is a breach.

  Why intervals between consecutive runs and not one delayed task: a one-tick-delayed task scheduled at an arbitrary phase of the tick runs between about 50 and 100 ms later on a *healthy* region. A 100 ms threshold on that reading has almost no margin against false positives, and misses a region that steadily overruns at 55-60 ms per tick. Consecutive runs of a period-1 task remove the phase, so the threshold can sit just above 50 ms. This is the same interval reading the existing probes use.

  Tasks of players who leave are retired by the scheduler's retired callback; the sampler holds no reference after that. A missed sample keeps the previous reading for one round, then becomes `STALE`. The cost is at most 640 task runs per 2 s round. Each sampler signal declares its lag (ADR-087 section 4.1); interval readings have lag 0.
- **No spare-time reading.** Folia starts each region tick on a 50 ms boundary, so a task's timing looks the same whether the region used 5 ms or 45 ms. No public reading measures headroom below 50 ms, so the share is found by probing (section 6) instead of computed.

### 6. Per-region share (found by probing)

$$S_k = \text{clamp}\left(\text{limit}_k,\; S_{\min},\; S_{\text{cfg}}\right), \qquad S_k^{b} = \max\left(0,\; S_k - c_k^{i}\right)$$

Here $\text{limit}_k$ is an ADR-087 section 4.4 limiter, kept on the cell group (section 3). A breach is a sampler breach (which carries the 10 ms jitter allowance), windowed `regionMspt` above 50 ms, dispatch lag above 100 ms, or `ServerHealthGate#regionUnhealthy`.

- **A breach cuts only if RTP contributed.** It halves $\text{limit}_k$ only if RTP charged at least $\max(0.5\text{ ms},\; S_{\text{cfg}} / 10)$ per tick on $k$, averaged over the 2 s before the breach. Otherwise the breach holds the limit (no growth) and is counted as `NOT_RTP`. *Why:* a late tick caused by a farm or a boss fight would otherwise halve RTP's share even when RTP spent nothing there. In regions with natural spikes $\text{limit}_k$ would rarely return to $S_{\text{cfg}}$, and those regions could never borrow, because a borrower must be at $S_{\text{cfg}}$ (section 8).
- **Growth.** Without a breach the limit grows additively. Regions with $P_k \le 1$ or with queued interactive work grow at the ADR-087 section 4.4 rate. A populated region ($P_k > 1$) without queued interactive work grows only after 60 s without a breach, and then at a quarter of that rate. One transient breach therefore does not cap a hub permanently, and the probe's occasional late tick falls mostly on empty or sparse regions, often on the teleporting player alone. A new group in a populated region stays near $S_{\text{cfg}} / 2$ for minutes, so its background work stays near $S_{\text{cfg}} / 2 - c_k^{i}$.
- $S_{\min}$ is the ADR-112 one-task floor. `RegionThreadBudget` reads $S_k$ (plus any loan) as its `budgetNanos` supplier for the slice's group. The no-catch-up bucket is unchanged. The group map is bounded, and entries expire after 60 s idle.
- A slow tick region is handled by its own share and never cuts a global ADR-087 knob (ADR-087 section 4.3, Folia).

### 7. Steering: moving background work to regions with headroom

Each region has a headroom weight $w_k = (S_k^{b} + e_k) / S_{\text{cfg}}$, in $[0, 1]$ without a loan and in $[0, 2]$ with one, and a bystander-adjusted weight $w'_k = w_k / (1 + P_k / 4)$ (section 9). Background stages that choose among candidates read them: cold-to-hot promotion, live fallback checks, pregeneration live scans, and the shed order for hot-cap contraction (ADR-087 section 4.3, item 6).

- Candidates are still drawn in ADR-001 order. Steering changes only **when** a candidate is processed, never which candidates exist (ADR-087 section 3.3).
- A candidate is processed now if $w_k \ge 0.5$ **and** $w'_k \ge \tfrac{1}{2} \max_j w'_j$, the maximum taken over tick regions with queued RTP background work. Otherwise it is deferred in its current tier (ADR-087 section 3.5), and its deferral count and first-deferral time are recorded on the entry. No new queue is created. The first condition is headroom, an absolute test. The second is the bystander preference, a relative test: a populated region is deferred only while a less populated region with work offers at least twice its adjusted weight, so when every region with work is populated, population defers nothing.
- A candidate in no tick region (Type C, which would create one) has $w = 1/2$, the new-group prior, and $P = 0$, while ADR-087 section 3.2 permits are free, and is deferred while they are saturated (ADR-087 section 3.3). A new region has no players, so it is a good place for work, but it has no history yet; the prior and the pool bound protect it and the other regions.
- **Aging (I9).** After 60 s or 8 deferrals, whichever comes first, steering stops deferring a candidate: it is taken first in its region's next turn. An aged candidate is an ordinary unit. It counts against the turn's quota $u_r$ (ADR-087 I11) and the section 3.2 permits, and it waits like any other background work while a hard gate pauses background work, the ADR-112 circuit breaker holds its region, or the region's quota is zero; that time does not count towards the bound. Aging removes steering's delay and nothing else, so no other bound is exceeded. No candidate is dropped by deferral. The processed sequence is therefore the ADR-001 sequence with each element delayed by steering for at most the aging bound. Aging applies to background candidates only; no player request is deferred.
- **Hot-tier footprint.** A kept chunk becomes part of the tick region that owns it, and adds to that region's tick and merge footprint (ADR-112 Context item 5). Contraction sheds from the lowest-$w'_k$ regions first, and steering keeps new hot entries out of busy regions while they stay busy.
- This replaces the binary `RTPScheduler#isRegionSaturated` skip (ADR-112 decision 5) with a graded weight. The skip stays as the backstop.

**Compatibility with ADR-001.** ADR-001 fixes the order in which candidates are drawn and guarantees that every drawn candidate is evaluated; steering keeps both (I9: the multiset processed equals the multiset drawn, each delayed by at most the aging bound). What it does not keep is the *instantaneous* spatial mix of the hot tier. The aging bound (60 s) is longer than the ADR-087 refill horizon (30 s), so while a load pattern persists, the hot tier's mix at any instant is skewed towards regions with headroom, and teleports served from the hot tier are skewed the same way. The skew lasts as long as the load and ends within one aging bound after it. This is accepted because it applies to background refill only: live selection for a waiting player is not steered, and cold, backlog and on-demand paths are unaffected. The Folia differential benchmark (section 12) reports the hot-tier share of teleports under load so the size of the skew is visible.

### 8. Lending: finishing the work faster

Background throughput on Folia is $\sum_k \min(q_k, S_k^{b})$ per tick. Clustered work drains at one share per tick in its own region, whatever the other regions are doing. Steering moves work to where the headroom is; lending lets those regions drain it with budget that throttled regions did not use:

$$\Pi = S_{\text{cfg}} \cdot \min(K, \Theta), \qquad L = \max\left(0,\; \Pi - \sum_k S_k\right)$$

Here $K$ is the number of tick regions (groups) with RTP work in the last second, and $\Theta$ is the number of distinct tick threads that ran RTP work in that second. With one region per thread, $\Pi = K \cdot S_{\text{cfg}}$. With more regions than threads, $\Pi$ is today's per-thread total. $\Pi$ bounds the sum and the outer thread bucket bounds each thread, so neither the server nor any one thread can outspend today, except a thread carrying a loan (I1). If $\sum_k S_k > \Pi$, background shares $S_k^{b}$ are scaled down proportionally and $L = 0$. Interactive work is charged but never scaled or deferred (ADR-087 principle 4, ADR-112 decision 4).

- **The pool depends on how spread out RTP's work already is.** $\Theta$ counts threads that ran RTP work. If RTP's work is concentrated on one thread, $\Theta = 1$, $\Pi = S_{\text{cfg}}$ and there is nothing to lend. Lending therefore only helps once placement (section 2) and steering (section 7) have spread the work. This is accepted: it keeps the pool at today's bound by construction.

A region $k$ may borrow only when all of these hold:

- it has more queued work than its share ($q_k > S_k^{b}$);
- its limiter is at $S_{\text{cfg}}$ and it has had no breach for 10 s;
- its tick thread has run RTP work for no other group in the last second, so the loan cannot take time from another region where RTP also works. Folia does not pin regions to threads, and RTP cannot see regions where it has no work, so the same thread may tick such bystander regions between the borrower's ticks. A loan can delay those by at most the loan $e_k \le S_{\text{cfg}}$ per 50 ms beyond today's bound, and at most 25 ms in total RTP time on the thread. This is accepted only behind the remaining conditions and rollout step 4;
- no sampled region has shown an overload signal in the last 5 s;
- the ADR-087 section 3.2 permits are not saturated. A saturated pool time-shares tick threads, so extra time on one region would delay another region's tick.

Each borrower has its own loan limiter $\ell_k$, probed the same way as its share: it grows additively from 0 while the conditions hold and halves on any breach in $k$. Requests

$$e_k = \min\left(S_{\text{cfg}},\; 25\text{ ms} - S_k,\; \ell_k,\; q_k - S_k^{b}\right)$$

are scaled by $\min\left(1,\; L / \sum e_k\right)$. Hence $S_k + e_k \le \min(2 \cdot S_{\text{cfg}},\; 25\text{ ms})$ and $\sum_k (S_k + e_k) \le \Pi$. Loans are recomputed at every evaluation. A loan lapses when the lending region recovers, any breach appears, or the borrower's thread ticks another group with RTP work; the outer thread bucket then drops back to $S_{\text{cfg}}$ at once.

- *Why the absolute cap:* `syncAllottedTime` may be configured up to 25 ms (`MAX_ALLOTTED_MS`), and `RegionThreadBudget#budget()` clamps only at a full tick. A 2x loan alone would allow 50 ms, the whole tick. With the cap no region ever receives more than the largest value an operator can configure for one region.
- The global overload veto may hold most of the time on a busy server, which would make lending a calm-server feature. Go/no-go 3 measures how often it would hold before lending is built; if it holds most of the time, lending is not built (section 13, step 4).

### 9. Fairness measures and bystanders

Without a headroom reading, fairness is measured against what each region has shown it can take. RTP's use of a region's proven capacity is $x_k = c_k / \text{limit}_k$, with $c_k = c_k^{i} + c_k^{b}$. It is reported as Jain's index over regions with RTP work:

$$J = \frac{\left(\sum_k x_k\right)^2}{n \cdot \sum_k x_k^2}$$

$J = 1$ means every region gives up the same fraction of its proven capacity. A second measure tracks principle 2 directly: **bystander exposure** $E = \sum_k P_k \, c_k^{b} / \sum_k P_k$, the background RTP milliseconds per tick that the average online player's region carries. The allocator aims to keep $E$ low, not equal. The uniform ADR-112 cap scores low on both whenever load differs between regions.

**Bystanders.** The adjusted weight $w'_k = w_k / (1 + P_k / 4)$ makes background work and new hot entries prefer empty regions over populated ones that take work equally well (section 7, relative test), and orders hot-cap shedding (lowest $w'_k$ first). It is a preference between regions, not a cap on any one region. A teleport's arrival work necessarily lands in its destination's tick region; RTP destinations are mostly in sparse areas, and that work is the teleporting player's own cost (fairness rule 1).

### 10. Operator surface and visibility

No new key. On Folia, the ADR-087 `autoTune` key also enables this ADR's actuating steps once they ship; `autoTune: false` starts no sampler task and constructs no group bucket.

| Key | With `autoTune: true` | With `autoTune: false` |
|-----|-----------------------|------------------------|
| `syncAllottedTime` on Folia | Per-region share follows that region's headroom; once lending ships, a region with headroom, the only one with RTP work on its tick thread, may borrow unused budget up to 2x, at most 25 ms; the server-wide total never exceeds today's one value per tick thread running RTP work, and no other thread exceeds one value | Fixed per region thread (ADR-112) |

When lending ships (rollout step 4), the `autoTune` comment in `performance.yml` and its locale mirrors gain one sentence: "On Folia one region may borrow budget other regions leave unused, up to 2x syncAllottedTime (at most 25 ms)."

- **`/rtp info`** (all strings in `messages.yml`, REQ-RTP-F-013, REQ-RTP-S-007): the top tick regions by RTP cost (ms per tick by placement class, share, loan, deferred candidates, players), the global region's RTP cost, the total lent, the fairness index $J$, bystander exposure $E$, and sampler coverage (players per round and rotation length).
- **Metrics** (`metrics-api`): `rtp_governor_region_sync_ms` (by kind and placement class; label set bounded to the top 8 tick regions by cost plus `global` and `other`), `rtp_governor_lent_ms`, `rtp_governor_deferred_total`, `rtp_governor_aged_total`, `rtp_governor_fairness_index`, `rtp_governor_bystander_exposure_ms`, `rtp_governor_overruns_total`, `rtp_governor_breaches_not_rtp_total`.
- Reductions of a region's share below $S_{\text{cfg}}$ are reported through ADR-087's console line and `compute-governor` BIT component (REQ-RTP-OPS-002), with reason `REGION_OVERLOAD`.

### 11. Implementation layout

- **Placement.** Each tick-thread stage declares its placement class. Placement-free stages use `RTPScheduler#runTaskAsynchronously`; when an API needs a tick thread they use `RTPScheduler#runTaskForPlayer` with the consumer, or the global share for unowned work. Futures completed inside region-task bodies (`QueueTask`, `PregenTask`, `RegionCacheTask` live checks) complete through an executor backed by `RTPScheduler#runTaskAsynchronously` (no new threads, F-001), like `FoliaRTPWorld.OFF_REGION`; an inline fallback is counted and logged at `FINE`. Deadlines and failsafes that only complete a future move to async delayed tasks. Every global-region dispatch, including the `SyncTaskProcessing` pulse, runs inside a `RegionThreadBudget` span charged as $k = \text{global}$. In rollout step 1 that span only measures: its budget is unbounded, so wrapping changes no amount of work. The global share starts limiting with step 3.
- **Mechanical check.** An architecture test fails the build when `rtp-core` selection, cache, scan or teleport code calls a location-less `RTPScheduler#runTask` / `#runTaskLater` outside an allow-list of global-bound callers (VERIFICATION_GATES gate 2).
- **`FoliaSchedulerImpl`.** `RegionThreadBudget` gains a group bucket with supplier `() -> governor.regionalSyncNanos(groupOfCurrentSlice)` (share plus loan), and keeps its `ThreadLocal` bucket as the outer bucket with supplier `() -> governor.threadSyncNanos()` ($S_{\text{cfg}}$, or $S_k + e_k$ while the thread carries a loan). A task runs only if both have tokens (section 3). `RegionThreadBudget` resolves the group at slice start, runs the merge probe, and reports each charge to the ledger with its group, tick thread and kind (interactive or background); the governor derives $\Theta$, and whether a thread ticked only the borrower, from the distinct threads.
- **Steering reads.** Background candidate stages (`Region#execute` promotion, live fallback checks, `PregenTask` live scans, hot-cap shed order) read `budget.regionWeight(world, cx, cz)`, which returns $w_k$ and $w'_k$ together with the current $\max_j w'_j$. `RTPScheduler#isRegionSaturated` stays as the backstop. Deferral count and first-deferral time are two fields on the existing cache entry.
- **Allocator.** Shares, steering weights and loans are computed by a pure `RegionShareAllocator` in the ADR-087 governor package (inputs: per-region ledger, queue, overload, overrun and population readings; outputs: shares, weights, loans, $J$, $E$), and published in the same `ComputeBudget` snapshot.
- **Sampler.** `EntitySchedulerSampler` lives in `rtp-folia`, uses only `Entity#getScheduler()` and `Bukkit.isOwnedByCurrentRegion`, and is exposed through a new optional `MetricsBinding` default method that returns `NEVER_SAMPLED`, so other platforms are unaffected. No `EXTERNAL_HOOKS.md` row is needed (ADR-026). Sampler tasks are cancelled on shutdown, after their 10 runs, and when their player is retired.
- **Lifecycle.** Per-group state is bounded and reset with the governor on reload; no chunk tickets are held, so no `MemoryTracker` registration is needed.
- **Safety rules.** S-005: no signal read or actuation loads a chunk; chunk-bound work stays on its owning region. S-004: deferral leaves background candidates in their tier and never fails a teleport. F-001: no threads.

### 12. Verification and evidence

**Invariant checks.** Property tests run the pure `RegionShareAllocator` and simulated bucket charges over random signals, region sets and thread assignments, and assert on **published** values:

| ID | Check |
|----|-------|
| I1 | Over random group-to-thread assignments, including more groups than threads, false splits and undetected merges: per 50 ms, charges admitted to background tasks keep the server-wide total within $\Pi$ and each thread within $S_{\text{cfg}}$, or within $\min(2 S_{\text{cfg}}, 25\text{ ms})$ only on a thread that ran RTP work for the borrower alone, including with $S_{\text{cfg}} = 25$ ms. |
| I9 | Steering defers no candidate for more than 60 s or 8 deferrals of unpaused time (gate pauses, breaker holds and zero-quota turns excluded); aged candidates are taken first in the next turn and counted against $u_r$, so ADR-087 I11 holds with aging active; the multiset of processed candidates equals the multiset drawn. |

- **Allocator tests** (pure `RegionShareAllocator`): a breach halves that region's share on the same evaluation when RTP charged at least the contribution threshold, and holds it without cutting when RTP charged less; a populated region's limit does not grow within 60 s of a breach and then grows at a quarter rate; a new group starts at $S_{\text{cfg}} / 2$; a split group starts no higher than its old limit and merged groups keep the lower limit; the merge probe detects a merge within as many slices as there are live groups; tokens earned in one group are never spent in another; no loan while the borrower's limiter is below ceiling or has breached within 10 s, while its thread ticks another group, while any region is overloaded, or while section 3.2 permits are saturated; $w_k \le 1$ without a loan and $\le 2$ with one; when every region with work has 4 or more players, population alone defers nothing; an empty region is preferred over a populated one with an equal share; `autoTune: false` constructs no group bucket.
- **Placement tests**: placement-free stages never run on a region thread they did not declare; dependents of a future completed inside a region task run async; global-region work is charged as $k = \text{global}$, and in step 1 the global span runs the same work as without it; the architecture test rejects a new location-less `runTask` in selection code.
- **Sampler tests** (mocked entity scheduler, injected clock): population counts match `isOwnedByCurrentRegion` answers, and every online player is counted once per rotation; a region ticking every 50 ms at an arbitrary start phase produces no overrun; a region ticking steadily at 58 ms produces a breach within one window; one gap above 100 ms produces a breach; a retired player leaves no reference; a task cancels after 10 runs; at most 64 players are sampled per round; without ADR-087's observe-only property before its `autoTune` key ships, or with `autoTune: false` after, no sampler task starts.
- **Simulation**: the ADR-090 `SimulatedFoliaRuntime` with injected regional MSPT and dispatch lag. A clustered scenario reproduces the ADR-112 stress shape (one tick region at high own load holding most queued work, several idle regions) and compares the uniform cap with this ADR.
- **Observe-only go/no-go**, run in ADR-087's observe-only mode (its rollout step 1) and recorded in this ADR before step 3:
  1. **Concentration diagnostic**: rerun the ADR-112 stress shape with per-region tick time split by placement class, global-region RTP time, placement-free time inside region spans, inline fallbacks of the off-region hand-off, and region age at the first late tick. It establishes which Context mechanism produced the low-TPS region, and how much of sections 3-9 is needed. If placement (step 1) alone removes the concentration, steps 3 and 4 are not built.
  2. **Sampler agreement**: sampler breaches agree with `regionTps1m` and slice dispatch lag on each supported Folia version.
  3. **Lending veto**: the share of time the section 8 overload veto would hold.
- **Folia differential benchmark** (clustered stress run, uniform cap versus this ADR): worst-region TPS p5, spread between best and worst region MSPT, global-region MSPT, background fill time, teleport latency p99, hot-tier share of teleports, $J$ and $E$. Acceptance: worst-region TPS p5 and teleport latency p99 no worse, background fill time shorter, $J$ higher and $E$ lower.
- **Traceability**: I1 and I9 are added to `REQUIREMENTS.md` and `TRACEABILITY.md` when this ADR is accepted; each check carries its REQ ID.
- **Revision rule**: ADR-087's applies. Numeric constants (64 players, 10 runs, 60 ms overrun, 60 s / 8 deferrals, the $P_k / 4$ bystander factor, the quarter growth rate) are initial values, revisable under that rule.

### 13. Rollout

Steps 3 and 4 are independently revertible by `autoTune: false`; steps 1 and 2 are not governed by it.

1. **Placement.** Move placement-free work off the global region and off region threads, add global-region charging (measuring only, section 11) and the architecture test. This step changes where work runs, not how much, is independent of `autoTune` and of ADR-087, and may land before ADR-087 step 1.
2. **Observe.** Cell groups, the ledger, the sampler, $J$ and $E$ are computed and published in ADR-087's observe-only mode. Nothing actuates. Run go/no-go 1-3.
3. **Shares and steering.** After ADR-087 step 2 (its `autoTune` key), actuate the per-region share with probing and steering with aging, if go/no-go 1 shows tick-region concentration that shares can fix and go/no-go 2 passes, since the shares actuate on sampler breaches. The global share starts limiting.
4. **Lending.** Enable only if go/no-go 3 shows the veto leaves lending a meaningful share of time, and the Folia differential benchmark passes. If the veto holds most of the time, lending is not built.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **Uniform per-region cap on Folia (ADR-112 as is)** | A region at 45 ms of its own load pays the same as one at 5 ms, and clustered work drains at one budget per tick while other regions sit idle. Kept as the fallback while this ADR's steps are not shipped. |
| **Cut a region's share on every breach** | Farms and boss fights would cap RTP in regions where it spent nothing, and those regions could never recover to lend. Cuts require RTP's contribution (section 6). |
| **One-tick-delayed sampler with a 100 ms threshold** | A healthy region already reads 50-100 ms depending on phase, and a region steadily at 55-60 ms per tick is missed. Consecutive-run intervals remove the phase (section 5). |
| **Run all of a consumer's work on the consumer's thread** | Adopted for placement-free work only. Chunk-bound work must run on the region that owns the chunk; a task on the consumer's entity scheduler that touches a distant chunk throws `ThreadAccessException`. For chunk-bound work the consumer is billed instead ([ADR-124](ADR-124-consumer-attribution-for-background-refill.md)). |
| **Strictly avoid busy regions (no aging)** | Starves those regions' candidates and permanently biases destinations away from populated areas (ADR-001). |
| **Lending without the pool-health gate, unbounded, or capped only at 2x** | When the pool is saturated, tick threads are time-shared, so extra time on one region delays others (ADR-087 section 3.2). A 2x cap alone allows a whole 50 ms tick at `syncAllottedTime: 25`. |
| **Run chunk-bound work on another region's thread** | Violates Folia's threading contract; only work that touches no chunk can move between threads (section 2). |
| **Leave location-less work on the global region (status quo)** | One serial queue carries every placement-free task, whatever the number of tick regions, and none of it is budgeted. |
| **Reflective per-region tick report (`/tps` internals)** | Would give spare time below 50 ms directly, but reads Folia internals that can change on any update and needs an ADR-026 hook. Probing with public interval readings finds the same limits, at the cost of an occasional late tick in sparse regions. |
| **Specify this inside ADR-087** | It is a separate decision with its own evidence gates, and its need depends on go/no-go 1. Keeping it apart lets ADR-087 be accepted or rejected on its own. |

## Consequences

- **Positive:**
  - Placement-free work no longer queues on the global region or on whichever region completed a future, and every global-region dispatch is measured.
  - Background work is steered away from busy and populated tick regions, and clustered work can finish sooner because regions with headroom take it and may borrow unused budget. Whether this delivers is measured by the Folia differential benchmark; per-region cost, $J$ and $E$ make it visible who pays.
  - RTP's server-wide and per-thread sync spend stays within today's ADR-112 bound, except the bounded loan (I1).
- **Negative / Trade-offs:**
  - Without a public spare-time reading, each region's share is found by probing: a region occasionally gets a late tick while RTP finds its limit. Populated regions grow only after 60 s without a breach and at a quarter rate, so this mostly falls on empty regions or the teleporting player. In exchange, background work in a populated region stays near half of `syncAllottedTime` for minutes after the region forms or breaches.
  - Tick-region state is inferred from cell groups because public API has no region identity. Resolution costs up to one `isOwnedByCurrentRegion` test per live group per slice, plus one merge-probe test. Until a merge is found, or after a false split, one tick region holds two groups and two shares; the outer thread bucket keeps its spend at today's per-thread bound, but its ledger, $J$ and $E$ are split across the two groups.
  - The outer thread bucket means a tick thread that ticks several groups still spends at most one `syncAllottedTime` per 50 ms across them, as today; groups on a shared thread compete for it.
  - The sampler runs up to 10 one-tick tasks per sampled player every 2 s (at most 64 players per round) and adds per-player cleanup on quit. $P_k$ lags by up to one rotation on servers with more than 64 players.
  - Lending only helps once RTP's work is spread over several tick threads, and may rarely activate on a busy server because of the overload veto. A loan can delay bystander regions RTP cannot see by at most the loan.
  - Placement-free work moved onto a consumer's entity scheduler runs in the consumer's region, which may be a populated hub. It is small by construction and appears in that region's ledger.
  - Background destinations in a busy tick region are processed later, up to the aging bound (60 s), which is longer than the refill horizon. While the load lasts, the hot tier's instantaneous mix is skewed towards regions with headroom (section 7, compatibility with ADR-001). Every drawn candidate is still processed (I9).

## References

- [ADR-087](ADR-087-adaptive-compute-governor.md): Adaptive Compute Governor (snapshot, limiter, observe-only mode, section 3 concurrency bounds)
- [ADR-112](ADR-112-per-region-work-division-on-folia.md): Per-Region Work Division on Folia (per-thread bucket, cells, circuit breaker)
- [ADR-124](ADR-124-consumer-attribution-for-background-refill.md): Consumer attribution (bills chunk-bound work to the consumer)
- [ADR-001](ADR-001-archimedean-spiral-1d-mapping.md): Archimedean spiral mapping (draw order preserved under deferral)
- [ADR-026](ADR-026-external-hook-api-surface.md): External hook API surface (no reflective hook is needed; public API only)
- [ADR-090](ADR-090-test-driver-and-synthetic-mca-architecture.md): `SimulatedFoliaRuntime`
- Folia regional threading, `RegionScheduler` and `EntityScheduler` contracts
