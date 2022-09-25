package commonTestImpl.commands;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.RTPCmd;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BaseCommand implements RTPCmd {

    @Override
    public void successEvent(RTPCommandSender sender, RTPPlayer player) {

    }

    @Override
    public void failEvent(RTPCommandSender sender, String msg) {

    }

    @Override
    public Map<String, CommandParameter> getParameterLookup() {
        return null;
    }

    @Override
    public Map<String, CommandsAPICommand> getCommandLookup() {
        return null;
    }

    @Override
    public CommandsAPICommand parent() {
        return null;
    }

    @Override
    public long avgTime() {
        return 0;
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        return true;
    }
}
