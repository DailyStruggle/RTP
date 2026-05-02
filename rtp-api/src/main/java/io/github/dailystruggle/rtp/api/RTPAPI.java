package io.github.dailystruggle.rtp.api;

import io.github.dailystruggle.rtp.api.hooks.RTPHooks;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Central registry and delegate hub for the RTP public API.
 *
 * <p>All static fields are populated by {@code rtp-core} during its {@code onEnable}.
 * Addon plugins must declare {@code RTP} as a hard {@code depend} in their
 * {@code plugin.yml} to guarantee that core delegates are registered before any
 * addon method calls are made.
 *
 * <p><b>Invariant:</b> Once {@code rtp-core} has finished {@code onEnable},
 * {@code serverAccessor}, {@code shapeAdder}, {@code vertAdder}, and
 * {@code biomeProvider} are all non-null and remain non-null for the lifetime
 * of the server process.
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
   * Delegate that registers a custom {@code Shape} implementation with the core
   * registry (REQ-API-F-001). Populated by {@code rtp-core} during {@code onEnable};
   * {@code null} until then.
   */
  public static volatile Consumer<Object> shapeAdder = null;
  /**
   * Delegate that registers a custom vertical adjustor implementation with the
   * core registry (REQ-API-F-002). Populated by {@code rtp-core} during
   * {@code onEnable}; {@code null} until then.
   */
  public static volatile Consumer<Object> vertAdder = null;
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
   * Registers a custom {@code Shape} implementation with the core registry.
   *
   * <p><b>Preconditions:</b>
   * <ul>
   *   <li>{@code shape} must not be {@code null} and must implement the
   *       platform shape contract expected by {@code rtp-core}.</li>
   *   <li>{@code rtp-core} must have completed its {@code onEnable}.</li>
   * </ul>
   *
   * <p><b>Postconditions:</b> The shape is available for use in region
   * configuration by its registered name.
   *
   * <p><b>Thread safety:</b> Must be called from the main server thread during
   * the addon's {@code onEnable} (REQ-API-F-001).
   *
   * @param shape the shape implementation to register; must not be {@code null}
   * @throws IllegalStateException if called before core delegates are registered
   */
  public static void addShape(Object shape) {
    if (shapeAdder != null) {
      shapeAdder.accept(shape);
    } else {
      throw new IllegalStateException("[RTP API] Cannot add shape: Core implementation is not loaded.");
    }
  }

  /**
   * Registers a custom vertical adjustor implementation with the core registry.
   *
   * <p><b>Preconditions:</b>
   * <ul>
   *   <li>{@code verticalAdjustor} must not be {@code null}.</li>
   *   <li>{@code rtp-core} must have completed its {@code onEnable}.</li>
   * </ul>
   *
   * <p><b>Postconditions:</b>
   * <ul>
   *   <li>The adjustor is available for use in region configuration by its registered name.</li>
   * </ul>
   *
   * <p><b>Throws:</b> {@link IllegalStateException} if called before core delegates are
   * registered.
   *
   * <p><b>Thread safety:</b> Must be called from the main server thread during
   * the addon's {@code onEnable}.
   */
  public static void addVerticalAdjustor(Object verticalAdjustor) {
    if (vertAdder != null) {
      vertAdder.accept(verticalAdjustor);
    } else {
      throw new IllegalStateException("[RTP API] Cannot add vertical adjustor: Core implementation is not loaded.");
    }
  }

  /**
   * Returns the set of biome names available in the given world.
   *
   * <p><b>Preconditions:</b>
   * <ul>
   *   <li>{@code world} must not be {@code null}.</li>
   * </ul>
   *
   * <p><b>Postconditions:</b>
   * <ul>
   *   <li>Returns a non-null {@link Set} of biome name strings if the core biome
   *       provider has been registered.</li>
   *   <li>Returns {@code null} if called before {@code rtp-core} has registered
   *       the biome provider — callers must null-check the return value.</li>
   * </ul>
   *
   * <p><b>Thread safety:</b> Safe to call from any thread once {@code rtp-core}
   * has completed {@code onEnable}.
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
}
