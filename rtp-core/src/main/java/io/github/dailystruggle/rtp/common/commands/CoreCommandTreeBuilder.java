package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.admin.ClearCmd;
import io.github.dailystruggle.rtp.common.commands.config.ConfigCmd;
import io.github.dailystruggle.rtp.common.commands.gui.GuiCmd;
import io.github.dailystruggle.rtp.common.commands.info.InfoCmd;
import io.github.dailystruggle.rtp.common.commands.parameters.BiomeParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.BooleanParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.FloatParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.ShapeParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.VertParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.WorldParameter;
import io.github.dailystruggle.rtp.common.commands.reload.ReloadCmd;
import io.github.dailystruggle.rtp.common.commands.scan.ScanCmd;
import io.github.dailystruggle.rtp.common.commands.version.VersionCmd;
import io.github.dailystruggle.rtp.common.network.NetworkModeBootstrap;
import io.github.dailystruggle.rtp.common.network.NetworkRouter;
import io.github.dailystruggle.rtp.common.network.PeerRegionRegistry;
import java.util.Locale;
import java.util.Set;

/**
 * Platform-agnostic registrar for the shared {@code /rtp} command tree.
 *
 * <p>Every {@code /rtp} leaf verb and every platform-neutral parameter is
 * defined in {@code rtp-core}; the only genuinely platform-bound pieces are
 * the {@code player} / {@code world} parameter sources (supplied through
 * {@link PlatformCommandParameters}) and the per-platform menu / test
 * wiring (kept in each platform root). This builder is the single place that
 * assembles the common surface so adding a verb is a one-file change.</p>
 *
 * <p>The resulting command tree is identical, in verbs, order-independent
 * map contents, permissions, and the {@link VersionCmd#ALIAS} lookup entry,
 * to the hand-rolled assembly the three platform roots previously
 * duplicated.</p>
 */
public final class CoreCommandTreeBuilder {

  private CoreCommandTreeBuilder() {}

  /**
   * Registers the platform-neutral parameters ({@code region}, {@code biome},
   * {@code toggletargetperms}) plus the platform-supplied {@code player} and
   * {@code world} parameters onto {@code root}.
   *
   * @param root     the {@code /rtp} root command
   * @param platform supplier of the platform-bound {@code player}/{@code world} parameters
   */
  public static void attachCommonParameters(BaseRTPCmd root, PlatformCommandParameters platform) {
    root.addParameter("region", buildRegionParameter());
    root.addParameter("biome", buildBiomeParameter());
    root.addParameter("player", platform.playerParameter());
    root.addParameter("world", platform.worldParameter());
    root.addParameter("toggletargetperms", buildToggleTargetPermsParameter());
  }

  /**
   * Registers the platform-neutral subcommands onto {@code root}, in the same
   * set the platform roots previously registered by hand:
   * {@code reload, gui, config, scan, info, version} (plus the
   * {@link VersionCmd#ALIAS} lookup alias) and {@code clear}.
   *
   * <p>{@code help} is intentionally not registered: commands-api's
   * {@code TreeCommand} auto-emits a built-in help listing covering every
   * registered subcommand when no {@code HELP} subcommand exists.</p>
   *
   * @param root the {@code /rtp} root command
   */
  public static void attachCommonSubcommands(BaseRTPCmd root) {
    root.addSubCommand(new ReloadCmd(root));
    root.addSubCommand(new GuiCmd(root));
    root.addSubCommand(new ConfigCmd(root));
    root.addSubCommand(new ScanCmd(root));
    root.addSubCommand(new InfoCmd(root));
    VersionCmd versionCmd = new VersionCmd(root);
    root.addSubCommand(versionCmd);
    root.getCommandLookup().put(VersionCmd.ALIAS.toUpperCase(Locale.ROOT), versionCmd);
    root.addSubCommand(new ClearCmd(root));
  }

  /**
   * Builds the {@code region} parameter (with its nested {@code world} /
   * {@code price} / {@code worldborderoverride} / {@code shape} / {@code vert}
   * sub-parameters).
   *
   * <p>The validator accepts a bare local region name (permission
   * {@code rtp.regions.<region>}) and, when network mode is live, a qualified
   * {@code server:region} target reachable per the live
   * {@link PeerRegionRegistry} (permissions {@code rtp.servers.<server>} and
   * {@code rtp.regions.<region>}). The extras supplier surfaces peer-qualified
   * entries in tab-completion. When network mode is not booted, the qualified
   * path rejects and only local region names resolve.</p>
   */
  private static RegionParameter buildRegionParameter() {
    RegionParameter regionParameter =
        new RegionParameter(
            "rtp.region",
            "select a region to teleport to",
            (uuid, s) -> {
              if (s == null) return false;
              RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
              // Path 1: bare local region name.
              if (RTP.selectionAPI.regionNames().contains(s)) {
                return sender.hasPermission("rtp.regions." + s);
              }
              // Path 2: qualified `server:region`. Parse strictly; on any
              // malformed input fall through to reject.
              NetworkRouter.ParsedRegion parsed;
              try {
                parsed = NetworkRouter.parseRegionArgQualified(s);
              } catch (IllegalArgumentException malformed) {
                return false;
              }
              if (parsed == null || parsed.serverHint() == null) return false;
              NetworkModeBootstrap live = NetworkModeBootstrap.LIVE;
              if (live == null) return false;
              PeerRegionRegistry registry = live.peerRegionRegistry();
              if (registry == null) return false;
              if (!registry.isReachableHardPin(parsed.serverHint(), parsed.regionKey())) {
                return false;
              }
              return sender.hasPermission("rtp.servers." + parsed.serverHint())
                  && sender.hasPermission("rtp.regions." + parsed.regionKey());
            },
            () -> {
              NetworkModeBootstrap live = NetworkModeBootstrap.LIVE;
              if (live == null) return Set.of();
              PeerRegionRegistry registry = live.peerRegionRegistry();
              return registry == null ? Set.<String>of() : registry.peerEntries();
            });
    regionParameter.put(
        "world",
        new WorldParameter(
            "rtp.params",
            "modify xz selection",
            (uuid, s) ->
                RTP.serverAccessor.getRTPWorld(s) != null
                    && RTP.serverAccessor.getSender(uuid).hasPermission("rtp.worlds." + s)));
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
    return regionParameter;
  }

  /**
   * Builds the {@code biome} parameter. Accepts namespaced biome ids
   * (e.g. {@code minecraft:badlands}) as well as bare enum names
   * ({@code BADLANDS}); membership is probed against
   * {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor#getBiomes()}
   * (which emits both forms) with {@link Locale#ROOT} case folding to avoid
   * the Turkish-i JVM bug. Gated on {@code rtp.biome.*} / {@code rtp.biome.<id>}.
   */
  private static BiomeParameter buildBiomeParameter() {
    return new BiomeParameter(
        "rtp.biome",
        "select a biome to teleport to",
        (uuid, s) -> {
          RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
          if (s == null) return false;
          int colon = s.indexOf(':');
          String bareKey = (colon >= 0) ? s.substring(colon + 1) : s;
          String upper = bareKey.toUpperCase(Locale.ROOT);
          Set<String> biomes = RTP.serverAccessor.getBiomes();
          boolean known =
              biomes.contains(s)
                  || biomes.contains(upper)
                  || biomes.contains(bareKey.toLowerCase(Locale.ROOT))
                  || biomes.contains("minecraft:" + bareKey.toLowerCase(Locale.ROOT));
          return known
              && (sender.hasPermission("rtp.biome.*")
                  || sender.hasPermission("rtp.biome." + bareKey)
                  || sender.hasPermission("rtp.biome." + s));
        });
  }

  /**
   * Builds the {@code toggletargetperms} parameter: whether the target
   * player's permissions are checked when running the command. Gated on
   * {@code rtp.params}; valid values are {@code true} / {@code false}.
   */
  private static BooleanParameter buildToggleTargetPermsParameter() {
    return new BooleanParameter(
        "rtp.params",
        "check player's perms when running this command",
        (uuid, s) ->
            RTP.serverAccessor.getSender(uuid).hasPermission("rtp.params")
                && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")));
  }
}
