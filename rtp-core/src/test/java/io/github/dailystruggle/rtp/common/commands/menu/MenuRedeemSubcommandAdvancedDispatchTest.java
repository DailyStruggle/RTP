package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.config.ConfigCmd;
import io.github.dailystruggle.rtp.common.commands.menu.multiconfig.MultiConfigMenuBuilder;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Advanced action-routing coverage for {@link MenuRedeemSubcommand}.
 * Tests the remaining complex surfaces:
 * <ul>
 *   <li>{@code dispatchSwitchInfoToText} (GLOBAL, WORLD, REGION scopes, permission gates)</li>
 *   <li>{@code dispatchOpenConfigSearchPrompt} and {@code dispatchOpenConfigSearchResults}</li>
 *   <li>{@code dispatchOpenMultiConfigSelector} (toggles, remove-mode, render)</li>
 *   <li>{@code dispatchOpenMultiConfigEntry} (cart-snapshot passing, render)</li>
 *   <li>{@code dispatchMultiConfigMutate} (ADD, REMOVE, locked entries, failure paths)</li>
 *   <li>{@code dispatchOpenConfigKey} (file lookup, anvil prompt / options picker)</li>
 * </ul>
 */
public class MenuRedeemSubcommandAdvancedDispatchTest {

    private Path tempDir;
    private TestableRoot root;
    private File pluginDir;

    @BeforeEach
    void setUp() throws java.io.IOException {
        // Manually create temp directory to avoid Windows locked-handle DirectoryNotEmptyException on teardown
        tempDir = Files.createTempDirectory("rtp-advanced-dispatch-test-");
        pluginDir = tempDir.toFile();
        RTPTestSetup.install(pluginDir);
        io.github.dailystruggle.rtp.common.commands.menu.multiconfig.DefaultMultiConfigRemovalGuards.registerDefaults();
        root = new TestableRoot();
        RTP.baseCommand = root;
    }

    // ------------------------------------------------------------------------
    // dispatchSwitchInfoToText
    // ------------------------------------------------------------------------

    @Test
    void switchInfoToTextRejectsWhenPermissionDenied() {
        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(root, deny());
        UUID viewer = UUID.randomUUID();
        List<String> msgs = new ArrayList<>();

        boolean ok = redeem.dispatchSwitchInfoToText(viewer,
                new MenuAction.SwitchInfoToText(MenuAction.InfoScopeToken.global()),
                msgs::add);
        assertFalse(ok);
        assertFalse(msgs.isEmpty(), "must surface permission rejection");
    }

    @Test
    void switchInfoToTextDispatchesGlobalWorldAndRegionScopes() {
        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(root, allow());
        UUID viewer = UUID.randomUUID();

        // 1. GLOBAL
        root.lastDispatchedArgs = null;
        boolean okGlobal = redeem.dispatchSwitchInfoToText(viewer,
                new MenuAction.SwitchInfoToText(MenuAction.InfoScopeToken.global()),
                m -> {});
        assertTrue(okGlobal);
        assertEquals(List.of("info"), List.of(root.lastDispatchedArgs));

        // 2. WORLD
        root.lastDispatchedArgs = null;
        boolean okWorld = redeem.dispatchSwitchInfoToText(viewer,
                new MenuAction.SwitchInfoToText(MenuAction.InfoScopeToken.world("world_nether")),
                m -> {});
        assertTrue(okWorld);
        assertEquals(List.of("info", "world=world_nether"), List.of(root.lastDispatchedArgs));

        // 3. REGION
        root.lastDispatchedArgs = null;
        boolean okRegion = redeem.dispatchSwitchInfoToText(viewer,
                new MenuAction.SwitchInfoToText(MenuAction.InfoScopeToken.region("default")),
                m -> {});
        assertTrue(okRegion);
        assertEquals(List.of("info", "region=default"), List.of(root.lastDispatchedArgs));
    }

    // ------------------------------------------------------------------------
    // Config Search Prompt
    // ------------------------------------------------------------------------

    @Test
    void configSearchPromptGatingAndDispatch() {
        UUID viewer = UUID.randomUUID();

        // Deny permission
        MenuRedeemSubcommand denyRedeem = new MenuRedeemSubcommand(root, deny());
        assertFalse(denyRedeem.dispatchOpenConfigSearchPrompt(viewer, m -> {}));

        // Allow permission but absent opener
        MenuRedeemSubcommand allowRedeemNoOpener = new MenuRedeemSubcommand(root, allow());
        assertFalse(allowRedeemNoOpener.dispatchOpenConfigSearchPrompt(viewer, m -> {}));

        // Allow permission with wired opener
        AtomicBoolean anvilPrompted = new AtomicBoolean();
        MenuRedeemSubcommand.AnvilInputOpener opener = (u, path, param, prefill) -> {
            anvilPrompted.set(true);
            return true;
        };
        MenuRedeemSubcommand allowRedeem = wiredWithOpener(allow(), opener);

        assertTrue(allowRedeem.dispatchOpenConfigSearchPrompt(viewer, m -> {}));
        assertTrue(anvilPrompted.get());
    }

    // ------------------------------------------------------------------------
    // MultiConfig Selector & Remove-Mode Toggle
    // ------------------------------------------------------------------------

    @Test
    void multiConfigSelectorRejections() {
        UUID viewer = UUID.randomUUID();

        // Disabled builder
        MenuRedeemSubcommand disabled = new MenuRedeemSubcommand(root, allow());
        assertFalse(disabled.dispatchOpenMultiConfigSelector(viewer,
                new MenuAction.OpenMultiConfigSelector("regions"), m -> {}));

        // Permission denied
        MenuRedeemSubcommand denied = wired(deny());
        assertFalse(denied.dispatchOpenMultiConfigSelector(viewer,
                new MenuAction.OpenMultiConfigSelector("regions"), m -> {}));

        // Unknown parser kind
        MenuRedeemSubcommand wired = wired(allow());
        assertFalse(wired.dispatchOpenMultiConfigSelector(viewer,
                new MenuAction.OpenMultiConfigSelector("nonexistent_kind"), m -> {}));
    }

    @Test
    void multiConfigSelectorToggleRemoveMode() {
        MenuRedeemSubcommand wired = wired(allow());
        UUID viewer = UUID.randomUUID();

        assertFalse(wired.isRemoveMode(viewer, "regions"));

        // Toggle ON
        boolean ok1 = wired.dispatchOpenMultiConfigSelector(viewer,
                new MenuAction.OpenMultiConfigSelector("!toggle:regions"), m -> {});
        assertTrue(ok1);
        assertTrue(wired.isRemoveMode(viewer, "regions"));

        // Normal render does not change remove mode
        boolean ok2 = wired.dispatchOpenMultiConfigSelector(viewer,
                new MenuAction.OpenMultiConfigSelector("regions"), m -> {});
        assertTrue(ok2);
        assertTrue(wired.isRemoveMode(viewer, "regions"));

        // Toggle OFF
        boolean ok3 = wired.dispatchOpenMultiConfigSelector(viewer,
                new MenuAction.OpenMultiConfigSelector("!toggle:regions"), m -> {});
        assertTrue(ok3);
        assertFalse(wired.isRemoveMode(viewer, "regions"));
    }

    // ------------------------------------------------------------------------
    // MultiConfig Entry
    // ------------------------------------------------------------------------

    @Test
    void multiConfigEntryRejectionsAndRender() {
        UUID viewer = UUID.randomUUID();

        // Disabled builder
        MenuRedeemSubcommand disabled = new MenuRedeemSubcommand(root, allow());
        assertFalse(disabled.dispatchOpenMultiConfigEntry(viewer,
                new MenuAction.OpenMultiConfigEntry("regions", "default"), m -> {}));

        // Permission denied
        MenuRedeemSubcommand denied = wired(deny());
        assertFalse(denied.dispatchOpenMultiConfigEntry(viewer,
                new MenuAction.OpenMultiConfigEntry("regions", "default"), m -> {}));

        // Unknown parser kind
        MenuRedeemSubcommand wired = wired(allow());
        assertFalse(wired.dispatchOpenMultiConfigEntry(viewer,
                new MenuAction.OpenMultiConfigEntry("unknown_kind", "default"), m -> {}));

        // Successful render passing cart snapshot
        wired.stageInCart(viewer, "regions/default", "radius", "5000");
        wired.setMultiConfigBuilder(new MultiConfigMenuBuilder());

        assertTrue(wired.dispatchOpenMultiConfigEntry(viewer,
                new MenuAction.OpenMultiConfigEntry("regions", "default"), m -> {}));
    }

    // ------------------------------------------------------------------------
    // MultiConfig Mutate (ADD & REMOVE)
    // ------------------------------------------------------------------------

    @Test
    void multiConfigMutateRejectionPaths() {
        UUID viewer = UUID.randomUUID();

        // Disabled builder
        MenuRedeemSubcommand disabled = new MenuRedeemSubcommand(root, allow());
        assertFalse(disabled.dispatchMultiConfigMutate(viewer,
                new MenuAction.MultiConfigMutate("regions", "test", MenuAction.MultiConfigMutate.Op.ADD),
                m -> {}));

        // Permission denied
        MenuRedeemSubcommand denied = wired(deny());
        assertFalse(denied.dispatchMultiConfigMutate(viewer,
                new MenuAction.MultiConfigMutate("regions", "test", MenuAction.MultiConfigMutate.Op.ADD),
                m -> {}));

        // Unknown parser kind
        MenuRedeemSubcommand wired = wired(allow());
        assertFalse(wired.dispatchMultiConfigMutate(viewer,
                new MenuAction.MultiConfigMutate("bad_kind", "test", MenuAction.MultiConfigMutate.Op.ADD),
                m -> {}));

        // REMOVE locked entry (e.g. 'default' region is locked by MultiConfigRemovalGuards)
        List<String> msgs = new ArrayList<>();
        boolean okLocked = wired.dispatchMultiConfigMutate(viewer,
                new MenuAction.MultiConfigMutate("regions", "default", MenuAction.MultiConfigMutate.Op.REMOVE),
                msgs::add);
        assertFalse(okLocked, "attempting to remove locked entry 'default' must reject");
        assertFalse(msgs.isEmpty());
    }

    @Test
    void multiConfigMutateAddAndRemoveLifecycle() {
        MenuRedeemSubcommand wired = wired(allow());
        UUID viewer = UUID.randomUUID();

        // Seed 'default' parser into the regions MultiConfigParser so construct(name) has a template
        MultiConfigParser<?> regionsParser = null;
        for (MultiConfigParser<?> p : RTP.configs.multiConfigParserMap.values()) {
            if (p != null && "regions".equalsIgnoreCase(p.name)) {
                regionsParser = p;
                break;
            }
        }
        assertTrue(regionsParser != null);
        io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys> defaultParser =
                new io.github.dailystruggle.rtp.common.configuration.ConfigParser<>(
                        io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys.class,
                        "default",
                        "1.0",
                        regionsParser.myDirectory,
                        regionsParser.fileDatabase);
        regionsParser.addParser(defaultParser);

        // Add a new entry 'custom_region'
        boolean okAdd = wired.dispatchMultiConfigMutate(viewer,
                new MenuAction.MultiConfigMutate("regions", "custom_region", MenuAction.MultiConfigMutate.Op.ADD),
                m -> {});
        assertTrue(okAdd, "adding custom region should succeed");

        // Verify it was added to configs
        assertTrue(regionsParser.listParsers().contains("custom_region.yml")
                || regionsParser.listParsers().contains("custom_region"));

        // Now remove the newly added entry
        boolean okRemove = wired.dispatchMultiConfigMutate(viewer,
                new MenuAction.MultiConfigMutate("regions", "custom_region", MenuAction.MultiConfigMutate.Op.REMOVE),
                m -> {});
        assertTrue(okRemove, "removing custom region should succeed");
        assertFalse(regionsParser.listParsers().contains("custom_region.yml"));
        assertFalse(regionsParser.listParsers().contains("custom_region"));

        // Close any open streams/parsers on the temp files so file deletion can happen cleanly
        RTP.configs = null;
    }

    // ------------------------------------------------------------------------
    // dispatchOpenConfigKey
    // ------------------------------------------------------------------------

    @Test
    void openConfigKeyRejections() {
        UUID viewer = UUID.randomUUID();

        // Absent opener
        MenuRedeemSubcommand noOpener = new MenuRedeemSubcommand(root, allow());
        assertFalse(noOpener.dispatchOpenConfigKey(viewer,
                new MenuAction.OpenConfigKey("config", "radius"), m -> {}));

        // Permission denied
        MenuRedeemSubcommand denied = wired(deny());
        assertFalse(denied.dispatchOpenConfigKey(viewer,
                new MenuAction.OpenConfigKey("config", "radius"), m -> {}));

        // Unknown config file
        MenuRedeemSubcommand wired = wired(allow());
        assertFalse(wired.dispatchOpenConfigKey(viewer,
                new MenuAction.OpenConfigKey("unknown_file_xyz", "radius"), m -> {}));
    }

    @Test
    void openConfigKeyOpensAnvilForUnconstrainedKey() {
        AtomicBoolean anvilOpened = new AtomicBoolean();
        MenuRedeemSubcommand.AnvilInputOpener opener = (v, parentPath, paramName, prefill) -> {
            anvilOpened.set(true);
            return true;
        };
        MenuRedeemSubcommand wired = wiredWithOpener(allow(), opener);
        UUID viewer = UUID.randomUUID();

        // config.yml does not constrain 'cancelDistance' with @options or @source
        boolean ok = wired.dispatchOpenConfigKey(viewer,
                new MenuAction.OpenConfigKey("config", "cancelDistance"), m -> {});
        assertTrue(ok);
        assertTrue(anvilOpened.get(), "unconstrained key should route to anvil opener");
    }

    // ------------------------------------------------------------------------
    // Helpers & Fixtures
    // ------------------------------------------------------------------------

    private static Function<UUID, Predicate<String>> allow() {
        return uuid -> perm -> true;
    }

    private static Function<UUID, Predicate<String>> deny() {
        return uuid -> perm -> false;
    }

    private MenuRedeemSubcommand wired(Function<UUID, Predicate<String>> perm) {
        return wiredWithOpener(perm, (v, p, k, val) -> true);
    }

    private MenuRedeemSubcommand wiredWithOpener(Function<UUID, Predicate<String>> perm,
                                                 MenuRedeemSubcommand.AnvilInputOpener anvil) {
        // Wire a live ConfigCmd on the root so config file lookups succeed
        ConfigCmd configCmd = new ConfigCmd(root);
        root.getCommandLookup().put("CONFIG", configCmd);

        // Also wire dummy subcommands so TreeCommand path walking succeeds
        TestableRoot configSub = new TestableRoot();
        configCmd.getCommandLookup().put("SEARCH", configSub);
        configCmd.getCommandLookup().put("CONFIG.YML", configSub);
        configCmd.getCommandLookup().put("REGIONS", configSub);

        MenuRenderer renderer = (u, m) -> {};
        MenuRedeemSubcommand.MenuParamPickerBuilder picker = (p, v, path, name) -> stubModel();
        MenuRedeemSubcommand.MenuConfigSubtreeBuilder subtree = new MenuRedeemSubcommand.MenuConfigSubtreeBuilder() {
            @Override public MenuModel buildSelector(UUID viewer) { return stubModel(); }
            @Override public MenuModel buildFile(UUID viewer, String fileName) { return stubModel(); }
            @Override public MenuModel buildKey(UUID viewer, String fileName, String paramName) { return stubModel(); }
        };
        MenuRedeemSubcommand.MenuCuratedPageBuilder curated = new MenuRedeemSubcommand.MenuCuratedPageBuilder() {
            @Override public MenuModel buildAdminPanel(UUID viewer) { return stubModel(); }
            @Override public MenuModel buildFrontPage(UUID viewer) { return stubModel(); }
            @Override public MenuModel buildVisualizations(UUID viewer) { return stubModel(); }
            @Override public MenuModel buildVisualizationRegions(UUID viewer, io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind kind) { return stubModel(); }
        };
        MenuRedeemSubcommand.MenuConfigSearchBuilder search = (viewer, query, page) -> stubModel();
        MenuRedeemSubcommand.MenuInfoBookBuilder info = (viewer, scope) -> stubModel();

        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(root, perm, renderer, stubPage(),
                picker, anvil, subtree, curated, search, info);

        // Inject MultiConfigMenuBuilder
        redeem.setMultiConfigBuilder(new MultiConfigMenuBuilder());

        return redeem;
    }

    private static MenuRedeemSubcommand.MenuPageBuilder stubPage() {
        return (node, open, assembledPath) -> stubModel();
    }

    private static MenuModel stubModel() {
        return new MenuModel("title", List.of(new MenuPage(List.of(
                MenuLine.of(MenuFragment.plain("x"))))));
    }

    private static final class TestableRoot extends BaseRTPCmdImpl {
        String[] lastDispatchedArgs;

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
            this.lastDispatchedArgs = args;
            return CompletableFuture.completedFuture(true);
        }
    }
}
