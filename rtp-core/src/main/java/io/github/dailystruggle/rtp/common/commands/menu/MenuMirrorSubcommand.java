package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shadow {@link TreeCommand} that mirrors a node from the live {@code /rtp}
 * command tree underneath {@link MenuRedeemSubcommand}.
 *
 * <p>Walking {@code /rtp menu <sub> [<sub>...] [name=value]...} re-enters the
 * commands-api parser through these mirrors instead of the real subcommands:
 * each mirror exposes the target's child {@link CommandsAPICommand} graph
 * (wrapped in fresh {@code MenuMirrorSubcommand}s) and the target's
 * {@link CommandParameter} map verbatim, so tab-completion and parameter
 * grammar are identical. Execution diverges: instead of running the
 * underlying subcommand, the leaf mirror calls
 * {@link MenuRedeemSubcommand#renderAt(UUID, TreeCommand, List, int, Consumer)}
 * with the assembled path (constructor-supplied {@code path}, plus any
 * {@code name=value} segments rebuilt from the parsed {@code parameterValues}),
 * opening the menu at the corresponding node with the staged segments visible.
 * The actual command run only happens when the player clicks the Execute row
 * of the rendered page.
 *
 * <p>Permission: the mirror gates on {@link MenuRedeemSubcommand#PERMISSION}
 * ({@code rtp.menu}) rather than the underlying subcommand's permission —
 * navigating the menu is always cheaper than performing the operation, and
 * an Execute-row click is still subject to the underlying subcommand's own
 * permission check.
 *
 * <p>Lifetime: children and parameters are mirrored eagerly at construction
 * via {@link TreeCommand#addSubCommand} / {@link TreeCommand#addParameter},
 * so the mirror tree is a snapshot of the target subtree at the moment
 * {@link MenuRedeemSubcommand} is added to the {@code /rtp} root command.
 * Construction order: the {@code menu} subcommand must be added to the root
 * after all other {@code /rtp} subcommands so the walk sees them.
 */
public final class MenuMirrorSubcommand extends BaseRTPCmdImpl implements TreeCommand {

    private final MenuRedeemSubcommand redeem;
    private final TreeCommand target;
    /** Path from {@code /rtp} root down to (and including) {@link #target}. */
    private final List<String> path;

    MenuMirrorSubcommand(MenuRedeemSubcommand redeem,
                         CommandsAPICommand parent,
                         TreeCommand target,
                         List<String> path) {
        super(parent);
        this.redeem = Objects.requireNonNull(redeem, "redeem");
        this.target = Objects.requireNonNull(target, "target");
        this.path = List.copyOf(path);
        // Eagerly mirror the target's parameters by name (commands-api uses
        // these for tab-completion and `name=value` argument parsing). The
        // same CommandParameter instance is reused — its predicate and value
        // set already describe the target node's contract, and the mirror
        // never executes the underlying subcommand so there is no value in
        // diverging.
        Map<String, CommandParameter> targetParams = target.getParameterLookup();
        if (targetParams != null) {
            for (Map.Entry<String, CommandParameter> entry : targetParams.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                addParameter(entry.getKey(), entry.getValue());
            }
        }
        // Eagerly mirror the target's child TreeCommands as MenuMirrorSubcommands.
        // Non-TreeCommand children have no further navigation surface and are
        // skipped (they would not be walkable by the commands-api parser as a
        // shadow node anyway).
        Map<String, CommandsAPICommand> targetChildren = target.getCommandLookup();
        if (targetChildren != null) {
            for (CommandsAPICommand child : targetChildren.values()) {
                if (!(child instanceof TreeCommand tc)) continue;
                String childName = tc.name();
                if (childName == null || childName.isEmpty()) continue;
                List<String> childPath = new ArrayList<>(this.path.size() + 1);
                childPath.addAll(this.path);
                childPath.add(childName);
                addSubCommand(new MenuMirrorSubcommand(redeem, this, tc, childPath));
            }
        }
    }

    @Override
    public String name() {
        return target.name();
    }

    @Override
    public String permission() {
        // Navigating to a menu page is always allowed for menu users; the
        // underlying subcommand's permission is enforced separately when the
        // player clicks the Execute row.
        return MenuRedeemSubcommand.PERMISSION;
    }

    /**
     * Leaf-side dispatch from the args-form walker: when {@code nextCommand
     * != null} the parser is about to descend into a child mirror, so return
     * {@code true} (continuation will fire the child). Otherwise this is the
     * resolved leaf — assemble the staged {@code name=value} segments from
     * {@code parameterValues} and render the menu at {@link #target}.
     */
    @Override
    public boolean onCommand(@NotNull UUID senderId,
                             @NotNull Map<String, List<String>> parameterValues,
                             @Nullable CommandsAPICommand nextCommand) {
        return onCommand(senderId, parameterValues, nextCommand, msg -> {});
    }

    @Override
    public boolean onCommand(@NotNull UUID senderId,
                             @NotNull Map<String, List<String>> parameterValues,
                             @Nullable CommandsAPICommand nextCommand,
                             @NotNull Consumer<String> messageMethod) {
        if (nextCommand != null) {
            // Intermediate node — let the parser descend into the child mirror.
            return true;
        }
        List<String> assembled = new ArrayList<>(path);
        if (parameterValues != null) {
            for (Map.Entry<String, List<String>> entry : parameterValues.entrySet()) {
                String key = entry.getKey();
                List<String> vals = entry.getValue();
                if (key == null || vals == null || vals.isEmpty()) continue;
                // Skip the `page` parameter — it controls pagination of the
                // rendered model, not a staged subcommand argument.
                if (MenuRedeemSubcommand.PARAM_PAGE.equalsIgnoreCase(key)) continue;
                // Re-encode as `name=value[,value...]` matching commands-api wire form.
                StringBuilder sb = new StringBuilder(key).append('=');
                for (int i = 0; i < vals.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(vals.get(i));
                }
                assembled.add(sb.toString());
            }
        }
        int pageIndex = MenuRedeemSubcommand.extractPageIndex(parameterValues);
        return redeem.renderForPath(
                senderId,
                target,
                List.copyOf(assembled),
                parameterValues,
                pageIndex,
                messageMethod);
    }

}
