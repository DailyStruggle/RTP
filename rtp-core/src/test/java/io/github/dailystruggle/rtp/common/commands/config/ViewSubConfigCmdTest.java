package io.github.dailystruggle.rtp.common.commands.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Behaviour contract for {@link ViewSubConfigCmd}: identity / permission /
 * delegation are pure and asserted directly; the file-streaming path is
 * exercised through the mock scheduler (synchronous) and verified against
 * {@link MockRTPPlayer#sentMessages}.
 *
 * <p>Also asserts that {@link SubConfigCmd#addParameters()} registers the
 * {@code view} sub-command for {@link ConfigParser}-backed factories &mdash;
 * that registration is the integration point users invoke as
 * {@code /rtp config &lt;file&gt; view}.
 */
public class ViewSubConfigCmdTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private ConfigParser<PerformanceKeys> performanceConfig;

    @BeforeEach
    void setUp() throws Exception {
        accessor = RTPTestSetup.install(tempDir.toFile());

        // A messages parser is required so SubConfigCmd's addParameters() path
        // (which reads MessagesKeys via lang()) does not NPE if exercised.
        ConfigParser<MessagesKeys> lang = mock(ConfigParser.class);
        lang.language_mapping = new ConcurrentHashMap<>();
        lang.reverse_language_mapping = new ConcurrentHashMap<>();
        lang.name = "messages.yml";
        doReturn(new EnumMap<>(MessagesKeys.class)).when(lang).getData();
        when(lang.getConfigValue(any(), any())).thenReturn("");
        RTP.configs.configParserMap.put(MessagesKeys.class, lang);

        performanceConfig = mock(ConfigParser.class);
        performanceConfig.language_mapping = new ConcurrentHashMap<>();
        performanceConfig.reverse_language_mapping = new ConcurrentHashMap<>();
        performanceConfig.name = "performance.yml";
        performanceConfig.pluginDirectory = tempDir.toFile();

        Field myClassField = io.github.dailystruggle.rtp.common.factory.FactoryValue.class
                .getDeclaredField("myClass");
        myClassField.setAccessible(true);
        myClassField.set(performanceConfig, PerformanceKeys.class);

        EnumMap<PerformanceKeys, Object> data = new EnumMap<>(PerformanceKeys.class);
        data.put(PerformanceKeys.viewDistanceSelect, 5L);
        doReturn(data).when(performanceConfig).getData();
    }

    // ── identity ──────────────────────────────────────────────────────────────

    @Test
    void name_isView() {
        assertEquals("view", new ViewSubConfigCmd(null, performanceConfig).name());
    }

    @Test
    void permission_isRtpConfig() {
        assertEquals("rtp.config", new ViewSubConfigCmd(null, performanceConfig).permission());
    }

    @Test
    void description_isNonEmpty() {
        String desc = new ViewSubConfigCmd(null, performanceConfig).description();
        assertNotNull(desc);
        assertFalse(desc.isBlank());
    }

    // ── onCommand — file streaming ────────────────────────────────────────────

    @Test
    void onCommand_streamsFileContentsToCaller() throws IOException {
        File yaml = new File(tempDir.toFile(), "performance.yml");
        Files.write(
                yaml.toPath(),
                ("foo: 1\n" + "bar: two\n" + "baz: three\n").getBytes(StandardCharsets.UTF_8));

        UUID callerId = UUID.randomUUID();
        MockRTPPlayer caller = new MockRTPPlayer(callerId, "viewer",
                new RTPLocation(new MockRTPWorld("default"), 0, 0, 0));
        accessor.addPlayer(caller);

        ViewSubConfigCmd cmd = new ViewSubConfigCmd(null, performanceConfig);
        boolean ok = cmd.onCommand(callerId, new HashMap<>(), null);

        assertTrue(ok);
        // header + 3 content lines, in order, were emitted to the caller
        assertTrue(
                caller.sentMessages.stream().anyMatch(m -> m.contains("performance.yml") && m.contains("3 lines")),
                "header must name the file and report line count; got " + caller.sentMessages);
        assertTrue(caller.sentMessages.contains("foo: 1"), "expected raw line 'foo: 1' in " + caller.sentMessages);
        assertTrue(caller.sentMessages.contains("bar: two"));
        assertTrue(caller.sentMessages.contains("baz: three"));
    }

    @Test
    void onCommand_missingFile_reportsAndDoesNotThrow() {
        // No file written; pluginDirectory + name resolves to a non-existent path.
        UUID callerId = UUID.randomUUID();
        MockRTPPlayer caller = new MockRTPPlayer(callerId, "viewer",
                new RTPLocation(new MockRTPWorld("default"), 0, 0, 0));
        accessor.addPlayer(caller);

        ViewSubConfigCmd cmd = new ViewSubConfigCmd(null, performanceConfig);
        assertTrue(cmd.onCommand(callerId, new HashMap<>(), null));

        assertTrue(
                caller.sentMessages.stream().anyMatch(m -> m.contains("file not found")),
                "missing-file case must surface a player-visible diagnostic (S-004); got "
                        + caller.sentMessages);
    }

    @Test
    void onCommand_delegatesToNextCommandWhenPresent() {
        CommandsAPICommand next = mock(CommandsAPICommand.class);
        when(next.onCommand(any(), any(), any())).thenReturn(true);

        ViewSubConfigCmd cmd = new ViewSubConfigCmd(null, performanceConfig);
        cmd.onCommand(UUID.randomUUID(), new HashMap<>(), next);

        verify(next, times(1)).onCommand(any(), any(), any());
    }

    // ── integration: SubConfigCmd registers `view` ────────────────────────────

    @Test
    void subConfigCmd_registersViewSubcommand() {
        SubConfigCmd sub = new SubConfigCmd(null, "performance.yml", performanceConfig);
        // CommandsAPI uppercases sub-command names in the lookup map.
        boolean hasView = sub.getCommandLookup().keySet().stream()
                .anyMatch(k -> k.equalsIgnoreCase("view"));
        assertTrue(
                hasView,
                "SubConfigCmd must register a 'view' sub-command for ConfigParser factories; got "
                        + sub.getCommandLookup().keySet());
        CommandsAPICommand viewCmd = sub.getCommandLookup().get("view");
        if (viewCmd == null) viewCmd = sub.getCommandLookup().get("VIEW");
        assertInstanceOf(ViewSubConfigCmd.class, viewCmd);
    }
}
