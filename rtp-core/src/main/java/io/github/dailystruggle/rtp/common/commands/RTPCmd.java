package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.economy.RTPEconomy;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.*;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

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
   * Resolves a vanilla {@code /spreadplayers}-style relative coordinate token against a base
   * coordinate. The Bukkit {@code CoordinateParameter} advertises {@code ~} / {@code -~} in its
   * tab-completion, so these tokens must resolve to an actual coordinate rather than fall through
   * to {@code Boolean.valueOf("~") == false} (which silently produced a {@code 0}/garbage center).
   *
   * <p>Supported forms (whitespace-trimmed): {@code ~} ({@code base}), {@code ~<n>}
   * ({@code base + n}, where {@code n} may be negative e.g. {@code ~-5}), {@code -~}
   * ({@code -base}), and {@code -~<n>} ({@code -base + n}). A token containing no {@code ~} is
   * parsed as an absolute value.
   *
   * @param token the raw override token (must be non-null)
   * @param base  the player's current coordinate on this axis
   * @return the resolved absolute coordinate
   * @throws NumberFormatException if the numeric offset is malformed
   */
  static long resolveRelativeCoordinate(String token, long base) {
    String t = token.trim();
    int tilde = t.indexOf('~');
    if (tilde < 0) {
      return (long) Double.parseDouble(t);
    }
    boolean negativeBase = tilde > 0 && t.charAt(tilde - 1) == '-';
    long resolved = negativeBase ? -base : base;
    String offsetStr = t.substring(tilde + 1).trim();
    if (!offsetStr.isEmpty()) {
      resolved += (long) Double.parseDouble(offsetStr);
    }
    return resolved;
  }

  default void init() {}

    default boolean onCommand(
        RTPCommandSender sender, CommandsAPICommand command, String label, String[] args) {
        UUID senderId = sender.uuid();
        java.util.function.Consumer<String> messageMethod = sender::sendMessage;

        if (RTP.reloading.get()) {
            ConfigParser<MessagesKeys> langParser =
                    (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
            String msg = (String) langParser.getConfigValue(MessagesKeys.busy, "");
            messageMethod.accept(msg);
            return true;
        }

    // ------------------------D--------------------------------------------------------------------------------------
    // guard command perms with custom message
    if (!sender.hasPermission("rtp.use")) {
      ConfigParser<MessagesKeys> langParser =
              (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
      String msg = (String) langParser.getConfigValue(MessagesKeys.noPerms, "");
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
    // Pluggable bare-/rtp root action (ADR-056). A third-party addon may bind an
    // action (e.g. open a GUI) that replaces the classic teleport for a bare
    // `/rtp`. Subcommands (`hasSubCommand`) are never affected. When no action is
    // bound, current() is null and we fall through to the classic teleport path.
    // A bound action that returns true has handled the command and bypasses the
    // teleport-specific guards (cooldown / processingPlayers), exactly as a
    // subcommand does. An action that throws is logged (REQ-RTP-S-004) and treated
    // as not-handled, so the classic teleport still runs.
    if (!hasSubCommand) {
      io.github.dailystruggle.rtp.api.hooks.RootActionRegistry.Action rootAction = null;
      try {
        io.github.dailystruggle.rtp.api.hooks.RootActionRegistry rootActionRegistry =
                io.github.dailystruggle.rtp.api.RTPAPI.hooks().rootAction();
        if (rootActionRegistry != null) rootAction = rootActionRegistry.current();
      } catch (IllegalStateException coreNotLoaded) {
        // hooks facade not published yet (REQ-RTP-S-006); fall through to classic teleport.
        rootAction = null;
      }
      if (rootAction != null) {
        boolean handled = false;
        try {
          handled = rootAction.run(senderId, messageMethod);
        } catch (Throwable t) {
          RTP.log(Level.WARNING,
                  "[RTP] bound /rtp root action threw; falling back to classic teleport", t);
        }
        if (handled) return true;
      }
    }

    long dt = -1;

    // --------------------------------------------------------------------------------------------------------------
    // guard last teleport time synchronously to prevent spam
    TeleportData senderData = RTP.getInstance().latestTeleportData.get(senderId);

    if (senderData != null) {
      if (senderData.sender == null) {
        senderData.sender = sender;
      }

      dt = System.currentTimeMillis() - senderData.time;

      if (dt < 0) dt = Long.MAX_VALUE + dt;

      if (!hasSubCommand && dt < sender.cooldown()) {
        ConfigParser<MessagesKeys> langParser =
                (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
        String msg = (String) langParser.getConfigValue(MessagesKeys.cooldownMessage, "");
        messageMethod.accept(msg);
        RTP.log(Level.FINE, "[ENQUEUE_TRACE] RTPCmd.onCommand REJECT cooldown senderId=" + senderId
                + " dtMs=" + dt + " cooldownMs=" + sender.cooldown());
        return true;
      } else if (senderData.completed) { // resolve command bugs preemptively
        RTP.getInstance().processingPlayers.remove(senderId);
      }
    }

    if (RTP.reloading.get()) {
      ConfigParser<MessagesKeys> langParser =
              (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
      String msg = (String) langParser.getConfigValue(MessagesKeys.teleportDeniedReloading, "");
      messageMethod.accept(msg);
      RTP.log(Level.FINE, "[ENQUEUE_TRACE] RTPCmd.onCommand REJECT reloading senderId=" + senderId);
      return true;
    }

    if (!hasSubCommand && RTP.getInstance().processingPlayers.contains(senderId)) {
      ConfigParser<MessagesKeys> langParser =
              (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
      String msg = (String) langParser.getConfigValue(MessagesKeys.alreadyTeleporting, "");
      messageMethod.accept(msg);
      RTP.log(Level.FINE, "[ENQUEUE_TRACE] RTPCmd.onCommand REJECT alreadyTeleporting senderId=" + senderId);
      return true;
    }

    if (!hasSubCommand && !senderId.equals(CommandsAPI.serverId)) RTP.getInstance().processingPlayers.add(senderId);

    try {
      onCommand(senderId, sender::hasPermission, messageMethod, args)
          .whenComplete((aBoolean, throwable) -> {
            if (throwable != null) {
              RTP.log(Level.WARNING, throwable.getMessage(), throwable);
              RTP.getInstance().processingPlayers.remove(senderId);
            }
          });
    } catch (Throwable throwable) {
      RTP.log(Level.WARNING, throwable.getMessage(), throwable);
      RTP.getInstance().processingPlayers.remove(senderId);
    }
    return true;
  }

  @Override
  default boolean onCommand(UUID senderId, Map<String, List<String>> rtpArgs, CommandsAPICommand nextCommand, java.util.function.Consumer<String> messageMethod) {
    RTP.log(Level.FINER, "[RTP][trace] RTPCmd.onCommand(4-arg default) ENTER senderId=" + senderId
            + " rtpArgs=" + rtpArgs
            + " nextCommand=" + (nextCommand == null ? "null" : nextCommand.name())
            + " thread=" + Thread.currentThread().getName());
    if (nextCommand != null) {
      RTP.log(Level.FINER, "[RTP][trace] RTPCmd.onCommand(4-arg default) returning early (nextCommand != null)");
      return true;
    }

    boolean res = compute(senderId, rtpArgs, nextCommand, messageMethod);
    RTP.log(Level.FINER, "[RTP][trace] RTPCmd.onCommand(4-arg default) compute returned " + res);
    return res;
  }

  // CommandsAPI puts the responsibility on the plugin to decide when and how to run compute() - called via CommandsAPI.execute() in SyncTaskProcessing
  default boolean compute(
          UUID senderId, Map<String, List<String>> rtpArgs, CommandsAPICommand nextCommand) {
    return compute(senderId, rtpArgs, nextCommand, null);
  }

  default boolean compute(
      UUID senderId, Map<String, List<String>> rtpArgs, CommandsAPICommand nextCommand, java.util.function.Consumer<String> messageMethod) {
    if (nextCommand != null) {
      return true;
    }
    if (senderId.equals(new java.util.UUID(0, 0)) && !rtpArgs.containsKey("player")) {
      if(messageMethod != null) {
        ConfigParser<MessagesKeys> langParser =
                (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
        String msg = (String) langParser.getConfigValue(MessagesKeys.consoleCmdNotAllowed, "");
        messageMethod.accept(msg);
      }
      else RTP.serverAccessor.sendMessage(senderId, MessagesKeys.consoleCmdNotAllowed);
      return true;
    }

    // --- L6 cross-server pre-dispatch hook (rtp-proxy-ADR-014) ----------------
    // Consult the platform-installed NetworkCommandHook BEFORE the local
    // pipeline runs. Default install (LOCAL_ONLY) is a zero-cost no-op for
    // single-server deployments. Network-mode adapters return CrossServer
    // (enrol + short-circuit), Reject (localized message + short-circuit),
    // or Local (fall through unchanged). S-004: hook throws degrade to
    // local fall-through with a WARNING log, never silent-swallow.
    if (!senderId.equals(new java.util.UUID(0, 0))) {
      io.github.dailystruggle.rtp.api.network.NetworkCommandHook hook = RTP.networkCommandHook;
      if (hook != null && hook != io.github.dailystruggle.rtp.api.network.NetworkCommandHook.LOCAL_ONLY) {
        io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult decision;
        try {
          decision = hook.route(senderId, rtpArgs);
        } catch (Throwable t) {
          RTP.log(Level.WARNING,
                  "[NETWORK] command hook threw; falling back to local pipeline: " + t.getMessage(), t);
          decision = io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.local();
        }
        if (decision instanceof io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.CrossServer cross) {
          ConfigParser<MessagesKeys> langParser =
                  (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
          String tmpl = (String) langParser.getConfigValue(MessagesKeys.networkQueued, "");
          // Empty-template fallback: a stale messages.yml (pre-Slice-5
          // install missing the new key) must NEVER produce silent
          // dispatch, otherwise the player sees no response and spams
          // /rtp. Mirror the hardcoded English fallback Slice-5
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
          else RTP.serverAccessor.sendMessage(senderId, msg);
          // KEEP the processingPlayers lock for the cross-server branch.
          // Prior behaviour released it immediately so that the
          // destination backend's eventual transfer wouldn't be blocked
          // by this JVM's "busy" set - but the side effect was that the
          // player could re-run /rtp on the same lobby and re-enrol
          // duplicates (REJECTED_DUPLICATE on the proxy, no feedback
          // to the player, looks like nothing happens). The Slice-4
          // NetworkWaitlistGuard + lobby-side quit/terminal-status
          // listeners are the correct authoritative releases; until
          // they fire, this local lock is the anti-spam primitive.
          // Disconnect / shutdown clears the set via RTP.shutdown().
          return true;
        }
        if (decision instanceof io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.Reject reject) {
          ConfigParser<MessagesKeys> langParser =
                  (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
          MessagesKeys key;
          try {
            key = MessagesKeys.valueOf(reject.messageKey());
          } catch (IllegalArgumentException ex) {
            key = MessagesKeys.networkRegionUnavailable;
          }
          String tmpl = (String) langParser.getConfigValue(key, "");
          String msg = tmpl == null ? "" : tmpl
                  .replace("[region]", reject.placeholder())
                  .replace("[reason]", reject.placeholder());
          if (!msg.isEmpty()) {
            if (messageMethod != null) messageMethod.accept(msg);
            else RTP.serverAccessor.sendMessage(senderId, msg);
          }
          // Release the processingPlayers lock acquired by the outer
          // onCommand. See CrossServer branch comment above.
          if (!senderId.equals(CommandsAPI.serverId)) RTP.getInstance().processingPlayers.remove(senderId);
          return true;
        }
        // RoutingResult.Local -> fall through to local pipeline
      }
    }
    // --- end L6 hook ---------------------------------------------------------

    RTPCommandSender sender = RTP.serverAccessor.getSender(senderId);
    RTP.log(Level.FINER, "[RTP][trace] RTPCmd.compute ENTER senderId=" + senderId
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
        RTP.log(Level.WARNING, "[RTP] PvP gate evaluation threw; allowing teleport", t);
        pvpAction = io.github.dailystruggle.rtp.api.hooks.PvPCombatAction.ALLOW;
      }
      if (pvpAction != io.github.dailystruggle.rtp.api.hooks.PvPCombatAction.ALLOW) {
        // DENY / CANCEL / DELAY all collapse to "refuse this new request" at the
        // pre-dispatch surface (there is no in-progress teleport to abort yet).
        ConfigParser<MessagesKeys> langParser =
                (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
        String msg = (String) langParser.getConfigValue(MessagesKeys.pvpInCombat,
                "&c[RTP] You cannot use /rtp while in combat.");
        if (msg != null && !msg.isEmpty()) {
          if (messageMethod != null) messageMethod.accept(msg);
          else RTP.serverAccessor.sendMessage(senderId, MessagesKeys.pvpInCombat);
        }
        // S-004: the refusal is never silent.
        RTP.log(Level.WARNING, "[RTP] PvP gate refused /rtp for " + senderId
                + " (action=" + pvpAction + ", in combat)");
        return true;
      } else if (io.github.dailystruggle.rtp.common.pvp.PvPGate.isEnabled()
              && io.github.dailystruggle.rtp.common.pvp.PvPGate.isInCombat(senderId)) {
        // ALLOW-and-audit: operator wants visibility without changing behaviour.
        RTP.log(Level.INFO, "[RTP] PvP gate: " + senderId
                + " is in combat but pvpOnCombat=ALLOW; permitting /rtp");
      }
    }
    // --- end PvP gate --------------------------------------------------------

    if (!senderId.equals(CommandsAPI.serverId)) RTP.getInstance().processingPlayers.add(senderId);

    List<String> toggletargetpermsList = rtpArgs.get("toggletargetperms");
    boolean toggleTargetPerms =
        toggletargetpermsList != null && Boolean.parseBoolean(toggletargetpermsList.get(0));

    ConfigParser<LoggingKeys> logging =
        (ConfigParser<LoggingKeys>) RTP.configs.getParser(LoggingKeys.class);
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
      RTP.log(Level.INFO, "#0080ff[RTP] RTP command triggered by " + sender.name() + ".");
    }

    ConfigParser<MessagesKeys> langParser =
        (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
    ConfigParser<EconomyKeys> eco =
        (ConfigParser<EconomyKeys>) RTP.configs.getParser(EconomyKeys.class);
    ConfigParser<PerformanceKeys> perf =
        (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
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
        RTPPlayer p = RTP.serverAccessor.getPlayer(playerName);
        if (p == null) {
          String msg =
              (String)
                  langParser.getConfigValue(MessagesKeys.badArg, "[P0] bad parameter - [arg]");
          msg = msg.replace("[arg]", "player=" + playerName);
          if(messageMethod != null) messageMethod.accept(msg);
          else RTP.serverAccessor.sendMessage(senderId, msg);
          RTP.log(Level.WARNING, msg);
          continue;
        }

        players.add(p);
      }
    } else if (sender
        instanceof RTPPlayer) { // if no players but sender is a player, use sender's location
      players = new ArrayList<>(1);
      players.add((RTPPlayer) sender);
    } else { // if no players and sender isn't a player, idk who to send
      String msg = (String) langParser.getConfigValue(MessagesKeys.consoleCmdNotAllowed, "");
      if(messageMethod != null) messageMethod.accept(msg);
      failEvent(sender, msg);
      RTP.getInstance().processingPlayers.remove(senderId);
      return true;
    }

    List<String> biomeList = rtpArgs.get("biome");
    List<String> shapeNames = rtpArgs.get("shape");
    List<String> vertNames = rtpArgs.get("vert");
    double price = 0.0;
    double floor = 0.0;
    RTPEconomy economy = RTP.economy;
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
      floor = eco.getNumber(EconomyKeys.balanceFloor, 0.0d).doubleValue();
      if ((bal - price) < floor) {
        String s = langParser.getConfigValue(MessagesKeys.notEnoughMoney, "").toString();
        s = s.replace("[money]", String.valueOf(price));
        if(messageMethod != null) messageMethod.accept(s);
        else RTP.serverAccessor.sendMessage(senderId, s);
        RTP.getInstance().processingPlayers.remove(senderId);
        return true;
      }
    }

    for (int i = 0; i < players.size(); i++) {
      RTPPlayer player = players.get(i);

      if (verbose && rtpArgs.containsKey("player")) {
        RTP.log(Level.INFO, "#0080ff[RTP] RTP processing player:" + player.name());
      }

      // get their data
      TeleportData data = RTP.getInstance().latestTeleportData.get(player.uuid());
      RTP.log(Level.FINER, "[RTP][trace] RTPCmd.compute per-player loop playerId=" + player.uuid()
              + " priorData=" + (data == null ? "null" : ("completed=" + data.completed + " age=" + (System.currentTimeMillis() - data.time) + "ms")));
      // if player has an incomplete teleport
      if (data != null) {
        if (!data.completed) {
          RTP.log(Level.FINER, "[RTP][trace] RTPCmd.compute short-circuit ALREADY_TELEPORTING playerId=" + player.uuid() + " -> skipping pipeline (sending alreadyTeleporting message via serverAccessor)");
          String msg = (String) langParser.getConfigValue(MessagesKeys.alreadyTeleporting, "");
          RTP.serverAccessor.sendMessage(senderId, player.uuid(), msg);
          failEvent(sender, msg);
          continue;
        }

        if (toggleTargetPerms) {
          long dt = System.currentTimeMillis() - data.time;
          if (dt < 0) dt = Long.MAX_VALUE + dt;
          if (dt < player.cooldown()) {
            RTP.serverAccessor.sendMessage(senderId, player.uuid(), MessagesKeys.cooldownMessage);
            continue;
          }
        }

        RTP.getInstance().priorTeleportData.put(player.uuid(), data);
      }

      data = new TeleportData();
      io.github.dailystruggle.rtp.common.tools.MemoryTracker.track(data, "TeleportData-" + player.uuid().toString(), 120000L);
      data.sender = sender;
      RTP.getInstance().latestTeleportData.put(player.uuid(), data);

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

        ConfigParser<WorldKeys> worldParser = RTP.configs.getWorldParser(worldName);

        if (worldParser == null) {
          String msg = (String) langParser.getConfigValue(MessagesKeys.badArg, "[P0] bad parameter - [arg]");
          msg = msg.replace("[arg]", "world=" + worldName);
          if(messageMethod != null) messageMethod.accept(msg);
          else RTP.serverAccessor.sendMessage(senderId, msg);
          RTP.log(Level.FINER, "[RTP][trace] RTPCmd.compute REJECT badArg world senderId=" + senderId
                  + " worldName=" + worldName);
          RTP.log(Level.WARNING, msg);
          RTP.getInstance().processingPlayers.remove(senderId);
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
              io.github.dailystruggle.rtp.common.commands.menu.BiomeMenuSource.bestRegionForBiome(
                  senderId, requestedBiome, regionName);
          if (resolved != null) {
            if (!resolved.equals(regionName)) {
              RTP.log(Level.FINER, "[RTP][trace] RTPCmd.compute auto-region by biome senderId="
                  + senderId + " biome=" + requestedBiome + " worldDefaultRegion=" + regionName
                  + " -> resolvedRegion=" + resolved);
            }
            regionName = resolved;
          }
        }
      }

      SelectionAPI selectionAPI = RTP.selectionAPI;

      Region region;
      try {
        region = selectionAPI.getRegionOrDefault(regionName);
        RTP.log(Level.FINER, "[RTP][trace] RTPCmd.compute region resolved senderId=" + senderId
                + " requestedRegion=" + regionName + " resolvedRegion=" + region.name);
      } catch (IllegalArgumentException | IllegalStateException exception) {
        String msg = (String) langParser.getConfigValue(MessagesKeys.badArg, "[P0] bad parameter - [arg]");
        msg = msg.replace("[arg]", "region=" + regionName);
        RTP.serverAccessor.sendMessage(senderId, msg);
        RTP.log(Level.FINER, "[RTP][trace] RTPCmd.compute REJECT badArg region senderId=" + senderId
                + " regionName=" + regionName + " cause=" + exception.getClass().getSimpleName());
        RTP.log(Level.WARNING, msg);
        RTP.getInstance().processingPlayers.remove(senderId);
        RTP.getInstance().latestTeleportData.remove(senderId);
        return true;
      }

      // ADR-065: world-override region. When a world:<w> argument is present
      // (top-level `rtp world:<w>` or the region sub-parameter
      // `rtp region:<r> world:<w>`), rebind the resolved base region to the
      // requested world so the teleport actually lands there instead of
      // following the base region's configured world (e.g. world:nether would
      // otherwise resolve back to the overworld via WorldKeys.region). CLI and
      // menu share this path, so the menu emits the plain /rtp world:<w>.
      if (rtpArgs.containsKey("world")) {
        String worldOverride = pickOne(rtpArgs.get("world"), null);
        if (worldOverride != null && !worldOverride.isEmpty()) {
          Region worldRegion = selectionAPI.worldRegion(region.name, worldOverride);
          if (worldRegion != null && worldRegion != region) {
            RTP.log(Level.FINER, "[RTP][trace] RTPCmd.compute world-override senderId=" + senderId
                + " baseRegion=" + region.name + " world=" + worldOverride
                + " -> region=" + worldRegion.name);
            region = worldRegion;
          }
        }
      }

      RTPWorld rtpWorld = region.getWorld();
      if (rtpWorld == null) {
        String msg = (String) langParser.getConfigValue(MessagesKeys.badArg, "[P0] bad parameter - [arg]");
        msg = msg.replace("[arg]", "region=" + regionName);
        RTP.serverAccessor.sendMessage(senderId, msg);
        RTP.log(Level.WARNING, msg);
        RTP.getInstance().processingPlayers.remove(senderId);
        RTP.getInstance().latestTeleportData.remove(senderId);
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
          region.set(RegionKeys.shape, RTP.serverAccessor.getShape(rtpWorld.name()));
        }
      }

      if (economy != null && !sender.hasPermission("rtp.free")) {
        if (player.uuid().equals(senderId))
          data.cost += eco.getNumber(EconomyKeys.price, 0.0).doubleValue();
        else if (player.hasPermission("rtp.notme")) continue;
        else data.cost += eco.getNumber(EconomyKeys.priceOther, 0.0).doubleValue();
        if (shapeNames != null || vertNames != null || doWBO)
          data.cost += eco.getNumber(EconomyKeys.paramsPrice, 0.0).doubleValue();
        if (biomeList != null)
          data.cost += eco.getNumber(EconomyKeys.biomePrice, 0.0).doubleValue();

        data.cost += region.getNumber(RegionKeys.price, 0.0d).doubleValue();

        if (economy.bal(senderId) - data.cost < floor) {
          String s = langParser.getConfigValue(MessagesKeys.notEnoughMoney, "").toString();
          s = s.replace("[money]", String.valueOf(price));
          RTP.serverAccessor.sendMessage(senderId, s);
          RTP.getInstance().processingPlayers.remove(senderId);
          return true;
        }

        final RTPEconomy economyRefSelf = economy;
        final double takeCostSelf = data.cost;
        final double priceSelfForMsg = price;
        io.github.dailystruggle.rtp.common.economy.EconomyHop.run(
            () -> {
              if (!economyRefSelf.take(senderId, takeCostSelf)) {
                RTP.log(java.util.logging.Level.WARNING,
                    "[RTP] economy.take returned false for " + senderId
                        + " cost=" + takeCostSelf + " (balance check passed earlier)");
                String s = langParser.getConfigValue(MessagesKeys.notEnoughMoney, "").toString();
                s = s.replace("[money]", String.valueOf(priceSelfForMsg));
                RTP.serverAccessor.sendMessage(senderId, s);
              }
            });
      }

      if (economy != null
          && toggleTargetPerms
          && !player.hasPermission("rtp.free")
          && !player.uuid().equals(senderId)) {
        data.cost += eco.getNumber(EconomyKeys.price, 0.0).doubleValue();
        if (shapeNames != null || vertNames != null || doWBO)
          data.cost += eco.getNumber(EconomyKeys.paramsPrice, 0.0).doubleValue();
        if (biomeList != null)
          data.cost += eco.getNumber(EconomyKeys.biomePrice, 0.0).doubleValue();

        data.cost += region.getNumber(RegionKeys.price, 0.0d).doubleValue();

        if (economy.bal(player.uuid()) - data.cost < floor) {
          String s = langParser.getConfigValue(MessagesKeys.notEnoughMoney, "").toString();
          s = s.replace("[money]", String.valueOf(price));
          RTP.serverAccessor.sendMessage(senderId, player.uuid(), s);
          RTP.getInstance().processingPlayers.remove(senderId);
          return true;
        }

        final RTPEconomy economyRefOther = economy;
        final java.util.UUID otherId = player.uuid();
        final double takeCostOther = data.cost;
        final double priceOtherForMsg = price;
        io.github.dailystruggle.rtp.common.economy.EconomyHop.run(
            () -> {
              if (!economyRefOther.take(otherId, takeCostOther)) {
                RTP.log(java.util.logging.Level.WARNING,
                    "[RTP] economy.take returned false for " + otherId
                        + " cost=" + takeCostOther + " (balance check passed earlier)");
                String s = langParser.getConfigValue(MessagesKeys.notEnoughMoney, "").toString();
                s = s.replace("[money]", String.valueOf(priceOtherForMsg));
                RTP.serverAccessor.sendMessage(senderId, otherId, s);
              }
            });
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

          RTP.selectionAPI.tempRegions.put(senderId, region);
          String shapeName = pickOne(shapeNames, "CIRCLE");

          Factory<?> factory = RTP.factoryMap.get(RTP.factoryNames.shape);
          FactoryValue<?> factoryValue = factory.get(shapeName);
          if (!(factoryValue instanceof Shape<?>)) {
            RTP.log(
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
            if (rtpArgs.containsKey(name)) {
              String string = pickOne(rtpArgs.get(name), "");

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
                try {
                  value = Long.parseLong(string);
                } catch (IllegalArgumentException ignored) {
                  try {
                    value = Double.parseDouble(string);
                  } catch (IllegalArgumentException ignored2) {
                    try {
                      value = Boolean.valueOf(string);
                    } catch (IllegalArgumentException ignored3) {
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
      RTP.log(Level.FINER, "[RTP][trace] RTPCmd.compute TeleportPipelineTask constructed playerId=" + player.uuid()
              + " region=" + region.name
              + " biomes=" + biomes
              + " syncLoading=" + syncLoading);

      long delay = (toggleTargetPerms) ? player.delay() : sender.delay();
      data.delay = delay;
      if (delay > 0) {
        String msg = langParser.getConfigValue(MessagesKeys.delayMessage, "").toString();
        RTP.serverAccessor.sendMessage(senderId, player.uuid(), msg);
      }

      if (!syncLoading) {
        syncLoading = biomes == null && region.hasLocation(player.uuid()) && delay <= 0;
      }

      if (syncLoading) {
        RTP.log(Level.FINER, "[RTP][trace] RTPCmd.compute SUBMIT pipelineTask SYNC playerId=" + player.uuid());
        region.inFlightCalculations.incrementAndGet();
        pipelineTask.run();
      } else {
        RTP.log(Level.FINER, "[RTP][trace] RTPCmd.compute SUBMIT pipelineTask ASYNC playerId=" + player.uuid());
        region.inFlightCalculations.incrementAndGet();
        RTP.scheduler.runTaskAsynchronously(pipelineTask);
      }
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
