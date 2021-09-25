package leafcraft.rtp.API.Commands;

import org.bukkit.command.CommandExecutor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;


public interface SubCommand {
    enum ParamType {
        BOOLEAN,
        PLAYER,
        COORDINATE,
        WORLD,
        BIOME,
        REGION,
        SHAPE,
        MODE,
        NONE
    }

    @NotNull
    String getPerm();

    @Nullable
    CommandExecutor getCommandExecutor();

    void setSubCommand(@NotNull String name, @NotNull SubCommand subCommand);

    @Nullable
    SubCommand getSubCommand(@NotNull String name);

    @Nullable
    Map<String, SubCommand> getSubCommands();

    void setSubParam(@NotNull String name, @NotNull String perm, @NotNull ParamType type);

    @Nullable
    String getSubParamPerm(@NotNull String name);

    @Nullable
    ParamType getSubParamType(@NotNull String name);

    @Nullable
    List<String> getSubParams(@NotNull String perm);

    @Nullable
    Set<Map.Entry<String, ArrayList<String>>> getSubParams();
}
