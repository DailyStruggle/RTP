package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuConsumerProfile;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.YamlCommentLookup;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CommandTreeMenuBuilder reflection and picker tests")
class CommandTreeMenuBuilderTest {

    @TempDir
    Path tempDir;

    private UUID caller;
    private CommandTreeMenuBuilder builder;
    private MenuConsumerProfile profile;
    private Predicate<String> allPerms;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        caller = UUID.randomUUID();
        builder = new CommandTreeMenuBuilder();
        allPerms = perm -> true;
        profile = new MenuConsumerProfile() {
            @Override
            public String suggestPrefix(java.util.Deque<String> commandPath, String parameterName) {
                return "/" + String.join(" ", commandPath) + " " + parameterName + "=";
            }

            @Override
            public YamlCommentLookup commentLookup() {
                return (file, key) -> Optional.of("Helpful documentation for " + key);
            }
        };
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = null;
        RTP.scheduler = null;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
    }

    @Test
    @DisplayName("build on root node includes title, hint, and subcommands/params")
    void build_rootNode() {
        TestableTree root = new TestableTree("rtp");
        TestableTree reload = new TestableTree("reload");
        root.addSubCommand(reload);

        BooleanParameter boolParam = new BooleanParameter("rtp.use", "enable flag", (id, p) -> true);
        root.addParameter("enabled", boolParam);

        MenuModel model = builder.build(root, caller, allPerms, profile);
        assertNotNull(model);
        assertEquals("rtp", model.title());
        assertEquals(1, model.pages().size());

        MenuPage page = model.pages().get(0);
        // Should contain root title, hint, subcommand row, parameter row
        assertTrue(page.lines().size() >= 3);

        boolean foundReload = false;
        boolean foundEnabled = false;
        for (MenuLine line : page.lines()) {
            for (MenuFragment frag : line.fragments()) {
                if ("reload".equalsIgnoreCase(frag.text())) foundReload = true;
                if ("enabled".equalsIgnoreCase(frag.text())) {
                    foundEnabled = true;
                    assertEquals("Helpful documentation for enabled", frag.hover());
                    assertTrue(frag.action() instanceof MenuAction.OpenParamPicker);
                }
            }
        }
        assertTrue(foundReload);
        assertTrue(foundEnabled);
    }

    @Test
    @DisplayName("build on nested path includes constructed breadcrumb, back row, and execute row")
    void build_nestedPath() {
        TestableTree node = new TestableTree("settings");
        MenuModel model = builder.build(node, caller, allPerms, profile, List.of("config", "settings"));

        assertNotNull(model);
        MenuPage page = model.pages().get(0);
        boolean foundBack = false;
        boolean foundExecute = false;

        for (MenuLine line : page.lines()) {
            for (MenuFragment frag : line.fragments()) {
                if (frag.action() instanceof MenuAction.OpenMenu openMenu) {
                    assertEquals(1, openMenu.path().length);
                    assertEquals("config", openMenu.path()[0]);
                    foundBack = true;
                }
                if (frag.action() instanceof MenuAction.RunRtpCommand runCmd) {
                    assertEquals(2, runCmd.args().length);
                    assertEquals("config", runCmd.args()[0]);
                    assertEquals("settings", runCmd.args()[1]);
                    foundExecute = true;
                }
            }
        }
        assertTrue(foundBack);
        assertTrue(foundExecute);
    }

    @Test
    @DisplayName("buildParamPicker with suggestion overflow creates paginated picker pages")
    void buildParamPicker_pagination() {
        TestableTree node = new TestableTree("regions");
        Set<String> suggestions = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            suggestions.add("item_" + (i < 10 ? "0" + i : i));
        }

        CommandParameter param = new CommandParameter("rtp.use", "description", (id, p) -> true) {
            @Override
            public Set<String> relevantValues(UUID senderId) {
                return suggestions;
            }

            @Override
            public Set<String> values() {
                return suggestions;
            }
        };
        node.getParameterLookup().put("target", param);

        MenuModel model = builder.buildParamPicker(node, caller, allPerms, profile, List.of("regions"), "target");
        assertNotNull(model);
        assertEquals(3, model.pages().size());

        for (MenuPage page : model.pages()) {
            assertFalse(page.lines().isEmpty());
        }
    }

    @Test
    @DisplayName("buildParamPicker for integer parameter creates hover from type fallback")
    void buildParamPicker_typeFallbackHover() {
        MenuConsumerProfile noCommentProfile = MenuConsumerProfile.defaultProfile();
        TestableTree node = new TestableTree("region");

        IntegerParameter intParam = new IntegerParameter("rtp.use", "radius", (id, p) -> true, 0, 1000);
        node.addParameter("radius", intParam);

        MenuModel model = builder.build(node, caller, allPerms, noCommentProfile);
        assertNotNull(model);

        boolean foundHover = false;
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                if ("radius".equalsIgnoreCase(frag.text()) && frag.hover() != null) {
                    assertTrue(frag.hover().contains("integer"));
                    foundHover = true;
                }
            }
        }
        assertTrue(foundHover);
    }

    private static final class TestableTree extends BaseRTPCmdImpl implements TreeCommand {
        private final String name;
        private final Map<String, CommandsAPICommand> commands = new HashMap<>();
        private final Map<String, CommandParameter> params = new HashMap<>();

        TestableTree(String name) {
            super(null);
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public String permission() { return "rtp.use"; }
        @Override public Map<String, CommandsAPICommand> getCommandLookup() { return commands; }
        @Override public Map<String, CommandParameter> getParameterLookup() { return params; }
        @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            return true;
        }
    }
}
