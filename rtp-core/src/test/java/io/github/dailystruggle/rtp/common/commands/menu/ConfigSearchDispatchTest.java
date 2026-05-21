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
 * PROPOSAL-rtp-menu-config-search.md / slice 5 (turn 1) -- redeem-dispatch
 * coverage for the {@link MenuAction.OpenConfigSearchResults} variant. The
 * {@link MenuAction.OpenConfigSearchPrompt} branch translates to the existing
 * {@link MenuAction.PromptAnvilInput} flow per Q4 Decision A and is covered
 * via the prompt-disabled S-004 reject path.
 *
 * <p>Shape mirrors {@link MenuConfigSubtreeDispatchTest}: a token-minting
 * {@link LocalMenuTokenRegistry} feeds a redeem call; the renderer captures
 * the resulting model so assertions can inspect the title set by the stub
 * builder.
 */
@DisplayName("PROPOSAL-rtp-menu-config-search slice 5 turn 1 - dispatch")
class ConfigSearchDispatchTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setupRTP() {
        RTPTestSetup.install(tempDir.toFile());
    }

    // ------------------------------------------------------------------------
    // OpenConfigSearchResults
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("OpenConfigSearchResults redeem: builder receives query+page, renderer receives model")
    void openConfigSearchResults_happyPath() {
        Fixture f = Fixture.withPermission(true);

        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.OpenConfigSearchResults("forest", 2),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);

        assertTrue(ok, "valid OpenConfigSearchResults redeem must succeed");
        assertNotNull(f.rendered.get(), "renderer must receive a model");
        assertEquals("search:forest:2", f.rendered.get().title());
        assertEquals("forest", f.lastQuery.get());
        assertEquals(Integer.valueOf(2), f.lastPage.get());
    }

    @Test
    @DisplayName("OpenConfigSearchResults: permission denied -> S-004 reject (no builder call)")
    void openConfigSearchResults_permissionDenied_rejects() {
        Fixture f = Fixture.withPermission(false);

        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.OpenConfigSearchResults("forest", 1),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);

        assertFalse(ok, "permission denial must reject");
        assertNull(f.rendered.get(), "no model must be rendered");
        assertNull(f.lastQuery.get(), "builder must not be invoked");
    }

    @Test
    @DisplayName("OpenConfigSearchResults: builder disabled (null) -> S-004 reject")
    void openConfigSearchResults_builderDisabled_rejects() {
        Fixture f = Fixture.withoutSearchBuilder();

        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.OpenConfigSearchResults("forest", 1),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);

        assertFalse(ok, "absent builder must reject");
        assertNull(f.rendered.get());
    }

    @Test
    @DisplayName("OpenConfigSearchResults: builder returns null -> S-004 reject")
    void openConfigSearchResults_nullModel_rejects() {
        Fixture f = Fixture.withPermission(true);
        f.searchBuilderReturnsNull = true;

        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.OpenConfigSearchResults("forest", 1),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);

        assertFalse(ok, "null model must reject");
        assertNull(f.rendered.get());
        // Builder was still consulted before returning null.
        assertEquals("forest", f.lastQuery.get());
    }

    // ------------------------------------------------------------------------
    // OpenConfigSearchPrompt
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("OpenConfigSearchPrompt: permission denied -> S-004 reject")
    void openConfigSearchPrompt_permissionDenied_rejects() {
        Fixture f = Fixture.withPermission(false);

        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.OpenConfigSearchPrompt(),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);

        assertFalse(ok, "permission denial must reject before opening anvil");
        assertNull(f.lastAnvilPrefill.get(), "anvil opener must not be invoked");
    }

    @Test
    @DisplayName("OpenConfigSearchPrompt: permission granted -> synthesizes PromptAnvilInput(menu,config,search)")
    void openConfigSearchPrompt_happyPath_synthesizesPrompt() {
        // The synthesized PromptAnvilInput is routed through
        // dispatchPromptAnvilInput, which walks parentPath against the
        // live TreeCommand graph before calling the opener. The minimal
        // TestableRoot stub has no children, so the walk rejects with
        // "unknown path segment 'menu'" (S-004). That rejection is itself
        // the proof that synthesis fired with the expected path: a
        // permission-denied prompt rejects earlier and never reaches
        // path-walking, so we'd see a different WARN.
        Fixture f = Fixture.withPermission(true);

        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.OpenConfigSearchPrompt(),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);

        assertFalse(ok, "stub root cannot resolve [menu,config,search] -> reject (expected)");
        assertNull(f.lastAnvilPrefill.get(),
                "opener must not be reached when path walk rejects");
        // The full happy-path (opener reached) requires a live /rtp tree
        // with a `menu config search <query>` leaf; that is covered by the
        // production TreeCommand wiring in a follow-up turn (slice 5b).
    }

    // ========================================================================
    // Test fixture: wires a MenuRedeemSubcommand with the 10-arg constructor.
    // ========================================================================

    private static final class Fixture {
        final LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        final TestableRoot root = new TestableRoot();
        final AtomicReference<MenuModel> rendered = new AtomicReference<>();
        final AtomicReference<String> lastQuery = new AtomicReference<>();
        final AtomicReference<Integer> lastPage = new AtomicReference<>();
        final AtomicReference<List<String>> lastAnvilPath = new AtomicReference<>();
        final AtomicReference<String> lastAnvilParam = new AtomicReference<>();
        final AtomicReference<String> lastAnvilPrefill = new AtomicReference<>();
        final List<String> messages = new ArrayList<>();
        final MenuRedeemSubcommand redeem;

        boolean searchBuilderReturnsNull = false;

        private Fixture(boolean grantConfigView, boolean wireSearchBuilder) {
            MenuRenderer renderer = (uuid, m) -> rendered.set(m);
            MenuRedeemSubcommand.MenuPageBuilder pageBuilder =
                    (node, open, assembled) -> new MenuModel("p",
                            List.of(new MenuPage(List.of())));
            MenuRedeemSubcommand.AnvilInputOpener anvilOpener =
                    (uuid, parentPath, paramName, prefill) -> {
                        lastAnvilPath.set(List.copyOf(parentPath));
                        lastAnvilParam.set(paramName);
                        lastAnvilPrefill.set(prefill);
                        return true;
                    };
            MenuRedeemSubcommand.MenuConfigSearchBuilder searchBuilder = wireSearchBuilder
                    ? (viewer, query, page) -> {
                        lastQuery.set(query);
                        lastPage.set(page);
                        if (searchBuilderReturnsNull) return null;
                        return new MenuModel("search:" + query + ":" + page,
                                List.of(new MenuPage(List.of())));
                    }
                    : null;
            Predicate<String> probe = perm ->
                    grantConfigView || !perm.equals(MenuRedeemSubcommand.CONFIG_VIEW_PERMISSION);
            this.redeem = new MenuRedeemSubcommand(root, registry,
                    uuid -> probe, renderer, pageBuilder, null, anvilOpener,
                    null, null, searchBuilder);
        }

        static Fixture withPermission(boolean grant) {
            return new Fixture(grant, true);
        }

        static Fixture withoutSearchBuilder() {
            return new Fixture(true, false);
        }

        boolean redeem(UUID viewer, String token) {
            Map<String, List<String>> params = new HashMap<>();
            params.put(MenuRedeemSubcommand.PARAM_TOKEN, List.of(token));
            return redeem.onCommand(viewer, params, null, (Consumer<String>) messages::add);
        }
    }

    // Minimal /rtp root stub -- same shape MenuConfigSubtreeDispatchTest uses.
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
