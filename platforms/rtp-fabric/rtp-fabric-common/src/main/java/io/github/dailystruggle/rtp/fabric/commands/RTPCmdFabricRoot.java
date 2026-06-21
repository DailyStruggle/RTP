package io.github.dailystruggle.rtp.fabric.commands;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.RTPCmd;
import io.github.dailystruggle.rtp.common.commands.parameters.WorldParameter;
import io.github.dailystruggle.rtp.fabric.server.FabricServerAccessor;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Fabric root {@code /rtp} command.
 *
 * <p><b>Pure delegation.</b> All teleport logic lives in
 * {@link RTPCmd#compute(UUID, Map, CommandsAPICommand, java.util.function.Consumer)};
 * this class exists solely to provide a concrete, named
 * {@link CommandsAPICommand} the Brigadier adapter can convert.
 *
 * <h2>TODO — Full Bukkit parity</h2>
 *
 * The Bukkit equivalent ({@code RTPCmdBukkit}, ~115 line ctor) registers:
 * <ul>
 *   <li><b>Parameters:</b> {@code region} (with nested {@code world} / {@code price}
 *       / {@code worldborderoverride} / {@code shape} / {@code vert}),
 *       {@code biome}, {@code player} (target player),
 *       {@code world}, {@code toggletargetperms}.</li>
 *   <li><b>Subcommands:</b> {@code reload}, {@code help}, {@code config},
 *       {@code scan}, {@code info}, {@code test}.</li>
 * </ul>
 *
 * Parity work cannot reuse {@code RTPCmdBukkit} verbatim: its parameter
 * validators call {@code Bukkit.getWorld(s)} / {@code Bukkit.getPlayer(s)}
 * directly, which would violate ADR-022 §4. The Fabric port must route
 * through {@code RTP.serverAccessor.getRTPWorld(s)} /
 * {@code RTP.serverAccessor.getPlayer(s)} instead.
 *
 * <p>Permission-based parameter gating ({@code rtp.region}, {@code rtp.biome},
 * {@code rtp.world.*}, {@code rtp.other}) requires fabric-permissions-api
 * integration. The current root is permissive —
 * any player can run {@code /rtp} on themselves.
 */
public final class RTPCmdFabricRoot extends BaseRTPCmdImpl implements RTPCmd {

    public RTPCmdFabricRoot() {
        super(null);

        // Platform-neutral parameters (region / biome / toggletargetperms) and
        // every common subcommand are assembled once by rtp-core's
        // CoreCommandTreeBuilder. Only the genuinely platform-bound `player`
        // and `world` parameters are supplied here through the
        // PlatformCommandParameters seam (FabricCommandParameters): their
        // validators and tab-complete values() reach for Fabric online-player /
        // world enumeration, routed via RTP.serverAccessor.* /
        // FabricServerAccessor so no net.minecraft.* leaks into the tree.
        io.github.dailystruggle.rtp.common.commands.CoreCommandTreeBuilder.attachCommonParameters(
            this, new FabricCommandParameters());
        io.github.dailystruggle.rtp.common.commands.CoreCommandTreeBuilder.attachCommonSubcommands(this);

        // /rtp menu - mirror of RTPCmdBukkit:215-230. ADR-050 Stage 3β.D.2b
        // (2026-05-24) deleted the token registry; clicks carry concrete
        // /rtp menu ... commands resolved by MenuWiringSupport.attachTo.
        // Fabric platform pieces:
        //   * permissionProbe - routes through RTP.serverAccessor's ADR-048
        //     Phase B menuPermissionProbe override (FabricServerAccessor
        //     -> FabricEffectivePermissionsResolver per rtp-fabric-ADR-011).
        //   * renderer        - ChatMenuRenderer (rtp-fabric-ADR-012 §1):
        //     single rtp-core class, no per-version carrier split needed
        //     because the only platform coupling is the run-command sink
        //     installed on FabricServerAccessor.sendMessageWithRunCommand.
        //   * anvilOpener     - FabricChatPromptCallback (rtp-fabric-ADR-012
        //     §3): TTL-bounded chat-prompt substitute for the Paper anvil
        //     GUI; degrades to menuInvalid when ServerMessageEvents is not
        //     on the runtime classpath.
        final java.util.function.Function<UUID, java.util.function.Predicate<String>>
            menuPermissionProbe = viewer -> perm -> {
                if (perm == null || perm.isEmpty()) return true;
                if (viewer.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
                    return true;
                }
                return RTP.serverAccessor.menuPermissionProbe(viewer).test(perm);
            };
        // Prefer the written-book modal where the active carrier supports it
        // (1.21+ and the deobf 26.x line), falling back transparently to the
        // chat renderer on 1.20.x (where FabricVersionAdapter.openBookMenu
        // keeps its default false).
        final io.github.dailystruggle.rtp.api.menu.MenuRenderer menuRenderer =
            new io.github.dailystruggle.rtp.fabric.menu.FabricBookMenuRenderer(
                new io.github.dailystruggle.rtp.common.commands.menu.ChatMenuRenderer());
        final io.github.dailystruggle.rtp.common.commands.menu.MenuRedeemSubcommand.AnvilInputOpener
            anvilOpener =
                new io.github.dailystruggle.rtp.fabric.menu.FabricChatPromptCallback();
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
        // Defer to RTPCmd.compute (the canonical teleport dispatcher).
        // RTPCmd's interface default for the 4-arg overload would route here
        // anyway; making this explicit avoids any super-call ambiguity.
        RTP.log(Level.FINER, "[RTP][trace] RTPCmdFabricRoot.onCommand(4-arg) ENTER senderId=" + senderId
                + " params=" + parameterValues
                + " nextCommand=" + (nextCommand == null ? "null" : nextCommand.name())
                + " thread=" + Thread.currentThread().getName());
        if (nextCommand != null) {
            RTP.log(Level.FINER, "[RTP][trace] RTPCmdFabricRoot.onCommand returning early (nextCommand != null)");
            return true;
        }
        boolean res;
        try {
            res = compute(senderId, parameterValues, nextCommand, null);
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP][trace] RTPCmdFabricRoot.compute threw: " + t.getMessage(), t);
            throw t;
        }
        RTP.log(Level.FINER, "[RTP][trace] RTPCmdFabricRoot.compute returned " + res);
        return res;
    }

    @Override
    public void successEvent(RTPCommandSender sender, RTPPlayer player) {
        // Intentional no-op on Fabric (by design). The Bukkit overrides fire
        // TeleportCommandSuccessEvent / TeleportCommandFailEvent on the Bukkit
        // plugin event bus for third-party plugin observability; those events
        // are org.bukkit.event.Event subclasses and cannot be reused on Fabric.
        // Fabric has no equivalent plugin-event-bus consumer surface in scope,
        // and RTP's in-house runnable-collection hooks (RTPRunnable /
        // TeleportData) already cover internal observability.
    }

    @Override
    public void failEvent(RTPCommandSender sender, String msg) {
        // Intentional no-op on Fabric (by design) — see successEvent().
    }

    /**
     * Fabric source for the two platform-bound parameters. Validators route
     * world/player lookups through {@code RTP.serverAccessor.*} (never
     * {@code net.minecraft.*}); the {@code player} parameter's {@code values()}
     * surfaces the live online-player snapshot via
     * {@link FabricServerAccessor#getOnlinePlayerNames()} so Brigadier offers
     * tab-completion, falling back to the empty set before the accessor is
     * bound (typed values still flow through the validator on execute).
     */
    private static final class FabricCommandParameters
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
                    return RTP.serverAccessor instanceof FabricServerAccessor f
                        ? f.getOnlinePlayerNames()
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
