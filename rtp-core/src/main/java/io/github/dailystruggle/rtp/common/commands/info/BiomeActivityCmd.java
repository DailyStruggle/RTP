package io.github.dailystruggle.rtp.common.commands.info;

import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.tools.PlaceholderProvider;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp info biomes} - renders biome-occupancy leaderboard from {@link RTP#biomeActivity}.
 */
public class BiomeActivityCmd extends BaseRTPCmdImpl {
  /** Number of rows shown by default. */
  private static final int MAX_ROWS = 15;
  /** Character width of the textual share bar. */
  private static final int BAR_WIDTH = 20;

  /**
   * Constructs the biome activity subcommand.
   *
   * @param parent the parent command tree (the {@code /rtp} root), or {@code null}
   */
  public BiomeActivityCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "biomes";
  }

  @Override
  public String permission() {
    return "rtp.info";
  }

  /** Routes a raw line through the book tap when active, else to chat. */
  private static void emit(UUID callerId, String line) {
    if (line == null || line.isEmpty()) return;
    Consumer<String> tap = RTP.messageTap.get();
    if (tap != null) {
      tap.accept(PlaceholderProvider.fillPlaceholders(line, callerId));
      return;
    }
    RTP.serverAccessor.sendMessage(callerId, line);
  }

  /** Builds an ASCII share bar of {@link #BAR_WIDTH} characters for {@code share} in [0,1]. */
  private static String bar(double share) {
    int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, share)) * BAR_WIDTH);
    StringBuilder sb = new StringBuilder(BAR_WIDTH);
    for (int i = 0; i < BAR_WIDTH; i++) sb.append(i < filled ? '|' : '.');
    return sb.toString();
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);


    Map<String, Long> snapshot = RTP.biomeActivity.snapshot();
    long total = RTP.biomeActivity.totalSamples();

    if (snapshot.isEmpty() || total <= 0L) {
      emit(callerId, RTP.configs.getConfigValue(CommandMessages.infoBiomeActivityEmpty, "").toString());
      return true;
    }

    String header = RTP.configs.getConfigValue(CommandMessages.infoBiomeActivityHeader, "").toString();
    if (!header.isEmpty()) {
      header =
          header
              .replace("[biomeTotalSamples]", Long.toString(total))
              .replace("[biomeDistinctCount]", Integer.toString(snapshot.size()));
      emit(callerId, header);
    }

    String rowTemplate = RTP.configs.getConfigValue(CommandMessages.infoBiomeActivityRow, "").toString();
    if (rowTemplate.isEmpty()) return true;

    int rank = 0;
    for (Map.Entry<String, Long> e : snapshot.entrySet()) {
      if (rank >= MAX_ROWS) break;
      rank++;
      long samples = e.getValue();
      double share = (double) samples / (double) total;
      String row =
          rowTemplate
              .replace("[biomeRank]", Integer.toString(rank))
              .replace("[biomeName]", e.getKey())
              .replace("[biomeSamples]", Long.toString(samples))
              .replace("[biomePercent]", String.format(Locale.ROOT, "%.1f", share * 100.0))
              .replace("[biomeBar]", bar(share));
      emit(callerId, row);
    }
    return true;
  }
}
