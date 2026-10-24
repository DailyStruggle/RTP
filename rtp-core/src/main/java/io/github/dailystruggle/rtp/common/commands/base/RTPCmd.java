package io.github.dailystruggle.rtp.common.commands.base;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.configuration.enums.NetworkMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.economy.RTPEconomy;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.*;
import io.github.dailystruggle.rtp.common.database.ActiveDatabase;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryNames;
import io.github.dailystruggle.rtp.common.factory.FactoryRegistry;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.network.NetworkHooks;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.util.RelativeCoordinate;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import io.github.dailystruggle.rtp.common.configuration.Messages;
public interface RTPCmd extends BaseRTPCmd {

  /**
   * RNG used by {@link #pickOne}. Defaults to {@link ThreadLocalRandom#current()} at call time.
   * Tests can inject a seeded {@link Random} via {@link #setRng(Random)} to make region selection
   * deterministic.
   */
  AtomicReference<Random> RNG_REF = new AtomicReference<>(null);

  /** Returns the active RNG, falling back to {@link ThreadLocalRandom#current()}. */
  static Random rng() {
    Random r = RNG_REF.get();
    return r != null ? r : ThreadLocalRandom.current();
  }

  /**
   * Injects a deterministic RNG. Pass {@code null} to restore {@link ThreadLocalRandom} behaviour.
   * Intended for unit tests only.
   */
  static void setRng(Random rng) {
    RNG_REF.set(rng);
  }

  static String pickOne(List<String> param, String d) {
    if (param == null || param.isEmpty()) return d;
    int sel = rng().nextInt(param.size());
    return param.get(sel);
  }

  /**
   * Checks if world-override applies (`rtp region:<r> world:<w>` with both args present).
   * Top-level `rtp world:<w>` resolves its region from the world configuration instead.
   *
   * @param rtpArgs the flattened {@code /rtp} argument map
   * @return {@code true} when the world-override region should be applied
   */
  static boolean shouldApplyWorldOverride(Map<String, List<String>> rtpArgs) {
    return rtpArgs != null
        && rtpArgs.containsKey("world")
        && rtpArgs.containsKey("region");
  }

  /**
   * Returns the local region explicitly requested via {@code region:<name>} / {@code region=<name>}
   * when the sender may use it and it carries a cooldown override; {@code null} otherwise.
   * Permission mirrors the {@code region} parameter: {@code rtp.region}, plus
   * {@code rtp.regions.<name>|*} when the region sets requirePermission. Network
   * ({@code server:region}) and multi-value requests return {@code null}; compute() decides those.
   *
   * @param sender the command sender
   * @param args   raw {@code /rtp} arguments, may be {@code null}
   * @return the permitted requested region with a cooldown override, or {@code null}
   */
  static Region requestedRegionWithOverride(RTPCommandSender sender, String[] args) {
    if (sender == null || args == null || ActiveSelection.get() == null) return null;
    String regionName = null;
    for (String a : args) {
      if (a == null || a.length() < 7) continue;
      String prefix = a.substring(0, 7).toLowerCase(Locale.ROOT);
      if (!prefix.equals("region:") && !prefix.equals("region=")) continue;
      if (regionName != null) return null;
      regionName = a.substring(7).trim();
    }
    if (regionName == null || regionName.isEmpty()
        || regionName.indexOf(',') >= 0 || regionName.indexOf(':') >= 0) {
      return null;
    }
    Region region = ActiveSelection.get().getRegion(regionName);
    if (region == null) return null;
    RegionSettings settings = region.getSettings();
    if (settings == null || settings.cooldownMillis() == null || settings.cooldownMillis() < 0) {
      return null;
    }
    if (!sender.hasPermission("rtp.region")) return null;
    if (settings.requirePermission()
        && !sender.hasPermission("rtp.regions." + regionName)
        && !sender.hasPermission("rtp.regions.*")) {
      return null;
    }
    return region;
  }

  /**
   * Resolves the world-default region a bare {@code /rtp} would target (as {@code compute()} does
   * without a {@code region}/{@code world} argument), or {@code null} when it cannot be resolved.
   *
   * @param sender the command sender; only an {@link RTPPlayer} with a location resolves
   * @return the sender's world-default region, or {@code null}
   */
  static Region worldDefaultRegion(RTPCommandSender sender) {
    if (!(sender instanceof RTPPlayer player) || ActiveConfigs.get() == null || ActiveSelection.get() == null) {
      return null;
    }
    var location = player.getLocation();
    if (location == null || location.world() == null) return null;
    ConfigParser<WorldKeys> worldParser = ActiveConfigs.get().getWorldParser(location.world().name());
    if (worldParser == null) return null;
    Object regionName = worldParser.getConfigValue(WorldKeys.region, "default");
    return (regionName == null) ? null : ActiveSelection.get().getRegion(regionName.toString());
  }

  /**
   * Remaining cooldown (ms) before {@code playerId} may teleport into {@code region}; 0 = allowed.
   * A requested region with a cooldown override uses that override alone. An unrequested
   * (world-default) region with an override passes once either its override or the global
   * cooldown has expired: the shorter wait wins (RTP-32). Without an override the global
   * cooldown applies. Region time falls back to {@code prior} when no per-region time is recorded.
   *
   * @param subject   sender whose permissions and global cooldown apply
   * @param playerId  the teleported player
   * @param region    destination region, may be {@code null} (global cooldown only)
   * @param requested whether the region was named explicitly ({@code region:<name>})
   * @param prior     the player's previous teleport data, may be {@code null}
   * @param now       current epoch millis
   * @return remaining milliseconds, never negative
   */
  static long cooldownRemaining(RTPCommandSender subject, UUID playerId, Region region,
      boolean requested, TeleportData prior, long now) {
    if (subject == null) return 0L;
    long localLast = (prior != null && prior.time > 0L) ? prior.time : 0L;
    long globalLast = Math.max(localLast, RTP.getSharedLastTeleportTime(playerId));
    long globalRemaining = remainingAfter(RTP.getCooldown(subject, null), globalLast, now);
    RegionSettings settings = (region == null) ? null : region.getSettings();
    if (settings == null || settings.cooldownMillis() == null || settings.cooldownMillis() < 0) {
      return globalRemaining;
    }
    long regionLast = RTP.getLastRegionTeleportTime(playerId, region.name);
    if (regionLast <= 0L && prior != null && prior.completed
        && (prior.targetRegion == null || region.name.equalsIgnoreCase(prior.targetRegion.name))) {
      regionLast = prior.time;
    }
    long regionRemaining = remainingAfter(RTP.getCooldown(subject, settings), regionLast, now);
    return requested ? regionRemaining : Math.min(regionRemaining, globalRemaining);
  }

  private static long remainingAfter(long cooldown, long last, long now) {
    if (cooldown <= 0L || last <= 0L) return 0L;
    long elapsed = now - last;
    if (elapsed < 0L) return 0L; // clock skew: treat as elapsed, as the dt overflow guard did
    return Math.max(0L, cooldown - elapsed);
  }

  /**
   * Resolves relative coordinate tokens (~, ~n, -~, -~n) against a base coordinate.
   * Tokens without ~ are parsed as absolute numbers.
   *
   * @param token raw override token (must be non-null)
   * @param base  the player's current coordinate on this axis
   * @return the resolved absolute coordinate
   * @throws NumberFormatException if the numeric offset is malformed
   */
  static long resolveRelativeCoordinate(String token, long base) {
    return RelativeCoordinate.resolve(token, base);
  }

  /**
   * Consults pluggable bare-{@code /rtp} root action (ADR-056).
   * Falls back to classic teleport if unbound, unhandled, or on error (REQ-RTP-S-004).
   *
   * @param senderId              command sender UUID
   * @param messageMethod         feedback sink or null for serverAccessor default
   * @param releaseProcessingLock whether to release processingPlayers lock when handled
   * @return {@code true} when the bound action handled the command
   */
  private boolean tryRootAction(
          UUID senderId,
          java.util.function.Consumer<String> messageMethod,
          boolean releaseProcessingLock) {
    if (senderId.equals(new java.util.UUID(0, 0))) return false;
    io.github.dailystruggle.rtp.api.hooks.RootActionRegistry.Action rootAction = null;
    try {
      io.github.dailystruggle.rtp.api.hooks.RootActionRegistry rootActionRegistry =
              io.github.dailystruggle.rtp.api.RTPAPI.hooks().rootAction();
      if (rootActionRegistry != null) rootAction = rootActionRegistry.current();
    } catch (IllegalStateException coreNotLoaded) {
      // hooks facade not published yet (REQ-RTP-S-006); fall through to classic teleport.
      RTPRuntime.log(Level.FINE, "[RTP-GUI] bare /rtp: hooks facade not published yet; "
              + "falling back to classic teleport for " + senderId);
      return false;
    }
    if (rootAction == null) {
      RTPRuntime.log(Level.FINE,
              "[RTP-GUI] bare /rtp: no root action bound; classic teleport for " + senderId);
      return false;
    }
    java.util.function.Consumer<String> feedback =
            messageMethod != null ? messageMethod : (m -> RTPRuntime.serverAccessor().sendMessage(senderId, m));
    RTPRuntime.log(Level.FINE, "[RTP-GUI] bare /rtp: invoking bound root action for " + senderId);
    boolean handled = false;
    try {
      handled = rootAction.run(senderId, feedback);
    } catch (Throwable t) {
      RTPRuntime.log(Level.WARNING,
              "[RTP] bound /rtp root action threw; unbinding it and resetting to the"
                      + " classic teleport so future invocations are not affected", t);
      clearFailedRootAction();
    }
    RTPRuntime.log(Level.FINE,
            "[RTP-GUI] bare /rtp: root action handled=" + handled + " for " + senderId);
    if (handled && releaseProcessingLock && !senderId.equals(CommandsAPI.serverId)) {
      // Release the processingPlayers lock the outer onCommand acquired, so the
      // GUI-handled bare /rtp does not leave the player flagged "busy".
      TeleportSessions.current().processingPlayers.remove(senderId);
    }
    return handled;
  }

  /**
   * Unbinds failing bare-{@code /rtp} root action, resetting to classic teleport.
   * Prevents repeated exceptions and log spam on permanently broken hook bindings.
   */
  private static void clearFailedRootAction() {
    try {
      io.github.dailystruggle.rtp.api.hooks.RootActionRegistry registry =
              io.github.dailystruggle.rtp.api.RTPAPI.hooks().rootAction();
      if (registry != null) {
        registry.clear();
        RTPRuntime.log(Level.INFO,
                "[RTP] unbound failing /rtp root action; bare /rtp reset to the classic teleport");
      }
    } catch (Throwable clearError) {
      RTPRuntime.log(Level.FINE,
              "[RTP] could not unbind the failing /rtp root action", clearError);
    }
  }

  default void init() {}

    default boolean onCommand(
        RTPCommandSender sender, CommandsAPICommand command, String label, String[] args) {
        UUID senderId = sender.uuid();
        java.util.function.Consumer<String> messageMethod = sender::sendMessage;

        if (RTP.reloading.get()) {
            String msg = (String) ActiveConfigs.get().getConfigValue(PlayerMessages.busy, "");
            messageMethod.accept(msg);
            return true;
        }

    // ------------------------D--------------------------------------------------------------------------------------
    // guard command perms with custom message
    if (!sender.hasPermission("rtp.use")) {
      String msg = (String) ActiveConfigs.get().getConfigValue(PlayerMessages.noPerms, "");
      messageMethod.accept(msg);
      return true;
    }

    // --------------------------------------------------------------------------------------------------------------
    // Subcommand dispatch (e.g. `rtp info`, `rtp reload`) must bypass teleport-related guards.
    // These guards (cooldown, alreadyTeleporting, processingPlayers registration) apply only to
    // the root teleport invocation, not to administrative / query subcommands.
    boolean hasSubCommand = false;
    if (args != null && args.length > 0) {
      // Built-in `help` verb (handled by TreeCommand when no "HELP" subcommand is registered)
      // is an informational query, not a teleport: bypass the teleport-related guards.
      if (args[0].equalsIgnoreCase("help")) {
        hasSubCommand = true;
      } else {
        Map<String, CommandsAPICommand> lookup = getCommandLookup();
        if (lookup != null && !lookup.isEmpty()) {
          hasSubCommand = lookup.containsKey(args[0].toUpperCase(java.util.Locale.ROOT));
        }
      }
    }

    // --------------------------------------------------------------------------------------------------------------
    // Pluggable bare-/rtp root action (ADR-056); shared with the compute() path via
    // tryRootAction() so the logic lives in exactly one place. Subcommands
    // (`hasSubCommand`) are never affected, and the action fires only for a *bare*
    // `/rtp` (no arguments at all): explicit teleport parameters express a concrete
    // destination and skip the GUI/menu override. This path has not yet acquired the
    // processingPlayers lock, so no release is requested.
    boolean bareInvocation = (args == null || args.length == 0);
    if (!hasSubCommand && bareInvocation && tryRootAction(senderId, messageMethod, false)) {
      return true;
    }

    long dt = -1;

    // --------------------------------------------------------------------------------------------------------------
    // guard last teleport time synchronously to prevent spam
    TeleportData senderData = TeleportSessions.current().latestTeleportData.get(senderId);

    if (senderData != null) {
      if (senderData.sender == null) {
        senderData.sender = sender;
      }

      dt = System.currentTimeMillis() - senderData.time;

      if (dt < 0) dt = Long.MAX_VALUE + dt;

      boolean hasTargetArg = false;
      if (args != null) {
        for (String a : args) {
          if (a != null) {
            String lower = a.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("region=") || lower.startsWith("world=") || lower.startsWith("biome=")
                || lower.startsWith("region:") || lower.startsWith("world:") || lower.startsWith("biome:")) {
              hasTargetArg = true;
              break;
            }
          }
        }
      }

      // Same rules as compute() (cooldownRemaining): a permitted requested region with an override
      // uses that override; bare /rtp uses the shorter of global and world-default override.
      // Other target args are left to compute().
      Region requestedRegion = hasSubCommand ? null : requestedRegionWithOverride(sender, args);
      if (!hasSubCommand && (!hasTargetArg || requestedRegion != null)) {
        Region guardRegion = (requestedRegion != null) ? requestedRegion : worldDefaultRegion(sender);
        long remaining = cooldownRemaining(sender, senderId, guardRegion, requestedRegion != null,
            senderData, System.currentTimeMillis());
        if (remaining > 0L) {
          String msg = (String) ActiveConfigs.get().getConfigValue(PlayerMessages.cooldownMessage, "");
          messageMethod.accept(msg);
          RTPRuntime.log(Level.FINE, "[ENQUEUE_TRACE] RTPCmd.onCommand REJECT cooldown senderId=" + senderId
                  + " dtMs=" + dt + " remainingMs=" + remaining
                  + " region=" + (guardRegion == null ? "null" : guardRegion.name));
          return true;
        } else if (senderData.completed) { // resolve command bugs preemptively
          TeleportSessions.current().processingPlayers.remove(senderId);
        }
      }
    }

    // --------------------------------------------------------------------------------------------------------------
    // BetterRTP LockAfter parity: per-player usage cap. Inert unless lockAfterUses > 0;
    // bypassed by rtp.nolock. The counter is incremented on a successful teleport in
    // TeleportPipelineTask; here we only reject when the player is already locked out.
    if (!hasSubCommand && !sender.hasPermission("rtp.nolock")) {
      ConfigParser<ConfigKeys> cfg =
              (ConfigParser<ConfigKeys>) ActiveConfigs.get().getParser(ConfigKeys.class);
      if (cfg != null) {
        long cap = cfg.getNumber(ConfigKeys.lockAfterUses, 0L).longValue();
        if (cap > 0) {
          long resetMillis =
                  ConfigParser.parseSecondsDurationMillis(
                          cfg.getConfigValue(ConfigKeys.lockAfterResetSeconds, 0L), 0L);
          if (ActiveDatabase.teleportLimitStore().isLocked(
                  senderId, cap, resetMillis, System.currentTimeMillis())) {
            String msg = (String) ActiveConfigs.get().getConfigValue(PlayerMessages.lockedAfterUses, "");
            messageMethod.accept(msg);
            RTPRuntime.log(Level.FINE, "[ENQUEUE_TRACE] RTPCmd.onCommand REJECT lockAfterUses senderId="
                    + senderId + " cap=" + cap);
            return true;
          }
        }
      }
    }

    if (RTP.reloading.get()) {
      String msg = (String) ActiveConfigs.get().getConfigValue(PlayerMessages.teleportDeniedReloading, "");
      messageMethod.accept(msg);
      RTPRuntime.log(Level.FINE, "[ENQUEUE_TRACE] RTPCmd.onCommand REJECT reloading senderId=" + senderId);
      return true;
    }

    if (!hasSubCommand && TeleportSessions.current().processingPlayers.contains(senderId)) {
      String msg = (String) ActiveConfigs.get().getConfigValue(PlayerMessages.alreadyTeleporting, "");
      messageMethod.accept(msg);
      RTPRuntime.log(Level.FINE, "[ENQUEUE_TRACE] RTPCmd.onCommand REJECT alreadyTeleporting senderId=" + senderId);
      return true;
    }

    if (!hasSubCommand && !senderId.equals(CommandsAPI.serverId)) TeleportSessions.current().processingPlayers.add(senderId);

    java.util.function.Consumer<String> effectiveMsg = (messageMethod != null) ? messageMethod : msg -> {};
    try {
      onCommand(senderId, sender::hasPermission, effectiveMsg, args)
          .whenComplete((aBoolean, throwable) -> {
            if (throwable != null || Boolean.FALSE.equals(aBoolean)) {
              if (throwable != null) {
                RTPRuntime.log(Level.WARNING, throwable.getMessage(), throwable);
              }
              TeleportSessions.current().processingPlayers.remove(senderId);
            }
          });
    } catch (Throwable throwable) {
      RTPRuntime.log(Level.WARNING, throwable.getMessage(), throwable);
      TeleportSessions.current().processingPlayers.remove(senderId);
    }
    return true;
  }

  @Override
  default boolean onCommand(UUID senderId, Map<String, List<String>> rtpArgs, CommandsAPICommand nextCommand, java.util.function.Consumer<String> messageMethod) {
    RTPRuntime.log(Level.FINER, "[RTP][trace] RTPCmd.onCommand(4-arg default) ENTER senderId=" + senderId
            + " rtpArgs=" + rtpArgs
            + " nextCommand=" + (nextCommand == null ? "null" : nextCommand.name())
            + " thread=" + Thread.currentThread().getName());
    if (nextCommand != null) {
      RTPRuntime.log(Level.FINER, "[RTP][trace] RTPCmd.onCommand(4-arg default) returning early (nextCommand != null)");
      return true;
    }

    boolean res = compute(senderId, rtpArgs, nextCommand, messageMethod);
    RTPRuntime.log(Level.FINER, "[RTP][trace] RTPCmd.onCommand(4-arg default) compute returned " + res);
    return res;
  }

  // CommandsAPI compute delegate; invoked via CommandsAPI.execute() in SyncTaskProcessing.
  default boolean compute(
          UUID senderId, Map<String, List<String>> rtpArgs, CommandsAPICommand nextCommand) {
    return compute(senderId, rtpArgs, nextCommand, null);
  }

  default boolean compute(
      UUID senderId, Map<String, List<String>> rtpArgs, CommandsAPICommand nextCommand, java.util.function.Consumer<String> messageMethod) {
    if (nextCommand != null) {
      return true;
    }

    // Bare /rtp root action (ADR-056) for platforms dispatching directly to compute().
    // Releases processingPlayers lock if handled.
    boolean bareInvocation = (rtpArgs == null || rtpArgs.isEmpty());
    if (bareInvocation && tryRootAction(senderId, messageMethod, true)) {
      return true;
    }

    if (senderId.equals(new java.util.UUID(0, 0)) && !rtpArgs.containsKey("player")) {
      if(messageMethod != null) {
        String msg = (String) ActiveConfigs.get().getConfigValue(PlayerMessages.consoleCmdNotAllowed, "");
        messageMethod.accept(msg);
      }
      else RTPRuntime.serverAccessor().sendMessage(senderId, PlayerMessages.consoleCmdNotAllowed);
      return true;
    }

    // --- Cross-server pre-dispatch hook (rtp-proxy-ADR-014) ----------------
    // Consult the platform-installed NetworkCommandHook BEFORE the local
    // pipeline runs. Default install (LOCAL_ONLY) is a zero-cost no-op for
    // single-server deployments. Network-mode adapters return CrossServer
    // (enrol + short-circuit), Reject (localized message + short-circuit),
    // or Local (fall through unchanged). S-004: hook throws degrade to
    // local fall-through with a WARNING log, never silent-swallow.
    if (!senderId.equals(new java.util.UUID(0, 0))) {
      io.github.dailystruggle.rtp.api.network.NetworkCommandHook hook = NetworkHooks.commandHook();
      if (hook != null && hook != io.github.dailystruggle.rtp.api.network.NetworkCommandHook.LOCAL_ONLY) {
        io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult decision;
        try {
          decision = hook.route(senderId, rtpArgs);
        } catch (Throwable t) {
          RTPRuntime.log(Level.WARNING,
                  "[RTP] command hook threw; falling back to local pipeline: " + t.getMessage(), t);
          decision = io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.local();
        }
        if (decision instanceof io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.CrossServer cross) {
          String tmpl = (String) ActiveConfigs.get().getConfigValue(NetworkMessages.networkQueued, "");
          // Empty-template fallback: a stale messages.yml (pre-network-mode
          // install missing the new key) must NEVER produce silent
          // dispatch, otherwise the player sees no response and spams
          // /rtp. Mirror the hardcoded English fallback
          // NetworkWaitlistGuard / NetworkWaitlistNotifier already use.
          if (tmpl == null || tmpl.isEmpty()) {
            tmpl = "&7[RTP] Queued for a cross-server destination (position [position])"
                    + ". You will be teleported when capacity is available.";
          }
          String msg = tmpl
                  .replace("[position]", "0")
                  .replace("[region]", cross.regionKey().orElse(""))
                  .replace("[server]", cross.serverHint().orElse(""));
          if (messageMethod != null) messageMethod.accept(msg);
          else RTPRuntime.serverAccessor().sendMessage(senderId, msg);
          // Retain processingPlayers lock for cross-server queue as anti-spam guard.
          // Released on disconnect, shutdown, or via terminal status listeners.
          return true;
        }
        if (decision instanceof io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.Reject reject) {
          Enum<?> key = Messages.byName(reject.messageKey());
          if (key == null) key = NetworkMessages.networkRegionUnavailable;
          String tmpl = (String) ActiveConfigs.get().getConfigValue(key, "");
          String msg = tmpl == null ? "" : tmpl
                  .replace("[region]", reject.placeholder())
                  .replace("[reason]", reject.placeholder());
          if (!msg.isEmpty()) {
            if (messageMethod != null) messageMethod.accept(msg);
            else RTPRuntime.serverAccessor().sendMessage(senderId, msg);
          }
          // Release the processingPlayers lock acquired by the outer
          // onCommand. See CrossServer branch comment above.
          if (!senderId.equals(CommandsAPI.serverId)) TeleportSessions.current().processingPlayers.remove(senderId);
          return true;
        }
        // RoutingResult.Local -> fall through to local pipeline
      }
    }
    // --- end cross-server hook -----------------------------------------------

    RTPCommandSender sender = RTPRuntime.serverAccessor().getSender(senderId);
    RTPRuntime.log(Level.FINER, "[RTP][trace] RTPCmd.compute ENTER senderId=" + senderId
            + " senderName=" + (sender != null ? sender.name() : "null")
            + " args=" + rtpArgs
            + " thread=" + Thread.currentThread().getName());

    // --- Optional PvP / combat-tag pre-flight gate (ADR-055) -----------------
    // Consult the combat authority BEFORE enrolling the requester. No-op when
    // the gate is disabled (pvpCheckEnabled=false, the default) or the player
    // is not in combat. A non-ALLOW action refuses the request with the
    // configurable pvpInCombat message and audits at WARNING (REQ-RTP-S-004);
    // the gate never blocks a teleport because an integration broke (PvPGate
    // fails open). Skipped for the console sender and for cross-player rtp
    // targeting (the requesting console is never "in combat").
    if (!senderId.equals(new java.util.UUID(0, 0)) && !rtpArgs.containsKey("player")) {
      io.github.dailystruggle.rtp.api.hooks.PvPCombatAction pvpAction;
      try {
        pvpAction = io.github.dailystruggle.rtp.common.pvp.PvPGate.evaluate(senderId);
      } catch (Throwable t) {
        // Defensive: the gate is fail-open, but guard the call site too.
        RTPRuntime.log(Level.WARNING, "[RTP] PvP gate evaluation threw; allowing teleport", t);
        pvpAction = io.github.dailystruggle.rtp.api.hooks.PvPCombatAction.ALLOW;
      }
      if (pvpAction != io.github.dailystruggle.rtp.api.hooks.PvPCombatAction.ALLOW) {
        // DENY / CANCEL / DELAY all collapse to "refuse this new request" at the
        // pre-dispatch surface (there is no in-progress teleport to abort yet).
        String msg = (String) ActiveConfigs.get().getConfigValue(PlayerMessages.pvpInCombat,
                "&c[RTP] You cannot use /rtp while in combat.");
        if (msg != null && !msg.isEmpty()) {
          if (messageMethod != null) messageMethod.accept(msg);
          else RTPRuntime.serverAccessor().sendMessage(senderId, PlayerMessages.pvpInCombat);
        }
        // S-004: the refusal is never silent.
        RTPRuntime.log(Level.WARNING, "[RTP] PvP gate refused /rtp for " + senderId
                + " (action=" + pvpAction + ", in combat)");
        // Terminal refuse: release the lock the outer onCommand acquired.
        TeleportSessions.current().processingPlayers.remove(senderId);
        return true;
      } else if (io.github.dailystruggle.rtp.common.pvp.PvPGate.isEnabled()
              && io.github.dailystruggle.rtp.common.pvp.PvPGate.isInCombat(senderId)) {
        // ALLOW-and-audit: operator wants visibility without changing behaviour.
        RTPRuntime.log(Level.INFO, "[RTP] PvP gate: " + senderId
                + " is in combat but pvpOnCombat=ALLOW; permitting /rtp");
      }
    }
    // --- end PvP gate --------------------------------------------------------

    if (!senderId.equals(CommandsAPI.serverId)) TeleportSessions.current().processingPlayers.add(senderId);

    List<String> toggletargetpermsList = rtpArgs.get("toggletargetperms");
    boolean toggleTargetPerms =
        toggletargetpermsList != null && Boolean.parseBoolean(toggletargetpermsList.get(0));

    ConfigParser<LoggingKeys> logging =
        (ConfigParser<LoggingKeys>) ActiveConfigs.get().getParser(LoggingKeys.class);
    boolean verbose = false;
    if (logging != null) {
      Object o = logging.getConfigValue(LoggingKeys.command, false);
      if (o instanceof Boolean) {
        verbose = (Boolean) o;
      } else {
        verbose = Boolean.parseBoolean(o.toString());
      }
    }

    if (verbose) {
      RTPRuntime.log(Level.INFO, "#0080ff[RTP] RTP command triggered by " + sender.name() + ".");
    }

    ConfigParser<EconomyKeys> eco =
        (ConfigParser<EconomyKeys>) ActiveConfigs.get().getParser(EconomyKeys.class);
    ConfigParser<PerformanceKeys> perf =
        (ConfigParser<PerformanceKeys>) ActiveConfigs.get().getParser(PerformanceKeys.class);
    boolean syncLoading = false;
    Object configValue = perf.getConfigValue(PerformanceKeys.syncLoading, false);
    if (configValue instanceof String) {
      configValue = Boolean.parseBoolean((String) configValue);
      perf.set(PerformanceKeys.syncLoading, configValue);
    }
    if (configValue instanceof Boolean) syncLoading = (Boolean) configValue;

    // --------------------------------------------------------------------------------------------------------------
    // collect target players to teleport
    List<RTPPlayer> players = new ArrayList<>();
    if (rtpArgs.containsKey("player")) { // if players are listed, use those.
      List<String> playerNames = rtpArgs.get("player");
      for (String playerName : playerNames) {
        // double check the player is still valid by the time we get here
        RTPPlayer p = RTPRuntime.serverAccessor().getPlayer(playerName);
        if (p == null) {
          String msg =
              (String)
                  ActiveConfigs.get().getConfigValue(PlayerMessages.badArg, "[P0] bad parameter - [arg]");
          msg = msg.replace("[arg]", "player=" + playerName);
          if(messageMethod != null) messageMethod.accept(msg);
          else RTPRuntime.serverAccessor().sendMessage(senderId, msg);
          RTPRuntime.log(Level.WARNING, msg);
          continue;
        }

        players.add(p);
      }
    } else if (sender
        instanceof RTPPlayer) { // if no players but sender is a player, use sender's location
      players = new ArrayList<>(1);
      players.add((RTPPlayer) sender);
    } else { // if no players and sender isn't a player, idk who to send
      String msg = (String) ActiveConfigs.get().getConfigValue(PlayerMessages.consoleCmdNotAllowed, "");
      if(messageMethod != null) messageMethod.accept(msg);
      failEvent(sender, msg);
      TeleportSessions.current().processingPlayers.remove(senderId);
      return true;
    }

    List<String> biomeList = rtpArgs.get("biome");
    List<String> shapeNames = rtpArgs.get("shape");
    List<String> vertNames = rtpArgs.get("vert");
    double price = 0.0;
    RTPEconomy economy = RTPRuntime.economy();
    if (economy != null) {
      if (!senderId.equals(CommandsAPI.serverId) && !sender.hasPermission("rtp.free")) {
        for (RTPPlayer player : players) {
          if (player.uuid().equals(senderId))
            price += eco.getNumber(EconomyKeys.price, 0.0).doubleValue();
          else if (player.hasPermission("rtp.notme")) continue;
          else price += eco.getNumber(EconomyKeys.priceOther, 0.0).doubleValue();
          if (shapeNames != null || vertNames != null)
            price += eco.getNumber(EconomyKeys.paramsPrice, 0.0).doubleValue();
          if (biomeList != null) price += eco.getNumber(EconomyKeys.biomePrice, 0.0).doubleValue();
        }
      }
      double bal = economy.bal(senderId);
      double floor = eco.getNumber(EconomyKeys.balanceFloor, 0.0d).doubleValue();
      if ((bal - price) < floor) {
        String s = ActiveConfigs.get().getConfigValue(PlayerMessages.notEnoughMoney, "").toString();
        s = s.replace("[money]", String.valueOf(price));
        if(messageMethod != null) messageMethod.accept(s);
        else RTPRuntime.serverAccessor().sendMessage(senderId, s);
        TeleportSessions.current().processingPlayers.remove(senderId);
        return true;
      }
    }

    // The sender's processingPlayers entry is owned by a pipeline only when one is
    // submitted for the sender themself; every other exit releases it (S-004).
    boolean senderDispatched = false;
    for (int i = 0; i < players.size(); i++) {
      RTPPlayer player = players.get(i);

      if (verbose && rtpArgs.containsKey("player")) {
        RTPRuntime.log(Level.INFO, "#0080ff[RTP] RTP processing player:" + player.name());
      }

      // get their data
      TeleportData data = TeleportSessions.current().latestTeleportData.get(player.uuid());
      RTPRuntime.log(Level.FINER, "[RTP][trace] RTPCmd.compute per-player loop playerId=" + player.uuid()
              + " priorData=" + (data == null ? "null" : ("completed=" + data.completed + " age=" + (System.currentTimeMillis() - data.time) + "ms")));
      // if player has an incomplete teleport
      if (data != null) {
        if (!data.completed) {
          RTPRuntime.log(Level.FINER, "[RTP][trace] RTPCmd.compute short-circuit ALREADY_TELEPORTING playerId=" + player.uuid() + " -> skipping pipeline (sending alreadyTeleporting message via serverAccessor)");
          String msg = (String) ActiveConfigs.get().getConfigValue(PlayerMessages.alreadyTeleporting, "");
          RTPRuntime.serverAccessor().sendMessage(senderId, player.uuid(), msg);
          failEvent(sender, msg);
          continue;
        }

        if (toggleTargetPerms) {
          long lastTpTime = RTP.getEffectiveLastTeleportTime(player.uuid());
          long dt = (lastTpTime > 0) ? (System.currentTimeMillis() - lastTpTime) : (System.currentTimeMillis() - data.time);
          if (dt < 0) dt = Long.MAX_VALUE + dt;
          if (dt < player.cooldown()) {
            RTPRuntime.serverAccessor().sendMessage(senderId, player.uuid(), PlayerMessages.cooldownMessage);
            continue;
          }
        }

        TeleportSessions.current().priorTeleportData.put(player.uuid(), data);
      }

      TeleportData prior = data;
      data = new TeleportData();
      io.github.dailystruggle.rtp.common.tools.MemoryTracker.track(data, "TeleportData-" + player.uuid().toString(), 120000L);
      data.sender = sender;
      TeleportSessions.current().latestTeleportData.put(player.uuid(), data);

      String regionName;
      List<String> regionNames = rtpArgs.get("region");
      if (rtpArgs.containsKey("region")) {
        // todo: get one region from the list
        regionName = pickOne(regionNames, "default");
      } else {
        String worldName;
        // get region parameter from world options
        if (rtpArgs.containsKey("world")) {
          // get one world from the list
          worldName = pickOne(rtpArgs.get("world"), "default");
        } else {
          // use player's world
          worldName = player.getLocation().world().name();
        }

        ConfigParser<WorldKeys> worldParser = ActiveConfigs.get().getWorldParser(worldName);

        if (worldParser == null) {
          String msg = (String) ActiveConfigs.get().getConfigValue(PlayerMessages.badArg, "[P0] bad parameter - [arg]");
          msg = msg.replace("[arg]", "world=" + worldName);
          if(messageMethod != null) messageMethod.accept(msg);
          else RTPRuntime.serverAccessor().sendMessage(senderId, msg);
          RTPRuntime.log(Level.FINER, "[RTP][trace] RTPCmd.compute REJECT badArg world senderId=" + senderId
                  + " worldName=" + worldName);
          RTPRuntime.log(Level.WARNING, msg);
          TeleportSessions.current().processingPlayers.remove(senderId);
          return true;
        }

        regionName = worldParser.getConfigValue(WorldKeys.region, "default").toString();

        // ADR-063: auto-region by biome availability. When a biome is requested
        // without an explicit region, keep the world-default region only if it
        // has itself observed the biome; otherwise pick the accessible region
        // that best offers it. Falls back to the world-default name when no
        // region has observed the biome (cold-data graceful degradation). This
        // shared resolution keeps CLI and menu in parity: the menu emits the
        // plain /rtp biome:<x> command and gets the same region choice.
        if (biomeList != null && !biomeList.isEmpty()) {
          String requestedBiome = biomeList.get(0);
          String resolved =
              io.github.dailystruggle.rtp.common.selection.region.BiomeMenuSource.bestRegionForBiome(
                  senderId, requestedBiome, regionName);
          if (resolved != null) {
            if (!resolved.equals(regionName)) {
              RTPRuntime.log(Level.FINER, "[RTP][trace] RTPCmd.compute auto-region by biome senderId="
                  + senderId + " biome=" + requestedBiome + " worldDefaultRegion=" + regionName
                  + " -> resolvedRegion=" + resolved);
            }
            regionName = resolved;
          }
        }
      }

      SelectionAPI selectionAPI = ActiveSelection.get();

      Region region;
      try {
        region = selectionAPI.getRegionOrDefault(regionName);
        RTPRuntime.log(Level.FINER, "[RTP][trace] RTPCmd.compute region resolved senderId=" + senderId
                + " requestedRegion=" + regionName + " resolvedRegion=" + region.name);
      } catch (IllegalArgumentException | IllegalStateException exception) {
        String msg = (String) ActiveConfigs.get().getConfigValue(PlayerMessages.badArg, "[P0] bad parameter - [arg]");
        msg = msg.replace("[arg]", "region=" + regionName);
        RTPRuntime.serverAccessor().sendMessage(senderId, msg);
        RTPRuntime.log(Level.FINER, "[RTP][trace] RTPCmd.compute REJECT badArg region senderId=" + senderId
                + " regionName=" + regionName + " cause=" + exception.getClass().getSimpleName());
        RTPRuntime.log(Level.WARNING, msg);
        TeleportSessions.current().processingPlayers.remove(senderId);
        TeleportSessions.current().latestTeleportData.remove(senderId);
        return true;
      }

      // World-override: duplicates base region <r> rebound to world <w> for `rtp region:<r> world:<w>`.
      // Top-level `rtp world:<w>` resolves its configured region above without override.
      if (shouldApplyWorldOverride(rtpArgs)) {
        String worldOverride = pickOne(rtpArgs.get("world"), null);
        if (worldOverride != null && !worldOverride.isEmpty()) {
          Region worldRegion = selectionAPI.worldRegion(region.name, worldOverride);
          if (worldRegion != null && worldRegion != region) {
            RTPRuntime.log(Level.FINER, "[RTP][trace] RTPCmd.compute world-override senderId=" + senderId
                + " baseRegion=" + region.name + " world=" + worldOverride
                + " -> region=" + worldRegion.name);
            region = worldRegion;
          }
        }
      }

      RTPWorld rtpWorld = region.getWorld();
      if (rtpWorld == null) {
        String msg = (String) ActiveConfigs.get().getConfigValue(PlayerMessages.badArg, "[P0] bad parameter - [arg]");
        msg = msg.replace("[arg]", "region=" + regionName);
        RTPRuntime.serverAccessor().sendMessage(senderId, msg);
        RTPRuntime.log(Level.WARNING, msg);
        TeleportSessions.current().processingPlayers.remove(senderId);
        TeleportSessions.current().latestTeleportData.remove(senderId);
        return true;
      }

      // check for wbo
      boolean doWBO = false;
      if (rtpArgs.containsKey("worldBorderOverride")) {
        List<String> WBOVals = rtpArgs.get("worldBorderOverride");
        if (WBOVals.size() > i) doWBO = Boolean.parseBoolean(WBOVals.get(i));
        else doWBO = Boolean.parseBoolean(WBOVals.get(0));

        if (doWBO) {
          region = region.clone();
          region.set(RegionKeys.shape, RTPRuntime.serverAccessor().getShape(rtpWorld.name()));
        }
      }

      // Cooldown guard:
      RTPCommandSender cooldownSubject = (toggleTargetPerms) ? player : sender;
      // Requested region -> its override; world-default region -> shorter of override and global.
      // Measured against the previous teleport (prior), not the TeleportData created above.
      long remainingCooldown = RTPCmd.cooldownRemaining(cooldownSubject, player.uuid(), region,
          rtpArgs.containsKey("region"), prior, System.currentTimeMillis());
      if (remainingCooldown > 0L) {
        String msg = (String) ActiveConfigs.get().getConfigValue(PlayerMessages.cooldownMessage, "");
        if (messageMethod != null) messageMethod.accept(msg);
        else RTPRuntime.serverAccessor().sendMessage(senderId, player.uuid(), msg);
        TeleportSessions.current().processingPlayers.remove(senderId);
        TeleportSessions.current().latestTeleportData.remove(player.uuid());
        return true;
      }

      // Sender charge. Routes through the single EconomyGate so the price math,
      // balance-floor check and withdrawal live in one place (shared with the
      // addon-facing RTPAPI.teleport path). The rtp.notme opt-out stays here as
      // control flow; the charge is accumulated onto data.cost for the
      // refund-on-failure path (RTPTeleportCancel).
      if (economy != null && !sender.hasPermission("rtp.free")) {
        io.github.dailystruggle.rtp.common.economy.EconomyGate.PriceKey which;
        if (player.uuid().equals(senderId)) {
          which = io.github.dailystruggle.rtp.common.economy.EconomyGate.PriceKey.SELF;
        } else if (player.hasPermission("rtp.notme")) {
          continue;
        } else {
          which = io.github.dailystruggle.rtp.common.economy.EconomyGate.PriceKey.OTHER;
        }
        io.github.dailystruggle.rtp.common.economy.EconomyGate.Charge senderCharge =
            io.github.dailystruggle.rtp.common.economy.EconomyGate.charge(
                senderId, sender, region, which,
                shapeNames != null || vertNames != null || doWBO, biomeList != null);
        if (senderCharge.result()
            == io.github.dailystruggle.rtp.common.economy.EconomyGate.Result.INSUFFICIENT_FUNDS) {
          String s = ActiveConfigs.get().getConfigValue(PlayerMessages.notEnoughMoney, "").toString();
          s = s.replace("[money]", String.valueOf(price));
          RTPRuntime.serverAccessor().sendMessage(senderId, s);
          TeleportSessions.current().processingPlayers.remove(senderId);
          return true;
        }
        data.cost += senderCharge.cost();
      }

      // Target-pays charge (BetterRTP target-perms parity): when target perms
      // are toggled and the target is not the sender, the target pays their own
      // teleport price. Same single EconomyGate; the target's rtp.free exemption
      // is applied inside the gate via the free-check subject.
      if (economy != null
          && toggleTargetPerms
          && !player.hasPermission("rtp.free")
          && !player.uuid().equals(senderId)) {
        io.github.dailystruggle.rtp.common.economy.EconomyGate.Charge targetCharge =
            io.github.dailystruggle.rtp.common.economy.EconomyGate.charge(
                player.uuid(), player, region,
                io.github.dailystruggle.rtp.common.economy.EconomyGate.PriceKey.SELF,
                shapeNames != null || vertNames != null || doWBO, biomeList != null);
        if (targetCharge.result()
            == io.github.dailystruggle.rtp.common.economy.EconomyGate.Result.INSUFFICIENT_FUNDS) {
          String s = ActiveConfigs.get().getConfigValue(PlayerMessages.notEnoughMoney, "").toString();
          s = s.replace("[money]", String.valueOf(price));
          RTPRuntime.serverAccessor().sendMessage(senderId, player.uuid(), s);
          TeleportSessions.current().processingPlayers.remove(senderId);
          return true;
        }
        data.cost += targetCharge.cost();
      }

      Set<String> biomes = null;
      if (biomeList != null) {
        biomes = new HashSet<>(biomeList.size());
        for (String biome : biomeList) {
          if (biome == null || biome.isEmpty()) continue;
          // Upper-case for case-insensitivity. Namespace is preserved verbatim so that
          // modded ids like `iris:overworld:plains` survive intact; vanilla-namespace
          // equivalence (`plains` <-> `minecraft:plains`) is handled at the comparator
          // site (`BiomeNames#matches`) rather than by normalizing the input.
          biomes.add(biome.toUpperCase());
        }
      }

      if (shapeNames != null || vertNames != null) {
        region = region.clone();
      }

      if (shapeNames != null) {
        for (int j = 0; j < 1 && shapeNames != null && !shapeNames.isEmpty(); j++) {
          Shape<?> originalShape = region.getShape();

          ActiveSelection.get().tempRegions.put(senderId, region);
          String shapeName = pickOne(shapeNames, "CIRCLE");

          Factory<?> factory = FactoryRegistry.get(FactoryNames.shape);
          FactoryValue<?> factoryValue = factory.get(shapeName);
          if (!(factoryValue instanceof Shape<?>)) {
            RTPRuntime.log(
                Level.SEVERE,
                "",
                new IllegalArgumentException("shape factory did not return a shape"));
          }
          Shape<?> shape = (Shape<?>) factoryValue;

          EnumMap<?, Object> originalShapeData = originalShape.getData();
          EnumMap<?, Object> shapeData = shape.getData();
          for (Map.Entry<? extends Enum<?>, Object> entry : shapeData.entrySet()) {
            String name = entry.getKey().name();
            if (name.equalsIgnoreCase("name")) continue;
            if (name.equalsIgnoreCase("version")) continue;
            String argKey = name;
            if (!rtpArgs.containsKey(argKey) && name.equalsIgnoreCase("height") && rtpArgs.containsKey("length")) {
              argKey = "length";
            }
            if (rtpArgs.containsKey(argKey)) {
              String string = pickOne(rtpArgs.get(argKey), "");

              Object value;
              boolean isCoord =
                  name.equalsIgnoreCase("centerx") || name.equalsIgnoreCase("centerz");
              if (isCoord && string.indexOf('~') >= 0) {
                // Relative coordinate token (vanilla `/spreadplayers`-style `~`, `~<n>`, `-~`).
                // Resolve against the teleporting player's current position; without this the
                // token falls through to Boolean.valueOf("~") == false below and silently yields
                // a garbage (0/false) center despite being advertised in tab-complete.
                long base =
                    name.equalsIgnoreCase("centerx")
                        ? player.getLocation().x()
                        : player.getLocation().z();
                try {
                  value = resolveRelativeCoordinate(string, base);
                } catch (NumberFormatException badOffset) {
                  // Malformed offset (e.g. `~abc`): fall back to the player's coordinate rather
                  // than a non-numeric override value.
                  value = base;
                }
              } else if (string.equalsIgnoreCase("true")) {
                value = true;
              } else if (string.equalsIgnoreCase("false")) {
                value = false;
              } else {
                io.github.dailystruggle.rtp.common.selection.region.util.DistanceParser.ParsedDistance parsedDist =
                    io.github.dailystruggle.rtp.common.selection.region.util.DistanceParser.parse(
                        string, io.github.dailystruggle.rtp.common.selection.region.util.SpatialUnit.CHUNK);
                if (parsedDist != null && parsedDist.explicitUnit()) {
                  double chunks = parsedDist.toChunks();
                  value = (chunks == Math.floor(chunks)) ? (Object) Long.valueOf((long) chunks) : (Object) Double.valueOf(chunks);
                } else if (parsedDist != null) {
                  double worldBorderRad = 0.0;
                  try {
                    Object wbObj = RTPRuntime.serverAccessor().getWorldBorder(rtpWorld.name());
                    if (wbObj instanceof io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder wb
                        && wb.getShape() != null
                        && wb.getShape().get() instanceof io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square bs) {
                      worldBorderRad = bs.getNumber(
                          io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams.radius,
                          0L).doubleValue() * 16.0;
                    }
                  } catch (Throwable ignored) {
                  }
                  io.github.dailystruggle.rtp.common.selection.region.util.DistanceParser.ParsedDistance interpreted =
                      io.github.dailystruggle.rtp.common.selection.region.util.DistanceParser.autoInterpret(
                          parsedDist, worldBorderRad, name, msg -> RTPRuntime.log(Level.INFO, msg));
                  if (interpreted != null && interpreted.unit() != io.github.dailystruggle.rtp.common.selection.region.util.SpatialUnit.CHUNK) {
                    double chunks = interpreted.toChunks();
                    value = (chunks == Math.floor(chunks)) ? (Object) Long.valueOf((long) chunks) : (Object) Double.valueOf(chunks);
                  } else {
                    try {
                      value = Long.parseLong(string);
                    } catch (IllegalArgumentException ignored) {
                      try {
                        value = Double.parseDouble(string);
                      } catch (IllegalArgumentException ignored2) {
                        double chunks = parsedDist.toChunks();
                        value = (chunks == Math.floor(chunks)) ? (Object) Long.valueOf((long) chunks) : (Object) Double.valueOf(chunks);
                      }
                    }
                  }
                } else {
                  try {
                    value = Long.parseLong(string);
                  } catch (IllegalArgumentException ignored) {
                    try {
                      value = Double.parseDouble(string);
                    } catch (IllegalArgumentException ignored2) {
                      value = string;
                    }
                  }
                }
              }

              entry.setValue(value);
            } else {
              Enum<?> e;
              try {
                e = Enum.valueOf(originalShape.myClass, name);
              } catch (IllegalArgumentException ignored) {
                continue;
              }

              Object o1 = originalShapeData.get(e);
              if ((o1 instanceof Number)
                  || entry.getValue().getClass().isAssignableFrom(o1.getClass()))
                entry.setValue(o1);
            }
          }
          shape.setData(shapeData);
          region.set(RegionKeys.shape, shape);
        }
      }

      // todo: vert params

      TeleportPipelineTask pipelineTask = new TeleportPipelineTask(new GenerationContext(sender, player, biomes), region);
      data.nextTask = pipelineTask;
      RTPRuntime.log(Level.FINER, "[RTP][trace] RTPCmd.compute TeleportPipelineTask constructed playerId=" + player.uuid()
              + " region=" + region.name
              + " biomes=" + biomes
              + " syncLoading=" + syncLoading);

      long delay = RTP.getDelay((toggleTargetPerms) ? player : sender, region.getSettings());
      data.delay = delay;
      if (delay > 0) {
        String msg = ActiveConfigs.get().getConfigValue(PlayerMessages.delayMessage, "").toString();
        RTPRuntime.serverAccessor().sendMessage(senderId, player.uuid(), msg);
      }

      if (!syncLoading) {
        syncLoading = biomes == null && region.hasLocation(player.uuid()) && delay <= 0;
      }

      if (player.uuid().equals(senderId)) senderDispatched = true;
      if (syncLoading) {
        RTPRuntime.log(Level.FINER, "[RTP][trace] RTPCmd.compute SUBMIT pipelineTask SYNC playerId=" + player.uuid());
        region.inFlightCalculations.incrementAndGet();
        pipelineTask.run();
      } else {
        RTPRuntime.log(Level.FINER, "[RTP][trace] RTPCmd.compute SUBMIT pipelineTask ASYNC playerId=" + player.uuid());
        region.inFlightCalculations.incrementAndGet();
        RTPRuntime.scheduler().runTaskAsynchronously(pipelineTask);
      }
    }

    if (!senderDispatched && !senderId.equals(CommandsAPI.serverId)) {
      TeleportSessions.current().processingPlayers.remove(senderId);
    }
    return true;
  }

  @Override
  default String name() {
    return "rtp";
  }

  @Override
  default String permission() {
    return "rtp.use";
  }

  @Override
  default String description() {
    return BaseRTPCmd.super.description();
  }

  void successEvent(RTPCommandSender sender, RTPPlayer player);

  void failEvent(RTPCommandSender sender, String msg);
}
