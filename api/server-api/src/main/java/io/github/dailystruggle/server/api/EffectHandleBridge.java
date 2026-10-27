package io.github.dailystruggle.server.api;

/**
 * Platform-neutral bridge for wrapping effect player/location handles and dispatching console
 * effect commands (ADR-116 Decision 2).
 *
 * @governedBy ADR-115
 * @governedBy ADR-116
 */
public interface EffectHandleBridge {
  /**
   * Wraps a platform-specific player object into a platform-neutral effect player handle (ADR-116
   * Decision 2).
   *
   * @param player platform player object or handle
   * @return wrapped player handle, or {@code null} if unsupported or unresolvable
   */
  default Object wrapEffectPlayer(Object player) {
    return null;
  }

  /**
   * Wraps a platform-specific location or entity object into a platform-neutral effect location
   * handle (ADR-116 Decision 2).
   *
   * @param location platform location, entity, or handle
   * @return wrapped location handle, or {@code null} if unsupported or unresolvable
   */
  default Object wrapEffectLocation(Object location) {
    return null;
  }

  /**
   * Resolves the online player standing at the given effect location handle, if any (ADR-116
   * Decision 2).
   *
   * @param location effect location handle or platform location
   * @return wrapped player handle at the location, or {@code null}
   */
  default Object resolveEffectPlayerAt(Object location) {
    return null;
  }

  /**
   * Dispatches a console command on the server runtime (ADR-116 Decision 2).
   *
   * @param command command string to execute
   */
  default void dispatchConsoleCommand(String command) {}
}
