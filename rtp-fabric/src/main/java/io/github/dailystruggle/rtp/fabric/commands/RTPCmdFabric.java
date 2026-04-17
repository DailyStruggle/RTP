package io.github.dailystruggle.rtp.fabric.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.RTPCmd;
import io.github.dailystruggle.rtp.common.commands.reload.ReloadCmd;
import io.github.dailystruggle.rtp.common.commands.scan.ScanCmd;
import io.github.dailystruggle.rtp.common.commands.help.HelpCmd;
import io.github.dailystruggle.rtp.common.commands.config.ConfigCmd;
import io.github.dailystruggle.rtp.common.commands.info.InfoCmd;
import io.github.dailystruggle.rtp.fabric.entity.FabricPlayer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class RTPCmdFabric extends FabricTreeCommand implements RTPCmd {
    public RTPCmdFabric() {
        super(null); // No parent for the base command

        addSubCommand(new ReloadCmd(this));
        addSubCommand(new HelpCmd(this));
        addSubCommand(new ConfigCmd(this));
        addSubCommand(new InfoCmd(this));
        // ScanCmd needs similar treatment if its available in core
        // addSubCommand(new ScanCmd(this));
    }

    @Override
    public String name() {
        return "rtp";
    }

    @Override
    public String permission() {
        return "rtp.use";
    }

    @Override
    public String description() {
        return "Randomly teleport";
    }

    @Override
    public boolean onCommand(UUID senderId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        return compute(senderId, parameterValues, nextCommand);
    }

    @Override
    public void successEvent(RTPCommandSender sender, RTPPlayer player) {
        // Handle success event, e.g., logging or special effects
    }

    @Override
    public void failEvent(RTPCommandSender sender, String msg) {
        // Handle failure event, e.g., error message to sender
    }
}
