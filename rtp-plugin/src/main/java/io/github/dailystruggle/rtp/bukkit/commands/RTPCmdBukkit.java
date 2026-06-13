package io.github.dailystruggle.rtp.bukkit.commands;

import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.*;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.bukkit.events.TeleportCommandFailEvent;
import io.github.dailystruggle.rtp.bukkit.events.TeleportCommandSuccessEvent;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.commands.RTPCmd;
import io.github.dailystruggle.rtp.common.commands.menu.MenuRedeemSubcommand;
import io.github.dailystruggle.rtp.common.commands.scan.ScanCmd;
import io.github.dailystruggle.rtp.bukkit.commands.test.BukkitTestCmd;
import io.github.dailystruggle.rtp.common.commands.info.InfoCmd;
import io.github.dailystruggle.rtp.common.commands.version.VersionCmd;
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
    // filter by region exists and sender permission. L6 Slice H2: validator
    // also accepts qualified `server:region` syntax when the named peer is
    // reachable per the live PeerRegionRegistry (looked up dynamically via
    // NetworkModeBootstrap.LIVE so this code path works whether network
    // mode boots before or after command registration). Extras supplier
    // surfaces peer-qualified entries in tab-completion.
    RegionParameter regionParameter =
        new RegionParameter(
            "rtp.region",
            "select a region to teleport to",
            (uuid, s) -> {
              if (s == null) return false;
              RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
              // Path 1: bare local region name (pre-H2 behaviour).
              if (RTP.selectionAPI.regionNames().contains(s)) {
                return sender.hasPermission("rtp.regions." + s);
              }
              // Path 2: qualified `server:region` (H2). Parse strictly; on
              // any malformed input fall through to reject.
              io.github.dailystruggle.rtp.common.network.NetworkRouter.ParsedRegion parsed;
              try {
                parsed = io.github.dailystruggle.rtp.common.network.NetworkRouter.parseRegionArgQualified(s);
              } catch (IllegalArgumentException malformed) {
                return false;
              }
              if (parsed == null || parsed.serverHint() == null) return false;
              io.github.dailystruggle.rtp.common.network.NetworkModeBootstrap live =
                  io.github.dailystruggle.rtp.common.network.NetworkModeBootstrap.LIVE;
              if (live == null) return false;
              io.github.dailystruggle.rtp.common.network.PeerRegionRegistry registry =
                  live.peerRegionRegistry();
              if (registry == null) return false;
              if (!registry.isReachableHardPin(parsed.serverHint(), parsed.regionKey())) {
                return false;
              }
              // Two independent gates for a qualified `server:region` target:
              //   - `rtp.servers.<server>` allows/denies the destination
              //     backend (new; mirrors `rtp.regions.x` but keyed on the
              //     server hint), and
              //   - `rtp.regions.<region>` allows/denies the region.
              // Region permission is keyed on the bare region name, not the
              // qualified form, so an operator's existing
              // `rtp.regions.default` grant covers `backend-a:default`.
              return sender.hasPermission("rtp.servers." + parsed.serverHint())
                  && sender.hasPermission("rtp.regions." + parsed.regionKey());
            },
            () -> {
              io.github.dailystruggle.rtp.common.network.NetworkModeBootstrap live =
                  io.github.dailystruggle.rtp.common.network.NetworkModeBootstrap.LIVE;
              if (live == null) return java.util.Set.of();
              io.github.dailystruggle.rtp.common.network.PeerRegionRegistry registry =
                  live.peerRegionRegistry();
              return registry == null ? java.util.Set.<String>of() : registry.peerEntries();
            });
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
              // Accept namespaced biome ids (e.g. `minecraft:badlands`) as well as bare
              // enum names (`BADLANDS`). `ServerAccessor#getBiomes()` now emits both
              // forms, so a single membership probe covers both grammars; the user-typed
              // string is checked verbatim and (for the bare-name grammar) up-cased
              // with the root locale to avoid Turkish-i case folding.
              if (s == null) return false;
              int colon = s.indexOf(':');
              String bareKey = (colon >= 0) ? s.substring(colon + 1) : s;
              String upper = bareKey.toUpperCase(java.util.Locale.ROOT);
              java.util.Set<String> biomes = RTP.serverAccessor.getBiomes();
              boolean known = biomes.contains(s)
                      || biomes.contains(upper)
                      || biomes.contains(bareKey.toLowerCase(java.util.Locale.ROOT))
                      || biomes.contains("minecraft:" + bareKey.toLowerCase(java.util.Locale.ROOT));
              return known
                      && (sender.hasPermission("rtp.biome.*")
                              || sender.hasPermission("rtp.biome." + bareKey)
                              || sender.hasPermission("rtp.biome." + s));
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
    // /rtp help intentionally NOT registered as a subcommand: when no HELP
    // subcommand exists, commands-api's TreeCommand auto-dispatches the
    // built-in help() listing (see TreeCommand line 231), which covers
    // every registered subcommand instead of only those that happen to
    // have a MessagesKeys enum value. Clickable suggestions are restored
    // by the help-line wrapping in the messageMethod consumer below.
    addSubCommand(new ConfigCmd(this));
    addSubCommand(new ScanCmd(this));
    addSubCommand(new InfoCmd(this));
    VersionCmd versionCmd = new VersionCmd(this);
    addSubCommand(versionCmd);
    getCommandLookup().put(VersionCmd.ALIAS.toUpperCase(), versionCmd);
    addSubCommand(new io.github.dailystruggle.rtp.common.commands.admin.ClearCacheCmd(this));
    addSubCommand(new BukkitTestCmd(this));

    // /rtp menu — full ADR-035 / ADR-044 / ADR-050 menu surface, installed
    // by the platform-neutral MenuWiringSupport. ADR-050 Stage 3β.D.2b
    // (2026-05-24): the token registry is gone; click events carry concrete
    // /rtp menu ... commands. The only Bukkit-specific pieces are still
    // constructed here and passed in via MenuPlatformBindings:
    //   * menuPermissionProbe — Bukkit.getPlayer(...).hasPermission(...).
    //   * menuRenderer        — reflectively resolved against the
    //                           menu.renderer config list (BookMenuRenderer
    //                           when rtp-paper-common is on the classpath).
    //   * anvilOpener         — reflectively resolved Paper AnvilInputSession.
    // When any of the platform pieces are null the redeem path degrades to
    // the configurable menuInvalid message per REQ-RTP-S-004 / REQ-RTP-F-013.
    final Function<UUID, Predicate<String>> menuPermissionProbe =
        viewer -> perm -> {
          if (perm == null || perm.isEmpty()) return true;
          if (viewer.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
            return true;
          }
          Player p = Bukkit.getPlayer(viewer);
          return p != null && p.hasPermission(perm);
        };
    final MenuRenderer menuRenderer = selectMenuRenderer();
    final MenuRedeemSubcommand.AnvilInputOpener anvilOpener =
        selectAnvilOpener(plugin);
    io.github.dailystruggle.rtp.common.commands.menu.MenuWiringSupport.attachTo(
        this,
        new io.github.dailystruggle.rtp.common.commands.menu.MenuPlatformBindings(
            menuPermissionProbe, menuRenderer, anvilOpener));
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
  private static @org.jetbrains.annotations.Nullable MenuRenderer selectMenuRenderer() {
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
      MenuRenderer r = tryInstantiateRenderer(id);
      if (r != null) return r;
    }
    RTP.log(
        Level.WARNING,
        "menu.renderer list exhausted (" + ids + "); /rtp menu open-page disabled");
    return null;
  }

  private static @org.jetbrains.annotations.Nullable MenuRenderer tryInstantiateRenderer(
      String id) {
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
      // ADR-050 Stage 3β.D.2b (2026-05-24): renderer ctor is now no-arg.
      Class<?> cls = Class.forName(className);
      return (MenuRenderer) cls.getConstructor().newInstance();
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

    // Skip sender checks (e.g. NetworkWaitlistGuard) when the invocation
    // is a subcommand dispatch (e.g. `/rtp admin`, `/rtp menu token=...`,
    // `/rtp info`, `/rtp reload`). The checks gate the *teleport* path;
    // subcommands do not teleport and must not be blocked by waitlist /
    // cross-server state. Mirrors the early-return at the parametric
    // onCommand(...) entry that bails when nextCommand != null.
    boolean isSubcommand = args != null
        && args.length > 0
        && getCommandLookup().containsKey(args[0].toUpperCase(java.util.Locale.ROOT));

    if (!isSubcommand) {
      boolean valid = true;
      for (Predicate<CommandSender> commandSenderPredicate : senderChecks) {
        valid &= commandSenderPredicate.test(sender);
      }
      if (!valid) {

        return false;
      }
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
    // Pattern: lines emitted by commands-api's built-in TreeCommand#help()
    // for each subcommand look like
    //     "  - /<full command> <subname>\n    <description>"
    // (TreeCommand.java lines 422-425). Detect those and render them via
    // SendMessage's hover/click overload so /rtp help rows remain clickable
    // (SUGGEST_COMMAND) the way the removed HelpCmd used to render them.
    // Non-matching lines (root "Command:" header, "Subcommands:" /
    // "Parameters:" section labels, parameter rows) fall through to the
    // plain-string sink.
    final java.util.regex.Pattern helpSubcommandLine =
        java.util.regex.Pattern.compile("^\\s+-\\s+(/\\S[^\\r\\n]*?)\\s*(?:\\R[\\s\\S]*)?$");
    final io.github.dailystruggle.rtp.api.entity.RTPCommandSender rtpSender =
        RTP.serverAccessor.getSender(senderId);
    java.util.function.Consumer<String> wrapped =
        msg -> {
          if (msg != null && rtpSender != null) {
            java.util.regex.Matcher m = helpSubcommandLine.matcher(msg);
            if (m.matches()) {
              String click = m.group(1).trim();
              io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage
                  .sendMessage(rtpSender, msg, click, click);
              return;
            }
          }
          io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.sendMessage(finalSender, msg);
        };

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
