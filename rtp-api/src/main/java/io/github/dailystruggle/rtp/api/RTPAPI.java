package io.github.dailystruggle.rtp.api;

import io.github.dailystruggle.metrics.api.FoliaRegionSample;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import io.github.dailystruggle.rtp.api.event.PlayerMoveDispatcher;
import io.github.dailystruggle.rtp.api.event.PlayerMoveEvent;
import io.github.dailystruggle.rtp.api.event.PrefabAppliedEvent;
import io.github.dailystruggle.rtp.api.event.PrefabEventDispatcher;
import io.github.dailystruggle.rtp.api.hooks.RTPHooks;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Central registry and delegate hub for the RTP public API.
 * Static fields are populated by {@code rtp-core} during {@code onEnable}.
 * Thread-safe: delegate reads are volatile or backed by thread-safe dispatchers.
 */
@PublicApi
public class RTPAPI {
  /** Platform server accessor. Volatile for cross-thread visibility. */
  public static volatile RTPServerAccessor serverAccessor;
  /** Multi-server or proxy instance UUID; default is all-zeros UUID(0, 0). */
  public static volatile UUID serverId = new UUID(0, 0);

  /** Biome resolver delegate populated by core during {@code onEnable}. */
  public static volatile Function<RTPWorld, Set<String>> biomeProvider = null;
  /** Hooks facade (claim verifiers, economy, PAPI, world border, anvil pre-filter; ADR-026). */
  public static volatile RTPHooks hooks = null;

  /** Teleport invocation delegate populated by core during {@code onEnable}. */
  public static volatile BiFunction<UUID, RtpTarget, CompletableFuture<RTPResult>> teleportDelegate =
      null;
  /** Teleport cancel delegate populated by core during {@code onEnable}. */
  public static volatile Predicate<UUID> cancelDelegate = null;
  /** Ready-location queue depth delegate populated by core during {@code onEnable}. */
  public static volatile ToIntFunction<RTPWorld<?>> queueDepthDelegate = null;
  /** Warmup state predicate delegate populated by core during {@code onEnable}. */
  public static volatile Predicate<UUID> warmupDelegate = null;

  /** Allowed targets query delegate populated by core during {@code onEnable}. */
  public static volatile Function<UUID, List<RtpTarget>> allowedTargetsDelegate = null;
  /** Target status snapshot delegate populated by core during {@code onEnable}. */
  public static volatile BiFunction<UUID, RtpTarget, RtpTargetStatus> targetStatusDelegate = null;
  /** Runtime metrics snapshot supplier delegate populated by core during {@code onEnable}. */
  public static volatile Supplier<MetricsSnapshot> metricsSnapshotDelegate = null;

  /** Eager dispatcher for {@link PrefabAppliedEvent} notifications. */
  public static final PrefabEventDispatcher prefabEvents = new PrefabEventDispatcher();

  /** Eager opt-in per-player dispatcher for {@link PlayerMoveEvent} notifications (ADR-075). */
  public static final PlayerMoveDispatcher playerMoveEvents = new PlayerMoveDispatcher();

  /**
   * Sets the platform-specific server accessor (write-once).
   *
   * @param accessor non-null platform accessor to register
   * @throws IllegalArgumentException if {@code accessor} is null
   * @throws IllegalStateException    if a different accessor has already been registered
   */
  @PublicApi
  public static synchronized void setServerAccessor(RTPServerAccessor accessor) {
    if (accessor == null) {
      throw new IllegalArgumentException("[RTP API] serverAccessor must not be null");
    }
    if (serverAccessor != null && serverAccessor != accessor) {
      throw new IllegalStateException(
          "[RTP API] serverAccessor is already initialised with a different instance. "
              + "setServerAccessor() must be called at most once per instance during onEnable. "
              + "If you are an addon developer, do not overwrite RTPAPI.serverAccessor.");
    }
    serverAccessor = accessor;
  }

  /** Biome names available in {@code world}, or {@code null} if core is not loaded. */
  @PublicApi
  public static Set<String> getBiomes(RTPWorld world) {
    if (biomeProvider != null) {
      return biomeProvider.apply(world);
    }
    return null;
  }

  /**
   * Returns the behavior-modification hook facade (ADR-026).
   *
   * @return non-null hooks facade
   * @throws IllegalStateException if called before core is loaded (REQ-RTP-S-006)
   */
  @PublicApi
  public static RTPHooks hooks() {
    RTPHooks h = hooks;
    if (h == null) {
      throw new IllegalStateException(
          "[RTP API] Cannot access hooks: Core implementation is not loaded.");
    }
    return h;
  }

  /**
   * Subscribes to post-apply prefab notifications (safe pre-core init).
   *
   * @param subscriber non-null consumer
   * @return handle that unregisters the subscriber when closed
   * @throws IllegalArgumentException if {@code subscriber} is null
   */
  @PublicApi
  public static AutoCloseable onPrefabApplied(java.util.function.Consumer<PrefabAppliedEvent> subscriber) {
    return prefabEvents.subscribe(subscriber);
  }

  /**
   * Subscribes to block-granularity movement events for {@code player} (ADR-075).
   *
   * @param player  player to watch
   * @param handler non-null consumer
   * @return handle that withdraws interest when closed
   * @throws IllegalArgumentException if {@code player} or {@code handler} is null
   */
  @PublicApi
  public static AutoCloseable watchPlayerMove(
      UUID player, java.util.function.Consumer<PlayerMoveEvent> handler) {
    return playerMoveEvents.watch(player, handler);
  }

  /**
   * Triggers a random teleport for an online player (REQ-RTP-S-004).
   *
   * @param player online player UUID
   * @param target destination target
   * @return future completed with the teleport outcome
   * @throws IllegalStateException    if core is not loaded (REQ-RTP-S-006)
   * @throws IllegalArgumentException if {@code player} or {@code target} is null
   */
  @PublicApi
  public static CompletableFuture<RTPResult> teleport(UUID player, RtpTarget target) {
    if (player == null) throw new IllegalArgumentException("[RTP API] player must not be null");
    if (target == null) throw new IllegalArgumentException("[RTP API] target must not be null");
    BiFunction<UUID, RtpTarget, CompletableFuture<RTPResult>> d = teleportDelegate;
    if (d == null) {
      throw new IllegalStateException(
          "[RTP API] Cannot teleport: Core implementation is not loaded.");
    }
    return d.apply(player, target);
  }

  /**
   * Cancels a pending teleport for {@code player}.
   *
   * @param player player UUID
   * @return {@code true} if a pending request was cancelled
   * @throws IllegalStateException    if core is not loaded (REQ-RTP-S-006)
   * @throws IllegalArgumentException if {@code player} is null
   */
  @PublicApi
  public static boolean cancel(UUID player) {
    if (player == null) throw new IllegalArgumentException("[RTP API] player must not be null");
    Predicate<UUID> d = cancelDelegate;
    if (d == null) {
      throw new IllegalStateException(
          "[RTP API] Cannot cancel: Core implementation is not loaded.");
    }
    return d.test(player);
  }

  /**
   * Returns pre-verified location queue depth for {@code world}.
   *
   * @param world world to query
   * @return queue depth (>= 0)
   * @throws IllegalStateException    if core is not loaded (REQ-RTP-S-006)
   * @throws IllegalArgumentException if {@code world} is null
   */
  @PublicApi
  public static int queueDepth(RTPWorld<?> world) {
    if (world == null) throw new IllegalArgumentException("[RTP API] world must not be null");
    ToIntFunction<RTPWorld<?>> d = queueDepthDelegate;
    if (d == null) {
      throw new IllegalStateException(
          "[RTP API] Cannot read queue depth: Core implementation is not loaded.");
    }
    return d.applyAsInt(world);
  }

  /**
   * Returns whether {@code player} has an in-flight warmup teleport.
   *
   * @param player player UUID
   * @return {@code true} if teleport warmup is in progress
   * @throws IllegalStateException    if core is not loaded (REQ-RTP-S-006)
   * @throws IllegalArgumentException if {@code player} is null
   */
  @PublicApi
  public static boolean isWarmingUp(UUID player) {
    if (player == null) throw new IllegalArgumentException("[RTP API] player must not be null");
    Predicate<UUID> d = warmupDelegate;
    if (d == null) {
      throw new IllegalStateException(
          "[RTP API] Cannot read warmup state: Core implementation is not loaded.");
    }
    return d.test(player);
  }

  /**
   * Returns permission-gated {@link RtpTarget}s available to {@code player}.
   *
   * @param player player UUID
   * @return immutable list of permitted targets
   * @throws IllegalStateException    if core is not loaded (REQ-RTP-S-006)
   * @throws IllegalArgumentException if {@code player} is null
   */
  @PublicApi
  public static List<RtpTarget> getAllowedTargets(UUID player) {
    if (player == null) throw new IllegalArgumentException("[RTP API] player must not be null");
    Function<UUID, List<RtpTarget>> d = allowedTargetsDelegate;
    if (d == null) {
      throw new IllegalStateException(
          "[RTP API] Cannot read allowed targets: Core implementation is not loaded.");
    }
    List<RtpTarget> result = d.apply(player);
    return (result == null) ? Collections.emptyList() : result;
  }

  /**
   * Returns cooldown, cost, and availability status for {@code target}.
   *
   * @param player player UUID
   * @param target destination target
   * @return status snapshot
   * @throws IllegalStateException    if core is not loaded (REQ-RTP-S-006)
   * @throws IllegalArgumentException if {@code player} or {@code target} is null
   */
  @PublicApi
  public static RtpTargetStatus getTargetStatus(UUID player, RtpTarget target) {
    if (player == null) throw new IllegalArgumentException("[RTP API] player must not be null");
    if (target == null) throw new IllegalArgumentException("[RTP API] target must not be null");
    BiFunction<UUID, RtpTarget, RtpTargetStatus> d = targetStatusDelegate;
    if (d == null) {
      throw new IllegalStateException(
          "[RTP API] Cannot read target status: Core implementation is not loaded.");
    }
    RtpTargetStatus status = d.apply(player, target);
    return (status == null)
        ? new RtpTargetStatus(RtpTargetStatus.Availability.UNKNOWN, 0L, 0.0)
        : status;
  }

  /**
   * Returns latest sampled runtime health snapshot (TPS, MSPT, heap, regions).
   *
   * @return non-null snapshot
   * @throws IllegalStateException if core is not loaded (REQ-RTP-S-006)
   */
  @PublicApi
  public static MetricsSnapshot getMetricsSnapshot() {
    Supplier<MetricsSnapshot> d = metricsSnapshotDelegate;
    if (d == null) {
      throw new IllegalStateException(
          "[RTP API] Cannot read metrics: Core implementation is not loaded.");
    }
    return d.get();
  }

  /**
   * Returns per-region runtime samples from latest {@link MetricsSnapshot}.
   *
   * @return non-null list of per-region samples
   * @throws IllegalStateException if core is not loaded (REQ-RTP-S-006)
   */
  @PublicApi
  public static List<FoliaRegionSample> getRegionSamples() {
    MetricsSnapshot snapshot = getMetricsSnapshot();
    if (snapshot == null || snapshot.foliaRegions == null) {
      return Collections.emptyList();
    }
    return snapshot.foliaRegions;
  }
}
