package io.github.dailystruggle.rtp.common.selection.region.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DataSizeUnit Tests")
class DataSizeUnitTest {

  @Test
  @DisplayName("Bytes unit conversions")
  void testBytesConversions() {
    DataSizeUnit unit = DataSizeUnit.BYTES;
    assertEquals(1.0, unit.toBytes());
    assertEquals(1024.0, unit.toBytes(1024.0));
    assertEquals(0.001, unit.toKilobytes(1.0), 1e-9);
    assertEquals(0.000001, unit.toMegabytes(1.0), 1e-9);
    assertEquals(1.0 / (1000.0 * 1000.0 * 1000.0), unit.toGigabytes(1.0), 1e-12);

    assertEquals(1.0 / 1024.0, unit.toKibibytes(1.0), 1e-9);
    assertEquals(1.0 / (1024.0 * 1024.0), unit.toMebibytes(1.0), 1e-12);
    assertEquals(1.0 / (1024.0 * 1024.0 * 1024.0), unit.toGibibytes(1.0), 1e-12);
  }

  @Test
  @DisplayName("Decimal Kilobytes conversions")
  void testKilobytesConversions() {
    DataSizeUnit unit = DataSizeUnit.KILOBYTES;
    assertEquals(1000.0, unit.toBytes());
    assertEquals(5000.0, unit.toBytes(5.0));
    assertEquals(5.0, unit.toKilobytes(5.0));
    assertEquals(0.005, unit.toMegabytes(5.0), 1e-9);
  }

  @Test
  @DisplayName("Binary Kibibytes conversions")
  void testKibibytesConversions() {
    DataSizeUnit unit = DataSizeUnit.KIBIBYTES;
    assertEquals(1024.0, unit.toBytes());
    assertEquals(2048.0, unit.toBytes(2.0));
    assertEquals(2.048, unit.toKilobytes(2.0), 1e-9);
    assertEquals(2.0, unit.toKibibytes(2.0));
  }

  @Test
  @DisplayName("Decimal Megabytes conversions")
  void testMegabytesConversions() {
    DataSizeUnit unit = DataSizeUnit.MEGABYTES;
    assertEquals(1_000_000.0, unit.toBytes());
    assertEquals(250_000_000.0, unit.toBytes(250.0));
    assertEquals(250_000.0, unit.toKilobytes(250.0));
    assertEquals(250.0, unit.toMegabytes(250.0));
  }

  @Test
  @DisplayName("Binary Mebibytes conversions")
  void testMebibytesConversions() {
    DataSizeUnit unit = DataSizeUnit.MEBIBYTES;
    assertEquals(1024.0 * 1024.0, unit.toBytes());
    assertEquals(256.0 * 1024.0 * 1024.0, unit.toBytes(256.0));
    assertEquals(256.0, unit.toMebibytes(256.0));
  }

  @Test
  @DisplayName("Decimal and Binary Gigabytes conversions")
  void testGigabytesConversions() {
    DataSizeUnit gb = DataSizeUnit.GIGABYTES;
    assertEquals(1_000_000_000.0, gb.toBytes());
    assertEquals(2.0 * 1_000_000_000.0, gb.toBytes(2.0));
    assertEquals(2000.0, gb.toMegabytes(2.0));

    DataSizeUnit gib = DataSizeUnit.GIBIBYTES;
    assertEquals(1024.0 * 1024.0 * 1024.0, gib.toBytes());
    assertEquals(1.0, gib.toGibibytes(1.0));
    assertEquals(1024.0, gib.toMebibytes(1.0));
  }

  @Test
  @DisplayName("Aliases and fromString lookup")
  void testFromStringAliases() {
    // Bytes
    assertEquals(DataSizeUnit.BYTES, DataSizeUnit.fromString("b"));
    assertEquals(DataSizeUnit.BYTES, DataSizeUnit.fromString("B"));
    assertEquals(DataSizeUnit.BYTES, DataSizeUnit.fromString("byte"));
    assertEquals(DataSizeUnit.BYTES, DataSizeUnit.fromString("bytes"));
    assertEquals(DataSizeUnit.BYTES, DataSizeUnit.fromString("BYTES"));

    // Kilobytes (decimal)
    assertEquals(DataSizeUnit.KILOBYTES, DataSizeUnit.fromString("kb"));
    assertEquals(DataSizeUnit.KILOBYTES, DataSizeUnit.fromString("KB"));
    assertEquals(DataSizeUnit.KILOBYTES, DataSizeUnit.fromString("kilobyte"));
    assertEquals(DataSizeUnit.KILOBYTES, DataSizeUnit.fromString("kilobytes"));

    // Kibibytes (binary)
    assertEquals(DataSizeUnit.KIBIBYTES, DataSizeUnit.fromString("kib"));
    assertEquals(DataSizeUnit.KIBIBYTES, DataSizeUnit.fromString("KiB"));
    assertEquals(DataSizeUnit.KIBIBYTES, DataSizeUnit.fromString("kibibyte"));
    assertEquals(DataSizeUnit.KIBIBYTES, DataSizeUnit.fromString("kibibytes"));

    // Megabytes (decimal)
    assertEquals(DataSizeUnit.MEGABYTES, DataSizeUnit.fromString("mb"));
    assertEquals(DataSizeUnit.MEGABYTES, DataSizeUnit.fromString("MB"));
    assertEquals(DataSizeUnit.MEGABYTES, DataSizeUnit.fromString("megabyte"));
    assertEquals(DataSizeUnit.MEGABYTES, DataSizeUnit.fromString("megabytes"));

    // Mebibytes (binary)
    assertEquals(DataSizeUnit.MEBIBYTES, DataSizeUnit.fromString("mib"));
    assertEquals(DataSizeUnit.MEBIBYTES, DataSizeUnit.fromString("MiB"));
    assertEquals(DataSizeUnit.MEBIBYTES, DataSizeUnit.fromString("mebibyte"));
    assertEquals(DataSizeUnit.MEBIBYTES, DataSizeUnit.fromString("mebibytes"));

    // Gigabytes (decimal)
    assertEquals(DataSizeUnit.GIGABYTES, DataSizeUnit.fromString("gb"));
    assertEquals(DataSizeUnit.GIGABYTES, DataSizeUnit.fromString("GB"));
    assertEquals(DataSizeUnit.GIGABYTES, DataSizeUnit.fromString("gigabyte"));
    assertEquals(DataSizeUnit.GIGABYTES, DataSizeUnit.fromString("gigabytes"));

    // Gibibytes (binary)
    assertEquals(DataSizeUnit.GIBIBYTES, DataSizeUnit.fromString("gib"));
    assertEquals(DataSizeUnit.GIBIBYTES, DataSizeUnit.fromString("GiB"));
    assertEquals(DataSizeUnit.GIBIBYTES, DataSizeUnit.fromString("gibibyte"));
    assertEquals(DataSizeUnit.GIBIBYTES, DataSizeUnit.fromString("gibibytes"));

    // Unknown or null
    assertNull(DataSizeUnit.fromString(null));
    assertNull(DataSizeUnit.fromString(""));
    assertNull(DataSizeUnit.fromString("invalidUnit"));
    assertNull(DataSizeUnit.fromString("tb"));
  }
}
