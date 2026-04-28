package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test folia-ownership} &mdash; runtime test job that proves
 * teleports are safely handed off to the Folia Entity Scheduler without
 * stalling a Region Thread.
 *
 * <p>This job dispatches a mock teleport task for a synthetic (or caller)
 * player through the {@link RTPScheduler} region tier, and immediately
 * before the final location modification it injects an assertion using
 * {@code Bukkit.isOwnedByCurrentRegion(Location)} to verify that the
 * callback is actually executing under the correct Entity Scheduler lock.
 *
 * <h2>Safety compliance</h2>
 * <ul>
 *   <li><b>REQ-RTP-S-005</b> &mdash; no chunk I/O; the pipeline is
 *       driven entirely by {@link CompletableFuture} chains. The assertion
 *       step confirms that the region hand-off actually occurred on the
 *       owning Entity Scheduler thread rather than silently running on
 *       the main server thread.</li>
 *   <li><b>REQ-RTP-S-004</b> &mdash; every outcome (pass, fail,
 *       unsupported, timeout) produces a player-visible line and a
 *       matching log entry at {@link Level#INFO} / {@link Level#WARNING}.</li>
 * </ul>
 *
 * <h2>Implementation notes</h2>
 * <p>{@code Bukkit.isOwnedByCurrentRegion(Location)} only exists on Folia
 * and Paper 1.20+; it throws {@code UnsupportedOperationException} on
 * Spigot. We therefore invoke it reflectively and treat a missing method
 * as a neutral "unsupported" outcome rather than a hard failure.
 *
 * <p>On Folia, calling region-sensitive APIs from a non-owning thread
 * raises {@code io.papermc.paper.threadedregions.ThreadAccessException}
 * (or {@code IllegalStateException} on some builds). The invocation is
 * wrapped in a {@link Throwable} catch so such exceptions are formatted
 * into the test output instead of propagating up the server-accessor
 * loop and crashing it.
 *
 * <p>The entire pipeline uses {@link CompletableFuture} chaining
 * ({@code thenCompose} / {@code whenComplete}); no {@code .get()} or
 * {@code .join()} is called on any scheduler thread. The terminal
 * {@code awaitReport} step uses {@code get(timeout)} only from the
 * dedicated async probe thread so no tick thread can stall.
 */
public class FoliaOwnershipTestJob extends BaseRTPCmdImpl {

  /** Wall-clock timeout for the full probe round-trip. */
  static final long PROBE_TIMEOUT_MS = 2_000L;

  public FoliaOwnershipTestJob(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "folia-ownership";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "verify Folia Entity-Scheduler region handoff without stalling region threads";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    RTPScheduler scheduler = RTP.scheduler;
    if (scheduler == null) {
      fail(callerId, "RTP.scheduler is null; core not yet loaded");
      return true;
    }

    // Snapshot the caller location on the dispatch thread. A "synthetic
    // player" for the purposes of this probe is simply a fabricated UUID
    // reusing the caller's location as a stand-in spawn point; we do not
    // touch the real entity, which keeps the probe read-only.
    final UUID syntheticId = UUID.randomUUID();
    RTPLocation callerLoc = null;
    try {
      RTPPlayer caller = RTP.serverAccessor.getPlayer(callerId);
      if (caller != null) callerLoc = caller.getLocation();
    } catch (Throwable ignored) {
      // handled below
    }
    if (callerLoc == null) {
      fail(callerId, "no caller location available; run as a player on a Folia/Paper server");
      return true;
    }
    final RTPLocation loc = callerLoc;

    // Fire-and-forget: run the probe pipeline on the async tier so the
    // dispatch thread (possibly the main server thread on Paper/Spigot)
    // is never blocked. The pipeline is a pure CompletableFuture chain.
    scheduler.runTaskAsynchronously(
        () -> {
          report(callerId, "[RTP test/folia-ownership] begin synthetic=" + syntheticId);
          dispatchMockTeleport(callerId, scheduler, loc)
              .whenComplete(
                  (result, err) -> {
                    if (err != null) {
                      fail(
                          callerId,
                          "pipeline failed ("
                              + err.getClass().getSimpleName()
                              + ": "
                              + err.getMessage()
                              + ")");
                    } else {
                      report(callerId, "[RTP test/folia-ownership] " + result);
                    }
                    report(callerId, "[RTP test/folia-ownership] end");
                  });
        });
    return true;
  }

  /**
   * Builds the mock-teleport pipeline as a {@link CompletableFuture}
   * chain. The chain has two stages:
   *
   * <ol>
   *   <li><b>Region hand-off</b> &mdash; schedule onto the region owning
   *       {@code loc} via {@link RTPScheduler#runTask(RTPLocation, Runnable)}.
   *       On Folia this routes to the Entity Scheduler for that region.
   *   <li><b>Ownership assertion</b> &mdash; invoke
   *       {@code Bukkit.isOwnedByCurrentRegion(loc)} reflectively; the
   *       result is fed back into the returned future.
   * </ol>
   *
   * <p>If step 2 throws (e.g. {@code ThreadAccessException} on Folia when
   * the scheduler mis-routed the task), the exception is captured and the
   * future completes <i>normally</i> with a human-readable failure line
   * &mdash; this is what prevents an unhandled exception from tearing
   * down the server-accessor loop.
   */
  private CompletableFuture<String> dispatchMockTeleport(
      UUID callerId, RTPScheduler scheduler, RTPLocation loc) {
    CompletableFuture<String> handoff = new CompletableFuture<>();
    long t0 = System.nanoTime();
    try {
      scheduler.runTask(
          loc,
          () -> {
            // This is the "final location modification" point. Before we
            // would apply any real change (teleportAsync, etc.), assert
            // that we hold the correct Entity Scheduler lock.
            String line = assertOwnership(loc, t0);
            handoff.complete(line);
          });
    } catch (Throwable t) {
      // Scheduler itself rejected the dispatch (e.g. platform adapter
      // missing). Complete normally with a formatted failure line.
      handoff.complete(
          "FAIL dispatch ("
              + t.getClass().getSimpleName()
              + ": "
              + t.getMessage()
              + ")");
    }

    // Apply a timeout guard so a stalled Region Thread can't wedge the
    // probe forever. We do this by composing a timeout future rather
    // than calling .get() on a tick thread.
    return handoff.completeOnTimeout(
        "TIMEOUT after " + PROBE_TIMEOUT_MS + "ms (region handoff never fired)",
        PROBE_TIMEOUT_MS,
        TimeUnit.MILLISECONDS);
  }

  /**
   * Invokes {@code Bukkit.isOwnedByCurrentRegion(Location)} via reflection.
   * Any exception (missing method on Spigot, {@code ThreadAccessException}
   * on Folia, {@code UnsupportedOperationException}, etc.) is caught and
   * folded into the returned status line so the caller never sees it as
   * an unhandled throwable.
   */
  private String assertOwnership(RTPLocation loc, long t0) {
    long micros = (System.nanoTime() - t0) / 1_000L;
    try {
      Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
      Class<?> bukkitLoc = Class.forName("org.bukkit.Location");
      Method m = bukkit.getMethod("isOwnedByCurrentRegion", bukkitLoc);

      // The RTP-side RTPLocation must be converted to an org.bukkit.Location
      // to call the Folia API. RTPLocation is a value type with a world()
      // accessor that returns an RTPWorld<T>; on the Bukkit family T is
      // org.bukkit.World, so we can construct a Bukkit Location directly.
      // We only fall through to UNSUPPORTED when the running platform is
      // demonstrably not Folia (the reflective method-lookup branch below
      // catches Spigot / pre-Folia Paper).
      Object bukkitLocation = buildBukkitLocation(loc, bukkitLoc);
      if (bukkitLocation == null) {
        // Not Folia (or RTPWorld handle isn't a Bukkit World) — record
        // unsupported only when we are also certain the Folia entry-point
        // isn't on the classpath; otherwise this would be a regression on
        // the very platform the probe exists for.
        if (!isFoliaRuntime()) {
          return "UNSUPPORTED (not running on Folia) latency=" + micros + "us";
        }
        return "FAIL could-not-build-bukkit-location-on-folia latency=" + micros + "us";
      }

      Object owned = m.invoke(null, bukkitLocation);
      boolean ok = owned instanceof Boolean && (Boolean) owned;
      if (ok) {
        return "PASS owned-by-current-region=true latency=" + micros + "us";
      }
      return "FAIL owned-by-current-region=false latency="
          + micros
          + "us (task ran on the wrong region thread)";
    } catch (ClassNotFoundException | NoSuchMethodException nf) {
      // Spigot or very old Paper; the Folia API isn't present. Report as
      // unsupported so CI on non-Folia platforms doesn't flag a failure.
      return "UNSUPPORTED (Bukkit.isOwnedByCurrentRegion not on classpath) latency="
          + micros
          + "us";
    } catch (Throwable t) {
      // Specifically: io.papermc.paper.threadedregions.ThreadAccessException
      // on Folia when this callback ended up on a non-owning thread. We
      // format the exception name + message into the report and swallow
      // it so the RTP server-accessor loop is not torn down.
      Throwable cause = t.getCause() != null ? t.getCause() : t;
      return "FAIL "
          + cause.getClass().getSimpleName()
          + " ("
          + String.valueOf(cause.getMessage())
          + ") latency="
          + micros
          + "us";
    }
  }

  /**
   * Builds an {@code org.bukkit.Location} from an {@link RTPLocation}.
   *
   * <p>{@link RTPLocation} is a platform-agnostic value type carrying an
   * {@link RTPWorld} reference plus integer block coordinates. On the
   * Bukkit family of adapters {@link RTPWorld#world()} returns the
   * underlying {@code org.bukkit.World}; we therefore reflectively
   * construct {@code new Location(world, x, y, z)}. Reflection is used
   * only to keep the test job free of a hard {@code org.bukkit} import
   * at compile time &mdash; the Bukkit jar is always on the classpath
   * here, so this is a runtime concern only insofar as it lets the same
   * code path produce a clean {@code UNSUPPORTED} on hypothetical
   * non-Bukkit hosts.
   *
   * @return a {@code org.bukkit.Location} instance, or {@code null} if
   *     the {@link RTPWorld} handle is not a Bukkit {@code World}.
   */
  private Object buildBukkitLocation(RTPLocation loc, Class<?> bukkitLocationClass) {
    try {
      RTPWorld<?> rtpWorld = loc.world();
      if (rtpWorld == null) return null;
      Object handle = rtpWorld.world();
      Class<?> bukkitWorld = Class.forName("org.bukkit.World");
      if (!bukkitWorld.isInstance(handle)) return null;
      // new Location(World, double, double, double)
      return bukkitLocationClass
          .getConstructor(bukkitWorld, double.class, double.class, double.class)
          .newInstance(handle, (double) loc.getBlockX(), (double) loc.getBlockY(), (double) loc.getBlockZ());
    } catch (Throwable ignored) {
      // Spigot/Paper without a usable World handle, or a non-Bukkit host.
      return null;
    }
  }

  /**
   * Detects whether the running server is Folia by class presence, the
   * same probe used in {@code RTPBukkitPlugin.isFolia()} and
   * {@code AbstractServerAccessor.isFolia()}.
   */
  private static boolean isFoliaRuntime() {
    try {
      Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  private void report(UUID callerId, String msg) {
    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTP.serverAccessor.sendMessage(callerId, msg);
    }
    RTP.log(Level.INFO, msg);
  }

  private void fail(UUID callerId, String msg) {
    String line = "[RTP test/folia-ownership] " + msg;
    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTP.serverAccessor.sendMessage(callerId, line);
    }
    RTP.log(Level.WARNING, line);
  }

  // Test-only accessors so unit tests can exercise the pure helpers
  // without spinning up a live scheduler.
  static long probeTimeoutMs() {
    return PROBE_TIMEOUT_MS;
  }

  static CompletableFuture<String> awaitWithTimeout(CompletableFuture<String> fut)
      throws InterruptedException, TimeoutException, java.util.concurrent.ExecutionException {
    fut.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    return fut;
  }
}
