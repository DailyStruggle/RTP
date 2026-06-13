package io.github.dailystruggle.rtp.api.hooks;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Single-binding SPI describing what a bare {@code /rtp} (no arguments) does.
 *
 * <p>By default no action is bound and a bare {@code /rtp} performs the classic
 * random teleport. A third-party addon (e.g. a chest/GUI destination picker) may
 * {@link #bind(Action) bind} an {@link Action} to <i>replace</i> that default - so
 * bare {@code /rtp} opens the addon's menu instead. Calling {@link #clear()}
 * restores the classic teleport.
 *
 * <p>This only governs the <b>bare root</b> invocation. Subcommands
 * ({@code /rtp admin}, {@code /rtp config}, ...) resolve to their own command
 * nodes <i>before</i> the root action is consulted, so binding an action never
 * affects them.
 *
 * <p><b>Reusing the classic behaviour.</b> An action that wants the original
 * teleport for some inputs/player states can either return {@code false} to defer
 * to the built-in teleport, or call
 * {@link io.github.dailystruggle.rtp.api.RTPAPI#teleport(UUID,
 * io.github.dailystruggle.rtp.api.RtpTarget)} itself (which re-applies every
 * safety and permission check) and return {@code true}.
 *
 * <p><b>Threading.</b> {@link Action#run(UUID, Consumer)} is invoked on the same
 * thread the command dispatched on (the server command thread on Bukkit-family
 * platforms). Implementations shall be non-blocking and must not perform
 * synchronous chunk I/O on that thread (REQ-RTP-S-005).
 *
 * <p><b>Failure mode.</b> An action that throws is logged once at WARNING and
 * treated as "not handled", so a bare {@code /rtp} falls back to the classic
 * teleport; the error is never silently swallowed (REQ-RTP-S-004).
 */
@PublicApi
public interface RootActionRegistry {

  /** Functional interface for the bare-{@code /rtp} action binding. */
  @FunctionalInterface
  interface Action {
    /**
     * Handle a bare {@code /rtp} invocation.
     *
     * @param player   the invoking player's UUID (non-null)
     * @param feedback sink for user-facing messages (never blocks)
     * @return {@code true} if the action handled the command (the classic
     *     teleport is suppressed); {@code false} to fall through to the
     *     built-in random teleport
     */
    boolean run(UUID player, Consumer<String> feedback);
  }

  /** Install {@code action} as the bare-{@code /rtp} behaviour (non-null). */
  void bind(Action action);

  /** @return the currently bound action, or {@code null} when the classic teleport is in effect. */
  Action current();

  /** Unbind any action; a bare {@code /rtp} reverts to the classic random teleport. */
  void clear();
}
