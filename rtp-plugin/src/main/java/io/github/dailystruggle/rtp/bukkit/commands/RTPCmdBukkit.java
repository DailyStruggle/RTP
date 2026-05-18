package io.github.dailystruggle.rtp.bukkit.commands;

import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.*;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.bukkit.events.TeleportCommandFailEvent;
import io.github.dailystruggle.rtp.bukkit.events.TeleportCommandSuccessEvent;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.api.menu.MenuConsumerProfile;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.api.menu.MenuTokenRegistry;
import io.github.dailystruggle.rtp.common.commands.RTPCmd;
import io.github.dailystruggle.rtp.common.commands.menu.CommandTreeMenuBuilder;
import io.github.dailystruggle.rtp.common.commands.menu.FrontPageBuilder;
import io.github.dailystruggle.rtp.common.commands.menu.LocalMenuTokenRegistry;
import io.github.dailystruggle.rtp.common.commands.menu.MenuRedeemSubcommand;
import io.github.dailystruggle.rtp.common.commands.scan.ScanCmd;
import io.github.dailystruggle.rtp.bukkit.commands.test.BukkitTestCmd;
import io.github.dailystruggle.rtp.common.commands.help.HelpCmd;
import io.github.dailystruggle.rtp.common.commands.info.InfoCmd;
import io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.ShapeParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.VertParameter;
import io.github.dailystruggle.rtp.common.commands.reload.ReloadCmd;
import io.github.dailystruggle.rtp.common.commands.config.ConfigCmd;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import java.util.Locale;
import java.util.function.Function;
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

    // Route reply messages through SendMessage so raw templates (placeholders,
    // '&' colour codes, hex tokens, PAPI) are formatted at the platform
    // boundary instead of leaking to console as e.g. "&c[P0] ...".
    // The default in BukkitTreeCommand is sender::sendMessage which bypasses
    // formatting entirely.
    this.messageMethodFactory =
        sender -> msg -> io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.sendMessage(sender, msg);

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
              // Biome keys are upper-cased with the root locale to avoid locale-dependent
              // case folding (e.g. Turkish 'i' -> 'İ'), which would otherwise miss biomes
              // such as ICE_SPIKES on operators running a tr_TR JVM.
              return (sender.hasPermission("rtp.biome.*") || sender.hasPermission("rtp.biome." + s))
                      && RTP.serverAccessor.getBiomes().contains(s.toUpperCase(java.util.Locale.ROOT));
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
              return player != null && player.getName().equalsIgnoreCase(s) && !player.hasPermission("rtp.notme");
            }));

    // world name parameter
    // filter by world exists and sender permission
    addParameter(
        "world",
        new WorldParameter(
            "rtp.world",
            "select a world to teleport to",
            (sender, s) -> {
                org.bukkit.World world = Bukkit.getWorld(s);
                return world != null && world.getName().equalsIgnoreCase(s) && sender.hasPermission("rtp.worlds." + s);
            }));

    addParameter(
        "toggletargetperms",
        new BooleanParameter(
            "rtp.params",
            "check player's perms when running this command",
            (sender, s) -> sender.hasPermission("rtp.params") && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))));

    addSubCommand(new ReloadCmd(this));
    addSubCommand(new HelpCmd(this));
    addSubCommand(new ConfigCmd(this));
    addSubCommand(new ScanCmd(this));
    addSubCommand(new InfoCmd(this));
    addSubCommand(new BukkitTestCmd(this));

    // /rtp menu — generalized menu subcommand (ADR-035 / ADR-044).
    // Restores the Stage 3.1 / 4.2.d / nav-5b wiring that
    // CHECKLIST-generalized-menu.md and CHECKLIST-menu-navigation.md describe.
    // The page builder reflects the live /rtp tree via CommandTreeMenuBuilder
    // (filtered by the viewer's permissions, so inaccessible rows are hidden,
    // not greyed). The renderer is resolved per `menu.renderer` config order,
    // reflectively (rtp-plugin does not link rtp-paper-common at compile
    // time — see build.gradle). If no renderer can be constructed the
    // subcommand stays registered and rejects with the configurable
    // `menuInvalid` message so /rtp menu is at least *recognised* on every
    // platform (REQ-RTP-S-007 / F-013).
    final MenuTokenRegistry menuTokenRegistry = new LocalMenuTokenRegistry();
    final Function<UUID, Predicate<String>> menuPermissionProbe =
        viewer -> perm -> {
          if (perm == null || perm.isEmpty()) return true;
          if (viewer.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
            return true;
          }
          Player p = Bukkit.getPlayer(viewer);
          return p != null && p.hasPermission(perm);
        };
    final MenuRenderer menuRenderer = selectMenuRenderer(menuTokenRegistry);
    final FrontPageBuilder frontPageBuilder = new FrontPageBuilder(menuTokenRegistry);
    final MenuRedeemSubcommand.MenuPageBuilder menuPageBuilder =
        (node, open, assembledPath) -> {
          // Stage B: the root /rtp menu page is the curated front page, not
          // the flat reflector. Any descended node (assembledPath non-empty)
          // or any node that isn't this command root falls through to the
          // existing CommandTreeMenuBuilder reflector. Visibility / row
          // selection is handled inside FrontPageBuilder via the viewer's
          // permission probe.
          if (node == this && assembledPath.isEmpty()) {
            return frontPageBuilder.build(
                node, open.viewer(), menuPermissionProbe.apply(open.viewer()));
          }
          return new CommandTreeMenuBuilder(menuTokenRegistry)
              .build(
                  node,
                  open.viewer(),
                  menuPermissionProbe.apply(open.viewer()),
                  MenuConsumerProfile.defaultProfile(),
                  assembledPath);
        };
    // Stage A.2: wire the param-picker builder so OpenParamPicker redeems
    // resolve to a value-picker sub-page instead of the "picker-page
    // disabled" reject path (see MenuRedeemSubcommand.dispatchOpenParamPicker).
    final MenuRedeemSubcommand.MenuParamPickerBuilder menuParamPickerBuilder =
        (parent, viewer, parentPath, paramName) ->
            new CommandTreeMenuBuilder(menuTokenRegistry)
                .buildParamPicker(
                    parent,
                    viewer,
                    menuPermissionProbe.apply(viewer),
                    MenuConsumerProfile.defaultProfile(),
                    parentPath,
                    paramName);
    // ADR-045: try to wire an anvil-GUI input opener for the
    // "type a custom value..." picker row. Reflective for the same reason
    // as the renderer — rtp-plugin doesn't link rtp-paper-common at compile
    // time. Null is the documented disabled state; redeem rejects with the
    // configurable menuInvalid message in that case.
    final MenuRedeemSubcommand.AnvilInputOpener anvilOpener =
        selectAnvilOpener(plugin);
    addSubCommand(
        new MenuRedeemSubcommand(
            this,
            menuTokenRegistry,
            menuPermissionProbe,
            menuRenderer,
            menuPageBuilder,
            menuParamPickerBuilder,
            anvilOpener));
  }

  /**
   * ADR-045 — instantiates the Paper {@code AnvilInputSession} reflectively
   * and registers it as a Bukkit listener bound to the RTP plugin. Returns
   * {@code null} when the Paper-side class is not on the runtime classpath
   * (e.g. plain Spigot), or instantiation / registration failed; callers
   * tolerate {@code null} (the picker row falls back to the configurable
   * {@code menuInvalid} reject when the player clicks it).
   */
  private static @org.jetbrains.annotations.Nullable
      MenuRedeemSubcommand.AnvilInputOpener selectAnvilOpener(Plugin plugin) {
    final String className =
        "io.github.dailystruggle.rtp.paper.menu.AnvilInputSession";
    try {
      Class<?> cls = Class.forName(className);
      Object instance = cls.getConstructor().newInstance();
      cls.getMethod("register", Plugin.class).invoke(instance, plugin);
      return (MenuRedeemSubcommand.AnvilInputOpener) instance;
    } catch (ClassNotFoundException cnfe) {
      RTP.log(
          Level.INFO,
          "menu anvil-input unavailable on this platform (" + className + ")");
      return null;
    } catch (ReflectiveOperationException roe) {
      RTP.log(
          Level.WARNING,
          "failed to instantiate menu anvil-input opener: " + roe.getMessage(),
          roe);
      return null;
    }
  }

  /**
   * Resolves the {@link MenuRenderer} from the {@code menu.renderer} config
   * list (first-wins; unknown / failing ids logged at WARNING and skipped).
   * Returns {@code null} if the list is empty, missing, or no listed id could
   * be instantiated — callers must tolerate {@code null} (the redeem path
   * degrades to the configurable {@code menuInvalid} message in that case).
   *
   * <p>Reflection-based on purpose: {@code rtp-plugin} does not declare a
   * compile-time dependency on {@code rtp-paper-common} (see this module's
   * {@code build.gradle}); the Paper {@code BookMenuRenderer} class is only
   * present transitively on the runtime classpath when a {@code rtp-paper-*}
   * adapter is loaded.
   */
  private static @org.jetbrains.annotations.Nullable MenuRenderer selectMenuRenderer(
      MenuTokenRegistry registry) {
    Object raw = null;
    try {
      @SuppressWarnings("unchecked")
      ConfigParser<ConfigKeys> menuConfig =
          (ConfigParser<ConfigKeys>) RTP.configs.getParser(ConfigKeys.class);
      if (menuConfig != null) {
        Object menuBlock = menuConfig.getConfigValue(ConfigKeys.menu, null);
        if (menuBlock instanceof Map<?, ?> map) {
          raw = map.get("renderer");
        } else {
          raw = menuBlock;
        }
      }
    } catch (RuntimeException ignored) {
      // Config not yet initialised (early-boot) or parser absent — fall
      // through to the default below.
    }
    List<String> ids = new ArrayList<>();
    if (raw instanceof List<?> list) {
      for (Object o : list) {
        if (o != null) ids.add(String.valueOf(o).trim().toLowerCase(Locale.ROOT));
      }
    } else if (raw instanceof String s && !s.isBlank()) {
      ids.add(s.trim().toLowerCase(Locale.ROOT));
    }
    if (ids.isEmpty()) {
      // Default-of-defaults: try `book`. Operators who explicitly want no
      // renderer can set `menu.renderer: []`.
      ids.add("book");
    }
    for (String id : ids) {
      MenuRenderer r = tryInstantiateRenderer(id, registry);
      if (r != null) return r;
    }
    RTP.log(
        Level.WARNING,
        "menu.renderer list exhausted (" + ids + "); /rtp menu open-page disabled");
    return null;
  }

  private static @org.jetbrains.annotations.Nullable MenuRenderer tryInstantiateRenderer(
      String id, MenuTokenRegistry registry) {
    final String className;
    switch (id) {
      case "book":
        className = "io.github.dailystruggle.rtp.paper.menu.BookMenuRenderer";
        break;
      default:
        RTP.log(Level.WARNING, "unknown menu.renderer id: " + id);
        return null;
    }
    try {
      Class<?> cls = Class.forName(className);
      return (MenuRenderer) cls
          .getConstructor(MenuTokenRegistry.class)
          .newInstance(registry);
    } catch (ClassNotFoundException cnfe) {
      RTP.log(
          Level.WARNING,
          "menu.renderer '" + id + "' unavailable on this platform (" + className + ")");
      return null;
    } catch (ReflectiveOperationException roe) {
      RTP.log(
          Level.WARNING,
          "failed to instantiate menu.renderer '" + id + "': " + roe.getMessage(),
          roe);
      return null;
    }
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


    boolean valid = true;
    for (Predicate<CommandSender> commandSenderPredicate : senderChecks) {
      valid &= commandSenderPredicate.test(sender);
    }
    if (!valid) {

      return false;
    }

    UUID senderUuid = sender instanceof Player
        ? ((Player) sender).getUniqueId()
        : io.github.dailystruggle.rtp.api.RTPAPI.serverId;

    return onCommand(
        RTP.serverAccessor.getSender(senderUuid),
        this,
        label,
        args);
  }

  @Override
  public boolean onCommand(
      UUID senderId,
      Map<String, List<String>> parameterValues,
      CommandsAPICommand nextCommand) {
    return onCommand(senderId, parameterValues, nextCommand, null);
  }

  @Override
  public boolean onCommand(
      UUID senderId,
      Map<String, List<String>> parameterValues,
      CommandsAPICommand nextCommand,
      java.util.function.Consumer<String> messageMethod) {

    if (nextCommand != null) return true;

    boolean valid = true;
    CommandSender sender =
        senderId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)
            ? Bukkit.getConsoleSender()
            : Bukkit.getPlayer(senderId);
    if (sender == null) {

        return false;
    }

    for (Predicate<CommandSender> commandSenderPredicate : senderChecks) {
      valid &= commandSenderPredicate.test(sender);
    }
    if (!valid) {

      return false;
    }

    // Route the messageMethod Consumer through SendMessage so any raw template
    // it receives from RTPCmd.compute is formatted (placeholders, '&' colour
    // codes, hex tokens, PAPI) before reaching the player/console. This keeps
    // formatting at the platform boundary and out of rtp-core. See REQ-RTP-F-013
    // and the AGENTS.md "Color handling" guidance.
    final CommandSender finalSender = sender;
    java.util.function.Consumer<String> wrapped =
        msg -> io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.sendMessage(finalSender, msg);

    return compute(senderId, parameterValues, nextCommand, wrapped);
  }

  @Override
  public boolean onCommand(
      CommandSender sender,
      Map<String, List<String>> parameterValues,
      CommandsAPICommand nextCommand) {

    if (nextCommand != null) return true;

    boolean valid = true;
    for (Predicate<CommandSender> commandSenderPredicate : senderChecks) {
      valid &= commandSenderPredicate.test(sender);
    }
    if (!valid) {

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
