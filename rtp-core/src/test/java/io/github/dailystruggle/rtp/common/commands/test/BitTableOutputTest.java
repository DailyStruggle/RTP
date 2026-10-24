package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.ArrayList;
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

  private static BitComponent component(String id) {
    return new BitComponent(id, "test." + id, BitTier.IBIT,
        BitSideEffect.SAFE, 1_000, () -> BitReport.of(List.of()));
  }

  private static BitFinding finding(Level level, int id, String code, String path) {
    return new BitFinding(level, id, code, "rtp test probe", path, "long diagnostic message", "", "");
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

  @Test
  @DisplayName("REQ-API-F-009 chat gets status, component and code only; the log keeps the full row")
  void chatLinesAreCompactAndTheLogKeepsTheFullTable() {
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "compact-player", null);
    accessor.addPlayer(player);

    BitTableOutput.emit(player.uuid(), failingRows());

    String chat = strip(player.sentMessages.get(1)
        .replace("<font:minecraft:uniform>", "").replace("</font>", ""));
    assertEquals("FAIL probe TEST_LEAK", chat);
    for (String detail : List.of("probe.tracker", "residual=1", "IBIT", "SAFE", "3103")) {
      assertFalse(chat.contains(detail), detail);
    }
    String log = String.join("\n", accessor.logMessages);
    assertTrue(log.contains("probe.tracker") && log.contains("residual=1, expected 0"), log);
  }

  @Test
  @DisplayName("REQ-API-F-009 chat maps health and severity to PASS/WARN/FAIL/INFO/SKIP and collapses repeats")
  void compactLinesMapStatusesAndCollapseRepeatedCodes() {
    BitComponent config = component("config-yaml");
    BitReport configReport = BitReport.of(List.of(
        finding(Level.WARNING, 1301, "RTP_REGION_UNKNOWN_WORLD", "regions.a.world"),
        finding(Level.WARNING, 1301, "RTP_REGION_UNKNOWN_WORLD", "regions.b.world"),
        finding(Level.ERROR, 1001, "RTP_YAML_PARSE", ""),
        finding(Level.INFO, 1002, "RTP_NOTE", "")));
    BitComponent healthy = component("chunk-ticket");
    BitComponent notRun = component("accessor");

    List<BitTable.Row> rows = new ArrayList<>(
        BitTable.rows(config, new BitResult(config, configReport, 1, 1)));
    rows.addAll(BitTable.rows(healthy, new BitResult(healthy, BitReport.of(List.of()), 1, 1)));
    rows.addAll(BitTable.rows(notRun, null));

    List<String> lines = BitTableOutput.compact(rows).stream().map(BitTableOutputTest::strip).toList();

    assertEquals(List.of(
        "FAIL 3 components: 1 pass, 0 warn, 1 fail, 0 info, 1 skip",
        "FAIL config-yaml RTP_YAML_PARSE",
        "WARN config-yaml RTP_REGION_UNKNOWN_WORLD x2",
        "INFO config-yaml RTP_NOTE"), lines);
    assertTrue(BitTableOutput.compact(rows).get(1).contains(BitTableOutput.BAD + "FAIL"));
  }

  @Test
  @DisplayName("REQ-API-F-009 chat summarizes components and lists FAIL before WARN and INFO")
  void chatSummaryCountsComponentsAndOmitsPassAndSkipDetails() {
    BitComponent failed = component("failed");
    BitReport failedReport = new BitReport(BitReport.CURRENT_SCHEMA, io.github.dailystruggle.bit.BitHealth.FAILED,
        List.of(finding(Level.ERROR, 3001, "TEST_FAILURE", "failure"),
            finding(Level.WARNING, 3002, "TEST_WARNING", "warning")));
    BitComponent warning = component("warning");
    BitReport warningReport = new BitReport(BitReport.CURRENT_SCHEMA, io.github.dailystruggle.bit.BitHealth.DEGRADED,
        List.of(finding(Level.WARNING, 3003, "TEST_DEGRADED", "warning"),
            finding(Level.WARNING, 3003, "TEST_DEGRADED", "warning-again")));
    BitComponent informational = component("informational");
    BitReport infoReport = new BitReport(BitReport.CURRENT_SCHEMA, io.github.dailystruggle.bit.BitHealth.HEALTHY,
        List.of(finding(Level.INFO, 3004, "TEST_NOTE", "note")));
    BitComponent healthy = component("healthy");
    BitComponent skipped = component("skipped");

    List<BitTable.Row> rows = new ArrayList<>();
    rows.addAll(BitTable.rows(failed, new BitResult(failed, failedReport, 1, 1)));
    rows.addAll(BitTable.rows(warning, new BitResult(warning, warningReport, 1, 1)));
    rows.addAll(BitTable.rows(informational, new BitResult(informational, infoReport, 1, 1)));
    rows.addAll(BitTable.rows(healthy, new BitResult(healthy, BitReport.of(List.of()), 1, 1)));
    rows.addAll(BitTable.rows(skipped, null));

    List<String> lines = BitTableOutput.compact(rows).stream().map(BitTableOutputTest::strip).toList();

    assertEquals(List.of(
        "FAIL 5 components: 1 pass, 1 warn, 1 fail, 1 info, 1 skip",
        "FAIL failed TEST_FAILURE",
        "WARN failed TEST_WARNING",
        "WARN warning TEST_DEGRADED x2",
        "INFO informational TEST_NOTE"), lines);
  }

  @Test
  @DisplayName("REQ-API-F-009 an all-healthy chat report is one PASS summary line")
  void healthyChatHasOnlyOnePassSummary() {
    BitComponent healthy = component("healthy");
    List<BitTable.Row> rows = BitTable.rows(healthy, new BitResult(healthy, BitReport.of(List.of()), 1, 1));

    List<String> lines = BitTableOutput.compact(rows).stream().map(BitTableOutputTest::strip).toList();

    assertEquals(List.of("PASS 1 components: 1 pass, 0 warn, 0 fail, 0 info, 0 skip"), lines);
  }

  @Test
  void consoleCallerGetsNoChatLines() {
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "bystander", null);
    accessor.addPlayer(player);

    BitTableOutput.emit(null, failingRows());

    assertTrue(player.sentMessages.isEmpty());
    assertEquals(2, accessor.logMessages.stream().filter(m -> m.contains(BitTableOutput.PREFIX)).count());
  }
}
