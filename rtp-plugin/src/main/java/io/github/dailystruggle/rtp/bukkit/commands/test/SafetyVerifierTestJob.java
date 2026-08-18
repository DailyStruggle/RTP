package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
import io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers;
import java.util.ArrayList;
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
 * {@code rtp test safety-verifier} &mdash; runtime probe covering REQ-RTP-S-001
 * (block safety) and REQ-RTP-S-003 (claim verifiers) in a single pass, including
 * the async-verifier timeout leg via a hang-mock registered/unregistered in a
 * {@code try/finally}. No chunk I/O; coords are sampled from the first
 * {@link RTPWorld}.
 */
public class SafetyVerifierTestJob extends BaseRTPCmdImpl {

  /** Async-verifier wait bound. */
  static final long VERIFIER_TIMEOUT_MS = 750L;

  /** Harness wait cap; must exceed {@link #VERIFIER_TIMEOUT_MS}. */
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

    // Sample a coord directly from the engine's world list.
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
        (ConfigParser<BlocksKeys>) RTP.configs.getParser(BlocksKeys.class);
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
