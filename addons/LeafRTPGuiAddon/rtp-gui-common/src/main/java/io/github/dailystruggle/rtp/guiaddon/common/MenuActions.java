package io.github.dailystruggle.rtp.guiaddon.common;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.RTPResult;
import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.UUID;

/**
 * Platform-neutral click handling: submits the teleport intent and reports the result.
 *
 * <p>The renderer's click handler calls {@link #submit(UUID, RtpTarget)} with the
 * resolved target; everything from here is engine work. The teleport goes through
 * {@link RTPAPI#teleport}, which enforces permission / cooldown / cost / safety
 * off-thread and always completes with a result (never a silent no-op, per
 * REQ-RTP-S-004). Player messaging is routed through {@code RTP.serverAccessor} so it
 * lands on the right thread on every platform, and the failure path is always
 * surfaced to the player rather than swallowed.
 */
public final class MenuActions {

  private MenuActions() {}

  /**
   * Submits a teleport to {@code target} for {@code playerId} and reports the outcome
   * using the configured messages.
   *
   * @param playerId the player who clicked
   * @param target the destination they selected; if {@code null}, nothing happens
   */
  public static void submit(UUID playerId, RtpTarget target) {
    if (playerId == null || target == null) {
      return;
    }
    GuiMenuConfig config = GuiMenuConfig.INSTANCE;
    message(playerId, config.textSearching());

    RTPAPI.teleport(playerId, target)
        .whenComplete(
            (result, error) -> {
              if (error != null) {
                message(playerId, config.textFailurePrefix() + error.getMessage());
              } else if (result != null && result.isQueued()) {
                // Cross-server enrolment accepted: not a failure - the player
                // will be transferred and teleported on the destination backend.
                message(playerId, config.textQueued());
              } else if (result == null || !result.isSuccess()) {
                String reason = (result == null) ? "unknown" : reason(result);
                message(playerId, config.textFailurePrefix() + reason);
              } else {
                message(playerId, config.textSuccess());
              }
            });
  }

  private static String reason(RTPResult result) {
    String msg = result.message();
    return (msg == null || msg.isEmpty()) ? "unknown" : msg;
  }

  /** Sends a message on the correct platform thread; the callback may be off-thread. */
  private static void message(UUID playerId, String text) {
    if (RTP.serverAccessor == null || RTP.serverAccessor.getPlayer(playerId) == null) {
      return;
    }
    RTP.scheduler.runTask(() -> {
      if (RTP.serverAccessor.getPlayer(playerId) != null) {
        RTP.serverAccessor.sendMessage(playerId, text);
      }
    });
  }
}
