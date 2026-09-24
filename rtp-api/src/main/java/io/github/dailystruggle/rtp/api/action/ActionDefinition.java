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
    LifecycleSpec lifecycle,
    CommandSpec command) {

  public ActionDefinition(
      String id,
      String alias,
      String permission,
      String description,
      PlacementSpec placement,
      ConfinementSpec confinement,
      LifecycleSpec lifecycle) {
    this(id, alias, permission, description, placement, confinement, lifecycle, CommandSpec.EMPTY);
  }

  public ActionDefinition {
    Objects.requireNonNull(id, "id must not be null");
    placement = (placement == null) ? PlacementSpec.DEFAULT : placement;
    confinement = (confinement == null) ? ConfinementSpec.DEFAULT : confinement;
    lifecycle = (lifecycle == null) ? LifecycleSpec.EMPTY : lifecycle;
    command = (command == null) ? CommandSpec.EMPTY : command;
  }

  /**
   * Top-level command declaration for the action (ADR-093).
   */
  @PublicApi
  public record CommandSpec(
      String name,
      String permission,
      String description,
      List<String> aliases) {

    public static final CommandSpec EMPTY =
        new CommandSpec("", "", "", Collections.emptyList());

    public CommandSpec {
      name = (name == null) ? "" : name.trim();
      permission = (permission == null) ? "" : permission.trim();
      description = (description == null) ? "" : description.trim();
      aliases = (aliases == null) ? Collections.emptyList() : List.copyOf(aliases);
    }

    public boolean isConfigured() {
      return !name.isBlank();
    }
  }

  /**
   * Spatial placement specifications (ADR-093, ADR-095).
   */
  @PublicApi
  public record PlacementSpec(
      String region,
      String shapeName,
      int radius,
      int minSeparation,
      int elevationTolerance,
      Map<String, Object> parameters,
      int retries,
      int cacheSize) {

    public static final PlacementSpec DEFAULT =
        new PlacementSpec("default", "SQUARE", 64, 16, 8, Collections.emptyMap(), 3, 0);

    public PlacementSpec(
        String region,
        String shapeName,
        int radius,
        int minSeparation,
        int elevationTolerance,
        Map<String, Object> parameters) {
      this(region, shapeName, radius, minSeparation, elevationTolerance, parameters, 3, 0);
    }

    public PlacementSpec {
      shapeName = (shapeName == null || shapeName.isBlank()) ? "SQUARE" : shapeName.trim().toUpperCase();
      radius = Math.max(1, radius);
      minSeparation = Math.max(1, minSeparation);
      elevationTolerance = Math.max(0, elevationTolerance);
      parameters = (parameters == null) ? Collections.emptyMap() : Map.copyOf(parameters);
      retries = Math.max(1, retries);
      cacheSize = Math.max(0, cacheSize);
    }

    /**
     * Spacing between players in the same spatial cluster (teammates).
     * Defaults to 4 blocks or clamped to {@code minSeparation}.
     */
    public int clusterSeparation() {
      Object v = parameters.get("clusterSeparation");
      if (v instanceof Number n) return Math.max(1, n.intValue());
      Object v2 = parameters.get("teammateSeparation");
      if (v2 instanceof Number n2) return Math.max(1, n2.intValue());
      Object v3 = parameters.get("groupSeparation");
      if (v3 instanceof Number n3) return Math.max(1, n3.intValue());
      return Math.min(minSeparation, 4);
    }
  }

  /**
   * Confinement boundary and timeout rules.
   */
  @PublicApi
  public record ConfinementSpec(
      ConfinementBoundary boundary,
      long durationSeconds,
      double leashRadius,
      double initialSize,
      double shrinkTo,
      long shrinkOverSeconds) {

    public static final ConfinementSpec DEFAULT =
        new ConfinementSpec(ConfinementBoundary.SUBSPACE, 300L, 64.0, 0.0, 0.0, 0L);

    public ConfinementSpec(
        ConfinementBoundary boundary,
        long durationSeconds,
        double leashRadius) {
      this(boundary, durationSeconds, leashRadius, 0.0, 0.0, 0L);
    }

    public ConfinementSpec {
      boundary = (boundary == null) ? ConfinementBoundary.SUBSPACE : boundary;
      if (leashRadius <= 0.0) leashRadius = 64.0;
      if (initialSize < 0.0) initialSize = 0.0;
      if (shrinkTo < 0.0) shrinkTo = 0.0;
      if (shrinkOverSeconds < 0L) shrinkOverSeconds = 0L;
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
   * Individual lifecycle step which may optionally be guarded by a gate and have an execution delay.
   */
  @PublicApi
  public record LifecycleStep(
      Map<String, Object> gateConfig,
      List<CommandAction> actions,
      long delaySeconds) {

    public LifecycleStep(
        Map<String, Object> gateConfig,
        List<CommandAction> actions) {
      this(gateConfig, actions, 0L);
    }

    public LifecycleStep {
      gateConfig = (gateConfig == null) ? Collections.emptyMap() : Map.copyOf(gateConfig);
      actions = (actions == null) ? Collections.emptyList() : List.copyOf(actions);
      if (delaySeconds < 0L) delaySeconds = 0L;
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
