package io.github.dailystruggle.rtp.common.commands.update.list;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.jetbrains.annotations.Nullable;
import org.simpleyaml.configuration.file.YamlFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public class ListCmd extends BaseRTPCmdImpl {
    private final String name;
    private final Supplier<Set<String>> values;
    private final YamlFile file;
    private final String key;

    public ListCmd(String name, @Nullable CommandsAPICommand parent, Supplier<Set<String>> values, YamlFile file, String key) {
        super(parent);
        this.name = name;
        this.values = values;
        this.file = file;
        this.key = key;

        RTP.getInstance().miscAsyncTasks.add(new RTPRunnable(this::addCommands, 20));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String permission() {
        return "rtp.update";
    }

    @Override
    public String description() {
        return "update a list in this configuration file";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        if (nextCommand != null) return true;
        addCommands();

        ConfigParser<MessagesKeys> lang = (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
        String msg = String.valueOf(lang.getConfigValue(MessagesKeys.updating, ""));
        if (msg != null) msg = msg.replace("[filename]", name);
        RTP.serverAccessor.sendMessage(CommandsAPI.serverId, callerId, msg);

        List<String> stringList = file.getStringList(key);

        List<String> add = parameterValues.get("add");
        if (add != null) {
            stringList.addAll(add);
        }

        List<String> remove = parameterValues.get("remove");
        if (remove != null) {
            stringList.removeAll(remove);
        }

        file.set(key, stringList);
        try {
            file.save();
        } catch (IOException e) {
            e.printStackTrace();
            return true;
        }

        msg = String.valueOf(lang.getConfigValue(MessagesKeys.updated, ""));
        if (msg != null) msg = msg.replace("[filename]", name);
        RTP.serverAccessor.sendMessage(CommandsAPI.serverId, callerId, msg);

        return true;
    }

    public void addCommands() {
        addParameter("add", new ListAddParameter(values, file, key));
        addParameter("remove", new ListRemoveParameter(file, key));
    }
}
