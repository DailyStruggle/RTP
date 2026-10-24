package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.test.ProbeBitReport;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test safety-verifier} &mdash; runtime probe covering REQ-RTP-S-001
 * (block safety) and REQ-RTP-S-003 (claim verifiers) in a single pass, including
 * the async-verifier timeout leg via a hang-mock registered/unregistered in a
 * {@code try/finally}. No chunk I/O; coords are sampled from the first
 * {@link RTPWorld}.
 */
public class SafetyVerifierTestJob extends BaseRTPCmdImpl {

  public static final BitCode PRECONDITION = registerCode(3901, "TEST_SAFETY_VERIFIER_PRECONDITION");
  public static final BitCode UNSAFE_CACHE = registerCode(3902, "TEST_SAFETY_VERIFIER_UNSAFE_CACHE");
  public static final BitCode FUTURE_RETURNED = registerCode(3903, "TEST_SAFETY_VERIFIER_FUTURE_RETURNED");
  public static final BitCode FUTURE_ASYNC = registerCode(3904, "TEST_SAFETY_VERIFIER_FUTURE_ASYNC");
  public static final BitCode DISPATCH = registerCode(3905, "TEST_SAFETY_VERIFIER_DISPATCH");
  public static final BitCode TIMEOUT = registerCode(3906, "TEST_SAFETY_VERIFIER_TIMEOUT");
  public static final BitComponent COMPONENT = registerComponent();

  /** Async-verifier wait bound. */
  static final long VERIFIER_TIMEOUT_MS = 750L;

  /** Harness wait cap; must exceed {@link #VERIFIER_TIMEOUT_MS}. */
  static final long HARNESS_DEADLINE_MS = VERIFIER_TIMEOUT_MS * 4L;

  public SafetyVerifierTestJob(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter("json", new BooleanParameter("rtp.test", "emit the BIT report as JSON", (uuid, value) -> true));
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
  @SuppressWarnings("java:S3516") // Method returns boolean per CommandsAPICommand contract; false on missing serverAccessor, true on probe dispatch
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    if (RTPRuntime.serverAccessor() == null) {
      String msg = "&c[RTP test/safety-verifier] serverAccessor is null; core not yet loaded";
      RTP.log(Level.WARNING, msg);
      Result unavailable = new Result();
      unavailable.notes = "serverAccessor is null";
      BitResult bitResult = RtpBit.RUNNER.run(COMPONENT.withCheck(() -> toBitReport(unavailable)), Runnable::run);
      ProbeBitReport.emit(callerId, parameterValues, COMPONENT, bitResult);
      return false;
    }

    Result r = runProbe();
    emit(callerId, r);
    BitResult bitResult = RtpBit.RUNNER.run(COMPONENT.withCheck(() -> toBitReport(r)), Runnable::run);
    ProbeBitReport.emit(callerId, parameterValues, COMPONENT, bitResult);
    return true;
  }

  private static BitCode registerCode(int id, String key) {
    return RtpBitCodes.TEST.register(
        id,
        key,
        java.lang.System.Logger.Level.ERROR,
        BitTier.IBIT,
        "test.safety-verifier",
        "",
        "Verify the teleport safety probe's preconditions, async verifier and strict timeout.");
  }

  private static BitComponent registerComponent() {
    return new BitComponent(
            "safety-verifier",
            "test.safety-verifier",
            BitTier.IBIT,
            BitSideEffect.SAFE,
            5_000,
            () -> toBitReport(runProbe()));
  }

  static BitReport toBitReport(Result result) {
    ProbeBitReport.Builder report = ProbeBitReport.forProbe("safety-verifier");
    report.check(
        PRECONDITION,
        !result.sampledWorld.isBlank(),
        "safety.world",
        result.notes == null ? "No RTP world was available." : result.notes);
    report.check(
        UNSAFE_CACHE,
        result.unsafeBlocksCached,
        "safety.unsafeBlocks",
        "Unsafe-block lookups did not reuse the same cached snapshot.");
    report.check(
        FUTURE_RETURNED,
        result.futureReturned,
        "safety.verifierFuture",
        "The async verifier did not return a live CompletableFuture.");
    report.check(
        FUTURE_ASYNC,
        result.futureAsync,
        "safety.verifierFuture",
        "The verifier future was already completed when returned.");
    report.check(
        DISPATCH,
        result.verifierDispatched,
        "safety.verifierDispatch",
        "The registered async verifier was not dispatched.");
    report.check(
        TIMEOUT,
        result.timeoutCaught,
        "safety.verifierTimeout",
        "The verifier wait did not take the strict timeout path.");
    return report.build();
  }

  /**
   * Core probe, extracted so unit tests can assert the result structure
   * without spinning up a CommandsAPI dispatch. Must not touch chunk I/O
   * (REQ-RTP-S-005) and must leave {@link GlobalRegionVerifiers} in the
   * state it started in.
   */
  static Result runProbe() {
    Result r = new Result();

    // Sample a coord directly from the engine's world list.
    // We deliberately do not force a chunk load (S-005). Using spawn-relative
    // coordinates mirrors what the queue would serve for a freshly-loaded
    // world.
    List<? extends RTPWorld> worlds = RTPRuntime.serverAccessor().getRTPWorlds();
    if (worlds == null || worlds.isEmpty()) {
      r.notes = "no RTPWorlds registered";
      return r;
    }
    RTPWorld world = worlds.get(0);
    RTPCoords sampled = new RTPCoords(world.name(), 0, 64, 0);
    r.sampledWorld = world.name();

    // Assert unsafe-block lookup uses a cached snapshot.
    // LocationGenerator materialises BlocksKeys.unsafeBlocks once per
    // getLocation() call and iterates it per candidate; the list reference
    // must therefore be stable across repeated reads inside a single
    // decision window. We read twice and demand identity to surface any
    // regression toward per-candidate config-tree traversal (which would
    // also violate the "zero config memory tree lock contention" note in
    // the job spec).
    @SuppressWarnings("unchecked")
    ConfigParser<BlocksKeys> blocksParser =
        (ConfigParser<BlocksKeys>) ActiveConfigs.get().getParser(BlocksKeys.class);
    if (blocksParser == null) {
      r.notes = "BlocksKeys parser missing";
      return r;
    }
    Object snap1 = blocksParser.getConfigValue(BlocksKeys.unsafeBlocks, new ArrayList<>());
    Object snap2 = blocksParser.getConfigValue(BlocksKeys.unsafeBlocks, new ArrayList<>());
    r.unsafeBlocksCached = (snap1 == snap2);
    // Record the bucket size so operators can cross-check against their
    // configured unsafeBlocks list length without scraping the YAML.
    if (snap1 instanceof List<?>) r.unsafeBlocksSize = ((List<?>) snap1).size();

    // Round-trip through GlobalRegionVerifiers with a hang-mock async
    // verifier, and assert the returned value is a live CompletableFuture
    // that is NOT pre-completed.
    CountDownLatch verifierEntered = new CountDownLatch(1);
    AtomicReference<CompletableFuture<Boolean>> hangingFuture = new AtomicReference<>();
    Function<RTPCoords, CompletableFuture<Boolean>> hangMock =
        loc -> {
          if (loc != sampled) return CompletableFuture.completedFuture(true);
          verifierEntered.countDown();
          CompletableFuture<Boolean> never = new CompletableFuture<>();
          hangingFuture.set(never);
          return never;
        };

    AutoCloseable hangMockRegistration =
        GlobalRegionVerifiers.addGlobalRegionVerifierAsync(SafetyVerifierTestJob.class, hangMock);
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
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        r.timeoutCaught = false;
        r.notes = "interrupted waiting for verifier: " + ie.getMessage();
      } catch (Throwable t) {
        r.timeoutCaught = false;
        r.notes = "unexpected exception waiting for verifier: " + t.getClass().getSimpleName();
      }
    } finally {
      CompletableFuture<Boolean> pending = hangingFuture.get();
      if (pending != null) pending.complete(true);
      // S-003 hygiene: never leave a hanging verifier registered. If this
      // cleanup is ever changed to a best-effort log-and-continue, a
      // subsequent check could encounter the probe's verifier.
      try {
        hangMockRegistration.close();
      } catch (Throwable t) {
        RTP.log(
            Level.WARNING,
            "[RTP test/safety-verifier] failed to unregister probe verifier: "
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

  /** Emits the unchanged legacy summary and detail rows. */
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
        RTPRuntime.serverAccessor().sendMessage(callerId, summary);
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
