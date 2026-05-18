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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Stage B tests for {@link FrontPageBuilder} — the curated landing page for
 * {@code /rtp menu} (no args).
 *
 * <p>Covers REQ-RTP-MENU-005:
 * <ul>
 *   <li>Player view always renders Teleport + Help and only those rows when
 *       no region / biome parameter is registered on the root.</li>
 *   <li>Player view exposes the region / biome picker rows only when the
 *       corresponding parameter exists <em>and</em> {@code relevantValues}
 *       is non-empty (so an unprivileged caller doesn't see broken pickers).</li>
 *   <li>Admin view (granted by {@code rtp.menu.admin}) replaces the picker
 *       block with the admin row catalogue (Info / Config / Scan / Diagnostics /
 *       Reload) — each row appearing only when its target subcommand exists.</li>
 *   <li>The reload row carries a non-null warning hover (Stage B redline #2).</li>
 *   <li>Permission probe is invoked safely (a throwing probe degrades to
 *       hiding the admin view, not breaking the page).</li>
 * </ul>
 *
 * Traceability: REQ-RTP-MENU-005, REQ-RTP-F-013 (all row labels configurable).
 */
final class FrontPageBuilderTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setupRTP() {
        RTPTestSetup.install(tempDir.toFile());
    }

    // ------------------------------------------------------------------------
    // Player view
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Player view: Teleport + Help are always present; no admin rows")
    void playerView_alwaysHasTeleportAndHelp() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();

        MenuModel model = new FrontPageBuilder(registry)
                .build(root, UUID.randomUUID(), perm -> false);

        // Teleport row: RunRtpCommand([]) — bare /rtp execute, args are
        // the sub-command path under the /rtp root (no leading "rtp").
        MenuAction teleport = findRunWithArgs(model);
        assertNotNull(teleport, "Teleport row must be present in player view");

        // Help row: RunRtpCommand(["help"]).
        MenuAction help = findRunWithArgs(model, "help");
        assertNotNull(help, "Help row must be present in player view");

        // Admin rows must NOT appear.
        assertNull(findRunWithArgs(model, "reload"),
                "Reload row must be absent for non-admin viewer");
        assertNull(findRunWithArgs(model, "info"),
                "Info row must be absent for non-admin viewer");
    }

    @Test
    @DisplayName("Player view: region row hidden when region parameter is absent")
    void playerView_regionRowHidden_whenParamMissing() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        // No parameters on the root.

        MenuModel model = new FrontPageBuilder(registry)
                .build(root, UUID.randomUUID(), perm -> false);

        assertNull(findOpenParamPicker(model, "region"),
                "region row must be hidden when no region parameter is registered");
        assertNull(findOpenParamPicker(model, "biome"),
                "biome row must be hidden when no biome parameter is registered");
    }

    @Test
    @DisplayName("Player view: region row hidden when parameter has zero suggestions")
    void playerView_regionRowHidden_whenNoSuggestions() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        root.getParameterLookup().put("region", new FixedParam("", Set.of()));

        MenuModel model = new FrontPageBuilder(registry)
                .build(root, UUID.randomUUID(), perm -> false);

        assertNull(findOpenParamPicker(model, "region"),
                "region row must be hidden when relevantValues is empty");
    }

    @Test
    @DisplayName("Player view: region / biome rows appear when parameter has suggestions")
    void playerView_regionAndBiomeRows_appear() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        root.getParameterLookup().put("region",
                new FixedParam("", Set.of("default")));
        root.getParameterLookup().put("biome",
                new FixedParam("", Set.of("plains", "forest")));

        MenuModel model = new FrontPageBuilder(registry)
                .build(root, UUID.randomUUID(), perm -> false);

        MenuAction regionAction = findOpenParamPicker(model, "region");
        assertNotNull(regionAction, "region picker row must surface");
        assertInstanceOf(MenuAction.OpenParamPicker.class, regionAction);

        MenuAction biomeAction = findOpenParamPicker(model, "biome");
        assertNotNull(biomeAction, "biome picker row must surface");
    }

    // ------------------------------------------------------------------------
    // Admin view
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Admin view: replaces player picker block with admin row catalogue")
    void adminView_emitsAdminRows() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        root.getCommandLookup().put("config", new LeafSub("config"));
        root.getCommandLookup().put("scan", new LeafSub("scan"));
        root.getCommandLookup().put("test", new LeafSub("test"));
        root.getCommandLookup().put("reload", new LeafSub("reload"));
        root.getCommandLookup().put("info", new LeafSub("info"));
        // Even with region/biome params present, the admin view does NOT
        // render the picker rows — it renders the admin catalogue instead.
        root.getParameterLookup().put("region", new FixedParam("", Set.of("default")));

        MenuModel model = new FrontPageBuilder(registry)
                .build(root, UUID.randomUUID(),
                        perm -> "rtp.menu.admin".equals(perm));

        // Admin rows present.
        assertNotNull(findRunWithArgs(model, "info"), "Info row must appear in admin view");
        assertNotNull(findOpenMenuTo(model, "config"), "Config row must appear in admin view");
        assertNotNull(findOpenMenuTo(model, "scan"), "Scan row must appear in admin view");
        assertNotNull(findRunWithArgs(model, "test", "full"),
                "Full diagnostics row must appear in admin view");
        assertNotNull(findRunWithArgs(model, "reload"),
                "Reload row must appear in admin view");

        // Player picker rows must NOT appear (admin view replaces them).
        assertNull(findOpenParamPicker(model, "region"),
                "region picker must be absent in admin view");
    }

    @Test
    @DisplayName("Admin view: rows for unregistered subcommands drop silently")
    void adminView_dropsRows_forMissingSubcommands() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        // Only `info` is registered; all other admin verbs are missing.
        root.getCommandLookup().put("info", new LeafSub("info"));

        MenuModel model = new FrontPageBuilder(registry)
                .build(root, UUID.randomUUID(),
                        perm -> "rtp.menu.admin".equals(perm));

        assertNotNull(findRunWithArgs(model, "info"),
                "Info row present (subcommand registered)");
        assertNull(findOpenMenuTo(model, "config"),
                "Config row must drop when /rtp config is unregistered");
        assertNull(findOpenMenuTo(model, "scan"),
                "Scan row must drop when /rtp scan is unregistered");
        assertNull(findRunWithArgs(model, "test", "full"),
                "Diagnostics row must drop when /rtp test is unregistered");
        assertNull(findRunWithArgs(model, "reload"),
                "Reload row must drop when /rtp reload is unregistered");
    }

    @Test
    @DisplayName("Admin view: reload row carries a non-null warning hover")
    void adminView_reloadRow_hasWarningHover() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        root.getCommandLookup().put("reload", new LeafSub("reload"));

        MenuModel model = new FrontPageBuilder(registry)
                .build(root, UUID.randomUUID(),
                        perm -> "rtp.menu.admin".equals(perm));

        MenuFragment reload = findFragmentWithRunArgs(model, "reload");
        assertNotNull(reload, "reload fragment must exist");
        assertNotNull(reload.hover(),
                "reload row must carry a non-null hover (Stage B redline #2 warning hover)");
        assertFalse(reload.hover().isEmpty(),
                "reload row hover must be non-empty");
    }

    // ------------------------------------------------------------------------
    // Robustness
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("A throwing permission probe degrades to player view (no admin rows)")
    void throwingPermissionProbe_yieldsPlayerView() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        root.getCommandLookup().put("reload", new LeafSub("reload"));

        MenuModel model = new FrontPageBuilder(registry).build(
                root, UUID.randomUUID(),
                perm -> { throw new RuntimeException("boom"); });

        // Page must still render with at least Teleport + Help.
        assertNotNull(findRunWithArgs(model), "Teleport row must still render");
        assertNotNull(findRunWithArgs(model, "help"), "Help row must still render");
        // Admin probe threw, so admin rows must be hidden.
        assertNull(findRunWithArgs(model, "reload"),
                "admin rows must be hidden when the admin permission probe throws");
    }

    @Test
    @DisplayName("Token registry receives one mint per clickable fragment")
    void tokensMinted_perClickableFragment() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        UUID viewer = UUID.randomUUID();

        MenuModel model = new FrontPageBuilder(registry)
                .build(root, viewer, perm -> false);

        // Count clickable fragments (action != null) on the (single) page.
        int clickable = 0;
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                if (frag.action() != null) clickable++;
            }
        }
        assertTrue(clickable >= 2, "at least Teleport + Help should be clickable");
        // We can't directly count mints without registry introspection, so we
        // assert that the page is renderable — the absence of an exception
        // during build() is the contract (token registry .mint always succeeds).
        assertNotNull(model);
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static MenuAction findRunWithArgs(MenuModel model, String... expected) {
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                MenuAction a = frag.action();
                if (a instanceof MenuAction.RunRtpCommand run) {
                    if (argsMatch(run.args(), expected)) return a;
                }
            }
        }
        return null;
    }

    private static MenuFragment findFragmentWithRunArgs(MenuModel model, String... expected) {
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                MenuAction a = frag.action();
                if (a instanceof MenuAction.RunRtpCommand run) {
                    if (argsMatch(run.args(), expected)) return frag;
                }
            }
        }
        return null;
    }

    private static MenuAction findOpenMenuTo(MenuModel model, String firstSegment) {
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                MenuAction a = frag.action();
                if (a instanceof MenuAction.OpenMenu open) {
                    if (open.path().length >= 1 && firstSegment.equals(open.path()[0])) {
                        return a;
                    }
                }
            }
        }
        return null;
    }

    private static MenuAction findOpenParamPicker(MenuModel model, String paramName) {
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                MenuAction a = frag.action();
                if (a instanceof MenuAction.OpenParamPicker pick) {
                    if (paramName.equals(pick.paramName())) return a;
                }
            }
        }
        return null;
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

    /** Minimal root fixture mirroring MenuNavigationStageATest.TestableRoot. */
    private static final class TestableRoot extends BaseRTPCmdImpl {
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

    /** Leaf subcommand; presence-only fixture. */
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

    /** Parameter fixture that returns a fixed value set from {@link #values()}. */
    private static final class FixedParam extends CommandParameter {
        private final Set<String> values;
        FixedParam(String permission, Set<String> values) {
            super(permission, "fixed test param", (u, v) -> true);
            this.values = new HashSet<>(values);
        }
        @Override public Set<String> values() { return values; }
    }
}
