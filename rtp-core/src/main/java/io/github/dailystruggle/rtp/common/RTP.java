package io.github.dailystruggle.rtp.common;

import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.economy.RTPEconomy;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.AbstractSQLDatabaseAccessor;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.metrics.CoreMetrics;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.*;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.fixed.FixedAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump.JumpAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.tasks.ScanTask;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import io.github.dailystruggle.rtp.common.tasks.TimeBoundTaskPipe;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import io.github.dailystruggle.rtp.common.tools.ChunkyChecker;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Core class for the RTP (Random Teleport) plugin.
 * This class serves as the central hub for the plugin's platform-agnostic logic, separating
 * the core functionality from Bukkit/Spigot/Paper-specific APIs.
 *
 * <p>Key responsibilities include:
 * <ul>
 *   <li>Managing the selection API (Shapes, Vertical Adjustors).</li>
 *   <li>Maintaining references to server accessors, schedulers, and economy integrations.</li>
 *   <li>Tracking queued players and teleport data.</li>
 *   <li>Handling synchronization and task pipelining across the plugin.</li>
 * </ul>
 *
 * <p>This class acts as a singleton, accessible via {@link #getInstance()}, and delegates
 * platform-specific operations to the injected `serverAccessor` and `scheduler`.
 */
public class RTP {
  public static final ConcurrentLinkedQueue<CompletableFuture<?>> futures =
      new ConcurrentLinkedQueue<>();

  public static SelectionAPI selectionAPI = new SelectionAPI();

  public static EnumMap<factoryNames, Factory<?>> factoryMap = new EnumMap<>(factoryNames.class);

  public final Set<UUID> queuedPlayers = ConcurrentHashMap.newKeySet();

  /**
   * minimum number of teleportations to executeAsyncTasks per gametick, to prevent bottlenecking
   * during lag spikes
   */
  public static int minRTPExecutions = 1;

  /** only one of each of these objects */
  public static Configs configs;

  /**
   * Platform-agnostic registry of {@link io.github.dailystruggle.rtp.api.addon.RTPAddon}s.
   * Discovered via {@link java.util.ServiceLoader} and loaded once core initialisation
   * completes; unloaded on {@link #stop()}. Replaces the Bukkit-only addon loading path
   * so example/third-party addons run on every platform.
   */
  public static final io.github.dailystruggle.rtp.common.addon.AddonRegistry addons =
      new io.github.dailystruggle.rtp.common.addon.AddonRegistry();

  /**
   * Process-wide accumulator of biome <em>occupancy</em> (where online players
   * spend their time), surfaced by {@code /rtp info biomes}. Populated by the
   * platform adapter's periodic occupancy sampler; resets on restart/reload.
   * In-memory only - no persistence, identical in the full and lite editions.
   */
  public static final io.github.dailystruggle.rtp.common.tools.activity.BiomeActivityTracker
      biomeActivity = new io.github.dailystruggle.rtp.common.tools.activity.BiomeActivityTracker();

  public static final UUID serverId = RTPAPI.serverId;

  public static RTPServerAccessor serverAccessor;
  public static RTPScheduler scheduler;
  public static RTPEconomy economy = null;

  /**
   * Pre-dispatch hook consulted at the top of {@code RTPCmd.compute(...)}
   * to decide whether a {@code /rtp} call should be served locally,
   * enrolled on the cross-server wait queue, or rejected with a localized
   * message. Defaults to {@link io.github.dailystruggle.rtp.api.network.NetworkCommandHook#LOCAL_ONLY}
   * so plain single-server deployments incur zero behavioural change.
   * Network-mode adapters (Bukkit's {@code NetworkModeBootstrap}) install
   * a richer impl during startup and revert to {@code LOCAL_ONLY} on
   * shutdown. See {@code rtp-proxy-ADR-014}.
   */
  public static volatile io.github.dailystruggle.rtp.api.network.NetworkCommandHook
      networkCommandHook =
          io.github.dailystruggle.rtp.api.network.NetworkCommandHook.LOCAL_ONLY;

  /**
   * Platform-supplied factory for the backend heartbeat sampler (ADR-049
   * step 4). Each backend platform adapter (Bukkit / Paper / Folia / Fabric)
   * installs a factory during startup that builds a
   * {@link io.github.dailystruggle.rtp.common.network.BackendStateSampler}
   * reading live host state (TPS / MSPT / player count / loaded worlds). The
   * boolean argument is the {@code routing.lobbyMode} flag so the sampler can
   * advertise an empty region inventory + {@code acceptingRequests=false}
   * when this backend is a pure cross-server lobby.
   *
   * <p>The factory lives on {@code RTP} (rtp-core) rather than
   * {@code RTPServerAccessor} (rtp-api) because {@code BackendStateSampler}
   * produces a {@code rtp-proxy-common} {@code BackendHeartbeat}, a type
   * rtp-api does not depend on. Remains {@code null} until a platform installs
   * one; {@code NetworkModeBootstrap.boot(...)} treats a {@code null} factory
   * as "platform has no sampler" and logs rather than throwing, so a missing
   * factory degrades network mode gracefully instead of crashing startup.
   *
   * @since 3.0.0-beta.4 (ADR-049)
   */
  public static volatile java.util.function.Function<Boolean,
      io.github.dailystruggle.rtp.common.network.BackendStateSampler>
      backendStateSamplerFactory;

  /**
   * Platform-supplied factory for the tier-1 (DB-free) plugin-message
   * {@link io.github.dailystruggle.rtp.common.network.pluginmessage.NetworkBridge}.
   * Each backend platform adapter that supports proxy plugin messaging
   * (Bukkit / Paper / Folia; later Fabric / NeoForge) installs a factory
   * during startup that wires the {@code bungeecord:main} channel and the
   * passive proxy-forwarding probe. Consumed by
   * {@code NetworkModeBootstrap.openTransport} when {@code transport.type} is
   * {@code plugin-message} or {@code auto}.
   *
   * <p>The factory lives on {@code RTP} (rtp-core) rather than
   * {@code RTPServerAccessor} (rtp-api) because {@code NetworkBridge} feeds a
   * {@code rtp-proxy-common}-typed transport. Remains {@code null} until a
   * platform installs one; the bootstrap treats {@code null} as "this platform
   * has no plugin-message bridge" and keeps the plugin-message / auto tiers
   * disabled (graceful, no crash). See {@code rtp-proxy-ADR-016}.
   */
  public static volatile java.util.function.Supplier<
      io.github.dailystruggle.rtp.common.network.pluginmessage.NetworkBridge>
      networkBridgeFactory;

  /**
   * When {@code true}, this backend is configured as a pure
   * cross-server lobby and skips all local region processing - the per-region
   * pre-fill ({@link io.github.dailystruggle.rtp.common.tasks.ScanTask}
   * scheduling), the per-region DB hydrate, and the steady-state
   * {@code Region.execute()} pulse all short-circuit. The lobby still owns
   * {@link io.github.dailystruggle.rtp.common.selection.region.Region}
   * instances (so {@code /rtp info} and config menus keep working), but those
   * regions never fill their {@code keptLocations}/{@code unkeptLocations}
   * pools and never accept reservations from peers (see
   * {@code BukkitBackendStateSampler}
   * which advertises {@code regions=[]} + {@code acceptingRequests=false}
   * under the same flag).
   *
   * <p>Read from {@code routing.lobbyMode} in {@code network.yml} by
   * {@code NetworkModeBootstrap.readLobbyModeEarly(File)} BEFORE the region
   * config is loaded, so the gate is in effect when regions are first
   * constructed. Defaults to {@code false} so non-lobby deployments incur
   * zero behavioural change. Volatile so the early-startup publish
   * happens-before the construction reads.
   *
   */
  public static volatile boolean lobbyMode = false;

  /**
   * Platform-supplied carrier for the {@code /rtp test ...} umbrella SPI
   * (sender + deferred scheduler + audit sink). Populated by each platform
   * plugin during startup; remains {@code null} until then. Callers inside
   * {@code rtp-core} should resolve this via
   * {@link io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaContext#require()}
   * which enforces S-006 (throws {@link IllegalStateException} rather than
   * silently no-opping when the umbrella runs before core load).
   *
   * <p>See {@code docs/dev/scratch/CHECKLIST-fabric-rtp-test-full.md} Phase 1.
   */
  public static volatile io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaContext
      testUmbrellaContext;

  /**
   * Process-wide runtime metrics aggregator. Defaults to a {@link CoreMetrics} with a
   * {@link io.github.dailystruggle.metrics.api.MetricsBinding#NOOP NOOP} binding so
   * callers never have to null-check; platform adapters install a real binding via
   * {@link CoreMetrics#setBinding} during startup. See {@code METRICS_PLAN.md}.
   */
  public static final CoreMetrics metrics = new CoreMetrics();

  public static final ThreadLocal<RTPWorld> worldContext = new ThreadLocal<>();
  public static final ThreadLocal<Region> regionContext = new ThreadLocal<>();

  /**
   * Optional per-thread message interceptor used by {@code /rtp info} when its
   * output is being mirrored into a book renderer (PROPOSAL-info-as-book.md
   * section 4.6). When non-null, {@code InfoCmd} routes every line it would
   * have sent to {@code RTP.serverAccessor.sendMessage(callerId, …)} into this
   * consumer instead — the chat path is skipped while the tap is installed so
   * the player is not double-served. Code outside {@code InfoCmd} ignores this
   * field; only paths that opt in via the helper consult it.
   *
   * <p>Always {@code null} on the chat-output path. The tap is set on the
   * dispatching thread, the {@code InfoCmd} body executes synchronously on the
   * same thread, and the tap is cleared in a {@code finally} block — so no
   * cross-invocation leakage is possible. Tests verify this invariant.
   */
  public static final ThreadLocal<java.util.function.Consumer<String>> messageTap = new ThreadLocal<>();

  public static TreeCommand baseCommand;
  public static AtomicBoolean reloading = new AtomicBoolean(false);

  /** only one instance will exist at a time, reset on plugin load */
  private static RTP instance;

  private static ScheduledExecutorService diagnosticTimer;
  private static final List<Object> trackedTasks = new ArrayList<>();

  static {
    Factory<Shape<?>> shapeFactory = new Factory<>();
    selectionAPI.shapeFactory = shapeFactory;
    factoryMap.put(factoryNames.shape, shapeFactory);

    Factory<VerticalAdjustor<?>> verticalAdjustorFactory = new Factory<>();
    factoryMap.put(factoryNames.vert, verticalAdjustorFactory);
    factoryMap.put(factoryNames.singleConfig, new Factory<ConfigParser<?>>());
    factoryMap.put(factoryNames.multiConfig, new Factory<MultiConfigParser<?>>());

    // --- INJECT IMPLEMENTATIONS INTO THE API ---
    // Two-tier API model: custom Shape / VerticalAdjustor registration is an
    // implementation-tier extension served by the typed RTP.addShape(Shape) /
    // RTP.addVerticalAdjustor(VerticalAdjustor) entry points in this module, not
    // by rtp-api. Addons deriving a new shape compile against rtp-core.

    // ADR-026: expose the unified external-hook facade. Third-party plugins call
    // RTPAPI.hooks() to register claim verifiers, economy, placeholders, world
    // border, and anvil pre-filter providers without depending on rtp-core
    // internals. See docs/dev/EXTERNAL_HOOKS.md.
    io.github.dailystruggle.rtp.api.RTPAPI.hooks =
        new io.github.dailystruggle.rtp.common.hooks.DefaultRTPHooks();

    // First-class teleport entry point for addons: trigger an RTP for an online
    // player and complete a future with the outcome, without reaching into core
    // internals. Mirrors the RTPCmd teleport path; completion is delivered via
    // TeleportData.onComplete, fired exactly once at the pipeline's terminal
    // cleanup phase (REQ-RTP-S-004). See RTPAPI#teleport.
    io.github.dailystruggle.rtp.api.RTPAPI.teleportDelegate = (uuid, target) -> {
      java.util.concurrent.CompletableFuture<io.github.dailystruggle.rtp.api.RTPResult> future =
          new java.util.concurrent.CompletableFuture<>();
      try {
        RTPPlayer player = serverAccessor.getPlayer(uuid);
        if (player == null) {
          future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
              io.github.dailystruggle.rtp.api.RTPResult.Reason.PLAYER_OFFLINE,
              "No online player with UUID " + uuid));
          return future;
        }

        // Cooldown guard, mirroring RTPCmd.compute(): reject while the player is
        // still within their teleport cooldown window. A completed prior teleport
        // is the signal to clear a stale processingPlayers entry (same preemptive
        // cleanup the command path performs) so the alreadyTeleporting guard below
        // does not misfire on a finished teleport.
        TeleportData priorData = getInstance().latestTeleportData.get(uuid);
        if (priorData != null) {
          long dt = System.currentTimeMillis() - priorData.time;
          if (dt < 0) dt = Long.MAX_VALUE + dt;
          if (dt < player.cooldown()) {
            future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
                io.github.dailystruggle.rtp.api.RTPResult.Reason.COOLDOWN,
                "Teleport is on cooldown for this player"));
            return future;
          } else if (priorData.completed) {
            getInstance().processingPlayers.remove(uuid);
          }
        }

        // Usage-cap guard (BetterRTP LockAfter parity), mirroring RTPCmd.compute():
        // reject when the player has exhausted lockAfterUses within the rolling
        // window. Inert unless lockAfterUses > 0; bypassed by rtp.nolock. The
        // increment happens in TeleportPipelineTask on success, so GUI/API
        // teleports already count toward the cap - only the rejection gate was
        // command-only before now.
        if (!player.hasPermission("rtp.nolock")) {
          ConfigParser<ConfigKeys> cfg =
              (ConfigParser<ConfigKeys>) configs.getParser(ConfigKeys.class);
          if (cfg != null) {
            long cap = cfg.getNumber(ConfigKeys.lockAfterUses, 0L).longValue();
            if (cap > 0) {
              long resetMillis =
                  cfg.getNumber(ConfigKeys.lockAfterResetSeconds, 0L).longValue() * 1000L;
              if (getInstance().teleportLimitStore.isLocked(
                  uuid, cap, resetMillis, System.currentTimeMillis())) {
                future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
                    io.github.dailystruggle.rtp.api.RTPResult.Reason.LOCKED,
                    "Player is locked out after reaching the usage cap"));
                return future;
              }
            }
          }
        }

        // Reloading guard, mirroring RTPCmd.compute(): refuse new teleports while
        // the plugin is swapping configuration so the pipeline never runs against
        // half-applied config.
        if (reloading.get()) {
          future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
              io.github.dailystruggle.rtp.api.RTPResult.Reason.RELOADING,
              "Teleport denied while the plugin is reloading"));
          return future;
        }

        // Concurrency guard, mirroring RTPCmd.compute()'s alreadyTeleporting
        // check: a player with a teleport already in flight must not start a
        // second one through the addon-facing API (e.g. repeated GUI clicks, or
        // clicking the menu while a /rtp is already warming up). The local
        // launch paths below register the player in processingPlayers; the
        // pipeline's terminal cleanup (TeleportPipelineTask / Region) clears it.
        if (getInstance().processingPlayers.contains(uuid)) {
          future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
              io.github.dailystruggle.rtp.api.RTPResult.Reason.ALREADY_TELEPORTING,
              "A teleport is already in progress for this player"));
          return future;
        }

        // Network/peer target: route across the cross-server wait queue via the
        // platform-installed NetworkCommandHook, exactly as a
        // `/rtp region=<server>:<region>` command would. The enrolment side
        // effect happens inside route(); we map the routing decision onto the
        // teleport future (QUEUED on enrolment, failure on reject/unavailable).
        if (target.kind() == io.github.dailystruggle.rtp.api.RtpTarget.Kind.NETWORK) {
          String serverId = target.serverId();
          String regionKey = target.name();
          io.github.dailystruggle.rtp.api.network.NetworkCommandHook hook = networkCommandHook;
          if (hook == null
              || hook == io.github.dailystruggle.rtp.api.network.NetworkCommandHook.LOCAL_ONLY) {
            future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
                io.github.dailystruggle.rtp.api.RTPResult.Reason.INVALID_TARGET,
                "Network mode is not enabled on this server"));
            return future;
          }
          java.util.Map<String, java.util.List<String>> netArgs = new java.util.HashMap<>();
          netArgs.put("region",
              java.util.Collections.singletonList(serverId + ":" + regionKey));
          io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult decision;
          try {
            decision = hook.route(uuid, netArgs);
          } catch (Throwable t) {
            future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
                io.github.dailystruggle.rtp.api.RTPResult.Reason.ERROR,
                "Cross-server routing failed: " + t.getMessage()));
            return future;
          }
          if (decision instanceof io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.CrossServer cross) {
            future.complete(io.github.dailystruggle.rtp.api.RTPResult.queued(
                "Queued for " + cross.serverHint().orElse(serverId)
                    + ":" + cross.regionKey().orElse(regionKey)));
          } else if (decision instanceof io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.Reject reject) {
            future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
                io.github.dailystruggle.rtp.api.RTPResult.Reason.INVALID_TARGET,
                reject.placeholder() == null || reject.placeholder().isEmpty()
                    ? "Destination unavailable" : reject.placeholder()));
          } else {
            // RoutingResult.Local: the hook elected to serve the request on this
            // backend (e.g. a self-pin). Fall through to the local pipeline using
            // the bare region name.
            Region localRegion = selectionAPI.getRegionOrDefault(regionKey);
            if (localRegion == null || localRegion.getWorld() == null) {
              future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
                  io.github.dailystruggle.rtp.api.RTPResult.Reason.INVALID_TARGET,
                  "Could not resolve a region/world for " + regionKey));
              return future;
            }
            runLocalTeleport(uuid, player, localRegion, future);
          }
          return future;
        }

        Region region;
        try {
          region = resolveApiRegion(target, player);
        } catch (IllegalArgumentException | IllegalStateException ex) {
          future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
              io.github.dailystruggle.rtp.api.RTPResult.Reason.INVALID_TARGET,
              String.valueOf(ex.getMessage())));
          return future;
        }
        if (region == null || region.getWorld() == null) {
          future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
              io.github.dailystruggle.rtp.api.RTPResult.Reason.INVALID_TARGET,
              "Could not resolve a region/world for " + target));
          return future;
        }

        final Region targetRegion = region;

        // Economy charge, mirroring the self-branch of RTPCmd.compute(): the
        // addon-facing teleport (every GUI/menu addon drives it) must debit the
        // configured teleport price + per-region price and refuse a player who
        // cannot afford it, exactly as the /rtp command does. RtpTarget carries
        // no shape/vert/biome parameters, so no params/biome surcharge applies.
        // Skipped automatically when no economy is bound or the player has
        // rtp.free (see EconomyGate).
        io.github.dailystruggle.rtp.common.economy.EconomyGate.Charge apiCharge =
            io.github.dailystruggle.rtp.common.economy.EconomyGate.charge(
                uuid, player, targetRegion,
                io.github.dailystruggle.rtp.common.economy.EconomyGate.PriceKey.SELF, false, false);
        if (apiCharge.result()
            == io.github.dailystruggle.rtp.common.economy.EconomyGate.Result.INSUFFICIENT_FUNDS) {
          getInstance().processingPlayers.remove(uuid);
          future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
              io.github.dailystruggle.rtp.api.RTPResult.Reason.INSUFFICIENT_FUNDS,
              "Player cannot afford the teleport cost"));
          return future;
        }

        TeleportData data = new TeleportData();
        io.github.dailystruggle.rtp.common.tools.MemoryTracker.track(
            data, "TeleportData-" + uuid, 120000L);
        // Record the charge so a failed teleport is refunded (RTPTeleportCancel).
        data.cost = apiCharge.cost();
        data.sender = player;
        data.targetRegion = targetRegion;
        data.onComplete = td -> {
          if (td.completed && td.selectedCoords != null) {
            RTPWorld<?> w = serverAccessor.getRTPWorld(td.selectedCoords.worldName());
            if (w == null) w = targetRegion.getWorld();
            future.complete(io.github.dailystruggle.rtp.api.RTPResult.success(
                new io.github.dailystruggle.rtp.api.world.RTPLocation(
                    w, td.selectedCoords.x(), td.selectedCoords.y(), td.selectedCoords.z())));
          } else {
            future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
                io.github.dailystruggle.rtp.api.RTPResult.Reason.NO_SAFE_LOCATION,
                "No safe location was found"));
          }
        };
        getInstance().latestTeleportData.put(uuid, data);

        io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask task =
            new io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask(
                new io.github.dailystruggle.rtp.api.selection.GenerationContext(player, player, null),
                targetRegion);
        data.nextTask = task;
        getInstance().processingPlayers.add(uuid);
        targetRegion.inFlightCalculations.incrementAndGet();
        // Fast path, mirroring the sync branch of RTPCmd.compute(): when a
        // pre-verified location is already cached for this player (its chunk is
        // kept-loaded, so no synchronous chunk I/O happens - S-005 safe), run
        // the pipeline inline instead of deferring to the next async scheduler
        // pulse. This makes a menu/GUI click teleport as immediately as the
        // /rtp command does. The addon-facing API applies no biome filter, so
        // the only gate is a ready cached location; any other case (empty
        // cache, biome-filtered - N/A here) still runs asynchronously.
        if (targetRegion.hasLocation(uuid)) {
          task.run();
        } else {
          scheduler.runTaskAsynchronously(task);
        }
      } catch (Throwable t) {
        getInstance().processingPlayers.remove(uuid);
        future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
            io.github.dailystruggle.rtp.api.RTPResult.Reason.ERROR, String.valueOf(t.getMessage())));
      }
      return future;
    };

    io.github.dailystruggle.rtp.api.RTPAPI.cancelDelegate = uuid -> {
      TeleportData d = getInstance().latestTeleportData.get(uuid);
      boolean pending = (d != null && !d.completed)
          || getInstance().processingPlayers.contains(uuid);
      new RTPTeleportCancel(uuid).run();
      return pending;
    };

    io.github.dailystruggle.rtp.api.RTPAPI.queueDepthDelegate = world -> {
      if (world == null) return 0;
      try {
        Region region = resolveApiRegion(
            io.github.dailystruggle.rtp.api.RtpTarget.world(world.name()), null);
        if (region == null) return 0;
        long depth = region.getPublicQueueLength();
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, depth));
      } catch (RuntimeException ex) {
        return 0;
      }
    };

    io.github.dailystruggle.rtp.api.RTPAPI.warmupDelegate = uuid -> {
      TeleportData d = getInstance().latestTeleportData.get(uuid);
      return (d != null && !d.completed) || getInstance().processingPlayers.contains(uuid);
    };

    // GUI-author SPI reads (PROPOSAL-gui-author-spi.md). Permission-gated target
    // list + per-target status for populating third-party menu items. Read-only:
    // these never trigger a teleport or charge a player - RTPAPI.teleport(...)
    // re-enforces every safety/permission/economy check regardless.
    io.github.dailystruggle.rtp.api.RTPAPI.allowedTargetsDelegate = uuid -> {
      java.util.List<io.github.dailystruggle.rtp.api.RtpTarget> out = new ArrayList<>();
      // A bare /rtp is always offered.
      out.add(io.github.dailystruggle.rtp.api.RtpTarget.defaultRegion());
      try {
        RTPPlayer player = serverAccessor.getPlayer(uuid);

        // The bare default target (added above) resolves, for this player, to a
        // concrete named region. Listing that same region again in the loop
        // below would surface it twice (e.g. the default region named
        // "default" appearing as both the bare default row and a region row),
        // so remember its name and skip it when enumerating named regions.
        String defaultRegionName = null;
        if (player != null) {
          try {
            Region defaultRegion = selectionAPI.getRegion(player);
            if (defaultRegion != null) defaultRegionName = defaultRegion.name;
          } catch (RuntimeException ignored) {
            // Defensive: default-region resolution must not break enumeration.
          }
        }

        // regionNames() is backed by a ConcurrentHashMap keySet, whose
        // iteration order is unspecified and unstable across reloads/JVMs.
        // Sort case-insensitively so the GUI (and any other consumer) renders
        // a deterministic, consistent region order.
        java.util.List<String> sortedNames =
            new ArrayList<>(selectionAPI.regionNames());
        sortedNames.sort(String.CASE_INSENSITIVE_ORDER);
        for (String name : sortedNames) {
          if (name == null || name.trim().isEmpty()) continue;
          if (defaultRegionName != null && defaultRegionName.equalsIgnoreCase(name)) {
            continue; // already offered as the bare default target
          }
          Region region = selectionAPI.getRegion(name);
          if (region == null) continue;
          boolean requirePerm = region.getSettings().requirePermission();
          if (requirePerm
              && (player == null || !player.hasPermission("rtp.regions." + name))) {
            continue;
          }
          out.add(io.github.dailystruggle.rtp.api.RtpTarget.region(name));
        }

        // Network/peer regions (rtp-proxy-ADR-014): surface the live
        // `server:region` entries advertised across the network so a GUI / addon
        // can offer cross-server destinations alongside local ones. Permission
        // gating mirrors the `/rtp region=<server>:<region>` command path:
        // `rtp.servers.<server>` AND `rtp.regions.<region>`. Reads are defensive -
        // a missing/flaky network layer simply contributes no extra targets.
        try {
          io.github.dailystruggle.rtp.common.network.NetworkModeBootstrap live =
              io.github.dailystruggle.rtp.common.network.NetworkModeBootstrap.LIVE;
          if (live != null) {
            io.github.dailystruggle.rtp.common.network.PeerRegionRegistry registry =
                live.peerRegionRegistry();
            if (registry != null) {
              String localServerId = registry.localServerId();
              // Sort the peer entries too so cross-server rows render in a
              // stable, consistent order alongside the local regions.
              java.util.List<String> peerEntries =
                  new ArrayList<>(registry.peerEntries());
              peerEntries.sort(String.CASE_INSENSITIVE_ORDER);
              for (String entry : peerEntries) {
                if (entry == null) continue;
                int colon = entry.indexOf(':');
                if (colon <= 0 || colon >= entry.length() - 1) continue;
                String serverId = entry.substring(0, colon);
                String regionKey = entry.substring(colon + 1);
                // Deduplicate: a self-qualified entry (e.g. backend-a:default while
                // running on backend-a) names the same region already offered locally
                // as the unqualified `default`/`<region>` above, so skip it rather than
                // listing the backend's own regions twice in the menu.
                if (localServerId != null && localServerId.equals(serverId)) {
                  continue;
                }
                if (player != null
                    && (!player.hasPermission("rtp.servers." + serverId)
                        || !player.hasPermission("rtp.regions." + regionKey))) {
                  continue;
                }
                out.add(io.github.dailystruggle.rtp.api.RtpTarget.network(serverId, regionKey));
              }
            }
          }
        } catch (Throwable networkEx) {
          log(Level.FINE,
              "[RTP API] getAllowedTargets network enumeration skipped: " + networkEx.getMessage());
        }
      } catch (RuntimeException ex) {
        log(Level.WARNING, "[RTP API] getAllowedTargets failed: " + ex.getMessage(), ex);
      }
      return Collections.unmodifiableList(out);
    };

    io.github.dailystruggle.rtp.api.RTPAPI.targetStatusDelegate = (uuid, target) -> {
      try {
        RTPPlayer player = serverAccessor.getPlayer(uuid);
        if (player == null) {
          return new io.github.dailystruggle.rtp.api.RtpTargetStatus(
              io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability.UNKNOWN, 0L, 0.0);
        }

        // Network/peer target: resolves on a remote backend, so there is no
        // local Region to inspect. Gate on the same two permissions the command
        // path uses (`rtp.servers.<server>` + `rtp.regions.<region>`) and report
        // reachability from the live peer registry. Cost is unknown locally (the
        // destination backend owns its economy), so 0.0 is reported here.
        if (target.kind() == io.github.dailystruggle.rtp.api.RtpTarget.Kind.NETWORK) {
          String serverId = target.serverId();
          String regionKey = target.name();
          if (!player.hasPermission("rtp.servers." + serverId)
              || !player.hasPermission("rtp.regions." + regionKey)) {
            return new io.github.dailystruggle.rtp.api.RtpTargetStatus(
                io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability.NO_PERMISSION, 0L, 0.0);
          }
          boolean reachable = false;
          // Peer-advertised display hints (icon block / environment string)
          // surfaced from the destination backend's heartbeat regionMetadata so
          // a GUI can show a recognisable cross-server icon. Purely cosmetic.
          String iconBlock = null;
          String environment = null;
          // Peer-advertised cosmetic display label (the backend's configured
          // region displayName), so a lobby shows the destination's chosen words.
          String label = null;
          try {
            io.github.dailystruggle.rtp.common.network.NetworkModeBootstrap live =
                io.github.dailystruggle.rtp.common.network.NetworkModeBootstrap.LIVE;
            if (live != null && live.peerRegionRegistry() != null) {
              io.github.dailystruggle.rtp.common.network.PeerRegionRegistry reg =
                  live.peerRegionRegistry();
              reachable = reg.isReachableHardPin(serverId, regionKey);
              iconBlock = reg.peerRegionAttribute(serverId, regionKey, "block");
              environment = reg.peerRegionAttribute(serverId, regionKey, "env");
              label = reg.peerRegionAttribute(serverId, regionKey, "label");
            }
          } catch (Throwable ignored) {
            // Defensive: a flaky network layer must not crash status reads.
          }
          return new io.github.dailystruggle.rtp.api.RtpTargetStatus(
              reachable
                  ? io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability.READY
                  : io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability.DISABLED,
              0L, 0.0, iconBlock, environment, label);
        }

        Region region;
        try {
          region = resolveApiRegion(target, player);
        } catch (RuntimeException ex) {
          return new io.github.dailystruggle.rtp.api.RtpTargetStatus(
              io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability.DISABLED, 0L, 0.0);
        }
        if (region == null || region.getWorld() == null) {
          return new io.github.dailystruggle.rtp.api.RtpTargetStatus(
              io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability.DISABLED, 0L, 0.0);
        }

        // Permission for the named target (mirrors SelectionAPI gating).
        boolean noPerm = false;
        switch (target.kind()) {
          case REGION:
            if (region.getSettings().requirePermission()
                && !player.hasPermission("rtp.regions." + target.name())) {
              noPerm = true;
            }
            break;
          case WORLD: {
            ConfigParser<WorldKeys> worldParser = configs.getWorldParser(target.name());
            boolean requirePerm = worldParser != null
                && Boolean.parseBoolean(
                    worldParser.getConfigValue(WorldKeys.requirePermission, false).toString());
            if (requirePerm && !player.hasPermission("rtp.worlds." + target.name())) {
              noPerm = true;
            }
            break;
          }
          case DEFAULT:
          default:
            break;
        }

        // Cost: region price plus the configured economy base price, unless the
        // player teleports for free. Funds check mirrors RTPCmd's balanceFloor.
        double cost = region.getSettings().price();
        boolean noFunds = false;
        RTPEconomy economy = RTP.economy;
        if (economy != null && !player.hasPermission("rtp.free")) {
          double floor = 0.0;
          ConfigParser<EconomyKeys> eco =
              (ConfigParser<EconomyKeys>) configs.getParser(EconomyKeys.class);
          if (eco != null) {
            cost += eco.getNumber(EconomyKeys.price, 0.0).doubleValue();
            floor = eco.getNumber(EconomyKeys.balanceFloor, 0.0).doubleValue();
          }
          if ((economy.bal(uuid) - cost) < floor) {
            noFunds = true;
          }
        }

        // Remaining cooldown.
        long remaining = 0L;
        TeleportData d = getInstance().latestTeleportData.get(uuid);
        if (d != null) {
          long dt = System.currentTimeMillis() - d.time;
          if (dt < 0) dt = 0L;
          remaining = Math.max(0L, player.cooldown() - dt);
        }

        io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability availability;
        if (noPerm) {
          availability = io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability.NO_PERMISSION;
        } else if (remaining > 0L) {
          availability = io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability.ON_COOLDOWN;
        } else if (noFunds) {
          availability = io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability.NO_FUNDS;
        } else {
          availability = io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability.READY;
        }
        // Advertise the destination world's environment so the menu can pick a
        // recognisable surface block locally (consumer-side env -> block
        // translation), matching how a cross-server target's environment is
        // surfaced. Block selection itself stays on the consuming side; we only
        // supply the environment string here (custom-dimension friendly).
        String localEnv = null;
        String localLabel = null;
        try {
          localEnv = region.getWorld().environment();
          // Cosmetic display label from the region's configured displayName
          // (falls back to the region name); the same value /rtp info uses.
          localLabel = region.displayName();
        } catch (Throwable ignored) {
          // Defensive: env/label enrichment is a cosmetic hint and must never break status.
        }
        return new io.github.dailystruggle.rtp.api.RtpTargetStatus(
            availability, remaining, Math.max(0.0, cost), null, localEnv, localLabel);
      } catch (RuntimeException ex) {
        log(Level.WARNING, "[RTP API] getTargetStatus failed: " + ex.getMessage(), ex);
        return new io.github.dailystruggle.rtp.api.RtpTargetStatus(
            io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability.UNKNOWN, 0L, 0.0);
      }
    };

    // Runtime-health snapshot for dashboard tiles. Sampled by the active metrics
    // binding, not computed on the caller's thread (no main-thread cost / chunk I/O).
    io.github.dailystruggle.rtp.api.RTPAPI.metricsSnapshotDelegate = () -> metrics.snapshot();
  }

  /**
   * Launch the standard local teleport pipeline for {@code targetRegion} and
   * complete {@code future} with the outcome. Mirrors the inline default/region
   * path of {@code teleportDelegate}; used by the network target's
   * {@code RoutingResult.Local} fall-through (self-pin served locally).
   */
  private static void runLocalTeleport(
      UUID uuid,
      RTPPlayer player,
      Region targetRegion,
      java.util.concurrent.CompletableFuture<io.github.dailystruggle.rtp.api.RTPResult> future) {
    // Economy charge parity with teleportDelegate: a network self-pin served
    // locally still debits the teleport + region price and refuses a player who
    // cannot afford it. Skipped when no economy is bound or the player is free.
    io.github.dailystruggle.rtp.common.economy.EconomyGate.Charge charge =
        io.github.dailystruggle.rtp.common.economy.EconomyGate.charge(
            uuid, player, targetRegion,
            io.github.dailystruggle.rtp.common.economy.EconomyGate.PriceKey.SELF, false, false);
    if (charge.result()
        == io.github.dailystruggle.rtp.common.economy.EconomyGate.Result.INSUFFICIENT_FUNDS) {
      getInstance().processingPlayers.remove(uuid);
      future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
          io.github.dailystruggle.rtp.api.RTPResult.Reason.INSUFFICIENT_FUNDS,
          "Player cannot afford the teleport cost"));
      return;
    }

    TeleportData data = new TeleportData();
    io.github.dailystruggle.rtp.common.tools.MemoryTracker.track(
        data, "TeleportData-" + uuid, 120000L);
    // Record the charge so a failed teleport is refunded (RTPTeleportCancel).
    data.cost = charge.cost();
    data.sender = player;
    data.targetRegion = targetRegion;
    data.onComplete = td -> {
      if (td.completed && td.selectedCoords != null) {
        RTPWorld<?> w = serverAccessor.getRTPWorld(td.selectedCoords.worldName());
        if (w == null) w = targetRegion.getWorld();
        future.complete(io.github.dailystruggle.rtp.api.RTPResult.success(
            new io.github.dailystruggle.rtp.api.world.RTPLocation(
                w, td.selectedCoords.x(), td.selectedCoords.y(), td.selectedCoords.z())));
      } else {
        future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
            io.github.dailystruggle.rtp.api.RTPResult.Reason.NO_SAFE_LOCATION,
            "No safe location was found"));
      }
    };
    getInstance().latestTeleportData.put(uuid, data);

    io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask task =
        new io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask(
            new io.github.dailystruggle.rtp.api.selection.GenerationContext(player, player, null),
            targetRegion);
    data.nextTask = task;
    getInstance().processingPlayers.add(uuid);
    targetRegion.inFlightCalculations.incrementAndGet();
    scheduler.runTaskAsynchronously(task);
  }

  /**
   * Resolve an {@link io.github.dailystruggle.rtp.api.RtpTarget} to a concrete
   * {@link Region} for the public teleport API. A {@code null} player is allowed
   * only for {@link io.github.dailystruggle.rtp.api.RtpTarget.Kind#REGION} and
   * {@code WORLD} targets (read-only queries); {@code DEFAULT} requires a player.
   */
  private static Region resolveApiRegion(
      io.github.dailystruggle.rtp.api.RtpTarget target, RTPPlayer player) {
    if (target == null) {
      throw new IllegalArgumentException("target must not be null");
    }
    switch (target.kind()) {
      case REGION:
        return selectionAPI.getRegionOrDefault(target.name());
      case WORLD: {
        ConfigParser<WorldKeys> worldParser = configs.getWorldParser(target.name());
        String regionName = (worldParser == null)
            ? "default"
            : worldParser.getConfigValue(WorldKeys.region, "default").toString();
        return selectionAPI.getRegionOrDefault(regionName);
      }
      case DEFAULT:
      default:
        if (player == null) {
          throw new IllegalArgumentException("default-region target requires an online player");
        }
        return selectionAPI.getRegion(player);
    }
  }

  public final ConcurrentHashMap<UUID, TeleportData> priorTeleportData = new ConcurrentHashMap<>();
  public final ConcurrentHashMap<UUID, TeleportData> latestTeleportData = new ConcurrentHashMap<>();
  /**
   * Per-player rolling usage-cap state backing the BetterRTP {@code LockAfter}
   * parity feature ({@code lockAfterUses} / {@code lockAfterResetSeconds}).
   * Inert while {@code lockAfterUses <= 0}.
   */
  public final io.github.dailystruggle.rtp.common.playerData.UsageCapTracker usageCaps =
      new io.github.dailystruggle.rtp.common.playerData.UsageCapTracker();
  /**
   * Teleport-limit guard seam (ADR-068). Fronts the usage cap above and adds
   * best-effort local persistence of the rolling window so it survives a
   * restart (parity with the already-persisted cooldown). The cross-server
   * durable/proxy layers of ADR-068 slot in behind this same field.
   */
  public final io.github.dailystruggle.rtp.common.playerData.TeleportLimitStore teleportLimitStore =
      new io.github.dailystruggle.rtp.common.playerData.LocalTeleportLimitStore(usageCaps, true);
  public final ConcurrentSkipListSet<UUID> processingPlayers = new ConcurrentSkipListSet<>();
  public RTPTaskPipe miscSyncTasks;
  public RTPTaskPipe miscAsyncTasks;
  public RTPTaskPipe startupTasks;
  public RTPTaskPipe cancelTasks;
  public final Map<String, ScanTask> scanTasks = new ConcurrentHashMap<>();
  public final ConcurrentHashMap<UUID, Long> invulnerablePlayers = new ConcurrentHashMap<>();
  public final ConcurrentLinkedQueue<RTPChunk<?>> chunksToUnload = new ConcurrentLinkedQueue<>();
  public DatabaseAccessor<?> databaseAccessor;
  /**
   * Optional cross-server messaging bus. Field type is the {@code RTPNetworkManager}
   * interface so {@code rtp-core} carries no symbolic reference to any concrete driver
   * class (ADR-024). Constructed reflectively below when network YAML enables a backend.
   */
  public io.github.dailystruggle.rtp.common.network.RTPNetworkManager networkManager;

  /**
   * Wraps the installed platform {@link #scheduler} in a {@link io.github.dailystruggle.rtp.common.metrics.ProfilingRTPScheduler}
   * so the wall-clock cost of RTP's own sync/async tasks is attributable. Idempotent:
   * never double-wraps if a previous instance already installed the decorator. Kept
   * static so the write to the static {@code scheduler} field is not performed from an
   * instance method.
   */
  private static synchronized void installProfilingScheduler() {
    if (!(scheduler instanceof io.github.dailystruggle.rtp.common.metrics.ProfilingRTPScheduler)) {
      scheduler = new io.github.dailystruggle.rtp.common.metrics.ProfilingRTPScheduler(scheduler);
    }
  }

  public RTP() {
    if (serverAccessor == null) throw new IllegalStateException("null serverAccessor");
    if (scheduler == null) throw new IllegalStateException("null scheduler");

    // Wrap the platform scheduler in a profiling decorator so the wall-clock cost
    // of RTP's own sync (main-thread / region) and async tasks is attributable
    // and reportable, independent of whole-server MSPT. Idempotent: never
    // double-wrap if a previous instance already installed one.
    installProfilingScheduler();

    // Install the active scheduler into RTPRunnable so tasks can self-dispatch onto the
    // correct thread via RTPRunnable#schedule() (entity/region/async routing).
    RTPRunnable.scheduler = scheduler;

    miscSyncTasks = (RTPTaskPipe) serverAccessor.createTaskPipe();
    miscAsyncTasks = (RTPTaskPipe) serverAccessor.createTaskPipe();
    startupTasks = (RTPTaskPipe) serverAccessor.createTaskPipe();
    cancelTasks = (RTPTaskPipe) serverAccessor.createTaskPipe();
    if (cancelTasks instanceof TimeBoundTaskPipe timeBoundTaskPipe) {
      timeBoundTaskPipe.setAvailableTime(Long.MAX_VALUE);
    }
    RTPAPI.setServerAccessor(serverAccessor);
    instance = this;

    addShape(new Circle());
    addShape(new Ellipse());
    addShape(new Square());
    addShape(new Rectangle());
    addShape(new Circle_Normal());
    addShape(new Square_Normal());
    addShape(new Polygon());
    addVerticalAdjustor(new LinearAdjustor(new ArrayList<>())); // todo: make this work
    addVerticalAdjustor(new JumpAdjustor(new ArrayList<>()));
    addVerticalAdjustor(new FixedAdjustor(new ArrayList<>()));

    configs = new Configs(serverAccessor.getPluginDirectory());

    startupTasks.add(new RTPRunnable(() -> {
      ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) configs.getParser(ConfigKeys.class);
      if (configParser == null) return;

      Map<String, Object> networkMap = configParser.getMap(ConfigKeys.network);
      Object redisObj = networkMap.get("redis");
      if (redisObj instanceof Map<?, ?> redisMap) {
        Object enabledObj = redisMap.get("enabled");
        boolean enabled = Boolean.parseBoolean(String.valueOf(enabledObj != null ? enabledObj : "false"));
        if (enabled) {
          Object hostObj = redisMap.get("host");
          String host = String.valueOf(hostObj != null ? hostObj : "127.0.0.1");
          Object portObj = redisMap.get("port");
          int port = portObj instanceof Number ? ((Number) portObj).intValue() : 6379;
          Object passwordObj = redisMap.get("password");
          String password = String.valueOf(passwordObj != null ? passwordObj : "");
          this.networkManager = createRedisNetworkManager(host, port, password);
          if (this.networkManager != null) this.networkManager.initializeAsync();
        }
      } else if (redisObj instanceof RtpYamlSection redisSection) {
        boolean enabled = redisSection.getBoolean("enabled", false);
        if (enabled) {
          String host = redisSection.getString("host", "127.0.0.1");
          int port = redisSection.getInt("port", 6379);
          String password = redisSection.getString("password", "");
          this.networkManager = createRedisNetworkManager(host, port, password);
          if (this.networkManager != null) this.networkManager.initializeAsync();
        }
      }
    }, 10));

    ChunkyChecker.loadChunky();

    trackedTasks.add(scheduler.runTaskTimerAsynchronously(
            MemoryTracker::runDiagnostics,
      1200L,
      1200L));

    // 1 Hz MSPT + heap ring sampler. Feeds the METRIC_SPARKLINE chart kind
    // (/rtp visualization sparkline) with ~2 minutes of rolling history at
    // 128 slots. Uses metrics.snapshot() which is platform-aware and
    // already wired with the active MetricsBinding by the platform adapter.
    trackedTasks.add(scheduler.runTaskTimerAsynchronously(
            () -> {
                try {
                    metrics.snapshotRing().recordFromSnapshot(metrics.snapshot());
                } catch (Throwable ignored) {
                    // Sampler shall never poison the scheduler thread.
                }
            },
            20L,
            20L));

    io.github.dailystruggle.rtp.common.tools.PerformanceTracker.start(scheduler);
    trackedTasks.add(scheduler.runTaskTimerAsynchronously(
        () -> {
          if (databaseAccessor != null) {
            // Rewrite the cached-locations table from authoritative in-memory
            // state so already-consumed locations cannot leak across restarts.
            // Must run BEFORE flushDirtyCache so the wipe-then-write ordering
            // survives into the shared writeQueue drain.
            databaseAccessor.rebuildCachedLocationsFromMemory();
            databaseAccessor.flushDirtyCache();
          }
        },
        6000,
        6000));
    trackedTasks.add(scheduler.runTaskTimerAsynchronously(
        () -> {
          if (databaseAccessor instanceof AbstractSQLDatabaseAccessor sqlDatabaseAccessor) {
            sqlDatabaseAccessor.flush();
          }
        },
        60,
        60));

    long syncTime = TimeUnit.MILLISECONDS.toNanos(5);
    trackedTasks.add(scheduler.runTaskTimer(new io.github.dailystruggle.rtp.common.tasks.tick.SyncTaskProcessing(syncTime), 1, 1));

    // Drive the world-scan on-screen progress bar from a platform-neutral main/server-thread
    // timer (once per second). The platform RTPServerAccessor renders it (boss-bar on
    // Bukkit/Fabric/NeoForge, no-op elsewhere); an empty scanBossBar template disables it.
    trackedTasks.add(scheduler.runTaskTimer(
        io.github.dailystruggle.rtp.common.tasks.tick.ScanProgressBars::update, 20, 20));

    long asyncTime = TimeUnit.MILLISECONDS.toNanos(25); // Bumped to 5ms since async has more headroom
    trackedTasks.add(scheduler.runTaskTimerAsynchronously(new io.github.dailystruggle.rtp.common.tasks.tick.AsyncTaskProcessing(asyncTime), 1, 1));

    // Discover and load platform-agnostic addons once core init has settled. Deferred via a
    // startup task so RTPAPI delegates installed by the platform adapter after construction are
    // visible to RTPAddon#onLoad (the load is idempotent; late programmatic registrations are
    // loaded eagerly by AddonRegistry#register).
    startupTasks.add(new RTPRunnable(() -> {
      addons.discover();
      // Also scan an optional RTP-owned folder so operators can drop addon jars into
      // <pluginDir>/addons instead of plugins/, where the server's plugin loader would
      // reject them for lacking a plugin.yml. A missing/empty folder is a no-op.
      if (serverAccessor != null) {
        File pluginDir = serverAccessor.getPluginDirectory();
        if (pluginDir != null) {
          File addonsDir = new File(pluginDir, "addons");
          // Unpack (and, on an RTP update, refresh) every addon jar bundled inside the
          // RTP jar (e.g. the GUI destination-picker demo and the claim-plugin
          // integrations) so they work out of the box and a fix shipped in a newer RTP
          // build actually reaches the server. extractBundledAddons is idempotent and
          // update-aware: it refreshes an unmodified stale jar, never clobbers an
          // operator-edited one, and records a deleted jar as a deliberate opt-out so it
          // is not re-installed. See AddonRegistry#extractBundledAddons.
          addons.extractBundledAddons(addonsDir);
          addons.discoverFromDirectory(addonsDir);
        }
      }
      addons.loadAll();
    }, 20));

  }

  /**
   * Reflectively construct the Redis-backed {@code RTPNetworkManager}, returning
   * {@code null} if the class (or its Jedis dependency) is not on the classpath.
   *
   * <p>ADR-024: the lite assembly excludes {@code RedisManager} and the Jedis
   * driver entirely. Resolving the class via {@code Class.forName} keeps the
   * symbolic reference inside a string literal so {@code RTP.class} itself can
   * load on a verifier-strict JVM without the driver present.
   */
  private static io.github.dailystruggle.rtp.common.network.RTPNetworkManager createRedisNetworkManager(String host, int port, String password) {
    try {
      Class<?> redisManagerClass = Class.forName("io.github.dailystruggle.rtp.common.network.RedisManager");
      return (io.github.dailystruggle.rtp.common.network.RTPNetworkManager)
          redisManagerClass.getDeclaredConstructor(String.class, int.class, String.class)
              .newInstance(host, port, password);
    } catch (ClassNotFoundException | NoClassDefFoundError missing) {
      log(Level.WARNING, "[RTP] redis enabled in config but RedisManager/Jedis is not on the classpath; skipping network bus init");
      return null;
    } catch (ReflectiveOperationException e) {
      log(Level.WARNING, "[RTP] failed to construct RedisManager", e);
      return null;
    }
  }

  public static void handleMigration(String previousState, String currentState) {
    if (previousState.equalsIgnoreCase("yaml") &&
        (currentState.equalsIgnoreCase("sqlite") ||
            currentState.equalsIgnoreCase("h2") ||
            currentState.equalsIgnoreCase("mysql") ||
            currentState.equalsIgnoreCase("postgresql"))) {
      serverAccessor.log(Level.INFO, "&aDatabase engine change detected. Initiating background migration from YAML to SQL...");
      getInstance().miscAsyncTasks.add(new RTPRunnable(() -> {
        File pluginDir = serverAccessor.getPluginDirectory();
        File databaseDir = new File(pluginDir, "database");

        // 1. Migrate teleportData.yml
        io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase yamlDb = new io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase(databaseDir);
        Map<String, RtpYamlConfig> lookup = yamlDb.connect();
        RtpYamlConfig teleportFile = lookup.get("teleportData.yml");
        if (teleportFile != null) {
          Map<String, Object> mapValues = teleportFile.getMapValues(false);
          for (Map.Entry<String, Object> entry : mapValues.entrySet()) {
            Object val = entry.getValue();
            Map<String, Object> dataMap;
            if (val instanceof Map<?, ?> map) dataMap = (Map<String, Object>) map;
            else if (val instanceof RtpYamlSection section) dataMap = section.getMapValues(false);
            else continue;

            try {
              TeleportData teleportData = new TeleportData();
              teleportData.sender = serverAccessor.getSender(UUID.fromString(dataMap.get("senderId").toString()));
              teleportData.time = ((Number) dataMap.getOrDefault("time", 0L)).longValue();
              Object oWorld = dataMap.get("originalWorldName");
              if (oWorld != null) teleportData.originalCoords = new RTPCoords(oWorld.toString(), ((Number) dataMap.get("originalX")).intValue(), ((Number) dataMap.get("originalY")).intValue(), ((Number) dataMap.get("originalZ")).intValue());
              Object sWorld = dataMap.get("selectedWorldName");
              if (sWorld != null) teleportData.selectedCoords = new RTPCoords(sWorld.toString(), ((Number) dataMap.get("selectedX")).intValue(), ((Number) dataMap.get("selectedY")).intValue(), ((Number) dataMap.get("selectedZ")).intValue());
              teleportData.attempts = ((Number) dataMap.getOrDefault("attempts", 0)).longValue();
              teleportData.cost = ((Number) dataMap.getOrDefault("cost", 0.0)).doubleValue();
              teleportData.targetRegion = selectionAPI.getRegion(dataMap.getOrDefault("region", "default").toString());
              teleportData.completed = true;

              if (getInstance().databaseAccessor instanceof AbstractSQLDatabaseAccessor sqlDb) {
                sqlDb.cacheValue(teleportData);
              }
            } catch (Exception ignored) {}
          }
        }

        // 2. Migrate individual UUID.yml files in teleportData directory
        File teleportDataDir = new File(databaseDir, "teleportData");
        if (teleportDataDir.exists() && teleportDataDir.isDirectory()) {
          File[] files = teleportDataDir.listFiles((dir, filename) -> filename.endsWith(".yml"));
          if (files != null) {
            for (File file : files) {
              try {
                RtpYamlConfig yamlFile = new RtpYamlConfig(file);
                yamlFile.load();
                Map<String, Object> mapValues = yamlFile.getMapValues(false);

                List<Map<String, Object>> records = new ArrayList<>();
                if (mapValues.containsKey("senderId") || mapValues.containsKey("time") || mapValues.containsKey("selectedX")) {
                  mapValues.putIfAbsent("senderId", file.getName().substring(0, file.getName().lastIndexOf('.')));
                  records.add(mapValues);
                } else {
                  for (Object val : mapValues.values()) {
                    if (val instanceof Map<?, ?> map) records.add((Map<String, Object>) map);
                    else if (val instanceof RtpYamlSection section) records.add(section.getMapValues(false));
                  }
                }

                boolean success = false;
                for (Map<String, Object> dataMap : records) {
                  try {
                    TeleportData teleportData = new TeleportData();
                    Object senderIdObj = dataMap.get("senderId");
                    if (senderIdObj == null) continue;
                    teleportData.sender = serverAccessor.getSender(UUID.fromString(senderIdObj.toString()));
                    teleportData.time = ((Number) dataMap.getOrDefault("time", 0L)).longValue();
                    Object oWorld = dataMap.get("originalWorldName");
                    if (oWorld != null) teleportData.originalCoords = new RTPCoords(oWorld.toString(), ((Number) dataMap.get("originalX")).intValue(), ((Number) dataMap.get("originalY")).intValue(), ((Number) dataMap.get("originalZ")).intValue());
                    Object sWorld = dataMap.get("selectedWorldName");
                    if (sWorld != null) teleportData.selectedCoords = new RTPCoords(sWorld.toString(), ((Number) dataMap.get("selectedX")).intValue(), ((Number) dataMap.get("selectedY")).intValue(), ((Number) dataMap.get("selectedZ")).intValue());
                    teleportData.attempts = ((Number) dataMap.getOrDefault("attempts", 0)).longValue();
                    teleportData.cost = ((Number) dataMap.getOrDefault("cost", 0.0)).doubleValue();
                    teleportData.targetRegion = selectionAPI.getRegion(dataMap.getOrDefault("region", "default").toString());
                    teleportData.completed = true;

                    if (getInstance().databaseAccessor instanceof AbstractSQLDatabaseAccessor sqlDb) {
                      sqlDb.cacheValue(teleportData);
                      success = true;
                    }
                  } catch (Exception ignored) {}
                }
                if (success) {
                  file.renameTo(new File(file.getAbsolutePath() + ".migrated"));
                }
              } catch (Exception ignored) {}
            }
          }
        }

        if (getInstance().databaseAccessor instanceof AbstractSQLDatabaseAccessor sqlDb) {
          sqlDb.flush();
        }
      }, 0));
    }
  }

  public static void addShape(Shape<?> shape) {
    ((Factory<Shape<?>>) factoryMap.get(factoryNames.shape)).add(shape.name, shape);
  }

  public static void addVerticalAdjustor(VerticalAdjustor<?> verticalAdjustor) {
    ((Factory<VerticalAdjustor<?>>) factoryMap.get(factoryNames.vert))
        .add(verticalAdjustor.name, verticalAdjustor);
  }

  public static RTP getInstance() {
    return instance;
  }

  public Object getPlugin() {
    return serverAccessor.getPlugin();
  }

  /**
   * Bounded buffer for sub-INFO log entries emitted before the {@code logging.yml}
   * parser is built. Without this, FINE/FINER/FINEST/CONFIG records emitted during
   * the bootstrap window (plugin enable, very early {@code reloadConfigs}) bypass
   * the configured {@code min_level} gate because the platform sink (e.g.
   * {@code SendMessage#getMinLevel()} on Bukkit) falls back to {@link Level#ALL}
   * when the parser is unavailable, causing them to render as INFO on the
   * console. After the parser is wired, {@link #flushPendingLogs()}
   * drains the buffer through the normal sink so the configured threshold gates
   * them retroactively.
   */
  private static final java.util.concurrent.ConcurrentLinkedQueue<Object[]> pendingLogs =
      new java.util.concurrent.ConcurrentLinkedQueue<>();

  /** Hard cap so a misconfigured/late bootstrap can't grow the buffer unbounded. */
  private static final int PENDING_LOGS_CAP = 4096;

  /**
   * True once {@link #flushPendingLogs()} has run; subsequent calls bypass the
   * buffer and emit directly. Kept volatile so the gate is visible across threads
   * without locking the hot log path.
   */
  private static volatile boolean logsBuffered = true;

  /**
   * Returns true when the {@code logging.yml} parser hasn't been built yet, so
   * sub-INFO records would otherwise leak past the (yet-unfiltered) sink.
   */
  private static boolean loggingParserUnavailable() {
    Configs c = configs;
    if (c == null) return true;
    try {
      return c.getParser(
              io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys.class)
          == null;
    } catch (Throwable t) {
      return true;
    }
  }

  /**
   * Drains any sub-INFO records buffered during bootstrap through the live sink.
   * Idempotent: callers (notably {@link Configs#reloadConfigs()} after the atomic
   * parser swap) may invoke this freely. Once drained, future {@link #log} calls
   * bypass the buffer.
   */
  public static void flushPendingLogs() {
    logsBuffered = false;
    RTPServerAccessor accessor = serverAccessor;
    Object[] entry;
    while ((entry = pendingLogs.poll()) != null) {
      Level lvl = (Level) entry[0];
      String msg = (String) entry[1];
      Throwable t = (Throwable) entry[2];
      try {
        if (accessor == null) {
          if (t == null) java.util.logging.Logger.getLogger("RTP").log(lvl, msg);
          else java.util.logging.Logger.getLogger("RTP").log(lvl, msg, t);
        } else {
          if (t == null) accessor.log(lvl, msg);
          else accessor.log(lvl, msg, t);
        }
      } catch (Throwable ignored) {
        // Never let replay failures break the caller.
      }
    }
  }

  public static void log(Level level, String str) {
    RTPServerAccessor accessor = serverAccessor;
    if (logsBuffered && level != null && level.intValue() < Level.INFO.intValue()
        && loggingParserUnavailable()) {
      if (pendingLogs.size() < PENDING_LOGS_CAP) {
        pendingLogs.offer(new Object[] {level, str, null});
      }
      return;
    }
    if (accessor == null) {
      // Bootstrap fallback: serverAccessor is wired during onEnable, but lifecycle
      // tracing fires before/after that window (e.g. early onEnable, onDisable after a
      // failed onEnable). Route through JUL so we never NPE during plugin enable/disable.
      java.util.logging.Logger.getLogger("RTP").log(level, str);
      return;
    }
    accessor.log(level, str);
  }

  public static void log(Level level, String str, Throwable throwable) {
    RTPServerAccessor accessor = serverAccessor;
    if (logsBuffered && level != null && level.intValue() < Level.INFO.intValue()
        && loggingParserUnavailable()) {
      if (pendingLogs.size() < PENDING_LOGS_CAP) {
        pendingLogs.offer(new Object[] {level, str, throwable});
      }
      return;
    }
    if (accessor == null) {
      java.util.logging.Logger.getLogger("RTP").log(level, str, throwable);
      return;
    }
    accessor.log(level, str, throwable);
  }

  public static RTPWorld getWorld(RTPPlayer player) {
    // get region from world name, check for overrides
    Set<String> worldsAttempted = new HashSet<>();
    String worldName = player.getLocation().world().name();
    MultiConfigParser<WorldKeys> worldParsers =
        (MultiConfigParser<WorldKeys>) RTP.configs.multiConfigParserMap.get(WorldKeys.class);
    ConfigParser<WorldKeys> worldParser = worldParsers.getParser(worldName);
    boolean requirePermission =
        Boolean.parseBoolean(
            worldParser.getConfigValue(WorldKeys.requirePermission, false).toString());

    while (requirePermission && !player.hasPermission("rtp.worlds." + worldName)) {
      if (worldsAttempted.contains(worldName))
        throw new IllegalStateException("infinite override loop detected at world - " + worldName);
      worldsAttempted.add(worldName);

      worldName =
          String.valueOf(worldParser.getConfigValue(WorldKeys.override, "DEFAULT.YML"))
              .toUpperCase();
      if (!worldName.equals(".YML")) worldName = worldName + ".YML";
      worldParser = worldParsers.getParser(worldName);
      requirePermission =
          Boolean.parseBoolean(
              worldParser.getConfigValue(WorldKeys.requirePermission, false).toString());
    }

    return serverAccessor.getRTPWorld(worldName);
  }

  public static void stop() {
    log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop ENTER");
    if (diagnosticTimer != null) {
      log(Level.FINER, "[SHUTDOWN_TRACE] RTP.stop diagnosticTimer.shutdown()");
      diagnosticTimer.shutdown();
    }

    List<CompletableFuture<?>> validFutures = new ArrayList<>(futures.size());
    for (CompletableFuture<?> future : futures) {
      if (!future.isDone()) validFutures.add(future);
    }
    if (!validFutures.isEmpty()) {
      log(Level.FINE,
          "[SHUTDOWN_TRACE] RTP.stop completing outstanding futures count=" + validFutures.size());
      for (CompletableFuture<?> future : validFutures) {
        try {
          if (future.isDone()) continue;
          future.complete(null);
        } catch (CancellationException ignored) {

        }
      }
    }

    if (instance == null) {
      log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop instance==null EARLY-RETURN");
      return;
    }

    int inflight = 0;
    for (Map.Entry<UUID, TeleportData> e : instance.latestTeleportData.entrySet()) {
      TeleportData data = e.getValue();
      if (data == null || data.completed) continue;
      log(Level.FINER,
          "[SHUTDOWN_TRACE] RTP.stop cancelInflight playerId=" + e.getKey());
      new RTPTeleportCancel(e.getKey()).run();
      inflight++;
    }
    log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop CancelInflight cancelled=" + inflight);

    // ADR-060: stop the emergency-platform restore reaper before the DB is flushed/closed so
    // it cannot touch a connection mid-shutdown. Persisted rows resume on next startup.
    io.github.dailystruggle.rtp.common.platform.PlatformRestoreManager.stopGlobal();

    if (instance.databaseAccessor != null) {
      if (instance.databaseAccessor instanceof AbstractSQLDatabaseAccessor sqlDatabaseAccessor) {
        log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop SQL accessor flush (WAL checkpoint)");
        sqlDatabaseAccessor.flush();
      }
      // Rewrite the cached-locations table from authoritative in-memory state
      // BEFORE flushing the dirtyCache. Without this, rows for locations that
      // were consumed in prior flush cycles (or whose delete enqueue lost the
      // write-vs-delete ordering race in processQueries) survive into the next
      // startup and get re-hydrated as ghosts.
      log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop rebuildCachedLocationsFromMemory");
      instance.databaseAccessor.rebuildCachedLocationsFromMemory();
      // Drain dirtyCache -> writeQueue (this enqueues async writes via setValue().thenAccept(),
      // but getTable returns a completed future synchronously, so the enqueue runs inline).
      log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop flushDirtyCache");
      instance.databaseAccessor.flushDirtyCache();
      // CRITICAL: actually drain the writeQueue (and deleteQueue) to disk. Previously this
      // step was missing on shutdown, so every cached-location save/delete that accumulated
      // between the 5-minute periodic flushDirtyCache cycle and server stop was lost —
      // which is why the kept cache appeared empty after restart. Must happen BEFORE
      // stop.set(true) below, because processQueries bails immediately if stop is set.
      log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop processQueries(MAX) drain BEFORE stop.set(true)");
      instance.databaseAccessor.processQueries(Long.MAX_VALUE);
    }

    log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop StopPipes miscAsyncTasks/miscSyncTasks");
    instance.miscAsyncTasks.stop();
    instance.miscSyncTasks.stop();

    log(Level.FINE,
        "[SHUTDOWN_TRACE] RTP.stop CancelTracked count=" + trackedTasks.size());
    for (Object task : trackedTasks) {
      log(Level.FINER, "[SHUTDOWN_TRACE] RTP.stop cancelTrackedTask task=" + task);
      scheduler.cancelTask(task);
    }
    trackedTasks.clear();

    log(Level.FINE,
        "[SHUTDOWN_TRACE] RTP.stop permRegionLookup shutDown count="
            + selectionAPI.permRegionLookup.size());
    for (Region r : selectionAPI.permRegionLookup.values()) {
      log(Level.FINER, "[SHUTDOWN_TRACE] RTP.stop permRegion.shutDown name=" + r.name);
      r.shutDown();
    }
    selectionAPI.permRegionLookup.clear();

    log(Level.FINE,
        "[SHUTDOWN_TRACE] RTP.stop tempRegions shutDown count=" + selectionAPI.tempRegions.size());
    for (Region r : selectionAPI.tempRegions.values()) {
      log(Level.FINER, "[SHUTDOWN_TRACE] RTP.stop tempRegion.shutDown name=" + r.name);
      r.shutDown();
    }
    selectionAPI.tempRegions.clear();

    if (instance.databaseAccessor != null) {
      log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop databaseAccessor.stop.set(true) + close()");
      instance.databaseAccessor.stop.set(true);
      instance.databaseAccessor.close();
    }

    instance.latestTeleportData.forEach(
        (uuid, data) -> {
          if (!data.completed) {
            log(Level.FINER,
                "[SHUTDOWN_TRACE] RTP.stop CancelAgain late-cancel playerId=" + uuid);
            new RTPTeleportCancel(uuid).run();
          }
        });

    instance.processingPlayers.clear();

    log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop ScanTask.kill");
    ScanTask.kill();

    if (instance.networkManager != null) {
      log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop networkManager.shutdown");
      instance.networkManager.shutdown();
    }

    log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop addons.unloadAll");
    addons.unloadAll();

    log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop serverAccessor.stop");
    serverAccessor.stop();
    log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop EXIT");
  }

  /** dynamic factories for certain types */
  public enum factoryNames {
    shape,
    vert,
    singleConfig,
    multiConfig
  }
}
