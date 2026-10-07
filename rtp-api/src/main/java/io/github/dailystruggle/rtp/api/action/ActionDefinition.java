package io.github.dailystruggle.rtp.api.action;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec;
import java.util.ArrayList;
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
    CommandSpec command,
    List<Map<String, Object>> gates,
    List<PhysicalTriggerSpec> triggers,
    String icon,
    String title) {

  public ActionDefinition(
      String id,
      String alias,
      String permission,
      String description,
      PlacementSpec placement,
      ConfinementSpec confinement,
      LifecycleSpec lifecycle) {
    this(id, alias, permission, description, placement, confinement, lifecycle, CommandSpec.EMPTY, Collections.emptyList(), Collections.emptyList(), null, null);
  }

  public ActionDefinition(
      String id,
      String alias,
      String permission,
      String description,
      PlacementSpec placement,
      ConfinementSpec confinement,
      LifecycleSpec lifecycle,
      CommandSpec command) {
    this(id, alias, permission, description, placement, confinement, lifecycle, command, Collections.emptyList(), Collections.emptyList(), null, null);
  }

  public ActionDefinition(
      String id,
      String alias,
      String permission,
      String description,
      PlacementSpec placement,
      ConfinementSpec confinement,
      LifecycleSpec lifecycle,
      CommandSpec command,
      Map<String, Object> gateConfig) {
    this(id, alias, permission, description, placement, confinement, lifecycle, command,
        (gateConfig == null || gateConfig.isEmpty()) ? Collections.emptyList() : List.of(gateConfig), Collections.emptyList(), null, null);
  }

  public ActionDefinition(
      String id,
      String alias,
      String permission,
      String description,
      PlacementSpec placement,
      ConfinementSpec confinement,
      LifecycleSpec lifecycle,
      CommandSpec command,
      List<Map<String, Object>> gates) {
    this(id, alias, permission, description, placement, confinement, lifecycle, command, gates, Collections.emptyList(), null, null);
  }

  public ActionDefinition(
      String id,
      String alias,
      String permission,
      String description,
      PlacementSpec placement,
      ConfinementSpec confinement,
      LifecycleSpec lifecycle,
      CommandSpec command,
      List<Map<String, Object>> gates,
      List<PhysicalTriggerSpec> triggers) {
    this(id, alias, permission, description, placement, confinement, lifecycle, command, gates, triggers, null, null);
  }

  public ActionDefinition {
    Objects.requireNonNull(id, "id must not be null");
    placement = (placement == null) ? PlacementSpec.DEFAULT : placement;
    confinement = (confinement == null) ? ConfinementSpec.DEFAULT : confinement;
    lifecycle = (lifecycle == null) ? LifecycleSpec.EMPTY : lifecycle;
    command = (command == null) ? CommandSpec.EMPTY : command;
    gates = (gates == null) ? Collections.emptyList() : List.copyOf(gates);
    triggers = (triggers == null) ? Collections.emptyList() : List.copyOf(triggers);
    icon = (icon != null && !icon.isBlank()) ? icon.trim() : null;
    title = (title != null && !title.isBlank()) ? title.trim() : null;
  }

  /**
   * Returns whether this action is eligible to appear in the interactive GUI destination menu.
   *
   * <p>Actions are omitted if:
   * <ul>
   *   <li>They require external location inputs (e.g. {@code anchor: "fixed"} or {@code "location"}
   *       without static {@code anchorX} and {@code anchorZ} coordinates);</li>
   *   <li>They declare required {@code COORDINATE} parameters without default values;</li>
   *   <li>They declare other required command parameters without default values;</li>
   *   <li>Their placement is explicitly disabled.</li>
   * </ul>
   */
  public boolean isGuiEligible() {
    if (!placement.enabled()) {
      return false;
    }
    // Check anchor requirements
    Object rawAnchor = placement.parameters().get("anchor");
    String anchor = (rawAnchor == null) ? "regionqueue" : rawAnchor.toString().trim().toLowerCase(java.util.Locale.ROOT);
    if ("fixed".equals(anchor) || "location".equals(anchor) || "landmark".equals(anchor)) {
      Object x = placement.parameters().get("anchorX");
      Object z = placement.parameters().get("anchorZ");
      if (x == null || z == null) {
        return false; // requires external location input!
      }
    }
    // Check command parameters for un-defaulted required inputs or location coordinate inputs
    if (command != null && command.parameters() != null) {
      for (ParameterSpec param : command.parameters()) {
        if (param.type() == ParameterType.COORDINATE && !param.hasDefault()) {
          return false; // requires coordinate input!
        }
        if (param.required() && !param.hasDefault()) {
          return false; // requires manual argument input!
        }
      }
    }
    return true;
  }

  /**
   * Returns backward-compatible primary gate configuration map, or empty map if no gates configured.
   */
  public Map<String, Object> gateConfig() {
    return gates.isEmpty() ? Collections.emptyMap() : gates.get(0);
  }

  /**
   * Top-level command declaration for the action (ADR-093).
   */
  @PublicApi
  public record CommandSpec(
      String name,
      String permission,
      String description,
      List<String> aliases,
      List<ParameterSpec> parameters,
      Map<String, SubcommandSpec> subcommands) {

    public static final CommandSpec EMPTY =
        new CommandSpec("", "", "", Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());

    public CommandSpec {
      name = (name == null) ? "" : name.trim();
      permission = (permission == null) ? "" : permission.trim();
      description = (description == null) ? "" : description.trim();
      aliases = (aliases == null) ? Collections.emptyList() : List.copyOf(aliases);
      parameters = (parameters == null) ? Collections.emptyList() : List.copyOf(parameters);
      subcommands = (subcommands == null) ? Collections.emptyMap() : Map.copyOf(subcommands);
    }

    /**
     * Backward-compatible constructor without declared subcommands.
     */
    public CommandSpec(
        String name,
        String permission,
        String description,
        List<String> aliases,
        List<ParameterSpec> parameters) {
      this(name, permission, description, aliases, parameters, Collections.emptyMap());
    }

    /**
     * Backward-compatible constructor without declared parameters or subcommands.
     */
    public CommandSpec(String name, String permission, String description, List<String> aliases) {
      this(name, permission, description, aliases, Collections.emptyList(), Collections.emptyMap());
    }

    public boolean isConfigured() {
      return !name.isBlank();
    }

    /**
     * Returns the first declared parameter of the given type, or {@code null} if none is declared.
     */
    public ParameterSpec firstParameterOfType(ParameterType type) {
      if (type == null) return null;
      for (ParameterSpec p : parameters) {
        if (p != null && p.type() == type) return p;
      }
      return null;
    }
  }

  /**
   * Declarative subcommand specification on an action command.
   */
  @PublicApi
  public record SubcommandSpec(
      String name,
      String permission,
      String description,
      List<String> aliases,
      List<CommandAction> actions) {

    public SubcommandSpec {
      name = (name == null) ? "" : name.trim();
      permission = (permission == null) ? "" : permission.trim();
      description = (description == null) ? "" : description.trim();
      aliases = (aliases == null) ? Collections.emptyList() : List.copyOf(aliases);
      actions = (actions == null) ? Collections.emptyList() : List.copyOf(actions);
    }

    public SubcommandSpec(String name, String permission, String description, List<CommandAction> actions) {
      this(name, permission, description, Collections.emptyList(), actions);
    }
  }

  /**
   * Spatial placement specifications (ADR-093, ADR-095).
   */
  @PublicApi
  public record PlacementSpec(
      boolean enabled,
      String region,
      String shapeName,
      int radius,
      int centerRadius,
      int minSeparation,
      int elevationTolerance,
      Map<String, Object> parameters,
      int retries,
      int cacheSize) {

    public static final PlacementSpec DEFAULT =
        new PlacementSpec(true, "default", "SQUARE", 64, 0, 16, 8, Collections.emptyMap(), 3, 0);

    public static final PlacementSpec DISABLED =
        new PlacementSpec(false, "", "SQUARE", 0, 0, 0, 0, Collections.emptyMap(), 0, 0);

    public PlacementSpec(
        boolean enabled,
        String region,
        String shapeName,
        int radius,
        int minSeparation,
        int elevationTolerance,
        Map<String, Object> parameters,
        int retries,
        int cacheSize) {
      this(enabled, region, shapeName, radius, 0, minSeparation, elevationTolerance, parameters, retries, cacheSize);
    }

    public PlacementSpec(
        String region,
        String shapeName,
        int radius,
        int minSeparation,
        int elevationTolerance,
        Map<String, Object> parameters) {
      this(true, region, shapeName, radius, 0, minSeparation, elevationTolerance, parameters, 3, 0);
    }

    public PlacementSpec(
        String region,
        String shapeName,
        int radius,
        int minSeparation,
        int elevationTolerance,
        Map<String, Object> parameters,
        int retries,
        int cacheSize) {
      this(true, region, shapeName, radius, 0, minSeparation, elevationTolerance, parameters, retries, cacheSize);
    }

    public PlacementSpec {
      shapeName = (shapeName == null || shapeName.isBlank()) ? "SQUARE" : shapeName.trim().toUpperCase();
      radius = enabled ? Math.max(1, radius) : Math.max(0, radius);
      centerRadius = Math.max(0, centerRadius);
      minSeparation = enabled ? Math.max(1, minSeparation) : Math.max(0, minSeparation);
      elevationTolerance = Math.max(0, elevationTolerance);
      parameters = (parameters == null) ? Collections.emptyMap() : Map.copyOf(parameters);
      retries = enabled ? Math.max(1, retries) : 0;
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
      long shrinkOverSeconds,
      boolean cancellable,
      String shapeName,
      int radius,
      int centerRadius,
      double damageAmount,
      double damageBuffer,
      long damageIntervalSeconds,
      double maxDistanceOutside,
      List<CommandAction> outsideActions) {

    public static final ConfinementSpec DEFAULT =
        new ConfinementSpec(ConfinementBoundary.SUBSPACE, 300L, 64.0, 0.0, 0.0, 0L, true, "SQUARE", 0, 0, 0.0, 0.0, 1L, 0.0, List.of(CommandAction.action("PULL_BACK")));

    public ConfinementSpec(
        ConfinementBoundary boundary,
        long durationSeconds,
        double leashRadius) {
      this(boundary, durationSeconds, leashRadius, 0.0, 0.0, 0L, true, null, 0, 0, 0.0, 0.0, 1L, 0.0, List.of(CommandAction.action("PULL_BACK")));
    }

    public ConfinementSpec(
        ConfinementBoundary boundary,
        long durationSeconds,
        double leashRadius,
        double initialSize,
        double shrinkTo,
        long shrinkOverSeconds) {
      this(boundary, durationSeconds, leashRadius, initialSize, shrinkTo, shrinkOverSeconds, true, null, 0, 0, 0.0, 0.0, 1L, 0.0, List.of(CommandAction.action("PULL_BACK")));
    }

    public ConfinementSpec(
        ConfinementBoundary boundary,
        long durationSeconds,
        double leashRadius,
        double initialSize,
        double shrinkTo,
        long shrinkOverSeconds,
        boolean cancellable) {
      this(boundary, durationSeconds, leashRadius, initialSize, shrinkTo, shrinkOverSeconds, cancellable, null, 0, 0, 0.0, 0.0, 1L, 0.0, List.of(CommandAction.action("PULL_BACK")));
    }

    public ConfinementSpec(
        ConfinementBoundary boundary,
        long durationSeconds,
        double leashRadius,
        double initialSize,
        double shrinkTo,
        long shrinkOverSeconds,
        boolean cancellable,
        String shapeName,
        int radius,
        int centerRadius) {
      this(boundary, durationSeconds, leashRadius, initialSize, shrinkTo, shrinkOverSeconds, cancellable, shapeName, radius, centerRadius, 0.0, 0.0, 1L, 0.0, List.of(CommandAction.action("PULL_BACK")));
    }

    public ConfinementSpec(
        ConfinementBoundary boundary,
        long durationSeconds,
        double leashRadius,
        double initialSize,
        double shrinkTo,
        long shrinkOverSeconds,
        boolean cancellable,
        String shapeName,
        int radius,
        int centerRadius,
        double damageAmount,
        double damageBuffer,
        long damageIntervalSeconds) {
      this(boundary, durationSeconds, leashRadius, initialSize, shrinkTo, shrinkOverSeconds, cancellable, shapeName, radius, centerRadius, damageAmount, damageBuffer, damageIntervalSeconds, 0.0, List.of(CommandAction.action("PULL_BACK")));
    }

    public ConfinementSpec(
        ConfinementBoundary boundary,
        long durationSeconds,
        double leashRadius,
        double initialSize,
        double shrinkTo,
        long shrinkOverSeconds,
        boolean cancellable,
        String shapeName,
        int radius,
        int centerRadius,
        double damageAmount,
        double damageBuffer,
        long damageIntervalSeconds,
        double maxDistanceOutside) {
      this(boundary, durationSeconds, leashRadius, initialSize, shrinkTo, shrinkOverSeconds, cancellable, shapeName, radius, centerRadius, damageAmount, damageBuffer, damageIntervalSeconds, maxDistanceOutside, List.of(CommandAction.action("PULL_BACK")));
    }

    public ConfinementSpec {
      boundary = (boundary == null) ? ConfinementBoundary.SUBSPACE : boundary;
      if (leashRadius <= 0.0) leashRadius = 64.0;
      if (initialSize < 0.0) initialSize = 0.0;
      if (shrinkTo < 0.0) shrinkTo = 0.0;
      if (shrinkOverSeconds < 0L) shrinkOverSeconds = 0L;
      shapeName = (shapeName == null || shapeName.isBlank())
          ? (boundary == ConfinementBoundary.LEASH ? "CIRCLE" : "SQUARE")
          : shapeName.trim().toUpperCase();
      radius = Math.max(0, radius);
      centerRadius = Math.max(0, centerRadius);
      if (damageAmount < 0.0) damageAmount = 0.0;
      if (damageBuffer < 0.0) damageBuffer = 0.0;
      if (damageIntervalSeconds <= 0L) damageIntervalSeconds = 1L;
      if (maxDistanceOutside < 0.0) maxDistanceOutside = 0.0;
      outsideActions = (outsideActions == null || outsideActions.isEmpty())
          ? List.of(CommandAction.action("PULL_BACK"))
          : Collections.unmodifiableList(new ArrayList<>(outsideActions));
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
      List<LifecycleStep> onDeath,
      List<LifecycleStep> onEnqueue,
      List<LifecycleStep> onCancel) {

    public static final LifecycleSpec EMPTY =
        new LifecycleSpec(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

    public LifecycleSpec(
        List<LifecycleStep> onStart,
        List<LifecycleStep> onBoundaryViolation,
        List<LifecycleStep> onExpire,
        List<LifecycleStep> onDeath) {
      this(onStart, onBoundaryViolation, onExpire, onDeath, Collections.emptyList(), Collections.emptyList());
    }

    public LifecycleSpec(
        List<LifecycleStep> onStart,
        List<LifecycleStep> onBoundaryViolation,
        List<LifecycleStep> onExpire,
        List<LifecycleStep> onDeath,
        List<LifecycleStep> onEnqueue) {
      this(onStart, onBoundaryViolation, onExpire, onDeath, onEnqueue, Collections.emptyList());
    }

    public LifecycleSpec {
      onStart = (onStart == null) ? Collections.emptyList() : List.copyOf(onStart);
      onBoundaryViolation = (onBoundaryViolation == null) ? Collections.emptyList() : List.copyOf(onBoundaryViolation);
      onExpire = (onExpire == null) ? Collections.emptyList() : List.copyOf(onExpire);
      onDeath = (onDeath == null) ? Collections.emptyList() : List.copyOf(onDeath);
      onEnqueue = (onEnqueue == null) ? Collections.emptyList() : List.copyOf(onEnqueue);
      onCancel = (onCancel == null) ? Collections.emptyList() : List.copyOf(onCancel);
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

    public static CommandAction message(String msg) {
      return new CommandAction(ActionType.MESSAGE, msg, Collections.emptyList());
    }

    public static CommandAction messageTarget(String msg) {
      return new CommandAction(ActionType.MESSAGE_TARGET, msg, Collections.emptyList());
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
    MESSAGE,
    MESSAGE_TARGET,
    FOR_EACH
  }
}
