package io.github.dailystruggle.rtp.common.commands.test;

import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitTable;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * BIT result rendering: full colored aligned CSV for the log, compact status lines for chat.
 *
 * <p>Developer diagnostics, not operator copy: colors and font are fixed here rather than in
 * {@code messages.yml}. Stripping the {@code &} codes and {@link #PREFIX} from log lines leaves
 * valid CSV. A full row overflows the chat width, so chat gets one summary followed by distinct
 * FAIL/WARN/INFO findings; PASS and SKIP components are counted but not listed. Path, message
 * and the other columns stay in the log and JSON, and each code is explained in the BIT catalog.
 * Chat lines use {@link #CHAT_FONT} where the client renders it; imperfect alignment elsewhere
 * is accepted.
 */
public final class BitTableOutput {
  /** Constant-width prefix; never matches the legacy {@code [RTP test/<probe>]} acceptance greps. */
  public static final String PREFIX = "[RTP bit] ";
  public static final String CHAT_FONT = "minecraft:uniform";

  static final String HEADER = "&7";
  static final String TEXT = "&f";
  static final String CODE = "&b";
  static final String PATH = "&e";
  static final String GOOD = "&a";
  static final String WARN = "&6";
  static final String BAD = "&c";
  static final String IDLE = "&8";

  private BitTableOutput() {}

  /** Rows for each component, using its cached result or {@code NOT_RUN}. */
  static List<BitTable.Row> rows(
      List<BitComponent> components, Function<String, BitResult> lastResult) {
    List<BitTable.Row> rows = new ArrayList<>();
    for (BitComponent component : components) {
      rows.addAll(BitTable.rows(component, lastResult.apply(component.id())));
    }
    return rows;
  }

  /** Rows for completed results, preserving the component attached to each result. */
  static List<BitTable.Row> rows(List<BitResult> results) {
    List<BitTable.Row> rows = new ArrayList<>();
    for (BitResult result : results) rows.addAll(BitTable.rows(result.component(), result));
    return rows;
  }

  /** Colored lines, header first, each starting with {@link #PREFIX}. */
  static List<String> render(List<BitTable.Row> rows) {
    List<String> lines = BitTable.render(rows, BitTable.COLUMNS, BitTableOutput::decorate);
    List<String> out = new ArrayList<>(lines.size());
    for (String line : lines) out.add(HEADER + PREFIX + line);
    return out;
  }

  /**
   * A component-count summary followed by distinct FAIL/WARN/INFO findings; repeats collapse to
   * {@code xN}. PASS and SKIP components appear only in the summary.
   */
  static List<String> compact(List<BitTable.Row> rows) {
    Map<String, String> components = new LinkedHashMap<>();
    Map<List<String>, Integer> findings = new LinkedHashMap<>();
    for (BitTable.Row row : rows) addRow(row, components, findings);
    int pass = count(components, "PASS");
    int warn = count(components, "WARN");
    int fail = count(components, "FAIL");
    int info = count(components, "INFO");
    int skip = count(components, "SKIP");
    String overall = components.values().stream().reduce(BitTableOutput::worseStatus).orElse("PASS");

    List<String> out = new ArrayList<>(findings.size() + 1);
    out.add(HEADER + PREFIX + statusTone(overall) + overall + TEXT + " " + components.size()
        + " components: " + pass + " pass, " + warn + " warn, " + fail + " fail, "
        + info + " info, " + skip + " skip");
    for (String status : List.of("FAIL", "WARN", "INFO")) appendFindings(out, findings, status);
    return out;
  }

  private static void addRow(
      BitTable.Row row, Map<String, String> components, Map<List<String>, Integer> findings) {
    String component = row.cells().getOrDefault(BitTable.COMPONENT, "");
    String code = row.cells().getOrDefault(BitTable.CODE, "");
    String healthStatus = status(row.cells().getOrDefault(BitTable.HEALTH, ""));
    String rowStatus = status(row.tone());
    components.merge(component, worseStatus(healthStatus, rowStatus), BitTableOutput::worseStatus);

    String findingStatus = code.isEmpty() ? healthStatus : rowStatus;
    if (!"PASS".equals(findingStatus) && !"SKIP".equals(findingStatus)) {
      findings.merge(List.of(findingStatus, component, code), 1, Integer::sum);
    }
  }

  private static void appendFindings(
      List<String> out, Map<List<String>, Integer> findings, String status) {
    for (Map.Entry<List<String>, Integer> entry : findings.entrySet()) {
      if (!status.equals(entry.getKey().get(0))) continue;
      String component = entry.getKey().get(1);
      String code = entry.getKey().get(2);
      StringBuilder line = new StringBuilder(HEADER).append(PREFIX)
          .append(statusTone(status)).append(status).append(' ')
          .append(TEXT).append(component);
      if (!code.isEmpty()) line.append(' ').append(CODE).append(code);
      if (entry.getValue() > 1) line.append(' ').append(HEADER).append('x').append(entry.getValue());
      out.add(line.toString());
    }
  }

  private static int count(Map<String, String> components, String status) {
    return (int) components.values().stream().filter(status::equals).count();
  }

  private static String worseStatus(String first, String second) {
    return statusRank(second) > statusRank(first) ? second : first;
  }

  private static int statusRank(String status) {
    return switch (status) {
      case "FAIL" -> 4;
      case "WARN" -> 3;
      case "INFO" -> 2;
      case "PASS" -> 1;
      default -> 0;
    };
  }

  /** Logs the full table and sends the compact lines, font-wrapped, to a non-console caller. */
  static void emit(UUID callerId, List<BitTable.Row> rows) {
    Level level = rows.stream().anyMatch(row -> isProblem(row.tone())) ? Level.WARNING : Level.INFO;
    for (String line : render(rows)) RTPRuntime.log(level, line);
    boolean chat = callerId != null
        && !callerId.equals(RTPAPI.serverId)
        && RTPRuntime.serverAccessor() != null;
    if (!chat) return;
    for (String line : compact(rows)) {
      RTPRuntime.serverAccessor().sendMessage(callerId, chatLine(line));
    }
  }

  /** Maps a row tone (health or severity name) to its chat status token. */
  static String status(String tone) {
    return switch (tone) {
      case "HEALTHY" -> "PASS";
      case "FAILED", "ERROR" -> "FAIL";
      case "DEGRADED", "WARNING" -> "WARN";
      case "NOT_RUN" -> "SKIP";
      default -> "INFO";
    };
  }

  private static String statusTone(String status) {
    return switch (status) {
      case "PASS" -> GOOD;
      case "FAIL" -> BAD;
      case "WARN" -> WARN;
      case "INFO" -> TEXT;
      default -> IDLE;
    };
  }

  static String chatLine(String line) {
    return "<font:" + CHAT_FONT + ">" + line + "</font>";
  }

  private static String decorate(String column, String cell, BitTable.Row row) {
    if (row == null) return HEADER + cell;
    return switch (column) {
      case BitTable.HEALTH, BitTable.SEVERITY -> tone(cell) + cell;
      case BitTable.CODE, BitTable.ID -> CODE + cell;
      case BitTable.PATH -> PATH + cell;
      default -> TEXT + cell;
    };
  }

  private static String tone(String value) {
    return switch (value) {
      case "FAILED", "ERROR" -> BAD;
      case "DEGRADED", "WARNING" -> WARN;
      case "HEALTHY", "INFO" -> GOOD;
      default -> IDLE;
    };
  }

  private static boolean isProblem(String tone) {
    return "FAILED".equals(tone) || "DEGRADED".equals(tone)
        || "ERROR".equals(tone) || "WARNING".equals(tone);
  }
}
