package leafcraft.rtp.commands;

import leafcraft.rtp.API.Commands.SubCommand;
import org.bukkit.command.CommandExecutor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class SubCommandImpl implements SubCommand {
    private final String perm;

    private final CommandExecutor commandExecutor;

    //parameter name,perm, for
    private final Map<String,String> subParams = new HashMap<>();

    //what type of param this is, for tabcompletion
    private final Map<String,ParamType> subParamTypes = new HashMap<>();

    //perm, list of params
    private final Map<String, ArrayList<String>> subParamsPermList = new HashMap<>();

    //command name, commands
    private final Map<String, SubCommand> commands = new HashMap<>();

    public SubCommandImpl(String perm, CommandExecutor commandExecutor) {
        this.perm = perm;
        this.commandExecutor = commandExecutor;
    }

    @Override
    public @NotNull String getPerm() {
        return perm;
    }

    @Override
    public CommandExecutor getCommandExecutor() {
        return this.commandExecutor;
    }

    @Override
    public void setSubCommand(@NotNull String name, @NotNull SubCommand subCommand) {
        commands.put(name, subCommand);
    }

    @Override
    public SubCommand getSubCommand(@NotNull String name) {
        return commands.get(name);
    }

    @Override
    public @Nullable Map<String, SubCommand> getSubCommands() {
        return commands;
    }

    @Override
    public void setSubParam(@NotNull String name, @NotNull String perm, @Nullable ParamType type) {
        if(type == null) type = ParamType.NONE;
        subParams.put(name,perm);
        subParamTypes.put(name,type);
        subParamsPermList.putIfAbsent(perm,new ArrayList<>());
        subParamsPermList.get(perm).add(name);
    }

    @Override
    @Nullable
    public String getSubParamPerm(@NotNull String name) {
        return subParams.get(name);
    }

    @Override
    @Nullable
    public ParamType getSubParamType(@NotNull String name) {
        return subParamTypes.get(name);
    }

    @Override
    @Nullable
    public List<String> getSubParams(@NotNull String perm) {
        return subParamsPermList.get(perm);
    }

    @Override
    public @Nullable Set<Map.Entry<String, ArrayList<String>>> getSubParams() {
        return subParamsPermList.entrySet();
    }
}
