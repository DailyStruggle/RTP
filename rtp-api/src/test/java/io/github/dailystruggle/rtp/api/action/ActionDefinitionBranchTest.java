package io.github.dailystruggle.rtp.api.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.action.ActionDefinition.ActionType;
import io.github.dailystruggle.rtp.api.action.ActionDefinition.CommandAction;
import io.github.dailystruggle.rtp.api.action.ActionDefinition.CommandSpec;
import io.github.dailystruggle.rtp.api.action.ActionDefinition.ConfinementSpec;
import io.github.dailystruggle.rtp.api.action.ActionDefinition.LifecycleSpec;
import io.github.dailystruggle.rtp.api.action.ActionDefinition.PlacementSpec;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Constructor normalisation and GUI-eligibility branches of {@link ActionDefinition} (ADR-093).
 */
@DisplayName("ADR-093 - ActionDefinition constructors and GUI eligibility")
class ActionDefinitionBranchTest {

  private static CommandSpec cmd(ParameterSpec... params) {
    return new CommandSpec("duel", "", "", List.of(), List.of(params));
  }

  private static ActionDefinition withPlacement(PlacementSpec placement) {
    return new ActionDefinition("a", null, null, null, placement, null, null);
  }

  @Test
  @DisplayName("single-gate constructor wraps a non-empty map and drops null/empty maps")
  void singleGateConstructor() {
    Map<String, Object> gate = Map.of("type", "permission");
    ActionDefinition withGate = new ActionDefinition("a", null, null, null, null, null, null,
        CommandSpec.EMPTY, gate);
    assertEquals(List.of(gate), withGate.gates());
    assertEquals(gate, withGate.gateConfig());

    ActionDefinition nullGate = new ActionDefinition("a", null, null, null, null, null, null,
        CommandSpec.EMPTY, (Map<String, Object>) null);
    assertTrue(nullGate.gates().isEmpty());
    assertTrue(nullGate.gateConfig().isEmpty());

    ActionDefinition emptyGate = new ActionDefinition("a", null, null, null, null, null, null,
        CommandSpec.EMPTY, Collections.<String, Object>emptyMap());
    assertTrue(emptyGate.gates().isEmpty());
  }

  @Test
  @DisplayName("gate-list and trigger-list constructors normalise null collections")
  void listConstructors() {
    ActionDefinition gatesOnly = new ActionDefinition("a", null, null, null, null, null, null,
        null, (List<Map<String, Object>>) null);
    assertTrue(gatesOnly.gates().isEmpty());
    assertSame(CommandSpec.EMPTY, gatesOnly.command());

    ActionDefinition withTriggers = new ActionDefinition("a", null, null, null, null, null, null,
        CommandSpec.EMPTY, List.of(Map.of("k", "v")), null);
    assertEquals(1, withTriggers.gates().size());
    assertTrue(withTriggers.triggers().isEmpty());
    assertNull(withTriggers.icon());
    assertNull(withTriggers.title());
  }

  @Test
  @DisplayName("icon and title are trimmed when present and nulled when blank")
  void iconAndTitle() {
    ActionDefinition full = new ActionDefinition("a", null, null, null, null, null, null,
        null, null, null, "  DIAMOND ", " Arena ");
    assertEquals("DIAMOND", full.icon());
    assertEquals("Arena", full.title());
    assertSame(LifecycleSpec.EMPTY, full.lifecycle());

    ActionDefinition blank = new ActionDefinition("a", null, null, null, null, null, null,
        null, null, null, "  ", "");
    assertNull(blank.icon());
    assertNull(blank.title());
  }

  @Test
  @DisplayName("disabled placement is never GUI-eligible")
  void disabledPlacementIneligible() {
    assertFalse(withPlacement(PlacementSpec.DISABLED).isGuiEligible());
  }

  @Test
  @DisplayName("location and landmark anchors need both static coordinates")
  void anchorCoordinates() {
    assertFalse(withPlacement(new PlacementSpec("r", null, 10, 1, 0,
        Map.of("anchor", "location", "anchorX", 1))).isGuiEligible());
    assertFalse(withPlacement(new PlacementSpec("r", null, 10, 1, 0,
        Map.of("anchor", " Landmark ", "anchorZ", 1))).isGuiEligible());
    assertTrue(withPlacement(new PlacementSpec("r", null, 10, 1, 0,
        Map.of("anchor", "landmark", "anchorX", 1, "anchorZ", 2))).isGuiEligible());
    assertTrue(withPlacement(new PlacementSpec("r", null, 10, 1, 0,
        Map.of("anchor", "player"))).isGuiEligible());
  }

  @Test
  @DisplayName("required parameters without defaults block GUI eligibility")
  void requiredParameterIneligible() {
    ActionDefinition required = new ActionDefinition("a", null, null, null, null, null, null,
        cmd(new ParameterSpec("target", ParameterType.PLAYER, true, null, null)));
    assertFalse(required.isGuiEligible());

    ActionDefinition defaulted = new ActionDefinition("a", null, null, null, null, null, null,
        cmd(new ParameterSpec("target", ParameterType.PLAYER, true, null, "self"),
            new ParameterSpec("x", ParameterType.COORDINATE, false, null, "spawn"),
            new ParameterSpec("note", ParameterType.STRING, false, null, null)));
    assertTrue(defaulted.isGuiEligible());

    ActionDefinition coordinate = new ActionDefinition("a", null, null, null, null, null, null,
        cmd(new ParameterSpec("x", ParameterType.COORDINATE, false, null, null)));
    assertFalse(coordinate.isGuiEligible());
  }

  @Test
  @DisplayName("PlacementSpec convenience constructors apply defaults")
  void placementConstructors() {
    PlacementSpec nine = new PlacementSpec(false, "r", "  circle ", 5, 2, 3, null, 4, 7);
    assertFalse(nine.enabled());
    assertEquals("CIRCLE", nine.shapeName());
    assertEquals(0, nine.centerRadius());
    assertEquals(0, nine.retries());
    assertEquals(7, nine.cacheSize());

    PlacementSpec eight = new PlacementSpec("r", "", 5, 2, 3, Map.of(), 4, 9);
    assertTrue(eight.enabled());
    assertEquals("SQUARE", eight.shapeName());
    assertEquals(4, eight.retries());
    assertEquals(9, eight.cacheSize());
  }

  @Test
  @DisplayName("ConfinementSpec cancellable constructor and custom outside actions")
  void confinementConstructors() {
    ConfinementSpec c = new ConfinementSpec(ConfinementBoundary.LEASH, 10L, 5.0, 1.0, 0.5, 3L, false);
    assertFalse(c.cancellable());
    assertEquals("CIRCLE", c.shapeName());
    assertEquals(List.of(CommandAction.action("PULL_BACK")), c.outsideActions());

    ConfinementSpec custom = new ConfinementSpec(ConfinementBoundary.SUBSPACE, 10L, 5.0, 0.0, 0.0, 0L,
        true, " circle ", 4, 1, 0.0, 0.0, 1L, 0.0, List.of(CommandAction.console("say hi")));
    assertEquals("CIRCLE", custom.shapeName());
    assertEquals(ActionType.CONSOLE, custom.outsideActions().get(0).type());
  }

  @Test
  @DisplayName("CommandAction normalises null payload and sub-actions; messageTarget factory")
  void commandActionFactories() {
    CommandAction target = CommandAction.messageTarget("hi");
    assertEquals(ActionType.MESSAGE_TARGET, target.type());
    assertEquals("hi", target.payload());

    CommandAction raw = new CommandAction(ActionType.MESSAGE, null, null);
    assertEquals("", raw.payload());
    assertTrue(raw.subActions().isEmpty());
  }

  @Test
  @DisplayName("CommandSpec normalises null lists and finds the first parameter by type")
  void commandSpecNormalisation() {
    CommandSpec spec = new CommandSpec(null, null, null, null, null, null);
    assertTrue(spec.parameters().isEmpty());
    assertTrue(spec.subcommands().isEmpty());
    assertFalse(spec.isConfigured());

    ParameterSpec world = new ParameterSpec("w", ParameterType.WORLD, false, null, null);
    CommandSpec typed = cmd(new ParameterSpec("t", ParameterType.PLAYER, false, null, null), world);
    assertSame(world, typed.firstParameterOfType(ParameterType.WORLD));
    assertNull(typed.firstParameterOfType(ParameterType.REGION));
  }
}
