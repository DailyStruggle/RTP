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

/**
 * Shadow {@link TreeCommand} mirroring a node from the live {@code /rtp} tree under {@link MenuRedeemSubcommand}.
 * Navigating via mirror opens the menu at the target node instead of executing the underlying command.
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
        // same CommandParameter instance is reused - its predicate and value
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
        // Eagerly mirror target TreeCommands as MenuMirrorSubcommands.
        // Dynamic additions are kept in sync by runtime mutation handlers.
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

    /**
     * Package-visible accessor used by {@link MenuRedeemSubcommand} to walk
     * the mirror tree to a specific sub-node for runtime add/remove of
     * multi-config children. Equivalent to a {@code getTarget()} but kept
     * narrowly scoped to package-private to discourage external coupling.
     */
    TreeCommand target() {
        return target;
    }

    /**
     * Package-visible accessor for the redeem instance, used by
     * {@link MenuRedeemSubcommand}'s runtime mirror-mutation helpers
     * when constructing fresh child mirrors so they share the same
     * {@code redeem} root.
     */
    MenuRedeemSubcommand redeem() {
        return redeem;
    }

    /**
     * Path from {@code /rtp} root down to (and including) this mirror's
     * target. Package-visible for the same reason as {@link #redeem()}:
     * fresh child mirrors need {@code parentPath + childName}.
     */
    List<String> path() {
        return path;
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
     * Leaf-side dispatch from args-form walker: delegates to {@link MenuRedeemSubcommand#renderForPath}.
     */
    @Override
    public boolean onCommand(UUID senderId,
                             Map<String, List<String>> parameterValues,
                             CommandsAPICommand nextCommand) {
        if (nextCommand != null) {
            // Intermediate node - let the parser descend into the child mirror.
            return true;
        }
        int pageIndex = MenuRedeemSubcommand.extractPageIndex(parameterValues);
        // No messageMethod available on the canonical 3-arg contract; the
        // commands-api default-method (CommandsAPICommand line 75) delegates
        // the 4-arg form here and discards the consumer, so renderForPath
        // receives a no-op sink consistent with the framework's own fallback.
        return redeem.renderForPath(
                senderId,
                target,
                path,
                parameterValues,
                pageIndex,
                msg -> {});
    }

}
