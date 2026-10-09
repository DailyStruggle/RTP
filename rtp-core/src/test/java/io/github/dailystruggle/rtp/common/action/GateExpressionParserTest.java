package io.github.dailystruggle.rtp.common.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GateExpressionParserTest {

  @Test
  @DisplayName("Comparisons with numeric operators match correctly")
  void testComparisons() {
    assertTrue(GateExpressionParser.matches("< 2", 1));
    assertFalse(GateExpressionParser.matches("< 2", 2));
    assertFalse(GateExpressionParser.matches("< 2", 3));

    assertTrue(GateExpressionParser.matches("<= 2", 2));
    assertTrue(GateExpressionParser.matches("<= 2", 1));
    assertFalse(GateExpressionParser.matches("<= 2", 3));

    assertTrue(GateExpressionParser.matches("> 5", 6));
    assertFalse(GateExpressionParser.matches("> 5", 5));

    assertTrue(GateExpressionParser.matches(">= 5", 5));
    assertTrue(GateExpressionParser.matches(">= 5", 6));
    assertFalse(GateExpressionParser.matches(">= 5", 4));

    assertTrue(GateExpressionParser.matches("== 10", 10));
    assertTrue(GateExpressionParser.matches("= 10", 10));
    assertFalse(GateExpressionParser.matches("== 10", 11));
  }

  @Test
  @DisplayName("Ranges (min..max) match inclusive bounds")
  void testRanges() {
    assertTrue(GateExpressionParser.matches("1..5", 1));
    assertTrue(GateExpressionParser.matches("1..5", 3));
    assertTrue(GateExpressionParser.matches("1..5", 5));
    assertFalse(GateExpressionParser.matches("1..5", 0));
    assertFalse(GateExpressionParser.matches("1..5", 6));
  }

  @Test
  @DisplayName("Duration strings with time units parse correctly")
  void testDurationParsing() {
    assertEquals(30L, GateExpressionParser.parseDurationSeconds("30s", 0L));
    assertEquals(30L, GateExpressionParser.parseDurationSeconds("30sec", 0L));
    assertEquals(300L, GateExpressionParser.parseDurationSeconds("5m", 0L));
    assertEquals(300L, GateExpressionParser.parseDurationSeconds("5min", 0L));
    assertEquals(3600L, GateExpressionParser.parseDurationSeconds("1h", 0L));
    assertEquals(86400L, GateExpressionParser.parseDurationSeconds("1d", 0L));
    assertEquals(10L, GateExpressionParser.parseDurationSeconds("invalid", 10L));
  }

  @Test
  @DisplayName("Matches with temporal units convert to seconds")
  void testMatchesWithUnits() {
    assertTrue(GateExpressionParser.matches(">= 30s", 30));
    assertTrue(GateExpressionParser.matches(">= 30s", 45));
    assertFalse(GateExpressionParser.matches(">= 30s", 25));

    assertTrue(GateExpressionParser.matches("<= 5m", 300));
    assertTrue(GateExpressionParser.matches("<= 5m", 120));
    assertFalse(GateExpressionParser.matches("<= 5m", 305));
  }

  @Test
  @DisplayName("Blank or malformed expressions fail closed (return false)")
  void testMalformedExpressionsFailClosed() {
    assertFalse(GateExpressionParser.matches(null, 5));
    assertFalse(GateExpressionParser.matches("", 5));
    assertFalse(GateExpressionParser.matches("abc", 5));
  }
}
