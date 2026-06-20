package io.github.dailystruggle.rtp.neoforge.commands;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.RTPCmd;
import io.github.dailystruggle.rtp.common.commands.config.ConfigCmd;
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
import io.github.dailystruggle.rtp.neoforge.server.NeoForgeServerAccessor;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * NeoForge root {@code /rtp} command.
 *
 * <p>Near-verbatim port of {@code RTPCmdFabricRoot}: the {@code commands-api}
 * tree is platform-neutral, so only the {@code values()} player-listing source
 * differs (routes through {@link NeoForgeServerAccessor#getOnlinePlayerNames()}
 * rather than the Fabric accessor). All teleport logic lives in
 * {@link RTPCmd#compute(UUID, Map, CommandsAPICommand, java.util.function.Consumer)};
 * this class provides the concrete, named root the Brigadier adapter converts.</p>
 *
 * <p>Parameter validators route world/player lookups through
 * {@code RTP.serverAccessor.*} (never {@code net.minecraft.*} server globals)
 * and permission checks through {@code RTP.serverAccessor.getSender(uuid)
 * .hasPermission(...)}, so permission gating works for free once the NeoForge
 * permission chain lands.</p>
 *
 * <p>The {@code /rtp menu} wiring (Fabric's {@code MenuWiringSupport.attachTo}
 * block) is intentionally omitted here. The teleport command and all other
 * subcommands are fully functional without it.</p>
 */
public final class RTPCmdNeoForgeRoot extends BaseRTPCmdImpl implements RTPCmd {

    public RTPCmdNeoForgeRoot() {
        super(null);

        // region (with nested world / price / worldborderoverride / shape / vert)
        RegionParameter regionParameter =
            new RegionParameter(
                "rtp.region",
                "select a region to teleport to",
                (uuid, s) ->
                    RTP.selectionAPI.regionNames().contains(s)
                        && RTP.serverAccessor.getSender(uuid).hasPermission("rtp.regions." + s));
        regionParameter.put(
            "world",
            new WorldParameter(
                "rtp.params",
                "override teleport world for this region",
                (uuid, s) ->
                    RTP.serverAccessor.getRTPWorld(s) != null
                        && RTP.serverAccessor.getSender(uuid).hasPermission("rtp.worlds." + s)));
        regionParameter.put(
            "price",
            new FloatParameter(
                "rtp.params",
                "override teleport cost for this region",
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
                "override world-border respect for this region",
                (uuid, s) -> s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")));
        regionParameter.put(
            "shape",
            new ShapeParameter(
                "rtp.params",
                "override region shape",
                (uuid, s) -> RTP.factoryMap.get(RTP.factoryNames.shape).contains(s)));
        regionParameter.put(
            "vert",
            new VertParameter(
                "rtp.params",
                "modify y selection",
                (uuid, s) -> RTP.factoryMap.get(RTP.factoryNames.vert).contains(s)));
        addParameter("region", regionParameter);

        // biome — preserve Locale.ROOT upper-casing (Turkish-i JVM bug).
        addParameter(
            "biome",
            new BiomeParameter(
                "rtp.biome",
                "select a biome to teleport to",
                (uuid, s) -> {
                    RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
                    if (s == null) return false;
                    int colon = s.indexOf(':');
                    String bareKey = (colon >= 0) ? s.substring(colon + 1) : s;
                    String upper = bareKey.toUpperCase(Locale.ROOT);
                    Set<String> biomes = RTP.serverAccessor.getBiomes();
                    boolean known = biomes.contains(s)
                            || biomes.contains(upper)
                            || biomes.contains(bareKey.toLowerCase(Locale.ROOT))
                            || biomes.contains("minecraft:" + bareKey.toLowerCase(Locale.ROOT));
                    return known
                            && (sender.hasPermission("rtp.biome.*")
                                || sender.hasPermission("rtp.biome." + bareKey)
                                || sender.hasPermission("rtp.biome." + s));
                }));

        // player — target-player parameter; values() surfaces the live online
        // snapshot for tab-completion via NeoForgeServerAccessor.
        addParameter(
            "player",
            new CommandParameter(
                "rtp.other",
                "teleport someone else",
                (uuid, s) -> {
                    RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
                    if (!sender.hasPermission("rtp.other")) return false;
                    RTPPlayer target = RTP.serverAccessor.getPlayer(s);
                    if (target == null || !target.name().equalsIgnoreCase(s)) return false;
                    RTPCommandSender targetSender = RTP.serverAccessor.getSender(target.uuid());
                    // Console (non-player sender) is exempt from rtp.notme - parity with RTPCmdBukkit.
                    return targetSender == null || !(sender instanceof RTPPlayer) || !targetSender.hasPermission("rtp.notme");
                }) {
                @Override
                public Set<String> values() {
                    return RTP.serverAccessor instanceof NeoForgeServerAccessor n
                        ? n.getOnlinePlayerNames()
                        : Collections.emptySet();
                }
            });

        // world — top-level world parameter.
        addParameter(
            "world",
            new WorldParameter(
                "rtp.world",
                "select a world to teleport to",
                (uuid, s) ->
                    RTP.serverAccessor.getRTPWorld(s) != null
                        && RTP.serverAccessor.getSender(uuid).hasPermission("rtp.worlds." + s)));

        // toggletargetperms
        addParameter(
            "toggletargetperms",
            new BooleanParameter(
                "rtp.params",
                "check player's perms when running this command",
                (uuid, s) ->
                    RTP.serverAccessor.getSender(uuid).hasPermission("rtp.params")
                        && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))));

        // ---- Subcommands ----
        // /rtp help intentionally NOT registered: commands-api's TreeCommand
        // auto-emits a complete built-in help listing when no HELP subcommand
        // exists. TestCmd is deferred (Bukkit-coupled); menu is N2.6.
        addSubCommand(new ReloadCmd(this));
        addSubCommand(new io.github.dailystruggle.rtp.common.commands.gui.GuiCmd(this));
        addSubCommand(new ConfigCmd(this));
        addSubCommand(new ScanCmd(this));
        addSubCommand(new InfoCmd(this));
        VersionCmd versionCmd = new VersionCmd(this);
        addSubCommand(versionCmd);
        getCommandLookup().put(VersionCmd.ALIAS.toUpperCase(), versionCmd);
        addSubCommand(new io.github.dailystruggle.rtp.common.commands.admin.ClearCacheCmd(this));

        // /rtp menu (N2.6) - mirror of RTPCmdFabricRoot. ADR-050: clicks carry
        // concrete /rtp menu ... commands resolved by MenuWiringSupport.attachTo.
        // NeoForge platform pieces:
        //   * permissionProbe - routes through RTP.serverAccessor's ADR-048
        //     menuPermissionProbe override (NeoForgeServerAccessor ->
        //     NeoForgeEffectivePermissionsResolver per rtp-fabric-ADR-011 port).
        //   * renderer        - NeoForgeBookMenuRenderer wrapping ChatMenuRenderer:
        //     prefers the written-book modal where the active carrier supports it
        //     (the 1.21.1 carrier does), falling back to chat otherwise.
        //   * anvilOpener     - NeoForgeChatPromptCallback: TTL-bounded chat-prompt
        //     substitute for the Paper anvil GUI, backed by the typed NeoForge
        //     ServerChatEvent.
        final java.util.function.Function<UUID, java.util.function.Predicate<String>>
            menuPermissionProbe = viewer -> perm -> {
                if (perm == null || perm.isEmpty()) return true;
                if (viewer.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
                    return true;
                }
                return RTP.serverAccessor.menuPermissionProbe(viewer).test(perm);
            };
        final io.github.dailystruggle.rtp.api.menu.MenuRenderer menuRenderer =
            new io.github.dailystruggle.rtp.neoforge.menu.NeoForgeBookMenuRenderer(
                new io.github.dailystruggle.rtp.common.commands.menu.ChatMenuRenderer());
        final io.github.dailystruggle.rtp.common.commands.menu.MenuRedeemSubcommand.AnvilInputOpener
            anvilOpener =
                new io.github.dailystruggle.rtp.neoforge.menu.NeoForgeChatPromptCallback();
        io.github.dailystruggle.rtp.common.commands.menu.MenuWiringSupport.attachTo(
            this,
            new io.github.dailystruggle.rtp.common.commands.menu.MenuPlatformBindings(
                menuPermissionProbe, menuRenderer, anvilOpener));
    }

    @Override
    public String name() {
        return "rtp";
    }

    @Override
    public boolean onCommand(UUID senderId,
                             Map<String, List<String>> parameterValues,
                             CommandsAPICommand nextCommand) {
        if (nextCommand != null) {
            return true;
        }
        boolean res;
        try {
            res = compute(senderId, parameterValues, nextCommand, null);
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP][NeoForge] RTPCmdNeoForgeRoot.compute threw: "
                    + t.getMessage(), t);
            throw t;
        }
        return res;
    }

    @Override
    public void successEvent(RTPCommandSender sender, RTPPlayer player) {
        // Intentional no-op on NeoForge (by design, not deferred), mirroring
        // RTPCmdFabricRoot: the Bukkit overrides fire Bukkit plugin-event-bus
        // events for third-party observability, which has no NeoForge analogue
        // in scope. RTP's in-house RTPRunnable / TeleportData hooks already
        // cover internal observability.
    }

    @Override
    public void failEvent(RTPCommandSender sender, String msg) {
        // Intentional no-op on NeoForge (by design) — see successEvent().
    }
}
