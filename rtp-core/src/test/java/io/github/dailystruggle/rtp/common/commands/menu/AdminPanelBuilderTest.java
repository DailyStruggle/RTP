package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPOSAL-admin-panel.md v2 tests for {@link AdminPanelBuilder} - the curated
 * admin-panel page reached from the front page's single {@code Admin panel}
 * entry row.
 *
 * <p>Covers:
 * <ul>
 *   <li>Section ordering: Configuration -> Diagnostics -> Lifecycle -> Browse,
 *       with the back row at the bottom.</li>
 *   <li>Per-row self-hide: every row drops silently when its underlying
 *       subcommand is missing or the viewer lacks the row's permission.</li>
 *   <li>Section-divider auto-suppression: a section whose body is entirely
 *       hidden produces no divider line (no orphan "Lifecycle" header with
 *       zero rows under it).</li>
 *   <li>Back row emits {@link MenuAction.OpenFrontPage}, not
 *       {@link MenuAction.OpenMenu} with an empty path.</li>
 *   <li>{@code Browse all commands} row is always present and emits
 *       {@link MenuAction.OpenMenu} with an empty path.</li>
 *   <li>Reload row carries a non-null warning hover.</li>
 * </ul>
 *
 * Traceability: PROPOSAL-admin-panel.md v2, REQ-RTP-F-013 (all labels
 * configurable).
 */
final class AdminPanelBuilderTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setupRTP() {
        RTPTestSetup.install(tempDir.toFile());
    }

    // ------------------------------------------------------------------------
    // Full row catalogue
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Full admin: emits all curated rows with the back row last")
    void fullAdmin_emitsAllRows() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = withAllAdminSubcommands();
        // Wire a /rtp test memory subcommand to exercise the optional row.
        TestableTree testNode = (TestableTree) root.getCommandLookup().get("test");
        testNode.getCommandLookup().put("memory", new LeafSub("memory"));

        MenuModel model = new AdminPanelBuilder(registry)
                .build(root, UUID.randomUUID(), perm -> true);

        // Configuration
        assertNotNull(findOpenConfigSelector(model),
                "Config row must appear when /rtp config is registered and viewer has rtp.config.view");
        // CHECKLIST-multiconfig-menu: Regions / Worlds rows live on the
        // config selector page, not the admin panel.
        assertNull(findOpenMultiConfigSelector(model, "regions"),
                "Regions submenu row must NOT appear on the admin panel");
        assertNull(findOpenMultiConfigSelector(model, "worlds"),
                "Worlds submenu row must NOT appear on the admin panel");

        // Diagnostics: per PROPOSAL-info-as-book.md section 4.6, the info
        // row now opens the curated info book via OpenInfo(global) rather
        // than synthesising a RunRtpCommand(["info"]).
        assertNotNull(findOpenInfoGlobal(model),
                "Server info row must appear and open the info book");
        assertNotNull(findRunWithArgs(model, "test", "full"),
                "Full diagnostics row must appear");
        assertNotNull(findRunWithArgs(model, "test", "memory"),
                "Memory tracker row must appear when /rtp test memory is registered");
        assertNotNull(findOpenMenuTo(model, "scan"),
                "Scan control row must appear");

        // Lifecycle
        assertNotNull(findRunWithArgs(model, "reload"),
                "Reload row must appear when /rtp reload is registered");

        // Browse - always present.
        assertNotNull(findOpenMenuEmpty(model),
                "Browse all commands row must always be present");

        // Back row - last clickable line.
        MenuAction last = lastClickable(model);
        assertInstanceOf(MenuAction.OpenFrontPage.class, last,
                "Back row must be the last clickable line and emit OpenFrontPage");
    }

    // ------------------------------------------------------------------------
    // Section-divider auto-suppression
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Lifecycle divider is suppressed when admin lacks rtp.reload")
    void lifecycleDivider_suppressed_whenReloadHidden() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = withAllAdminSubcommands();

        // Admin holds everything *except* rtp.reload.
        MenuModel model = new AdminPanelBuilder(registry)
                .build(root, UUID.randomUUID(), perm -> !AdminPanelBuilder.RELOAD_PERMISSION.equals(perm));

        assertNull(findRunWithArgs(model, "reload"),
                "Reload row must be hidden when rtp.reload is missing");
        // Lifecycle divider text fragment must not be present.
        assertFalse(hasFragmentContaining(model, "lifecycle"),
                "Lifecycle divider must auto-suppress when its only row is hidden");
    }

    @Test
    @DisplayName("Configuration divider is suppressed when /rtp config is unregistered")
    void configurationDivider_suppressed_whenConfigUnregistered() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        // No /rtp config. Add unrelated nodes so the panel still has body.
        root.getCommandLookup().put("info", new LeafSub("info"));
        root.getCommandLookup().put("reload", new LeafSub("reload"));

        MenuModel model = new AdminPanelBuilder(registry)
                .build(root, UUID.randomUUID(), perm -> true);

        assertNull(findOpenConfigSelector(model),
                "Config row must be hidden when /rtp config is unregistered");
        assertNull(findOpenMultiConfigSelector(model, "regions"),
                "Regions submenu row never appears on admin panel");
        assertNull(findOpenMultiConfigSelector(model, "worlds"),
                "Worlds submenu row never appears on admin panel");
        assertFalse(hasFragmentContaining(model, "configuration"),
                "Configuration divider must auto-suppress when its only row is hidden");
        // Other sections still render. The info row is now OpenInfo (book).
        assertNotNull(findOpenInfoGlobal(model));
        assertNotNull(findRunWithArgs(model, "reload"));
    }

    @Test
    @DisplayName("Admin panel never surfaces Regions / Worlds rows (config selector owns them)")
    void regionsWorldsRows_neverOnAdminPanel() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = withAllAdminSubcommands();

        MenuModel model = new AdminPanelBuilder(registry)
                .build(root, UUID.randomUUID(), perm -> true);

        assertNull(findOpenMultiConfigSelector(model, "regions"),
                "Regions submenu row must live on the config selector page, not the admin panel");
        assertNull(findOpenMultiConfigSelector(model, "worlds"),
                "Worlds submenu row must live on the config selector page, not the admin panel");
    }

    @Test
    @DisplayName("Browse section always renders (its row is unconditional)")
    void browseSection_alwaysRenders() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        // No admin subcommands wired; only the panel chrome + Browse row + Back.
        MenuModel model = new AdminPanelBuilder(registry)
                .build(root, UUID.randomUUID(), perm -> true);

        assertNotNull(findOpenMenuEmpty(model),
                "Browse row must render even when every other section is empty");
        // Section divider rows were removed so the admin panel fits one
        // book page; descriptive context now lives on each row's hover.
        assertFalse(hasFragmentContaining(model, "browse"),
                "Browse divider must not render (dividers removed)");
    }

    // ------------------------------------------------------------------------
    // Per-row permission hiding
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Config row is hidden when admin lacks rtp.config.view")
    void configRow_hidden_whenNoConfigView() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = withAllAdminSubcommands();

        MenuModel model = new AdminPanelBuilder(registry).build(
                root, UUID.randomUUID(),
                perm -> !AdminPanelBuilder.CONFIG_VIEW_PERMISSION.equals(perm));

        assertNull(findOpenConfigSelector(model),
                "Config row must be hidden without rtp.config.view");
    }

    @Test
    @DisplayName("Scan row is hidden when admin lacks rtp.scan")
    void scanRow_hidden_whenNoScan() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = withAllAdminSubcommands();

        MenuModel model = new AdminPanelBuilder(registry).build(
                root, UUID.randomUUID(),
                perm -> !AdminPanelBuilder.SCAN_PERMISSION.equals(perm));

        assertNull(findOpenMenuTo(model, "scan"),
                "Scan row must be hidden without rtp.scan");
    }

    // ------------------------------------------------------------------------
    // Missing-subcommand self-hide
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Diagnostics rows drop silently when /rtp test is unregistered")
    void diagnosticsRows_drop_whenTestUnregistered() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        // Only info + scan + reload registered; test is missing.
        root.getCommandLookup().put("info", new LeafSub("info"));
        root.getCommandLookup().put("scan", new LeafSub("scan"));
        root.getCommandLookup().put("reload", new LeafSub("reload"));

        MenuModel model = new AdminPanelBuilder(registry)
                .build(root, UUID.randomUUID(), perm -> true);

        assertNotNull(findOpenInfoGlobal(model),
                "Info still renders (now as OpenInfo book row)");
        assertNull(findRunWithArgs(model, "test", "full"),
                "Full diagnostics row must drop when /rtp test is missing");
        assertNull(findRunWithArgs(model, "test", "memory"),
                "Memory row must drop when /rtp test is missing");
    }

    @Test
    @DisplayName("Memory row drops silently when /rtp test memory is unregistered")
    void memoryRow_drops_whenChildUnregistered() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = withAllAdminSubcommands();
        // /rtp test exists but has no `memory` child.

        MenuModel model = new AdminPanelBuilder(registry)
                .build(root, UUID.randomUUID(), perm -> true);

        assertNotNull(findRunWithArgs(model, "test", "full"),
                "Full diagnostics still renders");
        assertNull(findRunWithArgs(model, "test", "memory"),
                "Memory row must drop when /rtp test memory is unregistered");
    }

    // ------------------------------------------------------------------------
    // Hover assertions
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Reload row carries a non-null warning hover")
    void reloadRow_hasWarningHover() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = withAllAdminSubcommands();

        MenuModel model = new AdminPanelBuilder(registry)
                .build(root, UUID.randomUUID(), perm -> true);

        MenuFragment reload = findFragmentWithRunArgs(model, "reload");
        assertNotNull(reload);
        assertNotNull(reload.hover(),
                "Reload row must carry a non-null warning hover");
        assertFalse(reload.hover().isEmpty(),
                "Reload row hover must be non-empty");
    }

    @Test
    @DisplayName("Full diagnostics row carries a non-null warning hover")
    void diagnosticsRow_hasWarningHover() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = withAllAdminSubcommands();

        MenuModel model = new AdminPanelBuilder(registry)
                .build(root, UUID.randomUUID(), perm -> true);

        MenuFragment diag = findFragmentWithRunArgs(model, "test", "full");
        assertNotNull(diag);
        assertNotNull(diag.hover(),
                "Full diagnostics row must carry a non-null hover");
    }

    // ------------------------------------------------------------------------
    // Robustness
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("A throwing permission probe hides perm-gated rows but keeps Browse + Back")
    void throwingPermissionProbe_hidesGatedRows() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = withAllAdminSubcommands();

        MenuModel model = new AdminPanelBuilder(registry).build(
                root, UUID.randomUUID(),
                perm -> { throw new RuntimeException("boom"); });

        // Gated rows must be hidden when the probe throws.
        assertNull(findOpenConfigSelector(model),
                "Config row must be hidden when permission probe throws");
        assertNull(findRunWithArgs(model, "reload"),
                "Reload row must be hidden when permission probe throws");
        assertNull(findOpenMenuTo(model, "scan"),
                "Scan row must be hidden when permission probe throws");
        // Browse + back must still render (no permission gate).
        assertNotNull(findOpenMenuEmpty(model),
                "Browse row must render even when probe throws");
        MenuAction last = lastClickable(model);
        assertInstanceOf(MenuAction.OpenFrontPage.class, last,
                "Back row must still be present");
    }

    @Test
    @DisplayName("Pagination: every page respects LINES_PER_PAGE and the Back row is the last clickable")
    void panelPagination_respectsPageCapAndPlacesBackLast() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = withAllAdminSubcommands();

        MenuModel model = new AdminPanelBuilder(registry)
                .build(root, UUID.randomUUID(), perm -> true);

        // After the Setup-section collapse (one row that opens the
        // prefab subtree via OpenMenu instead of seven inline prefab rows)
        // a fully-populated admin panel comfortably fits within a single
        // book page, but the pagination invariants must still hold: no
        // page may exceed LINES_PER_PAGE, and the Back row must always
        // be the final clickable line so the operator can return to the
        // front page from whichever page they end on.
        assertFalse(model.pages().isEmpty(),
                "Admin panel must produce at least one page");
        for (io.github.dailystruggle.rtp.api.menu.MenuPage p : model.pages()) {
            assertTrue(p.lines().size() <= AdminPanelBuilder.LINES_PER_PAGE,
                    "each page must respect LINES_PER_PAGE");
        }
        MenuAction last = lastClickable(model);
        assertInstanceOf(MenuAction.OpenFrontPage.class, last,
                "Back row must remain the last clickable line");
    }

    // ------------------------------------------------------------------------
    // Setup section (PROPOSAL-admin-panel-prefabs.md v3.1, Session 5)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Setup section: single OpenParamPicker row opening the prefab id picker")
    void setupSection_emitsSinglePrefabPickerRow() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = withAllAdminSubcommands();

        MenuModel model = new AdminPanelBuilder(registry)
                .build(root, UUID.randomUUID(), perm -> true);

        // The Setup section is a single entry-point row that opens the
        // prefab `id` parameter picker directly (server-resolved by
        // MenuRedeemSubcommand, rendering one clickable row per value
        // from PrefabIdParameter.values()), giving the operator the
        // clickable prefab-selection book menu without flooding the
        // admin panel page itself with per-prefab rows. The chat-list
        // dispatch, the one-row-per-prefab inline layout, and the
        // intermediate OpenMenu({admin,prefab}) subcommand-list page
        // are all prior regressions.
        assertNotNull(
                findOpenParamPicker(model, "id", "admin", "prefab", "apply"),
                "Setup section must emit an OpenParamPicker({admin,prefab,apply}, id) row");
        assertNull(
                findRunWithArgs(model, "admin", "prefab", "list"),
                "Setup section must not dispatch the chat-list verb (prior regression)");
        assertNull(
                findOpenMenuToPath(model, "admin", "prefab"),
                "Setup section must not open the apply/confirm/rollback/list subcommand-list page (prior regression)");
    }

    @Test
    @DisplayName("Setup section: suppressed wholesale when viewer lacks rtp.admin.prefab")
    void setupSection_suppressed_whenLackingPermission() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = withAllAdminSubcommands();

        MenuModel model = new AdminPanelBuilder(registry)
                .build(root, UUID.randomUUID(),
                        perm -> !AdminPanelBuilder.PREFAB_PERMISSION.equals(perm));

        assertNull(
                findOpenParamPicker(model, "id", "admin", "prefab", "apply"),
                "Setup row must be hidden without rtp.admin.prefab");
        // Setup divider literal must not leak either.
        assertFalse(hasFragmentContaining(model, "setup (quick start)"),
                "Setup divider must also be suppressed when the entire section is hidden");
    }

    @Test
    @DisplayName("Setup section: throwing permission probe hides the entire section")
    void setupSection_hidden_whenProbeThrows() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = withAllAdminSubcommands();

        MenuModel model = new AdminPanelBuilder(registry)
                .build(root, UUID.randomUUID(),
                        perm -> { throw new RuntimeException("boom"); });

        assertNull(
                findOpenParamPicker(model, "id", "admin", "prefab", "apply"),
                "Setup row must be hidden when probe throws");
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static TestableRoot withAllAdminSubcommands() {
        TestableRoot root = new TestableRoot();
        root.getCommandLookup().put("config", new LeafSub("config"));
        root.getCommandLookup().put("info", new LeafSub("info"));
        root.getCommandLookup().put("test", new TestableTree("test"));
        root.getCommandLookup().put("scan", new LeafSub("scan"));
        root.getCommandLookup().put("reload", new LeafSub("reload"));
        return root;
    }

    private static MenuAction findOpenConfigSelector(MenuModel model) {
        for (io.github.dailystruggle.rtp.api.menu.MenuPage page : model.pages()) {
            for (MenuLine line : page.lines()) {
                for (MenuFragment frag : line.fragments()) {
                    if (frag.action() instanceof MenuAction.OpenConfigSelector) return frag.action();
                }
            }
        }
        return null;
    }

    /**
     * CHECKLIST-multiconfig-menu step 10: locates an
     * {@link MenuAction.OpenMultiConfigSelector} row whose {@code parserKind}
     * matches the requested string (case-sensitive on the wire; kind is
     * normalised to lower-case at construction).
     */
    private static MenuAction findOpenMultiConfigSelector(MenuModel model, String parserKind) {
        for (io.github.dailystruggle.rtp.api.menu.MenuPage page : model.pages()) {
            for (MenuLine line : page.lines()) {
                for (MenuFragment frag : line.fragments()) {
                    if (frag.action() instanceof MenuAction.OpenMultiConfigSelector sel
                            && parserKind.equals(sel.parserKind())) {
                        return frag.action();
                    }
                }
            }
        }
        return null;
    }

    /**
     * PROPOSAL-info-as-book.md section 4.6: the admin-panel info row emits
     * an {@link MenuAction.OpenInfo} with the {@code GLOBAL} scope instead of
     * the legacy {@code RunRtpCommand(["info"])}.
     */
    private static MenuAction findOpenInfoGlobal(MenuModel model) {
        for (io.github.dailystruggle.rtp.api.menu.MenuPage page : model.pages()) {
            for (MenuLine line : page.lines()) {
                for (MenuFragment frag : line.fragments()) {
                    if (frag.action() instanceof MenuAction.OpenInfo open
                            && open.scope().kind() == MenuAction.InfoScopeToken.Kind.GLOBAL) {
                        return open;
                    }
                }
            }
        }
        return null;
    }

    private static MenuAction findRunWithArgs(MenuModel model, String... expected) {
        for (io.github.dailystruggle.rtp.api.menu.MenuPage page : model.pages()) {
            for (MenuLine line : page.lines()) {
                for (MenuFragment frag : line.fragments()) {
                    if (frag.action() instanceof MenuAction.RunRtpCommand run
                            && argsMatch(run.args(), expected)) {
                        return run;
                    }
                }
            }
        }
        return null;
    }

    private static MenuFragment findFragmentWithRunArgs(MenuModel model, String... expected) {
        for (io.github.dailystruggle.rtp.api.menu.MenuPage page : model.pages()) {
            for (MenuLine line : page.lines()) {
                for (MenuFragment frag : line.fragments()) {
                    if (frag.action() instanceof MenuAction.RunRtpCommand run
                            && argsMatch(run.args(), expected)) {
                        return frag;
                    }
                }
            }
        }
        return null;
    }

    private static MenuAction findOpenMenuTo(MenuModel model, String firstSegment) {
        for (io.github.dailystruggle.rtp.api.menu.MenuPage page : model.pages()) {
            for (MenuLine line : page.lines()) {
                for (MenuFragment frag : line.fragments()) {
                    if (frag.action() instanceof MenuAction.OpenMenu open
                            && open.path().length >= 1
                            && firstSegment.equals(open.path()[0])) {
                        return open;
                    }
                }
            }
        }
        return null;
    }

    private static MenuAction findOpenMenuToPath(MenuModel model, String... expected) {
        for (io.github.dailystruggle.rtp.api.menu.MenuPage page : model.pages()) {
            for (MenuLine line : page.lines()) {
                for (MenuFragment frag : line.fragments()) {
                    if (frag.action() instanceof MenuAction.OpenMenu open
                            && argsMatch(open.path(), expected)) {
                        return open;
                    }
                }
            }
        }
        return null;
    }

    private static MenuAction findOpenParamPicker(MenuModel model,
                                                  String paramName,
                                                  String... parentPath) {
        for (io.github.dailystruggle.rtp.api.menu.MenuPage page : model.pages()) {
            for (MenuLine line : page.lines()) {
                for (MenuFragment frag : line.fragments()) {
                    if (frag.action() instanceof MenuAction.OpenParamPicker picker
                            && paramName.equals(picker.paramName())
                            && argsMatch(picker.parentPath(), parentPath)) {
                        return picker;
                    }
                }
            }
        }
        return null;
    }

    private static MenuAction findOpenMenuEmpty(MenuModel model) {
        for (io.github.dailystruggle.rtp.api.menu.MenuPage page : model.pages()) {
            for (MenuLine line : page.lines()) {
                for (MenuFragment frag : line.fragments()) {
                    if (frag.action() instanceof MenuAction.OpenMenu open
                            && open.path().length == 0) {
                        return open;
                    }
                }
            }
        }
        return null;
    }

    private static MenuAction lastClickable(MenuModel model) {
        MenuAction last = null;
        for (io.github.dailystruggle.rtp.api.menu.MenuPage page : model.pages()) {
            for (MenuLine line : page.lines()) {
                for (MenuFragment frag : line.fragments()) {
                    if (frag.action() != null) last = frag.action();
                }
            }
        }
        return last;
    }

    private static boolean hasFragmentContaining(MenuModel model, String needle) {
        for (io.github.dailystruggle.rtp.api.menu.MenuPage page : model.pages()) {
            for (MenuLine line : page.lines()) {
                for (MenuFragment frag : line.fragments()) {
                    String text = frag.text();
                    if (text != null && text.contains(needle)) return true;
                }
            }
        }
        return false;
    }

    private static boolean argsMatch(String[] actual, String[] expected) {
        if (actual.length != expected.length) return false;
        for (int i = 0; i < actual.length; i++) {
            if (!expected[i].equals(actual[i])) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    private static class TestableRoot extends BaseRTPCmdImpl {
        TestableRoot() { super(null); }
        @Override public String name() { return "rtp"; }
        @Override public String permission() { return ""; }
        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 CommandsAPICommand nextCommand) { return true; }
        @Override
        public CompletableFuture<Boolean> onCommand(UUID callerId,
                                                    Predicate<String> permissionCheckMethod,
                                                    Consumer<String> messageMethod,
                                                    String[] args,
                                                    int i,
                                                    Map<String, CommandParameter> tempParameters) {
            return CompletableFuture.completedFuture(true);
        }
    }

    /** Tree fixture so a subcommand can host its own children (used for {@code test}). */
    private static final class TestableTree extends BaseRTPCmdImpl {
        private final String name;
        TestableTree(String name) { super(null); this.name = name; }
        @Override public String name() { return name; }
        @Override public String permission() { return ""; }
        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 CommandsAPICommand nextCommand) { return true; }
    }

    private static final class LeafSub extends BaseRTPCmdImpl {
        private final String name;
        LeafSub(String name) { super(null); this.name = name; }
        @Override public String name() { return name; }
        @Override public String permission() { return ""; }
        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 CommandsAPICommand nextCommand) { return true; }
    }
}
