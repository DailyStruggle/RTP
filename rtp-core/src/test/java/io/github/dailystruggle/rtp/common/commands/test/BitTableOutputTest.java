package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTable;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("REQ-API-F-009 colored BIT table output for console and chat")
class BitTableOutputTest {
  @TempDir File tempDir;
  private MockRTPServerAccessor accessor;

  @BeforeEach
  void setUp() {
    accessor = RTPTestSetup.install(tempDir);
  }

  private static List<BitTable.Row> failingRows() {
    BitComponent component = new BitComponent("probe", "test.probe", BitTier.IBIT,
        BitSideEffect.SAFE, 1_000, () -> BitReport.of(List.of()));
    BitReport report = BitReport.of(List.of(new BitFinding(Level.ERROR, 3103, "TEST_LEAK",
        "rtp test probe", "probe.tracker", "residual=1, expected 0", "", "")));
    return BitTable.rows(component, new BitResult(component, report, 1, 1));
  }

  private static String strip(String line) {
    return line.replaceAll("&[0-9a-fk-or]", "").substring(BitTableOutput.PREFIX.length());
  }

  @Test
  void strippedLinesEqualThePlainCsvTable() {
    List<BitTable.Row> rows = failingRows();
    List<String> plain = BitTable.render(rows, BitTable.COLUMNS, BitTable.Decorator.NONE);
    List<String> colored = BitTableOutput.render(rows);

    assertEquals(plain, colored.stream().map(BitTableOutputTest::strip).toList());
    assertTrue(colored.get(1).contains(BitTableOutput.BAD + "FAILED"));
    assertTrue(colored.get(1).contains(BitTableOutput.CODE + "TEST_LEAK"));
    assertTrue(colored.get(1).contains(BitTableOutput.PATH + "probe.tracker"));
  }

  @Test
  void chatLinesUseTheMonospaceFontAndConsoleLinesDoNot() {
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "table-player", null);
    accessor.addPlayer(player);

    BitTableOutput.emit(player.uuid(), failingRows());

    assertEquals(2, player.sentMessages.size());
    assertTrue(player.sentMessages.stream().allMatch(m ->
        m.startsWith("<font:minecraft:uniform>") && m.endsWith("</font>")));
    assertTrue(accessor.logMessages.stream().anyMatch(m -> m.contains(BitTableOutput.PREFIX)));
    assertTrue(accessor.logMessages.stream().noneMatch(m -> m.contains("<font:")));
  }
}
