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
        // shadow node anyway). Runtime-added children on the target after the
        // initial seed are wired in eagerly too: callers that mutate the real
        // tree at runtime (currently only {@link MenuRedeemSubcommand}'s
        // multi-config ADD/REMOVE branches via {@code registerMirrorChild} /
        // {@code unregisterMirrorChild}) are responsible for the symmetric
        // mutation on this mirror. There is intentionally no lazy merge-on-
        // read fallback: a second source of truth produced subtle ordering
        // bugs (the lazy override returning a fresh map silently discarded
        // {@code addSubCommand} writes; even the merge-and-write variant
        // raced with concurrent mutators).
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
     * Leaf-side dispatch from the args-form walker: when {@code nextCommand
     * != null} the parser is about to descend into a child mirror, so return
     * {@code true} (continuation will fire the child). Otherwise this is the
     * resolved leaf — hand the constructor-supplied sub-command path and the
     * parsed {@code parameterValues} map to {@link MenuRedeemSubcommand#renderForPath}
     * verbatim. Per commands-api/docs/README.md, the wire grammar has exactly
     * two token shapes — bare literal (sub-command) and {@code name=value}
     * (parameter) — and commands-api has already split them for us. We
     * deliberately do <strong>not</strong> re-encode parameters back into the
     * path here: that bypasses the framework's parsing contract and forces
     * downstream consumers to re-implement the split (the regression that
     * surfaced staged keys to {@code dispatchOpenConfigKey} as opaque tokens
     * like {@code "canceldistance=5"}).
     */
    @Override
    public boolean onCommand(UUID senderId,
                             Map<String, List<String>> parameterValues,
                             CommandsAPICommand nextCommand) {
        if (nextCommand != null) {
            // Intermediate node — let the parser descend into the child mirror.
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
