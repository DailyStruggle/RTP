package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused coverage for the {@code dispatch*} action-routing surface of
 * {@link MenuRedeemSubcommand} (ADR-035 / ADR-050). Drives each dispatch arm
 * through its S-004 reject branches (builder disabled + permission denied) and
 * its success path via same-package access, complementing the cart-helper
 * coverage in {@code MenuRedeemSubcommandCartTest}. Part of the
 * ENTERPRISE_READINESS.md coverage push against the single worst-covered class
 * in rtp-core.
 */
public class MenuRedeemSubcommandDispatchTest {

    @TempDir Path tempDir;

    private TestableRoot root;

    @BeforeEach
    void setUp() {
        // Reject paths call RTP.log / RTP.serverAccessor; RTPTestSetup wires both.
        RTPTestSetup.install(tempDir.toFile());
        root = new TestableRoot();
    }

    // ------------------------------------------------------------------------
    // Builder-less instance: every builder-gated arm rejects with "disabled".
    // ------------------------------------------------------------------------

    @Test
    void builderlessInstanceRejectsAllGatedArmsAsDisabled() {
        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(root, allow());
        UUID viewer = UUID.randomUUID();

        assertRejects(msgs -> redeem.dispatchOpen(viewer,
                new MenuAction.OpenMenu(new String[0]), msgs));
        assertRejects(msgs -> redeem.dispatchOpenParamPicker(viewer,
                new MenuAction.OpenParamPicker(new String[0], "page"), msgs));
        assertRejects(msgs -> redeem.dispatchPromptAnvilInput(viewer,
                new MenuAction.PromptAnvilInput(new String[0], "x", ""), msgs));
        assertRejects(msgs -> redeem.dispatchOpenConfigSelector(viewer, msgs));
        assertRejects(msgs -> redeem.dispatchOpenConfigFile(viewer,
                new MenuAction.OpenConfigFile("config"), msgs));
        assertRejects(msgs -> redeem.dispatchOpenConfigSearchResults(viewer,
                new MenuAction.OpenConfigSearchResults("q", 0), msgs));
        assertRejects(msgs -> redeem.dispatchOpenAdminPanel(viewer, msgs));
        assertRejects(msgs -> redeem.dispatchOpenVisualizations(viewer, msgs));
        assertRejects(msgs -> redeem.dispatchOpenFrontPage(viewer, msgs));
        assertRejects(msgs -> redeem.dispatchOpenInfo(viewer,
                new MenuAction.OpenInfo(MenuAction.InfoScopeToken.global()), msgs));
    }

    @Test
    void dispatchRejectsNullSenderOnBareOpen() {
        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(root, allow());
        List<String> msgs = new ArrayList<>();
        // onCommand -> dispatch: null sender is the menuUnknownPlayer reject.
        boolean ok = redeem.onCommand(null, new java.util.HashMap<>(), null,
                (Consumer<String>) msgs::add);
        assertFalse(ok, "null sender must be rejected");
    }

    // ------------------------------------------------------------------------
    // Fully-wired instance with a deny-all probe: gated arms reject as denied.
    // ------------------------------------------------------------------------

    @Test
    void wiredInstanceWithDenyAllProbeRejectsGatedArmsAsPermissionDenied() {
        MenuRedeemSubcommand redeem = wired(deny());
        UUID viewer = UUID.randomUUID();

        assertRejects(msgs -> redeem.dispatchOpenConfigSelector(viewer, msgs));
        assertRejects(msgs -> redeem.dispatchOpenConfigFile(viewer,
                new MenuAction.OpenConfigFile("config"), msgs));
        assertRejects(msgs -> redeem.dispatchOpenConfigSearchResults(viewer,
                new MenuAction.OpenConfigSearchResults("q", 0), msgs));
        assertRejects(msgs -> redeem.dispatchOpenAdminPanel(viewer, msgs));
        assertRejects(msgs -> redeem.dispatchOpenVisualizations(viewer, msgs));
        assertRejects(msgs -> redeem.dispatchOpenInfo(viewer,
                new MenuAction.OpenInfo(MenuAction.InfoScopeToken.global()), msgs));
        assertRejects(msgs -> redeem.dispatchStageConfigValue(viewer,
                new MenuAction.StageConfigValue("config", "radius", "100"), msgs));
        assertRejects(msgs -> redeem.dispatchUnstageConfigValue(viewer,
                new MenuAction.UnstageConfigValue("config", "radius"), msgs));
        assertRejects(msgs -> redeem.dispatchApplyStagedConfig(viewer,
                new MenuAction.ApplyStagedConfig("config"), msgs));
        assertRejects(msgs -> redeem.dispatchDiscardStagedConfig(viewer,
                new MenuAction.DiscardStagedConfig("config"), msgs));
    }

    // ------------------------------------------------------------------------
    // Fully-wired instance with an allow-all probe: gated arms render.
    // ------------------------------------------------------------------------

    @Test
    void wiredInstanceWithAllowAllProbeRendersCuratedPages() {
        AtomicInteger rendered = new AtomicInteger();
        MenuRedeemSubcommand redeem = wired(allow(), rendered);
        UUID viewer = UUID.randomUUID();

        assertTrue(redeem.dispatchOpenConfigSelector(viewer, m -> {}));
        assertTrue(redeem.dispatchOpenConfigFile(viewer,
                new MenuAction.OpenConfigFile("config"), m -> {}));
        assertTrue(redeem.dispatchOpenAdminPanel(viewer, m -> {}));
        assertTrue(redeem.dispatchOpenFrontPage(viewer, m -> {}));
        assertTrue(redeem.dispatchOpenVisualizations(viewer, m -> {}));
        assertTrue(redeem.dispatchOpenVisualizationRegions(viewer,
                ChartSpec.Kind.REGION_BIOMES, m -> {}));
        assertTrue(redeem.dispatchOpenInfo(viewer,
                new MenuAction.OpenInfo(MenuAction.InfoScopeToken.global()), m -> {}));
        assertTrue(redeem.dispatchOpenConfigSearchResults(viewer,
                new MenuAction.OpenConfigSearchResults("q", 0), m -> {}));
        assertTrue(redeem.dispatchPromptAnvilInput(viewer,
                new MenuAction.PromptAnvilInput(new String[0], "x", ""), m -> {}));

        // 8 arms render through MenuRenderer; the anvil prompt routes through
        // the AnvilInputOpener instead, so it does not increment the counter.
        assertEquals(8, rendered.get(), "each rendered arm must reach the renderer");
    }

    @Test
    void stageApplyAndDiscardCartFlowsThroughDispatch() {
        MenuRedeemSubcommand redeem = wired(allow());
        UUID viewer = UUID.randomUUID();

        // Stage -> value lands in the cart and the file page re-renders.
        assertTrue(redeem.dispatchStageConfigValue(viewer,
                new MenuAction.StageConfigValue("config", "radius", "100"), m -> {}));
        assertEquals("100", redeem.snapshotCart(viewer, "config").get("radius"));

        // Apply -> the batched /rtp config command dispatches through the root.
        int before = root.dispatchCount;
        assertTrue(redeem.dispatchApplyStagedConfig(viewer,
                new MenuAction.ApplyStagedConfig("config"), m -> {}));
        assertEquals(before + 1, root.dispatchCount,
                "apply must dispatch the batched config command once");
        assertTrue(redeem.snapshotCart(viewer, "config").isEmpty(),
                "apply must drain the cart");

        // Discard -> pops the cart without dispatching.
        redeem.dispatchStageConfigValue(viewer,
                new MenuAction.StageConfigValue("config", "shape", "SQUARE"), m -> {});
        int afterStage = root.dispatchCount;
        assertTrue(redeem.dispatchDiscardStagedConfig(viewer,
                new MenuAction.DiscardStagedConfig("config"), m -> {}));
        assertEquals(afterStage, root.dispatchCount, "discard must not dispatch");
        assertTrue(redeem.snapshotCart(viewer, "config").isEmpty(),
                "discard must drain the cart");
    }

    @Test
    void applyEmptyCartRejects() {
        MenuRedeemSubcommand redeem = wired(allow());
        UUID viewer = UUID.randomUUID();
        assertRejects(msgs -> redeem.dispatchApplyStagedConfig(viewer,
                new MenuAction.ApplyStagedConfig("config"), msgs));
    }

    @Test
    void builderReturningNullModelRejects() {
        // configSubtreeBuilder.buildFile returns null for an unknown file.
        MenuRedeemSubcommand redeem = wired(allow());
        UUID viewer = UUID.randomUUID();
        assertRejects(msgs -> redeem.dispatchOpenConfigFile(viewer,
                new MenuAction.OpenConfigFile("__unknown__"), msgs));
    }

    @Test
    void builderThrowingRejects() {
        // A curated-page builder that throws must collapse to an S-004 reject.
        MenuRenderer renderer = (u, m) -> {};
        MenuRedeemSubcommand.MenuCuratedPageBuilder curated =
                new MenuRedeemSubcommand.MenuCuratedPageBuilder() {
                    @Override public MenuModel buildAdminPanel(UUID viewer) {
                        throw new RuntimeException("boom");
                    }
                    @Override public MenuModel buildFrontPage(UUID viewer) {
                        return stubModel();
                    }
                };
        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(root, allow(),
                renderer, stubPage(), null, null, null, curated, null, null);
        UUID viewer = UUID.randomUUID();
        assertRejects(msgs -> redeem.dispatchOpenAdminPanel(viewer, msgs));
    }

    @Test
    void extractPageIndexTranslatesOneBasedWireToZeroBased() {
        assertEquals(0, MenuRedeemSubcommand.extractPageIndex(null));
        assertEquals(0, MenuRedeemSubcommand.extractPageIndex(
                Map.of(MenuRedeemSubcommand.PARAM_PAGE, List.of("1"))));
        assertEquals(2, MenuRedeemSubcommand.extractPageIndex(
                Map.of(MenuRedeemSubcommand.PARAM_PAGE, List.of("3"))));
        assertEquals(0, MenuRedeemSubcommand.extractPageIndex(
                Map.of(MenuRedeemSubcommand.PARAM_PAGE, List.of("0"))));
        assertEquals(0, MenuRedeemSubcommand.extractPageIndex(
                Map.of(MenuRedeemSubcommand.PARAM_PAGE, List.of("notanumber"))));
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static Function<UUID, Predicate<String>> allow() {
        return uuid -> perm -> true;
    }

    private static Function<UUID, Predicate<String>> deny() {
        return uuid -> perm -> false;
    }

    private MenuRedeemSubcommand wired(Function<UUID, Predicate<String>> perm) {
        return wired(perm, new AtomicInteger());
    }

    private MenuRedeemSubcommand wired(Function<UUID, Predicate<String>> perm,
                                       AtomicInteger renderCounter) {
        MenuRenderer renderer = (u, m) -> renderCounter.incrementAndGet();
        MenuRedeemSubcommand.MenuParamPickerBuilder picker =
                (parent, viewer, parentPath, paramName) -> stubModel();
        MenuRedeemSubcommand.AnvilInputOpener anvil =
                (viewer, parentPath, paramName, prefill) -> true;
        MenuRedeemSubcommand.MenuConfigSubtreeBuilder subtree =
                new MenuRedeemSubcommand.MenuConfigSubtreeBuilder() {
                    @Override public MenuModel buildSelector(UUID viewer) {
                        return stubModel();
                    }
                    @Override public MenuModel buildFile(UUID viewer, String fileName) {
                        // Null-model reject path for the unknown-file test.
                        return "__unknown__".equals(fileName) ? null : stubModel();
                    }
                    @Override public MenuModel buildKey(UUID viewer, String fileName,
                                                        String paramName) {
                        return stubModel();
                    }
                };
        MenuRedeemSubcommand.MenuCuratedPageBuilder curated =
                new MenuRedeemSubcommand.MenuCuratedPageBuilder() {
                    @Override public MenuModel buildAdminPanel(UUID viewer) {
                        return stubModel();
                    }
                    @Override public MenuModel buildFrontPage(UUID viewer) {
                        return stubModel();
                    }
                    @Override public MenuModel buildVisualizations(UUID viewer) {
                        return stubModel();
                    }
                    @Override public MenuModel buildVisualizationRegions(
                            UUID viewer, ChartSpec.Kind kind) {
                        return stubModel();
                    }
                };
        MenuRedeemSubcommand.MenuConfigSearchBuilder search =
                (viewer, query, page) -> stubModel();
        MenuRedeemSubcommand.MenuInfoBookBuilder info =
                (viewer, scope) -> stubModel();
        return new MenuRedeemSubcommand(root, perm, renderer, stubPage(), picker,
                anvil, subtree, curated, search, info);
    }

    private static MenuRedeemSubcommand.MenuPageBuilder stubPage() {
        return (node, open, assembledPath) -> stubModel();
    }

    private static MenuModel stubModel() {
        return new MenuModel("title", List.of(new MenuPage(List.of(
                MenuLine.of(MenuFragment.plain("x"))))));
    }

    /** Invokes {@code arm} and asserts it returned false with a surfaced message. */
    private static void assertRejects(java.util.function.Function<Consumer<String>, Boolean> arm) {
        List<String> msgs = new ArrayList<>();
        boolean ok = arm.apply(msgs::add);
        assertFalse(ok, "gated arm must reject");
        assertFalse(msgs.isEmpty(),
                "reject must surface a configurable message (S-004 / REQ-RTP-F-013)");
    }

    /** Minimal TreeCommand root fixture (mirrors MenuStageTwoTest). */
    private static final class TestableRoot extends BaseRTPCmdImpl {
        int dispatchCount = 0;

        TestableRoot() { super(null); }

        @Override public String name() { return "rtp"; }
        @Override public String permission() { return ""; }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 CommandsAPICommand nextCommand) {
            return true;
        }

        @Override
        public CompletableFuture<Boolean> onCommand(UUID callerId,
                                                    Predicate<String> permissionCheckMethod,
                                                    Consumer<String> messageMethod,
                                                    String[] args,
                                                    int i,
                                                    Map<String, CommandParameter> tempParameters) {
            dispatchCount++;
            return CompletableFuture.completedFuture(true);
        }
    }
}
