package io.github.dailystruggle.rtp.common.commands.fill;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.tasks.FillTask;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class FillResumeCmd extends FillSubCmd {
    private FillStartCmd fillStartCmd = new FillStartCmd(this);

    public FillResumeCmd(@Nullable CommandsAPICommand parent) {
        super(parent);

    }

    @Override
    public String name() {
        return "resume";
    }

    @Override
    public String description() {
        return "continue fill process";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        if(nextCommand!=null) return true;

        List<Region> regions = getRegions(callerId, parameterValues.get("region"));
        for(Region region : regions) {
            FillTask fillTask = RTP.getInstance().fillTasks.get(region.name);
            ConfigParser<LangKeys> parser = (ConfigParser<LangKeys>) RTP.getInstance().configs.getParser(LangKeys.class);
            if(fillTask==null) {
                return fillStartCmd.onCommand(callerId,parameterValues,null);
            }

            fillTask.pause.set(false);

            if(parser == null) continue;
            String msg = String.valueOf(parser.getConfigValue(LangKeys.fillResume,""));
            if(msg == null || msg.isBlank()) continue;
            msg = StringUtils.replaceIgnoreCase(msg, "[region]", region.name);
            RTP.serverAccessor.announce(msg,"rtp.fill");
        }
        return true;
    }

    public List<Region> getRegions(UUID callerId, List<String> regionParameter) {
        List<Region> regions = new ArrayList<>();
        RTPCommandSender sender = RTP.serverAccessor.getSender(callerId);
        if(regionParameter!=null) {
            for(String name : regionParameter) regions.add(RTP.getInstance().selectionAPI.getRegion(name));
        }
        else if(sender instanceof RTPPlayer player) regions.add(RTP.getInstance().selectionAPI.getRegion(player));
        else regions.add(RTP.getInstance().selectionAPI.getRegion("default"));
        return regions;
    }
}
