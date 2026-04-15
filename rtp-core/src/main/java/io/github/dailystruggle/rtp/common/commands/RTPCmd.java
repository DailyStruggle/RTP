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

  default void init() {}

  // synchronous command component
  default boolean onCommand(
      RTPCommandSender sender, CommandsAPICommand command, String label, String[] args) {
    UUID senderId = sender.uuid();

    if (RTP.reloading.get()) {
      RTP.serverAccessor.sendMessage(senderId, "&4busy");
      return true;
    }

    for (String arg : args) {
      if (!arg.contains(String.valueOf(CommandsAPI.parameterDelimiter))) {
        java.util.concurrent.CompletableFuture<Boolean> future = onCommand(senderId, sender::hasPermission, sender::sendMessage, args);

        future.whenComplete((result, throwable) -> {
          if (throwable != null) {
            RTP.log(Level.WARNING, "[DEBUG_LOG] RTPCmd.onCommand - library onCommand threw an exception:", throwable);
          }
        });

        return true;
      }
    }

    // ------------------------D--------------------------------------------------------------------------------------
    // guard command perms with custom message
    if (!sender.hasPermission("rtp.use")) {
      RTP.serverAccessor.sendMessage(senderId, MessagesKeys.noPerms);
      return true;
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

      if (dt < sender.cooldown()) {
        RTP.serverAccessor.sendMessage(senderId, MessagesKeys.cooldownMessage);
        return true;
      } else if (senderData.completed) { // resolve command bugs preemptively
        RTP.getInstance().processingPlayers.remove(senderId);
      }
    }

    if (RTP.getInstance().processingPlayers.contains(senderId)) {
      RTP.serverAccessor.sendMessage(senderId, MessagesKeys.alreadyTeleporting);
      return true;
    }

    if (!senderId.equals(CommandsAPI.serverId)) RTP.getInstance().processingPlayers.add(senderId);

    try {
      onCommand(senderId, sender::hasPermission, sender::sendMessage, args)
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

  // async command component
  default boolean compute(
      UUID senderId, Map<String, List<String>> rtpArgs, CommandsAPICommand nextCommand) {
    if (senderId.equals(new java.util.UUID(0, 0)) && !rtpArgs.containsKey("player")) {
      RTP.serverAccessor.sendMessage(senderId, MessagesKeys.consoleCmdNotAllowed);
      return true;
    }
    if (nextCommand != null) {
      return true;
    }

    RTPCommandSender sender = RTP.serverAccessor.getSender(senderId);

    RTP.getInstance().processingPlayers.add(senderId);

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
                  langParser.getConfigValue(MessagesKeys.badArg, "player:" + rtpArgs.get("player"));
          RTP.serverAccessor.sendMessage(senderId, msg);
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
        RTP.serverAccessor.sendMessage(senderId, s);
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
      // if player has an incomplete teleport
      if (data != null) {
        if (!data.completed) {
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
          String msg = (String) langParser.getConfigValue(MessagesKeys.badArg, "world:" + worldName);
          RTP.serverAccessor.sendMessage(senderId, msg);
          RTP.getInstance().processingPlayers.remove(senderId);
          return true;
        }

        regionName = worldParser.getConfigValue(WorldKeys.region, "default").toString();
      }

      SelectionAPI selectionAPI = RTP.selectionAPI;

      Region region;
      try {
        region = selectionAPI.getRegionOrDefault(regionName);
      } catch (IllegalArgumentException | IllegalStateException exception) {
        String msg = (String) langParser.getConfigValue(MessagesKeys.badArg, "region:" + regionName);
        RTP.serverAccessor.sendMessage(senderId, msg);
        RTP.getInstance().processingPlayers.remove(senderId);
        RTP.getInstance().latestTeleportData.remove(senderId);
        return true;
      }

      RTPWorld rtpWorld = region.getWorld();
      if (rtpWorld == null) {
        String msg = (String) langParser.getConfigValue(MessagesKeys.badArg, "region:" + regionName);
        RTP.serverAccessor.sendMessage(senderId, msg);
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

        boolean take = economy.take(senderId, data.cost);
        if (!take) {
          String s = langParser.getConfigValue(MessagesKeys.notEnoughMoney, "").toString();
          s = s.replace("[money]", String.valueOf(price));
          RTP.serverAccessor.sendMessage(senderId, s);
          RTP.getInstance().processingPlayers.remove(senderId);
          return true;
        }
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

        boolean take = economy.take(player.uuid(), data.cost);
        if (!take) {
          String s = langParser.getConfigValue(MessagesKeys.notEnoughMoney, "").toString();
          s = s.replace("[money]", String.valueOf(price));
          RTP.serverAccessor.sendMessage(senderId, player.uuid(), s);
          RTP.getInstance().processingPlayers.remove(senderId);
          return true;
        }
      }

      Set<String> biomes = null;
      if (biomeList != null) {
        biomes = new HashSet<>(biomeList.size());
        for (String biome : biomeList) {
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
              if (string.equalsIgnoreCase("true")) {
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
        region.inFlightCalculations.incrementAndGet();
        pipelineTask.run();
      } else {
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
    return "teleport randomly";
  }

  void successEvent(RTPCommandSender sender, RTPPlayer player);

  void failEvent(RTPCommandSender sender, String msg);
}
