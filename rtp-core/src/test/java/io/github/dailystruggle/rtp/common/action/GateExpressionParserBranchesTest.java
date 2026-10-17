package io.github.dailystruggle.rtp.common.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Branch coverage for gate range/comparison expressions (ADR-093 Section 3).
 */
class GateExpressionParserBranchesTest {

  @Test
  @DisplayName("Bare duration expressions fall back to duration parsing when not plain numbers")
  void bareDurationExactMatch() {
    assertTrue(GateExpressionParser.matches("30s", 30.0));
    assertFalse(GateExpressionParser.matches("30s", 31.0));
    assertTrue(GateExpressionParser.matches("1m30s", 90.0));
  }

  @Test
  @DisplayName("Operands that are not durations but are valid doubles are parsed numerically")
  void numericOperandsBypassDurationParser() {
    assertTrue(GateExpressionParser.matches("< 1e3", 999.0));
    assertFalse(GateExpressionParser.matches("< 1e3", 1000.0));
    assertTrue(GateExpressionParser.matches("1e2..Infinity", 5000.0));
    assertFalse(GateExpressionParser.matches("> abc", -1.0));
  }

  @Test
  @DisplayName("parseDurationSeconds returns the default for blank input")
  void parseDurationSecondsBlank() {
    assertEquals(7L, GateExpressionParser.parseDurationSeconds("   ", 7L));
    assertEquals(90L, GateExpressionParser.parseDurationSeconds("1m30s", 7L));
  }

  @Test
  @DisplayName("Both equality spellings compare with a tolerance; padded operands are trimmed")
  void equalityOperators() {
    assertTrue(GateExpressionParser.matches("== 5", 5.0));
    assertTrue(GateExpressionParser.matches("=5", 5.0));
    assertFalse(GateExpressionParser.matches("= 5", 5.1));
    assertTrue(GateExpressionParser.matches(" 1 ..  3 ", 2.0));
  }
}
