package io.github.dailystruggle.rtp.bukkit.commands;

import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.*;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.bukkit.events.TeleportCommandFailEvent;
import io.github.dailystruggle.rtp.bukkit.events.TeleportCommandSuccessEvent;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.RTPCmd;
import io.github.dailystruggle.rtp.common.commands.fill.FillCmd;
import io.github.dailystruggle.rtp.common.commands.help.HelpCmd;
import io.github.dailystruggle.rtp.common.commands.info.InfoCmd;
import io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.ShapeParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.VertParameter;
import io.github.dailystruggle.rtp.common.commands.reload.ReloadCmd;
import io.github.dailystruggle.rtp.common.commands.config.ConfigCmd;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class RTPCmdBukkit extends BukkitBaseRTPCmd implements RTPCmd {
  // for optimizing parameters,

  private final Semaphore senderChecksGuard = new Semaphore(1);
  private final List<Predicate<CommandSender>> senderChecks = new ArrayList<>();

  public RTPCmdBukkit(Plugin plugin) {
    super(plugin, null);

    // region name parameter
    // filter by region exists and sender permission
    RegionParameter regionParameter =
        new RegionParameter(
            "rtp.region",
            "select a region to teleport to",
            (uuid, s) ->
                RTP.selectionAPI.regionNames().contains(s)
                    && RTP.serverAccessor.getSender(uuid).hasPermission("rtp.regions." + s));
    regionParameter.put(
        "world",
        new io.github.dailystruggle.rtp.common.commands.parameters.WorldParameter(
            "rtp.params",
            "modify xz selection",
            (uuid, s) ->
                (Bukkit.getWorld(s) != null)
                    & RTP.serverAccessor.getSender(uuid).hasPermission("rtp.worlds." + s)));
    regionParameter.put(
        "price",
        new FloatParameter(
            "rtp.params",
            "modify xz selection",
            (uuid, s) -> {
              try {
                Double.parseDouble(s);
                return true;
              } catch (NumberFormatException exception) {
                return false;
              }
            }));
    regionParameter.put(
        "worldborderoverride",
        new BooleanParameter(
            "rtp.params",
            "modify xz selection",
            (uuid, s) -> (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))));
    regionParameter.put(
        "shape",
        new ShapeParameter(
            "rtp.params",
            "modify xz selection",
            (uuid, s) -> RTP.factoryMap.get(RTP.factoryNames.shape).contains(s)));
    regionParameter.put(
        "vert",
        new VertParameter(
            "rtp.params",
            "modify y selection",
            (uuid, s) -> RTP.factoryMap.get(RTP.factoryNames.vert).contains(s)));

    addParameter("region", regionParameter);

    addParameter(
        "biome",
        new io.github.dailystruggle.rtp.common.commands.parameters.BiomeParameter(
            "rtp.biome",
            "select a biome to teleport to",
            (uuid, s) -> {
              RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
              return sender.hasPermission("rtp.biome.*") || sender.hasPermission("rtp.biome." + s);
            }));

    // target player parameter
    // filter by player exists and player permission
    addParameter(
        "player",
        new OnlinePlayerParameter(
            "rtp.other",
            "teleport someone else",
            (sender, s) -> {
              if (!sender.hasPermission("rtp.other")) return false;
              Player player = Bukkit.getPlayer(s);
              return player != null && !player.hasPermission("rtp.notme");
            }));

    // world name parameter
    // filter by world exists and sender permission
    addParameter(
        "world",
        new WorldParameter(
            "rtp.world",
            "select a world to teleport to",
            (sender, s) -> Bukkit.getWorld(s) != null && sender.hasPermission("rtp.worlds." + s)));

    addParameter(
        "toggletargetperms",
        new BooleanParameter(
            "rtp.params",
            "check player's perms when running this command",
            (sender, s) -> sender.hasPermission("rtp.params")));

    addSubCommand(new ReloadCmd(this));
    addSubCommand(new HelpCmd(this));
    addSubCommand(new ConfigCmd(this));
    addSubCommand(new FillCmd(this));
    addSubCommand(new InfoCmd(this));
  }

  public void addSenderCheck(Predicate<CommandSender> senderCheck) {
    try {
      senderChecksGuard.acquire();
      senderChecks.add(senderCheck);
    } catch (InterruptedException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    } finally {
      senderChecksGuard.release();
    }
  }

  @Override
  public boolean onCommand(CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
    System.out.println("[DEBUG_LOG] RTPCmdBukkit.onCommand called label=" + label);
    Bukkit.getLogger().info("[DEBUG_LOG] RTPCmdBukkit.onCommand(CommandSender, ...) called with label: " + label + " and args: " + java.util.Arrays.toString(args));
    boolean valid = true;
    for (Predicate<CommandSender> commandSenderPredicate : senderChecks) {
      valid &= commandSenderPredicate.test(sender);
    }
    if (!valid) {
      Bukkit.getLogger().info("[DEBUG_LOG] RTPCmdBukkit.onCommand(CommandSender, ...) sender checks failed.");
      return false;
    }
    return onCommand(
        RTP.serverAccessor.getSender(
            sender instanceof Player
                ? ((Player) sender).getUniqueId()
                : io.github.dailystruggle.rtp.api.RTPAPI.serverId),
        this,
        label,
        args);
  }

  @Override
  public boolean onCommand(
      UUID senderId,
      Map<String, List<String>> parameterValues,
      CommandsAPICommand nextCommand) {
    Bukkit.getLogger().info("[DEBUG_LOG] RTPCmdBukkit.onCommand(UUID, ...) called for senderId: " + senderId + ", nextCommand: " + (nextCommand != null ? nextCommand.getClass().getName() : "null"));
    if (nextCommand != null) return nextCommand.onCommand(senderId, parameterValues, null);

    boolean valid = true;
    CommandSender sender =
        senderId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)
            ? Bukkit.getConsoleSender()
            : Bukkit.getPlayer(senderId);
    if (sender == null) {
        Bukkit.getLogger().info("[DEBUG_LOG] RTPCmdBukkit.onCommand(UUID, ...) sender is null.");
        return false;
    }

    for (Predicate<CommandSender> commandSenderPredicate : senderChecks) {
      valid &= commandSenderPredicate.test(sender);
    }
    if (!valid) {
      Bukkit.getLogger().info("[DEBUG_LOG] RTPCmdBukkit.onCommand(UUID, ...) sender checks failed.");
      return false;
    }

    return compute(senderId, parameterValues, nextCommand);
  }

  @Override
  public boolean onCommand(
      CommandSender sender,
      Map<String, List<String>> parameterValues,
      CommandsAPICommand nextCommand) {
    Bukkit.getLogger().info("[DEBUG_LOG] RTPCmdBukkit.onCommand(CommandSender, Map, ...) called, nextCommand: " + (nextCommand != null ? nextCommand.getClass().getName() : "null"));
    if (nextCommand != null) return nextCommand.onCommand(
            sender instanceof Player ? ((Player) sender).getUniqueId() : io.github.dailystruggle.rtp.api.RTPAPI.serverId,
            parameterValues, null);

    boolean valid = true;
    for (Predicate<CommandSender> commandSenderPredicate : senderChecks) {
      valid &= commandSenderPredicate.test(sender);
    }
    if (!valid) {
      Bukkit.getLogger().info("[DEBUG_LOG] RTPCmdBukkit.onCommand(CommandSender, Map, ...) sender checks failed.");
      return false;
    }
    UUID uuid =
        sender instanceof Player
            ? ((Player) sender).getUniqueId()
            : io.github.dailystruggle.rtp.api.RTPAPI.serverId;
    return compute(uuid, parameterValues, nextCommand); // todo:async
  }

  @Override
  public void successEvent(RTPCommandSender sender, RTPPlayer player) {
    TeleportCommandSuccessEvent event = new TeleportCommandSuccessEvent(sender, player);
    Bukkit.getPluginManager().callEvent(event);
  }

  @Override
  public void failEvent(RTPCommandSender sender, String msg) {
    TeleportCommandFailEvent event = new TeleportCommandFailEvent(sender, msg);
    Bukkit.getPluginManager().callEvent(event);
  }
}
