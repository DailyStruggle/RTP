package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MenuMirrorSubcommand mirror tree and command execution")
class MenuMirrorSubcommandTest {

    @TempDir
    Path tempDir;

    private MenuRedeemSubcommand redeem;
    private TestableTree targetTree;
    private TestableRoot root;

    private static CommandParameter dummyParam(String desc) {
        return new CommandParameter("rtp.use", desc, (u, v) -> true) {
            @Override
            public Set<String> values() {
                return Collections.emptySet();
            }
        };
    }

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        root = new TestableRoot();
        redeem = new MenuRedeemSubcommand(
                root,
                id -> perm -> true,
                (MenuRenderer) null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        targetTree = new TestableTree("custom");
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = null;
        RTP.scheduler = null;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
    }

    @Test
    @DisplayName("Constructor mirrors parameters and child tree commands recursively")
    void mirrorsParametersAndChildren() {
        targetTree.addParameter("foo", dummyParam("foo param"));
        TestableTree childTree = new TestableTree("child");
        childTree.addParameter("bar", dummyParam("bar param"));
        targetTree.addSubCommand(childTree);

        MenuMirrorSubcommand mirror = new MenuMirrorSubcommand(
                redeem,
                root,
                targetTree,
                List.of("custom")
        );

        assertEquals("custom", mirror.name());
        assertEquals(MenuRedeemSubcommand.PERMISSION, mirror.permission());
        assertSame(targetTree, mirror.target());
        assertSame(redeem, mirror.redeem());
        assertEquals(List.of("custom"), mirror.path());
        assertTrue(mirror.getParameterLookup().containsKey("foo"));

        CommandsAPICommand mirroredChild = mirror.getCommandLookup().get("CHILD");
        assertNotNull(mirroredChild);
        assertTrue(mirroredChild instanceof MenuMirrorSubcommand);
        MenuMirrorSubcommand childMirror = (MenuMirrorSubcommand) mirroredChild;
        assertEquals("child", childMirror.name());
        assertEquals(List.of("custom", "child"), childMirror.path());
        assertTrue(childMirror.getParameterLookup().containsKey("bar"));
    }

    @Test
    @DisplayName("onCommand returns true when nextCommand is not null")
    void onCommand_withNextCommand_returnsTrue() {
        MenuMirrorSubcommand mirror = new MenuMirrorSubcommand(
                redeem,
                root,
                targetTree,
                List.of("custom")
        );

        CommandsAPICommand dummy = new BaseRTPCmdImpl(mirror) {
            @Override public String name() { return "dummy"; }
            @Override public String permission() { return "rtp.use"; }
            @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
                return true;
            }
        };

        boolean res = mirror.onCommand(UUID.randomUUID(), Map.of(), dummy);
        assertTrue(res);
    }

    @Test
    @DisplayName("onCommand delegates to redeem.renderForPath when nextCommand is null")
    void onCommand_leafDispatch_delegatesToRedeem() {
        AtomicBoolean rendered = new AtomicBoolean(false);
        MenuRenderer mockRenderer = (u, m) -> rendered.set(true);
        MenuRedeemSubcommand.MenuPageBuilder pageBuilder = (node, open, assembledPath) -> {
            return new io.github.dailystruggle.rtp.api.menu.MenuModel(
                    "custom",
                    List.of(new io.github.dailystruggle.rtp.api.menu.MenuPage(List.of())));
        };

        redeem = new MenuRedeemSubcommand(
                root,
                id -> perm -> true,
                mockRenderer,
                pageBuilder,
                null,
                null,
                null,
                null,
                null,
                null
        );

        MenuMirrorSubcommand mirror = new MenuMirrorSubcommand(
                redeem,
                root,
                targetTree,
                List.of("custom")
        );

        Map<String, List<String>> params = new HashMap<>();
        params.put("page", List.of("2"));
        boolean res = mirror.onCommand(UUID.randomUUID(), params, null);
        assertTrue(res);
        assertTrue(rendered.get());
    }

    private static final class TestableRoot extends BaseRTPCmdImpl {
        TestableRoot() { super(null); }
        @Override public String name() { return "rtp"; }
        @Override public String permission() { return "rtp.use"; }
        @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            return true;
        }
    }

    private static final class TestableTree extends BaseRTPCmdImpl implements TreeCommand {
        private final String name;
        TestableTree(String name) {
            super(null);
            this.name = name;
        }
        @Override public String name() { return name; }
        @Override public String permission() { return "rtp.use"; }
        @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            return true;
        }
    }
}
