package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPOSAL-config-view-as-book.md v3.7 / CHECKLIST step 5 — redeem-dispatch
 * coverage for the three curated config-subtree {@link MenuAction} variants
 * ({@link MenuAction.OpenConfigSelector} / {@link MenuAction.OpenConfigFile} /
 * {@link MenuAction.OpenConfigKey}).
 *
 * <p>Each arm shares the same shape: permission gate
 * ({@link MenuRedeemSubcommand#CONFIG_VIEW_PERMISSION}), then a call into
 * {@link MenuRedeemSubcommand.MenuConfigSubtreeBuilder}, then the
 * {@link MenuRenderer}. All failure paths are S-004 (reject + WARN).
 *
 * <p>The {@link MenuAction.OpenConfigKey} arm was reshaped 2026-05-21 to
 * short-circuit the param-picker step and open the anvil directly in
 * {@link MenuAction.Mode#STAGE} mode (see
 * {@link #openConfigKey_happyPath()}). The pre-2026-05-21 post-write
 * rebuild contract test against {@code buildKey} was retired with the
 * param-picker.
 */
@DisplayName("PROPOSAL-config-view-as-book v3.7 § config subtree dispatch (step 5)")
class MenuConfigSubtreeDispatchTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setupRTP() {
        RTPTestSetup.install(tempDir.toFile());
    }

    // ------------------------------------------------------------------------
    // Happy paths
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("OpenConfigSelector redeem: permission + builder + renderer happy path")
    void openConfigSelector_happyPath() {
        Fixture f = Fixture.withPermission(true);

        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer, new MenuAction.OpenConfigSelector(),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);

        assertTrue(ok, "valid OpenConfigSelector redeem must succeed");
        assertNotNull(f.rendered.get(), "renderer must receive a model");
        assertEquals("selector", f.rendered.get().title());
    }

    @Test
    @DisplayName("OpenConfigFile redeem: walks fileName into builder, renders returned model")
    void openConfigFile_happyPath() {
        Fixture f = Fixture.withPermission(true);

        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.OpenConfigFile("performance.yml"),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);

        assertTrue(ok, "valid OpenConfigFile redeem must succeed");
        assertNotNull(f.rendered.get());
        assertEquals("file:performance.yml", f.rendered.get().title());
        assertEquals("performance.yml", f.lastFile.get());
    }

    @Test
    @DisplayName("OpenConfigKey redeem: skips param-picker and opens the anvil directly in STAGE mode")
    void openConfigKey_happyPath() {
        // Updated 2026-05-21: clicking a config key no longer renders an
        // intermediate per-key value picker page. The dispatch arm now
        // short-circuits to the AnvilInputOpener in STAGE mode so the
        // operator types the new value straight away; on confirm the
        // platform opener reopens /rtp config <file> with the staged
        // entry surfaced at the top of the Pending list.
        Fixture f = Fixture.withPermission(true);

        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.OpenConfigKey("performance.yml", "threadCount"),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);

        assertTrue(ok, "OpenConfigKey must open the anvil directly");
        assertNull(f.rendered.get(),
                "renderer must NOT be invoked: param-picker step is removed");
        assertNull(f.lastFile.get(),
                "buildKey must NOT be called: the param-picker step is removed");
        assertEquals(1, f.anvilOpens.size(),
                "exactly one anvil-open invocation expected");
        AnvilOpenCapture cap = f.anvilOpens.get(0);
        assertEquals(viewer, cap.viewer);
        assertEquals(List.of("config", "performance.yml"), cap.parentPath);
        assertEquals("threadCount", cap.paramName);
        assertEquals("", cap.prefill);
        assertEquals(MenuAction.Mode.STAGE, cap.mode,
                "STAGE mode required so the typed value is staged in the cart, not run");
    }

    // ------------------------------------------------------------------------
    // Permission denial (rtp.config.view) — all three arms (S-004)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("OpenConfigSelector rejects with menuInvalid when sender lacks rtp.config.view")
    void openConfigSelector_permissionDenied_rejects() {
        Fixture f = Fixture.withPermission(false);

        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer, new MenuAction.OpenConfigSelector(),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);

        assertFalse(ok, "missing rtp.config.view must reject (S-004)");
        assertFalse(f.messages.isEmpty(),
                "rejection must surface a configurable message (REQ-RTP-F-013)");
        assertNull(f.rendered.get(), "renderer must not be invoked on denial");
    }

    @Test
    @DisplayName("OpenConfigFile rejects with menuInvalid when sender lacks rtp.config.view")
    void openConfigFile_permissionDenied_rejects() {
        Fixture f = Fixture.withPermission(false);

        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.OpenConfigFile("performance.yml"),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);

        assertFalse(ok);
        assertFalse(f.messages.isEmpty());
        assertNull(f.rendered.get());
    }

    @Test
    @DisplayName("OpenConfigKey rejects with menuInvalid when sender lacks rtp.config.view")
    void openConfigKey_permissionDenied_rejects() {
        Fixture f = Fixture.withPermission(false);

        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.OpenConfigKey("performance.yml", "threadCount"),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);

        assertFalse(ok);
        assertFalse(f.messages.isEmpty());
        assertNull(f.rendered.get());
    }

    // ------------------------------------------------------------------------
    // Builder unwired (S-004): pre-v3.7 6-arg constructor leaves
    // configSubtreeBuilder == null. Inbound OpenConfig* must reject + WARN.
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("OpenConfigSelector rejects when config-subtree builder is unwired")
    void openConfigSelector_builderDisabled_rejects() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        AtomicReference<MenuModel> rendered = new AtomicReference<>();
        MenuRenderer renderer = (uuid, m) -> rendered.set(m);
        // Stage A.2 6-arg constructor: configSubtreeBuilder defaults to null.
        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(root, registry,
                uuid -> perm -> true,
                renderer,
                (node, open, assembled) -> new MenuModel("p",
                        List.of(new MenuPage(List.of()))),
                null);

        UUID viewer = UUID.randomUUID();
        String token = registry.mint(viewer, new MenuAction.OpenConfigSelector(),
                Duration.ofSeconds(30));
        Map<String, List<String>> params = new HashMap<>();
        params.put(MenuRedeemSubcommand.PARAM_TOKEN, List.of(token));

        List<String> msgs = new ArrayList<>();
        boolean ok = redeem.onCommand(viewer, params, null, (Consumer<String>) msgs::add);

        assertFalse(ok, "OpenConfigSelector must reject when builder is unwired");
        assertFalse(msgs.isEmpty());
        assertNull(rendered.get());
    }

    // ------------------------------------------------------------------------
    // Builder returns null → unknown file / unknown (file, key) (S-004)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("OpenConfigFile rejects when builder returns null (unknown file)")
    void openConfigFile_unknownFile_rejects() {
        Fixture f = Fixture.withPermission(true);
        f.fileBuilderReturnsNullFor = "ghost.yml";

        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.OpenConfigFile("ghost.yml"),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);

        assertFalse(ok, "unknown file must reject (S-004)");
        assertFalse(f.messages.isEmpty());
        assertNull(f.rendered.get());
    }

    @Test
    @DisplayName("OpenConfigKey rejects with S-004 when the file is unknown to the live config subtree")
    void openConfigKey_unknownFile_rejects() {
        // Updated 2026-05-21: rejection is now driven by the live
        // /rtp config <file> subtree lookup (since the param-picker step
        // is removed), not by a builder-returns-null path.
        Fixture f = Fixture.withPermission(true);

        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.OpenConfigKey("ghost.yml", "ghostParam"),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);

        assertFalse(ok, "unknown config file must reject (S-004)");
        assertFalse(f.messages.isEmpty(),
                "rejection must surface a configurable message (REQ-RTP-F-013)");
        assertTrue(f.anvilOpens.isEmpty(),
                "anvil opener must not be invoked for an unknown file");
    }

    @Test
    @DisplayName("OpenConfigKey rejects when anvil-input opener is unwired")
    void openConfigKey_anvilDisabled_rejects() {
        // Updated 2026-05-21: with the param-picker step removed, the
        // dispatch arm now depends on the AnvilInputOpener (not the
        // subtree builder). A null opener is the new S-004 reject path.
        Fixture f = Fixture.withPermission(true).withoutAnvilOpener();

        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.OpenConfigKey("performance.yml", "threadCount"),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);

        assertFalse(ok, "OpenConfigKey must reject when anvil-input is unwired");
        assertFalse(f.messages.isEmpty());
    }

    // ========================================================================
    // Test fixture: wires a MenuRedeemSubcommand with the 8-arg constructor.
    // ========================================================================

    /** Capture record for {@link MenuRedeemSubcommand.AnvilInputOpener} invocations. */
    static final class AnvilOpenCapture {
        final UUID viewer;
        final List<String> parentPath;
        final String paramName;
        final String prefill;
        final MenuAction.Mode mode;
        AnvilOpenCapture(UUID viewer, List<String> parentPath, String paramName,
                         String prefill, MenuAction.Mode mode) {
            this.viewer = viewer;
            this.parentPath = parentPath;
            this.paramName = paramName;
            this.prefill = prefill;
            this.mode = mode;
        }
    }

    private static final class Fixture {
        final LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        final TestableRoot root = new TestableRoot();
        final AtomicReference<MenuModel> rendered = new AtomicReference<>();
        final AtomicReference<String> lastFile = new AtomicReference<>();
        final AtomicReference<String> lastParam = new AtomicReference<>();
        final AtomicReference<String> simulatedValue = new AtomicReference<>("(unset)");
        final List<String> messages = new ArrayList<>();
        final List<AnvilOpenCapture> anvilOpens = new ArrayList<>();
        MenuRedeemSubcommand redeem;
        final boolean grantConfigView;

        String fileBuilderReturnsNullFor = null;
        boolean keyBuilderReturnsNull = false;

        private Fixture(boolean grantConfigView) {
            this.grantConfigView = grantConfigView;
            rewire(true);
        }

        /**
         * Rebuild the redeem subcommand. When {@code withAnvilOpener} is
         * false the opener slot is null - exercises the new "anvil-input
         * disabled" reject path for OpenConfigKey post 2026-05-21.
         */
        private void rewire(boolean withAnvilOpener) {
            MenuRenderer renderer = (uuid, m) -> rendered.set(m);
            MenuRedeemSubcommand.MenuPageBuilder pageBuilder =
                    (node, open, assembled) -> new MenuModel("p",
                            List.of(new MenuPage(List.of())));
            MenuRedeemSubcommand.MenuConfigSubtreeBuilder subtree =
                    new MenuRedeemSubcommand.MenuConfigSubtreeBuilder() {
                        @Override
                        public MenuModel buildSelector(UUID viewer) {
                            return new MenuModel("selector",
                                    List.of(new MenuPage(List.of())));
                        }
                        @Override
                        public MenuModel buildFile(UUID viewer, String fileName) {
                            lastFile.set(fileName);
                            if (fileName.equals(fileBuilderReturnsNullFor)) return null;
                            return new MenuModel("file:" + fileName,
                                    List.of(new MenuPage(List.of())));
                        }
                        @Override
                        public MenuModel buildKey(UUID viewer, String fileName,
                                                  String paramName) {
                            // Updated 2026-05-21: buildKey is no longer
                            // invoked by the OpenConfigKey dispatch arm
                            // (the param-picker step is removed). Recording
                            // here lets the happy-path test assert that it
                            // is NOT called.
                            lastFile.set(fileName);
                            lastParam.set(paramName);
                            if (keyBuilderReturnsNull) return null;
                            return new MenuModel(
                                    "key:" + fileName + ":" + paramName,
                                    List.of(new MenuPage(List.of())));
                        }
                    };
            MenuRedeemSubcommand.AnvilInputOpener opener = !withAnvilOpener ? null :
                    new MenuRedeemSubcommand.AnvilInputOpener() {
                        @Override
                        public boolean open(UUID viewer, List<String> parentPath,
                                            String paramName, String prefill) {
                            // RUN-fallback path - test always exercises the
                            // mode-aware 6-arg overload below, but we honour
                            // the SAM contract by recording anyway.
                            anvilOpens.add(new AnvilOpenCapture(viewer,
                                    parentPath, paramName, prefill,
                                    MenuAction.Mode.RUN));
                            return true;
                        }
                        @Override
                        public boolean open(UUID viewer, List<String> parentPath,
                                            String paramName, String prefill,
                                            MenuAction.Mode mode,
                                            MenuRedeemSubcommand.CartSink sink) {
                            anvilOpens.add(new AnvilOpenCapture(viewer,
                                    parentPath, paramName, prefill, mode));
                            return true;
                        }
                    };
            Predicate<String> probe = perm ->
                    grantConfigView || !perm.equals(MenuRedeemSubcommand.CONFIG_VIEW_PERMISSION);
            this.redeem = new MenuRedeemSubcommand(root, registry,
                    uuid -> probe, renderer, pageBuilder, null, opener, subtree);
        }

        static Fixture withPermission(boolean grant) {
            return new Fixture(grant);
        }

        /**
         * Rebuild without an AnvilInputOpener. Used by
         * {@link #openConfigKey_anvilDisabled_rejects()} to assert that
         * the OpenConfigKey dispatch arm is now opener-gated.
         */
        Fixture withoutAnvilOpener() {
            rewire(false);
            return this;
        }

        boolean redeem(UUID viewer, String token) {
            Map<String, List<String>> params = new HashMap<>();
            params.put(MenuRedeemSubcommand.PARAM_TOKEN, List.of(token));
            return redeem.onCommand(viewer, params, null, (Consumer<String>) messages::add);
        }
    }

    /**
     * Minimal /rtp root stub - same shape MenuParamPickerStageA2Test uses,
     * but pre-populated with a {@code config} subtree containing a
     * {@code performance.yml} child so the OpenConfigKey dispatch arm's
     * live-subtree lookup
     * ({@code rtpRoot.getCommandLookup().get("CONFIG")} -> child
     * {@code "PERFORMANCE.YML"}) resolves under test.
     */
    static final class TestableRoot extends BaseRTPCmdImpl {
        TestableRoot() {
            super(null);
            // Install /rtp config <performance.yml> stubs so the live
            // subtree lookup in dispatchOpenConfigKey resolves. The
            // children are intentionally inert TreeCommand stubs; the
            // dispatch arm only needs `instanceof TreeCommand` and the
            // lookup-map entry, not a real onCommand implementation.
            TreeCommandStub configCmd = new TreeCommandStub("config", "rtp.config.view");
            TreeCommandStub perfCmd = new TreeCommandStub("performance.yml", "rtp.config.view");
            // dispatchPromptAnvilInput resolves the parameter under the
            // last path segment's parameter-lookup before opening the
            // anvil; supply at least one accepted parameter so the
            // happy-path test does not trip the "unknown parameter"
            // S-004 reject path.
            perfCmd.getParameterLookup().put("threadCount",
                    new CommandParameter("", "thread count", (u, v) -> true) {
                        @Override
                        public java.util.Set<String> values() {
                            return java.util.Collections.emptySet();
                        }
                    });
            configCmd.addSubCommand(perfCmd);
            this.addSubCommand(configCmd);
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
        private final String name;
        private final String permission;
        TreeCommandStub(String name, String permission) {
            super(null);
            this.name = name;
            this.permission = permission;
        }
        @Override public String name() { return name; }
        @Override public String permission() { return permission; }
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
