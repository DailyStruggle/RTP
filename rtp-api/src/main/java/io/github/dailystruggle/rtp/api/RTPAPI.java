package io.github.dailystruggle.rtp.api;

import io.github.dailystruggle.metrics.api.FoliaRegionSample;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.api.annotations.PublicApi;
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
 *
 * <p>All static fields are populated by {@code rtp-core} during its {@code onEnable}.
 * Addon plugins must declare {@code RTP} as a hard {@code depend} in their
 * {@code plugin.yml} to guarantee that core delegates are registered before any
 * addon method calls are made.
 *
 * <p><b>Invariant:</b> Once {@code rtp-core} has finished {@code onEnable},
 * {@code serverAccessor} and {@code biomeProvider} are non-null and remain
 * non-null for the lifetime of the server process.
 *
 * <p><b>Two-tier API model:</b> {@code rtp-api} is the thin, stable, publishable
 * <em>contract</em> surface (teleport, hooks, by-world queries). Registering a
 * custom {@code Shape} or vertical adjustor is an <em>implementation-tier</em>
 * extension that requires the concrete, heavyweight base classes; those live in
 * {@code rtp-core} (the platform-independent engine) and are registered through
 * the typed {@code RTP.addShape(Shape)} / {@code RTP.addVerticalAdjustor(VerticalAdjustor)}
 * entry points. An addon author who derives a new shape therefore compiles
 * against {@code rtp-core}, not {@code rtp-api} (REQ-API-F-001/F-002,
 * REQ-API-NF-002).
 *
 * <p><b>Thread safety:</b> All delegate fields are {@code volatile} so that the
 * single write performed by the main thread during {@code onEnable} is
 * immediately visible to every thread that reads them afterwards, without
 * requiring explicit synchronisation at each call site.
 * {@link #setServerAccessor(RTPServerAccessor)} is the preferred write path for
 * production code; it enforces the write-once contract and prevents a buggy
 * addon from silently replacing the accessor with an incompatible implementation.
 */
@PublicApi
public class RTPAPI {
  /** The platform-specific server accessor. Volatile for cross-thread visibility. */
  public static volatile RTPServerAccessor serverAccessor;
  /**
   * The UUID used to identify this server instance in multi-server or proxy
   * deployments. Defaults to {@code new UUID(0, 0)} (all-zeros) and may be
   * overwritten by the core during startup when a server-id is configured.
   */
  public static volatile UUID serverId = new UUID(0, 0);


  // Functional delegates mapped by the Core module. Volatile for cross-thread visibility.
  /**
   * Delegate that resolves the set of biome names available in a given world.
   * Populated by {@code rtp-core} during {@code onEnable}; {@code null} until then.
   * Use {@link #getBiomes(RTPWorld)} rather than calling this field directly.
   */
  public static volatile Function<RTPWorld, Set<String>> biomeProvider = null;
  /**
   * Singleton facade for behavior-modification hooks (claim verifiers, economy,
   * placeholders, world border, anvil pre-filter). Populated by {@code rtp-core}
   * during {@code onEnable}; {@code null} until then. Use {@link #hooks()} rather
   * than reading this field directly so missing initialisation is loud
   * (REQ-RTP-S-006). See ADR-026 and {@code docs/dev/EXTERNAL_HOOKS.md}.
   */
  public static volatile RTPHooks hooks = null;

  /**
   * Delegate that triggers a teleport for an online player and completes the
   * returned future with the outcome. Populated by {@code rtp-core} during
   * {@code onEnable}; {@code null} until then. Use
   * {@link #teleport(UUID, RtpTarget)} rather than calling this field directly.
   */
  public static volatile BiFunction<UUID, RtpTarget, CompletableFuture<RTPResult>> teleportDelegate =
      null;
  /**
   * Delegate that cancels a pending teleport for a player, returning {@code true}
   * if a request was actually cancelled. Populated by {@code rtp-core} during
   * {@code onEnable}; {@code null} until then. Use {@link #cancel(UUID)} rather
   * than calling this field directly.
   */
  public static volatile Predicate<UUID> cancelDelegate = null;
  /**
   * Delegate that reports the combined ready-location queue depth for a world.
   * Populated by {@code rtp-core} during {@code onEnable}; {@code null} until then.
   * Use {@link #queueDepth(RTPWorld)} rather than calling this field directly.
   */
  public static volatile ToIntFunction<RTPWorld<?>> queueDepthDelegate = null;
  /**
   * Delegate that reports whether a player currently has an in-flight (warming-up)
   * teleport. Populated by {@code rtp-core} during {@code onEnable}; {@code null}
   * until then. Use {@link #isWarmingUp(UUID)} rather than calling this field
   * directly.
   */
  public static volatile Predicate<UUID> warmupDelegate = null;

  /**
   * Delegate that lists the {@link RtpTarget}s a player is permitted to use,
   * with permission gates already applied. Populated by {@code rtp-core} during
   * {@code onEnable}; {@code null} until then. Use
   * {@link #getAllowedTargets(UUID)} rather than calling this field directly.
   */
  public static volatile Function<UUID, List<RtpTarget>> allowedTargetsDelegate = null;
  /**
   * Delegate that reports the per-player {@link RtpTargetStatus} for a target
   * (cooldown, cost, availability). Populated by {@code rtp-core} during
   * {@code onEnable}; {@code null} until then. Use
   * {@link #getTargetStatus(UUID, RtpTarget)} rather than calling this field
   * directly.
   */
  public static volatile BiFunction<UUID, RtpTarget, RtpTargetStatus> targetStatusDelegate = null;
  /**
   * Delegate that supplies the latest sampled runtime-health
   * {@link MetricsSnapshot}. Populated by {@code rtp-core} during
   * {@code onEnable}; {@code null} until then. Use {@link #getMetricsSnapshot()}
   * rather than calling this field directly.
   */
  public static volatile Supplier<MetricsSnapshot> metricsSnapshotDelegate = null;

  /**
   * Eagerly-created dispatcher for {@link PrefabAppliedEvent} notifications.
   * Unlike the core-populated delegates above, this is always available so
   * addons may register a subscriber at any point in their lifecycle, including
   * before {@code rtp-core} has finished loading. {@code rtp-core} fires events
   * through it after a prefab is applied. Use {@link #onPrefabApplied} to
   * register rather than reading this field directly.
   */
  public static final PrefabEventDispatcher prefabEvents = new PrefabEventDispatcher();


  /**
   * Sets the platform-specific server accessor.
   *
   * <p>This method enforces a write-once contract: calling it a second time with a
   * <em>different</em> accessor instance throws {@link IllegalStateException}, preventing
   * a buggy addon from silently replacing the accessor after core initialisation.
   * Calling it again with the <em>same</em> instance is a no-op (idempotent).
   *
   * <p><b>Usage:</b> Call exactly once from {@code rtp-core} during {@code onEnable}.
   * Test harnesses may reset {@code serverAccessor} directly via the public field, then
   * call this method — or use the public field directly throughout.
   *
   * @param accessor the non-null platform accessor to register
   * @throws IllegalArgumentException if {@code accessor} is {@code null}
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

  /**
   * Biome names available in {@code world}. Returns {@code null} if the core biome
   * provider has not been registered yet (callers MUST null-check). Thread-safe once
   * {@code rtp-core} is loaded.
   */
  @PublicApi
  public static Set<String> getBiomes(RTPWorld world) {
    if (biomeProvider != null) {
      return biomeProvider.apply(world);
    }
    return null;
  }

  /**
   * Returns the behavior-modification hook facade.
   *
   * <p>Use this from third-party plugins to register claim verifiers, an
   * economy provider, PAPI-style placeholder resolvers, a world-border
   * integration, or an anvil pre-filter SPI without depending on
   * {@code rtp-core} internals (ADR-026; see {@code docs/dev/EXTERNAL_HOOKS.md}).
   *
   * <p><b>Thread safety:</b> Safe to call from any thread once {@code rtp-core}
   * has completed {@code onEnable}. The returned facade is itself thread-safe.
   *
   * @return the non-null hooks facade
   * @throws IllegalStateException if called before core delegates are registered
   *     (REQ-RTP-S-006)
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
   * Registers a subscriber to be notified after an admin-panel prefab has been
   * applied (written to disk and the affected configs reloaded).
   *
   * <p>This is a fire-and-forget, post-apply notification: the subscriber
   * cannot veto or mutate the apply, which has already completed by the time
   * the {@link PrefabAppliedEvent} is delivered. Use it to react from an addon -
   * e.g. to refresh cached config, rebuild a menu, or emit your own audit line.
   *
   * <p>Unlike most {@code RTPAPI} entry points, this is safe to call before
   * {@code rtp-core} has loaded; the dispatcher is created eagerly so an addon
   * may subscribe during its own {@code onEnable}.
   *
   * <p><b>Threading:</b> the subscriber is invoked on whatever thread executes
   * the prefab confirm command. A subscriber that touches the world must
   * re-schedule onto the RTP scheduler itself. Exceptions thrown by a
   * subscriber are isolated so a single faulty addon cannot break delivery to
   * the others.
   *
   * @param subscriber the consumer to notify; must not be {@code null}.
   * @return an {@link AutoCloseable} that unregisters the subscriber when closed.
   * @throws IllegalArgumentException if {@code subscriber} is {@code null}.
   */
  @PublicApi
  public static AutoCloseable onPrefabApplied(java.util.function.Consumer<PrefabAppliedEvent> subscriber) {
    return prefabEvents.subscribe(subscriber);
  }

  /**
   * Triggers a random teleport for an online player and reports the outcome.
   *
   * <p>This is the addon-facing equivalent of a player running {@code /rtp}: it
   * resolves {@code target} to a region, runs the full safety pipeline off the
   * main thread, and moves the player when a safe destination is found. The
   * returned future always completes - with a success {@link RTPResult} on a
   * successful teleport, or a failure {@code RTPResult} otherwise (REQ-RTP-S-004);
   * it is never left to silently hang on a no-op.
   *
   * <p>Safe to call from any thread once {@code rtp-core} has loaded. The future
   * may complete on an internal RTP thread; use {@code thenAcceptAsync} with your
   * platform executor if you need main-thread continuation.
   *
   * @param player the UUID of the online player to teleport; must not be {@code null}
   * @param target where to send the player; use {@link RtpTarget#defaultRegion()}
   *     for the default behaviour. Must not be {@code null}.
   * @return a future that completes with the teleport outcome
   * @throws IllegalStateException    if {@code rtp-core} has not loaded yet
   *     (REQ-RTP-S-006)
   * @throws IllegalArgumentException if {@code player} or {@code target} is {@code null}
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
   * Cancels a pending or in-flight teleport for a player, if any.
   *
   * @param player the player UUID; must not be {@code null}
   * @return {@code true} if a teleport request was found and cancelled,
   *     {@code false} if the player had no pending teleport
   * @throws IllegalStateException    if {@code rtp-core} has not loaded yet
   *     (REQ-RTP-S-006)
   * @throws IllegalArgumentException if {@code player} is {@code null}
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
   * Returns the number of pre-verified, ready-to-serve locations currently queued
   * for {@code world}'s teleport region.
   *
   * @param world the world to query; must not be {@code null}
   * @return the queue depth ({@code >= 0})
   * @throws IllegalStateException    if {@code rtp-core} has not loaded yet
   *     (REQ-RTP-S-006)
   * @throws IllegalArgumentException if {@code world} is {@code null}
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
   * Returns whether {@code player} currently has an in-flight (warming-up)
   * teleport that has been requested but not yet completed.
   *
   * @param player the player UUID; must not be {@code null}
   * @return {@code true} if a teleport is in progress for the player
   * @throws IllegalStateException    if {@code rtp-core} has not loaded yet
   *     (REQ-RTP-S-006)
   * @throws IllegalArgumentException if {@code player} is {@code null}
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
   * Returns the {@link RtpTarget}s {@code player} is permitted to teleport to,
   * with the same permission gates the {@code /rtp} command applies already
   * resolved.
   *
   * <p>Intended for populating a destination picker in a third-party GUI: every
   * returned target is one the player may actually use, so a GUI can render the
   * list verbatim. The result always contains {@link RtpTarget#defaultRegion()}
   * as its first element (a bare {@code /rtp} is always offered), followed by any
   * named regions the player passes the permission check for.
   *
   * <p>This is a read-only query; it never triggers a teleport. Submitting one of
   * the returned targets to {@link #teleport(UUID, RtpTarget)} re-enforces every
   * safety and permission check regardless of this list.
   *
   * @param player the player UUID; must not be {@code null}
   * @return an immutable, non-null list of permitted targets
   * @throws IllegalStateException    if {@code rtp-core} has not loaded yet
   *     (REQ-RTP-S-006)
   * @throws IllegalArgumentException if {@code player} is {@code null}
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
   * Returns the per-player {@link RtpTargetStatus} for {@code target}: remaining
   * cooldown, monetary cost, and an availability verdict.
   *
   * <p>Intended for decorating a destination button in a third-party GUI (greyed
   * out on cooldown, a price tag, a lock when the player lacks permission). The
   * values are a point-in-time read and are never refreshed after the call.
   *
   * <p>This is a read-only query; it never triggers a teleport or charges the
   * player. The authoritative checks still run inside
   * {@link #teleport(UUID, RtpTarget)}.
   *
   * @param player the player UUID; must not be {@code null}
   * @param target the target to describe; must not be {@code null}
   * @return a non-null status snapshot
   * @throws IllegalStateException    if {@code rtp-core} has not loaded yet
   *     (REQ-RTP-S-006)
   * @throws IllegalArgumentException if {@code player} or {@code target} is {@code null}
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
   * Returns the latest sampled runtime-health {@link MetricsSnapshot} (TPS, MSPT,
   * tick-budget utilisation, player count, heap, and per-region detail).
   *
   * <p>Intended for dashboard tiles in a third-party GUI. The snapshot is
   * produced by the active metrics binding's sampler, not computed on the
   * caller's thread, so reading it introduces no main-thread cost and no chunk
   * I/O. Unsampled fields are reported as {@link MetricsSnapshot#UNSAMPLED}.
   *
   * @return a non-null snapshot
   * @throws IllegalStateException if {@code rtp-core} has not loaded yet
   *     (REQ-RTP-S-006)
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
   * Returns the per-region runtime samples from the latest
   * {@link #getMetricsSnapshot()}.
   *
   * <p>On Folia (and any future multi-region runtime) this is one
   * {@link FoliaRegionSample} per region; on single-region runtimes it is empty.
   * Convenience equivalent of {@code getMetricsSnapshot().foliaRegions}.
   *
   * @return an immutable, non-null list of per-region samples (possibly empty)
   * @throws IllegalStateException if {@code rtp-core} has not loaded yet
   *     (REQ-RTP-S-006)
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
