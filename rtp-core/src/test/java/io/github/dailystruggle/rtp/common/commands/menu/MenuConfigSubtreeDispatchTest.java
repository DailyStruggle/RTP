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
 * <p>The post-write rebuild contract (v3.6.3) is exercised by
 * {@link #rebuildAfterWrite_reflectsNewValue()}: the builder reads a mutable
 * source on each invocation; we mutate it between redeems and assert that the
 * second redeem renders the new value.
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
    @DisplayName("OpenConfigKey redeem: passes both fileName and paramName to builder")
    void openConfigKey_happyPath() {
        Fixture f = Fixture.withPermission(true);

        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.OpenConfigKey("performance.yml", "threadCount"),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);

        assertTrue(ok);
        // Default simulatedValue is "(unset)"; the builder appends "=<live>".
        assertEquals("key:performance.yml:threadCount=(unset)", f.rendered.get().title());
        assertEquals("performance.yml", f.lastFile.get());
        assertEquals("threadCount", f.lastParam.get());
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
    @DisplayName("OpenConfigKey rejects when builder returns null (unknown param)")
    void openConfigKey_unknownPair_rejects() {
        Fixture f = Fixture.withPermission(true);
        f.keyBuilderReturnsNull = true;

        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.OpenConfigKey("performance.yml", "ghostParam"),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);

        assertFalse(ok);
        assertFalse(f.messages.isEmpty());
    }

    // ------------------------------------------------------------------------
    // Post-write rebuild contract (v3.6.3): the builder reads a mutable
    // source on each invocation. Mutating the source between redeems and
    // re-issuing the same logical action must produce a fresh model
    // reflecting the new state.
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("post-write rebuild: second OpenConfigKey redeem surfaces the mutated value")
    void rebuildAfterWrite_reflectsNewValue() {
        Fixture f = Fixture.withPermission(true);

        UUID viewer = UUID.randomUUID();

        // First redeem — value is "alpha".
        f.simulatedValue.set("alpha");
        String t1 = f.registry.mint(viewer,
                new MenuAction.OpenConfigKey("performance.yml", "threadCount"),
                Duration.ofSeconds(30));
        assertTrue(f.redeem(viewer, t1));
        assertEquals("key:performance.yml:threadCount=alpha", f.rendered.get().title());

        // Simulate the user clicking a value row → ConfigParser write happens
        // somewhere downstream → the menu redeem is re-issued (a fresh token,
        // same logical action). The builder must observe the new live value.
        f.rendered.set(null);
        f.simulatedValue.set("bravo");
        String t2 = f.registry.mint(viewer,
                new MenuAction.OpenConfigKey("performance.yml", "threadCount"),
                Duration.ofSeconds(30));
        assertTrue(f.redeem(viewer, t2));
        assertEquals("key:performance.yml:threadCount=bravo", f.rendered.get().title(),
                "v3.6.3 rebuild contract: second redeem must reflect new value");
    }

    // ========================================================================
    // Test fixture: wires a MenuRedeemSubcommand with the 8-arg constructor.
    // ========================================================================

    private static final class Fixture {
        final LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        final TestableRoot root = new TestableRoot();
        final AtomicReference<MenuModel> rendered = new AtomicReference<>();
        final AtomicReference<String> lastFile = new AtomicReference<>();
        final AtomicReference<String> lastParam = new AtomicReference<>();
        final AtomicReference<String> simulatedValue = new AtomicReference<>("(unset)");
        final List<String> messages = new ArrayList<>();
        final MenuRedeemSubcommand redeem;

        String fileBuilderReturnsNullFor = null;
        boolean keyBuilderReturnsNull = false;

        private Fixture(boolean grantConfigView) {
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
                            lastFile.set(fileName);
                            lastParam.set(paramName);
                            if (keyBuilderReturnsNull) return null;
                            // Embed the simulated live value so the rebuild
                            // contract can be observed via the model title.
                            String live = simulatedValue.get();
                            String title = "key:" + fileName + ":" + paramName
                                    + (live == null ? "" : "=" + live);
                            return new MenuModel(title,
                                    List.of(new MenuPage(List.of())));
                        }
                    };
            Predicate<String> probe = perm ->
                    grantConfigView || !perm.equals(MenuRedeemSubcommand.CONFIG_VIEW_PERMISSION);
            this.redeem = new MenuRedeemSubcommand(root, registry,
                    uuid -> probe, renderer, pageBuilder, null, null, subtree);
        }

        static Fixture withPermission(boolean grant) {
            return new Fixture(grant);
        }

        boolean redeem(UUID viewer, String token) {
            Map<String, List<String>> params = new HashMap<>();
            params.put(MenuRedeemSubcommand.PARAM_TOKEN, List.of(token));
            return redeem.onCommand(viewer, params, null, (Consumer<String>) messages::add);
        }
    }

    // Minimal /rtp root stub — same shape MenuParamPickerStageA2Test uses.
    static final class TestableRoot extends BaseRTPCmdImpl {
        TestableRoot() { super(null); }
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
}
