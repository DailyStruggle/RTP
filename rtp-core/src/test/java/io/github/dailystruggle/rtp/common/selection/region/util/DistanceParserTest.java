package io.github.dailystruggle.rtp.common.selection.region.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SpatialUnit and DistanceParser Tests")
class DistanceParserTest {

  @ParameterizedTest(name = "parse \"{0}\" -> {1} blocks")
  @CsvSource({
      "256c, 4096.0",
      "4096b, 4096.0",
      "4096m, 4096.0",
      "4096metre, 4096.0",
      "4096metres, 4096.0",
      "4r, 2048.0",
      "2km, 2000.0",
      "2k, 2000.0",
      "2kilo, 2000.0",
      "2kilos, 2000.0",
      "2kilometre, 2000.0",
      "2kilometres, 2000.0",
      "10nb, 80.0",
      "10netherblocks, 80.0",
      "1mi, 1609.344",
      "1nm, 1852.0",
      "1nmi, 1852.0",
      "100yd, 91.44",
      "100ft, 30.48",
      "100', 30.48",
      "12\", 0.3048",
      "100smoots, 170.18",
      "1league, 4828.032",
      "10furlongs, 2011.68",
      "1au, 149597870700.0",
      "2aus, 299195741400.0",
      "1pc, 3.085677581491367e16"
  })
  void testUnitParsing(String input, double expectedBlocks) {
    DistanceParser.ParsedDistance parsed = DistanceParser.parse(input, SpatialUnit.BLOCK);
    assertNotNull(parsed, "Failed to parse: " + input);
    assertTrue(parsed.explicitUnit());
    assertEquals(expectedBlocks, parsed.toBlocks(), 0.001);
  }

  @Test
  void testDimensionlessFallback() {
    DistanceParser.ParsedDistance parsed = DistanceParser.parse("256", SpatialUnit.CHUNK);
    assertNotNull(parsed);
    assertFalse(parsed.explicitUnit());
    assertEquals(256.0, parsed.magnitude());
    assertEquals(SpatialUnit.CHUNK, parsed.unit());
    assertEquals(4096.0, parsed.toBlocks(), 0.001);
  }

  @Test
  void testAutoInterpretSmallRadius() {
    // 4 dimensionless with world border of 10,000 blocks
    DistanceParser.ParsedDistance parsed = DistanceParser.parse("4", SpatialUnit.BLOCK);
    assertNotNull(parsed);
    assertFalse(parsed.explicitUnit());

    DistanceParser.ParsedDistance reinterpreted = DistanceParser.autoInterpret(parsed, 10000.0, "test radius");
    assertEquals(SpatialUnit.REGION, reinterpreted.unit());
    assertEquals(2048.0, reinterpreted.toBlocks(), 0.001);
  }

  @Test
  void testAutoInterpretMediumSmallRadius() {
    // 16 dimensionless with world border of 1000 blocks -> 16 * 512 = 8192 > 1000, so not regions; 16 * 16 = 256 <= 1000 -> chunks!
    DistanceParser.ParsedDistance parsed = DistanceParser.parse("16", SpatialUnit.BLOCK);
    assertNotNull(parsed);

    DistanceParser.ParsedDistance reinterpreted = DistanceParser.autoInterpret(parsed, 1000.0, "test radius");
    assertEquals(SpatialUnit.CHUNK, reinterpreted.unit());
    assertEquals(256.0, reinterpreted.toBlocks(), 0.001);
  }

  @Test
  void testExplicitUnitIsNotReinterpreted() {
    DistanceParser.ParsedDistance parsed = DistanceParser.parse("4b", SpatialUnit.BLOCK);
    assertNotNull(parsed);
    assertTrue(parsed.explicitUnit());

    DistanceParser.ParsedDistance reinterpreted = DistanceParser.autoInterpret(parsed, 10000.0, "test radius");
    assertEquals(SpatialUnit.BLOCK, reinterpreted.unit());
    assertEquals(4.0, reinterpreted.toBlocks(), 0.001);
  }
}
