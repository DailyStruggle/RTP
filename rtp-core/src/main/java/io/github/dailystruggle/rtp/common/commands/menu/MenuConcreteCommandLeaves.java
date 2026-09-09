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
 * Concrete {@code /rtp menu ...} and {@code /rtp visualization} subcommand leaves (ADR-050).
 * Replaces token indirection with typed commands that route into {@link MenuRedeemSubcommand}.
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
     * {@code /rtp visualization} root command opening the visualizations selector.
     * Hosts per-kind sub-commands; bare invocation opens the selector (gates on {@code rtp.menu.admin}).
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
            // Pipeline composite map (ADR-089)
            addSubCommand(new VisualizationPipelineCmd(
                    dispatch,
                    (uuid, msg) -> owner.dispatchOpenVisualizationRegions(
                            uuid,
                            io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.REGION_COMPOSITE,
                            msg)));
            // Sparkline is a global chart (no region), so it has no
            // kind-scoped region picker fallback - the leaf paints
            // directly when invoked with no parameters.
            addSubCommand(new VisualizationSparklineCmd(dispatch));
            // Export image subcommand (ADR-089)
            addSubCommand(new VisualizationExportCmd());
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
     * {@code /rtp visualization bad-locations [region=<regionName>]} command (ADR-050).
     * Draws {@link ChartSpec.Kind#REGION_BAD_LOCATIONS_SHAPE}; falls back to region picker if omitted.
     * Gates on {@code rtp.menu.admin}.
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
     * {@code /rtp visualization biomes [region=<regionName>]} command.
     * Draws {@link ChartSpec.Kind#REGION_BIOMES} using cached biome data without chunk I/O (S-005).
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
     * {@code /rtp visualization pipeline [region=<regionName>]} command (ADR-089).
     * Draws composite map (desaturated biomes, red hazard wash, queue markers, and L1/L2/L3 health bars).
     */
    static final class VisualizationPipelineCmd
            extends io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl {

        private final VisualizationDispatch dispatch;
        private final java.util.function.BiFunction<UUID, Consumer<String>, Boolean> selectorOpener;

        VisualizationPipelineCmd(
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
            return "pipeline";
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
                    "[viz/pipeline] leaf reached: caller=" + callerId
                            + " region=" + regionName
                            + " hasMsg=" + (messageMethod != null));
            if (regionName == null || regionName.isEmpty()) {
                RTP.log(java.util.logging.Level.FINE,
                        "[viz/pipeline] no region= -> opening selector");
                Boolean ok = selectorOpener.apply(callerId, messageMethod);
                return Boolean.TRUE.equals(ok);
            }
            boolean result = dispatch.paintPipeline(callerId, regionName, messageMethod);
            RTP.log(java.util.logging.Level.FINE,
                    "[viz/pipeline] leaf returning result=" + result);
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

    /**
     * {@code /rtp visualization export <type> [region=<name>] [format=png|bmp]} command (ADR-089).
     * Renders and exports high-resolution image files to {@code plugins/RTP/charts/}.
     */
    static final class VisualizationExportCmd
            extends io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl {

        VisualizationExportCmd() {
            super(null);
            addSubCommand(new ExportTypeCmd("pipeline", io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.REGION_COMPOSITE));
            addSubCommand(new ExportTypeCmd("biomes", io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.REGION_BIOMES));
            addSubCommand(new ExportTypeCmd("bad-locations", io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE));
            addSubCommand(new ExportTypeCmd("sparkline", io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.METRIC_SPARKLINE));
        }

        @Override
        public String name() {
            return "export";
        }

        @Override
        public String permission() {
            return MenuRedeemSubcommand.ADMIN_MENU_PERMISSION;
        }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 @Nullable CommandsAPICommand nextCommand) {
            return true;
        }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 @Nullable CommandsAPICommand nextCommand,
                                 Consumer<String> messageMethod) {
            if (messageMethod != null) {
                messageMethod.accept("Usage: /rtp visualization export <pipeline|biomes|bad-locations|sparkline> [region=<region>]");
            }
            return true;
        }

        static final class ExportTypeCmd
                extends io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl {

            private final String typeName;
            private final io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind kind;

            ExportTypeCmd(String typeName, io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind kind) {
                super(null);
                this.typeName = typeName;
                this.kind = kind;
                addParameter(PARAM_REGION, new CommandParameter(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION,
                        "region name",
                        (uuid, value) -> value != null && !value.isEmpty()) {
                    @Override
                    public Set<String> values() {
                        try {
                            return RTP.selectionAPI != null ? RTP.selectionAPI.regionNames() : Collections.emptySet();
                        } catch (Exception e) {
                            return Collections.emptySet();
                        }
                    }
                });
            }

            @Override
            public String name() {
                return typeName;
            }

            @Override
            public String permission() {
                return MenuRedeemSubcommand.ADMIN_MENU_PERMISSION;
            }

            @Override
            public boolean onCommand(UUID callerId,
                                     Map<String, List<String>> parameterValues,
                                     @Nullable CommandsAPICommand nextCommand) {
                return executeExport(callerId, parameterValues, null);
            }

            @Override
            public boolean onCommand(UUID callerId,
                                     Map<String, List<String>> parameterValues,
                                     @Nullable CommandsAPICommand nextCommand,
                                     Consumer<String> messageMethod) {
                return executeExport(callerId, parameterValues, messageMethod);
            }

            private boolean executeExport(UUID callerId,
                                          Map<String, List<String>> parameterValues,
                                          @Nullable Consumer<String> messageMethod) {
                List<String> regVals = parameterValues != null ? parameterValues.get(PARAM_REGION) : null;
                String regionName = (regVals != null && !regVals.isEmpty()) ? regVals.get(0) : "default";

                if (RTP.scheduler == null) return false;
                RTP.scheduler.runTaskAsynchronously(() -> {
                    long t0 = System.currentTimeMillis();
                    try {
                        io.github.dailystruggle.rtp.api.maps.ChartSpec spec =
                                io.github.dailystruggle.rtp.api.maps.ChartSpec.of(kind, regionName);
                        io.github.dailystruggle.rtp.common.commands.maps.ChartSpecResolver resolver =
                                io.github.dailystruggle.rtp.common.commands.maps.ChartSpecResolvers.get(kind);
                        if (resolver == null) {
                            sendMsg(callerId, "No resolver registered for " + kind, messageMethod);
                            return;
                        }

                        io.github.dailystruggle.rtp.common.commands.maps.ChartSpecResolver.Resolution resolution =
                                resolver.resolve(spec);
                        if (resolution == null) {
                            sendMsg(callerId, "Failed to resolve chart data for " + regionName, messageMethod);
                            return;
                        }

                        io.github.dailystruggle.mapsapi.image.ImageMapCanvas canvas =
                                new io.github.dailystruggle.mapsapi.image.ImageMapCanvas(512, 512);
                        @SuppressWarnings("unchecked")
                        io.github.dailystruggle.mapsapi.render.ChartRenderer<io.github.dailystruggle.mapsapi.model.ChartModel> renderer =
                                (io.github.dailystruggle.mapsapi.render.ChartRenderer<io.github.dailystruggle.mapsapi.model.ChartModel>) resolution.renderer();
                        renderer.render(canvas, resolution.model());
                        canvas.commit();

                        java.io.File outDir = new java.io.File("plugins/RTP/charts");
                        outDir.mkdirs();
                        String fileName = regionName + "_" + typeName + ".png";
                        java.io.File target = new java.io.File(outDir, fileName);
                        canvas.writeToFile(target, "png");

                        long elapsed = System.currentTimeMillis() - t0;
                        sendMsg(callerId, "[RTP] Exported " + typeName + " chart to " + target.getPath() + " (" + elapsed + "ms)", messageMethod);
                    } catch (Exception e) {
                        RTP.log(java.util.logging.Level.WARNING, "Export failed for " + typeName + ": " + e.getMessage(), e);
                        sendMsg(callerId, "[RTP] Export failed: " + e.getMessage(), messageMethod);
                    }
                });
                return true;
            }

            private void sendMsg(UUID callerId, String msg, @Nullable Consumer<String> messageMethod) {
                if (messageMethod != null) {
                    messageMethod.accept(msg);
                } else if (RTP.serverAccessor != null) {
                    RTP.serverAccessor.sendMessage(io.github.dailystruggle.rtp.api.RTPAPI.serverId, callerId, msg, null);
                }
            }
        }
    }
}
