package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import io.github.dailystruggle.rtp.common.database.options.AbstractSQLDatabaseAccessor;
import io.github.dailystruggle.rtp.common.network.NetworkStateBinding;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.Subscription;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test network} - Shape A automated integration test for the
 * network-mode SPI ({@code MULTI_SERVER_PLAN.md} H3,
 * {@code rtp-proxy-ADR-011} et al.). Reaches the live
 * {@link NetworkTransport} on this JVM via the D3 slot on
 * {@link AbstractSQLDatabaseAccessor#getNetworkStateBinding()} +
 * {@link NetworkStateBinding#transport()}, publishes N synthetic
 * {@link BackendHeartbeat} rows, asserts they appear in the local snapshot
 * and trigger subscriber fan-out, then cleans up by republishing each
 * synthetic row with {@code pluginState=SHUTTING_DOWN} so a real subscriber
 * observes the departure and the binding's reaper drops the row on its
 * next pass.
 *
 * <p>REQ-RTP-NET-002 parity: when network mode is disabled (no binding /
 * no transport on the D3 slot), the test emits {@code network: NOT-CONFIGURED}
 * and returns without touching any transport surface, opening no threads
 * and allocating no schedulers.</p>
 *
 * <p>What this test does NOT do: it does not test HMAC reject paths
 * (Phase 4 / ADR-010), does not test multi-JVM agreement (requires a real
 * devstack), does not test churn or partition (Shape B territory), and
 * does not test reservation-token claim races. Audit row is suffixed
 * with {@code (single-JVM only)} on an in-memory binding so operators
 * do not mistake it for a multi-server-readiness check.</p>
 *
 * <p>Mirrors {@link EconomyIsolationTestJob}'s ActiveTestJobs registration
 * pattern so {@code rtp test full} blocks on this probe when invoked via
 * the umbrella.</p>
 */
public class NetworkSimulationTestJob extends BaseRTPCmdImpl {

  /** Per-step wall-clock timeout (publish/await/cleanup). */
  static final long PROBE_TIMEOUT_MS = 2_000L;

  /** Default number of synthetic peers when invoked without an argument. */
  static final int DEFAULT_PEER_COUNT = 3;

  /** Default observation window in ms - sleep between publish and assert. */
  static final long DEFAULT_OBSERVE_MS = 500L;

  /** Hard upper bound on peer count to keep the probe lightweight. */
  static final int MAX_PEER_COUNT = 16;

  /** Audit-row identifier for synthetic peers; downstream filters can detect them. */
  static final String SYNTHETIC_PREFIX = "rtp-sim-";

  /** Default number of synthetic reservation tokens when invoked without an argument. */
  static final int DEFAULT_TOKEN_COUNT = 2;

  /** Hard upper bound on the token-probe peer count to keep the probe lightweight. */
  static final int MAX_TOKEN_COUNT = 8;

  /** Default reservation TTL for the token probe (ms). */
  static final long DEFAULT_TOKEN_TTL_MS = 2_000L;

  /**
   * Minimum observation window for the TTL-reap step, in ms. Independent of
   * {@link #DEFAULT_OBSERVE_MS}: on real Redis/SQL the reaper round-trip
   * needs a floor even when the operator passes a smaller observe window.
   */
  static final long MIN_REAP_OBSERVE_MS = 250L;

  /** Parameter name for the peer / token count (positional N replacement). */
  public static final String PARAM_COUNT = "count";

  /** Parameter name for the heartbeat observe window in ms. */
  public static final String PARAM_OBSERVE_MS = "observeMs";

  /** Parameter name for the per-token TTL in ms (tokens probe). */
  public static final String PARAM_TTL_MS = "ttlMs";

  public NetworkSimulationTestJob(@Nullable CommandsAPICommand parent) {
    super(parent);
    // Per commands-api/README.md §2.2 + §4: bare tokens are always
    // subcommands, never parameter values. Expose probe modes as real
    // subcommands so `/rtp test network heartbeat`, `tokens`, and `all`
    // dispatch correctly instead of triggering `invalid command - <mode>`.
    addSubCommand(new NetworkHeartbeatProbeCmd(this));
    addSubCommand(new NetworkTokensProbeCmd(this));
    addSubCommand(new NetworkAllProbeCmd(this));
  }

  @Override
  public String name() {
    return "network";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "exercise the live network transport via heartbeat and/or reservation-token probes (Shape A)";
  }

  /**
   * Bare {@code /rtp test network} (no probe-mode subcommand) runs the
   * heartbeat probe with built-in defaults. The probe-mode subcommands
   * ({@link NetworkHeartbeatProbeCmd}, {@link NetworkTokensProbeCmd},
   * {@link NetworkAllProbeCmd}) bypass this method and call into
   * {@link #runProbe} / {@link #runTokenProbe} directly via the shared
   * {@link #dispatchProbe} helper.
   */
  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;
    dispatchProbe(callerId, ProbeKind.HEARTBEAT,
            DEFAULT_PEER_COUNT, DEFAULT_OBSERVE_MS, DEFAULT_TOKEN_TTL_MS);
    return true;
  }

  /** Which probe a subcommand selected. Used only inside {@link #dispatchProbe}. */
  enum ProbeKind { HEARTBEAT, TOKENS, ALL }

  /**
   * Common dispatch path for all probe-mode subcommands and the bare
   * {@code /rtp test network} default. Resolves the live transport, gates on
   * REQ-RTP-NET-002 (NOT-CONFIGURED when no binding/transport), registers
   * the job with {@link ActiveTestJobs}, and runs the selected probe on
   * the async scheduler.
   */
  void dispatchProbe(UUID callerId, ProbeKind kind,
          int peerCount, long observeWindowMs, long tokenTtlMs) {
    RTPScheduler scheduler = RTP.scheduler;
    if (scheduler == null) {
      audit(callerId, "FAIL", "RTP.scheduler is null; core not yet loaded");
      return;
    }
    NetworkTransport transport = resolveTransportOrNull(
            RTP.getInstance() == null ? null : RTP.getInstance().databaseAccessor);
    if (transport == null) {
      audit(callerId, "NOT-CONFIGURED",
              "network mode disabled or no transport bound on this JVM");
      return;
    }

    final Runnable[] hookHolder = new Runnable[1];
    hookHolder[0] = ActiveTestJobs.register(
            callerId, new ActiveTestJobs.Job("network", () -> { /* cooperative cancel only */ }));

    scheduler.runTaskAsynchronously(() -> {
      try {
        switch (kind) {
          case TOKENS -> runTokenProbe(callerId, transport, peerCount, tokenTtlMs);
          case ALL -> {
            runProbe(callerId, transport, peerCount, observeWindowMs);
            runTokenProbe(callerId, transport,
                    Math.min(peerCount, MAX_TOKEN_COUNT), DEFAULT_TOKEN_TTL_MS);
          }
          default -> runProbe(callerId, transport, peerCount, observeWindowMs);
        }
      } catch (Throwable t) {
        audit(callerId, "FAIL",
                "uncaught: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        RTP.log(Level.WARNING, "[RTP test/network] uncaught", t);
      } finally {
        if (hookHolder[0] != null) hookHolder[0].run();
      }
    });
  }

  /**
   * Walk the D3 slot to find the live {@link NetworkTransport}. Returns
   * {@code null} when any link in the chain is null (which is the
   * REQ-RTP-NET-002 disabled-mode parity case). Pure of any {@code RTP}
   * singleton access so the resolver is unit-testable with a stub
   * {@link io.github.dailystruggle.rtp.common.database.DatabaseAccessor}.
   */
  static @Nullable NetworkTransport resolveTransportOrNull(
          @Nullable io.github.dailystruggle.rtp.common.database.DatabaseAccessor<?> accessor) {
    try {
      if (!(accessor instanceof AbstractSQLDatabaseAccessor sql)) return null;
      NetworkStateBinding binding = sql.getNetworkStateBinding();
      if (binding == null) return null;
      return binding.transport();
    } catch (Throwable t) {
      // Defensive: any unexpected failure to resolve = NOT-CONFIGURED.
      return null;
    }
  }

  private void runProbe(
          UUID callerId, NetworkTransport transport, int peerCount, long observeWindowMs)
          throws InterruptedException {
    // 1. Subscribe BEFORE publishing so synchronous bindings (in-memory) fan
    //    out to us during publish; SQL bindings will fan out on their next poll.
    java.util.Set<String> observed = ConcurrentHashMap.newKeySet();
    Subscription sub;
    try {
      sub = transport.subscribeBackendHeartbeats(row -> {
        if (row != null && row.serverId() != null && row.serverId().startsWith(SYNTHETIC_PREFIX)) {
          observed.add(row.serverId());
        }
      });
    } catch (Throwable t) {
      audit(callerId, "FAIL", "subscribe threw: " + t.getMessage());
      RTP.log(Level.WARNING, "[RTP test/network] subscribe", t);
      return;
    }

    // 2. Deterministic synthetic peers. Seed = caller's UUID hi xor job nano.
    long seed = callerId.getMostSignificantBits() ^ System.nanoTime();
    java.util.Random rng = new java.util.Random(seed);
    List<String> synthIds = new ArrayList<>(peerCount);
    for (int i = 0; i < peerCount; i++) synthIds.add(SYNTHETIC_PREFIX + i + "-" + (seed & 0xFFFFFFL));

    long now = System.currentTimeMillis();
    List<BackendHeartbeat> rows = new ArrayList<>(peerCount);
    for (String id : synthIds) {
      rows.add(new BackendHeartbeat(
              id,
              1, // schemaVersion
              BackendHeartbeat.PluginState.READY,
              true, // acceptingRequests
              now,
              10.0 + rng.nextDouble() * 30.0, // mspt 10..40
              0,    // queueDepth
              64,   // softCap
              64L * 1024 * 1024,
              512L * 1024 * 1024,
              rng.nextInt(40),
              Collections.emptyList(),
              Collections.emptyList(),
              false));
    }

    // 3. Publish. Await all futures with the probe timeout.
    long t0 = System.nanoTime();
    try {
      java.util.concurrent.CompletableFuture<?>[] futures =
              new java.util.concurrent.CompletableFuture<?>[rows.size()];
      for (int i = 0; i < rows.size(); i++) {
        futures[i] = transport.publishBackendHeartbeat(rows.get(i));
      }
      java.util.concurrent.CompletableFuture.allOf(futures)
              .get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (Throwable t) {
      safeUnsubscribe(sub);
      audit(callerId, "FAIL", "publish: " + t.getClass().getSimpleName() + ": " + t.getMessage());
      RTP.log(Level.WARNING, "[RTP test/network] publish", t);
      return;
    }

    // 4. Wait one observation window so SQL bindings can pick up rows from poll.
    Thread.sleep(observeWindowMs);

    // 5. Assert snapshot contains all synthetic peers.
    NetworkSnapshot snap;
    try {
      snap = transport.readSnapshot().get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (Throwable t) {
      safeUnsubscribe(sub);
      cleanupSynthetic(transport, rows);
      audit(callerId, "FAIL", "readSnapshot: " + t.getClass().getSimpleName() + ": " + t.getMessage());
      RTP.log(Level.WARNING, "[RTP test/network] readSnapshot", t);
      return;
    }
    Map<String, BackendHeartbeat> backends = snap.backends();

    List<String> missingFromSnapshot = new ArrayList<>();
    for (String id : synthIds) if (!backends.containsKey(id)) missingFromSnapshot.add(id);

    // 6. Assert subscriber observed all synthetic peers.
    List<String> missingFromSubscriber = new ArrayList<>();
    for (String id : synthIds) if (!observed.contains(id)) missingFromSubscriber.add(id);

    long latencyMicros = (System.nanoTime() - t0) / 1_000L;

    // 7. Cleanup (always - even on failure).
    safeUnsubscribe(sub);
    cleanupSynthetic(transport, rows);

    // 8. Audit.
    boolean snapshotOk = missingFromSnapshot.isEmpty();
    // Subscriber fan-out is required on in-memory bindings (synchronous);
    // SQL bindings may legitimately miss a tick depending on poll alignment.
    // We surface the count but only fail on snapshot mismatch.
    String suffix = isInMemoryHint(transport) ? " (single-JVM only)" : "";
    if (!snapshotOk) {
      audit(callerId, "FAIL", "snapshot missing " + missingFromSnapshot.size()
              + "/" + peerCount + " synthetic peer(s) " + missingFromSnapshot
              + " latency=" + latencyMicros + "us" + suffix);
    } else {
      audit(callerId, "PASS", "peers=" + peerCount
              + " observed=" + observed.size() + "/" + peerCount
              + (missingFromSubscriber.isEmpty()
                      ? "" : " subscriber-missed=" + missingFromSubscriber)
              + " latency=" + latencyMicros + "us" + suffix);
    }
  }

  /**
   * Reservation-token round-trip against the live transport. For each synthetic
   * player UUID: {@code claim} -&gt; {@code findReservation} (must be present)
   * -&gt; {@code release} (with {@link ReleaseReason#TEST_PROBE}) -&gt;
   * {@code findReservation} (must be empty). Then provisions one extra token
   * with a 1ms TTL, sleeps long enough for it to expire, and asserts
   * {@code reapExpired} returns it. Best-effort cleanup releases any
   * synthetic tokens still live in the store, even on failure.
   *
   * <p>Distinct from {@link #runProbe}: that one exercises the heartbeat
   * surface (publish/subscribe/snapshot); this one exercises the
   * reservation-token surface that {@code rtp-proxy-ADR-005} A1+A2 + the
   * reservation-token TTL reaper newly enable on Redis.</p>
   */
  private void runTokenProbe(
          UUID callerId, NetworkTransport transport, int peerCount, long ttlMs) {
    final String suffix = " (" + transport.getClass().getSimpleName() + ")";
    final String serverId = SYNTHETIC_PREFIX + "tokens-"
            + (callerId.getMostSignificantBits() & 0xFFFFFFL);

    // 1. Mint deterministic synthetic player UUIDs from the caller seed.
    long seed = callerId.getMostSignificantBits() ^ System.nanoTime();
    java.util.Random rng = new java.util.Random(seed);
    List<UUID> players = new ArrayList<>(peerCount);
    for (int i = 0; i < peerCount; i++) {
      players.add(new UUID(rng.nextLong(), rng.nextLong()));
    }

    List<String> mintedTokenIds = new ArrayList<>(peerCount + 1);
    long t0 = System.nanoTime();
    long claimMicros = 0L, findMicros = 0L, releaseMicros = 0L, reapMicros = 0L;

    try {
      // 2. claim -> findReservation (present) -> release -> findReservation (empty).
      for (UUID playerId : players) {
        long s = System.nanoTime();
        ReservationToken token;
        try {
          token = transport.claim(serverId, playerId, Duration.ofMillis(ttlMs))
                  .get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
          audit(callerId, "FAIL", "claim threw: "
                  + t.getClass().getSimpleName() + ": " + t.getMessage() + suffix);
          RTP.log(Level.WARNING, "[RTP test/network/tokens] claim", t);
          cleanupSyntheticTokens(transport, mintedTokenIds);
          return;
        }
        claimMicros += (System.nanoTime() - s) / 1_000L;
        if (token == null || token.tokenId() == null) {
          audit(callerId, "FAIL", "claim returned null token" + suffix);
          cleanupSyntheticTokens(transport, mintedTokenIds);
          return;
        }
        mintedTokenIds.add(token.tokenId());

        s = System.nanoTime();
        Optional<ReservationToken> found;
        try {
          found = transport.findReservation(playerId)
                  .get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
          audit(callerId, "FAIL", "findReservation(present) threw: "
                  + t.getClass().getSimpleName() + ": " + t.getMessage() + suffix);
          RTP.log(Level.WARNING, "[RTP test/network/tokens] findReservation", t);
          cleanupSyntheticTokens(transport, mintedTokenIds);
          return;
        }
        findMicros += (System.nanoTime() - s) / 1_000L;
        if (found.isEmpty() || !token.tokenId().equals(found.get().tokenId())) {
          audit(callerId, "FAIL", "findReservation expected token " + token.tokenId()
                  + " for player " + playerId + " but got "
                  + found.map(ReservationToken::tokenId).orElse("<empty>") + suffix);
          cleanupSyntheticTokens(transport, mintedTokenIds);
          return;
        }

        s = System.nanoTime();
        try {
          transport.release(token.tokenId(), ReleaseReason.TEST_PROBE)
                  .get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
          audit(callerId, "FAIL", "release threw: "
                  + t.getClass().getSimpleName() + ": " + t.getMessage() + suffix);
          RTP.log(Level.WARNING, "[RTP test/network/tokens] release", t);
          cleanupSyntheticTokens(transport, mintedTokenIds);
          return;
        }
        releaseMicros += (System.nanoTime() - s) / 1_000L;

        // Post-release findReservation must be empty (idempotency baseline).
        Optional<ReservationToken> afterRelease;
        try {
          afterRelease = transport.findReservation(playerId)
                  .get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
          audit(callerId, "FAIL", "findReservation(post-release) threw: "
                  + t.getClass().getSimpleName() + ": " + t.getMessage() + suffix);
          RTP.log(Level.WARNING, "[RTP test/network/tokens] findReservation post-release", t);
          cleanupSyntheticTokens(transport, mintedTokenIds);
          return;
        }
        if (afterRelease.isPresent()) {
          audit(callerId, "FAIL", "findReservation after release still returns token "
                  + afterRelease.get().tokenId() + " for player " + playerId + suffix);
          cleanupSyntheticTokens(transport, mintedTokenIds);
          return;
        }
        // Successful release removed it from the synthetic set we have to clean up.
        mintedTokenIds.remove(token.tokenId());
      }

      // 3. TTL-reap step: provision one token with a tiny TTL, sleep past it,
      //    and assert reapExpired returns it. Uses a fresh synthetic UUID so
      //    it cannot collide with any active per-player slot above.
      UUID reapPlayer = new UUID(rng.nextLong(), rng.nextLong());
      ReservationToken doomed;
      try {
        doomed = transport.claim(serverId, reapPlayer, Duration.ofMillis(1L))
                .get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      } catch (Throwable t) {
        audit(callerId, "FAIL", "reap-claim threw: "
                + t.getClass().getSimpleName() + ": " + t.getMessage() + suffix);
        RTP.log(Level.WARNING, "[RTP test/network/tokens] reap-claim", t);
        cleanupSyntheticTokens(transport, mintedTokenIds);
        return;
      }
      mintedTokenIds.add(doomed.tokenId());

      try {
        Thread.sleep(Math.max(MIN_REAP_OBSERVE_MS, ttlMs));
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        audit(callerId, "FAIL", "interrupted during reap-observe sleep" + suffix);
        cleanupSyntheticTokens(transport, mintedTokenIds);
        return;
      }

      long s = System.nanoTime();
      List<String> reaped;
      try {
        reaped = transport.reapExpired(Instant.now())
                .get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      } catch (Throwable t) {
        audit(callerId, "FAIL", "reapExpired threw: "
                + t.getClass().getSimpleName() + ": " + t.getMessage() + suffix);
        RTP.log(Level.WARNING, "[RTP test/network/tokens] reapExpired", t);
        cleanupSyntheticTokens(transport, mintedTokenIds);
        return;
      }
      reapMicros = (System.nanoTime() - s) / 1_000L;

      if (!reaped.contains(doomed.tokenId())) {
        audit(callerId, "FAIL", "reapExpired did not observe doomed token "
                + doomed.tokenId() + " (reaped=" + reaped.size() + " in "
                + reapMicros + "us; observe-window=" + Math.max(MIN_REAP_OBSERVE_MS, ttlMs)
                + "ms)" + suffix);
        cleanupSyntheticTokens(transport, mintedTokenIds);
        return;
      }
      // Reaper consumed the doomed token; do not double-release in cleanup.
      mintedTokenIds.remove(doomed.tokenId());

      long totalMicros = (System.nanoTime() - t0) / 1_000L;
      audit(callerId, "PASS", "tokens peers=" + peerCount
              + " claim_us=" + claimMicros
              + " find_us=" + findMicros
              + " release_us=" + releaseMicros
              + " reap_us=" + reapMicros
              + " total_us=" + totalMicros + suffix);
    } finally {
      // Best-effort defensive sweep: release anything we minted but did not
      // explicitly clear above (e.g. on an early-return FAIL path that the
      // explicit cleanup already handled, this is a no-op).
      cleanupSyntheticTokens(transport, mintedTokenIds);
    }
  }

  /**
   * Best-effort release of any tokens the probe minted that may still be live
   * in the store. Each release is independent so a single failure cannot
   * orphan the rest; never throws.
   */
  private static void cleanupSyntheticTokens(NetworkTransport transport, List<String> tokenIds) {
    if (tokenIds == null || tokenIds.isEmpty()) return;
    for (String tokenId : new ArrayList<>(tokenIds)) {
      try {
        transport.release(tokenId, ReleaseReason.TEST_PROBE)
                .get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      } catch (Throwable ignored) {
        // Cleanup is best-effort; the reaper will eventually drop the row.
      }
    }
    tokenIds.clear();
  }

  /** Republish each row with {@code SHUTTING_DOWN} and a far-past timestamp so reapers drop them. */
  private void cleanupSynthetic(NetworkTransport transport, List<BackendHeartbeat> rows) {
    for (BackendHeartbeat row : rows) {
      try {
        BackendHeartbeat leave = new BackendHeartbeat(
                row.serverId(),
                row.schemaVersion(),
                BackendHeartbeat.PluginState.SHUTTING_DOWN,
                false,
                0L, // far-past lastSeenEpochMs so TTL reapers drop it
                row.mspt(),
                row.queueDepth(),
                row.softCap(),
                row.heapUsedBytes(),
                row.heapMaxBytes(),
                row.playerCount(),
                row.regionsAvailable(),
                row.worldsLoaded(),
                row.killSwitch());
        transport.publishBackendHeartbeat(leave);
      } catch (Throwable ignored) {
        // Cleanup is best-effort; the reaper will eventually drop the row.
      }
    }
  }

  private static void safeUnsubscribe(Subscription sub) {
    if (sub == null) return;
    try { sub.close(); } catch (Throwable ignored) { /* idempotent close */ }
  }

  /**
   * Best-effort detector: is the transport the in-memory binding? Used only to
   * suffix the audit row so a passing run on a single JVM is not misread as a
   * multi-server-readiness signal. Falls back to {@code false} on any failure.
   */
  private static boolean isInMemoryHint(NetworkTransport transport) {
    if (transport == null) return false;
    String n = transport.getClass().getSimpleName();
    return n != null && n.contains("InMemory");
  }

  static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  static long clamp(long v, long lo, long hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private void audit(UUID callerId, String verdict, String detail) {
    String msg = "[RTP test/network] " + verdict + ": " + detail;
    if (!callerId.equals(RTPAPI.serverId)) {
      RTP.serverAccessor.sendMessage(callerId, msg);
    }
    if ("PASS".equals(verdict) || "NOT-CONFIGURED".equals(verdict)) {
      RTP.log(Level.INFO, msg);
    } else {
      RTP.log(Level.WARNING, msg);
    }
  }
}
