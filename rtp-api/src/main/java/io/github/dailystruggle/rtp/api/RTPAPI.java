package io.github.dailystruggle.rtp.api;

import io.github.dailystruggle.rtp.api.hooks.RTPHooks;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
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
  public static RTPHooks hooks() {
    RTPHooks h = hooks;
    if (h == null) {
      throw new IllegalStateException(
          "[RTP API] Cannot access hooks: Core implementation is not loaded.");
    }
    return h;
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
  public static boolean isWarmingUp(UUID player) {
    if (player == null) throw new IllegalArgumentException("[RTP API] player must not be null");
    Predicate<UUID> d = warmupDelegate;
    if (d == null) {
      throw new IllegalStateException(
          "[RTP API] Cannot read warmup state: Core implementation is not loaded.");
    }
    return d.test(player);
  }
}
