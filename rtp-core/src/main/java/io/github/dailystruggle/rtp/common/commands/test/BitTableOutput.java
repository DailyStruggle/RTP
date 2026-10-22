package io.github.dailystruggle.rtp.common.commands.test;

import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitTable;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Colored aligned-CSV rendering of BIT results for console and chat.
 *
 * <p>Developer diagnostics, not operator copy: colors and font are fixed here rather than in
 * {@code messages.yml}. Stripping the {@code &} codes and {@link #PREFIX} leaves valid CSV.
 * Chat lines are wrapped in the fixed-width {@link #CHAT_FONT} so columns align in-game (1.16+);
 * older clients and consoles ignore or never see the font.
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

  /** Logs every line and sends it, font-wrapped, to a non-console caller. */
  static void emit(UUID callerId, List<BitTable.Row> rows) {
    Level level = rows.stream().anyMatch(row -> isProblem(row.tone())) ? Level.WARNING : Level.INFO;
    boolean chat = callerId != null
        && !callerId.equals(RTPAPI.serverId)
        && RTPRuntime.serverAccessor() != null;
    for (String line : render(rows)) {
      RTPRuntime.log(level, line);
      if (chat) RTPRuntime.serverAccessor().sendMessage(callerId, chatLine(line));
    }
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
