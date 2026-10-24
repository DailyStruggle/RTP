package io.github.dailystruggle.rtp.common.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test config-set} - runtime self-test verifying that
 * {@link ConfigParser#set(String, Object)} updates live in-memory state.
 * Targets {@link PerformanceKeys#viewDistanceSelect} with mutate-verify-restore cycle.
 * Complies with REQ-RTP-S-004 (no silent returns) and REQ-RTP-S-005 (async execution).
 */
public class TestConfigSetCmd extends BaseRTPCmdImpl {

  public TestConfigSetCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "config-set";
  }

  @Override
  public String permission() {
    return "rtp.test.admin";
  }

  @Override
  public String description() {
    return "round-trip a config value via ConfigParser.set/get and verify it persisted in memory";
  }

  @Override
  @SuppressWarnings("java:S3516") // Method returns true on async probe dispatch
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    if (RTPRuntime.scheduler() == null || ActiveConfigs.get() == null) {
      String msg = "&c[RTP test/config-set] core not yet loaded (scheduler or configs null)";
      report(callerId, msg, Level.WARNING, null);
      return false;
    }

    RTPRuntime.scheduler().runTaskAsynchronously(() -> runProbe(callerId));
    return true;
  }

  @SuppressWarnings("unchecked")
  private void runProbe(UUID callerId) {
    ConfigParser<PerformanceKeys> parser =
        (ConfigParser<PerformanceKeys>) ActiveConfigs.get().getParser(PerformanceKeys.class);
    if (parser == null) {
      report(callerId, "&c[RTP test/config-set] PerformanceKeys parser not found", Level.WARNING, null);
      return;
    }

    Object originalRaw = parser.getConfigValue(PerformanceKeys.viewDistanceSelect, 5L);
    long original;
    try {
      original = ((Number) originalRaw).longValue();
    } catch (ClassCastException cce) {
      try {
        original = Long.parseLong(String.valueOf(originalRaw));
      } catch (NumberFormatException nfe) {
        report(
            callerId,
            "&c[RTP test/config-set] unexpected viewDistanceSelect type: " + originalRaw,
            Level.WARNING,
            nfe);
        return;
      }
    }

    long probe = original + 1L;
    report(
        callerId,
        "[RTP test/config-set] begin viewDistanceSelect: original=" + original + " probe=" + probe,
        Level.INFO,
        null);

    boolean ok = false;
    try {
      parser.set(PerformanceKeys.viewDistanceSelect.name(), probe);

      Object readBackRaw = parser.getConfigValue(PerformanceKeys.viewDistanceSelect, -1L);
      long readBack;
      try {
        readBack = ((Number) readBackRaw).longValue();
      } catch (ClassCastException cce) {
        readBack = Long.parseLong(String.valueOf(readBackRaw));
      }

      if (readBack == probe) {
        ok = true;
        report(
            callerId,
            "[RTP test/config-set] PASS: read-back matches probe (" + readBack + ")",
            Level.INFO,
            null);
      } else {
        report(
            callerId,
            "&c[RTP test/config-set] FAIL: probe=" + probe + " readBack=" + readBack,
            Level.WARNING,
            null);
      }
    } catch (Throwable t) {
      report(
          callerId,
          "&c[RTP test/config-set] FAIL: exception during set/get: " + t.getMessage(),
          Level.WARNING,
          t);
    } finally {
      try {
        parser.set(PerformanceKeys.viewDistanceSelect.name(), original);
        report(
            callerId,
            "[RTP test/config-set] restored original=" + original + " (passed=" + ok + ")",
            Level.INFO,
            null);
      } catch (Throwable restoreErr) {
        report(
            callerId,
            "&c[RTP test/config-set] WARNING: failed to restore original viewDistanceSelect="
                + original,
            Level.WARNING,
            restoreErr);
      }
    }
  }

  private void report(UUID callerId, String msg, Level level, Throwable t) {
    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTPRuntime.serverAccessor().sendMessage(callerId, msg);
    }
    if (t != null) {
      RTPRuntime.log(level, msg, t);
    } else {
      RTPRuntime.log(level, msg);
    }
  }
}
