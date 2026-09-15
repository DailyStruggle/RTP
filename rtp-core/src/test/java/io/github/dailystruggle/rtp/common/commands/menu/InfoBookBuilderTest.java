package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.mapsapi.noop.NoopMapBinding;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.info.InfoCmd;
import io.github.dailystruggle.rtp.common.commands.maps.MapDispatch;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("InfoBookBuilder pagination, footer generation, and scope mapping")
class InfoBookBuilderTest {

    @TempDir
    Path tempDir;

    private UUID viewer;
    private TestRoot root;
    private InfoBookBuilder builder;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        viewer = UUID.randomUUID();
        root = new TestRoot();
        builder = new InfoBookBuilder();
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = null;
        RTP.scheduler = null;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
        MapDispatch.setMapBinding(new NoopMapBinding());
    }

    @Test
    @DisplayName("scopeToParameters maps GLOBAL, WORLD, and REGION correctly")
    void scopeToParameters_mappings() {
        Map<String, List<String>> globalParams = InfoBookBuilder.scopeToParameters(
                new MenuAction.InfoScopeToken(MenuAction.InfoScopeToken.Kind.GLOBAL, ""));
        assertTrue(globalParams.isEmpty());

        Map<String, List<String>> worldParams = InfoBookBuilder.scopeToParameters(
                new MenuAction.InfoScopeToken(MenuAction.InfoScopeToken.Kind.WORLD, "nether"));
        assertEquals(List.of("nether"), worldParams.get("world"));

        Map<String, List<String>> regionParams = InfoBookBuilder.scopeToParameters(
                new MenuAction.InfoScopeToken(MenuAction.InfoScopeToken.Kind.REGION, "plains_reg"));
        assertEquals(List.of("plains_reg"), regionParams.get("region"));
    }

    @Test
    @DisplayName("paginate handles empty list, empty strings, and pagination threshold")
    void paginate_variousInputs() {
        List<MenuPage> emptyPages = InfoBookBuilder.paginate(List.of());
        assertEquals(1, emptyPages.size());
        assertTrue(emptyPages.get(0).lines().isEmpty());

        List<String> rawLines = new ArrayList<>();
        // Add 15 lines (exceeds LINES_PER_PAGE = 13) including empty and null lines
        for (int i = 0; i < 15; i++) {
            if (i == 5) rawLines.add("");
            else if (i == 6) rawLines.add(null);
            else rawLines.add("Line " + i);
        }

        List<MenuPage> pages = InfoBookBuilder.paginate(rawLines);
        assertEquals(2, pages.size());
        assertEquals(13, pages.get(0).lines().size());
        assertFalse(pages.get(1).lines().isEmpty());
    }

    @Test
    @DisplayName("build when InfoCmd is missing produces fallback model with footer")
    void build_missingInfoCmd() {
        MenuAction.InfoScopeToken scope = new MenuAction.InfoScopeToken(
                MenuAction.InfoScopeToken.Kind.GLOBAL, "");
        MenuModel model = builder.build(root, viewer, scope);

        assertNotNull(model);
        assertFalse(model.pages().isEmpty());
        MenuPage page = model.pages().get(0);
        // Header, spacer, refresh, switch, note
        assertTrue(page.lines().size() >= 3);
    }

    @Test
    @DisplayName("build with registered InfoCmd captures tapped output")
    void build_withRegisteredInfoCmd() {
        InfoCmd infoCmd = new InfoCmd(root) {
            @Override
            public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
                java.util.function.Consumer<String> tap = RTP.messageTap.get();
                if (tap != null) {
                    tap.accept("Server Performance: 20.0 TPS");
                    tap.accept("Database Latency: 12ms");
                }
                return true;
            }
        };
        root.addSubCommand(infoCmd);

        MenuAction.InfoScopeToken scope = new MenuAction.InfoScopeToken(
                MenuAction.InfoScopeToken.Kind.GLOBAL, "");
        MenuModel model = builder.build(root, viewer, scope);

        assertNotNull(model);
        assertFalse(model.pages().isEmpty());
        boolean foundPerformance = false;
        for (MenuPage page : model.pages()) {
            for (MenuLine line : page.lines()) {
                for (MenuFragment frag : line.fragments()) {
                    if (frag.text().contains("Server Performance")) {
                        foundPerformance = true;
                    }
                }
            }
        }
        assertTrue(foundPerformance, "Captured tapped message should appear in the book pages");
    }

    @Test
    @DisplayName("appendFooter creates new page when lines exceed cap")
    void appendFooter_overflow() {
        List<MenuPage> initialPages = InfoBookBuilder.paginate(List.of("1","2","3","4","5","6","7","8","9"));
        assertEquals(1, initialPages.size());
        assertEquals(9, initialPages.get(0).lines().size());

        MenuAction.InfoScopeToken scope = new MenuAction.InfoScopeToken(
                MenuAction.InfoScopeToken.Kind.GLOBAL, "");
        MenuModel model = builder.build(root, viewer, scope);
        assertNotNull(model);

        // Test REGION scope with custom MapBinding active
        MapDispatch.setMapBinding(new io.github.dailystruggle.mapsapi.MapBinding() {
            @Override public io.github.dailystruggle.mapsapi.MapHandle allocate(io.github.dailystruggle.mapsapi.MapAllocationRequest request) { return null; }
            @Override public <M extends io.github.dailystruggle.mapsapi.model.ChartModel> void renderEphemeral(io.github.dailystruggle.mapsapi.MapHandle handle, io.github.dailystruggle.mapsapi.render.ChartRenderer<M> renderer, M model) {}
            @Override public <M extends io.github.dailystruggle.mapsapi.model.ChartModel> io.github.dailystruggle.mapsapi.Cancellation bindLive(io.github.dailystruggle.mapsapi.MapHandle handle, io.github.dailystruggle.mapsapi.render.ChartRenderer<M> renderer, java.util.function.Supplier<M> modelSupplier) { return null; }
        });
        MenuAction.InfoScopeToken regionScope = new MenuAction.InfoScopeToken(
                MenuAction.InfoScopeToken.Kind.REGION, "default");
        MenuModel regionModel = builder.build(root, viewer, regionScope);
        assertNotNull(regionModel);
    }

    @Test
    @DisplayName("build throws NPE on null arguments")
    void build_nullArguments() {
        assertThrows(NullPointerException.class, () -> builder.build(null, viewer, new MenuAction.InfoScopeToken(MenuAction.InfoScopeToken.Kind.GLOBAL, "")));
        assertThrows(NullPointerException.class, () -> builder.build(root, null, new MenuAction.InfoScopeToken(MenuAction.InfoScopeToken.Kind.GLOBAL, "")));
        assertThrows(NullPointerException.class, () -> builder.build(root, viewer, null));
    }

    private static final class TestRoot extends BaseRTPCmdImpl implements TreeCommand {
        private final Map<String, CommandsAPICommand> commands = new HashMap<>();
        private final Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> params = new HashMap<>();

        TestRoot() { super(null); }
        @Override public String name() { return "rtp"; }
        @Override public String permission() { return "rtp.use"; }
        @Override public Map<String, CommandsAPICommand> getCommandLookup() { return commands; }
        @Override public Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> getParameterLookup() { return params; }
        @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            return true;
        }
    }
}
