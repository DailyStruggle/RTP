package io.github.dailystruggle.rtp.common.commands.fill;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public abstract class FillSubCmd extends BaseRTPCmdImpl {
    public FillSubCmd(@Nullable CommandsAPICommand parent) {
        super(parent);
        addParameter("region", new RegionParameter("rtp.fill","fill a specific region", (uuid, s) -> true));
    }

    @Override
    public String permission() {
        return "rtp.fill";
    }
}
