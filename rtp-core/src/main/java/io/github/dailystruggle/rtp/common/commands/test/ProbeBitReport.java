package io.github.dailystruggle.rtp.common.commands.test;

import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitOutput;
import io.github.dailystruggle.bit.BitOutputs;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.bit.BitTable;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/** Shared adapter for on-request test results and their machine-readable BIT representation. */
public final class ProbeBitReport {
  private ProbeBitReport() {}

  public static Builder forProbe(String probe) {
    return new Builder(probe);
  }

  public static BitReport last(String probe) {
    BitResult result = RtpBit.RUNNER.last(probe);
    return result == null ? null : result.report();
  }

  public static void emitJson(UUID callerId, BitResult result) {
    Objects.requireNonNull(result, "result");
    BitReport report = result.report();
    BitOutput output = BitOutputs.json("[RTP bit/test.");
    output.write(
        result,
        line -> {
          Level level =
              switch (report.health()) {
                case FAILED, DEGRADED -> Level.WARNING;
                case HEALTHY -> Level.INFO;
              };
          RTPRuntime.log(level, line);
          if (callerId != null
              && !callerId.equals(RTPAPI.serverId)
              && RTPRuntime.serverAccessor() != null) {
            RTPRuntime.serverAccessor().sendMessage(callerId, line);
          }
        });
  }

  public static boolean isJson(Map<String, List<String>> parameterValues) {
    if (parameterValues == null) return false;
    List<String> values = parameterValues.get("json");
    return values != null && values.stream().anyMatch(value -> Boolean.parseBoolean(value));
  }

  public static void emit(
      UUID callerId, Map<String, List<String>> parameterValues,
      BitComponent component, BitResult result) {
    emit(callerId, isJson(parameterValues), component, result);
  }

  public static void emit(
      UUID callerId, Map<String, List<String>> parameterValues, List<BitResult> results) {
    emit(callerId, isJson(parameterValues), results);
  }

  public static void emit(
      UUID callerId, boolean json, BitComponent component, BitResult result) {
    Objects.requireNonNull(component, "component");
    Objects.requireNonNull(result, "result");
    if (json) {
      emitJson(callerId, result);
    } else {
      BitTableOutput.emit(callerId, BitTable.rows(component, result));
    }
  }

  public static void emit(UUID callerId, boolean json, List<BitResult> results) {
    Objects.requireNonNull(results, "results");
    if (!json) {
      BitTableOutput.emit(callerId, BitTableOutput.rows(results));
      return;
    }
    BitReport report = RtpBit.RUNNER.aggregate(results);
    BitComponent component =
        new BitComponent("all", "test.all", BitTier.IBIT, BitSideEffect.SAFE, 1, () -> report);
    long durationMillis = results.stream().mapToLong(BitResult::durationMillis).sum();
    long completedAt =
        results.stream().mapToLong(BitResult::completedAtMillis).max().orElse(System.currentTimeMillis());
    emitJson(callerId, new BitResult(component, report, durationMillis, completedAt));
  }

  public static final class Builder {
    private final String probe;
    private final List<BitFinding> findings = new ArrayList<>();

    private Builder(String probe) {
      this.probe = Objects.requireNonNull(probe, "probe");
    }

    public Builder check(BitCode code, boolean ok, String path, String detail) {
      if (!ok) {
        findings.add(BitFinding.of(
            code,
            "rtp test " + probe,
            path,
            detail,
            "probe check passes",
            code.remediation()));
      }
      return this;
    }

    public Builder note(BitCode code, String path, String detail) {
      findings.add(
          BitFinding.of(
              Objects.requireNonNull(code, "code"),
              "rtp test " + probe,
              path,
              detail,
              "informational condition",
              code.remediation()));
      return this;
    }

    public BitReport build() {
      return BitReport.of(findings);
    }
  }
}
