package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.menu.MenuAction;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

/**
 * ADR-050 Stage 1a (Proposed 2026-05-24): concrete `/rtp menu ...` and
 * `/rtp visualization` subcommand leaves that replace the opaque
 * `MenuTokenRegistry` indirection with self-documenting commands. Each leaf
 * routes into the matching package-private `dispatch*` helper on its owning
 * {@link MenuRedeemSubcommand}; permission gating, builder dispatch, and
 * S-004 reject paths remain inside the helper. No logic is duplicated.
 *
 * <p>This file is the Stage-1a subset (open / admin / front / visualizations
 * under {@code menu}, plus a root-level {@code visualization} sibling).
 * Stage 1b will extend the same pattern to the remaining leaves
 * (picker, page, config subtree, stage/unstage/apply/discard, info, multi).
 *
 * <p>Lifecycle: instances are constructed and registered by
 * {@code MenuRedeemSubcommand}'s constructor. They do not own state of
 * their own beyond the back-reference to the redeem subcommand.
 *
 * <p>Tokens coexist with these leaves throughout Stage 1; the renderer is
 * unchanged. Stage 2 will switch the renderer to emit these concrete
 * commands; Stage 3 deletes the token-redeem branch and registries.
 */
final class MenuConcreteCommandLeaves {

    private MenuConcreteCommandLeaves() {}

    /**
     * Parameter name on {@code /rtp menu open path=<dotted.path>}.
     * Dotted form ({@code config.regions}) is used in the wire grammar; the
     * leaf splits on {@code '.'} to recover the array form expected by
     * {@link MenuAction.OpenMenu}.
     */
    static final String PARAM_PATH = "path";

    /**
     * Parameter name on {@code /rtp visualization x=<regionName>} when the
     * regional drill-down lands in Stage 1b. In Stage 1a the parameter is
     * accepted (for forward compatibility with the renderer changes in
     * Stage 2) but a present value is currently ignored: the bare command
     * always opens the visualizations selector through
     * {@code dispatchOpenVisualizations}. The per-region drill-down arrives
     * with the {@code OpenMap(String)} record change in Stage 3.
     */
    static final String PARAM_X = "x";

    /**
     * {@code /rtp menu open [path=<dotted.path>]} - open a menu page at the
     * given dotted path under {@code /rtp}. Empty / missing path opens the
     * root menu page (same semantics as {@link MenuAction.OpenMenu} with an
     * empty path array).
     */
    static final class OpenMenuConcreteCmd extends io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl {

        private final MenuRedeemSubcommand owner;

        OpenMenuConcreteCmd(MenuRedeemSubcommand owner) {
            super(owner);
            this.owner = owner;
            addParameter(PARAM_PATH, new CommandParameter(MenuRedeemSubcommand.PERMISSION,
                    "dotted /rtp subtree path (empty = root menu page)",
                    (uuid, value) -> true) {
                @Override
                public Set<String> values() {
                    return Collections.emptySet();
                }
            });
        }

        @Override
        public String name() {
            return "open";
        }

        @Override
        public String permission() {
            return MenuRedeemSubcommand.PERMISSION;
        }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 @Nullable CommandsAPICommand nextCommand) {
            return dispatch(callerId, parameterValues, null);
        }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 @Nullable CommandsAPICommand nextCommand,
                                 Consumer<String> messageMethod) {
            return dispatch(callerId, parameterValues, messageMethod);
        }

        private boolean dispatch(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 @Nullable Consumer<String> messageMethod) {
            String[] path = parsePath(parameterValues);
            return owner.dispatchOpen(callerId, new MenuAction.OpenMenu(path), messageMethod);
        }

        private static String[] parsePath(@Nullable Map<String, List<String>> parameterValues) {
            if (parameterValues == null) return new String[0];
            List<String> raw = parameterValues.get(PARAM_PATH);
            if (raw == null || raw.isEmpty()) return new String[0];
            String value = raw.get(0);
            if (value == null || value.isEmpty()) return new String[0];
            // Wire grammar: dot-separated dotted path. Empty segments are
            // dropped (a leading/trailing/double dot is treated as a typo
            // by the user, not a structural assertion).
            String[] parts = value.split("\\.");
            int kept = 0;
            for (String p : parts) {
                if (p != null && !p.isEmpty()) kept++;
            }
            String[] out = new String[kept];
            int i = 0;
            for (String p : parts) {
                if (p != null && !p.isEmpty()) out[i++] = p;
            }
            return out;
        }
    }

    /**
     * {@code /rtp menu admin} - open the curated admin panel. Routes through
     * {@link MenuRedeemSubcommand#dispatchOpenAdminPanel}; permission gate
     * ({@code rtp.menu.admin}) stays inside the helper.
     */
    static final class OpenAdminPanelConcreteCmd extends io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl {

        private final MenuRedeemSubcommand owner;

        OpenAdminPanelConcreteCmd(MenuRedeemSubcommand owner) {
            super(owner);
            this.owner = owner;
        }

        @Override
        public String name() {
            return "admin";
        }

        @Override
        public String permission() {
            return MenuRedeemSubcommand.ADMIN_MENU_PERMISSION;
        }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 @Nullable CommandsAPICommand nextCommand) {
            return owner.dispatchOpenAdminPanel(callerId, null);
        }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 @Nullable CommandsAPICommand nextCommand,
                                 Consumer<String> messageMethod) {
            return owner.dispatchOpenAdminPanel(callerId, messageMethod);
        }
    }

    /**
     * {@code /rtp menu front} - open the curated front page. No permission
     * gate; the front page is the default landing for any menu viewer.
     */
    static final class OpenFrontPageConcreteCmd extends io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl {

        private final MenuRedeemSubcommand owner;

        OpenFrontPageConcreteCmd(MenuRedeemSubcommand owner) {
            super(owner);
            this.owner = owner;
        }

        @Override
        public String name() {
            return "front";
        }

        @Override
        public String permission() {
            return MenuRedeemSubcommand.PERMISSION;
        }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 @Nullable CommandsAPICommand nextCommand) {
            return owner.dispatchOpenFrontPage(callerId, null);
        }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 @Nullable CommandsAPICommand nextCommand,
                                 Consumer<String> messageMethod) {
            return owner.dispatchOpenFrontPage(callerId, messageMethod);
        }
    }

    /**
     * {@code /rtp menu visualizations} - open the visualizations selector.
     * Gates on {@code rtp.menu.admin} (inside
     * {@link MenuRedeemSubcommand#dispatchOpenVisualizations}).
     */
    static final class OpenVisualizationsConcreteCmd extends io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl {

        private final MenuRedeemSubcommand owner;

        OpenVisualizationsConcreteCmd(MenuRedeemSubcommand owner) {
            super(owner);
            this.owner = owner;
        }

        @Override
        public String name() {
            return "visualizations";
        }

        @Override
        public String permission() {
            return MenuRedeemSubcommand.ADMIN_MENU_PERMISSION;
        }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 @Nullable CommandsAPICommand nextCommand) {
            return owner.dispatchOpenVisualizations(callerId, null);
        }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 @Nullable CommandsAPICommand nextCommand,
                                 Consumer<String> messageMethod) {
            return owner.dispatchOpenVisualizations(callerId, messageMethod);
        }
    }

    /**
     * {@code /rtp visualization [x=<regionName>]} - root-level sibling of
     * {@code /rtp menu} that opens the visualizations selector. In Stage 1a
     * the {@code x} parameter is registered for forward compatibility but a
     * present value is ignored: the bare command always opens the selector
     * through {@link MenuRedeemSubcommand#dispatchOpenVisualizations}.
     * Per-region drill-down (replacing the {@code OpenMap(UUID chartSpecToken)}
     * record with {@code OpenMap(String regionName)} per ADR-050 §Decision 2)
     * arrives in Stage 3 with the {@code ChartSpecTokens} deletion.
     *
     * <p>This leaf is registered as a child of the {@code /rtp} root
     * (not as a child of {@code menu}) so the command is reachable as
     * {@code /rtp visualization} directly. Permission gate is
     * {@code rtp.menu.admin}, matching {@link OpenVisualizationsConcreteCmd}.
     */
    static final class VisualizationRootCmd extends io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl {

        private final MenuRedeemSubcommand owner;

        VisualizationRootCmd(CommandsAPICommand rtpRoot, MenuRedeemSubcommand owner) {
            super(rtpRoot);
            this.owner = owner;
            addParameter(PARAM_X, new CommandParameter(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION,
                    "region name (Stage 1b: opens per-region map; Stage 1a: ignored)",
                    (uuid, value) -> value != null && !value.isEmpty()) {
                @Override
                public Set<String> values() {
                    return Collections.emptySet();
                }
            });
        }

        @Override
        public String name() {
            return "visualization";
        }

        @Override
        public String permission() {
            return MenuRedeemSubcommand.ADMIN_MENU_PERMISSION;
        }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 @Nullable CommandsAPICommand nextCommand) {
            return owner.dispatchOpenVisualizations(callerId, null);
        }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 @Nullable CommandsAPICommand nextCommand,
                                 Consumer<String> messageMethod) {
            return owner.dispatchOpenVisualizations(callerId, messageMethod);
        }
    }
}
