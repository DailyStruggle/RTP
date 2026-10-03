package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
     * Generic, parameterized leaf command class (eliminates duplicated inner leaf classes).
     */
    public static class MenuActionLeafCmd extends io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl {
        private final String name;
        private final String permission;
        private final String description;
        private final java.util.function.BiConsumer<UUID, Map<String, List<String>>> action;
        private final java.util.function.BiFunction<UUID, Consumer<String>, Boolean> feedbackAction;

        public MenuActionLeafCmd(@Nullable CommandsAPICommand parent,
                                 String name,
                                 String permission,
                                 String description,
                                 java.util.function.BiConsumer<UUID, Map<String, List<String>>> action) {
            super(parent);
            this.name = java.util.Objects.requireNonNull(name, "name");
            this.permission = java.util.Objects.requireNonNull(permission, "permission");
            this.description = description != null ? description : "";
            this.action = java.util.Objects.requireNonNull(action, "action");
            this.feedbackAction = null;
        }

        public MenuActionLeafCmd(@Nullable CommandsAPICommand parent,
                                 String name,
                                 String permission,
                                 String description,
                                 java.util.function.BiFunction<UUID, Consumer<String>, Boolean> feedbackAction) {
            super(parent);
            this.name = java.util.Objects.requireNonNull(name, "name");
            this.permission = java.util.Objects.requireNonNull(permission, "permission");
            this.description = description != null ? description : "";
            this.action = null;
            this.feedbackAction = java.util.Objects.requireNonNull(feedbackAction, "feedbackAction");
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String permission() {
            return permission;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 @Nullable CommandsAPICommand nextCommand) {
            if (feedbackAction != null) {
                return Boolean.TRUE.equals(feedbackAction.apply(callerId, null));
            }
            if (action != null) {
                action.accept(callerId, parameterValues);
                return true;
            }
            return true;
        }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 @Nullable CommandsAPICommand nextCommand,
                                 Consumer<String> messageMethod) {
            if (feedbackAction != null) {
                return Boolean.TRUE.equals(feedbackAction.apply(callerId, messageMethod));
            }
            if (action != null) {
                action.accept(callerId, parameterValues);
                return true;
            }
            return true;
        }
    }

    /**
     * Factory method to create a parameterized action leaf.
     */
    public static MenuActionLeafCmd createLeaf(
            @Nullable CommandsAPICommand parent,
            String name,
            String permission,
            String description,
            java.util.function.BiConsumer<UUID, Map<String, List<String>>> action) {
        return new MenuActionLeafCmd(parent, name, permission, description, action);
    }

    /**
     * Factory method to create a parameterized action leaf with message feedback support.
     */
    public static MenuActionLeafCmd createLeaf(
            @Nullable CommandsAPICommand parent,
            String name,
            String permission,
            String description,
            java.util.function.BiFunction<UUID, Consumer<String>, Boolean> feedbackAction) {
        return new MenuActionLeafCmd(parent, name, permission, description, feedbackAction);
    }

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
            return MenuCommandUtils.splitDots(MenuCommandUtils.first(parameterValues, PARAM_PATH));
        }
    }

    /**
     * {@code /rtp menu admin} - open the curated admin panel. Routes through
     * {@link MenuRedeemSubcommand#dispatchOpenAdminPanel}; permission gate
     * ({@code rtp.menu.admin}) stays inside the helper.
     */
    static final class OpenAdminPanelConcreteCmd extends MenuActionLeafCmd {
        OpenAdminPanelConcreteCmd(MenuRedeemSubcommand owner) {
            super(owner, "admin", MenuRedeemSubcommand.ADMIN_MENU_PERMISSION, "open the curated admin panel",
                    (java.util.function.BiFunction<UUID, Consumer<String>, Boolean>) (uuid, msg) -> owner.dispatchOpenAdminPanel(uuid, msg));
        }
    }

    /**
     * {@code /rtp menu front} - open the curated front page. No permission
     * gate; the front page is the default landing for any menu viewer.
     */
    static final class OpenFrontPageConcreteCmd extends MenuActionLeafCmd {
        OpenFrontPageConcreteCmd(MenuRedeemSubcommand owner) {
            super(owner, "front", MenuRedeemSubcommand.PERMISSION, "open the curated front page",
                    (java.util.function.BiFunction<UUID, Consumer<String>, Boolean>) (uuid, msg) -> owner.dispatchOpenFrontPage(uuid, msg));
        }
    }

    /**
     * {@code /rtp menu visualizations} - open the visualizations selector.
     * Gates on {@code rtp.menu.admin} (inside
     * {@link MenuRedeemSubcommand#dispatchOpenVisualizations}).
     */
    static final class OpenVisualizationsConcreteCmd extends MenuActionLeafCmd {
        OpenVisualizationsConcreteCmd(MenuRedeemSubcommand owner) {
            super(owner, "visualizations", MenuRedeemSubcommand.ADMIN_MENU_PERMISSION, "open the visualizations selector",
                    (java.util.function.BiFunction<UUID, Consumer<String>, Boolean>) (uuid, msg) -> owner.dispatchOpenVisualizations(uuid, msg));
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
            // Selection heatmap (ADR-089)
            addSubCommand(new VisualizationHeatmapCmd(
                    dispatch,
                    (uuid, msg) -> owner.dispatchOpenVisualizationRegions(
                            uuid,
                            io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.SELECTION_HEATMAP,
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
     * Common base command for region-scoped visualization leaves (bad-locations, biomes, pipeline, heatmap).
     * Eliminates duplicate parameter registration, selector fallback, logging, and dispatching.
     */
    static abstract class AbstractVisualizationRegionCmd
            extends io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl {

        protected final VisualizationDispatch dispatch;
        protected final java.util.function.BiFunction<UUID, Consumer<String>, Boolean> selectorOpener;
        private final String name;

        AbstractVisualizationRegionCmd(
                VisualizationDispatch dispatch,
                java.util.function.BiFunction<UUID, Consumer<String>, Boolean> selectorOpener,
                String name) {
            super(null);
            this.dispatch = java.util.Objects.requireNonNull(dispatch, "dispatch");
            this.selectorOpener = java.util.Objects.requireNonNull(selectorOpener, "selectorOpener");
            this.name = java.util.Objects.requireNonNull(name, "name");
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
            return name;
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

        protected abstract boolean paint(UUID callerId, String regionName, @Nullable Consumer<String> messageMethod);

        private boolean dispatch(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 @Nullable Consumer<String> messageMethod) {
            String regionName = firstValue(parameterValues, PARAM_REGION);
            RTP.log(java.util.logging.Level.FINE,
                    "[viz/" + name + "] leaf reached: caller=" + callerId
                            + " region=" + regionName
                            + " hasMsg=" + (messageMethod != null));
            if (regionName == null || regionName.isEmpty()) {
                RTP.log(java.util.logging.Level.FINE,
                        "[viz/" + name + "] no region= -> opening selector");
                Boolean ok = selectorOpener.apply(callerId, messageMethod);
                return Boolean.TRUE.equals(ok);
            }
            boolean result = paint(callerId, regionName, messageMethod);
            RTP.log(java.util.logging.Level.FINE,
                    "[viz/" + name + "] leaf returning result=" + result);
            return result;
        }

        static @Nullable String firstValue(@Nullable Map<String, List<String>> values,
                                           String key) {
            if (values == null) return null;
            List<String> raw = values.get(key);
            if (raw == null || raw.isEmpty()) return null;
            String first = raw.get(0);
            return (first == null || first.isEmpty()) ? null : first;
        }

        static Set<String> liveRegionNames() {
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
     * {@code /rtp visualization bad-locations [region=<regionName>]} command (ADR-050).
     * Draws {@link ChartSpec.Kind#REGION_BAD_LOCATIONS_SHAPE}; falls back to region picker if omitted.
     * Gates on {@code rtp.menu.admin}.
     */
    static final class VisualizationBadLocationsCmd extends AbstractVisualizationRegionCmd {
        VisualizationBadLocationsCmd(
                VisualizationDispatch dispatch,
                java.util.function.BiFunction<UUID, Consumer<String>, Boolean> selectorOpener) {
            super(dispatch, selectorOpener, "bad-locations");
        }

        @Override
        protected boolean paint(UUID callerId, String regionName, @Nullable Consumer<String> messageMethod) {
            return dispatch.paintBadLocations(callerId, regionName, messageMethod);
        }
    }

    /**
     * {@code /rtp visualization biomes [region=<regionName>]} command.
     * Draws {@link ChartSpec.Kind#REGION_BIOMES} using cached biome data without chunk I/O (S-005).
     */
    static final class VisualizationBiomesCmd extends AbstractVisualizationRegionCmd {
        VisualizationBiomesCmd(
                VisualizationDispatch dispatch,
                java.util.function.BiFunction<UUID, Consumer<String>, Boolean> selectorOpener) {
            super(dispatch, selectorOpener, "biomes");
        }

        @Override
        protected boolean paint(UUID callerId, String regionName, @Nullable Consumer<String> messageMethod) {
            return dispatch.paintBiomes(callerId, regionName, messageMethod);
        }
    }

    /**
     * {@code /rtp visualization pipeline [region=<regionName>]} command (ADR-089).
     * Draws composite map (desaturated biomes, red hazard wash, queue markers, and L1/L2/L3 health bars).
     */
    static final class VisualizationPipelineCmd extends AbstractVisualizationRegionCmd {
        VisualizationPipelineCmd(
                VisualizationDispatch dispatch,
                java.util.function.BiFunction<UUID, Consumer<String>, Boolean> selectorOpener) {
            super(dispatch, selectorOpener, "pipeline");
        }

        @Override
        protected boolean paint(UUID callerId, String regionName, @Nullable Consumer<String> messageMethod) {
            return dispatch.paintPipeline(callerId, regionName, messageMethod);
        }
    }

    /**
     * {@code /rtp visualization heatmap [region=<regionName>]} command.
     * Draws {@link ChartSpec.Kind#SELECTION_HEATMAP}; falls back to region picker if omitted.
     * Gates on {@code rtp.menu.admin}.
     */
    static final class VisualizationHeatmapCmd extends AbstractVisualizationRegionCmd {
        VisualizationHeatmapCmd(
                VisualizationDispatch dispatch,
                java.util.function.BiFunction<UUID, Consumer<String>, Boolean> selectorOpener) {
            super(dispatch, selectorOpener, "heatmap");
        }

        @Override
        protected boolean paint(UUID callerId, String regionName, @Nullable Consumer<String> messageMethod) {
            return dispatch.paintHeatmap(callerId, regionName, messageMethod);
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

        private static final String PARAM_SIZE = "size";
        private static final String PARAM_WIDTH = "width";
        private static final String PARAM_HEIGHT = "height";
        private static final String PARAM_ZOOM = "zoom";
        private static final int DEFAULT_SIZE = 512;

        VisualizationExportCmd() {
            super(null);
            addSubCommand(new ExportAllCmd());
            addSubCommand(new ExportTypeCmd("pipeline", io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.REGION_COMPOSITE));
            addSubCommand(new ExportTypeCmd("biomes", io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.REGION_BIOMES));
            addSubCommand(new ExportTypeCmd("bad-locations", io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE));
            addSubCommand(new ExportTypeCmd("sparkline", io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.METRIC_SPARKLINE));
            addSubCommand(new ExportTypeCmd("walk", io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.REGION_WALK_PATH));
            addSubCommand(new ExportTypeCmd("walk-path", io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.REGION_WALK_PATH));
            addSubCommand(new ExportTypeCmd("heatmap", io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.SELECTION_HEATMAP));
            addSubCommand(new ExportTypeCmd("selection-heatmap", io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.SELECTION_HEATMAP));
            addSubCommand(new ExportComprehensiveCmd());
        }

        private static int parsePixelDimension(String raw, int defaultValue) {
            if (raw == null || raw.isBlank()) return defaultValue;
            String s = raw.trim().toLowerCase(Locale.ROOT);
            try {
                // Metric and unit transformations:
                // Suffixes:
                // k / kilo / kilos / kp / kpx / kpix / kpixels -> 1,000 (kilo-pixels)
                // m / mega / mp / mpx / mpix / mpixels -> 1,000,000 (mega-pixels)
                // g / giga / gp / gpx / gpix / gpixels -> 1,000,000,000 (giga-pixels)
                // kib / mib / gib -> binary 1024 / 1048576 / 1073741824
                // px / pix / pixels -> 1
                if (s.endsWith("giga") || s.endsWith("gp") || s.endsWith("gpx") || s.endsWith("gpix") || s.endsWith("gpixels") || (s.endsWith("g") && !s.endsWith("deg"))) {
                    String numPart = s.replaceAll("[^0-9.]", "").trim();
                    double num = numPart.isEmpty() ? 1.0 : Double.parseDouble(numPart);
                    return (int) Math.round(num * 1_000_000_000.0);
                } else if (s.endsWith("mega") || s.endsWith("mp") || s.endsWith("mpx") || s.endsWith("mpix") || s.endsWith("mpixels") || s.endsWith("m")) {
                    String numPart = s.replaceAll("[^0-9.]", "").trim();
                    double num = numPart.isEmpty() ? 1.0 : Double.parseDouble(numPart);
                    return (int) Math.round(num * 1_000_000.0);
                } else if (s.endsWith("kilo") || s.endsWith("kilos") || s.endsWith("kp") || s.endsWith("kpx") || s.endsWith("kpix") || s.endsWith("kpixels") || s.endsWith("k")) {
                    String numPart = s.replaceAll("[^0-9.]", "").trim();
                    double num = numPart.isEmpty() ? 1.0 : Double.parseDouble(numPart);
                    return (int) Math.round(num * 1000.0);
                } else if (s.endsWith("kib")) {
                    String numPart = s.replaceAll("[^0-9.]", "").trim();
                    double num = numPart.isEmpty() ? 1.0 : Double.parseDouble(numPart);
                    return (int) Math.round(num * 1024.0);
                } else if (s.endsWith("mib")) {
                    String numPart = s.replaceAll("[^0-9.]", "").trim();
                    double num = numPart.isEmpty() ? 1.0 : Double.parseDouble(numPart);
                    return (int) Math.round(num * 1024.0 * 1024.0);
                } else if (s.endsWith("px") || s.endsWith("pix") || s.endsWith("pixels")) {
                    String numPart = s.replaceAll("[^0-9.]", "").trim();
                    double num = numPart.isEmpty() ? 1.0 : Double.parseDouble(numPart);
                    return (int) Math.round(num);
                } else {
                    String numPart = s.replaceAll("[^0-9.-]", "").trim();
                    if (numPart.isEmpty()) return defaultValue;
                    return (int) Math.round(Double.parseDouble(numPart));
                }
            } catch (Exception e) {
                return defaultValue;
            }
        }

        private static Integer[] parseDimensions(Map<String, List<String>> parameterValues) {
            Integer w = null;
            Integer h = null;
            if (parameterValues != null) {
                List<String> sizeVals = parameterValues.get(PARAM_SIZE);
                if (sizeVals != null && !sizeVals.isEmpty()) {
                    String sizeStr = sizeVals.get(0).trim().toLowerCase(Locale.ROOT);
                    if (sizeStr.contains("x")) {
                        String[] parts = sizeStr.split("x", 2);
                        w = parsePixelDimension(parts[0], DEFAULT_SIZE);
                        h = parsePixelDimension(parts[1], w);
                    } else {
                        w = parsePixelDimension(sizeStr, DEFAULT_SIZE);
                    }
                }
                List<String> widthVals = parameterValues.get(PARAM_WIDTH);
                if (widthVals != null && !widthVals.isEmpty()) {
                    w = parsePixelDimension(widthVals.get(0), w != null ? w : DEFAULT_SIZE);
                }
                List<String> heightVals = parameterValues.get(PARAM_HEIGHT);
                if (heightVals != null && !heightVals.isEmpty()) {
                    h = parsePixelDimension(heightVals.get(0), h != null ? h : DEFAULT_SIZE);
                }
            }
            if (w != null) w = Math.max(16, w);
            if (h != null) h = Math.max(16, h);
            return new Integer[]{w, h};
        }

        private static int[] resolveCanvasDimensions(
                @Nullable io.github.dailystruggle.rtp.common.selection.region.Region region,
                Integer customWidth,
                Integer customHeight,
                boolean isSpatial) {
            if (customWidth != null && customHeight != null) {
                return new int[]{customWidth, customHeight};
            }
            if (!isSpatial || region == null || !(region.shape instanceof io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape<?> memoryShape)) {
                int def = 512;
                int w = customWidth != null ? customWidth : def;
                int h = customHeight != null ? customHeight : (customWidth != null ? customWidth : (isSpatial ? def : 256));
                return new int[]{w, h};
            }

            long range = memoryShape.getRange();
            long boundW = 1L;
            long boundH = 1L;
            if (range > 0) {
                int sampleCount = 1024;
                long step = Math.max(1L, range / sampleCount);
                int minX = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE;
                int minZ = Integer.MAX_VALUE;
                int maxZ = Integer.MIN_VALUE;
                int samples = 0;
                for (long i = 0L; i < range; i += step) {
                    int[] xz = memoryShape.locationToXZ(i);
                    if (xz == null || xz.length < 2) continue;
                    if (xz[0] < minX) minX = xz[0];
                    if (xz[0] > maxX) maxX = xz[0];
                    if (xz[1] < minZ) minZ = xz[1];
                    if (xz[1] > maxZ) maxZ = xz[1];
                    samples++;
                }
                if (samples > 0) {
                    int extentX = maxX - minX;
                    int extentZ = maxZ - minZ;
                    int pad = Math.max(16, Math.max(extentX, extentZ) / 20);
                    boundW = Math.max(1L, (long) (maxX + pad) - (minX - pad));
                    boundH = Math.max(1L, (long) (maxZ + pad) - (minZ - pad));
                }
            }

            if (customWidth != null) {
                int w = Math.max(16, customWidth);
                int h = (int) Math.max(16L, Math.round((double) w * boundH / boundW));
                return new int[]{w, h};
            }
            if (customHeight != null) {
                int h = Math.max(16, customHeight);
                int w = (int) Math.max(16L, Math.round((double) h * boundW / boundH));
                return new int[]{w, h};
            }

            // Auto sizing: cap largest side at 4096 while matching region proportions boundW : boundH
            long diameterBlocks = Math.max(boundW, boundH);
            long diameterChunks = Math.max(1L, diameterBlocks / 16L);
            long largestSide = Math.max(512L, Math.min(4096L, 2L * diameterChunks));
            if (boundW >= boundH) {
                int w = (int) largestSide;
                int h = (int) Math.max(16L, Math.round((double) largestSide * boundH / boundW));
                return new int[]{w, h};
            } else {
                int h = (int) largestSide;
                int w = (int) Math.max(16L, Math.round((double) largestSide * boundW / boundH));
                return new int[]{w, h};
            }
        }

        private static double parseZoom(Map<String, List<String>> parameterValues) {
            if (parameterValues != null) {
                List<String> zoomVals = parameterValues.get(PARAM_ZOOM);
                if (zoomVals != null && !zoomVals.isEmpty()) {
                    try {
                        double z = Double.parseDouble(zoomVals.get(0).trim());
                        if (z > 0.0) return z;
                    } catch (Exception ignored) {
                    }
                }
            }
            return 1.0;
        }

        private static void addSizingParameters(io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl cmd) {
            cmd.addParameter(PARAM_SIZE, new CommandParameter(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION,
                    "image size in pixels or resolution (e.g. 512, 1024, 4k, 8k, k, kpx, m, mpx, 1920x1080; metric units supported)",
                    (uuid, value) -> value != null && !value.isEmpty()) {
                @Override
                public Set<String> values() {
                    return new HashSet<>(Arrays.asList("512", "1024", "2048", "4k", "8k", "16k", "k", "kpx", "m", "mpx"));
                }
            });
            cmd.addParameter(PARAM_WIDTH, new CommandParameter(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION,
                    "image width in pixels (e.g. 512, 1024, 4k, 8k, k, kpx; metric units supported)",
                    (uuid, value) -> value != null && !value.isEmpty()) {
                @Override
                public Set<String> values() {
                    return new HashSet<>(Arrays.asList("512", "1024", "2048", "4096", "8000", "8192", "k", "kpx"));
                }
            });
            cmd.addParameter(PARAM_HEIGHT, new CommandParameter(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION,
                    "image height in pixels (e.g. 512, 1024, 4k, 8k, k, kpx; metric units supported)",
                    (uuid, value) -> value != null && !value.isEmpty()) {
                @Override
                public Set<String> values() {
                    return new HashSet<>(Arrays.asList("512", "1024", "2048", "4096", "8000", "8192", "k", "kpx"));
                }
            });
            cmd.addParameter(PARAM_ZOOM, new CommandParameter(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION,
                    "zoom factor (e.g. 1.0, 2.0, 0.5)",
                    (uuid, value) -> value != null && !value.isEmpty()) {
                @Override
                public Set<String> values() {
                    return new HashSet<>(Arrays.asList("1.0", "1.5", "2.0", "4.0"));
                }
            });
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
            // A concrete sub-command (all|pipeline|biomes|...) follows: this
            // parent leaf is invoked purely for "independent functionality"
            // by the tree dispatcher (see TreeCommand line ~247). Emitting the
            // usage banner here would spuriously print alongside a valid
            // `export all` run, so only show usage when invoked as a true leaf.
            if (nextCommand == null && messageMethod != null) {
                messageMethod.accept("Usage: /rtp visualization export "
                        + "<all | pipeline | biomes | bad-locations | sparkline | walk | walk-path | comprehensive> [region=<region>] [size=<pixels>] [zoom=<factor>]");
            }
            return true;
        }

        static final class ExportComprehensiveCmd
                extends io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl {

            ExportComprehensiveCmd() {
                super(null);
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
                addSizingParameters(this);
            }

            @Override
            public String name() {
                return "comprehensive";
            }

            @Override
            public String permission() {
                return MenuRedeemSubcommand.ADMIN_MENU_PERMISSION;
            }

            @Override
            public boolean onCommand(UUID callerId,
                                     Map<String, List<String>> parameterValues,
                                     @Nullable CommandsAPICommand nextCommand) {
                return executeComprehensive(callerId, parameterValues, null);
            }

            @Override
            public boolean onCommand(UUID callerId,
                                     Map<String, List<String>> parameterValues,
                                     @Nullable CommandsAPICommand nextCommand,
                                     Consumer<String> messageMethod) {
                return executeComprehensive(callerId, parameterValues, messageMethod);
            }

            private boolean executeComprehensive(UUID callerId,
                                                 Map<String, List<String>> parameterValues,
                                                 @Nullable Consumer<String> messageMethod) {
                List<String> regVals = parameterValues != null ? parameterValues.get(PARAM_REGION) : null;
                String regionName = (regVals != null && !regVals.isEmpty()) ? regVals.get(0) : "default";
                Integer[] dims = parseDimensions(parameterValues);
                double zoom = parseZoom(parameterValues);

                if (RTP.scheduler == null) return false;
                RTP.scheduler.runTaskAsynchronously(() -> {
                    try {
                        if (RTP.selectionAPI == null) {
                            sendMsg(callerId, "[RTP] Selection API not initialized", messageMethod);
                            return;
                        }
                        io.github.dailystruggle.rtp.common.selection.region.Region region;
                        try {
                            region = RTP.selectionAPI.getRegionOrDefault(regionName);
                        } catch (Exception e) {
                            sendMsg(callerId, "[RTP] Region not found: " + regionName, messageMethod);
                            return;
                        }
                        if (region == null) {
                            sendMsg(callerId, "[RTP] Region not found: " + regionName, messageMethod);
                            return;
                        }

                        java.io.File outDir = new java.io.File("plugins/RTP/charts");
                        io.github.dailystruggle.rtp.common.visualization.ComprehensiveRegionImageExporter.ExportResult result =
                                io.github.dailystruggle.rtp.common.visualization.ComprehensiveRegionImageExporter.exportAll(
                                        region, outDir, dims[0], dims[1], zoom);

                        String msg = String.format(java.util.Locale.ROOT,
                                "[RTP] Exported comprehensive diagnostic to %s (%dms, NN mean: %.1fm, bad chunks: %,d)",
                                result.imageFile().getPath(), result.executionTimeMs(), result.nnMean(), result.badChunksCount());
                        sendMsg(callerId, msg, messageMethod);
                    } catch (Exception e) {
                        RTP.log(java.util.logging.Level.WARNING, "Export comprehensive failed for " + regionName + ": " + e.getMessage(), e);
                        sendMsg(callerId, "[RTP] Export comprehensive failed: " + e.getMessage(), messageMethod);
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

        static final class ExportAllCmd
                extends io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl {

            ExportAllCmd() {
                super(null);
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
                addSizingParameters(this);
            }

            @Override
            public String name() {
                return "all";
            }

            @Override
            public String permission() {
                return MenuRedeemSubcommand.ADMIN_MENU_PERMISSION;
            }

            @Override
            public boolean onCommand(UUID callerId,
                                     Map<String, List<String>> parameterValues,
                                     @Nullable CommandsAPICommand nextCommand) {
                return executeExportAll(callerId, parameterValues, null);
            }

            @Override
            public boolean onCommand(UUID callerId,
                                     Map<String, List<String>> parameterValues,
                                     @Nullable CommandsAPICommand nextCommand,
                                     Consumer<String> messageMethod) {
                return executeExportAll(callerId, parameterValues, messageMethod);
            }

            private boolean executeExportAll(UUID callerId,
                                             Map<String, List<String>> parameterValues,
                                             @Nullable Consumer<String> messageMethod) {
                List<String> regVals = parameterValues != null ? parameterValues.get(PARAM_REGION) : null;
                String regionName = (regVals != null && !regVals.isEmpty()) ? regVals.get(0) : "default";
                Integer[] dims = parseDimensions(parameterValues);
                double zoom = parseZoom(parameterValues);

                if (RTP.scheduler == null) return false;
                RTP.scheduler.runTaskAsynchronously(() -> {
                    long t0 = System.currentTimeMillis();
                    try {
                        if (RTP.selectionAPI == null) {
                            sendMsg(callerId, "[RTP] Selection API not initialized", messageMethod);
                            return;
                        }
                        io.github.dailystruggle.rtp.common.selection.region.Region region;
                        try {
                            region = RTP.selectionAPI.getRegionOrDefault(regionName);
                        } catch (Exception e) {
                            sendMsg(callerId, "[RTP] Region not found: " + regionName, messageMethod);
                            return;
                        }
                        if (region == null) {
                            sendMsg(callerId, "[RTP] Region not found: " + regionName, messageMethod);
                            return;
                        }

                        java.io.File outDir = new java.io.File("plugins/RTP/charts");
                        outDir.mkdirs();

                        List<String> exportedFiles = new java.util.ArrayList<>();

                        // 1. Export each individual visualization type as its own image
                        Map<String, io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind> vizTypes = new java.util.LinkedHashMap<>();
                        vizTypes.put("pipeline", io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.REGION_COMPOSITE);
                        vizTypes.put("biomes", io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.REGION_BIOMES);
                        vizTypes.put("bad-locations", io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE);
                        vizTypes.put("sparkline", io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.METRIC_SPARKLINE);
                        vizTypes.put("walk-path", io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.REGION_WALK_PATH);
                        vizTypes.put("heatmap", io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.SELECTION_HEATMAP);

                        for (Map.Entry<String, io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind> entry : vizTypes.entrySet()) {
                            String typeName = entry.getKey();
                            io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind kind = entry.getValue();
                            try {
                                io.github.dailystruggle.rtp.api.maps.ChartSpec spec =
                                        io.github.dailystruggle.rtp.api.maps.ChartSpec.of(kind, regionName);
                                io.github.dailystruggle.rtp.common.commands.maps.ChartSpecResolver resolver =
                                        io.github.dailystruggle.rtp.common.commands.maps.ChartSpecResolvers.get(kind);
                                if (resolver != null) {
                                    io.github.dailystruggle.rtp.common.commands.maps.ChartSpecResolver.Resolution resolution =
                                            resolver.resolve(spec);
                                    if (resolution != null) {
                                        boolean isSpatial = (kind != io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.METRIC_SPARKLINE);
                                        int[] canvasDims = resolveCanvasDimensions(region, dims[0], dims[1], isSpatial);
                                        io.github.dailystruggle.mapsapi.image.ImageMapCanvas canvas =
                                                new io.github.dailystruggle.mapsapi.image.ImageMapCanvas(canvasDims[0], canvasDims[1]);
                                        @SuppressWarnings("unchecked")
                                        io.github.dailystruggle.mapsapi.render.ChartRenderer<io.github.dailystruggle.mapsapi.model.ChartModel> renderer =
                                                (io.github.dailystruggle.mapsapi.render.ChartRenderer<io.github.dailystruggle.mapsapi.model.ChartModel>) resolution.renderer();
                                        renderer.render(canvas, resolution.model());
                                        canvas.commit();

                                        String fileName = regionName + "_" + typeName + ".png";
                                        java.io.File target = new java.io.File(outDir, fileName);
                                        java.awt.image.BufferedImage framed =
                                                io.github.dailystruggle.rtp.common.visualization.FramedVisualizationExporter.frame(
                                                        canvas, kind, typeName, region);
                                        javax.imageio.ImageIO.write(framed, "png", target);
                                        exportedFiles.add(target.getPath());
                                    }
                                }
                            } catch (Exception e) {
                                RTP.log(java.util.logging.Level.FINE, "Failed individual visualization in export all (" + typeName + "): " + e.getMessage(), e);
                            }
                        }

                        // 2. Export comprehensive diagnostic image and JSON
                        try {
                            io.github.dailystruggle.rtp.common.visualization.ComprehensiveRegionImageExporter.ExportResult result =
                                    io.github.dailystruggle.rtp.common.visualization.ComprehensiveRegionImageExporter.exportAll(
                                            region, outDir, dims[0], dims[1], zoom);
                            exportedFiles.add(result.imageFile().getPath());
                        } catch (Exception e) {
                            RTP.log(java.util.logging.Level.FINE, "Failed comprehensive visualization in export all: " + e.getMessage(), e);
                        }

                        long elapsed = System.currentTimeMillis() - t0;
                        sendMsg(callerId, String.format(java.util.Locale.ROOT,
                                "[RTP] Exported %d visualization images for %s to %s (%dms)",
                                exportedFiles.size(), regionName, outDir.getPath(), elapsed), messageMethod);
                    } catch (Exception e) {
                        RTP.log(java.util.logging.Level.WARNING, "Export all failed for " + regionName + ": " + e.getMessage(), e);
                        sendMsg(callerId, "[RTP] Export all failed: " + e.getMessage(), messageMethod);
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
                addSizingParameters(this);
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
                Integer[] dims = parseDimensions(parameterValues);

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

                        io.github.dailystruggle.rtp.common.selection.region.Region region = null;
                        if (RTP.selectionAPI != null) {
                            try {
                                region = RTP.selectionAPI.getRegionOrDefault(regionName);
                            } catch (Exception ignored) {
                            }
                        }

                        boolean isSpatial = (kind != io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.METRIC_SPARKLINE);
                        int[] canvasDims = resolveCanvasDimensions(region, dims[0], dims[1], isSpatial);

                        io.github.dailystruggle.mapsapi.image.ImageMapCanvas canvas =
                                new io.github.dailystruggle.mapsapi.image.ImageMapCanvas(canvasDims[0], canvasDims[1]);
                        @SuppressWarnings("unchecked")
                        io.github.dailystruggle.mapsapi.render.ChartRenderer<io.github.dailystruggle.mapsapi.model.ChartModel> renderer =
                                (io.github.dailystruggle.mapsapi.render.ChartRenderer<io.github.dailystruggle.mapsapi.model.ChartModel>) resolution.renderer();
                        renderer.render(canvas, resolution.model());
                        canvas.commit();

                        java.io.File outDir = new java.io.File("plugins/RTP/charts");
                        outDir.mkdirs();
                        String fileName = regionName + "_" + typeName + ".png";
                        java.io.File target = new java.io.File(outDir, fileName);
                        java.awt.image.BufferedImage framed =
                                io.github.dailystruggle.rtp.common.visualization.FramedVisualizationExporter.frame(
                                        canvas, kind, typeName, region);
                        javax.imageio.ImageIO.write(framed, "png", target);

                        long elapsed = System.currentTimeMillis() - t0;
                        sendMsg(callerId, "[RTP] Exported " + typeName + " chart to " + target.getPath() + " (" + elapsed + "ms, " + framed.getWidth() + "x" + framed.getHeight() + ")", messageMethod);
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
