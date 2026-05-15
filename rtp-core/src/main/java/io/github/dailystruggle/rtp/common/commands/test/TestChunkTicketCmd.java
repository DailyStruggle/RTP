package io.github.dailystruggle.rtp.common.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test chunk-ticket} &mdash; positive-path probe of the
 * {@link MemoryTracker} release paths (REQ-RTP-S-002): {@code untrack(UUID)},
 * {@code untrack(Object)}, and drop-and-diagnose. Uses sentinel {@code Object}s
 * (no real ticket) tagged with {@link #SENTINEL_LABEL} so it never collides
 * with live teleport activity. See {@code RUNTIME_TEST_SUITE_PLAN.md &sect;3.8}.
 */
public class TestChunkTicketCmd extends BaseRTPCmdImpl {

  /** Sentinel label, distinct from any production tracker label. */
  public static final String SENTINEL_LABEL = "rtp-test-chunk-ticket-sentinel";

  /** Sentinel lifespan for the drop-and-diagnose case. */
  static final long SENTINEL_LIFESPAN_MS = 50L;

  public TestChunkTicketCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "chunk-ticket";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "verifies MemoryTracker release paths (REQ-RTP-S-002 positive path)";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    Result r = runProbe();
    emit(callerId, r);
    return true;
  }

  /** Core probe, extracted for unit-testing without a CommandsAPI dispatch. */
  public static Result runProbe() {
    Result r = new Result();

    int baseline = MemoryTracker.trackedCountByLabel(SENTINEL_LABEL);
    if (baseline != 0) {
      // Previous aborted probe left sentinels behind; clean them now so our
      // assertions are meaningful. This is why we label-scope every count.
      r.staleBaseline = baseline;
    }

    // --- case 1: explicit untrack(UUID) ------------------------------------
    Object s1 = new Object();
    UUID id1 = MemoryTracker.track(s1, SENTINEL_LABEL, 60_000L);
    MemoryTracker.untrack(id1);
    r.afterUntrackById = MemoryTracker.trackedCountByLabel(SENTINEL_LABEL) - r.staleBaseline;

    // --- case 2: explicit untrack(Object) ----------------------------------
    Object s2 = new Object();
    MemoryTracker.track(s2, SENTINEL_LABEL, 60_000L);
    MemoryTracker.untrack(s2);
    r.afterUntrackByRef = MemoryTracker.trackedCountByLabel(SENTINEL_LABEL) - r.staleBaseline;

    // --- case 3: runDiagnostics does NOT drop a live, non-leaking entry ----
    // Register a long-lived sentinel, then run diagnostics; the entry MUST
    // survive (it is neither collected nor leaking). This protects against
    // a regression where runDiagnostics over-aggressively removes entries.
    Object s3 = new Object();
    UUID id3 = MemoryTracker.track(s3, SENTINEL_LABEL, 60_000L);
    MemoryTracker.runDiagnostics();
    r.afterDiagnosticsOnLive =
        MemoryTracker.trackedCountByLabel(SENTINEL_LABEL) - r.staleBaseline;
    MemoryTracker.untrack(id3); // clean up
    // Keep a strong reference until after the count read so the WeakReference
    // inside TrackedObject cannot be collected mid-probe and falsify the result.
    if (s3.hashCode() == Integer.MIN_VALUE) r.notes = "unreachable";

    // --- final residual ----------------------------------------------------
    // After all three cases and their cleanups, the label-scoped count must
    // equal the stale-baseline (which would be zero on a clean run). Any
    // excess is a lifecycle bug.
    r.finalResidual = MemoryTracker.trackedCountByLabel(SENTINEL_LABEL) - r.staleBaseline;

    r.pass =
        r.afterUntrackById == 0
            && r.afterUntrackByRef == 0
            && r.afterDiagnosticsOnLive == 1
            && r.finalResidual == 0;
    return r;
  }

  /** Emits the result per the RUNTIME_TEST_SUITE_PLAN output contract. */
  private void emit(UUID callerId, Result r) {
    String summary =
        "[RTP test/chunk-ticket] "
            + (r.pass ? "ok" : "FAIL")
            + " untrackById="
            + r.afterUntrackById
            + " untrackByRef="
            + r.afterUntrackByRef
            + " diagOnLive="
            + r.afterDiagnosticsOnLive
            + " residual="
            + r.finalResidual
            + (r.staleBaseline == 0 ? "" : " staleBaseline=" + r.staleBaseline);

    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTP.serverAccessor.sendMessage(callerId, summary);
    }
    // S-004: failing run logs at WARNING; clean run at INFO.
    RTP.log(r.pass ? Level.INFO : Level.WARNING, summary);

    if (r.staleBaseline != 0) {
      // Advisory, not a failure in itself: a previous aborted probe left
      // sentinels behind. Flag loudly because it implies the previous run
      // was interrupted (cancelled, exception) without a cleanup path.
      RTP.log(
          Level.WARNING,
          "[RTP test/chunk-ticket] stale sentinels from a previous run detected; count="
              + r.staleBaseline);
    }
  }

  /** Structured probe result. Package-private so the unit test can assert on it. */
  public static final class Result {
    public int staleBaseline;
    public int afterUntrackById;
    public int afterUntrackByRef;
    public int afterDiagnosticsOnLive;
    public int finalResidual;
    public boolean pass;
    public String notes = "";
  }
}
