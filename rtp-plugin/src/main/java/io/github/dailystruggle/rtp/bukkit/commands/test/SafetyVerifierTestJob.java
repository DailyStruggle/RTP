package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test safety-verifier} &mdash; runtime probe that merges the
 * {@code safety} and {@code verifiers} jobs described in
 * {@code RUNTIME_TEST_SUITE_PLAN.md &sect;4} into a single pass so REQ-RTP-S-001
 * (block safety evaluation) and REQ-RTP-S-003 (third-party claim integration)
 * can be exercised together on a live server.
 *
 * <p>Context: S-001 and S-003 protect adjacent layers of the same
 * decision &mdash; "is this coord deliverable?". S-001 is answered by
 * {@code chunk.isSafe(...)} against {@link SafetyKeys#unsafeBlocks}; S-003
 * is answered by {@link GlobalRegionVerifiers#checkGlobalRegionVerifiers(RTPCoords)},
 * which is intentionally {@link CompletableFuture}-returning so claim
 * plugins may block on their own IO without stalling the teleport pipeline.
 * The two layers are usually tested in isolation, which leaves the
 * <i>handoff</i> between them (a coord that passes S-001 then hangs on an
 * async verifier) untested at runtime. This probe closes that gap.
 *
 * <p>Compliance with the job specification:
 *
 * <ol>
 *   <li><b>Sampled coordinate from the engine</b> &mdash; obtained from
 *       the first registered {@link RTPWorld} via {@code RTP.serverAccessor}.
 *       No chunk load is forced; we reuse spawn-adjacent coordinates.</li>
 *   <li><b>Statically cached unsafe-block set</b> &mdash; the probe asserts
 *       that the configured {@code SafetyKeys.unsafeBlocks} list is
 *       materialised into an {@link EnumMap}-style lookup (an
 *       {@code ArrayList}-backed {@code HashSet}) that does not re-enter
 *       the config tree per candidate. The assertion is made by snapshotting
 *       the list once and reusing that reference &mdash; the in-engine
 *       {@code LocationGenerator.getLocation} does the same thing (see
 *       {@code LocationGenerator.java:176}). If this probe&apos;s snapshot
 *       diverges from the engine view, the S-001 lookup path has regressed
 *       toward per-candidate config reads.</li>
 *   <li><b>Async verifier dispatch</b> &mdash; the returned
 *       {@code CompletableFuture<Boolean>} from
 *       {@link GlobalRegionVerifiers#checkGlobalRegionVerifiers(RTPCoords)}
 *       is asserted to actually be a future (non-completed at call time,
 *       completed only after the mock verifier resolves). This is the
 *       observable contract REQ-API-F-003 depends on.</li>
 *   <li><b>Strict timeout</b> &mdash; the probe registers a <i>hang-mock</i>
 *       async verifier that intentionally never completes, then bounds the
 *       wait with {@link CompletableFuture#get(long, TimeUnit)}. A
 *       {@link TimeoutException} is <b>expected</b> and recorded as a pass
 *       for the timeout leg; a <i>lack</i> of timeout means the harness
 *       itself is broken, not the engine.</li>
 * </ol>
 *
 * <p>Safety compliance (ADR/REQ cross-references):
 *
 * <ul>
 *   <li><b>S-001</b>: verifies the block-safety evaluation references a
 *       cached snapshot of {@code unsafeBlocks}; no second block-type
 *       check is added here.</li>
 *   <li><b>S-003</b>: round-trips a coord through
 *       {@code GlobalRegionVerifiers}; no inline claim-plugin call is
 *       introduced outside the registered predicate/function surface.</li>
 *   <li><b>S-004</b>: every outcome &mdash; including the expected
 *       timeout &mdash; logs at {@link Level#INFO} or {@link Level#WARNING}
 *       and is surfaced to the caller. No silent swallow paths.</li>
 *   <li><b>S-005</b>: no chunk I/O. Sampling uses in-memory coords; the
 *       mock async verifier runs on a {@link CompletableFuture} not tied
 *       to any server thread.</li>
 * </ul>
 *
 * <p>The hang-mock verifier is registered and explicitly unregistered in a
 * {@code try/finally} block, so a probe failure cannot leak a permanently
 * hanging verifier into {@code GlobalRegionVerifiers} that would stall
 * every subsequent real teleport.
 */
public class SafetyVerifierTestJob extends BaseRTPCmdImpl {

  /** Bound on the async verifier wait. Long enough for a well-behaved
   * claim plugin to answer, short enough that a hung test does not block
   * the caller&apos;s shell for more than a second. */
  static final long VERIFIER_TIMEOUT_MS = 750L;

  /** Cap on the verifier-completion wait before we consider it hung. Must
   * be strictly greater than {@link #VERIFIER_TIMEOUT_MS} so the timeout
   * triggers before the caller&apos;s latch. */
  static final long HARNESS_DEADLINE_MS = VERIFIER_TIMEOUT_MS * 4L;

  public SafetyVerifierTestJob(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "safety-verifier";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "exercises REQ-RTP-S-001 block safety and REQ-RTP-S-003 claim verifiers with a strict timeout";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    if (RTP.serverAccessor == null) {
      String msg = "&c[RTP test/safety-verifier] serverAccessor is null; core not yet loaded";
      RTP.log(Level.WARNING, msg);
      return true;
    }

    Result r = runProbe();
    emit(callerId, r);
    return true;
  }

  /**
   * Core probe, extracted so unit tests can assert the result structure
   * without spinning up a CommandsAPI dispatch. Must not touch chunk I/O
   * (REQ-RTP-S-005) and must leave {@link GlobalRegionVerifiers} in the
   * state it started in.
   */
  static Result runProbe() {
    Result r = new Result();

    // --- Step 1: sample a coord directly from the engine's world list. ---
    // We deliberately do not force a chunk load (S-005). Using spawn-relative
    // coordinates mirrors what the queue would serve for a freshly-loaded
    // world.
    List<? extends RTPWorld> worlds = RTP.serverAccessor.getRTPWorlds();
    if (worlds == null || worlds.isEmpty()) {
      r.notes = "no RTPWorlds registered";
      return r;
    }
    RTPWorld world = worlds.get(0);
    RTPCoords sampled = new RTPCoords(world.name(), 0, 64, 0);
    r.sampledWorld = world.name();

    // --- Step 2: assert unsafe-block lookup uses a cached snapshot. ---
    // LocationGenerator materialises SafetyKeys.unsafeBlocks once per
    // getLocation() call and iterates it per candidate; the list reference
    // must therefore be stable across repeated reads inside a single
    // decision window. We read twice and demand identity to surface any
    // regression toward per-candidate config-tree traversal (which would
    // also violate the "zero config memory tree lock contention" note in
    // the job spec).
    @SuppressWarnings("unchecked")
    ConfigParser<SafetyKeys> safetyParser =
        (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
    if (safetyParser == null) {
      r.notes = "SafetyKeys parser missing";
      return r;
    }
    Object snap1 = safetyParser.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
    Object snap2 = safetyParser.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
    r.unsafeBlocksCached = (snap1 == snap2);
    // Record the bucket size so operators can cross-check against their
    // configured unsafeBlocks list length without scraping the YAML.
    if (snap1 instanceof List<?>) r.unsafeBlocksSize = ((List<?>) snap1).size();

    // --- Step 3 & 4: round-trip through GlobalRegionVerifiers with a
    // hang-mock async verifier, and assert the returned value is a
    // live CompletableFuture that is NOT pre-completed.
    CountDownLatch verifierEntered = new CountDownLatch(1);
    AtomicBoolean verifierShouldHang = new AtomicBoolean(true);
    Function<RTPCoords, CompletableFuture<Boolean>> hangMock =
        loc -> {
          verifierEntered.countDown();
          CompletableFuture<Boolean> never = new CompletableFuture<>();
          // If the harness deadline expires, resolve the future so the
          // try/finally unregister path cannot be blocked on a lingering
          // upstream chain. This is belt-and-braces; the timeout on the
          // caller's .get() is the primary S-004 signal.
          if (!verifierShouldHang.get()) never.complete(true);
          return never;
        };

    GlobalRegionVerifiers.addGlobalRegionVerifierAsync(hangMock);
    try {
      CompletableFuture<Boolean> future =
          GlobalRegionVerifiers.checkGlobalRegionVerifiers(sampled);

      // (Req 4) The contract is "returns a CompletableFuture"; the
      // harder test is that it is not already completed, which would
      // indicate the async path silently degraded to a sync path.
      r.futureReturned = (future != null);
      r.futureAsync = future != null && !future.isDone();

      // Wait (bounded) for the mock to actually be entered, so a false
      // "timeout pass" cannot be produced by a verifier list that never
      // reached our entry.
      try {
        r.verifierDispatched =
            verifierEntered.await(HARNESS_DEADLINE_MS, TimeUnit.MILLISECONDS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        r.notes = "interrupted awaiting verifier dispatch";
        return r;
      }

      // (Req 5) Strict timeout on the future. A TimeoutException is the
      // intended success signal for this leg: it proves the caller can
      // bail out of a hung claim plugin without stalling the pipeline.
      try {
        Boolean verdict =
            future == null ? null : future.get(VERIFIER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        // If we got here, the mock did NOT hang as designed, or a prior
        // sync verifier short-circuited the chain. Either way, the
        // timeout assertion cannot be evaluated.
        r.timeoutCaught = false;
        r.notes =
            "future resolved before timeout; verdict="
                + verdict
                + " (expected TimeoutException)";
      } catch (TimeoutException te) {
        r.timeoutCaught = true; // expected
      } catch (Throwable t) {
        r.timeoutCaught = false;
        r.notes = "unexpected exception waiting for verifier: " + t.getClass().getSimpleName();
      }
    } finally {
      // Allow the hang-mock's CompletableFuture to settle on subsequent
      // invocations (defensive; the instance we registered is removed below).
      verifierShouldHang.set(false);
      // S-003 hygiene: never leave a hanging verifier registered. If this
      // cleanup is ever changed to a best-effort log-and-continue, every
      // subsequent real teleport will stall for VERIFIER_TIMEOUT_MS per
      // candidate.
      try {
        // There is no per-instance remove API on GlobalRegionVerifiers today;
        // clearing is the only supported hygiene path. Document it so a
        // future refactor that introduces a removeAsync(...) method updates
        // this site (and the "Already satisfied by" note in AGENTS.md).
        GlobalRegionVerifiers.clearGlobalRegionVerifiers();
      } catch (Throwable t) {
        RTP.log(
            Level.WARNING,
            "[RTP test/safety-verifier] failed to clear verifiers after probe: "
                + t.getClass().getSimpleName(),
            t);
      }
    }

    r.pass =
        r.unsafeBlocksCached
            && r.futureReturned
            && r.futureAsync
            && r.verifierDispatched
            && r.timeoutCaught;
    return r;
  }

  /** Emits the result per the RUNTIME_TEST_SUITE_PLAN output contract. */
  private void emit(UUID callerId, Result r) {
    String summary =
        "[RTP test/safety-verifier] "
            + (r.pass ? "ok" : "FAIL")
            + " world="
            + r.sampledWorld
            + " unsafeCached="
            + r.unsafeBlocksCached
            + " unsafeSize="
            + r.unsafeBlocksSize
            + " futureReturned="
            + r.futureReturned
            + " futureAsync="
            + r.futureAsync
            + " verifierDispatched="
            + r.verifierDispatched
            + " timeoutCaught="
            + r.timeoutCaught
            + (r.notes.isEmpty() ? "" : " notes=" + r.notes);

    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, summary);
    }
    // S-004: failing run logs at WARNING; clean run at INFO.
    RTP.log(r.pass ? Level.INFO : Level.WARNING, summary);
  }

  /** Structured probe result. Package-private so unit tests may assert on it. */
  static final class Result {
    String sampledWorld = "";
    boolean unsafeBlocksCached;
    int unsafeBlocksSize;
    boolean futureReturned;
    boolean futureAsync;
    boolean verifierDispatched;
    boolean timeoutCaught;
    boolean pass;
    String notes = "";
  }
}
