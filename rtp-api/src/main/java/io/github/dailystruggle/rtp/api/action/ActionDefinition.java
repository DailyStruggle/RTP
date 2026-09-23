package io.github.dailystruggle.rtp.api.action;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable definition of a scripted action (ADR-093).
 */
@PublicApi
public record ActionDefinition(
    String id,
    String alias,
    String permission,
    String description,
    PlacementSpec placement,
    ConfinementSpec confinement,
    LifecycleSpec lifecycle) {

  public ActionDefinition {
    Objects.requireNonNull(id, "id must not be null");
    placement = (placement == null) ? PlacementSpec.DEFAULT : placement;
    confinement = (confinement == null) ? ConfinementSpec.DEFAULT : confinement;
    lifecycle = (lifecycle == null) ? LifecycleSpec.EMPTY : lifecycle;
  }

  /**
   * Spatial placement profile specifications.
   */
  @PublicApi
  public record PlacementSpec(
      String region,
      String profile,
      int subspaceChunkRadius,
      int minSeparation,
      int elevationTolerance,
      Map<String, Object> parameters) {

    public static final PlacementSpec DEFAULT =
        new PlacementSpec("default", "default", 4, 16, 8, Collections.emptyMap());

    public PlacementSpec {
      parameters = (parameters == null) ? Collections.emptyMap() : Map.copyOf(parameters);
    }
  }

  /**
   * Confinement boundary and timeout rules.
   */
  @PublicApi
  public record ConfinementSpec(
      ConfinementBoundary boundary,
      long durationSeconds,
      double leashRadius) {

    public static final ConfinementSpec DEFAULT =
        new ConfinementSpec(ConfinementBoundary.SUBSPACE, 300L, 64.0);

    public ConfinementSpec {
      boundary = (boundary == null) ? ConfinementBoundary.SUBSPACE : boundary;
      if (leashRadius <= 0.0) leashRadius = 64.0;
    }
  }

  /**
   * Lifecycle script steps.
   */
  @PublicApi
  public record LifecycleSpec(
      List<LifecycleStep> onStart,
      List<LifecycleStep> onBoundaryViolation,
      List<LifecycleStep> onExpire,
      List<LifecycleStep> onDeath) {

    public static final LifecycleSpec EMPTY =
        new LifecycleSpec(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

    public LifecycleSpec {
      onStart = (onStart == null) ? Collections.emptyList() : List.copyOf(onStart);
      onBoundaryViolation = (onBoundaryViolation == null) ? Collections.emptyList() : List.copyOf(onBoundaryViolation);
      onExpire = (onExpire == null) ? Collections.emptyList() : List.copyOf(onExpire);
      onDeath = (onDeath == null) ? Collections.emptyList() : List.copyOf(onDeath);
    }
  }

  /**
   * Individual lifecycle step which may optionally be guarded by a gate.
   */
  @PublicApi
  public record LifecycleStep(
      Map<String, Object> gateConfig,
      List<CommandAction> actions) {

    public LifecycleStep {
      gateConfig = (gateConfig == null) ? Collections.emptyMap() : Map.copyOf(gateConfig);
      actions = (actions == null) ? Collections.emptyList() : List.copyOf(actions);
    }
  }

  /**
   * Command action execution type and payload.
   */
  @PublicApi
  public record CommandAction(
      ActionType type,
      String payload,
      List<CommandAction> subActions) {

    public CommandAction {
      Objects.requireNonNull(type, "type must not be null");
      payload = (payload == null) ? "" : payload;
      subActions = (subActions == null) ? Collections.emptyList() : List.copyOf(subActions);
    }

    public static CommandAction console(String cmd) {
      return new CommandAction(ActionType.CONSOLE, cmd, Collections.emptyList());
    }

    public static CommandAction player(String cmd) {
      return new CommandAction(ActionType.PLAYER, cmd, Collections.emptyList());
    }

    public static CommandAction action(String actionName) {
      return new CommandAction(ActionType.ACTION, actionName, Collections.emptyList());
    }

    public static CommandAction forEach(List<CommandAction> subActions) {
      return new CommandAction(ActionType.FOR_EACH, "", subActions);
    }
  }

  /**
   * Supported command action execution types (ADR-093).
   */
  @PublicApi
  public enum ActionType {
    CONSOLE,
    PLAYER,
    ACTION,
    FOR_EACH
  }
}
