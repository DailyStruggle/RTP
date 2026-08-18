package io.github.dailystruggle.rtp.api.addon;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

/**
 * Platform-agnostic lifecycle contract for RTP addons loaded via {@link java.util.ServiceLoader}.
 * {@link #onLoad} is invoked after core delegates are fully initialised.
 * Non-blocking off-thread execution (S-004, S-005).
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
