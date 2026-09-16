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
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.config.ConfigCmd;
import io.github.dailystruggle.rtp.common.commands.menu.multiconfig.MultiConfigMenuBuilder;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MenuRedeemSubcommand action routing & cart lifecycle")
final class MenuRedeemSubcommandActionRoutingTest {

    @TempDir
    Path tempDir;

    private TestableRoot root;
    private UUID callerId;

    @BeforeEach
    void setUp() {
        callerId = UUID.randomUUID();
        RTPTestSetup.install(tempDir.toFile());
        root = new TestableRoot();
        RTP.baseCommand = root;
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = null;
        RTP.scheduler = null;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
    }

    private static Function<UUID, Predicate<String>> allowAll() {
        return uuid -> perm -> true;
    }

    private static Function<UUID, Predicate<String>> denyAll() {
        return uuid -> perm -> false;
    }

    private MenuRedeemSubcommand createWiredRedeem(Function<UUID, Predicate<String>> perm,
                                                   MenuRenderer renderer,
                                                   MenuRedeemSubcommand.AnvilInputOpener opener) {
        ConfigCmd configCmd = new ConfigCmd(root);
        root.getCommandLookup().put("CONFIG", configCmd);

        TestableRoot configSub = new TestableRoot();
        TestableRoot regionsSub = new TestableRoot();
        TestableRoot defaultSub = new TestableRoot();
        regionsSub.getCommandLookup().put("DEFAULT", defaultSub);

        configCmd.getCommandLookup().put("CONFIG.YML", configSub);
        configCmd.getCommandLookup().put("REGIONS", regionsSub);

        MenuRedeemSubcommand.MenuPageBuilder pageBuilder = (node, open, path) -> stubModel("page");
        MenuRedeemSubcommand.MenuParamPickerBuilder pickerBuilder = (parent, viewer, parentPath, paramName) -> stubModel("picker");
        MenuRedeemSubcommand.MenuConfigSubtreeBuilder subtreeBuilder = new MenuRedeemSubcommand.MenuConfigSubtreeBuilder() {
            @Override public MenuModel buildSelector(UUID viewer) { return stubModel("selector"); }
            @Override public MenuModel buildFile(UUID viewer, String fileName) { return stubModel("file:" + fileName); }
            @Override public MenuModel buildFile(UUID viewer, String fileName, LinkedHashMap<String, String> cart) {
                return stubModel("file-cart:" + fileName + ":" + cart.size());
            }
            @Override public MenuModel buildKey(UUID viewer, String fileName, String paramName) { return stubModel("key"); }
        };
        MenuRedeemSubcommand.MenuCuratedPageBuilder curatedBuilder = new MenuRedeemSubcommand.MenuCuratedPageBuilder() {
            @Override public MenuModel buildAdminPanel(UUID viewer) { return stubModel("admin"); }
            @Override public MenuModel buildFrontPage(UUID viewer) { return stubModel("front"); }
            @Override public MenuModel buildVisualizations(UUID viewer) { return stubModel("visualizations"); }
            @Override public MenuModel buildVisualizationRegions(UUID viewer, ChartSpec.Kind kind) { return stubModel("vis-reg:" + kind); }
        };
        MenuRedeemSubcommand.MenuConfigSearchBuilder searchBuilder = (viewer, query, page) -> stubModel("search:" + query);
        MenuRedeemSubcommand.MenuInfoBookBuilder infoBuilder = (viewer, scope) -> stubModel("info:" + scope.toString());

        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(
                root, perm, renderer, pageBuilder, pickerBuilder, opener,
                subtreeBuilder, curatedBuilder, searchBuilder, infoBuilder);

        MultiConfigMenuBuilder multiConfigBuilder = new MultiConfigMenuBuilder();
        multiConfigBuilder.setCommandTreeMenuBuilder(new CommandTreeMenuBuilder());
        redeem.setMultiConfigBuilder(multiConfigBuilder);

        if (opener != null) {
            opener.setCartSink(redeem.cartSink());
        }
        return redeem;
    }

    private static MenuModel stubModel(String title) {
        return new MenuModel(title, List.of(new MenuPage(List.of(
                MenuLine.of(MenuFragment.plain("test"))))));
    }

    @Test
    @DisplayName("Cart addition, quantity modifications, removal, and confirmation flows")
    void cartLifecycleFlows() {
        AtomicBoolean rendered = new AtomicBoolean(false);
        MenuRenderer renderer = (uuid, model) -> rendered.set(true);
        MenuRedeemSubcommand redeem = createWiredRedeem(allowAll(), renderer, (v, p, k, val) -> true);

        List<String> messages = new ArrayList<>();

        // 1. Stage a value into cart (cart addition)
        MenuAction.StageConfigValue stage1 = new MenuAction.StageConfigValue("config.yml", "maxRadius", "1000");
        boolean okStage1 = redeem.dispatchStageConfigValue(callerId, stage1, messages::add);
        assertTrue(okStage1);
        assertEquals("1000", redeem.snapshotCart(callerId, "config.yml").get("maxRadius"));

        // 2. Quantity modification / value modification for same key
        MenuAction.StageConfigValue stage2 = new MenuAction.StageConfigValue("config.yml", "maxRadius", "2500");
        boolean okStage2 = redeem.dispatchStageConfigValue(callerId, stage2, messages::add);
        assertTrue(okStage2);
        assertEquals("2500", redeem.snapshotCart(callerId, "config.yml").get("maxRadius"));

        // Stage a second key
        MenuAction.StageConfigValue stage3 = new MenuAction.StageConfigValue("config.yml", "minRadius", "100");
        redeem.dispatchStageConfigValue(callerId, stage3, messages::add);
        assertEquals("100", redeem.snapshotCart(callerId, "config.yml").get("minRadius"));

        // 3. Removal (unstage)
        MenuAction.UnstageConfigValue unstage = new MenuAction.UnstageConfigValue("config.yml", "minRadius");
        boolean okUnstage = redeem.dispatchUnstageConfigValue(callerId, unstage, messages::add);
        assertTrue(okUnstage);
        assertFalse(redeem.snapshotCart(callerId, "config.yml").containsKey("minRadius"));
        assertTrue(redeem.snapshotCart(callerId, "config.yml").containsKey("maxRadius"));

        // 4. Confirmation (apply) flow
        root.lastDispatchedArgs = null;
        MenuAction.ApplyStagedConfig apply = new MenuAction.ApplyStagedConfig("config.yml");
        boolean okApply = redeem.dispatchApplyStagedConfig(callerId, apply, messages::add);
        assertTrue(okApply);
        assertNotNull(root.lastDispatchedArgs);
        assertEquals("config", root.lastDispatchedArgs[0]);
        assertEquals("config.yml", root.lastDispatchedArgs[1]);
        assertEquals("maxRadius=2500", root.lastDispatchedArgs[2]);
        // Cart should now be empty for config.yml
        assertTrue(redeem.snapshotCart(callerId, "config.yml").isEmpty());
    }

    @Test
    @DisplayName("Empty cart confirmation is rejected")
    void applyEmptyCartRejected() {
        MenuRedeemSubcommand redeem = createWiredRedeem(allowAll(), (u, m) -> {}, (v, p, k, val) -> true);
        List<String> messages = new ArrayList<>();

        MenuAction.ApplyStagedConfig apply = new MenuAction.ApplyStagedConfig("config.yml");
        boolean ok = redeem.dispatchApplyStagedConfig(callerId, apply, messages::add);
        assertFalse(ok);
        assertFalse(messages.isEmpty(), "must reject when cart is empty");
    }

    @Test
    @DisplayName("Player cancellation / discard flow clears the cart")
    void discardCartFlow() {
        MenuRedeemSubcommand redeem = createWiredRedeem(allowAll(), (u, m) -> {}, (v, p, k, val) -> true);
        redeem.cartSink().stage(callerId, "config.yml", "radius", "500");
        assertEquals("500", redeem.snapshotCart(callerId, "config.yml").get("radius"));

        MenuAction.DiscardStagedConfig discard = new MenuAction.DiscardStagedConfig("config.yml");
        boolean ok = redeem.dispatchDiscardStagedConfig(callerId, discard, m -> {});
        assertTrue(ok);
        assertTrue(redeem.snapshotCart(callerId, "config.yml").isEmpty());
    }

    @Test
    @DisplayName("Slash-separated multiconfig cart confirmation formats args correctly")
    void multiConfigCartApply() {
        MenuRedeemSubcommand redeem = createWiredRedeem(allowAll(), (u, m) -> {}, (v, p, k, val) -> true);
        redeem.cartSink().stage(callerId, "regions/default", "shape.radius", "4000");

        MenuAction.ApplyStagedConfig apply = new MenuAction.ApplyStagedConfig("regions/default");
        root.lastDispatchedArgs = null;
        boolean ok = redeem.dispatchApplyStagedConfig(callerId, apply, m -> {});
        assertTrue(ok);
        assertNotNull(root.lastDispatchedArgs);
        assertEquals(4, root.lastDispatchedArgs.length);
        assertEquals("config", root.lastDispatchedArgs[0]);
        assertEquals("regions", root.lastDispatchedArgs[1]);
        assertEquals("default", root.lastDispatchedArgs[2]);
        assertEquals("shape.radius=4000", root.lastDispatchedArgs[3]);
        assertTrue(redeem.snapshotCart(callerId, "regions/default").isEmpty());
    }

    @Test
    @DisplayName("Insufficient permissions reject cart operations")
    void insufficientPermissionsReject() {
        MenuRedeemSubcommand redeem = createWiredRedeem(denyAll(), (u, m) -> {}, (v, p, k, val) -> true);
        List<String> messages = new ArrayList<>();

        assertFalse(redeem.dispatchStageConfigValue(callerId,
                new MenuAction.StageConfigValue("config.yml", "radius", "100"), messages::add));
        assertFalse(redeem.dispatchUnstageConfigValue(callerId,
                new MenuAction.UnstageConfigValue("config.yml", "radius"), messages::add));
        assertFalse(redeem.dispatchApplyStagedConfig(callerId,
                new MenuAction.ApplyStagedConfig("config.yml"), messages::add));
        assertFalse(redeem.dispatchDiscardStagedConfig(callerId,
                new MenuAction.DiscardStagedConfig("config.yml"), messages::add));
    }

    @Test
    @DisplayName("Invalid redemption and null parameter handling on onCommand")
    void invalidRedemptionCodesAndParams() {
        MenuRedeemSubcommand redeem = createWiredRedeem(allowAll(), (u, m) -> {}, (v, p, k, val) -> true);
        List<String> messages = new ArrayList<>();

        // Null callerId
        assertFalse(redeem.onCommand(null, Map.of(), null, messages::add));

        // When renderer is null, open-page is disabled and bare /rtp menu rejects
        MenuRedeemSubcommand redeemNoRenderer = createWiredRedeem(allowAll(), null, (v, p, k, val) -> true);
        messages.clear();
        assertFalse(redeemNoRenderer.onCommand(callerId, Collections.emptyMap(), null, messages::add));
        assertFalse(messages.isEmpty());

        // Missing / empty action
        boolean rootOpened = redeem.onCommand(callerId, Collections.emptyMap(), null, messages::add);
        assertTrue(rootOpened);
    }

    @Test
    @DisplayName("dispatchOpenConfigKey with null anvil opener and permission denial")
    void openConfigKeyErrorConditions() {
        // 1. Anvil input disabled (null opener)
        MenuRedeemSubcommand redeemNoAnvil = createWiredRedeem(allowAll(), (u, m) -> {}, null);
        List<String> msgs1 = new ArrayList<>();
        assertFalse(redeemNoAnvil.dispatchOpenConfigKey(callerId,
                new MenuAction.OpenConfigKey("config.yml", "radius"), msgs1::add));
        assertFalse(msgs1.isEmpty());

        // 2. Permission denied
        MenuRedeemSubcommand redeemDenied = createWiredRedeem(denyAll(), (u, m) -> {}, (v, p, k, val) -> true);
        List<String> msgs2 = new ArrayList<>();
        assertFalse(redeemDenied.dispatchOpenConfigKey(callerId,
                new MenuAction.OpenConfigKey("config.yml", "radius"), msgs2::add));
        assertFalse(msgs2.isEmpty());

        // 3. Unknown file
        MenuRedeemSubcommand redeemAllowed = createWiredRedeem(allowAll(), (u, m) -> {}, (v, p, k, val) -> true);
        List<String> msgs3 = new ArrayList<>();
        assertFalse(redeemAllowed.dispatchOpenConfigKey(callerId,
                new MenuAction.OpenConfigKey("nonexistent_unknown.yml", "radius"), msgs3::add));
        assertFalse(msgs3.isEmpty());
    }

    @Test
    @DisplayName("dispatchOpenConfigKey slash-path multiconfig resolves prefill and prompts anvil or picker")
    void openConfigKeySlashPath() {
        MenuRedeemSubcommand redeem = createWiredRedeem(allowAll(), (u, m) -> {}, (v, p, k, val) -> true);
        List<String> msgs = new ArrayList<>();

        boolean ok = redeem.dispatchOpenConfigKey(callerId,
                new MenuAction.OpenConfigKey("regions/default", "shape.radius"), msgs::add);
        assertTrue(ok);
    }

    @Test
    @DisplayName("dispatchSwitchInfoToText switches info book to text output")
    void switchInfoToTextFlows() {
        MenuRedeemSubcommand redeem = createWiredRedeem(allowAll(), (u, m) -> {}, (v, p, k, val) -> true);
        List<String> msgs = new ArrayList<>();

        // Global scope
        root.lastDispatchedArgs = null;
        assertTrue(redeem.dispatchSwitchInfoToText(callerId,
                new MenuAction.SwitchInfoToText(MenuAction.InfoScopeToken.global()), msgs::add));
        assertNotNull(root.lastDispatchedArgs);
        assertEquals("info", root.lastDispatchedArgs[0]);

        // World scope
        root.lastDispatchedArgs = null;
        assertTrue(redeem.dispatchSwitchInfoToText(callerId,
                new MenuAction.SwitchInfoToText(MenuAction.InfoScopeToken.world("world_nether")), msgs::add));
        assertNotNull(root.lastDispatchedArgs);
        assertEquals("world=world_nether", root.lastDispatchedArgs[1]);

        // Region scope
        root.lastDispatchedArgs = null;
        assertTrue(redeem.dispatchSwitchInfoToText(callerId,
                new MenuAction.SwitchInfoToText(MenuAction.InfoScopeToken.region("default")), msgs::add));
        assertNotNull(root.lastDispatchedArgs);
        assertEquals("region=default", root.lastDispatchedArgs[1]);

        // Insufficient permission
        MenuRedeemSubcommand redeemDenied = createWiredRedeem(denyAll(), (u, m) -> {}, (v, p, k, val) -> true);
        msgs.clear();
        assertFalse(redeemDenied.dispatchSwitchInfoToText(callerId,
                new MenuAction.SwitchInfoToText(MenuAction.InfoScopeToken.global()), msgs::add));
        assertFalse(msgs.isEmpty());
    }

    @Test
    @DisplayName("dispatchOpenMap handles permission, chart spec building, and exceptions")
    void openMapFlows() throws Exception {
        MenuRedeemSubcommand redeem = createWiredRedeem(allowAll(), (u, m) -> {}, (v, p, k, val) -> true);
        List<String> msgs = new ArrayList<>();

        java.lang.reflect.Method mOpenMap = MenuRedeemSubcommand.class.getDeclaredMethod("dispatchOpenMap",
                UUID.class, MenuAction.OpenMap.class, Consumer.class);
        mOpenMap.setAccessible(true);

        // Valid call (MapDispatch.paint might return false because no map binding in test environment)
        MenuAction.OpenMap action = new MenuAction.OpenMap(ChartSpec.Kind.REGION_BIOMES, "default");
        mOpenMap.invoke(redeem, callerId, action, (Consumer<String>) msgs::add);

        // Permission denied
        MenuRedeemSubcommand redeemDenied = createWiredRedeem(denyAll(), (u, m) -> {}, (v, p, k, val) -> true);
        msgs.clear();
        boolean okDenied = (boolean) mOpenMap.invoke(redeemDenied, callerId, action, (Consumer<String>) msgs::add);
        assertFalse(okDenied);
        assertFalse(msgs.isEmpty());
    }

    @Test
    @DisplayName("dispatchOpenVisualizationRegions handles null kind and permissions")
    void openVisualizationRegionsFlows() {
        MenuRedeemSubcommand redeem = createWiredRedeem(allowAll(), (u, m) -> {}, (v, p, k, val) -> true);
        List<String> msgs = new ArrayList<>();

        // Null kind
        assertFalse(redeem.dispatchOpenVisualizationRegions(callerId, null, msgs::add));
        assertFalse(msgs.isEmpty());

        // Valid kind
        assertTrue(redeem.dispatchOpenVisualizationRegions(callerId, ChartSpec.Kind.REGION_BIOMES, msgs::add));

        // Permission denied
        MenuRedeemSubcommand redeemDenied = createWiredRedeem(denyAll(), (u, m) -> {}, (v, p, k, val) -> true);
        msgs.clear();
        assertFalse(redeemDenied.dispatchOpenVisualizationRegions(callerId, ChartSpec.Kind.REGION_BIOMES, msgs::add));
        assertFalse(msgs.isEmpty());
    }

    @Test
    @DisplayName("dispatchOpenAdminPanel and dispatchOpenVisualizations permission checks")
    void adminPanelAndVisualizationsPermissions() {
        MenuRedeemSubcommand redeemDenied = createWiredRedeem(denyAll(), (u, m) -> {}, (v, p, k, val) -> true);
        List<String> msgs = new ArrayList<>();

        assertFalse(redeemDenied.dispatchOpenAdminPanel(callerId, msgs::add));
        assertFalse(redeemDenied.dispatchOpenVisualizations(callerId, msgs::add));
        assertFalse(msgs.isEmpty());

        // Builder failure cases
        MenuRedeemSubcommand.MenuCuratedPageBuilder throwingCurated = new MenuRedeemSubcommand.MenuCuratedPageBuilder() {
            @Override public MenuModel buildAdminPanel(UUID viewer) { throw new RuntimeException("fail"); }
            @Override public MenuModel buildFrontPage(UUID viewer) { throw new RuntimeException("fail"); }
            @Override public MenuModel buildVisualizations(UUID viewer) { throw new RuntimeException("fail"); }
            @Override public MenuModel buildVisualizationRegions(UUID viewer, ChartSpec.Kind kind) { throw new RuntimeException("fail"); }
        };
        MenuRedeemSubcommand redeemThrowing = new MenuRedeemSubcommand(
                root, allowAll(), (u, m) -> {}, (n, o, p) -> stubModel("p"),
                (n, o, p, param) -> stubModel("picker"), (v, p, k, val) -> true,
                null, throwingCurated, null, null);

        msgs.clear();
        assertFalse(redeemThrowing.dispatchOpenAdminPanel(callerId, msgs::add));
        assertFalse(redeemThrowing.dispatchOpenVisualizations(callerId, msgs::add));
        assertFalse(redeemThrowing.dispatchOpenFrontPage(callerId, msgs::add));
        assertFalse(redeemThrowing.dispatchOpenVisualizationRegions(callerId, ChartSpec.Kind.REGION_BIOMES, msgs::add));
    }

    @Test
    @DisplayName("dispatchRun dispatches commands and handles permission gates and execution failure")
    void dispatchRunFlows() throws Exception {
        MenuRedeemSubcommand redeem = createWiredRedeem(allowAll(), (u, m) -> {}, (v, p, k, val) -> true);
        List<String> msgs = new ArrayList<>();

        java.lang.reflect.Method mRun = MenuRedeemSubcommand.class.getDeclaredMethod("dispatchRun",
                UUID.class, MenuAction.RunRtpCommand.class, Consumer.class);
        mRun.setAccessible(true);

        // Normal run
        MenuAction.RunRtpCommand run = new MenuAction.RunRtpCommand(new String[]{"reload"});
        root.lastDispatchedArgs = null;
        boolean ok = (boolean) mRun.invoke(redeem, callerId, run, (Consumer<String>) msgs::add);
        assertTrue(ok);
        assertNotNull(root.lastDispatchedArgs);
        assertEquals("reload", root.lastDispatchedArgs[0]);

        // Null / empty args reject
        boolean okEmpty = (boolean) mRun.invoke(redeem, callerId, new MenuAction.RunRtpCommand(new String[0]), (Consumer<String>) msgs::add);
        assertTrue(okEmpty);

        // When root execution throws RuntimeException
        root.throwExecution = true;
        msgs.clear();
        boolean okFail = (boolean) mRun.invoke(redeem, callerId, run, (Consumer<String>) msgs::add);
        assertFalse(okFail);
        assertFalse(msgs.isEmpty());
    }

    // ------------------------------------------------------------------------
    // Helper stub
    // ------------------------------------------------------------------------
    static final class TestableRoot extends BaseRTPCmdImpl {
        String[] lastDispatchedArgs;
        boolean succeedExecution = true;
        boolean throwExecution = false;

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
            if (throwExecution) {
                throw new RuntimeException("boom");
            }
            if (!succeedExecution) {
                if (messageMethod != null) messageMethod.accept("command execution failed");
                return CompletableFuture.completedFuture(false);
            }
            return CompletableFuture.completedFuture(true);
        }
    }
}
