package io.github.dailystruggle.rtp.common.commands.fill;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class FillCmd extends BaseRTPCmdImpl {
    public FillCmd(@Nullable CommandsAPICommand parent) {
        super(parent);
    }

    @Override
    public String name() {
        return "fill";
    }

    @Override
    public String permission() {
        return "rtp.fill";
    }

    @Override
    public String description() {
        return "clear stored data and try all possible region placements";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        if(nextCommand!=null) return true;



        return true;
    }
}
