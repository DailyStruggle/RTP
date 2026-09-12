package io.github.dailystruggle.rtp.common.selection.region.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DataSizeParser Tests")
class DataSizeParserTest {

  @Test
  @DisplayName("Parse standard binary and decimal strings")
  void testParseStandardStrings() {
    // Exact unit matching
    DataSizeParser.ParsedDataSize p1 = DataSizeParser.parse("256MB");
    assertNotNull(p1);
    assertEquals(256.0, p1.magnitude());
    assertEquals(DataSizeUnit.MEGABYTES, p1.unit());
    assertTrue(p1.explicitUnit());
    assertEquals(256_000_000.0, p1.toBytes());
    assertEquals(256.0, p1.toMegabytes());

    DataSizeParser.ParsedDataSize p2 = DataSizeParser.parse("1GiB");
    assertNotNull(p2);
    assertEquals(1.0, p2.magnitude());
    assertEquals(DataSizeUnit.GIBIBYTES, p2.unit());
    assertTrue(p2.explicitUnit());
    assertEquals(1024.0 * 1024.0 * 1024.0, p2.toBytes());
    assertEquals(1.0, p2.toGibibytes());

    DataSizeParser.ParsedDataSize p3 = DataSizeParser.parse("64KB");
    assertNotNull(p3);
    assertEquals(64.0, p3.magnitude());
    assertEquals(DataSizeUnit.KILOBYTES, p3.unit());
    assertEquals(64_000.0, p3.toBytes());
    assertEquals(64.0, p3.toKilobytes());

    DataSizeParser.ParsedDataSize p4 = DataSizeParser.parse("16kib");
    assertNotNull(p4);
    assertEquals(16.0, p4.magnitude());
    assertEquals(DataSizeUnit.KIBIBYTES, p4.unit());
    assertEquals(16.0 * 1024.0, p4.toBytes());
    assertEquals(16.0, p4.toKibibytes());
  }

  @Test
  @DisplayName("Parse handling whitespace and case-insensitivity")
  void testWhitespaceAndCaseInsensitivity() {
    DataSizeParser.ParsedDataSize p1 = DataSizeParser.parse("  256  mb  ");
    assertNotNull(p1);
    assertEquals(256.0, p1.magnitude());
    assertEquals(DataSizeUnit.MEGABYTES, p1.unit());

    DataSizeParser.ParsedDataSize p2 = DataSizeParser.parse("1 GIB");
    assertNotNull(p2);
    assertEquals(1.0, p2.magnitude());
    assertEquals(DataSizeUnit.GIBIBYTES, p2.unit());

    DataSizeParser.ParsedDataSize p3 = DataSizeParser.parse("\t64   Kb \n");
    assertNotNull(p3);
    assertEquals(64.0, p3.magnitude());
    assertEquals(DataSizeUnit.KILOBYTES, p3.unit());

    DataSizeParser.ParsedDataSize p4 = DataSizeParser.parse("512 Bytes");
    assertNotNull(p4);
    assertEquals(512.0, p4.magnitude());
    assertEquals(DataSizeUnit.BYTES, p4.unit());
    assertEquals(512.0, p4.toBytes());
  }

  @Test
  @DisplayName("Parse dimensionless values and defaults")
  void testDimensionlessValues() {
    DataSizeParser.ParsedDataSize p1 = DataSizeParser.parse("1024");
    assertNotNull(p1);
    assertEquals(1024.0, p1.magnitude());
    assertEquals(DataSizeUnit.BYTES, p1.unit());
    assertFalse(p1.explicitUnit());
    assertEquals(1024.0, p1.toBytes());

    DataSizeParser.ParsedDataSize p2 = DataSizeParser.parse("4", DataSizeUnit.MEGABYTES);
    assertNotNull(p2);
    assertEquals(4.0, p2.magnitude());
    assertEquals(DataSizeUnit.MEGABYTES, p2.unit());
    assertFalse(p2.explicitUnit());
    assertEquals(4_000_000.0, p2.toBytes());
  }

  @Test
  @DisplayName("parseBytes with numeric, string, and sentinel values")
  void testParseBytes() {
    // Numbers
    assertEquals(1024L, DataSizeParser.parseBytes(1024));
    assertEquals(5000000L, DataSizeParser.parseBytes(5000000L));
    assertEquals(-1L, DataSizeParser.parseBytes(-100));

    // Sentinels
    assertEquals(-1L, DataSizeParser.parseBytes("-1"));
    assertEquals(-1L, DataSizeParser.parseBytes("infinite"));
    assertEquals(-1L, DataSizeParser.parseBytes("unlimited"));
    assertEquals(-1L, DataSizeParser.parseBytes("none"));

    // Strings
    assertEquals(256L * 1000L * 1000L, DataSizeParser.parseBytes("256MB"));
    assertEquals(1024L * 1024L * 1024L, DataSizeParser.parseBytes("1GiB"));
    assertEquals(64000L, DataSizeParser.parseBytes("64KB"));
    assertEquals(16L * 1024L, DataSizeParser.parseBytes("16KiB"));
    assertEquals(100L, DataSizeParser.parseBytes("100b"));
    assertEquals(100L, DataSizeParser.parseBytes("100 bytes"));

    // Default fallbacks
    assertEquals(4096L, DataSizeParser.parseBytes(null, 4096L));
    assertEquals(4096L, DataSizeParser.parseBytes("", 4096L));
    assertEquals(4096L, DataSizeParser.parseBytes("   ", 4096L));
    assertEquals(4096L, DataSizeParser.parseBytes("invalidToken", 4096L));
    assertEquals(-1L, DataSizeParser.parseBytes("invalidToken"));
  }

  @Test
  @DisplayName("Error handling for malformed tokens")
  void testMalformedTokens() {
    assertNull(DataSizeParser.parse(null));
    assertNull(DataSizeParser.parse(""));
    assertNull(DataSizeParser.parse("   "));
    assertNull(DataSizeParser.parse("abc"));
    assertNull(DataSizeParser.parse("256XYZ"));
    assertNull(DataSizeParser.parse("256.12.34MB"));
    assertNull(DataSizeParser.parse("10MB extra"));
    assertNull(DataSizeParser.parse("MB"));
  }
}
