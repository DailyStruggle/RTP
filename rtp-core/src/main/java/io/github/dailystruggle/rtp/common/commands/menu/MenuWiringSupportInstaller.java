package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuConsumerProfile;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.admin.AdminCmd;
import io.github.dailystruggle.rtp.common.commands.menu.multiconfig.DefaultMultiConfigRemovalGuards;
import io.github.dailystruggle.rtp.common.commands.menu.multiconfig.MultiConfigMenuBuilder;
import io.github.dailystruggle.rtp.common.commands.prefab.PrefabCommand;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.menu.search.ConfigSearchResultsBuilder;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Package-private worker that holds the lifted L210-731 menu-wiring
 * block from the pre-2026-05-24 {@code RTPCmdBukkit} constructor.
 * Split out of {@link MenuWiringSupport} so the public facade stays small
 * and the implementation can carry per-call mutable locals (token
 * registry, builders, etc.) as instance state.
 *
 * <p>Behaviour is intended to be byte-identical to the pre-lift code.
 * Any deliberate behavioural change must update both this class and the
 * relevant tests.
 */
final class MenuWiringSupportInstaller {

    private final TreeCommand rtpRoot;
    private final Function<UUID, Predicate<String>> menuPermissionProbe;
    private final @Nullable MenuRenderer menuRenderer;
    private final @Nullable MenuRedeemSubcommand.AnvilInputOpener anvilOpener;

    MenuWiringSupportInstaller(TreeCommand rtpRoot, MenuPlatformBindings bindings) {
        this.rtpRoot = rtpRoot;
        this.menuPermissionProbe = bindings.permissionProbe();
        this.menuRenderer = bindings.renderer();
        this.anvilOpener = bindings.anvilOpener();
    }

    void install() {
        // /rtp menu — generalized menu subcommand (ADR-035 / ADR-044).
        // The page builder reflects the live /rtp tree via CommandTreeMenuBuilder
        // (filtered by the viewer's permissions, so inaccessible rows are hidden,
        // not greyed). If no renderer can be constructed the subcommand stays
        // registered and rejects with the configurable `menuInvalid` message so
        // /rtp menu is at least *recognised* on every platform
        // (REQ-RTP-S-007 / F-013).
        //
        // ADR-050 Stage 3β.D.2b (2026-05-24): builders are no-arg now; the
        // renderer emits concrete /rtp menu ... commands directly.
        final FrontPageBuilder frontPageBuilder = new FrontPageBuilder();
        final AdminPanelBuilder adminPanelBuilder = new AdminPanelBuilder();

        final MenuRedeemSubcommand.MenuPageBuilder menuPageBuilder =
                (node, open, assembledPath) -> {
                    // Stage B: the root /rtp menu page is the curated front page, not
                    // the flat reflector. Any descended node (assembledPath non-empty)
                    // or any node that isn't this command root falls through to the
                    // existing CommandTreeMenuBuilder reflector.
                    if (node == rtpRoot && assembledPath.isEmpty()) {
                        return frontPageBuilder.build(
                                node, open.viewer(), menuPermissionProbe.apply(open.viewer()));
                    }
                    return new CommandTreeMenuBuilder()
                            .build(
                                    node,
                                    open.viewer(),
                                    menuPermissionProbe.apply(open.viewer()),
                                    MenuConsumerProfile.defaultProfile(),
                                    assembledPath);
                };

        // Stage A.2: wire the param-picker builder so OpenParamPicker redeems
        // resolve to a value-picker sub-page (see dispatchOpenParamPicker).
        final MenuRedeemSubcommand.MenuParamPickerBuilder menuParamPickerBuilder =
                (parent, viewer, parentPath, paramName) ->
                        new CommandTreeMenuBuilder()
                                .buildParamPicker(
                                        parent,
                                        viewer,
                                        menuPermissionProbe.apply(viewer),
                                        MenuConsumerProfile.defaultProfile(),
                                        parentPath,
                                        paramName);

        final MenuRedeemSubcommand.MenuConfigSubtreeBuilder configSubtreeBuilder =
                buildConfigSubtreeBuilder();

        final MenuRedeemSubcommand.MenuCuratedPageBuilder curatedPageBuilder =
                buildCuratedPageBuilder(frontPageBuilder, adminPanelBuilder);

        final MenuRedeemSubcommand.MenuConfigSearchBuilder configSearchBuilder =
                buildConfigSearchBuilder();

        // PROPOSAL-info-as-book.md section 4.6 — curated /rtp info book builder.
        final InfoBookBuilder infoBookBuilderImpl = new InfoBookBuilder();
        final MenuRedeemSubcommand.MenuInfoBookBuilder infoBookBuilder =
                (UUID viewer, MenuAction.InfoScopeToken scope) ->
                        infoBookBuilderImpl.build(rtpRoot, viewer, scope);

        final MenuRedeemSubcommand menuRedeem =
                new MenuRedeemSubcommand(
                        rtpRoot,
                        menuPermissionProbe,
                        menuRenderer,
                        menuPageBuilder,
                        menuParamPickerBuilder,
                        anvilOpener,
                        configSubtreeBuilder,
                        curatedPageBuilder,
                        configSearchBuilder,
                        infoBookBuilder);

        // Staging-cart wiring: bind the anvil opener's cart sink to this redeem
        // instance so STAGE-mode anvil confirms push into the per-player cart
        // on this backend (CHECKLIST-config-staging-cart.md).
        if (anvilOpener != null) {
            anvilOpener.setCartSink(menuRedeem.cartSink());
        }
        // CHECKLIST-multiconfig-menu step 12: wire the MultiConfigMenuBuilder
        // and register the two default removal guards.
        final MultiConfigMenuBuilder multiConfigBuilder =
                new MultiConfigMenuBuilder();
        multiConfigBuilder.setCommandTreeMenuBuilder(
                new CommandTreeMenuBuilder());
        menuRedeem.setMultiConfigBuilder(multiConfigBuilder);
        DefaultMultiConfigRemovalGuards.registerDefaults();
        rtpRoot.addSubCommand(menuRedeem);

        installAdminCmd(adminPanelBuilder);
        installConfigSearchHandler(configSearchBuilder);
    }

    // ---- Builder factories (extracted purely for readability) -------------

    private MenuRedeemSubcommand.MenuConfigSubtreeBuilder buildConfigSubtreeBuilder() {
        // PROPOSAL-config-view-as-book v3.7 — wire the production config-subtree
        // builder so OpenConfigSelector / OpenConfigFile tokens redeem to the
        // curated book pages.
        return new MenuRedeemSubcommand.MenuConfigSubtreeBuilder() {
            @Override
            public MenuModel buildSelector(UUID viewer) {
                List<String> fileNames = new ArrayList<>();
                try {
                    for (ConfigParser<?> parser : RTP.configs.configParserMap.values()) {
                        if (parser == null || parser.name == null) continue;
                        String n = parser.name;
                        if (n.toLowerCase(Locale.ROOT).endsWith(".yml")) {
                            n = n.substring(0, n.length() - 4);
                        }
                        if (!n.isEmpty()) fileNames.add(n);
                    }
                } catch (RuntimeException e) {
                    RTP.log(Level.WARNING,
                            "menu config-selector: failed to enumerate parsers: " + e.getMessage(), e);
                }
                java.util.Collections.sort(fileNames);
                return new CommandTreeMenuBuilder()
                        .buildConfigSelector(viewer, fileNames);
            }

            @Override
            public MenuModel buildFile(UUID viewer, String fileName) {
                ConfigParser<?> parser = resolveParserByFileName(fileName);
                if (parser == null) return null;
                return new CommandTreeMenuBuilder()
                        .buildConfigFile(viewer, stripYml(fileName), parser,
                                new LinkedHashMap<>());
            }

            @Override
            public MenuModel buildFile(UUID viewer, String fileName,
                                       LinkedHashMap<String, String> cartSnapshot) {
                // Item 6 of the staging-cart redesign: cart-aware rendering of
                // /rtp config <file>. cartSnapshot is the viewer's currently
                // staged (paramName -> typed value) pairs.
                ConfigParser<?> parser = resolveParserByFileName(fileName);
                if (parser == null) return null;
                return new CommandTreeMenuBuilder()
                        .buildConfigFile(viewer, stripYml(fileName), parser,
                                cartSnapshot == null
                                        ? new LinkedHashMap<>()
                                        : cartSnapshot);
            }

            @Override
            public MenuModel buildKey(UUID viewer, String fileName, String paramName) {
                // PROPOSAL v3.7 §3.3 — resolve the live SubConfigCmd node for
                // <fileName> and reuse buildParamPicker so suggested values
                // write back via /rtp config <fileName>.yml <paramName>:<value>.
                CommandsAPICommand configCmd = rtpRoot.getCommandLookup().get("CONFIG");
                if (!(configCmd instanceof TreeCommand configTree)) {
                    return null;
                }
                String subKey = (stripYml(fileName) + ".yml").toUpperCase(Locale.ROOT);
                CommandsAPICommand subCmd = configTree.getCommandLookup().get(subKey);
                String subSegment = stripYml(fileName) + ".yml";
                if (subCmd == null) {
                    // Some baseline registrations historically used the bare
                    // file name without .yml; try that as a fallback and adjust
                    // the parentPath segment accordingly so the dispatcher walk
                    // matches the actual subcommand name.
                    subCmd = configTree.getCommandLookup().get(
                            stripYml(fileName).toUpperCase(Locale.ROOT));
                    if (subCmd != null) {
                        subSegment = stripYml(fileName);
                    }
                }
                if (!(subCmd instanceof TreeCommand parent)) {
                    return null;
                }
                List<String> parentPath = List.of("config", subSegment);
                return new CommandTreeMenuBuilder()
                        .buildParamPicker(
                                parent,
                                viewer,
                                menuPermissionProbe.apply(viewer),
                                MenuConsumerProfile.defaultProfile(),
                                parentPath,
                                paramName);
            }
        };
    }

    private MenuRedeemSubcommand.MenuCuratedPageBuilder buildCuratedPageBuilder(
            FrontPageBuilder frontPageBuilder,
            AdminPanelBuilder adminPanelBuilder) {
        // PROPOSAL-admin-panel.md v2 — curated admin-panel and front-page
        // builders so OpenAdminPanel / OpenFrontPage tokens redeem to the
        // curated book pages. The rtp.menu.admin gate is enforced in
        // MenuRedeemSubcommand.dispatchOpenAdminPanel, not here.
        return new MenuRedeemSubcommand.MenuCuratedPageBuilder() {
            @Override
            public MenuModel buildAdminPanel(UUID viewer) {
                return adminPanelBuilder.build(
                        rtpRoot, viewer, menuPermissionProbe.apply(viewer));
            }

            @Override
            public MenuModel buildFrontPage(UUID viewer) {
                return frontPageBuilder.build(
                        rtpRoot, viewer, menuPermissionProbe.apply(viewer));
            }

            @Override
            public MenuModel buildVisualizations(UUID viewer) {
                // PR2b: curated admin Visualizations submenu. rtp.menu.admin is
                // enforced by MenuRedeemSubcommand.dispatchOpenVisualizations.
                return new VisualizationsSubmenuBuilder().build(viewer);
            }
        };
    }

    private MenuRedeemSubcommand.MenuConfigSearchBuilder buildConfigSearchBuilder() {
        // PROPOSAL-rtp-menu-config-search.md slice 5b — production
        // MenuConfigSearchBuilder. Walks RTP.configs via
        // ConfigSearchResultsBuilder, renders a paginated MenuModel where each
        // hit row is a gray-base line with an off-blue highlight overlay at the
        // raw-offset match ranges. Each row click resolves to OpenConfigKey.
        return (UUID viewer, String query, int page) -> {
            if (query == null) query = "";
            String safeQuery = query;
            int safePage = Math.max(1, page);
            List<ConfigSearchResultsBuilder.Hit> hits;
            try {
                hits = ConfigSearchResultsBuilder.search(safeQuery);
            } catch (RuntimeException e) {
                RTP.log(Level.WARNING,
                        "menu config-search: search failed for query='" + safeQuery
                                + "': " + e.getMessage(), e);
                return null;
            }

            final int rowsPerPage = 8;
            final String backLabel = "&7« back";
            final MenuLine backRow = MenuLine.of(
                    new MenuFragment(backLabel, null, new MenuAction.OpenConfigSelector()));
            final MenuLine headerRow = MenuLine.of(
                    new MenuFragment("&1&lconfig search: &9" + safeQuery, null, null));

            List<MenuPage> pages = new ArrayList<>();
            List<MenuLine> lines = new ArrayList<>();
            lines.add(backRow);
            lines.add(headerRow);
            if (hits.isEmpty()) {
                lines.add(MenuLine.of(
                        new MenuFragment("&7(no matches)", null, null)));
                pages.add(new MenuPage(lines));
            } else {
                for (var hit : hits) {
                    MenuAction click = new MenuAction.OpenConfigKey(
                            hit.fileName(), hit.keyName());
                    List<MenuFragment> frags = new ArrayList<>();
                    String prefix = "&8" + hit.fileName() + "&7/&2" + hit.keyName() + "&7: ";
                    String raw = hit.rawValue();
                    List<int[]> ranges = hit.matchRanges();
                    if (ranges == null || ranges.isEmpty()) {
                        frags.add(new MenuFragment(prefix + "&7" + raw, null, click));
                    } else {
                        int cursor = 0;
                        StringBuilder head = new StringBuilder(prefix).append("&7");
                        int[] first = ranges.get(0);
                        if (first[0] > 0) head.append(raw, 0, first[0]);
                        frags.add(new MenuFragment(head.toString(), null, click));
                        cursor = first[0];
                        for (int i = 0; i < ranges.size(); i++) {
                            int[] r = ranges.get(i);
                            frags.add(new MenuFragment(
                                    "&9&l" + raw.substring(r[0], r[1]) + "&r&7", null, click));
                            cursor = r[1];
                            int nextStart = (i + 1 < ranges.size())
                                    ? ranges.get(i + 1)[0]
                                    : raw.length();
                            if (nextStart > cursor) {
                                frags.add(new MenuFragment(
                                        raw.substring(cursor, nextStart), null, click));
                            }
                        }
                    }
                    lines.add(new MenuLine(frags));
                    if (lines.size() - 2 >= rowsPerPage) {
                        pages.add(new MenuPage(lines));
                        lines = new ArrayList<>();
                        lines.add(backRow);
                        lines.add(headerRow);
                    }
                }
                if (lines.size() > 2) {
                    pages.add(new MenuPage(lines));
                }
            }

            // ADR-050 Stage 3α: pre-mint loop removed. Renderer emits
            // concrete /rtp menu ... commands per fragment action.

            if (pages.isEmpty()) return null;
            return new MenuModel("config:search:" + safeQuery, pages);
        };
    }

    // ---- Admin verb + deferred /rtp config search handler ----------------

    private void installAdminCmd(AdminPanelBuilder adminPanelBuilder) {
        // PROPOSAL-admin-panel-prefabs.md v3.1 (Session 4b D1) — register the
        // top-level `/rtp admin` verb whose bare form opens the curated admin
        // panel. When the renderer is null (no menu renderer available on this
        // platform) the opener is null and the bare form rejects with the
        // configurable menuInvalid message per S-004 / S-007.
        final Consumer<UUID> openAdminPanel;
        if (menuRenderer == null) {
            openAdminPanel = null;
        } else {
            openAdminPanel = viewer -> {
                if (viewer == null) return;
                Predicate<String> probe = menuPermissionProbe.apply(viewer);
                try {
                    if (probe == null || !probe.test("rtp.menu.admin")) {
                        RTP.log(Level.WARNING,
                                "/rtp admin opener: " + viewer + " lacks rtp.menu.admin");
                        return;
                    }
                } catch (RuntimeException e) {
                    RTP.log(Level.WARNING,
                            "/rtp admin opener: permission probe threw for " + viewer
                                    + ": " + e.getMessage(), e);
                    return;
                }
                MenuModel model;
                try {
                    model = adminPanelBuilder.build(rtpRoot, viewer, probe);
                } catch (RuntimeException e) {
                    RTP.log(Level.WARNING,
                            "/rtp admin opener: admin-panel builder failed for " + viewer
                                    + ": " + e.getMessage(), e);
                    return;
                }
                if (model == null) {
                    RTP.log(Level.WARNING,
                            "/rtp admin opener: admin-panel builder returned null model for " + viewer);
                    return;
                }
                try {
                    menuRenderer.render(viewer, model);
                } catch (RuntimeException e) {
                    RTP.log(Level.WARNING,
                            "/rtp admin opener: renderer failed for " + viewer
                                    + ": " + e.getMessage(), e);
                }
            };
        }
        final AdminCmd adminCmd = new AdminCmd(rtpRoot, openAdminPanel);
        adminCmd.addSubCommand(new PrefabCommand(adminCmd));
        rtpRoot.addSubCommand(adminCmd);
    }

    private void installConfigSearchHandler(
            MenuRedeemSubcommand.MenuConfigSearchBuilder configSearchBuilder) {
        // PROPOSAL-rtp-menu-config-search.md slice 5b — wire the search-leaf
        // handler now that the renderer + builder are constructed. ConfigCmd
        // registers ConfigSearchSubCmd via its own 5-tick deferred addCommands;
        // schedule our handler attachment at 8 ticks so it lands after ConfigCmd
        // but before the menu mirror seeds at 10 ticks.
        final MenuRedeemSubcommand.MenuConfigSearchBuilder finalSearchBuilder = configSearchBuilder;
        final MenuRenderer finalRenderer = menuRenderer;
        RTP.getInstance().miscAsyncTasks.add(new RTPRunnable(() -> {
            CommandsAPICommand configCmd = rtpRoot.getCommandLookup().get("CONFIG");
            if (!(configCmd instanceof TreeCommand configTree)) {
                return;
            }
            CommandsAPICommand searchCmd = configTree.getCommandLookup().get("SEARCH");
            if (!(searchCmd
                    instanceof io.github.dailystruggle.rtp.common.commands.config.ConfigSearchSubCmd searchLeaf)) {
                return;
            }
            if (finalRenderer == null || finalSearchBuilder == null) return;
            searchLeaf.setHandler((UUID callerId, String query) -> {
                var model = finalSearchBuilder.buildResults(callerId, query, 1);
                if (model == null) {
                    RTP.log(Level.WARNING,
                            "config search: empty model for query='" + query + "'");
                    return;
                }
                finalRenderer.render(callerId, model);
            });
        }, 8));
    }

    // ---- Local helpers ----------------------------------------------------

    private static @Nullable ConfigParser<?> resolveParserByFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        String wanted = stripYml(fileName).toLowerCase(Locale.ROOT);
        try {
            for (ConfigParser<?> parser : RTP.configs.configParserMap.values()) {
                if (parser == null || parser.name == null) continue;
                String n = stripYml(parser.name).toLowerCase(Locale.ROOT);
                if (n.equals(wanted)) return parser;
            }
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu config: failed to resolve parser '" + fileName + "': "
                            + e.getMessage(), e);
        }
        return null;
    }

    private static String stripYml(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).endsWith(".yml")
                ? s.substring(0, s.length() - 4)
                : s;
    }
}
