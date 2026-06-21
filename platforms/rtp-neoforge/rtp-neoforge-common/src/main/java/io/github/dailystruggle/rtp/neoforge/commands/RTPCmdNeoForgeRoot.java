package io.github.dailystruggle.rtp.neoforge.commands;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.RTPCmd;
import io.github.dailystruggle.rtp.common.commands.parameters.WorldParameter;
import io.github.dailystruggle.rtp.neoforge.server.NeoForgeServerAccessor;

import java.util.Collections;
import java.util.List;
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

        // Platform-neutral parameters (region / biome / toggletargetperms) and
        // every common subcommand are assembled once by rtp-core's
        // CoreCommandTreeBuilder. Only the genuinely platform-bound `player`
        // and `world` parameters are supplied here through the
        // PlatformCommandParameters seam (NeoForgeCommandParameters): their
        // validators and tab-complete values() reach for NeoForge online-player /
        // world enumeration, routed via RTP.serverAccessor.* /
        // NeoForgeServerAccessor so no net.minecraft.* leaks into the tree.
        io.github.dailystruggle.rtp.common.commands.CoreCommandTreeBuilder.attachCommonParameters(
            this, new NeoForgeCommandParameters());
        io.github.dailystruggle.rtp.common.commands.CoreCommandTreeBuilder.attachCommonSubcommands(this);

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

    /**
     * NeoForge source for the two platform-bound parameters. Validators route
     * world/player lookups through {@code RTP.serverAccessor.*} (never
     * {@code net.minecraft.*}); the {@code player} parameter's {@code values()}
     * surfaces the live online-player snapshot via
     * {@link NeoForgeServerAccessor#getOnlinePlayerNames()} so Brigadier offers
     * tab-completion, falling back to the empty set before the accessor is
     * bound (typed values still flow through the validator on execute).
     */
    private static final class NeoForgeCommandParameters
        implements io.github.dailystruggle.rtp.common.commands.PlatformCommandParameters {

        @Override
        public CommandParameter playerParameter() {
            return new CommandParameter(
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
            };
        }

        @Override
        public CommandParameter worldParameter() {
            return new WorldParameter(
                "rtp.world",
                "select a world to teleport to",
                (uuid, s) ->
                    RTP.serverAccessor.getRTPWorld(s) != null
                        && RTP.serverAccessor.getSender(uuid).hasPermission("rtp.worlds." + s));
        }
    }
}
