package io.github.dailystruggle.rtp.api.action;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Coverage for declarative command parameters (ADR-093): {@link ParameterType}, {@link ParameterSpec},
 * and {@link ActionDefinition.CommandSpec} parameter accessors, including fail-fast validation.
 */
class DeclarativeParameterTest {

  @Test
  @DisplayName("ParameterType parses names, defaults to STRING, and exposes symbolic keyword sets")
  void parameterTypeParsingAndKeywords() {
    assertEquals(ParameterType.PLAYER, ParameterType.parse("player"));
    assertEquals(ParameterType.COORDINATE, ParameterType.parse("COORDINATE"));
    assertEquals(ParameterType.STRING, ParameterType.parse(null));
    assertEquals(ParameterType.STRING, ParameterType.parse("  "));
    assertThrows(IllegalArgumentException.class, () -> ParameterType.parse("nope"));

    assertTrue(ParameterType.PLAYER.isSymbolicDefault("any"));
    assertTrue(ParameterType.PLAYER.isSymbolicDefault("SELF"));
    assertFalse(ParameterType.PLAYER.isSymbolicDefault("spawn"));
    assertFalse(ParameterType.NUMBER.isSymbolicDefault("self"));
    assertTrue(ParameterType.COORDINATE.symbolicDefaults().contains("spawn"));

    assertTrue(ParameterType.isKnownSymbolicKeyword("any"));
    assertTrue(ParameterType.isKnownSymbolicKeyword("spawn"));
    assertFalse(ParameterType.isKnownSymbolicKeyword("Notch"));
    assertFalse(ParameterType.isKnownSymbolicKeyword(null));
  }

  @Test
  @DisplayName("Valid symbolic and literal defaults are accepted")
  void validDefaultsAccepted() {
    ParameterSpec player = new ParameterSpec("player", ParameterType.PLAYER, false, "rtp.x", "any");
    assertDoesNotThrow(player::validate);
    assertTrue(player.isSymbolicDefault());
    assertTrue(player.hasPermission());
    assertTrue(player.hasDefault());
    assertEquals("rtp.x", player.permission());

    ParameterSpec coordSymbolic = new ParameterSpec("anchorx", ParameterType.COORDINATE, false, "", "spawn");
    assertDoesNotThrow(coordSymbolic::validate);

    ParameterSpec coordLiteral = new ParameterSpec("anchorx", ParameterType.COORDINATE, true, "", "0");
    assertDoesNotThrow(coordLiteral::validate);
    assertFalse(coordLiteral.isSymbolicDefault());
    assertTrue(coordLiteral.required());

    ParameterSpec none = new ParameterSpec("player", ParameterType.PLAYER, false, null, null);
    assertFalse(none.hasPermission());
    assertFalse(none.hasDefault());
    assertDoesNotThrow(none::validate);
  }

  @Test
  @DisplayName("Illegal symbolic default for the declared type and blank name fail fast")
  void invalidDefaultsRejected() {
    ParameterSpec badCoord = new ParameterSpec("anchorx", ParameterType.COORDINATE, false, "", "any");
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, badCoord::validate);
    assertTrue(ex.getMessage().contains("any"));

    ParameterSpec badPlayer = new ParameterSpec("player", ParameterType.PLAYER, false, "", "spawn");
    assertThrows(IllegalArgumentException.class, badPlayer::validate);

    ParameterSpec blank = new ParameterSpec("", ParameterType.PLAYER, false, "", "self");
    assertThrows(IllegalArgumentException.class, blank::validate);
  }

  @Test
  @DisplayName("CommandSpec carries declared parameters and resolves first-of-type")
  void commandSpecParameters() {
    ParameterSpec target = new ParameterSpec("player", ParameterType.PLAYER, false, "rtp.command.x.target", "any");
    ActionDefinition.CommandSpec spec =
        new ActionDefinition.CommandSpec("x", "rtp.command.x", "desc", List.of(), List.of(target));

    assertEquals(1, spec.parameters().size());
    assertEquals(target, spec.firstParameterOfType(ParameterType.PLAYER));
    assertNull(spec.firstParameterOfType(ParameterType.COORDINATE));
    assertNull(spec.firstParameterOfType(null));

    // Backward-compatible constructor yields an empty parameter list.
    ActionDefinition.CommandSpec legacy =
        new ActionDefinition.CommandSpec("y", "", "", List.of());
    assertTrue(legacy.parameters().isEmpty());
    assertNull(legacy.firstParameterOfType(ParameterType.PLAYER));
    assertTrue(ActionDefinition.CommandSpec.EMPTY.parameters().isEmpty());
  }
}
