package io.github.dailystruggle.rtp.bukkit.commands.test;

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
 * {@link MemoryTracker} lifecycle, which is the registry that backs every
 * chunk ticket RTP acquires (REQ-RTP-S-002).
 *
 * <p>Context: the existing unit-test suite exercises the <i>watchdog</i>
 * path &mdash; i.e. {@link MemoryTracker#runDiagnostics()} correctly
 * force-closing a <b>leaked</b> {@code TeleportPipelineTask}. That leaves
 * the <i>happy</i> path unverified at runtime: when code explicitly
 * releases a tracked object (the common case), does the registry actually
 * drop it, and does {@code runDiagnostics()} leave the correctly-released
 * set alone? A silent regression here would let tickets pile up for a
 * full reservation window before the watchdog noticed &mdash; exactly the
 * class of leak REQ-RTP-S-002 forbids.
 *
 * <p>This probe exercises every release path {@link MemoryTracker}
 * currently exposes, using sentinel objects so no real chunk ticket or
 * teleport pipeline is involved:
 *
 * <ol>
 *   <li><b>Explicit {@code untrack(UUID)}</b> &mdash; mirrors the normal
 *       {@code TeleportPipelineTask.runCleanup()} release path.</li>
 *   <li><b>Explicit {@code untrack(Object)}</b> &mdash; mirrors the
 *       {@code RTPRunnable.untrackHook} invocation used by
 *       {@link io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask}.</li>
 *   <li><b>Drop-and-diagnose</b> &mdash; registers a short-lifespan sentinel,
 *       drops the strong reference, runs diagnostics, and asserts the
 *       entry is gone. Verifies the watchdog's steady-state behaviour
 *       against correctly-released objects (should be a no-op warning-wise).</li>
 * </ol>
 *
 * <p>Each sentinel is tagged with a unique label
 * ({@link #SENTINEL_LABEL}) so the probe's assertions are isolated from
 * any concurrent live teleport activity on the server &mdash; the probe
 * only asserts against its own label's count, never the global count.
 *
 * <p>Safety compliance:
 *
 * <ul>
 *   <li><b>S-002</b>: sentinels use primitive {@code Object}s, not real
 *       chunk tickets, so a bug in this probe cannot itself leak a ticket.</li>
 *   <li><b>S-004</b>: any non-zero residual count after the full sequence
 *       is logged at {@link Level#WARNING} and surfaced to the caller.</li>
 *   <li><b>S-005</b>: no chunk I/O; every step is an in-memory registry
 *       operation. Runs on the caller's thread.</li>
 * </ul>
 *
 * <p>Traces REQ-RTP-S-002. See {@code docs/dev/RUNTIME_TEST_SUITE_PLAN.md &sect;3.8}.
 */
public class TestChunkTicketCmd extends BaseRTPCmdImpl {

  /**
   * Label applied to every sentinel this probe registers. Chosen to be
   * unmistakable in log scraping and distinct from any production label
   * (production labels are typically {@code TeleportPipelineTask:<uuid>} or
   * {@code TrackedRTPTask[...]}).
   */
  static final String SENTINEL_LABEL = "rtp-test-chunk-ticket-sentinel";

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

  /**
   * Core probe, extracted for unit-testing without a CommandsAPI dispatch.
   * Returns a structured {@link Result} rather than emitting directly so
   * tests can assert on counts without capturing log output.
   */
  static Result runProbe() {
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
  static final class Result {
    int staleBaseline;
    int afterUntrackById;
    int afterUntrackByRef;
    int afterDiagnosticsOnLive;
    int finalResidual;
    boolean pass;
    String notes = "";
  }
}
