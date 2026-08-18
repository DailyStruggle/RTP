package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ChatMenuRenderer} and {@link MenuActionToCommand}.
 */
class ChatMenuRendererTest {

    @TempDir File tempDir;

    /** Live recorder of every {@code sendMessageWithRunCommand} call. */
    private final List<RunCommandCall> runCalls = new CopyOnWriteArrayList<>();
    /** Live recorder of every {@code sendMessage(target,msg,hover,click,tag)} call. */
    private final List<HoverCall> hoverCalls = new CopyOnWriteArrayList<>();

    private UUID viewer;

    @BeforeEach
    void setUp() {
        // Install the standard fixture so RTP.serverAccessor / RTP.scheduler /
        // RTP.getInstance() are wired, then swap in a capturing subclass.
        RTPTestSetup.install(tempDir);
        viewer = UUID.randomUUID();
        CapturingAccessor capturing = new CapturingAccessor(tempDir);
        capturing.addPlayer(new MockRTPPlayer(viewer, "viewer", null));
        // Replace both static fields exposed by RTPTestSetup.install so the
        // renderer's lookups land on the capturing instance.
        RTP.serverAccessor = capturing;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = capturing;
    }

    // ------------------------------------------------------------------
    // MenuActionToCommand mapping (helper-level)
    // ------------------------------------------------------------------

    @Test
    void runRtpCommand_joinsArgsWithSingleSpaces() {
        String cmd = MenuActionToCommand.toRunCommand(
                new MenuAction.RunRtpCommand(new String[] { "world=mining", "biome=plains" }));
        assertEquals("/rtp world=mining biome=plains", cmd);
    }

    @Test
    void runRtpCommand_emptyArgsDegradesToBareRtp() {
        String cmd = MenuActionToCommand.toRunCommand(
                new MenuAction.RunRtpCommand(new String[0]));
        assertEquals("/rtp", cmd);
    }

    @Test
    void openMenu_rootPathOmitsPathEqualsSentinel() {
        // Empty path must NOT emit `path=` - commands-api TreeCommand
        // rejects parameters whose value is empty (TreeCommand:224).
        String cmd = MenuActionToCommand.toRunCommand(
                new MenuAction.OpenMenu(new String[0]));
        assertEquals("/rtp menu open", cmd);
    }

    @Test
    void openMenu_nestedPathEmitsDottedPathArg() {
        String cmd = MenuActionToCommand.toRunCommand(
                new MenuAction.OpenMenu(new String[] { "config", "regions" }));
        assertEquals("/rtp menu open path=config.regions", cmd);
    }

    @Test
    void changePageCommand_is1Indexed() {
        // The MenuAction.ChangePage record stores 0-indexed page numbers;
        // the wire form is 1-indexed (so menu page 1 = n=1, not n=0).
        assertEquals("/rtp menu page n=1",
                MenuActionToCommand.changePageCommand(new MenuAction.ChangePage(0)));
        assertEquals("/rtp menu page n=4",
                MenuActionToCommand.changePageCommand(new MenuAction.ChangePage(3)));
    }

    @Test
    void rendererOnlyVariants_returnNullFromToRunCommand() {
        assertAll(
                () -> assertNull(MenuActionToCommand.toRunCommand(new MenuAction.ChangePage(0))),
                () -> assertNull(MenuActionToCommand.toRunCommand(new MenuAction.SuggestInput("/rtp x="))),
                () -> assertNull(MenuActionToCommand.toRunCommand(
                        new MenuAction.OpenExternalUrl(java.net.URI.create("https://example.com")))));
    }

    @Test
    void openConfigKey_emitsFileAndKey() {
        String cmd = MenuActionToCommand.toRunCommand(
                new MenuAction.OpenConfigKey("worlds", "centerX"));
        assertEquals("/rtp menu config file=worlds key=centerX", cmd);
    }

    @Test
    void stageConfigValue_emitsFileKeyValue() {
        String cmd = MenuActionToCommand.toRunCommand(
                new MenuAction.StageConfigValue("worlds", "centerX", "100"));
        assertEquals("/rtp menu stage file=worlds key=centerX value=100", cmd);
    }

    // ------------------------------------------------------------------
    // ChatMenuRenderer end-to-end
    // ------------------------------------------------------------------

    @Test
    void render_emitsTitle_thenPlainLine_thenOneRunCallPerClickableFragment() {
        // Page 1: title + one decorative line ("hello world") split across
        // two fragments + one mixed line with two clickable fragments.
        MenuLine decorative = new MenuLine(List.of(
                MenuFragment.plain("hello "),
                MenuFragment.plain("world")));
        MenuLine mixed = new MenuLine(List.of(
                MenuFragment.plain("[label] "),
                new MenuFragment("[click me]", "open admin menu",
                        new MenuAction.OpenMenu(new String[] { "admin" })),
                new MenuFragment("[other]", null,
                        new MenuAction.OpenAdminPanel())));
        MenuModel model = new MenuModel(
                "&aTITLE",
                List.of(new MenuPage(List.of(decorative, mixed))));

        new ChatMenuRenderer().render(viewer, model);

        // Expected output sequence on the recorder, in order:
        //   plain  "&aTITLE"             (title line)
        //   plain  "hello world"         (decorative coalesce)
        //   plain  "[label] "            (decorative leading fragment of mixed line)
        //   RUN    "[click me]" -> /rtp menu open path=admin
        //   RUN    "[other]"    -> /rtp menu admin
        List<RunCommandCall> runs = new ArrayList<>(runCalls);
        List<HoverCall> hovers = new ArrayList<>(hoverCalls);
        RTPCommandSender target = RTP.serverAccessor.getSender(viewer);
        List<String> plain = ((MockRTPPlayer) target).sentMessages;

        assertAll(
                () -> assertTrue(plain.contains("&aTITLE"),
                        "expected title in plain stream; got " + plain),
                () -> assertTrue(plain.contains("hello world"),
                        "expected decorative coalesce in plain stream; got " + plain),
                () -> assertEquals(2, runs.size(),
                        "expected one run-command call per clickable fragment; got " + runs),
                () -> assertEquals("[click me]", runs.get(0).text()),
                () -> assertEquals("/rtp menu open path=admin", runs.get(0).runCommand()),
                () -> assertEquals("open admin menu", runs.get(0).hover()),
                () -> assertEquals("[other]", runs.get(1).text()),
                () -> assertEquals("/rtp menu admin", runs.get(1).runCommand()),
                // Decorative leading fragment of mixed line goes through the
                // hover sink with an empty click, NOT the run sink.
                () -> assertTrue(hovers.isEmpty() || hovers.stream()
                                .noneMatch(h -> "/rtp".equals(h.click)),
                        "decorative leading fragment must not produce a run-command click; got hovers=" + hovers)
        );
    }

    @Test
    void render_multiplePages_emitsPageDivider() {
        MenuPage p1 = new MenuPage(List.of(new MenuLine(List.of(MenuFragment.plain("a")))));
        MenuPage p2 = new MenuPage(List.of(new MenuLine(List.of(MenuFragment.plain("b")))));
        MenuModel model = new MenuModel("", List.of(p1, p2));

        new ChatMenuRenderer().render(viewer, model);

        MockRTPPlayer player = (MockRTPPlayer) RTP.serverAccessor.getSender(viewer);
        // Expect dividers around both pages.
        long dividers = player.sentMessages.stream()
                .filter(m -> m.startsWith("&8-- page "))
                .count();
        assertEquals(2, dividers,
                "expected one divider per page when pageCount > 1; got " + player.sentMessages);
    }

    @Test
    void render_changePage_emitsRunCommandFor1IndexedPage() {
        MenuLine paginator = new MenuLine(List.of(
                new MenuFragment("[next]", null, new MenuAction.ChangePage(2))));
        MenuModel model = new MenuModel(
                "",
                List.of(new MenuPage(List.of(paginator))));

        new ChatMenuRenderer().render(viewer, model);

        assertEquals(1, runCalls.size());
        RunCommandCall c = runCalls.get(0);
        assertEquals("[next]", c.text());
        assertEquals("/rtp menu page n=3", c.runCommand(),
                "ChangePage(2) is the 0-indexed third page; wire form is n=3");
    }

    @Test
    void render_suggestInputFragment_degradesToPlainText() {
        MenuLine line = new MenuLine(List.of(
                new MenuFragment("[type]", "type a value",
                        new MenuAction.SuggestInput("/rtp x="))));
        MenuModel model = new MenuModel("", List.of(new MenuPage(List.of(line))));

        new ChatMenuRenderer().render(viewer, model);

        assertEquals(0, runCalls.size(),
                "SuggestInput has no run-command analogue; must NOT emit a run-command call");
        // Plain text + hover lands on the hover-only sink with empty click.
        assertEquals(1, hoverCalls.size(),
                "SuggestInput fragment with hover must produce one hover-only call");
        HoverCall hc = hoverCalls.get(0);
        assertEquals("[type]", hc.text);
        assertEquals("type a value", hc.hover);
        assertEquals("", hc.click);
    }

    // ------------------------------------------------------------------
    // Capturing accessor: subclass of the standard mock so default
    // RTPTestSetup wiring stays usable.
    // ------------------------------------------------------------------

    private final class CapturingAccessor extends MockRTPServerAccessor {
        CapturingAccessor(File pluginDir) {
            super(pluginDir);
        }

        @Override
        public void sendMessageWithRunCommand(RTPCommandSender target,
                                              String message, String hover,
                                              String runCommand, String tag) {
            runCalls.add(new RunCommandCall(
                    target == null ? null : target.uuid(),
                    message, hover, runCommand, tag));
        }

        @Override
        public void sendMessage(RTPCommandSender target,
                                String message, String hover,
                                String click, String tag) {
            hoverCalls.add(new HoverCall(message, hover, click));
        }
    }

    private record RunCommandCall(UUID viewer, String text, String hover,
                                   String runCommand, String tag) { }

    private record HoverCall(String text, String hover, String click) { }
}
