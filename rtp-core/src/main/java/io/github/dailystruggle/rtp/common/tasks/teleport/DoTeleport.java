package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Task for performing the actual teleportation */
public final class DoTeleport extends RTPRunnable {
  /** Actions to perform before teleportation */
  public static final List<Consumer<DoTeleport>> preActions = new ArrayList<>();

  /** Actions to perform after teleportation */
  public static final List<Consumer<DoTeleport>> postActions = new ArrayList<>();

  private final RTPCommandSender sender;
  private final RTPPlayer player;
  private final RTPCoords coords;
  private final Region region;

  /**
   * Constructor for DoTeleport
   *
   * @param sender the command sender
   * @param player the player being teleported
   * @param coords the target coordinates
   * @param region the target region
   */
  public DoTeleport(RTPCommandSender sender, RTPPlayer player, RTPCoords coords, Region region) {
    this.sender = sender;
    this.player = player;
    this.coords = coords;
    this.region = region;
  }

  @Override
  public void run() {
    try {
      preActions.forEach(consumer -> consumer.accept(this));

      RTPLocation location = location();
      // todo: safety checks
      location.world().platform(location);

      RTP.getInstance().invulnerablePlayers.put(player.uuid(), System.currentTimeMillis());

      TeleportData teleportData = RTP.getInstance().latestTeleportData.get(player.uuid());
      if (teleportData == null) {
        teleportData = new TeleportData();
        teleportData.sender = (sender != null) ? sender : player;
        teleportData.originalCoords =
            new RTPCoords(
                player.getLocation().world().name(),
                player.getLocation().x(),
                player.getLocation().y(),
                player.getLocation().z());
        teleportData.time = System.currentTimeMillis();
        teleportData.nextTask = this;
        teleportData.delay = sender.delay();
      }
      teleportData.targetRegion = region;
      teleportData.selectedCoords = coords;
      teleportData.completed = true;
      RTP.getInstance().latestTeleportData.put(player.uuid(), teleportData);

      RTP.getInstance().processingPlayers.remove(player.uuid());

      CompletableFuture<Boolean> setLocation = player.setLocation(location);

      Map<String, Object> dataMap = DatabaseAccessor.toColumns(teleportData);
      dataMap.put("playerName", player.name());
      Map<String, Object> saveMap = new HashMap<>();
      if (RTP.getInstance().databaseAccessor instanceof YamlFileDatabase) {
        saveMap.put(player.uuid().toString(), dataMap);
      } else {
        saveMap.put("UUID", player.uuid().toString());
        saveMap.putAll(dataMap);
      }
      RTP.getInstance().databaseAccessor.setValue("teleportData", saveMap);

      RTP.getInstance().chunkCleanupPipeline.add(new ChunkCleanup(coords, region));

      TeleportData finalTeleportData = teleportData;
      setLocation.thenAccept(
          aBoolean -> {
            ConfigParser<LoggingKeys> logging =
                (ConfigParser<LoggingKeys>) RTP.configs.getParser(LoggingKeys.class);
            boolean verbose = true;
            if (logging != null) {
              Object o = logging.getConfigValue(LoggingKeys.teleport, false);
              if (o instanceof Boolean) {
                verbose = (Boolean) o;
              } else {
                verbose = Boolean.parseBoolean(o.toString());
              }
            }

            if (aBoolean) {
              finalTeleportData.processingTime =
                  System.currentTimeMillis() - finalTeleportData.time;
              RTP.getInstance().latestTeleportData.put(player.uuid(), finalTeleportData);
              RTP.serverAccessor.sendMessage(player.uuid(), MessagesKeys.teleportMessage);

              if (verbose) {
                long time = finalTeleportData.processingTime;
                ConfigParser<MessagesKeys> langParser =
                    (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
                long days = TimeUnit.MILLISECONDS.toDays(time);
                long hours = TimeUnit.MILLISECONDS.toHours(time) % 24;
                long minutes = TimeUnit.MILLISECONDS.toMinutes(time) % 60;
                long seconds = TimeUnit.MILLISECONDS.toSeconds(time) % 60;
                long millis = time % 1000;
                if (millis > 500 && seconds > 0) {
                  seconds++;
                  millis = 0;
                }

                String replacement = "";
                if (days > 0)
                  replacement +=
                      days + langParser.getConfigValue(MessagesKeys.days, "").toString() + " ";
                if (hours > 0)
                  replacement +=
                      hours + langParser.getConfigValue(MessagesKeys.hours, "").toString() + " ";
                if (minutes > 0)
                  replacement +=
                      minutes
                          + langParser.getConfigValue(MessagesKeys.minutes, "").toString()
                          + " ";
                if (seconds > 0)
                  replacement +=
                      seconds + langParser.getConfigValue(MessagesKeys.seconds, "").toString();
                if (seconds < 2) {
                  replacement +=
                      millis + langParser.getConfigValue(MessagesKeys.millis, "").toString();
                }
                RTP.log(
                    Level.INFO,
                    "#00FFA0[RTP] completed teleport for player:"
                        + player.name()
                        + " in "
                        + replacement);
              }
            } else {
              if (verbose)
                RTP.log(
                    Level.WARNING, "[RTP] failed to complete teleport for player:" + player.name());
            }
          });

      ConfigParser<ConfigKeys> configParser =
          (ConfigParser<ConfigKeys>) RTP.configs.getParser(ConfigKeys.class);
      Object o = configParser.getConfigValue(ConfigKeys.consoleCommands, null);
      if (o instanceof List) {
        List<?> list = (List<?>) o;
        for (Object cmd : list) {
          try {
            RTP.serverAccessor
                .getSender(CommandsAPI.serverId)
                .performCommand(player, cmd.toString());
          } catch (Throwable throwable) {
            throwable.printStackTrace();
          }
        }
      }

      postActions.forEach(consumer -> consumer.accept(this));
    } catch (Throwable throwable) {
      throwable.printStackTrace();
      new RTPTeleportCancel(player.uuid()).run();
    }
  }

  /**
   * Get the command sender
   *
   * @return the command sender
   */
  public RTPCommandSender sender() {
    return sender;
  }

  /**
   * Get the player
   *
   * @return the player
   */
  public RTPPlayer player() {
    return player;
  }

  /**
   * Get the target location
   *
   * @return the location
   */
  public RTPLocation location() {
    return new RTPLocation(
        RTP.serverAccessor.getRTPWorld(coords.worldName()), coords.x(), coords.y(), coords.z());
  }

  /**
   * Get the target region
   *
   * @return the region
   */
  public Region region() {
    return region;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj == null || obj.getClass() != this.getClass()) return false;
    DoTeleport that = (DoTeleport) obj;
    return Objects.equals(this.sender, that.sender)
        && Objects.equals(this.player, that.player)
        && Objects.equals(this.coords, that.coords)
        && Objects.equals(this.region, that.region);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sender, player, coords, region);
  }

  @Override
  public String toString() {
    return "DoTeleport["
        + "sender="
        + sender
        + ", "
        + "player="
        + player
        + ", "
        + "coords="
        + coords
        + ", "
        + "region="
        + region
        + ']';
  }
}
