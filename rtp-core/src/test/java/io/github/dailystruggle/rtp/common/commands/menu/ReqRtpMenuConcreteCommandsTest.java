package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-050 Stage 1a (Proposed 2026-05-24) - concrete-command leaves coexist
 * with the token-redeem path. This suite asserts:
 *
 * <ol>
 *   <li><b>Registration</b>: {@code /rtp menu open/admin/front/visualizations}
 *       and {@code /rtp visualization} are registered as expected after the
 *       {@link MenuRedeemSubcommand} constructor runs.</li>
 *   <li><b>Routing</b>: each leaf's {@code onCommand} invocation drives the
 *       matching {@code dispatch*} helper end-to-end through the renderer
 *       (no token mint / consume involved).</li>
 *   <li><b>Path-parameter parsing</b>: the {@code open} leaf splits dotted
 *       paths on {@code '.'} and forwards the array to
 *       {@link MenuAction.OpenMenu}.</li>
 *   <li><b>Permission gating</b>: the {@code admin} and {@code visualizations}
 *       leaves still respect the {@code rtp.menu.admin} gate inside their
 *       shared {@code dispatchOpen*} helpers (S-004 reject path).</li>
 * </ol>
 *
 * <p>This test is the REQ-RTP-F-013 regression guard for the new
 * concrete-command surface (configurable rejection messages still apply on
 * the new leaves because the underlying helpers are unchanged).
 */
@DisplayName("ADR-050 Stage 1a: concrete `/rtp menu ...` and `/rtp visualization` leaves")
class ReqRtpMenuConcreteCommandsTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setupRTP() {
        RTPTestSetup.install(tempDir.toFile());
    }

    // ------------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("Registration")
    class Registration {

        @Test
        @DisplayName("`menu` subcommand exposes open / admin / front / visualizations leaves")
        void menuSubleavesRegistered() {
            Fixture f = Fixture.withAllPermissions();

            Map<String, CommandsAPICommand> lookup = f.redeem.getCommandLookup();
            assertNotNull(lookup, "menu subcommand lookup");

            assertTrue(lookup.containsKey("OPEN"),
                    "/rtp menu open must be registered under MenuRedeemSubcommand");
            assertTrue(lookup.containsKey("ADMIN"),
                    "/rtp menu admin must be registered");
            assertTrue(lookup.containsKey("FRONT"),
                    "/rtp menu front must be registered");
            assertTrue(lookup.containsKey("VISUALIZATIONS"),
                    "/rtp menu visualizations must be registered");
        }

        @Test
        @DisplayName("`visualization` registers as a sibling of `menu` under /rtp root")
        void visualizationRegisteredAsRootSibling() {
            Fixture f = Fixture.withAllPermissions();

            Map<String, CommandsAPICommand> rootLookup = f.root.getCommandLookup();
            assertNotNull(rootLookup, "rtpRoot lookup");
            assertTrue(rootLookup.containsKey("VISUALIZATION"),
                    "/rtp visualization must be registered as a root sibling");

            CommandsAPICommand visualization = rootLookup.get("VISUALIZATION");
            assertEquals("visualization", visualization.name());
            assertEquals(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION,
                    visualization.permission(),
                    "/rtp visualization shares the rtp.menu.admin gate of /rtp menu visualizations");
        }

        @Test
        @DisplayName("leaf classes report correct name() and permission()")
        void leafIdentities() {
            Fixture f = Fixture.withAllPermissions();
            Map<String, CommandsAPICommand> lookup = f.redeem.getCommandLookup();

            assertEquals(MenuRedeemSubcommand.PERMISSION,
                    lookup.get("OPEN").permission(),
                    "/rtp menu open inherits rtp.menu");
            assertEquals(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION,
                    lookup.get("ADMIN").permission(),
                    "/rtp menu admin gates on rtp.menu.admin");
            assertEquals(MenuRedeemSubcommand.PERMISSION,
                    lookup.get("FRONT").permission(),
                    "/rtp menu front is open to any menu viewer");
            assertEquals(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION,
                    lookup.get("VISUALIZATIONS").permission(),
                    "/rtp menu visualizations gates on rtp.menu.admin");
        }
    }

    // ------------------------------------------------------------------------
    // Stage 1b Registration - the remaining concrete leaves
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("RegistrationStage1b (picker, page, stage, unstage, apply, discard, multi)")
    class RegistrationStage1b {

        @Test
        @DisplayName("All Stage 1b leaves register under /rtp menu")
        void stage1bLeavesRegistered() {
            Fixture f = Fixture.withAllPermissions();
            Map<String, CommandsAPICommand> lookup = f.redeem.getCommandLookup();
            assertNotNull(lookup);

            assertTrue(lookup.containsKey("PICKER"), "/rtp menu picker");
            assertTrue(lookup.containsKey("PAGE"), "/rtp menu page");
            assertTrue(lookup.containsKey("STAGE"), "/rtp menu stage");
            assertTrue(lookup.containsKey("UNSTAGE"), "/rtp menu unstage");
            assertTrue(lookup.containsKey("APPLY"), "/rtp menu apply");
            assertTrue(lookup.containsKey("DISCARD"), "/rtp menu discard");
            assertTrue(lookup.containsKey("MULTI"), "/rtp menu multi");
        }

        @Test
        @DisplayName("Stage 1b leaves report correct name()")
        void stage1bLeafNames() {
            Fixture f = Fixture.withAllPermissions();
            Map<String, CommandsAPICommand> lookup = f.redeem.getCommandLookup();
            assertEquals("picker", lookup.get("PICKER").name());
            assertEquals("page", lookup.get("PAGE").name());
            assertEquals("stage", lookup.get("STAGE").name());
            assertEquals("unstage", lookup.get("UNSTAGE").name());
            assertEquals("apply", lookup.get("APPLY").name());
            assertEquals("discard", lookup.get("DISCARD").name());
            assertEquals("multi", lookup.get("MULTI").name());
        }

        @Test
        @DisplayName("Stage 1b leaves all gate on the base rtp.menu permission")
        void stage1bLeafPermissions() {
            Fixture f = Fixture.withAllPermissions();
            Map<String, CommandsAPICommand> lookup = f.redeem.getCommandLookup();
            for (String key : List.of("PICKER", "PAGE", "STAGE", "UNSTAGE",
                    "APPLY", "DISCARD", "MULTI")) {
                assertEquals(MenuRedeemSubcommand.PERMISSION,
                        lookup.get(key).permission(),
                        key + " gates on rtp.menu (deeper permissions enforced "
                                + "inside the dispatch helper)");
            }
        }

        @Test
        @DisplayName("`/rtp menu picker` rejects with menuInvalid when 'param' is missing")
        void pickerRejectsMissingParam() {
            Fixture f = Fixture.withAllPermissions();
            CommandsAPICommand leaf = f.redeem.getCommandLookup().get("PICKER");
            UUID viewer = UUID.randomUUID();
            boolean ok = leaf.onCommand(viewer, new HashMap<>(), null,
                    (Consumer<String>) f.messages::add);
            assertFalse(ok, "missing 'param' must not reach dispatchOpenParamPicker");
        }

        @Test
        @DisplayName("`/rtp menu apply` rejects with menuInvalid when 'file' is missing")
        void applyRejectsMissingFile() {
            Fixture f = Fixture.withAllPermissions();
            CommandsAPICommand leaf = f.redeem.getCommandLookup().get("APPLY");
            UUID viewer = UUID.randomUUID();
            boolean ok = leaf.onCommand(viewer, new HashMap<>(), null,
                    (Consumer<String>) f.messages::add);
            assertFalse(ok, "missing 'file' must not reach dispatchApplyStagedConfig");
        }

        @Test
        @DisplayName("`/rtp menu multi` rejects when 'kind' is missing")
        void multiRejectsMissingKind() {
            Fixture f = Fixture.withAllPermissions();
            CommandsAPICommand leaf = f.redeem.getCommandLookup().get("MULTI");
            UUID viewer = UUID.randomUUID();
            boolean ok = leaf.onCommand(viewer, new HashMap<>(), null,
                    (Consumer<String>) f.messages::add);
            assertFalse(ok, "missing 'kind' must not reach the multi-config dispatchers");
        }
    }

    // ------------------------------------------------------------------------
    // Routing - each leaf reaches its dispatch* helper and renders a model
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("Routing (concrete-command -> dispatch* helper -> renderer)")
    class Routing {

        @Test
        @DisplayName("/rtp menu admin renders the curated admin-panel model")
        void adminRouting() {
            Fixture f = Fixture.withAllPermissions();

            CommandsAPICommand leaf = f.redeem.getCommandLookup().get("ADMIN");
            assertNotNull(leaf);

            UUID viewer = UUID.randomUUID();
            boolean ok = leaf.onCommand(viewer, new HashMap<>(), null,
                    (Consumer<String>) f.messages::add);

            assertTrue(ok, "/rtp menu admin happy path returns true");
            assertNotNull(f.rendered.get(), "renderer must receive a model");
            assertEquals("admin-panel", f.rendered.get().title());
        }

        @Test
        @DisplayName("/rtp menu front renders the curated front-page model (no permission needed)")
        void frontRouting() {
            Fixture f = Fixture.withAllPermissions();

            CommandsAPICommand leaf = f.redeem.getCommandLookup().get("FRONT");
            UUID viewer = UUID.randomUUID();
            boolean ok = leaf.onCommand(viewer, new HashMap<>(), null,
                    (Consumer<String>) f.messages::add);

            assertTrue(ok, "/rtp menu front happy path returns true");
            assertEquals("front-page", f.rendered.get().title());
        }

        @Test
        @DisplayName("/rtp menu visualizations renders the visualizations selector model")
        void visualizationsRouting() {
            Fixture f = Fixture.withAllPermissions();

            CommandsAPICommand leaf = f.redeem.getCommandLookup().get("VISUALIZATIONS");
            UUID viewer = UUID.randomUUID();
            boolean ok = leaf.onCommand(viewer, new HashMap<>(), null,
                    (Consumer<String>) f.messages::add);

            assertTrue(ok);
            assertEquals("visualizations", f.rendered.get().title());
        }

        @Test
        @DisplayName("/rtp visualization (root sibling) renders the visualizations selector model")
        void visualizationRootRouting() {
            Fixture f = Fixture.withAllPermissions();

            CommandsAPICommand leaf = f.root.getCommandLookup().get("VISUALIZATION");
            UUID viewer = UUID.randomUUID();
            boolean ok = leaf.onCommand(viewer, new HashMap<>(), null,
                    (Consumer<String>) f.messages::add);

            assertTrue(ok);
            assertEquals("visualizations", f.rendered.get().title(),
                    "root /rtp visualization shares the menu visualizations selector model");
        }

        @Test
        @DisplayName("/rtp menu open with empty path opens the root menu page")
        void openRouting_emptyPath() {
            Fixture f = Fixture.withAllPermissions();

            CommandsAPICommand leaf = f.redeem.getCommandLookup().get("OPEN");
            UUID viewer = UUID.randomUUID();
            boolean ok = leaf.onCommand(viewer, new HashMap<>(), null,
                    (Consumer<String>) f.messages::add);

            assertTrue(ok);
            // The empty-path branch walks no segments and renders rtpRoot
            // through renderAt; the test fixture's pageBuilder echoes the
            // node name as the model title.
            assertNotNull(f.rendered.get());
            assertEquals("rtp", f.rendered.get().title(),
                    "empty path resolves to the rtp root node");
        }

        @Test
        @DisplayName("/rtp menu open path=config walks into the config subtree")
        void openRouting_singleSegment() {
            Fixture f = Fixture.withAllPermissions();

            CommandsAPICommand leaf = f.redeem.getCommandLookup().get("OPEN");
            UUID viewer = UUID.randomUUID();
            Map<String, List<String>> params = new HashMap<>();
            params.put(MenuConcreteCommandLeaves.PARAM_PATH, List.of("config"));
            boolean ok = leaf.onCommand(viewer, params, null,
                    (Consumer<String>) f.messages::add);

            assertTrue(ok, "/rtp menu open path=config happy path");
            assertEquals("config", f.rendered.get().title(),
                    "single-segment path resolves into the config child");
        }

        @Test
        @DisplayName("/rtp menu open path=does.not.exist rejects with menuInvalid")
        void openRouting_unknownPath() {
            Fixture f = Fixture.withAllPermissions();

            CommandsAPICommand leaf = f.redeem.getCommandLookup().get("OPEN");
            UUID viewer = UUID.randomUUID();
            Map<String, List<String>> params = new HashMap<>();
            params.put(MenuConcreteCommandLeaves.PARAM_PATH, List.of("does.not.exist"));
            boolean ok = leaf.onCommand(viewer, params, null,
                    (Consumer<String>) f.messages::add);

            assertFalse(ok, "unknown-path branch must reject");
            assertFalse(f.messages.isEmpty(),
                    "S-004 requires a configurable reject message to surface");
        }
    }

    // ------------------------------------------------------------------------
    // Permission gating - leaves still defer to the dispatch* helpers
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("Permission gating (helper-level, S-004)")
    class Permissions {

        @Test
        @DisplayName("/rtp menu admin rejects when caller lacks rtp.menu.admin")
        void adminRejectsWithoutPermission() {
            Fixture f = Fixture.withAdminPermission(false);

            CommandsAPICommand leaf = f.redeem.getCommandLookup().get("ADMIN");
            boolean ok = leaf.onCommand(UUID.randomUUID(), new HashMap<>(), null,
                    (Consumer<String>) f.messages::add);

            assertFalse(ok, "missing rtp.menu.admin must reject");
            assertFalse(f.messages.isEmpty(),
                    "configurable rejection message must surface (REQ-RTP-F-013)");
        }

        @Test
        @DisplayName("/rtp menu visualizations rejects when caller lacks rtp.menu.admin")
        void visualizationsRejectsWithoutPermission() {
            Fixture f = Fixture.withAdminPermission(false);

            CommandsAPICommand leaf = f.redeem.getCommandLookup().get("VISUALIZATIONS");
            boolean ok = leaf.onCommand(UUID.randomUUID(), new HashMap<>(), null,
                    (Consumer<String>) f.messages::add);

            assertFalse(ok);
            assertFalse(f.messages.isEmpty());
        }
    }

    // ------------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------------

    /**
     * Minimal wiring of {@link MenuRedeemSubcommand} with a mock renderer
     * and a mock page-builder / curated-page-builder / config-subtree
     * builder. The renderer simply captures the most recently presented
     * {@link MenuModel} into {@link #rendered}. The page-builder fabricates
     * a model whose title is the resolved node's {@link TreeCommand#name()}
     * so routing assertions can inspect "which node did we end up on".
     */
    static final class Fixture {

        final TestableRoot root;
        final MenuRedeemSubcommand redeem;
        final AtomicReference<MenuModel> rendered = new AtomicReference<>();
        final List<String> messages = new ArrayList<>();

        private Fixture(boolean grantAdmin) {
            this.root = new TestableRoot();
            MenuRenderer renderer = (playerId, model) -> rendered.set(model);
            MenuRedeemSubcommand.MenuPageBuilder pageBuilder =
                    (node, open, assembled) -> stubModel(node.name());
            MenuRedeemSubcommand.MenuCuratedPageBuilder curated =
                    new MenuRedeemSubcommand.MenuCuratedPageBuilder() {
                        @Override
                        public MenuModel buildAdminPanel(UUID viewer) {
                            return stubModel("admin-panel");
                        }
                        @Override
                        public MenuModel buildFrontPage(UUID viewer) {
                            return stubModel("front-page");
                        }
                        @Override
                        public MenuModel buildVisualizations(UUID viewer) {
                            return stubModel("visualizations");
                        }
                    };
            Predicate<String> probe = perm -> {
                if (perm == null) return true;
                if (perm.equals(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION)) return grantAdmin;
                return true;
            };
            // ADR-050 Stage 3beta.D.2 (2026-05-24): ctor no longer takes
            // MenuTokenRegistry; the curated-page-builder is the 8th arg.
            this.redeem = new MenuRedeemSubcommand(root,
                    uuid -> probe, renderer, pageBuilder, null, null, null, curated);
        }

        static Fixture withAllPermissions() {
            return new Fixture(true);
        }

        static Fixture withAdminPermission(boolean grant) {
            return new Fixture(grant);
        }

        private static MenuModel stubModel(String title) {
            return new MenuModel(title, List.of(new MenuPage(Collections.emptyList())));
        }
    }

    /**
     * Minimal /rtp root stub - same shape as the existing
     * MenuConfigSubtreeDispatchTest fixture but only carries enough state
     * for the new concrete-command leaves to register and route. A single
     * {@code config} child stub is installed so the
     * {@code OpenMenuConcreteCmd}'s dotted-path splitter resolves the
     * "config" segment into a {@link TreeCommand} under {@code dispatchOpen}.
     * The dotted-path splitter itself is package-private inside
     * {@link MenuConcreteCommandLeaves} and not part of the public surface.
     */
    static final class TestableRoot extends BaseRTPCmdImpl {
        TestableRoot() {
            super(null);
            this.addSubCommand(new TreeCommandStub("config"));
        }
        @Override public String name() { return "rtp"; }
        @Override public String permission() { return "rtp.use"; }
        @Override public boolean onCommand(UUID callerId,
                                           Map<String, List<String>> parameterValues,
                                           CommandsAPICommand nextCommand) {
            return true;
        }
        @Override public CompletableFuture<Boolean> onCommand(UUID callerId,
                                                              Predicate<String> permissionCheckMethod,
                                                              Consumer<String> messageMethod,
                                                              String[] args,
                                                              int i,
                                                              Map<String, CommandParameter> tempParameters) {
            return CompletableFuture.completedFuture(true);
        }
    }

    /** Inert TreeCommand stub for subtree-lookup wiring. */
    static final class TreeCommandStub extends BaseRTPCmdImpl {
        private final String n;
        TreeCommandStub(String n) {
            super(null);
            this.n = n;
        }
        @Override public String name() { return n; }
        @Override public String permission() { return "rtp.use"; }
        @Override public boolean onCommand(UUID callerId,
                                           Map<String, List<String>> parameterValues,
                                           CommandsAPICommand nextCommand) {
            return true;
        }
        @Override public CompletableFuture<Boolean> onCommand(UUID callerId,
                                                              Predicate<String> permissionCheckMethod,
                                                              Consumer<String> messageMethod,
                                                              String[] args,
                                                              int i,
                                                              Map<String, CommandParameter> tempParameters) {
            return CompletableFuture.completedFuture(true);
        }
    }
}
