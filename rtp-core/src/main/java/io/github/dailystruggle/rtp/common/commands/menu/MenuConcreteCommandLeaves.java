package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.Collections;
import java.util.LinkedHashSet;
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
     * Parameter name on {@code /rtp visualization bad-locations region=<regionName>}
     * (and any future visualization sub-command that drills down per region).
     * Always named {@code region}: the previous Stage 1a {@code x} alias on
     * {@code /rtp visualization} was removed when the typed sub-command
     * grammar landed.
     */
    static final String PARAM_REGION = "region";

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
     * {@code /rtp visualization} - root-level sibling of {@code /rtp menu}
     * that opens the visualizations selector. Typed per-kind sub-commands
     * (e.g. {@link VisualizationBadLocationsCmd}) hang off this node; the
     * bare {@code /rtp visualization} call always opens the selector
     * through {@link MenuRedeemSubcommand#dispatchOpenVisualizations}.
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
            // Typed per-kind sub-commands hang off the visualization root.
            // Each kind owns its own dispatcher class (e.g.
            // VisualizationDispatch for `bad-locations`) and does NOT call
            // back into MenuRedeemSubcommand for the dispatch itself -
            // visualization wiring is intentionally kept out of the legacy
            // menu/cart god-class. The selector fallback (no-arg, no-region)
            // is the one menu-side path that legitimately stays on `owner`.
            VisualizationDispatch dispatch =
                    new VisualizationDispatch(owner.permissionProbeFactory());
            // Each kind's leaf opens its own kind-scoped region picker when
            // invoked without region= (so the menu flow is "pick kind ->
            // pick region", not a loop back to the chart-kind picker).
            addSubCommand(new VisualizationBadLocationsCmd(
                    dispatch,
                    (uuid, msg) -> owner.dispatchOpenVisualizationRegions(
                            uuid,
                            io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE,
                            msg)));
            addSubCommand(new VisualizationBiomesCmd(
                    dispatch,
                    (uuid, msg) -> owner.dispatchOpenVisualizationRegions(
                            uuid,
                            io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.REGION_BIOMES,
                            msg)));
            // Sparkline is a global chart (no region), so it has no
            // kind-scoped region picker fallback - the leaf paints
            // directly when invoked with no parameters.
            addSubCommand(new VisualizationSparklineCmd(dispatch));
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
            // When a typed sub-command was matched (e.g. `bad-locations`),
            // commands-api's TreeCommand walker invokes the sub-command's
            // onCommand itself after this node completes (see
            // TreeCommand.java:267-272). We just need to step aside and
            // NOT also open the selector - opening it here would
            // double-execute alongside the sub-command. Only the bare
            // `/rtp visualization` (no sub-command) opens the selector.
            // Matches the pattern in ScanCmd / PrefabApplyCmd / TestCancelCmd.
            if (nextCommand != null) return true;
            return owner.dispatchOpenVisualizations(callerId, null);
        }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 @Nullable CommandsAPICommand nextCommand,
                                 Consumer<String> messageMethod) {
            if (nextCommand != null) return true;
            return owner.dispatchOpenVisualizations(callerId, messageMethod);
        }
    }

    /**
     * {@code /rtp visualization bad-locations [region=<regionName>]} - draw
     * the per-region {@link ChartSpec.Kind#REGION_BAD_LOCATIONS_SHAPE} chart.
     * When {@code region=} is absent the command falls through to the
     * visualizations selector (same behavior as bare
     * {@code /rtp visualization}); when {@code region=<name>} is present it
     * dispatches through {@link VisualizationDispatch#paintBadLocations},
     * which owns the permission gate, {@link ChartSpec} validation, and
     * {@code MapDispatch.paint} failure surfaces.
     *
     * <p>Crucially, this leaf does <strong>not</strong> hold a reference to
     * {@link MenuRedeemSubcommand}: visualization dispatch lives in its own
     * class so the legacy redeem/cart subcommand does not keep growing.
     * Only the no-region selector fallback (a menu-side concern) is passed
     * in as a {@link java.util.function.BiFunction} callback wired up by
     * {@link VisualizationRootCmd}.
     *
     * <p>Permission gate is {@code rtp.menu.admin}. The {@code region}
     * parameter's suggestion set is read live from
     * {@code RTP.selectionAPI.regionNames()} so tab-completion stays in
     * sync with the running configuration; an empty / missing
     * {@code selectionAPI} collapses to an empty suggestion set.
     */
    static final class VisualizationBadLocationsCmd
            extends io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl {

        private final VisualizationDispatch dispatch;
        private final java.util.function.BiFunction<UUID, Consumer<String>, Boolean> selectorOpener;

        VisualizationBadLocationsCmd(
                VisualizationDispatch dispatch,
                java.util.function.BiFunction<UUID, Consumer<String>, Boolean> selectorOpener) {
            // Parent is rewritten by addSubCommand on registration under
            // the `visualization` root - passing null here matches the
            // pattern used by other concrete leaves attached via
            // addSubCommand in this package.
            super(null);
            this.dispatch = java.util.Objects.requireNonNull(dispatch, "dispatch");
            this.selectorOpener = java.util.Objects.requireNonNull(selectorOpener, "selectorOpener");
            addParameter(PARAM_REGION, new CommandParameter(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION,
                    "region name (omit to open the visualizations selector)",
                    (uuid, value) -> value != null && !value.isEmpty()) {
                @Override
                public Set<String> values() {
                    return liveRegionNames();
                }
            });
        }

        @Override
        public String name() {
            return "bad-locations";
        }

        @Override
        public String permission() {
            return MenuRedeemSubcommand.ADMIN_MENU_PERMISSION;
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
            String regionName = firstValue(parameterValues, PARAM_REGION);
            RTP.log(java.util.logging.Level.FINE,
                    "[viz/bad-locations] leaf reached: caller=" + callerId
                            + " region=" + regionName
                            + " hasMsg=" + (messageMethod != null));
            if (regionName == null || regionName.isEmpty()) {
                // No region specified - fall through to the visualizations
                // selector. This is a menu-side concern, hence the callback
                // back into MenuRedeemSubcommand#dispatchOpenVisualizations.
                RTP.log(java.util.logging.Level.FINE,
                        "[viz/bad-locations] no region= -> opening selector");
                Boolean ok = selectorOpener.apply(callerId, messageMethod);
                return Boolean.TRUE.equals(ok);
            }
            boolean result = dispatch.paintBadLocations(callerId, regionName, messageMethod);
            RTP.log(java.util.logging.Level.FINE,
                    "[viz/bad-locations] leaf returning result=" + result);
            return result;
        }

        private static @Nullable String firstValue(@Nullable Map<String, List<String>> values,
                                                   String key) {
            if (values == null) return null;
            List<String> raw = values.get(key);
            if (raw == null || raw.isEmpty()) return null;
            String first = raw.get(0);
            return (first == null || first.isEmpty()) ? null : first;
        }

        private static Set<String> liveRegionNames() {
            try {
                if (RTP.selectionAPI == null) return Collections.emptySet();
                Set<String> names = RTP.selectionAPI.regionNames();
                if (names == null || names.isEmpty()) return Collections.emptySet();
                return new LinkedHashSet<>(names);
            } catch (RuntimeException e) {
                return Collections.emptySet();
            }
        }
    }

    /**
     * {@code /rtp visualization biomes [region=<regionName>]} - draw the
     * per-region {@link ChartSpec.Kind#REGION_BIOMES} chart. Structural
     * twin of {@link VisualizationBadLocationsCmd} (same parameter set,
     * same permission gate, same selector fallback); only the dispatch
     * method and command name differ. Biome data is read exclusively from
     * the region's {@code MemoryShape.biomeKeysCache} - no
     * {@code World#getBiome}, no anvil prefilter, no chunk I/O.
     */
    static final class VisualizationBiomesCmd
            extends io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl {

        private final VisualizationDispatch dispatch;
        private final java.util.function.BiFunction<UUID, Consumer<String>, Boolean> selectorOpener;

        VisualizationBiomesCmd(
                VisualizationDispatch dispatch,
                java.util.function.BiFunction<UUID, Consumer<String>, Boolean> selectorOpener) {
            super(null);
            this.dispatch = java.util.Objects.requireNonNull(dispatch, "dispatch");
            this.selectorOpener = java.util.Objects.requireNonNull(selectorOpener, "selectorOpener");
            addParameter(PARAM_REGION, new CommandParameter(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION,
                    "region name (omit to open the visualizations selector)",
                    (uuid, value) -> value != null && !value.isEmpty()) {
                @Override
                public Set<String> values() {
                    return liveRegionNames();
                }
            });
        }

        @Override
        public String name() {
            return "biomes";
        }

        @Override
        public String permission() {
            return MenuRedeemSubcommand.ADMIN_MENU_PERMISSION;
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
            String regionName = firstValue(parameterValues, PARAM_REGION);
            RTP.log(java.util.logging.Level.FINE,
                    "[viz/biomes] leaf reached: caller=" + callerId
                            + " region=" + regionName
                            + " hasMsg=" + (messageMethod != null));
            if (regionName == null || regionName.isEmpty()) {
                RTP.log(java.util.logging.Level.FINE,
                        "[viz/biomes] no region= -> opening selector");
                Boolean ok = selectorOpener.apply(callerId, messageMethod);
                return Boolean.TRUE.equals(ok);
            }
            boolean result = dispatch.paintBiomes(callerId, regionName, messageMethod);
            RTP.log(java.util.logging.Level.FINE,
                    "[viz/biomes] leaf returning result=" + result);
            return result;
        }

        private static @Nullable String firstValue(@Nullable Map<String, List<String>> values,
                                                   String key) {
            if (values == null) return null;
            List<String> raw = values.get(key);
            if (raw == null || raw.isEmpty()) return null;
            String first = raw.get(0);
            return (first == null || first.isEmpty()) ? null : first;
        }

        private static Set<String> liveRegionNames() {
            try {
                if (RTP.selectionAPI == null) return Collections.emptySet();
                Set<String> names = RTP.selectionAPI.regionNames();
                if (names == null || names.isEmpty()) return Collections.emptySet();
                return new LinkedHashSet<>(names);
            } catch (RuntimeException e) {
                return Collections.emptySet();
            }
        }
    }

    /**
     * {@code /rtp visualization sparkline} - draw the global MSPT + heap
     * sparkline chart ({@link ChartSpec.Kind#METRIC_SPARKLINE}). No
     * parameters; the chart is server-global, with Folia regions
     * aggregated as max-MSPT upstream by {@code MetricsSnapshotRing}.
     * Permission gate is {@code rtp.menu.admin}.
     */
    static final class VisualizationSparklineCmd
            extends io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl {

        private final VisualizationDispatch dispatch;

        VisualizationSparklineCmd(VisualizationDispatch dispatch) {
            super(null);
            this.dispatch = java.util.Objects.requireNonNull(dispatch, "dispatch");
        }

        @Override
        public String name() {
            return "sparkline";
        }

        @Override
        public String permission() {
            return MenuRedeemSubcommand.ADMIN_MENU_PERMISSION;
        }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 @Nullable CommandsAPICommand nextCommand) {
            return dispatch(callerId, null);
        }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 @Nullable CommandsAPICommand nextCommand,
                                 Consumer<String> messageMethod) {
            return dispatch(callerId, messageMethod);
        }

        private boolean dispatch(UUID callerId, @Nullable Consumer<String> messageMethod) {
            RTP.log(java.util.logging.Level.FINE,
                    "[viz/sparkline] leaf reached: caller=" + callerId
                            + " hasMsg=" + (messageMethod != null));
            boolean result = dispatch.paintSparkline(callerId, messageMethod);
            RTP.log(java.util.logging.Level.FINE,
                    "[viz/sparkline] leaf returning result=" + result);
            return result;
        }
    }
}
