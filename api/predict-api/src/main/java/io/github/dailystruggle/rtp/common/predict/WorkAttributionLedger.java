package io.github.dailystruggle.rtp.common.predict;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Lock-free bounded dual-EWMA work attribution ledger and per-scope distinct active consumer tracker.
 *
 * <p>Tracks distinct active actors per scope within a sliding window and maintains per-actor
 * short/long half-life exponentially weighted moving average (EWMA) compute cost rates in
 * milliseconds per minute using lock-free CAS updates and bounded LRU eviction.
 *
 * <p>Zero external dependencies, zero locks, and zero thread creation.
 *
 * @param <K> actor identifier type (e.g. {@link java.util.UUID} or {@link String})
 * @governedBy ADR-087
 * @governedBy ADR-115
 * @governedBy ADR-124
 */
public final class WorkAttributionLedger<K> {

    public static final int DEFAULT_MAX_ACTOR_ENTRIES = 4096;
    public static final int DEFAULT_MAX_SCOPE_CONSUMERS = 4096;
    public static final long DEFAULT_SCOPE_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(10);
    public static final long DEFAULT_IDLE_EXPIRY_NANOS = TimeUnit.MINUTES.toNanos(30);
    public static final long DEFAULT_SHORT_HALF_LIFE_NANOS = TimeUnit.SECONDS.toNanos(30);
    public static final long DEFAULT_LONG_HALF_LIFE_NANOS = TimeUnit.MINUTES.toNanos(10);

    private static final double LN2 = Math.log(2.0);
    private static final double NANOS_PER_MINUTE = TimeUnit.MINUTES.toNanos(1);

    /**
     * Immutable telemetry snapshot for a single actor's attributed cost in milliseconds per minute.
     *
     * @param actorId           actor identifier
     * @param costMsPerMin      mean of short and long EWMAs ({@code 0.5 * (shortEwma + longEwma)})
     * @param shortEwmaMsPerMin short half-life EWMA in ms/min
     * @param longEwmaMsPerMin  long half-life EWMA in ms/min
     * @param <K>               actor identifier type
     */
    public record AttributionEntry<K>(
            K actorId,
            double costMsPerMin,
            double shortEwmaMsPerMin,
            double longEwmaMsPerMin
    ) {}

    private record ActorState(
            double shortEwmaMsPerMin,
            double longEwmaMsPerMin,
            long lastUpdatedNanos
    ) {}

    private final int maxActorEntries;
    private final int maxScopeConsumers;
    private final long scopeWindowNanos;
    private final long idleExpiryNanos;
    private final long shortHalfLifeNanos;
    private final long longHalfLifeNanos;
    private final double shortRateGain;
    private final double longRateGain;
    private final Comparator<K> comparatorTieBreaker;

    private final ConcurrentHashMap<String, ConcurrentHashMap<K, Long>> scopeConsumers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<K, AtomicReference<ActorState>> actorCosts = new ConcurrentHashMap<>();

    public WorkAttributionLedger() {
        this(
                DEFAULT_MAX_ACTOR_ENTRIES,
                DEFAULT_MAX_SCOPE_CONSUMERS,
                DEFAULT_SCOPE_WINDOW_NANOS,
                DEFAULT_IDLE_EXPIRY_NANOS,
                DEFAULT_SHORT_HALF_LIFE_NANOS,
                DEFAULT_LONG_HALF_LIFE_NANOS,
                Comparator.comparing(String::valueOf)
        );
    }

    public WorkAttributionLedger(Comparator<K> comparatorTieBreaker) {
        this(
                DEFAULT_MAX_ACTOR_ENTRIES,
                DEFAULT_MAX_SCOPE_CONSUMERS,
                DEFAULT_SCOPE_WINDOW_NANOS,
                DEFAULT_IDLE_EXPIRY_NANOS,
                DEFAULT_SHORT_HALF_LIFE_NANOS,
                DEFAULT_LONG_HALF_LIFE_NANOS,
                comparatorTieBreaker
        );
    }

    public WorkAttributionLedger(
            int maxActorEntries,
            int maxScopeConsumers,
            long scopeWindowNanos,
            long idleExpiryNanos,
            long shortHalfLifeNanos,
            long longHalfLifeNanos,
            Comparator<K> comparatorTieBreaker
    ) {
        if (maxActorEntries < 1) {
            throw new IllegalArgumentException("maxActorEntries must be >= 1, got " + maxActorEntries);
        }
        if (maxScopeConsumers < 1) {
            throw new IllegalArgumentException("maxScopeConsumers must be >= 1, got " + maxScopeConsumers);
        }
        if (scopeWindowNanos < 1L) {
            throw new IllegalArgumentException("scopeWindowNanos must be >= 1, got " + scopeWindowNanos);
        }
        if (idleExpiryNanos < 1L) {
            throw new IllegalArgumentException("idleExpiryNanos must be >= 1, got " + idleExpiryNanos);
        }
        if (shortHalfLifeNanos < 1L) {
            throw new IllegalArgumentException("shortHalfLifeNanos must be >= 1, got " + shortHalfLifeNanos);
        }
        if (longHalfLifeNanos < 1L) {
            throw new IllegalArgumentException("longHalfLifeNanos must be >= 1, got " + longHalfLifeNanos);
        }
        this.maxActorEntries = maxActorEntries;
        this.maxScopeConsumers = maxScopeConsumers;
        this.scopeWindowNanos = scopeWindowNanos;
        this.idleExpiryNanos = idleExpiryNanos;
        this.shortHalfLifeNanos = shortHalfLifeNanos;
        this.longHalfLifeNanos = longHalfLifeNanos;
        this.shortRateGain = LN2 / (shortHalfLifeNanos / NANOS_PER_MINUTE);
        this.longRateGain = LN2 / (longHalfLifeNanos / NANOS_PER_MINUTE);
        this.comparatorTieBreaker = comparatorTieBreaker != null
                ? comparatorTieBreaker
                : Comparator.comparing(String::valueOf);
    }

    /**
     * Records that {@code actorId} consumed a unit of work in {@code scope} at {@code nowNanos}
     * and charges {@code unitCostNanos} to {@code actorId}.
     *
     * @param scope         scope/region identifier
     * @param actorId       consuming actor identifier (ignored when null)
     * @param unitCostNanos measured unit cost in nanoseconds
     * @param nowNanos      monotonic timestamp in nanoseconds
     */
    public void recordConsumption(String scope, K actorId, double unitCostNanos, long nowNanos) {
        if (actorId == null) {
            return;
        }
        if (scope != null && !scope.isEmpty()) {
            ConcurrentHashMap<K, Long> consumers = scopeConsumers.computeIfAbsent(
                    scope, k -> new ConcurrentHashMap<>());
            evictExpiredScopeConsumers(consumers, nowNanos);
            if (!consumers.containsKey(actorId) && consumers.size() >= maxScopeConsumers) {
                evictOldestScopeConsumer(consumers);
            }
            consumers.put(actorId, nowNanos);
        }
        long chargeNanos = (!Double.isNaN(unitCostNanos) && !Double.isInfinite(unitCostNanos) && unitCostNanos > 0.0)
                ? Math.round(unitCostNanos)
                : 0L;
        recordCharge(actorId, chargeNanos, nowNanos);
    }

    /**
     * Returns the number of distinct active actors who consumed work in {@code scope}
     * within {@code scopeWindowNanos}, floored at {@code 1} and capped at {@code maxScopeConsumers}.
     *
     * @param scope    scope/region identifier
     * @param nowNanos monotonic timestamp in nanoseconds
     * @return distinct active consumers in {@code [1, maxScopeConsumers]}
     */
    public int distinctConsumers(String scope, long nowNanos) {
        if (scope == null || scope.isEmpty()) {
            return 1;
        }
        ConcurrentHashMap<K, Long> consumers = scopeConsumers.get(scope);
        if (consumers == null || consumers.isEmpty()) {
            return 1;
        }
        evictExpiredScopeConsumers(consumers, nowNanos);
        return Math.max(1, Math.min(maxScopeConsumers, consumers.size()));
    }

    /**
     * Charges {@code costNanos} of work to {@code actorId} at {@code nowNanos}.
     *
     * @param actorId   actor identifier (ignored when null)
     * @param costNanos work duration in nanoseconds
     * @param nowNanos  monotonic timestamp in nanoseconds
     */
    public void recordCharge(K actorId, long costNanos, long nowNanos) {
        if (actorId == null) {
            return;
        }
        double costMs = Math.max(0.0, costNanos / 1_000_000.0);
        AtomicReference<ActorState> slot = actorCosts.get(actorId);
        if (slot == null) {
            evictExpiredActors(nowNanos);
            while (actorCosts.size() >= maxActorEntries && !actorCosts.containsKey(actorId)) {
                if (!evictLeastRecentlyActiveActor(actorId)) {
                    break;
                }
            }
            ActorState initial = newInitialActorState(costMs, nowNanos);
            AtomicReference<ActorState> created = new AtomicReference<>(initial);
            AtomicReference<ActorState> existing = actorCosts.putIfAbsent(actorId, created);
            if (existing == null) {
                enforceCapAfterInsert(actorId, nowNanos);
                return;
            }
            slot = existing;
        }

        while (true) {
            ActorState prev = slot.get();
            ActorState next = computeNextActorState(prev, costMs, nowNanos);
            if (slot.compareAndSet(prev, next)) {
                break;
            }
        }
    }

    private ActorState newInitialActorState(double costMs, long nowNanos) {
        return new ActorState(
                costMs * shortRateGain,
                costMs * longRateGain,
                nowNanos
        );
    }

    private ActorState computeNextActorState(ActorState prev, double costMs, long nowNanos) {
        if (prev == null || (nowNanos >= prev.lastUpdatedNanos()
                && (nowNanos - prev.lastUpdatedNanos()) > idleExpiryNanos)) {
            return newInitialActorState(costMs, nowNanos);
        }
        long dtNanos = Math.max(0L, nowNanos - prev.lastUpdatedNanos());
        double decayShort = (dtNanos == 0L) ? 1.0 : Math.pow(0.5, (double) dtNanos / (double) shortHalfLifeNanos);
        double decayLong = (dtNanos == 0L) ? 1.0 : Math.pow(0.5, (double) dtNanos / (double) longHalfLifeNanos);
        double nextShort = prev.shortEwmaMsPerMin() * decayShort + costMs * shortRateGain;
        double nextLong = prev.longEwmaMsPerMin() * decayLong + costMs * longRateGain;
        long nextTime = Math.max(prev.lastUpdatedNanos(), nowNanos);
        return new ActorState(nextShort, nextLong, nextTime);
    }

    /**
     * Returns the current decayed {@link AttributionEntry} for {@code actorId} at {@code nowNanos},
     * or {@code null} if absent or expired after {@code idleExpiryNanos}.
     */
    public AttributionEntry<K> entry(K actorId, long nowNanos) {
        if (actorId == null) {
            return null;
        }
        AtomicReference<ActorState> slot = actorCosts.get(actorId);
        if (slot == null) {
            return null;
        }
        ActorState state = slot.get();
        if (state == null) {
            return null;
        }
        if (nowNanos >= state.lastUpdatedNanos() && (nowNanos - state.lastUpdatedNanos()) > idleExpiryNanos) {
            actorCosts.remove(actorId, slot);
            return null;
        }
        return toDecayedEntry(actorId, state, nowNanos);
    }

    /**
     * Returns the attributed cost {@code 0.5 * (shortEwma + longEwma)} in ms/min for {@code actorId}
     * at {@code nowNanos}, or {@code 0.0} if absent/expired.
     */
    public double attributedCostMsPerMin(K actorId, long nowNanos) {
        AttributionEntry<K> e = entry(actorId, nowNanos);
        return (e != null) ? e.costMsPerMin() : 0.0;
    }

    /**
     * Returns up to {@code limit} active actors ordered by descending attributed cost at {@code nowNanos}.
     */
    public List<AttributionEntry<K>> topActors(int limit, long nowNanos) {
        return topActors(limit, nowNanos, Function.identity());
    }

    /**
     * Returns up to {@code limit} active actors ordered by descending attributed cost at {@code nowNanos},
     * projected through {@code mapper} in a single pass.
     */
    public <R> List<R> topActors(int limit, long nowNanos, Function<AttributionEntry<K>, R> mapper) {
        if (limit <= 0 || mapper == null) {
            return Collections.emptyList();
        }
        List<AttributionEntry<K>> active = collectActiveEntries(nowNanos);
        if (active.isEmpty()) {
            return Collections.emptyList();
        }
        active.sort(Comparator.comparingDouble(AttributionEntry<K>::costMsPerMin).reversed()
                .thenComparing(AttributionEntry::actorId, comparatorTieBreaker));
        int count = Math.min(limit, active.size());
        List<R> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(mapper.apply(active.get(i)));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Returns the 99th-percentile attributed cost (ms/min) across active actors at {@code nowNanos}.
     */
    public double costP99MsPerMin(long nowNanos) {
        List<AttributionEntry<K>> active = collectActiveEntries(nowNanos);
        if (active.isEmpty()) {
            return 0.0;
        }
        active.sort(Comparator.comparingDouble(AttributionEntry::costMsPerMin));
        long rank = (long) Math.ceil(0.99 * active.size()) - 1L;
        int idx = (int) Math.min(active.size() - 1L, Math.max(0L, rank));
        return active.get(idx).costMsPerMin();
    }

    /**
     * Evicts expired actor entries and returns the current number of active actor entries.
     */
    public int activeActorCount(long nowNanos) {
        evictExpiredActors(nowNanos);
        return actorCosts.size();
    }

    /** Returns the raw map size of tracked actor entries. */
    public int size() {
        return actorCosts.size();
    }

    /**
     * Evicts {@code actorId} from both actor cost tracking and all scope consumer windows,
     * releasing retained state immediately when an actor disconnects.
     *
     * @param actorId actor identifier to evict (ignored when null)
     * @return {@code true} if the actor had state removed from {@code actorCosts} or any scope
     */
    public boolean evictActor(K actorId) {
        if (actorId == null) {
            return false;
        }
        boolean removed = actorCosts.remove(actorId) != null;
        for (ConcurrentHashMap<K, Long> consumers : scopeConsumers.values()) {
            if (consumers.remove(actorId) != null) {
                removed = true;
            }
        }
        return removed;
    }

    /** Clears all scope and actor attribution state. */
    public void reset() {
        scopeConsumers.clear();
        actorCosts.clear();
    }

    public int maxActorEntries() {
        return maxActorEntries;
    }

    public int maxScopeConsumers() {
        return maxScopeConsumers;
    }

    public long scopeWindowNanos() {
        return scopeWindowNanos;
    }

    public long idleExpiryNanos() {
        return idleExpiryNanos;
    }

    public long shortHalfLifeNanos() {
        return shortHalfLifeNanos;
    }

    public long longHalfLifeNanos() {
        return longHalfLifeNanos;
    }

    private List<AttributionEntry<K>> collectActiveEntries(long nowNanos) {
        List<AttributionEntry<K>> list = new ArrayList<>();
        for (Map.Entry<K, AtomicReference<ActorState>> e : actorCosts.entrySet()) {
            K id = e.getKey();
            AtomicReference<ActorState> slot = e.getValue();
            if (id == null || slot == null) continue;
            ActorState state = slot.get();
            if (state == null) continue;
            if (nowNanos >= state.lastUpdatedNanos() && (nowNanos - state.lastUpdatedNanos()) > idleExpiryNanos) {
                actorCosts.remove(id, slot);
                continue;
            }
            list.add(toDecayedEntry(id, state, nowNanos));
        }
        return list;
    }

    private AttributionEntry<K> toDecayedEntry(K actorId, ActorState state, long nowNanos) {
        long dtNanos = Math.max(0L, nowNanos - state.lastUpdatedNanos());
        double decayShort = (dtNanos == 0L) ? 1.0 : Math.pow(0.5, (double) dtNanos / (double) shortHalfLifeNanos);
        double decayLong = (dtNanos == 0L) ? 1.0 : Math.pow(0.5, (double) dtNanos / (double) longHalfLifeNanos);
        double ewmaShort = state.shortEwmaMsPerMin() * decayShort;
        double ewmaLong = state.longEwmaMsPerMin() * decayLong;
        double cost = 0.5 * (ewmaShort + ewmaLong);
        return new AttributionEntry<>(actorId, cost, ewmaShort, ewmaLong);
    }

    private void evictExpiredScopeConsumers(ConcurrentHashMap<K, Long> consumers, long nowNanos) {
        consumers.entrySet().removeIf(e -> {
            Long last = e.getValue();
            return last == null || (nowNanos >= last && (nowNanos - last) > scopeWindowNanos);
        });
    }

    private void evictOldestScopeConsumer(ConcurrentHashMap<K, Long> consumers) {
        K oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<K, Long> e : consumers.entrySet()) {
            Long t = e.getValue();
            if (t != null && t < oldestTime) {
                oldestTime = t;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null) {
            consumers.remove(oldestKey);
        }
    }

    private void evictExpiredActors(long nowNanos) {
        for (Map.Entry<K, AtomicReference<ActorState>> e : actorCosts.entrySet()) {
            AtomicReference<ActorState> slot = e.getValue();
            ActorState st = (slot != null) ? slot.get() : null;
            if (st == null || (nowNanos >= st.lastUpdatedNanos() && (nowNanos - st.lastUpdatedNanos()) > idleExpiryNanos)) {
                actorCosts.remove(e.getKey(), slot);
            }
        }
    }

    private void enforceCapAfterInsert(K protectedActorId, long nowNanos) {
        if (actorCosts.size() <= maxActorEntries) {
            return;
        }
        evictExpiredActors(nowNanos);
        while (actorCosts.size() > maxActorEntries) {
            if (!evictLeastRecentlyActiveActor(protectedActorId)) {
                break;
            }
        }
    }

    private boolean evictLeastRecentlyActiveActor(K protectedActorId) {
        K oldestId = null;
        AtomicReference<ActorState> oldestSlot = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<K, AtomicReference<ActorState>> e : actorCosts.entrySet()) {
            K id = e.getKey();
            if (id == null || id.equals(protectedActorId)) continue;
            AtomicReference<ActorState> slot = e.getValue();
            ActorState st = (slot != null) ? slot.get() : null;
            long t = (st != null) ? st.lastUpdatedNanos() : Long.MIN_VALUE;
            if (oldestId == null || t < oldestTime) {
                oldestTime = t;
                oldestId = id;
                oldestSlot = slot;
            }
        }
        if (oldestId != null) {
            actorCosts.remove(oldestId, oldestSlot);
            return true;
        }
        return false;
    }
}
