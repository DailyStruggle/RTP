package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Platform-neutral fan-out registry for {@code /rtp} command outcome events.
 * Platforms register success/fail callbacks to bridge into platform-specific event buses.
 */
public final class RTPCommandEvents {

  private static final CopyOnWriteArrayList<BiConsumer<RTPCommandSender, RTPPlayer>> SUCCESS =
      new CopyOnWriteArrayList<>();
  private static final CopyOnWriteArrayList<BiConsumer<RTPCommandSender, String>> FAIL =
      new CopyOnWriteArrayList<>();

  private RTPCommandEvents() {}

  /**
   * Register a callback invoked on a successful {@code /rtp} command teleport.
   *
   * @param handler receives the command sender and the teleported player; must not be {@code null}
   * @return an {@link AutoCloseable} that unregisters the handler when closed
   */
  public static AutoCloseable onSuccess(BiConsumer<RTPCommandSender, RTPPlayer> handler) {
    if (handler == null) throw new IllegalArgumentException("handler must not be null");
    SUCCESS.add(handler);
    return () -> SUCCESS.remove(handler);
  }

  /**
   * Register a callback invoked on a failed {@code /rtp} command teleport.
   *
   * @param handler receives the command sender and the failure message; must not be {@code null}
   * @return an {@link AutoCloseable} that unregisters the handler when closed
   */
  public static AutoCloseable onFail(BiConsumer<RTPCommandSender, String> handler) {
    if (handler == null) throw new IllegalArgumentException("handler must not be null");
    FAIL.add(handler);
    return () -> FAIL.remove(handler);
  }

  /** Fan out a success event to every registered subscriber. */
  public static void fireSuccess(RTPCommandSender sender, RTPPlayer player) {
    for (BiConsumer<RTPCommandSender, RTPPlayer> h : SUCCESS) {
      try {
        h.accept(sender, player);
      } catch (Throwable ignored) {
        // intentional: a faulty subscriber must not break fan-out
      }
    }
  }

  /** Fan out a fail event to every registered subscriber. */
  public static void fireFail(RTPCommandSender sender, String msg) {
    for (BiConsumer<RTPCommandSender, String> h : FAIL) {
      try {
        h.accept(sender, msg);
      } catch (Throwable ignored) {
        // intentional: a faulty subscriber must not break fan-out
      }
    }
  }

  /** Remove every registered subscriber (used by tests and on full reload). */
  public static void clear() {
    SUCCESS.clear();
    FAIL.clear();
  }
}
