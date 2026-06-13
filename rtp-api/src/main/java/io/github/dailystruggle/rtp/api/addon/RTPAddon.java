package io.github.dailystruggle.rtp.api.addon;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

/**
 * Platform-agnostic lifecycle contract for RTP addons.
 *
 * <p>An addon implements this interface and declares itself in
 * {@code META-INF/services/io.github.dailystruggle.rtp.api.addon.RTPAddon} so the
 * core can discover it through {@link java.util.ServiceLoader} on every platform
 * (Bukkit/Paper/Folia, Fabric, and - where the API surface the addon uses permits -
 * proxy JVMs), rather than relying on the Bukkit plugin loader.
 *
 * <p>Implementations are instantiated by {@code ServiceLoader} and therefore must
 * declare a public no-argument constructor.
 *
 * <p><b>Lifecycle contract.</b> {@link #onLoad()} is invoked exactly once, after
 * {@code rtp-core} has finished initialising. At that point the
 * {@code RTPAPI} delegates ({@code serverAccessor}, {@code hooks}, the teleport
 * delegate, etc.) are guaranteed to be non-null, so an addon may register config
 * parsers, safety verifiers via {@code RTPAPI.hooks()}, and post-teleport callbacks
 * via the core pipeline runnable lists without a null-check race. {@link #onUnload()}
 * is invoked on server/plugin shutdown so an addon can release tickets, cancel tasks,
 * and flush state.
 *
 * <p><b>Threading / safety.</b> {@code onLoad()} runs on an RTP task thread. It must
 * not block, must not perform synchronous chunk I/O on the main thread (S-005), and
 * must not silently swallow teleport failures from any callback it registers (S-004).
 */
@PublicApi
public interface RTPAddon {

  /**
   * Called once after {@code rtp-core} initialisation completes. The {@code RTPAPI}
   * delegates are guaranteed non-null when this is invoked.
   */
  @PublicApi
  void onLoad();

  /**
   * Called once on shutdown. Release tickets, cancel scheduled tasks, and flush any
   * state allocated in {@link #onLoad()}. The default implementation does nothing.
   */
  @PublicApi
  default void onUnload() {}

  /**
   * Human-readable addon name for logging. Defaults to the simple class name.
   *
   * @return a non-null display name
   */
  @PublicApi
  default String name() {
    return getClass().getSimpleName();
  }
}
