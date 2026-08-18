package io.github.dailystruggle.rtp.common.network;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Producer-side abstraction for RTP trigger events (commands, player joins, external events).
 * Emits {@link Trigger} records to a registered consumer between {@link #start(Consumer)} and {@link #stop()}.
 */
public interface RtpTriggerSource {

  /**
   * Why a trigger fired. Mirrors the three-valued plurality required by
   * REQ-RTP-PROXY-COMMON-005 but is defined here so {@code rtp-core}
   * does not need to import the proxy SPI's {@code TriggerType} enum.
   */
  enum Kind {
    /** Operator or player ran {@code /rtp ...}. */
    COMMAND,
    /** Player joined and join-RTP is enabled. */
    JOIN,
    /** Programmatic trigger from another plugin or backend event. */
    EVENT
  }

  /**
   * Immutable description of one trigger event.
   *
   * @param playerId the player for whom an RTP was requested (never {@code null})
   * @param kind     why the trigger fired (never {@code null})
   * @param regionKey optional region constraint (may be {@code null})
   * @param worldKey  optional world constraint (may be {@code null})
   */
  record Trigger(UUID playerId, Kind kind, String regionKey, String worldKey) {
    public Trigger {
      if (playerId == null) throw new NullPointerException("playerId");
      if (kind == null) throw new NullPointerException("kind");
    }

    /** Convenience for command-driven triggers without region/world constraints. */
    public static Trigger ofCommand(UUID playerId) {
      return new Trigger(playerId, Kind.COMMAND, null, null);
    }

    /** Convenience for join-driven triggers without region/world constraints. */
    public static Trigger ofJoin(UUID playerId) {
      return new Trigger(playerId, Kind.JOIN, null, null);
    }
  }

  /**
   * Begin emitting triggers to {@code consumer}. Calling {@code start} a
   * second time without an intervening {@link #stop()} is an
   * implementation defect; implementations may either ignore the second
   * call or throw {@link IllegalStateException}.
   *
   * @param consumer the sink for emitted triggers (never {@code null})
   */
  void start(Consumer<Trigger> consumer);

  /**
   * Stop emitting triggers. Idempotent: calling {@code stop} on an
   * already-stopped source is a no-op.
   */
  void stop();
}
