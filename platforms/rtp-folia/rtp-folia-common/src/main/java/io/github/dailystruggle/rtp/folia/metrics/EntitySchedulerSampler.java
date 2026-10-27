package io.github.dailystruggle.rtp.folia.metrics;

import io.github.dailystruggle.rtp.common.bit.PlatformHookBitComponent;
import io.github.dailystruggle.rtp.common.governor.ComputeBudget;
import io.github.dailystruggle.rtp.common.governor.ComputeGovernor;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Folia entity-scheduler sampler measuring per-tick-region player population {@code P_k} and
 * consecutive-tick interval overruns/breaches using public Folia APIs only (ADR-123 Section 5 & 11).
 *
 * <p>Every 2 s round samples at most {@value #MAX_PLAYERS_PER_ROUND} online players in rotating order
 * ({@code ceil(N_online / 64)} rounds per rotation), running a 1-tick periodic task on each sampled
 * player's {@code Entity#getScheduler()} for {@value #RUNS_PER_PLAYER} runs.
 *
 * @governedBy ADR-123 (Folia Tick-Region Allocation: Work Placement, Probed Shares, Steering and Lending)
 */
public final class EntitySchedulerSampler {

    public static final int MAX_PLAYERS_PER_ROUND = 64;
    public static final int RUNS_PER_PLAYER = 10;
    public static final long ROUND_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(2);
    public static final long OVERRUN_GAP_NANOS = TimeUnit.MILLISECONDS.toNanos(60);
    public static final long SEVERE_BREACH_GAP_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    public static final int OVERRUNS_FOR_BREACH = 2;
    public static final int CELL_SHIFT = 3;

    /** Cell coordinate key for active RTP cells. */
    public record CellCoord(UUID worldId, World world, int cx, int cz) {
        public int sx() { return cx >> CELL_SHIFT; }
        public int sz() { return cz >> CELL_SHIFT; }
    }

    /** Per-cell sampled population and breach state. */
    public record CellSample(
            int population,
            boolean breached,
            long maxGapNanos,
            int overrunsInWindow,
            long sampledAtNanos,
            boolean stale
    ) {}

    /** Cancellable handle for a scheduled 1-tick player entity task. */
    @FunctionalInterface
    public interface TaskHandle {
        void cancel();
    }

    /** Seam for scheduling a 1-tick repeating task on a player's entity scheduler. */
    @FunctionalInterface
    public interface PlayerTaskScheduler {
        TaskHandle scheduleAtFixedRate(UUID playerId, Runnable runCallback, Runnable retiredCallback);
    }

    /** Seam for testing whether a chunk belongs to the calling thread's tick region. */
    @FunctionalInterface
    public interface OwnershipChecker {
        boolean isOwnedByCurrentRegion(UUID worldId, World world, int cx, int cz);
    }

    private static final class ActivePlayerProbe {
        final UUID playerId;
        final AtomicBoolean finished = new AtomicBoolean(false);
        final Set<CellKey> matchedCells = new HashSet<>();
        int runCount = 0;
        long lastRunNanos = Long.MIN_VALUE;
        int overruns = 0;
        long maxGapNanos = 0L;
        boolean breached = false;
        volatile TaskHandle handle;

        ActivePlayerProbe(UUID playerId) {
            this.playerId = playerId;
        }

        void cancelAndClear() {
            if (finished.compareAndSet(false, true)) {
                matchedCells.clear();
                TaskHandle h = handle;
                handle = null;
                if (h != null) {
                    try {
                        h.cancel();
                    } catch (Throwable e) {
                        PlatformHookBitComponent.recordStateSamplerDegraded(
                                "EntitySchedulerSampler#cancelAndClear", e);
                    }
                }
            }
        }
    }

    private record CellKey(UUID worldId, int sx, int sz) {}

    private final LongSupplier clock;
    private final BooleanSupplier enabledSupplier;
    private final Supplier<List<UUID>> onlinePlayersSupplier;
    private final Supplier<Collection<CellCoord>> activeCellsSupplier;
    private final OwnershipChecker ownershipChecker;
    private final PlayerTaskScheduler playerTaskScheduler;

    private final ConcurrentHashMap<UUID, ActivePlayerProbe> activeProbes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CellKey, AtomicInteger> rotationCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CellKey, CellSample> publishedSamples = new ConcurrentHashMap<>();
    private final AtomicLong overrunsTotal = new AtomicLong(0L);
    private final AtomicLong breachesTotal = new AtomicLong(0L);
    private int rotationCursor = 0;
    private int roundsInCurrentRotation = 0;
    private int expectedRoundsInRotation = 1;
    private volatile long lastCompletedRotationNanos = Long.MIN_VALUE;

    /**
     * Production constructor wired to Bukkit's online player list, {@code Entity#getScheduler()},
     * and {@code Bukkit.isOwnedByCurrentRegion}.
     */
    public EntitySchedulerSampler(Plugin plugin, LongSupplier clock, Supplier<Collection<CellCoord>> activeCellsSupplier) {
        this(
                clock,
                EntitySchedulerSampler::defaultEnabled,
                () -> {
                    List<UUID> ids = new ArrayList<>();
                    try {
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (p != null && p.isOnline()) {
                                ids.add(p.getUniqueId());
                            }
                        }
                    } catch (Throwable e) {
                        PlatformHookBitComponent.recordStateSamplerDegraded(
                                "EntitySchedulerSampler#getOnlinePlayers", e);
                    }
                    return ids;
                },
                activeCellsSupplier,
                (worldId, world, cx, cz) -> world != null && Bukkit.isOwnedByCurrentRegion(world, cx, cz),
                (playerId, runCallback, retiredCallback) -> {
                    if (plugin == null || !plugin.isEnabled()) return null;
                    Player player = Bukkit.getPlayer(playerId);
                    if (player == null || !player.isOnline()) return null;
                    ScheduledTask st = player.getScheduler().runAtFixedRate(
                            plugin,
                            task -> runCallback.run(),
                            retiredCallback,
                            1L,
                            1L
                    );
                    return (st != null) ? st::cancel : null;
                }
        );
    }

    /**
     * Deterministic constructor for unit tests with injected clock, player list, active cells,
     * ownership predicate, and entity scheduler.
     */
    public EntitySchedulerSampler(
            LongSupplier clock,
            BooleanSupplier enabledSupplier,
            Supplier<List<UUID>> onlinePlayersSupplier,
            Supplier<Collection<CellCoord>> activeCellsSupplier,
            OwnershipChecker ownershipChecker,
            PlayerTaskScheduler playerTaskScheduler
    ) {
        this.clock = clock;
        this.enabledSupplier = enabledSupplier;
        this.onlinePlayersSupplier = onlinePlayersSupplier;
        this.activeCellsSupplier = activeCellsSupplier;
        this.ownershipChecker = ownershipChecker;
        this.playerTaskScheduler = playerTaskScheduler;
    }

    static boolean defaultEnabled() {
        ComputeBudget b = ComputeGovernor.currentBudget();
        return (b != null && (b.autoTune() || b.observeOnly())) || ComputeGovernor.isObservePropertyEnabled();
    }

    /**
     * Executes one 2-second sampling round, selecting at most {@value #MAX_PLAYERS_PER_ROUND}
     * online players in rotating order (ADR-123 Section 5).
     *
     * @return number of player sampler tasks started in this round
     */
    public int runRound() {
        if (!enabledSupplier.getAsBoolean()) {
            cancelAllActiveProbes();
            return 0;
        }

        List<UUID> online = onlinePlayersSupplier.get();
        if (online == null || online.isEmpty()) {
            completeRotationAndPublish(clock.getAsLong(), Collections.emptyList());
            rotationCursor = 0;
            roundsInCurrentRotation = 0;
            return 0;
        }

        int totalOnline = online.size();
        if (rotationCursor >= totalOnline || roundsInCurrentRotation == 0) {
            rotationCursor = 0;
            roundsInCurrentRotation = 0;
            expectedRoundsInRotation = Math.max(1, (totalOnline + MAX_PLAYERS_PER_ROUND - 1) / MAX_PLAYERS_PER_ROUND);
        }

        int countThisRound = Math.min(MAX_PLAYERS_PER_ROUND, totalOnline - rotationCursor);
        int started = 0;
        for (int i = 0; i < countThisRound; i++) {
            UUID playerId = online.get(rotationCursor + i);
            if (playerId == null) continue;
            if (startProbeForPlayer(playerId)) {
                started++;
            }
        }

        rotationCursor += countThisRound;
        roundsInCurrentRotation++;
        if (rotationCursor >= totalOnline) {
            rotationCursor = 0;
            roundsInCurrentRotation = 0;
            if (activeProbes.isEmpty()) {
                completeRotationAndPublish(clock.getAsLong(), activeCellsSupplier.get());
            }
        }
        return started;
    }

    private boolean startProbeForPlayer(UUID playerId) {
        ActivePlayerProbe existing = activeProbes.remove(playerId);
        if (existing != null) {
            existing.cancelAndClear();
        }
        ActivePlayerProbe probe = new ActivePlayerProbe(playerId);
        activeProbes.put(playerId, probe);
        TaskHandle handle = playerTaskScheduler.scheduleAtFixedRate(
                playerId,
                () -> onProbeTick(probe),
                () -> onProbeRetired(probe)
        );
        if (handle == null) {
            activeProbes.remove(playerId, probe);
            probe.cancelAndClear();
            if (activeProbes.isEmpty() && rotationCursor == 0) {
                completeRotationAndPublish(clock.getAsLong(), activeCellsSupplier.get());
            }
            return false;
        }
        probe.handle = handle;
        if (probe.finished.get()) {
            handle.cancel();
        }
        return true;
    }

    private void onProbeTick(ActivePlayerProbe probe) {
        if (probe.finished.get()) {
            return;
        }
        long now = clock.getAsLong();
        probe.runCount++;

        if (probe.runCount == 1) {
            probe.lastRunNanos = now;
            Collection<CellCoord> activeCells = activeCellsSupplier.get();
            if (activeCells != null) {
                for (CellCoord cell : activeCells) {
                    if (cell == null || cell.worldId() == null) continue;
                    if (ownershipChecker.isOwnedByCurrentRegion(cell.worldId(), cell.world(), cell.cx(), cell.cz())) {
                        CellKey key = new CellKey(cell.worldId(), cell.sx(), cell.sz());
                        if (probe.matchedCells.add(key)) {
                            rotationCounts.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
                        }
                    }
                }
            }
        } else {
            long gap = Math.max(0L, now - probe.lastRunNanos);
            probe.lastRunNanos = now;
            if (gap > probe.maxGapNanos) {
                probe.maxGapNanos = gap;
            }
            if (gap > OVERRUN_GAP_NANOS) {
                probe.overruns++;
                overrunsTotal.incrementAndGet();
            }
            if ((gap > SEVERE_BREACH_GAP_NANOS || probe.overruns >= OVERRUNS_FOR_BREACH)
                    && !probe.breached) {
                probe.breached = true;
                breachesTotal.incrementAndGet();
            }
        }

        if (probe.runCount >= RUNS_PER_PLAYER) {
            finishCompletedProbe(probe, now);
        }
    }

    private void finishCompletedProbe(ActivePlayerProbe probe, long nowNanos) {
        Set<CellKey> cells = new HashSet<>(probe.matchedCells);
        boolean breached = probe.breached;
        long maxGap = probe.maxGapNanos;
        int overruns = probe.overruns;

        activeProbes.remove(probe.playerId, probe);
        probe.cancelAndClear();

        for (CellKey key : cells) {
            AtomicInteger rotPop = rotationCounts.get(key);
            int pop = (rotPop != null) ? rotPop.get() : 1;
            CellSample prev = publishedSamples.get(key);
            int finalPop = (prev != null && pop == 0) ? prev.population() : pop;
            boolean finalBreach = breached || (prev != null && (nowNanos - prev.sampledAtNanos()) < ROUND_INTERVAL_NANOS && prev.breached());
            publishedSamples.put(key, new CellSample(
                    finalPop,
                    finalBreach,
                    Math.max(maxGap, prev != null ? prev.maxGapNanos() : 0L),
                    Math.max(overruns, prev != null ? prev.overrunsInWindow() : 0),
                    nowNanos,
                    false
            ));
        }

        if (activeProbes.isEmpty() && rotationCursor == 0) {
            completeRotationAndPublish(nowNanos, activeCellsSupplier.get());
        }
    }

    private void completeRotationAndPublish(long nowNanos, Collection<CellCoord> activeCells) {
        lastCompletedRotationNanos = nowNanos;
        Set<CellKey> touched = new HashSet<>();
        if (activeCells != null) {
            for (CellCoord c : activeCells) {
                if (c == null || c.worldId() == null) continue;
                CellKey key = new CellKey(c.worldId(), c.sx(), c.sz());
                touched.add(key);
                AtomicInteger cnt = rotationCounts.remove(key);
                int pop = (cnt != null) ? cnt.get() : 0;
                CellSample prev = publishedSamples.get(key);
                publishedSamples.put(key, new CellSample(
                        pop,
                        prev != null && (nowNanos - prev.sampledAtNanos()) < ROUND_INTERVAL_NANOS && prev.breached(),
                        prev != null ? prev.maxGapNanos() : 0L,
                        prev != null ? prev.overrunsInWindow() : 0,
                        nowNanos,
                        false
                ));
            }
        }
        for (Map.Entry<CellKey, AtomicInteger> e : rotationCounts.entrySet()) {
            CellKey key = e.getKey();
            if (touched.contains(key)) continue;
            int pop = e.getValue().get();
            CellSample prev = publishedSamples.get(key);
            publishedSamples.put(key, new CellSample(
                    pop,
                    prev != null && prev.breached(),
                    prev != null ? prev.maxGapNanos() : 0L,
                    prev != null ? prev.overrunsInWindow() : 0,
                    nowNanos,
                    false
            ));
        }
        rotationCounts.clear();
    }

    private void onProbeRetired(ActivePlayerProbe probe) {
        activeProbes.remove(probe.playerId, probe);
        probe.cancelAndClear();
        if (activeProbes.isEmpty() && rotationCursor == 0) {
            completeRotationAndPublish(clock.getAsLong(), activeCellsSupplier.get());
        }
    }

    /**
     * Returns the sampled population {@code P_k} for the cell containing {@code (cx, cz)} in {@code worldId},
     * or {@code -1} ({@code NEVER_SAMPLED}) if never sampled or stale after more than one missed round
     * (ADR-123 Section 5).
     */
    public int sampledPopulation(UUID worldId, int cx, int cz) {
        CellSample sample = sampleForCell(worldId, cx, cz);
        if (sample == null || sample.stale()) {
            return -1;
        }
        return sample.population();
    }

    /**
     * Returns {@code true} if the cell containing {@code (cx, cz)} in {@code worldId} experienced a
     * consecutive-tick breach in the last sampling round (ADR-123 Section 5).
     */
    public boolean isCellBreached(UUID worldId, int cx, int cz) {
        CellSample sample = sampleForCell(worldId, cx, cz);
        return sample != null && !sample.stale() && sample.breached();
    }

    /**
     * Returns the {@link CellSample} for {@code (worldId, cx, cz)}, marking it {@code stale} when more
     * than two rounds ({@code 2 * max(1, expectedRoundsInRotation) * ROUND_INTERVAL_NANOS}) have elapsed
     * since it was sampled (ADR-123 Section 5: "A missed sample keeps the previous reading for one round,
     * then becomes STALE").
     */
    public CellSample sampleForCell(UUID worldId, int cx, int cz) {
        if (worldId == null) return null;
        CellKey key = new CellKey(worldId, cx >> CELL_SHIFT, cz >> CELL_SHIFT);
        CellSample sample = publishedSamples.get(key);
        if (sample == null) return null;
        long now = clock.getAsLong();
        long staleThreshold = 2L * Math.max(1, expectedRoundsInRotation) * ROUND_INTERVAL_NANOS;
        if (now >= sample.sampledAtNanos() && (now - sample.sampledAtNanos()) > staleThreshold) {
            return new CellSample(
                    sample.population(),
                    false,
                    sample.maxGapNanos(),
                    sample.overrunsInWindow(),
                    sample.sampledAtNanos(),
                    true
            );
        }
        return sample;
    }

    public int activeProbeCount() {
        return activeProbes.size();
    }

    public long overrunsTotal() {
        return overrunsTotal.get();
    }

    public long breachesTotal() {
        return breachesTotal.get();
    }

    public int expectedRoundsInRotation() {
        return expectedRoundsInRotation;
    }

    public long lastCompletedRotationNanos() {
        return lastCompletedRotationNanos;
    }

    /** Cancels all active player probes and clears state (called on shutdown/disable, ADR-123 Section 11). */
    public void shutdown() {
        cancelAllActiveProbes();
        rotationCounts.clear();
        publishedSamples.clear();
        rotationCursor = 0;
        roundsInCurrentRotation = 0;
    }

    private void cancelAllActiveProbes() {
        for (ActivePlayerProbe p : new ArrayList<>(activeProbes.values())) {
            activeProbes.remove(p.playerId, p);
            p.cancelAndClear();
        }
    }
}
