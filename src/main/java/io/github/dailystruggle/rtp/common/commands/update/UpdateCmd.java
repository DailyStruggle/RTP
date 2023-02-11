package io.github.dailystruggle.rtp.common.commands.update;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class UpdateCmd extends BaseRTPCmdImpl {
    public UpdateCmd(@Nullable CommandsAPICommand parent) {
        super(parent);

        RTP.getInstance().miscAsyncTasks.add(new RTPRunnable(this::addCommands, 5));
    }

    @Override
    public String name() {
        return "update";
    }

    @Override
    public String permission() {
        return "rtp.update";
    }

    @Override
    public String description() {
        return "update configuration files at runtime";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        return true;
    }

    public void addCommands() {
        final Configs configs = RTP.configs;
        for (ConfigParser<?> value : configs.configParserMap.values()) {
            String name = value.name.replace(".yml", "");
            if (getCommandLookup().containsKey(name)) continue;
            addSubCommand(new SubUpdateCmd(this, value.name, value));
        }

        for (Map.Entry<Class<?>, MultiConfigParser<?>> e : configs.multiConfigParserMap.entrySet()) {
            MultiConfigParser<? extends Enum<?>> value = e.getValue();
            if (getCommandLookup().containsKey(value.name)) continue;
            addSubCommand(new SubUpdateCmd(this, value.name, value));
        }
    }
}
