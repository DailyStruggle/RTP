# predict-api

Zero-dependency online statistical estimation, adaptive rate/budget control, fair work attribution, Folia regional share allocation, and deterministic/stochastic mathematical shaping & sampling toolkit (`io.github.dailystruggle.rtp.common.predict`). Pure Java 17 (`java.base` only; no Minecraft, Bukkit, Fabric, or `rtp-core` dependencies), so any standalone plugin or mod (e.g. `TreeGen`, `OnePlayerSleep`, `WorldGuardLite`) can pace tick-bound work, govern competing execution paths, attribute background compute per player, allocate Folia regional shares, and evaluate unit-interval shaping curves, continuous distribution samplers, and polar-spiral / harmonic cross-sections.

## Coordinates

- **Maven Central:** `io.github.dailystruggle:predict-api:<version>`
- **JitPack:** `com.github.DailyStruggle.RTP:predict-api:<tag>`

```gradle
dependencies {
    implementation 'io.github.dailystruggle:predict-api:3.3.1'
    // or via JitPack:
    // implementation 'com.github.DailyStruggle.RTP:predict-api:3.3.1'
}
```

## Surface

- `EwmaEstimator` - thread-safe running mean, Welford variance, and squared standard error (`standardErrorSquared()`) estimator supporting `DecayMode.FRACTIONAL` (`ofWindow`, `ofAlpha`) and lock-free `DecayMode.INTEGER_SEVEN_EIGHTHS` (`ofTaskPipe`, `ofIntegerDecay`).
- `BernoulliEstimator` - exponentially weighted binary outcome estimator (`ofWindow`, `recordOutcome(boolean)`, `rate()`, `sampleCount()`) for tracking hit, rejection, or success probabilities.
- `EwmaLinearFit` - online simple linear regression `y = intercept + slope * x` (`ofCoalescedCost`, `record(x, y)`, `predict(x)`, `standardErrorSquaredAt(x)`) for separating per-item cost from shared batch-open overhead (`x = 1.0 / groupSize`).
- `AimdLimiter` - lag-aware Additive-Increase / Multiplicative-Decrease stability limiter (`evaluate(modelTarget, breached, maxLagNanos, anyStale, allNeverSampled, nowNanos)`) with asymmetric deadband publication and `STALE` / `NEVER_SAMPLED` signal hold semantics.
- `BudgetPacer` - tick-budget task admission controller (`ofTaskPipe`, `ofEstimator`, `canExecute(elapsedNanos, availableNanos, ranAny)`, `recordDuration(durationNanos)`) guaranteeing forward progress on positive budgets and overrun protection when `availableNanos <= 0`.
- `ComparativeCostGovernor<T>` - branch selector supporting stateless policies (`of(Function<CostContext, T>)`) and standard-error-gated hysteresis (`ofVarianceHysteresis(HysteresisConfig)`, `evaluateVarianceGated(costA, costB, combinedSeSquared)`, `CoalescedTrialAccrual`).
- `CostContext` - immutable `Map<String, Double>` metric snapshot (`CostContext.builder().with(key, val).build()`) consumed by `ComparativeCostGovernor`.
- `WaterFillingAllocator` - constrained weighted max-min fairness solver (`allocate(deliverableDemands, weights, totalBudget)`) and share normalizer (`normalizeShares(plannedRates)`).
- `WorkAttributionLedger<K>` - lock-free bounded (4096-entry LRU) dual-EWMA (30 s and 10 min half-lives) per-actor cost ledger (`recordConsumption`, `recordCharge`, `distinctConsumers`, `attributedCostMsPerMin`, `topActors`, `costP99MsPerMin`).
- `ProbedShareAllocator` - lock-free Folia / multi-group probed share, steering-weight (`w_k`, `w'_k`), and idle-thread pool lending (`Pi`, `e_k`) allocator (`evaluate(...)`, `recordSplit`, `recordMerge`, `isCandidateAged`) computing Jain's fairness index `J` and bystander exposure `E`.
- `UnitCurve` - stateless, allocation-free, `NaN`/boundary-clamped unit-interval shaping and envelope evaluators (`clamp01`, `linear`, `power`, `complementaryPower`, `sigmoid`, `smoothstep`, `parabolicCrown`, `gaussian`, `logarithmic`, `logarithmicTaper`, `harmonic`) and composable `DoubleUnaryOperator` factories (`ofLinear`, `ofPower`, `ofSigmoid`, `ofGaussian`, `ofLogarithmic`, `ofLogarithmicTaper`).
- `DistributionSampler` - deterministic (`DoubleSupplier`) and `ThreadLocalRandom` continuous samplers over `[0, 1)` and `[0, range)` (`samplePowerLaw`, `samplePowerLawIndex`, `sampleTruncatedNormal`, `radialAreaExponent`) plus deterministic 64-bit FNV-1a + SplitMix64 seed derivation (`variantSeed(namespaceKey, variantIndex)`).
- `PolarCurve` - parametric polar-to-Cartesian spiral and multi-harmonic cross-section helpers (`spiralOffset`, `spiralAngle`, `harmonicRadius` general array and fast 3-harmonic overloads, `annularRingRadius`).

## Usage

```java
import io.github.dailystruggle.rtp.common.predict.AimdLimiter;
import io.github.dailystruggle.rtp.common.predict.BudgetPacer;
import io.github.dailystruggle.rtp.common.predict.EwmaEstimator;
import io.github.dailystruggle.rtp.common.predict.WaterFillingAllocator;
import io.github.dailystruggle.rtp.common.predict.WorkAttributionLedger;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

// Standalone plugin (e.g. TreeGen) pacing per-tick generation and sharing background refill fairly:
BudgetPacer pacer = BudgetPacer.ofEstimator(
        EwmaEstimator.ofWindow(32, TimeUnit.MILLISECONDS.toNanos(1)), 1.10);
AimdLimiter limiter = new AimdLimiter(1.0, 10.0); // [1 ms, 10 ms] per-tick budget

long now = System.nanoTime();
double budgetMs = limiter.evaluate(10.0, false, TimeUnit.SECONDS.toNanos(1), false, false, now);
long availableNanos = Math.round(budgetMs * 1_000_000.0);

long start = System.nanoTime();
boolean ranAny = false;
while (pacer.canExecute(System.nanoTime() - start, availableNanos, ranAny)) {
    long t0 = System.nanoTime();
    // run one generation step...
    pacer.recordDuration(System.nanoTime() - t0);
    ranAny = true;
}

// Attribute work by player UUID and divide constrained refill across worlds via water-filling:
WorkAttributionLedger<UUID> ledger = new WorkAttributionLedger<>();
ledger.recordConsumption("overworld", UUID.randomUUID(), TimeUnit.MILLISECONDS.toNanos(2), now);

Map<String, Double> demands = Map.of("overworld", 8.0, "nether", 4.0);
Map<String, Double> weights = Map.of(
        "overworld", (double) ledger.distinctConsumers("overworld", now),
        "nether", (double) ledger.distinctConsumers("nether", now));
Map<String, Double> allocated = WaterFillingAllocator.allocate(demands, weights, 6.0);

// Unit-interval shaping, deterministic variant seeding, and polar spiral / harmonic geometry:
long seed = DistributionSampler.variantSeed("minecraft:oak", 7L);
java.util.Random rng = new java.util.Random(seed);
double rNorm = DistributionSampler.sampleTruncatedNormal(0.5, 1.0, 0.625, rng::nextGaussian, rng::nextDouble);
double[] offsetXZ = PolarCurve.spiralOffset(0.75, 12.0, 1.5, 0.0, UnitCurve::smoothstep);
double lobedRadius = PolarCurve.harmonicRadius(8.0 * UnitCurve.parabolicCrown(0.5), Math.PI / 3.0, 0.2, 1.1, 2.4);
```

## Build

```
./gradlew :predict-api:build
```

## Docs

- ADR-087: [`../../docs/adr/ADR-087-adaptive-compute-governor.md`](../../docs/adr/ADR-087-adaptive-compute-governor.md)
- ADR-109: [`../../docs/adr/ADR-109-measured-cost-probe-first-gate.md`](../../docs/adr/ADR-109-measured-cost-probe-first-gate.md)
- ADR-114: [`../../docs/adr/ADR-114-cost-governed-on-demand-chunk-reads.md`](../../docs/adr/ADR-114-cost-governed-on-demand-chunk-reads.md)
- ADR-115: [`../../docs/adr/ADR-115-package-dag-disentanglement-and-decision-preservation-hypergraph.md`](../../docs/adr/ADR-115-package-dag-disentanglement-and-decision-preservation-hypergraph.md)
- ADR-123: [`../../docs/adr/ADR-123-folia-tick-region-allocation.md`](../../docs/adr/ADR-123-folia-tick-region-allocation.md)
- ADR-124: [`../../docs/adr/ADR-124-consumer-attribution-for-background-refill.md`](../../docs/adr/ADR-124-consumer-attribution-for-background-refill.md)
