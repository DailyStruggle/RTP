package io.github.dailystruggle.rtp.fabric.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.RTPCmd;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fabric root {@code /rtp} command (Phase 2 Step G — G1 minimal scope).
 *
 * <p><b>G1 scope:</b> bare-minimum {@code /rtp} that triggers the default RTP
 * teleport on the caller. <em>No parameters, no subcommands.</em> Brigadier
 * registration via {@link RTPCmdFabric#register} from
 * {@code RTPFabricMod.onInitialize()} using
 * {@code CommandRegistrationCallback.EVENT}.
 *
 * <p><b>Why minimal:</b> the user explicitly scoped Step G to "let permissions
 * be deferred and focus on getting `/rtp` to work" so a Fabric server can be
 * smoke-tested end-to-end before parameter/subcommand parity work begins. See
 * {@code MULTI_PLATFORM_PLAN.md} Step G status block.
 *
 * <p><b>Pure delegation.</b> All teleport logic lives in
 * {@link RTPCmd#compute(UUID, Map, CommandsAPICommand, java.util.function.Consumer)};
 * this class exists solely to provide a concrete, named, parameter-free
 * {@link CommandsAPICommand} the Brigadier adapter can convert.
 *
 * <h2>TODO — Full Bukkit parity (deferred follow-up Step G2)</h2>
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
 * {@code rtp.world.*}, {@code rtp.other}) blocks on Step F
 * (fabric-permissions-api integration). The current G1 root is permissive —
 * any player can run {@code /rtp} on themselves.
 */
public final class RTPCmdFabricRoot extends BaseRTPCmdImpl implements RTPCmd {

    public RTPCmdFabricRoot() {
        super(null);
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
        if (nextCommand != null) return true;
        return compute(senderId, parameterValues, nextCommand, null);
    }

    @Override
    public void successEvent(RTPCommandSender sender, RTPPlayer player) {
        // No-op on Fabric for G1: Bukkit fires Bukkit-specific events here
        // (TeleportCommandSuccessEvent) for plugin observability. Fabric has
        // no equivalent event bus yet — left as a Step G2 hook (custom
        // CommandsAPI event channel or a Fabric-side EventBus pattern).
    }

    @Override
    public void failEvent(RTPCommandSender sender, String msg) {
        // No-op on Fabric for G1 — see successEvent().
    }
}
