package io.github.dailystruggle.menu.api;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Platform-neutral SPI for opening an anvil or text-input prompt on a viewer (ADR-045).
 *
 * <p>Implementations must remain asynchronous-safe (S-005), scheduling entity/UI operations onto
 * the owning thread when required by the platform.
 *
 * @governedBy ADR-045
 * @governedBy ADR-115
 * @invariant S-005: Async-safe prompt opening
 */
@FunctionalInterface
public interface AnvilInputOpener {

  /** Sink invoked when a staged anvil prompt confirms a typed value. */
  @FunctionalInterface
  interface StagingSink {
    /**
     * Records a staged {@code paramName=value} entry for {@code viewer} under {@code scopeName}.
     *
     * @param viewer viewer UUID
     * @param scopeName target scope or file identifier
     * @param paramName parameter key
     * @param value typed value
     */
    void stage(UUID viewer, String scopeName, String paramName, String value);
  }

  /**
   * Opens an input prompt for {@code viewer} in {@code RUN} mode.
   *
   * @param viewer target player UUID; must not be {@code null}
   * @param parentPath command path leading to the target node
   * @param paramName parameter name being edited
   * @param prefill initial text populated in the input field
   * @return {@code true} if the prompt was opened, {@code false} if refused
   */
  boolean open(UUID viewer, List<String> parentPath, String paramName, String prefill);

  /**
   * Mode-aware overload supporting both immediate command execution and session staging.
   *
   * @param viewer target player UUID
   * @param parentPath command path or scope path
   * @param paramName parameter name being edited
   * @param prefill initial text populated in the input field
   * @param mode confirmation dispatch mode
   * @param sink staging sink when {@code mode == MenuAction.Mode.STAGE}; may be {@code null}
   * @return {@code true} if the prompt was opened, {@code false} if refused
   */
  default boolean open(
      UUID viewer,
      List<String> parentPath,
      String paramName,
      String prefill,
      MenuAction.Mode mode,
      @Nullable StagingSink sink) {
    return open(viewer, parentPath, paramName, prefill);
  }

  /**
   * Binds the default {@link StagingSink} for {@link MenuAction.Mode#STAGE} confirmations.
   *
   * @param sink staging sink, or {@code null}
   */
  default void setStagingSink(@Nullable StagingSink sink) {
    // Default no-op for RUN-only implementations.
  }
}
