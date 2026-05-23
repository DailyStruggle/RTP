package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.menu.multiconfig.MultiConfigMenuBuilder;
import io.github.dailystruggle.rtp.common.commands.menu.multiconfig.MultiConfigRemovalGuard;
import io.github.dailystruggle.rtp.common.commands.menu.multiconfig.MultiConfigRemovalGuards;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CHECKLIST-multiconfig-menu step 8: redeem-dispatch coverage for the three
 * new MultiConfig submenu {@link MenuAction} variants
 * ({@link MenuAction.OpenMultiConfigSelector} /
 * {@link MenuAction.OpenMultiConfigEntry} /
 * {@link MenuAction.MultiConfigMutate}).
 *
 * <p>Exercises the real {@link MultiConfigMenuBuilder} against a live
 * {@link MultiConfigParser} of {@link RegionKeys} seeded into
 * {@link RTP#configs} by {@link RTPTestSetup}. All failure paths are S-004
 * (reject + WARN); happy paths confirm the renderer receives a model.
 */
@DisplayName("CHECKLIST-multiconfig-menu step 8: MultiConfig dispatch arms")
class MenuMultiConfigDispatchTest {

    private Path tempDir;
    private MultiConfigParser<RegionKeys> regions;

    @BeforeEach
    void setUp() throws IOException {
        // Manually-created temp dir to avoid Windows-locked YAML cleanup
        // noise (same rationale as MultiConfigMenuBuilderTest).
        tempDir = Files.createTempDirectory("rtp-multiconfig-dispatch-test-");
        RTPTestSetup.install(tempDir.toFile());
        @SuppressWarnings("unchecked")
        MultiConfigParser<RegionKeys> r = (MultiConfigParser<RegionKeys>)
                RTP.configs.multiConfigParserMap.get(RegionKeys.class);
        assertNotNull(r, "test fixture: RegionKeys MultiConfigParser must be installed");
        this.regions = r;
        // Seed the canonical 'default' entry so the selector has something
        // to render and the resolver can find a non-empty parser.
        seedRegion("default");
        // Hermetic guard registry.
        MultiConfigRemovalGuards.clear();
    }

    @AfterEach
    void tearDown() {
        MultiConfigRemovalGuards.clear();
    }

    private void seedRegion(String name) {
        ConfigParser<RegionKeys> p = new ConfigParser<>(
                RegionKeys.class, name, "1.0",
                regions.myDirectory, regions.fileDatabase);
        regions.addParser(p);
    }

    // ------------------------------------------------------------------------
    // Happy paths
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("OpenMultiConfigSelector redeem: builds and renders the selector page")
    void openSelector_happyPath() {
        Fixture f = Fixture.withPermission(true);
        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.OpenMultiConfigSelector("regions"),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);
        assertTrue(ok, "valid OpenMultiConfigSelector must succeed");
        assertNotNull(f.rendered.get(), "renderer must receive a model");
    }

    @Test
    @DisplayName("OpenMultiConfigEntry redeem: builds and renders the entry page")
    void openEntry_happyPath() {
        Fixture f = Fixture.withPermission(true);
        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.OpenMultiConfigEntry("regions", "default"),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);
        assertTrue(ok, "valid OpenMultiConfigEntry must succeed");
        assertNotNull(f.rendered.get());
    }

    @Test
    @DisplayName("MultiConfigMutate ADD: creates the entry and re-renders the selector")
    void mutate_addCreatesEntryAndRerendersSelector() {
        Fixture f = Fixture.withPermission(true);
        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.MultiConfigMutate("regions", "freshlymade",
                        MenuAction.MultiConfigMutate.Op.ADD),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);
        assertTrue(ok, "valid ADD must succeed");
        // The selector re-render fires after ADD; the new entry must be on disk.
        assertTrue(regions.listParsers().contains("freshlymade"),
                "ADD must persist the new entry in the parser");
        assertNotNull(f.rendered.get(), "selector should be re-rendered after ADD");
    }

    @Test
    @DisplayName("MultiConfigMutate REMOVE: drops the entry when guard allows")
    void mutate_removeDropsEntryWhenAllowed() {
        Fixture f = Fixture.withPermission(true);
        seedRegion("disposable");
        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.MultiConfigMutate("regions", "disposable",
                        MenuAction.MultiConfigMutate.Op.REMOVE),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);
        assertTrue(ok, "valid REMOVE must succeed when guard does not lock the entry");
        assertFalse(regions.listParsers().contains("disposable"),
                "REMOVE must drop the entry from the parser");
    }

    @Test
    @DisplayName("OpenMultiConfigSelector with !toggle: prefix flips remove-mode for the viewer")
    void openSelector_toggleFlipsRemoveMode() {
        Fixture f = Fixture.withPermission(true);
        UUID viewer = UUID.randomUUID();
        // Initially no remove-mode for any kind.
        assertFalse(f.redeem.isRemoveMode(viewer, "regions"));
        String token = f.registry.mint(viewer,
                new MenuAction.OpenMultiConfigSelector("!toggle:regions"),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);
        assertTrue(ok);
        assertTrue(f.redeem.isRemoveMode(viewer, "regions"),
                "toggle must flip remove-mode ON");
        // Toggle again -> OFF.
        String token2 = f.registry.mint(viewer,
                new MenuAction.OpenMultiConfigSelector("!toggle:regions"),
                Duration.ofSeconds(30));
        f.redeem(viewer, token2);
        assertFalse(f.redeem.isRemoveMode(viewer, "regions"),
                "second toggle must flip remove-mode OFF");
    }

    // ------------------------------------------------------------------------
    // Permission denial paths (3 arms)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("OpenMultiConfigSelector without rtp.config.view: S-004 reject")
    void openSelector_permissionDenied() {
        Fixture f = Fixture.withPermission(false);
        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.OpenMultiConfigSelector("regions"),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);
        assertFalse(ok, "selector must reject when permission missing");
        assertNull(f.rendered.get(), "renderer must NOT be called on permission-denied path");
        assertFalse(f.messages.isEmpty(), "viewer must receive a reject message");
    }

    @Test
    @DisplayName("OpenMultiConfigEntry without rtp.config.view: S-004 reject")
    void openEntry_permissionDenied() {
        Fixture f = Fixture.withPermission(false);
        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.OpenMultiConfigEntry("regions", "default"),
                Duration.ofSeconds(30));
        assertFalse(f.redeem(viewer, token));
        assertNull(f.rendered.get());
    }

    @Test
    @DisplayName("MultiConfigMutate without rtp.config.view: S-004 reject (does not mutate parser)")
    void mutate_permissionDenied() {
        Fixture f = Fixture.withPermission(false);
        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.MultiConfigMutate("regions", "default",
                        MenuAction.MultiConfigMutate.Op.REMOVE),
                Duration.ofSeconds(30));
        assertFalse(f.redeem(viewer, token));
        assertTrue(regions.listParsers().contains("default"),
                "permission-denied REMOVE must NOT mutate the parser");
    }

    // ------------------------------------------------------------------------
    // Forged-token / unknown / locked rejection paths
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("OpenMultiConfigSelector with unknown parserKind: S-004 reject")
    void openSelector_unknownKind() {
        Fixture f = Fixture.withPermission(true);
        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.OpenMultiConfigSelector("nosuchkind"),
                Duration.ofSeconds(30));
        assertFalse(f.redeem(viewer, token));
        assertNull(f.rendered.get());
    }

    @Test
    @DisplayName("MultiConfigMutate REMOVE locked by guard: S-004 reject (does not mutate)")
    void mutate_removeLockedByGuard() {
        Fixture f = Fixture.withPermission(true);
        // Lock the 'default' region under the 'regions' kind.
        MultiConfigRemovalGuards.register("regions", new MultiConfigRemovalGuard() {
            @Override public boolean isLocked(String entryName) {
                return "default".equalsIgnoreCase(entryName);
            }
            @Override public String reason(String entryName) {
                return "default region cannot be removed";
            }
        });
        UUID viewer = UUID.randomUUID();
        String token = f.registry.mint(viewer,
                new MenuAction.MultiConfigMutate("regions", "default",
                        MenuAction.MultiConfigMutate.Op.REMOVE),
                Duration.ofSeconds(30));
        boolean ok = f.redeem(viewer, token);
        assertFalse(ok, "locked REMOVE must be rejected even on a valid token");
        assertTrue(regions.listParsers().contains("default"),
                "locked REMOVE must NOT mutate the parser");
        assertFalse(f.messages.isEmpty(), "viewer must receive a reject message");
    }

    @Test
    @DisplayName("Builder disabled (setMultiConfigBuilder(null)): all three arms reject")
    void allArmsRejectWhenBuilderDisabled() {
        Fixture f = Fixture.withPermission(true);
        f.redeem.setMultiConfigBuilder(null);
        UUID viewer = UUID.randomUUID();
        // Selector
        String t1 = f.registry.mint(viewer,
                new MenuAction.OpenMultiConfigSelector("regions"),
                Duration.ofSeconds(30));
        assertFalse(f.redeem(viewer, t1));
        // Entry
        String t2 = f.registry.mint(viewer,
                new MenuAction.OpenMultiConfigEntry("regions", "default"),
                Duration.ofSeconds(30));
        assertFalse(f.redeem(viewer, t2));
        // Mutate
        String t3 = f.registry.mint(viewer,
                new MenuAction.MultiConfigMutate("regions", "x",
                        MenuAction.MultiConfigMutate.Op.ADD),
                Duration.ofSeconds(30));
        assertFalse(f.redeem(viewer, t3));
        assertNull(f.rendered.get(), "renderer must never be called when builder is null");
    }

    // ========================================================================
    // Test fixture: wires a MenuRedeemSubcommand with a real
    // MultiConfigMenuBuilder, mirroring the MenuConfigSubtreeDispatchTest
    // shape but trimmed to the multiconfig surface only.
    // ========================================================================

    private final class Fixture {
        final LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        final TestableRoot root = new TestableRoot();
        final AtomicReference<MenuModel> rendered = new AtomicReference<>();
        final List<String> messages = new ArrayList<>();
        MenuRedeemSubcommand redeem;

        private Fixture(boolean grantConfigView) {
            MenuRenderer renderer = (uuid, m) -> rendered.set(m);
            MenuRedeemSubcommand.MenuPageBuilder pageBuilder =
                    (node, open, assembled) -> new MenuModel("p",
                            List.of(new MenuPage(List.of())));
            Predicate<String> probe = perm ->
                    grantConfigView || !perm.equals(MenuRedeemSubcommand.CONFIG_VIEW_PERMISSION);
            // 4-arg constructor (renderer + page builder, others null).
            this.redeem = new MenuRedeemSubcommand(root, registry,
                    uuid -> probe, renderer, pageBuilder);
            this.redeem.setMultiConfigBuilder(new MultiConfigMenuBuilder(registry));
        }

        static Fixture withPermission(boolean grant) {
            return new MenuMultiConfigDispatchTest().newFixture(grant);
        }

        boolean redeem(UUID viewer, String token) {
            Map<String, List<String>> params = new HashMap<>();
            params.put(MenuRedeemSubcommand.PARAM_TOKEN, List.of(token));
            return redeem.onCommand(viewer, params, null,
                    (Consumer<String>) messages::add);
        }
    }

    /**
     * Static-method factory replacement: the inner {@code Fixture} class is
     * non-static (so it can see the test's {@link #regions} field via the
     * outer instance), but the JUnit-test-pattern call sites want
     * {@code Fixture.withPermission(...)}. We delegate via a fresh outer
     * instance whose setup has already run.
     */
    private Fixture newFixture(boolean grant) {
        // Reuse the live RTP.configs (already installed by @BeforeEach on the
        // surrounding test instance). A second RTPTestSetup.install would
        // collide, so just construct the inner Fixture against the live
        // configs and the surrounding test's seeded entries.
        return new Fixture(grant);
    }

    /** Inert /rtp root stub. Minimal: dispatch arms here never re-enter the root. */
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
