package io.github.dailystruggle.rtp.api.hooks;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Single-binding SPI for overriding bare {@code /rtp} (no arguments) behavior.
 *
 * <p>By default, bare {@code /rtp} performs random teleportation. Addons may {@link #bind(Action)}
 * a custom action (e.g. GUI picker) and restore defaults with {@link #clear()}.
 *
 * <p>Subcommands are resolved before this hook and remain unaffected.
 */
@PublicApi
public interface RootActionRegistry {

  /** Functional interface for the bare-{@code /rtp} action binding. */
  @FunctionalInterface
  interface Action {
    /**
     * Handles bare {@code /rtp} invocation.
     *
     * @param player   invoking player UUID (non-null)
     * @param feedback message sink (non-null)
     * @return {@code true} if handled (suppresses default teleport), {@code false} to fall through
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
