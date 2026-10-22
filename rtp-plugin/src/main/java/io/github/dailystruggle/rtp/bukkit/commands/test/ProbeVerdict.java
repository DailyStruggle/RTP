package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitCodes;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Turns each asynchronous probe completion into one cached BIT component result. */
final class ProbeVerdict {
  private ProbeVerdict() {}

  static CompletableFuture<BitResult> record(
      BitComponent component, CompletableFuture<BitReport> report, long timeoutMillis) {
    long timeout = Math.max(1L, timeoutMillis);
    return report.orTimeout(timeout, TimeUnit.MILLISECONDS)
        .handle(
            (value, failure) -> {
              BitReport resolved = value;
              if (failure != null || value == null) {
                Throwable cause = unwrap(failure);
                BitCode code = cause instanceof TimeoutException
                    ? BitCodes.COMPONENT_TIMEOUT
                    : BitCodes.COMPONENT_THROWN;
                resolved = failureReport(component, code, message(cause, value == null));
              }
              BitReport finalReport = resolved;
              return RtpBit.RUNNER.run(component.withCheck(() -> finalReport), Runnable::run);
            });
  }

  static CompletableFuture<BitResult> runAsync(BitComponent component, Callable<BitReport> check) {
    CompletableFuture<BitReport> report = new CompletableFuture<>();
    CompletableFuture<BitResult> result = record(component, report, component.timeoutMillis());
    if (RTPRuntime.scheduler() == null) {
      report.completeExceptionally(new IllegalStateException("RTP.scheduler is unavailable"));
      return result;
    }
    try {
      RTPRuntime.scheduler()
          .runTaskAsynchronously(
              () -> {
                if (report.isDone()) return;
                try {
                  report.complete(check.call());
                } catch (Throwable failure) {
                  report.completeExceptionally(failure);
                }
              });
    } catch (Throwable failure) {
      report.completeExceptionally(failure);
    }
    return result;
  }

  static BitReport failureReport(BitComponent component, BitCode code, String detail) {
    return BitReport.of(
        List.of(
            BitFinding.of(
                code,
                component.id(),
                "",
                detail,
                "probe completes within its contract",
                code.remediation())));
  }

  private static Throwable unwrap(Throwable failure) {
    Throwable cause = failure;
    while ((cause instanceof CompletionException || cause instanceof ExecutionException)
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }

  private static String message(Throwable failure, boolean nullReport) {
    if (failure == null) return nullReport ? "Probe returned a null report." : "Probe failed.";
    if (failure.getMessage() == null || failure.getMessage().isBlank()) {
      return failure.getClass().getSimpleName();
    }
    return failure.getClass().getSimpleName() + ": " + failure.getMessage();
  }
}
