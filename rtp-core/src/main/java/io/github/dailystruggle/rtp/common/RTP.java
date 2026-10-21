package io.github.dailystruggle.rtp.common;

import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.economy.RTPEconomy;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.common.action.ActionServices;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.database.ActiveDatabase;
import io.github.dailystruggle.rtp.common.database.options.AbstractSQLDatabaseAccessor;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryNames;
import io.github.dailystruggle.rtp.common.factory.FactoryRegistry;
import io.github.dailystruggle.rtp.common.metrics.CoreMetrics;
import io.github.dailystruggle.rtp.common.network.NetworkHooks;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.*;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.fixed.FixedAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump.JumpAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.tasks.scan.ScanTask;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import io.github.dailystruggle.rtp.common.tasks.TimeBoundTaskPipe;
import io.github.dailystruggle.rtp.common.tasks.scan.ScanTasks;
import io.github.dailystruggle.rtp.common.tasks.teleport.DeathTeleports;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import io.github.dailystruggle.rtp.common.tasks.tick.MiscTasks;
import io.github.dailystruggle.rtp.common.tools.ChunkyChecker;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;
import io.github.dailystruggle.rtp.common.tools.ParsePermissions;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;

import io.github.dailystruggle.rtp.common.trigger.PhysicalTriggerManager;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Core class for RTP platform-agnostic logic.
 * Manages selections, configurations, tasks, server accessor references,
 * and teleport execution state across supported platforms.
 */
public class RTP {
  public static final ConcurrentLinkedQueue<CompletableFuture<?>> futures =
      new ConcurrentLinkedQueue<>();

  /**
   * minimum number of teleportations to executeAsyncTasks per gametick, to prevent bottlenecking
   * during lag spikes
   */
  public static int minRTPExecutions = 1;


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

  /**
   * Process-wide entry point for multi-participant subspace group teleports.
   * Eagerly constructed and stateless: it reads {@link ActiveSelection#get()} at call time and fails
   * closed (structured {@link io.github.dailystruggle.rtp.api.group.GroupPlacementResult.Reason})
   * when the region/world is not resolvable, so it is safe to reference before core is fully wired.
   */
  public static volatile io.github.dailystruggle.rtp.api.group.GroupPlacementService
      groupPlacementService =
          new io.github.dailystruggle.rtp.common.selection.region.GroupPlacementDispatcher();

  /**
   * Platform-supplied carrier for {@code /rtp test} runtime umbrella SPI.
   * Resolved via {@link io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaContext#require()}.
   */
  public static volatile io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaContext
      testUmbrellaContext;

  /**
   * Optional per-thread message interceptor for book-mode mirroring in {@code /rtp info}.
   * Non-null redirects output to this consumer instead of player chat.
   */
  public static final ThreadLocal<java.util.function.Consumer<String>> messageTap = new ThreadLocal<>();

  public static TreeCommand baseCommand;
  public static AtomicBoolean reloading = new AtomicBoolean(false);

  private static final java.util.concurrent.atomic.AtomicReference<Set<String>> AIR_BLOCKS_CACHE =
      new java.util.concurrent.atomic.AtomicReference<>(Collections.emptySet());

  /**
   * Returns the reconciled configured air blocks set from {@code BlocksKeys.airBlocks}.
   */
  public static Set<String> getAirBlocks() {
    Set<String> cached = AIR_BLOCKS_CACHE.get();
    try {
      if (ActiveConfigs.get() == null) return cached;
      Object value = ActiveConfigs.get().getConfigValue(
          io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys.airBlocks,
          new ArrayList<>());
      if (!(value instanceof Collection<?> coll)) return cached;
      Map<String, Set<String>> tagSnapshot = Collections.emptyMap();
      if (RTPRuntime.serverAccessor() != null) {
        try {
          Map<String, Set<String>> s = RTPRuntime.serverAccessor().blockTagSnapshot();
          if (s != null) tagSnapshot = s;
        } catch (Throwable ignoredTag) {
          // best-effort
        }
      }
      Set<String> raw = new HashSet<>();
      for (Object o : coll) {
        String token = (o == null) ? "" : o.toString().trim();
        if (token.isEmpty()) continue;
        Set<String> members = resolveAirBlockTag(token, tagSnapshot);
        if (members != null) raw.addAll(members);
        else raw.add(token);
      }
      Set<String> reconciled = io.github.dailystruggle.rtp.api.configuration.PaletteIdentifierNormalizer.normalizeAll(raw);
      AIR_BLOCKS_CACHE.set(reconciled);
      return reconciled;
    } catch (Throwable ignored) {
      return cached;
    }
  }

  /** Expands a {@code #tag} token to its non-empty member set; {@code null} keeps the token as written. */
  private static Set<String> resolveAirBlockTag(String token, Map<String, Set<String>> tagSnapshot) {
    if (token.charAt(0) != '#') return null;
    String tagId = token.substring(1);
    if (tagId.indexOf(':') < 0) tagId = "minecraft:" + tagId;
    tagId = tagId.toLowerCase(Locale.ROOT);
    Set<String> members = tagSnapshot.get(tagId);
    return (members == null || members.isEmpty()) ? null : members;
  }

  /**
   * Checks if the given material name represents air (either vanilla air or configured air blocks).
   *
   * @param materialName material or block identifier
   * @return {@code true} if air, {@code false} otherwise
   */
  public static boolean isAir(String materialName) {
    if (materialName == null || materialName.isEmpty()) return false;
    String upper = materialName.toUpperCase(Locale.ROOT);
    int colon = upper.indexOf(':');
    String bare = (colon >= 0) ? upper.substring(colon + 1) : upper;
    if (bare.equals("AIR") || bare.equals("CAVE_AIR") || bare.equals("VOID_AIR")) {
      return true;
    }
    Set<String> air = getAirBlocks();
    if (air != null && !air.isEmpty()) {
      return air.contains(bare) || air.contains(upper);
    }
    return false;
  }

  /**
   * Reload all plugin configurations and regions.
   *
   * @return true if reload succeeded
   */
  public static boolean reload() {
    reloading.set(true);
    AIR_BLOCKS_CACHE.set(Collections.emptySet());
    try {
      if (ActiveConfigs.get() == null) return false;
      return ActiveConfigs.get().reload();
    } finally {
      reloading.set(false);
    }
  }

  /** only one instance will exist at a time, reset on plugin load */
  private static RTP instance;

  private static ScheduledExecutorService diagnosticTimer;
  private static final List<Object> trackedTasks = new CopyOnWriteArrayList<>();

  static {
    // ADR-115 seam 4: the bootstrap log buffer lives in the Tier 1 runtime holder; the gate
    // reads config state, so the root injects it before anything else can log.
    RTPRuntime.setLogBufferGate(RTP::loggingParserUnavailable);

    Factory<Shape<?>> shapeFactory = new Factory<>();
    ActiveSelection.get().shapeFactory = shapeFactory;
    FactoryRegistry.map().put(FactoryNames.shape, shapeFactory);

    Factory<VerticalAdjustor<?>> verticalAdjustorFactory = new Factory<>();
    FactoryRegistry.map().put(FactoryNames.vert, verticalAdjustorFactory);
    FactoryRegistry.map().put(FactoryNames.singleConfig, new Factory<ConfigParser<?>>());
    FactoryRegistry.map().put(FactoryNames.multiConfig, new Factory<MultiConfigParser<?>>());

    registerDefaultShapes();
    registerDefaultVerticalAdjustors();

    // ADR-115: reload steps on tasks and regions live in Tier 4. Installed in static init, which
    // platform entry points force first (ensureInitialized), so the Configs built later reloads
    // with the coordinator. A Configs built before RTP initializes has no coordinator.
    Configs.setReloadLifecycle(new io.github.dailystruggle.rtp.common.lifecycle.ConfigReloadCoordinator());
    // Same for the scan and teleport tasks a Region starts. Region no longer reads RTP statics,
    // so only the explicit ensureInitialized ordering guarantees this runs before any dispatch;
    // until then Region uses RegionTaskDispatcher.NONE.
    Region.setTaskDispatcher(new io.github.dailystruggle.rtp.common.lifecycle.RegionTaskCoordinator());
    // MemoryTracker (tools) counts region queues through views it owns, not Region itself.
    MemoryTracker.setRegionSource(
        io.github.dailystruggle.rtp.common.selection.region.RegionTicketCensus::allRegions);

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
    io.github.dailystruggle.rtp.api.RTPAPI.isAirDelegate = RTP::isAir;

    PhysicalTriggerManager.instance().start();

    // First-class teleport entry point for addons: trigger an RTP for an online
    // player and complete a future with the outcome, without reaching into core
    // internals. Mirrors the RTPCmd teleport path; completion is delivered via
    // TeleportData.onComplete, fired exactly once at the pipeline's terminal
    // cleanup phase (REQ-RTP-S-004). See RTPAPI#teleport.
    io.github.dailystruggle.rtp.api.RTPAPI.teleportDelegate = (uuid, target) -> {
      java.util.concurrent.CompletableFuture<io.github.dailystruggle.rtp.api.RTPResult> future =
          new java.util.concurrent.CompletableFuture<>();
      try {
        RTPPlayer player = RTPRuntime.serverAccessor().getPlayer(uuid);
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
        TeleportData priorData = TeleportSessions.current().latestTeleportData.get(uuid);
        Region reqRegion = null;
        if (target != null && target.kind() == io.github.dailystruggle.rtp.api.RtpTarget.Kind.REGION) {
          reqRegion = ActiveSelection.get().getRegion(target.name());
        }
        RegionSettings regSettings = (reqRegion != null) ? reqRegion.getSettings() : null;
        boolean hasRegionOverride = (regSettings != null && regSettings.cooldownMillis() != null && regSettings.cooldownMillis() >= 0);
        long cooldown = getCooldown(player, regSettings);
        long lastTpTime = hasRegionOverride
            ? getLastRegionTeleportTime(uuid, reqRegion.name)
            : getEffectiveLastTeleportTime(uuid);
        if (hasRegionOverride && lastTpTime <= 0 && priorData != null && priorData.targetRegion != null && reqRegion.name.equalsIgnoreCase(priorData.targetRegion.name)) {
          lastTpTime = priorData.time;
        }
        if (lastTpTime > 0 && cooldown > 0) {
          long dt = System.currentTimeMillis() - lastTpTime;
          if (dt < 0) dt = Long.MAX_VALUE + dt;
          if (dt < cooldown) {
            future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
                io.github.dailystruggle.rtp.api.RTPResult.Reason.COOLDOWN,
                "Teleport is on cooldown for this player"));
            return future;
          } else if (priorData != null && priorData.completed) {
            TeleportSessions.current().processingPlayers.remove(uuid);
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
              (ConfigParser<ConfigKeys>) ActiveConfigs.get().getParser(ConfigKeys.class);
          if (cfg != null) {
            long cap = cfg.getNumber(ConfigKeys.lockAfterUses, 0L).longValue();
            if (cap > 0) {
              long resetMillis =
                  cfg.getNumber(ConfigKeys.lockAfterResetSeconds, 0L).longValue() * 1000L;
              if (ActiveDatabase.teleportLimitStore().isLocked(
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
        if (TeleportSessions.current().processingPlayers.contains(uuid)) {
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
          if (apiTargetPermissionDenied(player, target, null)) {
            future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
                io.github.dailystruggle.rtp.api.RTPResult.Reason.NO_PERMISSION,
                getNoPermissionMessage(target)));
            return future;
          }
          io.github.dailystruggle.rtp.api.network.NetworkCommandHook hook = NetworkHooks.commandHook();
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
            Region localRegion = ActiveSelection.get().getRegionOrDefault(regionKey);
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

        // ACTION target is a UI navigation descriptor, not a teleport destination:
        if (target.kind() == io.github.dailystruggle.rtp.api.RtpTarget.Kind.ACTION) {
          future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
              io.github.dailystruggle.rtp.api.RTPResult.Reason.INVALID_TARGET,
              "ACTION targets cannot be teleported to"));
          return future;
        }

        // Coordinate target (e.g. /rtp back to exact coordinate on local or remote server):
        if (target.kind() == io.github.dailystruggle.rtp.api.RtpTarget.Kind.COORDINATE) {
          if (apiTargetPermissionDenied(player, target, null)) {
            future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
                io.github.dailystruggle.rtp.api.RTPResult.Reason.NO_PERMISSION,
                getNoPermissionMessage(target)));
            return future;
          }
          String destServer = target.serverId();
          String localServer = (io.github.dailystruggle.rtp.common.network.NetworkModeBootstrap.LIVE != null)
              ? io.github.dailystruggle.rtp.common.network.NetworkModeBootstrap.LIVE.serverId() : null;
          boolean isLocal = (destServer == null || destServer.isEmpty() || destServer.equalsIgnoreCase(localServer));
          if (isLocal) {
            String worldName = target.worldName();
            RTPWorld<?> world = (worldName != null && !worldName.isBlank())
                ? RTPRuntime.serverAccessor().getRTPWorld(worldName)
                : null;
            if (world == null) {
              future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
                  io.github.dailystruggle.rtp.api.RTPResult.Reason.INVALID_TARGET,
                  "World '" + worldName + "' not found"));
              return future;
            }
            io.github.dailystruggle.rtp.api.world.RTPLocation loc =
                new io.github.dailystruggle.rtp.api.world.RTPLocation(world, target.x(), target.y(), target.z());
            TeleportSessions.current().processingPlayers.add(uuid);
            player.setLocation(loc).whenComplete((success, ex) -> {
              TeleportSessions.current().processingPlayers.remove(uuid);
              if (ex != null || (success != null && !success)) {
                future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
                    io.github.dailystruggle.rtp.api.RTPResult.Reason.ERROR,
                    ex != null ? ex.getMessage() : "Teleport to coordinate failed"));
              } else {
                future.complete(io.github.dailystruggle.rtp.api.RTPResult.success(loc));
              }
            });
            return future;
          } else {
            // Remote server coordinate route: dispatch through NetworkCommandHook
            io.github.dailystruggle.rtp.api.network.NetworkCommandHook hook = NetworkHooks.commandHook();
            if (hook == null || hook == io.github.dailystruggle.rtp.api.network.NetworkCommandHook.LOCAL_ONLY) {
              future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
                  io.github.dailystruggle.rtp.api.RTPResult.Reason.INVALID_TARGET,
                  "Network mode is not enabled on this server"));
              return future;
            }
            java.util.Map<String, java.util.List<String>> netArgs = new java.util.HashMap<>();
            netArgs.put("region", java.util.Collections.singletonList(destServer + ":default"));
            netArgs.put("coords", java.util.Collections.singletonList(target.worldName() + ":" + target.x() + "," + target.y() + "," + target.z()));
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
                  "Queued for " + cross.serverHint().orElse(destServer) + " at " + target.worldName()));
            } else if (decision instanceof io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.Reject reject) {
              future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
                  io.github.dailystruggle.rtp.api.RTPResult.Reason.INVALID_TARGET,
                  reject.placeholder() == null || reject.placeholder().isEmpty()
                      ? "Destination unavailable" : reject.placeholder()));
            } else {
              future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
                  io.github.dailystruggle.rtp.api.RTPResult.Reason.INVALID_TARGET,
                  "Could not route to destination server " + destServer));
            }
            return future;
          }
        }

        // Permission gate before resolution and any charge: same rules as getTargetStatus,
        // so a GUI menu opened before a revoke (or any addon) cannot bypass it.
        if (apiTargetPermissionDenied(player, target, null)) {
          future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
              io.github.dailystruggle.rtp.api.RTPResult.Reason.NO_PERMISSION,
              getNoPermissionMessage(target)));
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

        // REGION's requirePermission is only known once resolved.
        if (apiTargetPermissionDenied(player, target, region)) {
          future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
              io.github.dailystruggle.rtp.api.RTPResult.Reason.NO_PERMISSION,
              getNoPermissionMessage(target)));
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
          TeleportSessions.current().processingPlayers.remove(uuid);
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
            RTPWorld<?> w = RTPRuntime.serverAccessor().getRTPWorld(td.selectedCoords.worldName());
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
        TeleportSessions.current().latestTeleportData.put(uuid, data);

        java.util.Set<String> biomeFilter = null;
        if (target.kind() == io.github.dailystruggle.rtp.api.RtpTarget.Kind.BIOME) {
          biomeFilter = java.util.Set.of(target.name());
        }

        io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask task =
            new io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask(
                new io.github.dailystruggle.rtp.api.selection.GenerationContext(player, player, biomeFilter),
                targetRegion);
        data.nextTask = task;
        TeleportSessions.current().processingPlayers.add(uuid);
        targetRegion.inFlightCalculations.incrementAndGet();
        // Fast path, mirroring the sync branch of RTPCmd.compute(): when a
        // pre-verified location is already cached for this player (its chunk is
        // kept-loaded, so no synchronous chunk I/O happens - S-005 safe), run
        // the pipeline inline instead of deferring to the next async scheduler
        // pulse. This makes a menu/GUI click teleport as immediately as the
        // /rtp command does. When a biome filter is specified, bypass fast-path
        // so pipeline evaluates biomes asynchronously.
        if (biomeFilter == null && targetRegion.hasLocation(uuid)) {
          task.run();
        } else {
          RTPRuntime.scheduler().runTaskAsynchronously(task);
        }
      } catch (Throwable t) {
        TeleportSessions.current().processingPlayers.remove(uuid);
        future.complete(io.github.dailystruggle.rtp.api.RTPResult.failure(
            io.github.dailystruggle.rtp.api.RTPResult.Reason.ERROR, String.valueOf(t.getMessage())));
      }
      return future;
    };

    io.github.dailystruggle.rtp.api.RTPAPI.cancelDelegate = uuid -> {
      TeleportData d = TeleportSessions.current().latestTeleportData.get(uuid);
      boolean pending = (d != null && !d.completed)
          || TeleportSessions.current().processingPlayers.contains(uuid);
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
      TeleportData d = TeleportSessions.current().latestTeleportData.get(uuid);
      return (d != null && !d.completed) || TeleportSessions.current().processingPlayers.contains(uuid);
    };

    // GUI-author SPI reads. Permission-gated target list + per-target status
    // for populating third-party menu items. Read-only:
    // these never trigger a teleport or charge a player - RTPAPI.teleport(...)
    // re-enforces every safety/permission/economy check regardless.
    io.github.dailystruggle.rtp.api.RTPAPI.allowedTargetsDelegate = uuid -> {
      java.util.List<io.github.dailystruggle.rtp.api.RtpTarget> out = new ArrayList<>();
      // A bare /rtp is always offered.
      out.add(io.github.dailystruggle.rtp.api.RtpTarget.defaultRegion());
      try {
        RTPPlayer player = RTPRuntime.serverAccessor().getPlayer(uuid);

        // The bare default target (added above) resolves, for this player, to a
        // concrete named region. Listing that same region again in the loop
        // below would surface it twice (e.g. the default region named
        // "default" appearing as both the bare default row and a region row),
        // so remember its name and skip it when enumerating named regions.
        String defaultRegionName = null;
        if (player != null) {
          try {
            Region defaultRegion = ActiveSelection.get().getRegion(player);
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
            new ArrayList<>(ActiveSelection.get().regionNames());
        sortedNames.sort(String.CASE_INSENSITIVE_ORDER);
        for (String name : sortedNames) {
          if (name == null || name.isBlank()) continue;
          if (defaultRegionName != null && defaultRegionName.equalsIgnoreCase(name)) {
            continue; // already offered as the bare default target
          }
          Region region = ActiveSelection.get().getRegion(name);
          if (region == null) continue;
          boolean requirePerm = region.getSettings().requirePermission();
          if (requirePerm
              && (player == null || !player.hasPermission("rtp.regions." + name))) {
            continue;
          }
          out.add(io.github.dailystruggle.rtp.api.RtpTarget.region(name));
        }

        // Configured worlds: enumerate worlds and gate on `rtp.worlds.<world>` / `rtp.worlds.*`
        // when requirePermission is enabled.
        java.util.Set<String> worldCandidates = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (RTPRuntime.serverAccessor() != null) {
          try {
            java.util.List<RTPWorld<?>> rtpWorlds = RTPRuntime.serverAccessor().getRTPWorlds();
            if (rtpWorlds != null) {
              for (RTPWorld<?> w : rtpWorlds) {
                if (w != null && w.name() != null && !w.name().isBlank()) {
                  worldCandidates.add(w.name());
                }
              }
            }
          } catch (Throwable ignored) {
          }
        }
        if (ActiveConfigs.get() != null && ActiveConfigs.get().multiConfigParserMap != null) {
          @SuppressWarnings("unchecked")
          MultiConfigParser<WorldKeys> worldMcp =
              (MultiConfigParser<WorldKeys>) ActiveConfigs.get().multiConfigParserMap.get(WorldKeys.class);
          if (worldMcp != null && worldMcp.configParserFactory != null) {
            for (String key : worldMcp.configParserFactory.map.keySet()) {
              if (key == null || key.isBlank()) continue;
              String clean = key.endsWith(".YML") ? key.substring(0, key.length() - 4) : key;
              if (clean.equalsIgnoreCase("default")) continue;
              worldCandidates.add(clean);
            }
          }
        }
        for (String worldName : worldCandidates) {
          if (worldName == null || worldName.isBlank()) continue;
          boolean requirePerm = false;
          if (ActiveConfigs.get() != null && ActiveConfigs.get().multiConfigParserMap != null) {
            @SuppressWarnings("unchecked")
            MultiConfigParser<WorldKeys> worldMcp =
                (MultiConfigParser<WorldKeys>) ActiveConfigs.get().multiConfigParserMap.get(WorldKeys.class);
            if (worldMcp != null && worldMcp.configParserFactory != null && worldMcp.configParserFactory.contains(worldName)) {
              ConfigParser<WorldKeys> wp = worldMcp.getParser(worldName);
              if (wp != null) {
                requirePerm = Boolean.parseBoolean(String.valueOf(wp.getConfigValue(WorldKeys.requirePermission, false)));
              }
            }
          }
          if (requirePerm && player != null
              && !player.hasPermission("rtp.worlds." + worldName)
              && !player.hasPermission("rtp.worlds.*")) {
            continue;
          }
          out.add(io.github.dailystruggle.rtp.api.RtpTarget.world(worldName));
        }

        // Configured / observed biomes: enumerate allowed biomes and gate on
        // `rtp.biome.<biome>` / `rtp.biome.*`.
        java.util.Set<String> biomeCandidates = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        @SuppressWarnings("unchecked")
        ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.BiomesKeys> biomesParser =
            (ActiveConfigs.get() != null) ? (ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.BiomesKeys>)
                ActiveConfigs.get().getParser(io.github.dailystruggle.rtp.common.configuration.enums.BiomesKeys.class) : null;
        if (biomesParser != null) {
          Object configuredBiomes = biomesParser.getConfigValue(
              io.github.dailystruggle.rtp.common.configuration.enums.BiomesKeys.biomes, null);
          if (configuredBiomes instanceof java.util.Collection<?> col) {
            for (Object obj : col) {
              if (obj != null && !obj.toString().isBlank()) {
                biomeCandidates.add(obj.toString().trim());
              }
            }
          }
          java.util.Map<String, Object> weights = biomesParser.getMap(
              io.github.dailystruggle.rtp.common.configuration.enums.BiomesKeys.biomeWeights);
          if (weights != null) {
            for (String b : weights.keySet()) {
              if (b != null && !b.isBlank()) {
                biomeCandidates.add(b.trim());
              }
            }
          }
        }
        // Also include observed biomes if available from spatial memory
        try {
          if (player != null) {
            biomeCandidates.addAll(io.github.dailystruggle.rtp.common.selection.region.BiomeMenuSource.observedBiomes(player.uuid()));
          } else {
            biomeCandidates.addAll(io.github.dailystruggle.rtp.common.selection.region.BiomeMenuSource.observedBiomes(null));
          }
        } catch (Throwable ignored) {
        }
        for (String biomeName : biomeCandidates) {
          if (biomeName == null || biomeName.isBlank()) continue;
          if (player != null
              && !player.hasPermission("rtp.biome." + biomeName.toLowerCase(Locale.ROOT))
              && !player.hasPermission("rtp.biome." + biomeName.toUpperCase(Locale.ROOT))
              && !player.hasPermission("rtp.biome.*")) {
            continue;
          }
          out.add(io.github.dailystruggle.rtp.api.RtpTarget.biome(biomeName));
        }

        // Network/peer regions (rtp-proxy-ADR-014): surface the live
        // `server:region` entries advertised across the network so a GUI / addon
        // can offer cross-server destinations alongside local ones. Permission
        // gating mirrors the `/rtp region=<server>:<region>` command path:
        // `rtp.servers.<server>` always, plus `rtp.regions.<region>` only when the
        // owning backend advertised the region as permission-gated - an open peer
        // region is no more restricted than the same region would be locally.
        // Reads are defensive - a missing/flaky network layer simply contributes
        // no extra targets.
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
                    && ((!player.hasPermission("rtp.servers." + serverId)
                            && !player.hasPermission("rtp.servers.*"))
                        || (registry.peerRegionRequiresPermission(serverId, regionKey)
                            && !player.hasPermission("rtp.regions." + regionKey)
                            && !player.hasPermission("rtp.regions.*")))) {
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
        RTPPlayer player = RTPRuntime.serverAccessor().getPlayer(uuid);
        if (player == null) {
          return new io.github.dailystruggle.rtp.api.RtpTargetStatus(
              io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability.UNKNOWN, 0L, 0.0);
        }

        // Network/peer target: resolves on a remote backend, so there is no
        // local Region to inspect. Gate on the same permissions the command
        // path uses: `rtp.servers.<server>` always, plus `rtp.regions.<region>`
        // only for a region the owning backend declared permission-gated. Report
        // reachability from the live peer registry. Cost is unknown locally (the
        // destination backend owns its economy), so 0.0 is reported here.
        if (target.kind() == io.github.dailystruggle.rtp.api.RtpTarget.Kind.NETWORK) {
          String serverId = target.serverId();
          String regionKey = target.name();
          if (apiTargetPermissionDenied(player, target, null)) {
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

        if (target.kind() == io.github.dailystruggle.rtp.api.RtpTarget.Kind.ACTION) {
          return new io.github.dailystruggle.rtp.api.RtpTargetStatus(
              io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability.READY,
              0L, 0.0, null, null, target.name());
        }

        if (target.kind() == io.github.dailystruggle.rtp.api.RtpTarget.Kind.COORDINATE) {
          boolean noPerm = apiTargetPermissionDenied(player, target, null);
          return new io.github.dailystruggle.rtp.api.RtpTargetStatus(
              noPerm
                  ? io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability.NO_PERMISSION
                  : io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability.READY,
              0L, 0.0, null, null, target.name());
        }

        // Check target permission first; REGION is gated after resolution below.
        boolean noPerm = apiTargetPermissionDenied(player, target, null);

        Region region;
        try {
          region = resolveApiRegion(target, player);
        } catch (RuntimeException ex) {
          region = null;
        }
        if (region == null || region.getWorld() == null) {
          // Fall back to default region if resolveApiRegion did not find a region for world or biome
          try {
            if (player != null) {
              region = ActiveSelection.get().getRegion(player);
            }
            if (region == null || region.getWorld() == null) {
              region = ActiveSelection.get().getRegionOrDefault("default");
            }
            if (region == null || region.getWorld() == null) {
              // Try selectionAPI.permRegionLookup
              for (Region r : ActiveSelection.get().permRegionLookup.values()) {
                if (r != null && r.getWorld() != null) {
                  region = r;
                  break;
                }
              }
            }
          } catch (Throwable ignored) {
          }
        }
        if (region == null || region.getWorld() == null) {
          return new io.github.dailystruggle.rtp.api.RtpTargetStatus(
              noPerm
                  ? io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability.NO_PERMISSION
                  : io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability.DISABLED,
              0L, 0.0,
              null, null, target.kind() == io.github.dailystruggle.rtp.api.RtpTarget.Kind.BIOME ? "Biome: " + target.name() : null);
        }

        if (!noPerm && apiTargetPermissionDenied(player, target, region)) {
          noPerm = true;
        }

        // Cost: region price plus the configured economy base price, unless the
        // player teleports for free. Funds check mirrors RTPCmd's balanceFloor.
        double cost = region.getSettings().price();
        boolean noFunds = false;
        RTPEconomy econ = RTPRuntime.economy();
        if (econ != null && !player.hasPermission("rtp.free")) {
          double floor = 0.0;
          ConfigParser<EconomyKeys> eco =
              (ConfigParser<EconomyKeys>) ActiveConfigs.get().getParser(EconomyKeys.class);
          if (eco != null) {
            cost += eco.getNumber(EconomyKeys.price, 0.0).doubleValue();
            if (target.kind() == io.github.dailystruggle.rtp.api.RtpTarget.Kind.BIOME) {
              cost += eco.getNumber(EconomyKeys.biomePrice, 0.0).doubleValue();
            }
            floor = eco.getNumber(EconomyKeys.balanceFloor, 0.0).doubleValue();
          }
          if ((econ.bal(uuid) - cost) < floor) {
            noFunds = true;
          }
        }

        // Remaining cooldown for this target region.
        long remaining = 0L;
        RegionSettings regSettings = region.getSettings();
        boolean hasRegionOverride = (regSettings != null && regSettings.cooldownMillis() != null && regSettings.cooldownMillis() >= 0);
        long targetCooldown = getCooldown(player, regSettings);
        long lastTp = hasRegionOverride
            ? getLastRegionTeleportTime(uuid, region.name)
            : getEffectiveLastTeleportTime(uuid);
        if (hasRegionOverride && lastTp <= 0L) {
          TeleportData prior = TeleportSessions.current().latestTeleportData.get(uuid);
          if (prior != null && prior.targetRegion != null && region.name.equalsIgnoreCase(prior.targetRegion.name)) {
            lastTp = prior.time;
          }
        }
        if (lastTp > 0L && targetCooldown > 0L) {
          long dt = System.currentTimeMillis() - lastTp;
          if (dt < 0) dt = 0L;
          remaining = Math.max(0L, targetCooldown - dt);
        }

        // PvP Combat Gate check
        boolean inCombat = false;
        long combatRemaining = 0L;
        try {
          if (io.github.dailystruggle.rtp.common.pvp.PvPGate.isEnabled()
              && (io.github.dailystruggle.rtp.common.pvp.PvPGate.isInCombat(uuid)
                  || io.github.dailystruggle.rtp.common.pvp.PvPGate.evaluate(uuid) != io.github.dailystruggle.rtp.api.hooks.PvPCombatAction.ALLOW)) {
            inCombat = true;
            combatRemaining = io.github.dailystruggle.rtp.common.pvp.PvPGate.combatRemainingMillis(uuid);
            if (combatRemaining <= 0L) combatRemaining = 1000L;
          }
        } catch (Throwable ignored) {
          // Defensive: combat hook failure reports not-in-combat; status must not break.
        }

        long targetDelay = getDelay(player, regSettings);

        io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability availability;
        if (noPerm) {
          availability = io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability.NO_PERMISSION;
        } else if (inCombat) {
          availability = io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability.IN_COMBAT;
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
        if (target.kind() == io.github.dailystruggle.rtp.api.RtpTarget.Kind.BIOME) {
          localLabel = "Biome: " + target.name();
        }
        try {
          localEnv = region.getWorld().environment();
          if (localLabel == null && target.kind() != io.github.dailystruggle.rtp.api.RtpTarget.Kind.WORLD) {
            // Cosmetic display label from the region's configured displayName
            // when it differs from the fallback region name (matching RtpTargetStatus
            // contract: "or null for default name").
            String regionDisplayName = region.displayName();
            if (regionDisplayName != null && !regionDisplayName.isEmpty()
                && !regionDisplayName.equals(region.name)) {
              localLabel = regionDisplayName;
            }
          }
        } catch (Throwable ignored) {
          // Defensive: env/label enrichment is a cosmetic hint and must never break status.
        }
        return new io.github.dailystruggle.rtp.api.RtpTargetStatus(
            availability, remaining, Math.max(0.0, cost), null, localEnv, localLabel, targetDelay, combatRemaining);
      } catch (RuntimeException ex) {
        log(Level.WARNING, "[RTP API] getTargetStatus failed: " + ex.getMessage(), ex);
        return new io.github.dailystruggle.rtp.api.RtpTargetStatus(
            io.github.dailystruggle.rtp.api.RtpTargetStatus.Availability.UNKNOWN, 0L, 0.0);
      }
    };

    // Runtime-health snapshot for dashboard tiles. Sampled by the active metrics
    // binding, not computed on the caller's thread (no main-thread cost / chunk I/O).
    io.github.dailystruggle.rtp.api.RTPAPI.metricsSnapshotDelegate = () -> CoreMetrics.instance().snapshot();
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
      TeleportSessions.current().processingPlayers.remove(uuid);
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
        RTPWorld<?> w = RTPRuntime.serverAccessor().getRTPWorld(td.selectedCoords.worldName());
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
    TeleportSessions.current().latestTeleportData.put(uuid, data);

    io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask task =
        new io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask(
            new io.github.dailystruggle.rtp.api.selection.GenerationContext(player, player, null),
            targetRegion);
    data.nextTask = task;
    TeleportSessions.current().processingPlayers.add(uuid);
    targetRegion.inFlightCalculations.incrementAndGet();
    RTPRuntime.scheduler().runTaskAsynchronously(task);
  }

  private static String getNoPermissionMessage(io.github.dailystruggle.rtp.api.RtpTarget target) {
    if (ActiveConfigs.get() != null) {
      Object val = ActiveConfigs.get().getConfigValue(PlayerMessages.noPerms, null);
      if (val instanceof String msg && !msg.isBlank()) {
        return msg;
      }
    }
    return "Missing permission for " + target;
  }

  /**
   * Target permission gate shared by {@code RTPAPI.teleport} and {@code getTargetStatus}, so an
   * addon or stale GUI cannot reach a target its status reports as {@code NO_PERMISSION}.
   * Rules mirror {@code getAllowedTargets}: WORLD needs {@code rtp.worlds.<w>|*} only when the
   * world sets requirePermission; BIOME needs {@code rtp.biome.<b>|*}; REGION needs
   * {@code rtp.regions.<r>} when the region sets requirePermission (evaluated only once
   * {@code region} is resolved); NETWORK needs {@code rtp.servers.<s>|*}, plus
   * {@code rtp.regions.<r>|*} when the peer advertises the region as gated.
   *
   * @param player online player; {@code null} is denied
   * @param target requested target; {@code null} is denied
   * @param region resolved region for REGION targets, or {@code null} to defer that check
   * @return {@code true} if the player lacks a permission the target requires
   */
  static boolean apiTargetPermissionDenied(
      RTPPlayer player, io.github.dailystruggle.rtp.api.RtpTarget target, Region region) {
    if (player == null || target == null) return true;
    switch (target.kind()) {
      case NETWORK: {
        String serverId = target.serverId();
        String regionKey = target.name();
        boolean peerRegionGated = false;
        try {
          io.github.dailystruggle.rtp.common.network.NetworkModeBootstrap live =
              io.github.dailystruggle.rtp.common.network.NetworkModeBootstrap.LIVE;
          if (live != null && live.peerRegionRegistry() != null) {
            peerRegionGated = live.peerRegionRegistry().peerRegionRequiresPermission(serverId, regionKey);
          }
        } catch (Throwable ignored) {
          // Defensive: a flaky network layer falls back to the server-level gate only.
        }
        return (!player.hasPermission("rtp.servers." + serverId)
                && !player.hasPermission("rtp.servers.*"))
            || (peerRegionGated
                && !player.hasPermission("rtp.regions." + regionKey)
                && !player.hasPermission("rtp.regions.*"));
      }
      case WORLD: {
        if (ActiveConfigs.get() == null) return false;
        ConfigParser<WorldKeys> worldParser = ActiveConfigs.get().getWorldParser(target.name());
        boolean requirePerm = worldParser != null
            && Boolean.parseBoolean(
                worldParser.getConfigValue(WorldKeys.requirePermission, false).toString());
        return requirePerm
            && !player.hasPermission("rtp.worlds." + target.name())
            && !player.hasPermission("rtp.worlds.*");
      }
      case BIOME:
        return !player.hasPermission("rtp.biome." + target.name().toLowerCase(Locale.ROOT))
            && !player.hasPermission("rtp.biome." + target.name().toUpperCase(Locale.ROOT))
            && !player.hasPermission("rtp.biome.*");
      case REGION:
        return region != null
            && region.getSettings().requirePermission()
            && !player.hasPermission("rtp.regions." + target.name());
      case COORDINATE: {
        String worldName = target.worldName();
        if (worldName != null && !worldName.isBlank() && ActiveConfigs.get() != null) {
          ConfigParser<WorldKeys> worldParser = ActiveConfigs.get().getWorldParser(worldName);
          boolean requirePerm = worldParser != null
              && Boolean.parseBoolean(
                  worldParser.getConfigValue(WorldKeys.requirePermission, false).toString());
          if (requirePerm
              && !player.hasPermission("rtp.worlds." + worldName)
              && !player.hasPermission("rtp.worlds.*")) {
            return true;
          }
        }
        return false;
      }
      case ACTION:
      case DEFAULT:
      default:
        return false;
    }
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
        return ActiveSelection.get().getRegionOrDefault(target.name());
      case WORLD: {
        ConfigParser<WorldKeys> worldParser = ActiveConfigs.get().getWorldParser(target.name());
        String regionName = (worldParser == null)
            ? "default"
            : worldParser.getConfigValue(WorldKeys.region, "default").toString();
        return ActiveSelection.get().getRegionOrDefault(regionName);
      }
      case BIOME: {
        // Resolve the best region that observes or accommodates this biome
        String best = io.github.dailystruggle.rtp.common.selection.region.BiomeMenuSource.bestRegionForBiome(
            player != null ? player.uuid() : null, target.name(), null);
        if (best != null) {
          Region r = ActiveSelection.get().getRegion(best);
          if (r != null) return r;
        }
        if (player != null) {
          return ActiveSelection.get().getRegion(player);
        }
        return ActiveSelection.get().getRegionOrDefault("default");
      }
      case ACTION:
      case COORDINATE:
        throw new IllegalArgumentException(target.kind() + " targets cannot be resolved to a region");
      case DEFAULT:
      default:
        if (player == null) {
          throw new IllegalArgumentException("default-region target requires an online player");
        }
        return ActiveSelection.get().getRegion(player);
    }
  }

  // ADR-115 seam 4: per-instance state lives in its owning package; installed when this
  // instance becomes current (see the constructor). Field-init order is unchanged.
  private final TeleportSessions sessions = new TeleportSessions();
  public io.github.dailystruggle.rtp.common.playerData.FileBinStore fileBinStore;
  /**
   * Teleport-limit guard seam (ADR-068). Fronts the usage cap and adds best-effort local
   * persistence of the rolling window so it survives a restart (parity with the
   * already-persisted cooldown). The cross-server durable/proxy layers of ADR-068 slot in
   * behind {@link ActiveDatabase#teleportLimitStore()}.
   */
  private final io.github.dailystruggle.rtp.common.playerData.TeleportLimitStore limitStore =
      new io.github.dailystruggle.rtp.common.playerData.LocalTeleportLimitStore(sessions.usageCaps, true);
  private final Map<String, ScanTask> scanTaskMap = new ConcurrentHashMap<>();
  private final ConcurrentLinkedQueue<RTPChunk<?>> unloadQueue = new ConcurrentLinkedQueue<>();
  /**
   * Optional cross-server messaging bus. Field type is the {@code RTPNetworkManager}
   * interface so {@code rtp-core} carries no symbolic reference to any concrete driver
   * class (ADR-024). Constructed reflectively below when network YAML enables a backend.
   */
  public io.github.dailystruggle.rtp.common.network.RTPNetworkManager networkManager;

  /**
   * Resolves the effective last teleport timestamp for a player across local and network storage.
   * Takes {@code max(local, shared)} so network latency or clock skew never shortens the local view.
   *
   * @param uuid player UUID
   * @return epoch milliseconds, or 0 if no teleport recorded
   */
  public static long getEffectiveLastTeleportTime(UUID uuid) {
    if (uuid == null) return 0L;
    long localTime = 0L;
    RTP inst = instance;
    if (inst != null) {
      TeleportData localData = TeleportSessions.current().latestTeleportData.get(uuid);
      if (localData != null) {
        localTime = localData.time;
      }
    }
    long sharedTime = 0L;
    try {
      if (inst != null && ActiveDatabase.accessor() instanceof AbstractSQLDatabaseAccessor sql) {
        io.github.dailystruggle.rtp.common.database.options.NetworkStateBinding binding = sql.getNetworkStateBinding();
        if (binding != null) {
          sharedTime = binding.getLastTeleportTime(uuid);
        }
      }
      if (sharedTime <= 0L && inst != null && inst.networkManager != null) {
        sharedTime = inst.networkManager.getLastTeleportTime(uuid);
      }
    } catch (Throwable ignored) {
      // Defensive: network read failure degrades safely to local time
    }
    return Math.max(localTime, sharedTime);
  }

  /**
   * Updates the shared last teleport timestamp across the network transport.
   *
   * @param uuid player UUID
   * @param epochMillis timestamp in milliseconds
   */
  public static void updateSharedLastTeleportTime(UUID uuid, long epochMillis) {
    if (uuid == null) return;
    RTP inst = instance;
    if (inst == null) return;
    try {
      if (ActiveDatabase.accessor() instanceof AbstractSQLDatabaseAccessor sql) {
        io.github.dailystruggle.rtp.common.database.options.NetworkStateBinding binding = sql.getNetworkStateBinding();
        if (binding != null) {
          binding.setLastTeleportTime(uuid, epochMillis);
        }
      }
      if (inst.networkManager != null) {
        inst.networkManager.setLastTeleportTime(uuid, epochMillis);
      }
    } catch (Throwable t) {
      log(Level.FINE, "[RTP] updateSharedLastTeleportTime failed: " + t.getMessage());
    }
  }

  /**
   * Resolves the timestamp of the player's last teleport to a specific region.
   *
   * @param uuid player UUID
   * @param regionName target region name
   * @return epoch milliseconds, or 0 if no teleport to this region recorded
   */
  public static long getLastRegionTeleportTime(UUID uuid, String regionName) {
    if (uuid == null || regionName == null) return 0L;
    RTP inst = instance;
    if (inst == null) return 0L;
    Map<String, Long> map = TeleportSessions.current().regionTeleportTimes.get(uuid);
    if (map == null) return 0L;
    Long time = map.get(regionName.toLowerCase(Locale.ROOT));
    return time != null ? time : 0L;
  }

  /**
   * Records the timestamp of the player's last teleport to a specific region.
   *
   * @param uuid player UUID
   * @param regionName target region name
   * @param epochMillis timestamp in milliseconds
   */
  public static void setLastRegionTeleportTime(UUID uuid, String regionName, long epochMillis) {
    if (uuid == null || regionName == null) return;
    RTP inst = instance;
    if (inst == null) return;
    TeleportSessions.current().regionTeleportTimes
        .computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
        .put(regionName.toLowerCase(Locale.ROOT), epochMillis);
  }

  /**
   * Resolves effective last teleport time for a player, preferring region-specific if recorded.
   */
  public static long getEffectiveLastTeleportTime(UUID uuid, String regionName) {
    if (uuid == null) return 0L;
    if (regionName != null) {
      long regTime = getLastRegionTeleportTime(uuid, regionName);
      if (regTime > 0L) return regTime;
    }
    return getEffectiveLastTeleportTime(uuid);
  }

  /**
   * Resolves warmup delay through the precedence hierarchy:
   * permission bypass -> permission override -> region setting -> global config default.
   */
  public static long getDelay(RTPCommandSender sender, io.github.dailystruggle.rtp.common.selection.region.RegionSettings settings) {
    if (sender == null) return 0L;
    if (settings != null && settings.delayMillis() != null && settings.delayMillis() >= 0) {
      if (sender.hasPermission("rtp.nodelay") || sender.hasPermission("rtp.noDelay")) {
        return 0L;
      }
      int permOverride = ParsePermissions.getInt(sender, "rtp.delay.");
      if (permOverride >= 0) {
        return TimeUnit.SECONDS.toMillis(permOverride);
      }
      return settings.delayMillis();
    }
    return sender.delay();
  }

  /**
   * Resolves cooldown through the precedence hierarchy:
   * permission bypass -> permission override -> region setting -> global config default.
   */
  public static long getCooldown(RTPCommandSender sender, io.github.dailystruggle.rtp.common.selection.region.RegionSettings settings) {
    if (sender == null) return 0L;
    if (settings != null && settings.cooldownMillis() != null && settings.cooldownMillis() >= 0) {
      if (sender.hasPermission("rtp.nocooldown") || sender.hasPermission("rtp.noCooldown")) {
        return 0L;
      }
      int permOverride = ParsePermissions.getInt(sender, "rtp.cooldown.");
      if (permOverride >= 0) {
        return TimeUnit.SECONDS.toMillis(permOverride);
      }
      return settings.cooldownMillis();
    }
    return sender.cooldown();
  }

  /**
   * Wraps the installed platform {@link RTPRuntime#scheduler()} in a profiling decorator
   * to track wall-clock execution time of sync/async tasks. Idempotent.
   */
  private static void installProfilingScheduler() {
    synchronized (RTP.class) {
      RTPScheduler current = RTPRuntime.scheduler();
      if (!(current instanceof io.github.dailystruggle.rtp.common.metrics.ProfilingRTPScheduler)) {
        RTPRuntime.setScheduler(
            new io.github.dailystruggle.rtp.common.metrics.ProfilingRTPScheduler(current));
      }
    }
  }

  public RTP() {
    if (RTPRuntime.serverAccessor() == null) throw new IllegalStateException("null serverAccessor");
    if (RTPRuntime.scheduler() == null) throw new IllegalStateException("null scheduler");

    // Wrap the platform scheduler in a profiling decorator so the wall-clock cost
    // of RTP's own sync (main-thread / region) and async tasks is attributable
    // and reportable, independent of whole-server MSPT. Idempotent: never
    // double-wrap if a previous instance already installed one.
    installProfilingScheduler();

    // Install the active scheduler into RTPRunnable so tasks can self-dispatch onto the
    // correct thread via RTPRunnable#schedule() (entity/region/async routing).
    RTPRunnable.scheduler = RTPRuntime.scheduler();

    MiscTasks.setSync((RTPTaskPipe) RTPRuntime.serverAccessor().createTaskPipe());
    MiscTasks.setAsync((RTPTaskPipe) RTPRuntime.serverAccessor().createTaskPipe());
    MiscTasks.setStartup((RTPTaskPipe) RTPRuntime.serverAccessor().createTaskPipe());
    MiscTasks.setCancel((RTPTaskPipe) RTPRuntime.serverAccessor().createTaskPipe());
    if (MiscTasks.cancel() instanceof TimeBoundTaskPipe timeBoundTaskPipe) {
      timeBoundTaskPipe.setAvailableTime(Long.MAX_VALUE);
    }
    RTPAPI.setServerAccessor(RTPRuntime.serverAccessor());
    TeleportSessions.install(sessions);
    ScanTasks.install(scanTaskMap);
    ActiveDatabase.setAccessor(null);
    ActiveDatabase.setTeleportLimitStore(limitStore);
    MiscTasks.setChunksToUnload(unloadQueue);
    instance = this;

    registerDefaultShapes();
    registerDefaultVerticalAdjustors();

    ActiveConfigs.set(new Configs(RTPRuntime.serverAccessor().getPluginDirectory()));

    // Opt-in core backlog prefilter provider; addons that bind later replace it.
    if (RTPAPI.hooks != null) {
      io.github.dailystruggle.rtp.common.hooks.AnvilBatchPrefilterProvider.bindIfEnabled(
          RTPAPI.hooks.anvilPrefilter());
    }

    MiscTasks.startup().add(new RTPRunnable(() -> {
      ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) ActiveConfigs.get().getParser(ConfigKeys.class);
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

    trackedTasks.add(RTPRuntime.scheduler().runTaskTimerAsynchronously(
            MemoryTracker::runDiagnostics,
      1200L,
      1200L));

    // 1 Hz MSPT + heap ring sampler. Feeds the METRIC_SPARKLINE chart kind
    // (/rtp visualization sparkline) with ~2 minutes of rolling history at
    // 128 slots. Uses metrics.snapshot() which is platform-aware and
    // already wired with the active MetricsBinding by the platform adapter.
    trackedTasks.add(RTPRuntime.scheduler().runTaskTimerAsynchronously(
            () -> {
                try {
                    CoreMetrics.instance().snapshotRing().recordFromSnapshot(CoreMetrics.instance().snapshot());
                } catch (Throwable ignored) {
                    // Sampler shall never poison the scheduler thread.
                }
            },
            20L,
            20L));

    io.github.dailystruggle.rtp.common.tools.PerformanceTracker.start(RTPRuntime.scheduler());
    trackedTasks.add(RTPRuntime.scheduler().runTaskTimerAsynchronously(
        () -> {
          if (ActiveDatabase.accessor() != null) {
            // Rewrite the cached-locations table from authoritative in-memory
            // state so already-consumed locations cannot leak across restarts.
            // Must run BEFORE flushDirtyCache so the wipe-then-write ordering
            // survives into the shared writeQueue drain.
            ActiveDatabase.accessor().rebuildCachedLocationsFromMemory();
            ActiveDatabase.accessor().flushDirtyCache();
          }
        },
        6000,
        6000));
    trackedTasks.add(RTPRuntime.scheduler().runTaskTimerAsynchronously(
        () -> {
          if (ActiveDatabase.accessor() instanceof AbstractSQLDatabaseAccessor sqlDatabaseAccessor) {
            sqlDatabaseAccessor.flush();
          }
        },
        60,
        60));

    updateRetentionWindow();

    // Periodic binned cleaner for memory, disk file bins, and shared database
    trackedTasks.add(RTPRuntime.scheduler().runTaskTimerAsynchronously(
        () -> {
          long now = System.currentTimeMillis();
          TeleportSessions.current().latestTeleportData.purgeExpired(now);
          TeleportSessions.current().priorTeleportData.purgeExpired(now);
          if (fileBinStore != null) {
            fileBinStore.purgeExpired(now);
          }
          if (ActiveDatabase.accessor() instanceof AbstractSQLDatabaseAccessor sql) {
            sql.purgeStaleTeleportData(TeleportSessions.current().latestTeleportData.getRetentionMs());
          }
        },
        1200L,
        1200L));

    // Both pulses fire every tick; per-pulse budgets come from TickBudget (performance.yml).
    long syncTime = TimeUnit.MILLISECONDS.toNanos(io.github.dailystruggle.rtp.common.tasks.tick.TickBudget.DEFAULT_SYNC_MS);
    trackedTasks.add(RTPRuntime.scheduler().runTaskTimer(new io.github.dailystruggle.rtp.common.tasks.tick.SyncTaskProcessing(syncTime), 1, 1));

    // Drive the world-scan on-screen progress bar from a platform-neutral main/server-thread
    // timer (once per second). The platform RTPServerAccessor renders it (boss-bar on
    // Bukkit/Fabric/NeoForge, no-op elsewhere); an empty scanBossBar template disables it.
    trackedTasks.add(RTPRuntime.scheduler().runTaskTimer(
        io.github.dailystruggle.rtp.common.tasks.tick.ScanProgressBars::update, 20, 20));

    long asyncTime = TimeUnit.MILLISECONDS.toNanos(io.github.dailystruggle.rtp.common.tasks.tick.TickBudget.DEFAULT_ASYNC_MS);
    trackedTasks.add(RTPRuntime.scheduler().runTaskTimerAsynchronously(new io.github.dailystruggle.rtp.common.tasks.tick.AsyncTaskProcessing(asyncTime), 1, 1));

    // The static initializer ran before the platform set the scheduler, so the wave
    // accumulator for batchInterval triggers (ADR-093) can only be scheduled here.
    PhysicalTriggerManager.instance().start();

    // Discover and load platform-agnostic addons once core init has settled. Deferred via a
    // startup task so RTPAPI delegates installed by the platform adapter after construction are
    // visible to RTPAddon#onLoad (the load is idempotent; late programmatic registrations are
    // loaded eagerly by AddonRegistry#register).
    MiscTasks.startup().add(new RTPRunnable(() -> {
      if (RTPRuntime.serverAccessor() != null) {
        try {
          RTPRuntime.serverAccessor().getPlayerLifecycleHook().onPlayerQuit(uuid -> {
            if (uuid == null) return;
            io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask pending =
                DeathTeleports.pending().remove(uuid);
            if (pending != null) {
              pending.completeDeathTeleport(false);
            }
            if (ActionServices.actionManager() != null) {
              ActionServices.actionManager().handlePlayerQuit(uuid);
            }
          });
        } catch (Throwable t) {
          log(Level.FINE, "[RTP] player quit hook registration in RTP init skipped: " + t);
        }
      }
      addons.discover();
      // Also scan an optional RTP-owned folder so operators can drop addon jars into
      // <pluginDir>/addons instead of plugins/, where the server's plugin loader would
      // reject them for lacking a plugin.yml. A missing/empty folder is a no-op.
      if (RTPRuntime.serverAccessor() != null) {
        File pluginDir = RTPRuntime.serverAccessor().getPluginDirectory();
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
    }, 0));

  }

  /**
   * Recalculates the binned retention window and bucket duration from the configured teleport cooldown.
   * Derives retention as a multiple (2x) of the teleport cooldown without requiring manual operator knobs.
   */
  public void updateRetentionWindow() {
    long cdMillis = 60_000L;
    if (ActiveConfigs.get() != null) {
      ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) ActiveConfigs.get().getParser(ConfigKeys.class);
      if (configParser != null) {
        Object val = configParser.getConfigValue(ConfigKeys.teleportCooldown, 0);
        long parsed = ConfigParser.parseSecondsDurationMillis(val, 0L);
        if (parsed > 0) cdMillis = parsed;
      }
    }
    long retentionMs = Math.max(cdMillis * 2L, 60_000L);
    long binWidthMs = Math.min(Math.max(cdMillis, 30_000L), 300_000L);

    TeleportSessions.current().latestTeleportData.setRetentionMs(retentionMs);
    TeleportSessions.current().priorTeleportData.setRetentionMs(retentionMs);

    File baseDir = (RTPRuntime.serverAccessor() != null)
        ? new File(RTPRuntime.serverAccessor().getPluginDirectory(), "data" + File.separator + "bins")
        : new File("plugins/RTP/data/bins");
    if (this.fileBinStore == null) {
      this.fileBinStore = new io.github.dailystruggle.rtp.common.playerData.FileBinStore(baseDir, binWidthMs, retentionMs);
    } else {
      this.fileBinStore.setRetentionMs(retentionMs);
    }
  }

  /**
   * Reflectively constructs the Redis-backed {@code RTPNetworkManager}.
   * Returns null if RedisManager or Jedis is not on the classpath (ADR-024).
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

  @SuppressWarnings("unchecked") // untyped YAML map values narrowed to Map<String,Object> after instanceof
  public static void handleMigration(String previousState, String currentState) {
    if (previousState.equalsIgnoreCase("yaml") &&
        (currentState.equalsIgnoreCase("sqlite") ||
            currentState.equalsIgnoreCase("h2") ||
            currentState.equalsIgnoreCase("mysql") ||
            currentState.equalsIgnoreCase("postgresql"))) {
      RTPRuntime.serverAccessor().log(Level.INFO, "&aDatabase engine change detected. Initiating background migration from YAML to SQL...");
      MiscTasks.async().add(new RTPRunnable(() -> {
        File pluginDir = RTPRuntime.serverAccessor().getPluginDirectory();
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
              teleportData.sender = RTPRuntime.serverAccessor().getSender(UUID.fromString(dataMap.get("senderId").toString()));
              teleportData.time = ((Number) dataMap.getOrDefault("time", 0L)).longValue();
              Object oWorld = dataMap.get("originalWorldName");
              if (oWorld != null) teleportData.originalCoords = new RTPCoords(oWorld.toString(), ((Number) dataMap.get("originalX")).intValue(), ((Number) dataMap.get("originalY")).intValue(), ((Number) dataMap.get("originalZ")).intValue());
              Object sWorld = dataMap.get("selectedWorldName");
              if (sWorld != null) teleportData.selectedCoords = new RTPCoords(sWorld.toString(), ((Number) dataMap.get("selectedX")).intValue(), ((Number) dataMap.get("selectedY")).intValue(), ((Number) dataMap.get("selectedZ")).intValue());
              teleportData.attempts = ((Number) dataMap.getOrDefault("attempts", 0)).longValue();
              teleportData.cost = ((Number) dataMap.getOrDefault("cost", 0.0)).doubleValue();
              teleportData.targetRegion = ActiveSelection.get().getRegion(dataMap.getOrDefault("region", "default").toString());
              teleportData.completed = true;

              if (ActiveDatabase.accessor() instanceof AbstractSQLDatabaseAccessor sqlDb) {
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
                    teleportData.sender = RTPRuntime.serverAccessor().getSender(UUID.fromString(senderIdObj.toString()));
                    teleportData.time = ((Number) dataMap.getOrDefault("time", 0L)).longValue();
                    Object oWorld = dataMap.get("originalWorldName");
                    if (oWorld != null) teleportData.originalCoords = new RTPCoords(oWorld.toString(), ((Number) dataMap.get("originalX")).intValue(), ((Number) dataMap.get("originalY")).intValue(), ((Number) dataMap.get("originalZ")).intValue());
                    Object sWorld = dataMap.get("selectedWorldName");
                    if (sWorld != null) teleportData.selectedCoords = new RTPCoords(sWorld.toString(), ((Number) dataMap.get("selectedX")).intValue(), ((Number) dataMap.get("selectedY")).intValue(), ((Number) dataMap.get("selectedZ")).intValue());
                    teleportData.attempts = ((Number) dataMap.getOrDefault("attempts", 0)).longValue();
                    teleportData.cost = ((Number) dataMap.getOrDefault("cost", 0.0)).doubleValue();
                    teleportData.targetRegion = ActiveSelection.get().getRegion(dataMap.getOrDefault("region", "default").toString());
                    teleportData.completed = true;

                    if (ActiveDatabase.accessor() instanceof AbstractSQLDatabaseAccessor sqlDb) {
                      sqlDb.cacheValue(teleportData);
                      success = true;
                    }
                  } catch (Exception ignored) {}
                }
                if (success) {
                  boolean renamed = file.renameTo(new File(file.getAbsolutePath() + ".migrated"));
                  if (!renamed) {
                    RTP.log(Level.FINE, "[RTP] Failed to rename migrated file: " + file.getName());
                  }
                }
              } catch (Exception ignored) {}
            }
          }
        }

        if (ActiveDatabase.accessor() instanceof AbstractSQLDatabaseAccessor sqlDb) {
          sqlDb.flush();
        }
      }, 0));
    }
  }

  @SuppressWarnings("unchecked") // heterogeneous factoryMap holds the shape Factory under a raw value type
  public static void registerDefaultShapes() {
    Factory<Shape<?>> shapeFactory = (Factory<Shape<?>>) FactoryRegistry.get(FactoryNames.shape);
    if (shapeFactory == null) {
      shapeFactory = new Factory<>();
      FactoryRegistry.map().put(FactoryNames.shape, shapeFactory);
    }
    if (ActiveSelection.get() != null && ActiveSelection.get().shapeFactory == null) {
      ActiveSelection.get().shapeFactory = shapeFactory;
    }
    addShape(new Circle());
    addShape(new CircleOptimizedDualLayer());
    addShape(new CircleOptimizedDualLayer("CIRCLE"));
    addShape(new Ellipse());
    addShape(new Square());
    addShape(new SquareOptimizedDualLayer());
    addShape(new SquareOptimizedDualLayer("SQUARE"));
    addShape(new Rectangle());
    addShape(new Circle_Normal());
    addShape(new Square_Normal());
    addShape(new Polygon());
  }

  @SuppressWarnings("unchecked") // heterogeneous factoryMap holds the vert Factory under a raw value type
  public static void registerDefaultVerticalAdjustors() {
    Factory<VerticalAdjustor<?>> vertFactory = (Factory<VerticalAdjustor<?>>) FactoryRegistry.get(FactoryNames.vert);
    if (vertFactory == null) {
      vertFactory = new Factory<>();
      FactoryRegistry.map().put(FactoryNames.vert, vertFactory);
    }
    addVerticalAdjustor(new LinearAdjustor(new ArrayList<>()));
    addVerticalAdjustor(new JumpAdjustor(new ArrayList<>()));
    addVerticalAdjustor(new FixedAdjustor(new ArrayList<>()));
  }

  @SuppressWarnings("unchecked") // heterogeneous factoryMap holds the shape Factory under a raw value type
  public static void addShape(Shape<?> shape) {
    Factory<Shape<?>> shapeFactory =
        (Factory<Shape<?>>) FactoryRegistry.map().computeIfAbsent(FactoryNames.shape, k -> new Factory<Shape<?>>());
    shapeFactory.add(shape.name, shape);
    if (ActiveSelection.get() != null && ActiveSelection.get().shapeFactory == null) {
      ActiveSelection.get().shapeFactory = shapeFactory;
    }
  }

  @SuppressWarnings("unchecked") // heterogeneous factoryMap holds the vert Factory under a raw value type
  public static void addVerticalAdjustor(VerticalAdjustor<?> verticalAdjustor) {
    Factory<VerticalAdjustor<?>> vertFactory = (Factory<VerticalAdjustor<?>>)
        FactoryRegistry.map().computeIfAbsent(FactoryNames.vert, k -> new Factory<VerticalAdjustor<?>>());
    vertFactory.add(verticalAdjustor.name, verticalAdjustor);
  }

  public static RTP getInstance() {
    return instance;
  }

  /**
   * Runs {@code RTP}'s static initializer if it has not run yet; otherwise a no-op.
   *
   * <p>Platform entry points shall call this before {@link RTPRuntime#setServerAccessor}.
   * The static initializer registers the default shapes, whose lang-file load is skipped while
   * no accessor is installed; the former {@code RTP.serverAccessor} field write forced that
   * ordering implicitly (ADR-115 invariant 3).
   */
  public static void ensureInitialized() {
    // Invoking any static method triggers class initialization (JLS 12.4.1).
  }

  public Object getPlugin() {
    return RTPRuntime.serverAccessor().getPlugin();
  }

  /**
   * Returns true when the {@code logging.yml} parser hasn't been built yet, so
   * sub-INFO records would otherwise leak past the (yet-unfiltered) sink.
   */
  private static boolean loggingParserUnavailable() {
    Configs c = ActiveConfigs.get();
    if (c == null) return true;
    try {
      return c.getParser(
              io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys.class)
          == null;
    } catch (Throwable t) {
      return true;
    }
  }

  /** Delegates to {@link RTPRuntime#flushPendingLogs()}; kept for platform and addon callers. */
  public static void flushPendingLogs() {
    RTPRuntime.flushPendingLogs();
  }

  /** Delegates to {@link RTPRuntime#log(Level, String)}; kept for platform and addon callers. */
  public static void log(Level level, String str) {
    RTPRuntime.log(level, str);
  }

  /** Delegates to {@link RTPRuntime#log(Level, String, Throwable)}; kept for platform and addon callers. */
  public static void log(Level level, String str, Throwable throwable) {
    RTPRuntime.log(level, str, throwable);
  }

  @SuppressWarnings("unchecked") // heterogeneous multiConfigParserMap keyed by enum class; WorldKeys parser cast is safe
  public static RTPWorld getWorld(RTPPlayer player) {
    // get region from world name, check for overrides
    Set<String> worldsAttempted = new HashSet<>();
    String worldName = player.getLocation().world().name();
    MultiConfigParser<WorldKeys> worldParsers =
        (MultiConfigParser<WorldKeys>) ActiveConfigs.get().multiConfigParserMap.get(WorldKeys.class);
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

    return RTPRuntime.serverAccessor().getRTPWorld(worldName);
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
    for (Map.Entry<UUID, TeleportData> e : TeleportSessions.current().latestTeleportData.entrySet()) {
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
    io.github.dailystruggle.rtp.common.commands.editor.EditorLiveFeed.stopActive("plugin disabled");
    io.github.dailystruggle.rtp.common.commands.editor.EditorExtensions.unregisterAll();

    if (ActiveDatabase.accessor() != null) {
      if (ActiveDatabase.accessor() instanceof AbstractSQLDatabaseAccessor sqlDatabaseAccessor) {
        log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop SQL accessor flush (WAL checkpoint)");
        sqlDatabaseAccessor.flush();
      }
      // Rewrite the cached-locations table from authoritative in-memory state
      // BEFORE flushing the dirtyCache. Without this, rows for locations that
      // were consumed in prior flush cycles (or whose delete enqueue lost the
      // write-vs-delete ordering race in processQueries) survive into the next
      // startup and get re-hydrated as ghosts.
      log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop rebuildCachedLocationsFromMemory");
      ActiveDatabase.accessor().rebuildCachedLocationsFromMemory();
      // Drain dirtyCache -> writeQueue (this enqueues async writes via setValue().thenAccept(),
      // but getTable returns a completed future synchronously, so the enqueue runs inline).
      log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop flushDirtyCache");
      ActiveDatabase.accessor().flushDirtyCache();
      // CRITICAL: actually drain the writeQueue (and deleteQueue) to disk. Previously this
      // step was missing on shutdown, so every cached-location save/delete that accumulated
      // between the 5-minute periodic flushDirtyCache cycle and server stop was lost -
      // which is why the kept cache appeared empty after restart. Must happen BEFORE
      // stop.set(true) below, because processQueries bails immediately if stop is set.
      log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop processQueries(MAX) drain BEFORE stop.set(true)");
      ActiveDatabase.accessor().processQueries(Long.MAX_VALUE);
    }

    log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop StopPipes miscAsyncTasks/miscSyncTasks");
    MiscTasks.async().stop();
    MiscTasks.sync().stop();

    log(Level.FINE,
        "[SHUTDOWN_TRACE] RTP.stop CancelTracked count=" + trackedTasks.size());
    for (Object task : trackedTasks) {
      log(Level.FINER, "[SHUTDOWN_TRACE] RTP.stop cancelTrackedTask task=" + task);
      RTPRuntime.scheduler().cancelTask(task);
    }
    trackedTasks.clear();
    // Cancels the wave timer and drops triggers so the next enable schedules afresh.
    PhysicalTriggerManager.instance().close();

    // Delayed arrival releases never run once the plugin is disabled (S-002).
    int arrivalHolds =
        io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask.flushArrivalReservations();
    log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop flushArrivalReservations released=" + arrivalHolds);

    log(Level.FINE,
        "[SHUTDOWN_TRACE] RTP.stop permRegionLookup shutDown count="
            + ActiveSelection.get().permRegionLookup.size());
    for (Region r : ActiveSelection.get().permRegionLookup.values()) {
      log(Level.FINER, "[SHUTDOWN_TRACE] RTP.stop permRegion.shutDown name=" + r.name);
      r.shutDown();
    }
    ActiveSelection.get().permRegionLookup.clear();

    log(Level.FINE,
        "[SHUTDOWN_TRACE] RTP.stop tempRegions shutDown count=" + ActiveSelection.get().tempRegions.size());
    for (Region r : ActiveSelection.get().tempRegions.values()) {
      log(Level.FINER, "[SHUTDOWN_TRACE] RTP.stop tempRegion.shutDown name=" + r.name);
      r.shutDown();
    }
    ActiveSelection.get().tempRegions.clear();

    if (ActiveDatabase.accessor() != null) {
      log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop databaseAccessor.stop.set(true) + close()");
      if (ActiveDatabase.accessor().stop != null) {
        ActiveDatabase.accessor().stop.set(true);
      }
      ActiveDatabase.accessor().close();
      ActiveDatabase.setAccessor(null);
    }

    TeleportSessions.current().latestTeleportData.forEach(
        (uuid, data) -> {
          if (!data.completed) {
            log(Level.FINER,
                "[SHUTDOWN_TRACE] RTP.stop CancelAgain late-cancel playerId=" + uuid);
            new RTPTeleportCancel(uuid).run();
          }
        });

    TeleportSessions.current().processingPlayers.clear();

    log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop ScanTask.kill");
    ScanTask.kill();

    if (instance.networkManager != null) {
      log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop networkManager.shutdown");
      instance.networkManager.shutdown();
    }

    log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop addons.unloadAll");
    addons.unloadAll();

    log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop serverAccessor.stop");
    RTPRuntime.serverAccessor().stop();
    log(Level.FINE, "[SHUTDOWN_TRACE] RTP.stop EXIT");
  }
}
