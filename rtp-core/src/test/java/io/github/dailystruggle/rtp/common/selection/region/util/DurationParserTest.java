package io.github.dailystruggle.rtp.common.selection.region.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TemporalUnit and DurationParser Tests")
class DurationParserTest {

  @ParameterizedTest(name = "parse single unit \"{0}\" -> {1} seconds")
  @CsvSource({
      "20t, 1.0",
      "1tick, 0.05",
      "10ticks, 0.5",
      "50ms, 0.05",
      "1000milli, 1.0",
      "250millis, 0.25",
      "100millisecond, 0.1",
      "500milliseconds, 0.5",
      "1s, 1.0",
      "10sec, 10.0",
      "1second, 1.0",
      "45seconds, 45.0",
      "1m, 60.0",
      "5min, 300.0",
      "2minute, 120.0",
      "30minutes, 1800.0",
      "1h, 3600.0",
      "2hr, 7200.0",
      "1hour, 3600.0",
      "12hours, 43200.0",
      "1d, 86400.0",
      "2day, 172800.0",
      "14days, 1209600.0",
      "1w, 604800.0",
      "2weeks, 1209600.0"
  })
  void testSingleUnitParsing(String input, double expectedSeconds) {
    DurationParser.ParsedDuration parsed = DurationParser.parse(input, TemporalUnit.SECOND);
    assertNotNull(parsed, "Failed to parse: " + input);
    assertTrue(parsed.explicitUnit());
    assertEquals(expectedSeconds, parsed.toSeconds(), 0.0001);
    assertEquals(expectedSeconds * 1000.0, parsed.toMillis(), 0.001);
    assertEquals(expectedSeconds * 20.0, parsed.toTicks(), 0.001);
  }

  @ParameterizedTest(name = "parse composite \"{0}\" -> {1} seconds")
  @CsvSource({
      "1d12h, 129600.0",
      "2h30m, 9000.0",
      "1m30s, 90.0",
      "1d2h15m20s, 94520.0",
      "10s500ms, 10.5",
      "2s20t, 3.0"
  })
  void testCompositeUnitParsing(String input, double expectedSeconds) {
    DurationParser.ParsedDuration parsed = DurationParser.parse(input, TemporalUnit.SECOND);
    assertNotNull(parsed, "Failed to parse composite: " + input);
    assertTrue(parsed.explicitUnit());
    assertEquals(expectedSeconds, parsed.toSeconds(), 0.0001);
    assertEquals(expectedSeconds * 1000.0, parsed.toMillis(), 0.001);
    assertEquals(expectedSeconds * 20.0, parsed.toTicks(), 0.001);
  }

  @Test
  void testPrimarySuffixLookup() {
    assertEquals("t", TemporalUnit.TICK.getPrimarySuffix());
    assertEquals("ms", TemporalUnit.MILLISECOND.getPrimarySuffix());
    assertEquals("s", TemporalUnit.SECOND.getPrimarySuffix());
    assertEquals("m", TemporalUnit.MINUTE.getPrimarySuffix());
    assertEquals("h", TemporalUnit.HOUR.getPrimarySuffix());
    assertEquals("d", TemporalUnit.DAY.getPrimarySuffix());
    assertEquals("w", TemporalUnit.WEEK.getPrimarySuffix());
  }

  @Test
  void testCaseInsensitivityAndAliases() {
    assertEquals(TemporalUnit.TICK, TemporalUnit.fromString("TICKS"));
    assertEquals(TemporalUnit.TICK, TemporalUnit.fromString("Tick"));
    assertEquals(TemporalUnit.MILLISECOND, TemporalUnit.fromString("MILLIS"));
    assertEquals(TemporalUnit.MILLISECOND, TemporalUnit.fromString("Millisecond"));
    assertEquals(TemporalUnit.SECOND, TemporalUnit.fromString("SEC"));
    assertEquals(TemporalUnit.SECOND, TemporalUnit.fromString("Seconds"));
    assertEquals(TemporalUnit.MINUTE, TemporalUnit.fromString("MIN"));
    assertEquals(TemporalUnit.HOUR, TemporalUnit.fromString("HR"));
    assertEquals(TemporalUnit.DAY, TemporalUnit.fromString("DAYS"));
    assertNull(TemporalUnit.fromString("unknownUnit"));
    assertNull(TemporalUnit.fromString(""));
    assertNull(TemporalUnit.fromString(null));
  }

  @Test
  void testDimensionlessFallback() {
    DurationParser.ParsedDuration parsedSeconds = DurationParser.parse("60", TemporalUnit.SECOND);
    assertNotNull(parsedSeconds);
    assertFalse(parsedSeconds.explicitUnit());
    assertEquals(60.0, parsedSeconds.magnitude());
    assertEquals(TemporalUnit.SECOND, parsedSeconds.unit());
    assertEquals(60.0, parsedSeconds.toSeconds(), 0.001);
    assertEquals(60000.0, parsedSeconds.toMillis(), 0.001);
    assertEquals(1200.0, parsedSeconds.toTicks(), 0.001);

    DurationParser.ParsedDuration parsedTicks = DurationParser.parse("20", TemporalUnit.TICK);
    assertNotNull(parsedTicks);
    assertFalse(parsedTicks.explicitUnit());
    assertEquals(20.0, parsedTicks.magnitude());
    assertEquals(TemporalUnit.TICK, parsedTicks.unit());
    assertEquals(1.0, parsedTicks.toSeconds(), 0.001);
    assertEquals(1000.0, parsedTicks.toMillis(), 0.001);
    assertEquals(20.0, parsedTicks.toTicks(), 0.001);
  }

  @Test
  void testAutoInterpret() {
    // 5000 in tick context with modulo 50 -> likely 5000ms
    DurationParser.ParsedDuration parsedTick = DurationParser.parse("5000", TemporalUnit.TICK);
    assertNotNull(parsedTick);
    assertFalse(parsedTick.explicitUnit());
    DurationParser.ParsedDuration reinterpreted =
        DurationParser.autoInterpret(parsedTick, TemporalUnit.TICK, "queue period");
    assertEquals(TemporalUnit.MILLISECOND, reinterpreted.unit());
    assertEquals(5000.0, reinterpreted.magnitude());
    assertEquals(5.0, reinterpreted.toSeconds(), 0.001);
    assertEquals(100.0, reinterpreted.toTicks(), 0.001);

    // Explicit unit must never be reinterpreted
    DurationParser.ParsedDuration explicit = DurationParser.parse("5000t", TemporalUnit.TICK);
    assertNotNull(explicit);
    assertTrue(explicit.explicitUnit());
    DurationParser.ParsedDuration notChanged =
        DurationParser.autoInterpret(explicit, TemporalUnit.TICK, "queue period");
    assertEquals(TemporalUnit.TICK, notChanged.unit());
    assertEquals(5000.0, notChanged.magnitude());
  }

  @Test
  void testInvalidInput() {
    assertNull(DurationParser.parse(null, TemporalUnit.SECOND));
    assertNull(DurationParser.parse("", TemporalUnit.SECOND));
    assertNull(DurationParser.parse("   ", TemporalUnit.SECOND));
    assertNull(DurationParser.parse("abc", TemporalUnit.SECOND));
    assertNull(DurationParser.parse("10invalidUnit", TemporalUnit.SECOND));
  }
}
