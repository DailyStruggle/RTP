# ADR-131 - Three-Regime Mob AI Encoding Architecture and Technical-Farm TDD Parity Proof Contract

**Status:** Proposed
**Date:** 2026-11-15
**Extends:** [ADR-094](ADR-094-quality-engineering-gates.md), [ADR-111](ADR-111-live-server-behaviour-fuzzing.md), [ADR-113](ADR-113-java-17-baseline-and-26x-support-boundary.md), [ADR-115](ADR-115-package-dag-disentanglement-and-decision-preservation-hypergraph.md), [leafanvil-ADR-002](../../resource/LeafAnvil/docs/adr/leafanvil-ADR-002-in-memory-lca-chunk-representation-and-dirty-overlay-compaction.md), [leafsleuth-ADR-002](../../resource/LeafSleuth/docs/adr/leafsleuth-ADR-002-four-pillar-bayesian-stylometric-and-behavioral-profiling.md), [leafsleuth-ADR-005](../../resource/LeafSleuth/docs/adr/leafsleuth-ADR-005-unified-causal-server-forensics-engines.md)

---

## Context

On high-player survival Paper and Folia servers (`simulation-distance: 8`, `10,000-18,000` loaded entities), once world generation (`LeafGen`) and chunk I/O (`LeafAnvil`) are off the critical path, mob AI (`Brain<E>`, `GoalSelector`, `Sensor` sweeps, and `WalkNodeEvaluator` A* pathfinding) becomes one of the dominant per-tick CPU and Young-Gen allocation bottlenecks:

1. **Pointer-Chasing `Brain<E>` & Megamorphic `BehaviorControl` Polling:**
   - Mojang's `Brain<E>` (`Villager`, `Piglin`, `Warden`, `Breeze`, `Frog`, `Goat`, `Armadillo`, `Axolotl`, `Camel`, `Sniffer`) stores `40-80+` entries in `Map<MemoryModuleType<?>, Optional<? extends ExpirableValue<?>>>` alongside nested `Map<Integer, Map<Activity, Set<BehaviorControl<? super E>>>>` tables.
   - Every tick, `Brain#tick` decrements boxed `ExpirableValue` wrappers, runs periodic `Sensor` scans (such as `SecondaryPoiSensor` `16x16x16` block-cube walks and `NearestLivingEntitiesSensor` entity sorts), and invokes megamorphic `Map<MemoryModuleType<?>, MemoryStatus>` precondition checks across dozens of inactive behaviors even when `95%+` cannot start.
2. **Redundant Confined-Mob Pathfinding & Stationary Schedule Churn:**
   - Real survival servers concentrate thousands of mobs in spatially invariant enclosures (`1x1` `Villager` trading halls, enclosed livestock pens, idle farms) where `WalkNodeEvaluator` repeatedly allocates `Path` and `Node` heaps only to hit the same stationary trapdoor, lectern, or fence every few seconds.
3. **Proven Low-Allocation Actor Encodings Already in the Monorepo:**
   - In `:resource:LeafFuzz:leaffuzz-common`, `PersonaPolicyBotActor` (`computeContextInt8` + `selectPolicyActionInt`) packs local agent state into a `byte[16]` vector (`16 B`) and evaluates a `256-byte` `Int8[16x16]` policy genome via a 16-entry integer `EXP2_FRAC_LUT` (`Q10` fixed-point softmax) in `~40-60 ns` with zero heap allocations.
   - In `:resource:LeafSleuth:sleuth-common`, `GoalMarkovProfiler` models routine state transitions $P(S_{t+1} \mid S_t)$ in compact `7x7` matrices while `EmpiricalFarmBlackBox` gates steady-state contraption virtualization behind spatial `ProvenanceTaint` invalidation.
   - In `:resource:LeafAnvil`, `leafanvil-ADR-002` maintains per-section block-mutation counters (`sectionEpoch`) updated inline on block edits.
4. **The Technical-Farm Vanilla Parity Trap:**
   - Unlike generative fuzz bots (`LeafFuzz`) or observational behavioral classifiers (`LeafSleuth`), technical survival Minecraft treats **exact Mojang mob quirks and bug-for-bug timing as load-bearing game mechanics**:
     - **Iron farms** depend on exact `Villager` `GolemLastSeen` and `NearestVisibleLivingEntities` panic cadence, sleep/work schedule boundaries, and line-of-sight checks against `Zombie` / `Pillager` triggers.
     - **Mob grinders, creeper farms, and raid farms** depend on `WalkNodeEvaluator` treating open trapdoors, coral fans, and scaffolding as walkable nodes, as well as deterministic `GoalSelector` priority tie-breaking and `RandomSource` consumption order.
   - Replacing active survival mob `Brain<E>` or `GoalSelector` logic with an *approximate* `Int8[16x16]` softmax matrix or a *lossy* Markov chain would silently alter farm drop rates and violate behavioral parity (`ADR-115`).

---

## Decision

### 1. Three-Regime Mob AI Encoding Architecture

Mob AI acceleration across JVM server carriers (`Paper`, `Folia`, `Fabric`, `NeoForge`) is partitioned into three strict regimes bounded by explicit vanilla-parity guarantees:

| Regime | Target Entities | Encoding Pattern | Parity Guarantee |
|---|---|---|---|
| **Regime 1: Lossless Bitmask & `byte[16]` Pre-Filter** | All active vanilla survival mobs (`Brain<E>` & `GoalSelector`) | `long` occupancy bitmask + `byte[16]` quantized sensor lane + `(sectionKey, sectionEpoch)` `PathType` cache | **100% lossless** (exact vanilla transitions & `RandomSource` sequence) |
| **Regime 2: Taint-Gated Schedule & Markov Folding** | Spatially confined, zero-taint stationary mobs (`1x1` halls, pens) | Scheduled wakeup horizon (`GoalMarkovProfiler` / `EmpiricalFarmBlackBox` taint gate + `LeafAnvil` `sectionEpoch`) | **Exact observable parity** (`0`-tick fail-open wakeup to Regime 1 on any stimulus) |
| **Regime 3: Opt-In `Int8[16x16]` Policy Genomes** | Explicit custom NPCs, minigame crowds, opt-in distant cosmetic ambient agents | `256-byte` `Int8[16x16]` weight matrix + `Q10` `EXP2_FRAC_LUT` over `SoA` primitive arrays (`PersonaPolicyBotActor`) | **Opt-in non-farm scope only** (never applied to natural survival or farm-eligible mobs) |

#### 1.1 Regime 1 - Lossless Bitmask & `byte[16]` Sensor Pre-Filter (`BrainBitmaskGate`, `ChunkEpochPathTypeCache`)
- **Flat Memory & Sensor Mirror (`BrainBitmaskGate`):**
  - Each `Brain<E>` mirrors `MemoryModuleType` presence (`VALUE_PRESENT` vs. `VALUE_ABSENT`) into a contiguous 64-bit word (`long memoryPresentMask`, extended to `long[2]` when registered `MemoryModuleType` count exceeds `64`) maintained inline on `Brain#setMemoryWithExpiry` and `Brain#eraseMemory`, alongside an indexed `Object[]` slot table replacing `HashMap` lookup churn and a cache-line-aligned `byte[16]` quantized sensor lane (`nearestPlayerDistQ`, `hurtTimerQ`, `panicFlag`, `breedCooldownQ`, `lightAndFluidFlags`, `scheduleActivityId`) modeled on `PersonaPolicyBotActor#computeContextInt8`.
  - At registration time, every `BehaviorControl` and `:bytecode-api`-classified pure-guard `Goal` compiles its static `(requiredPresentMask, requiredAbsentMask)` bitmask pair.
  - During `Brain#tick` (or `ByteVector` / `LongVector` batch sweeps across entities owned by the current Folia region / Paper tick thread, `F-001`), `(mobPresentMask & reqPresent) == reqPresent && (mobPresentMask & reqAbsent) == 0` filters out ineligible behaviors in CPU registers before dereferencing heap `Optional` wrappers or megamorphic virtual call sites.
  - Every behavior whose bitmask precondition passes executes Mojang's exact `checkExtraStartConditions` and `start`/`tick` methods in canonical registration order, preserving `100%` of vanilla side effects and `RandomSource` draws.
- **Epoch-Invalidated Walkability Cache (`ChunkEpochPathTypeCache`):**
  - `WalkNodeEvaluator` caches 4-bit static block `PathType` nibbles (`TRAPDOOR`, `OPEN`, `BLOCKED`, `DAMAGE_OTHER`, `WATER`, `LAVA`) packed `2` voxels per byte and keyed by `(sectionKey, LeafAnvil.sectionEpoch)`.
  - Any block-state change increments `sectionEpoch` (`leafanvil-ADR-002`), invalidating stale `PathType` entries in $O(1)$ while keeping entity/door/fluid dynamic overrides exact.

#### 1.2 Regime 2 - Taint-Gated Stationary Folding for Confined Mobs (`ConfinedMobEpochFolder`)
- **Strict Entry Preconditions:** A survival mob may enter folded schedule evaluation only after all four conditions hold continuously across a configurable quiet window (`default = 200 ticks`):
  1. **Zero Reachable Movement Degree:** `WalkNodeEvaluator` verifies `0` reachable adjacent horizontal/vertical path nodes from the mob's current block (`1x1` cell confinement).
  2. **Zero Block-Epoch Mutation:** All chunks/sections within the mob's sensor neighborhood (`3x3x3` sections) exhibit `0` `sectionEpoch` increments across the quiet window.
  3. **Zero Threat, Redstone, or Player Taint:** Zero hostile mobs, projectiles, minecarts/boats, piston/dispenser activations, or player interactions intersect the mob's sensor bounding box (`EmpiricalFarmBlackBox` `ProvenanceTaint` invariant, `leafsleuth-ADR-005`).
  4. **Zero Unclassified Plugin/Custom Hooks:** `:bytecode-api` (`BytecodeCausalGraphScanner`) confirms no custom `Goal` subclass, NMS `Brain` injection, or side-effecting entity tick listener targets the entity instance.
- **Folded Execution & `0`-Tick Fail-Open Wakeup:**
  - While folded, `ConfinedMobEpochFolder` advances deterministic `Activity` schedule transitions (`WORK`, `IDLE`, `REST`), workstation restock counters, gossip decay, and breeding/age timers via exact scheduled wakeup ticks (`GoalMarkovProfiler` transition horizon) rather than per-tick `SecondaryPoiSensor` cube scans or `PathNavigation` heap allocations.
  - Any increment to a watched `sectionEpoch`, hostile/player bounding-box entry, entity damage/velocity impulse, leash/ride/trade interaction, or chunk unload immediately clears the folded bit and executes full Regime 1 / vanilla NMS `Brain#tick` **within the same server tick** (`0`-tick wakeup latency).

#### 1.3 Regime 3 - Opt-In `256-Byte` `Int8[16x16]` Policy Genomes (`Int8PolicyGenomeEngine`)
- Exposes `PersonaPolicyBotActor#selectPolicyActionInt`'s `256-byte` `Int8[16x16]` weight matrix + `16-entry` `EXP2_FRAC_LUT` `Q10` evaluator over Structure-of-Arrays (`SoA`) entity position/velocity lanes for high-density custom NPCs, minigame swarms, and explicitly tagged ambient crowds (`10,000+` agents in `< 1 ms`).
- **Hard Survival Isolation:** Regime 3 requires an explicit API/spawn opt-in marker and is structurally prohibited from attaching to natural survival mob spawns, persistent/named entities, or any technical-farm species (`Villager`, `IronGolem`, `Zombie`, `Raider`, `Creeper`, `Enderman`, `WitherSkeleton`, `Piglin`, `Allay`, `Warden`, `Witch`, `Blaze`, `Ghast`, `Shulker`).

---

### 2. Mandatory TDD & Differential Technical-Farm Parity Proof Contract

Per [`docs/dev/VERIFICATION_GATES.md`](../dev/VERIFICATION_GATES.md) (Gates 1, 2, 5, 8, and 10), **prose reasoning is insufficient to ship any Mob AI fast path**. Every Regime 1 or Regime 2 implementation PR must author and pass the following four automated proof tiers *prior* to enabling the optimization by default (TDD red-green-benchmark sequence):

#### Proof Tier P1 - Lockstep Shadow-Tick & `RandomSource` Equivalence (`ShadowBrainEquivalenceTest`)
- Executes unaccelerated vanilla `Brain<E>` / `GoalSelector` in lockstep against `BrainBitmaskGate` on cloned deterministic worlds across `>= 10,000` ticks and `>= 3` distinct seeds covering every vanilla `Brain`-driven mob species.
- **Required Assertions (`0` tolerance):**
  1. **Zero False-Negative Skips:** For every `(entity, tick, behavior)` tuple, if `bitmaskPreFilter == false`, direct invocation of vanilla `hasRequiredMemories` / pure `canUse` shall also return `false` (`falseNegativeCount == 0`).
  2. **Exact Active-Set & `RandomSource` Parity:** At the end of every tick $t$, the ordered active `BehaviorControl` / `Goal` set, internal `RandomSource` draw count/state, entity `BlockPos`, `Vec3` velocity, and `FNV-1a-64` digest (`offset_basis = 0xcbf29ce484222325L`) over `(memoryModuleId, presentBit, ttlRemaining)` shall match vanilla bit-for-bit.

#### Proof Tier P2 - Golden Technical-Farm Contraption Suite (`TechnicalFarmParitySuite`)
- Runs headless server/fixture simulations (`>= 24,000` ticks = `20` in-game minutes per seed) comparing baseline vanilla against Regime 1 + Regime 2 across four canonical technical-farm archetypes:
  1. **`IronFarmPanicFixture` (`Villager` + `Zombie` line-of-sight bobbing):** Verifies identical `GolemLastSeen` expiry ticks, sleep/panic transitions, and exact `IronGolem` spawn count and spawn-tick timestamps.
  2. **`TradingHallFoldWakeupFixture` (`64` confined `1x1` `Villager`s):** Verifies identical daily workstation restock counts while Regime 2 is folded, plus `0`-tick wakeup parity when (a) a workstation block is broken (`sectionEpoch` increment), (b) a `Zombie` enters panic range, or (c) a player opens a `Merchant` trade session.
  3. **`PathfindingTrapdoorGrinderFixture` (`WalkNodeEvaluator` + `ChunkEpochPathTypeCache`):** Verifies identical `PathType` nibble resolution and mob drop-shaft entry rates across open/closed trapdoors, coral fans, scaffolding, water streams, and mid-run block edits.
  4. **`RaidAndMobSwitchBoundaryFixture`:** Verifies identical `Raider` wave progression, despawn sphere culling, and entity cramming outcomes.

#### Proof Tier P3 - `:bytecode-api` Guard Classification & Mutation-Kill Gate (`Gate 2` & `Gate 5`)
- Automated mutation checks in `Gate 1` unit tests shall deliberately inject:
  1. a 1-bit corruption in `requiredPresentMask` / `requiredAbsentMask`;
  2. a suppressed `sectionEpoch` increment on block mutation; and
  3. a synthetic third-party plugin `Goal` mutating external state inside `canUse()`.
- The suite passes **only** if mutations (1) and (2) fail `ShadowBrainEquivalenceTest` / `TechnicalFarmParitySuite` deterministically and case (3) is classified as impure by `BytecodeCausalGraphScanner`, forcing fail-open vanilla execution.

#### Proof Tier P4 - Differential MSPT & Eden Allocation Evidence (`Gate 10`)
- Any PR enabling a Regime 1, 2, or 3 fast path shall attach differential benchmark output from the same machine, JVM flags, and world seed (`baseline` vs. `accelerated` via runtime flags `mobAi.regime1BitmaskEnabled`, `mobAi.regime2ConfinedFoldingEnabled`, `mobAi.shadowVerifyMode`), reporting:
  - sample size ($N \ge 1,000$ measured ticks after warmup), `p50` / `p95` / `p99` tick time (`ms`), and run-to-run variance;
  - `Brain` + `GoalSelector` + `PathNavigation` CPU share delta;
  - Young-Gen heap allocation rate (`bytes/tick`); and
  - `TechnicalFarmParitySuite` yield delta (`expected = 0` for deterministic seeds).
- The unaccelerated baseline path shall remain runnable behind runtime flags so live shadow verification (`mobAi.shadowVerifyMode = true`) and regression A/B benchmarks remain permanently executable (`VERIFICATION_GATES.md` Gate 10).

---

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Replace active survival `Brain<E>` / `GoalSelector` with approximate `Int8[16x16]` `Q10` softmax genomes | Breaks deterministic `Villager` iron-farm panic cadences, trapdoor grinder pathfinding quirks, and `RandomSource` parity relied upon by technical survival players. |
| Distance-based tick throttling (`tick every Nth tick` / lobotomizing distant villagers via static config lists) | Alters restock timers, breaks iron/raid farm timing windows when players stand at farm AFK spots, and requires manual per-server tuning instead of automatic taint/epoch-safe folding. |
| Off-thread asynchronous `Brain#tick` / `GoalSelector` mutation across live chunks | Violates Folia region ownership (`F-001..F-003`) and races unsynchronized NMS `Level`, `EntityLookup`, and Bukkit plugin event listeners (`S-001`, `S-005`). |

---

## Consequences

- **Positive:**
  - Captures `LeafFuzz` (`byte[16]` + bitmask register/SIMD filtering) and `LeafSleuth` (`ProvenanceTaint` + `GoalMarkovProfiler` wakeup folding) CPU and allocation wins on dense survival servers without breaking a single iron farm, trading hall, or mob grinder.
  - Gives future implementers an unambiguous, mechanical TDD proof gate (`P1..P4`) before any NMS `Brain` or `PathNavigation` hook is allowed to ship.
  - Preserves a dedicated opt-in `Int8[16x16]` `SoA` lane (Regime 3) for custom `10,000+`-agent RPG/minigame workloads where vanilla farm quirks do not apply.
- **Negative / Trade-offs:**
  - Maintaining `memoryPresentMask`, `sectionEpoch` invalidation hooks, and `mobAi.shadowVerifyMode` lockstep harnesses increases test-fixture engineering up front before runtime speedups can be enabled by default.
  - Confined mobs standing inside active redstone/piston clocks or constant player traffic continuously trip taint/epoch guards and remain safely in Regime 1 rather than Regime 2.

---

## Invariants

- **Lossless Pre-Filter Soundness (`REQ-MOBAI-001`, `ADR-115`):** `BrainBitmaskGate` shall never suppress a `BehaviorControl` or `Goal` that would return `true` under unaccelerated vanilla `Brain<E>` / `GoalSelector` evaluation (`falseNegativeCount == 0`), and shall preserve exact evaluation order and `RandomSource` consumption for all eligible behaviors.
- **Zero-Tick Stimulus Wakeup (`REQ-MOBAI-002`):** `ConfinedMobEpochFolder` shall exit folded Regime 2 and execute full Regime 1 / vanilla NMS tick evaluation on the exact server tick that any watched `sectionEpoch` increments, any entity enters sensor range, or any damage, velocity, redstone, or player interaction occurs.
- **Survival Isolation of Approximate Policy Genomes (`REQ-MOBAI-003`):** `Int8PolicyGenomeEngine` (Regime 3) shall never attach to natural survival spawns, persistent/named entities, or technical-farm mob species unless explicitly spawned with an opt-in custom-agent capability flag.
- **Region-Thread Affinity & Fail-Closed Fallback (`REQ-MOBAI-004`, `F-001`, `S-004`, `S-005`):** All NMS `Brain<E>`, `GoalSelector`, and `Entity` state reads/writes shall execute strictly on the owning server/Folia region thread with zero blocking chunk/disk I/O; any `:bytecode-api` guard classification fault or shadow-mode mismatch shall log a `WARNING` via `ServerAccessor#log` and fall back immediately to unaccelerated vanilla execution.
- **Mandatory `P1..P4` Proof Gate Before Default Activation (`REQ-MOBAI-005`, `ADR-094`):** No Regime 1 or Regime 2 optimization shall be enabled by default in any carrier module without committed, passing `ShadowBrainEquivalenceTest` (`P1`), `TechnicalFarmParitySuite` (`P2`), mutation-kill checks (`P3`), and differential `Gate 10` variance benchmarks (`P4`).

---

## References

- [`PersonaPolicyBotActor`](../../resource/LeafFuzz/leaffuzz-common/src/main/java/net/leaf/sleuth/common/fuzz/PersonaPolicyBotActor.java) (`byte[16]` `computeContextInt8` & `256-byte` `Int8[16x16]` `Q10` `EXP2_FRAC_LUT` `selectPolicyActionInt`)
- [`GoalMarkovProfiler`](../../resource/LeafSleuth/sleuth-common/src/main/java/net/leaf/sleuth/common/behavior/GoalMarkovProfiler.java) & [`EmpiricalFarmBlackBox`](../../resource/LeafSleuth/sleuth-common/src/main/java/net/leaf/sleuth/common/governor/EmpiricalFarmBlackBox.java) (`7x7` Markov state transitions & `ProvenanceTaint` contraption virtualization)
- [`docs/dev/VERIFICATION_GATES.md`](../dev/VERIFICATION_GATES.md) (Gates 1, 2, 5, 8, and 10)
- [`docs/dev/RULES.md`](../dev/RULES.md) (`F-001..F-003`, `S-001..S-007`, `Rule D-002`, `Rule D-010`)
