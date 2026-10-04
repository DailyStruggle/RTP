package io.github.dailystruggle.rtp.common.commands.docs;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.CoreRtpRoot;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-045 / ADR-104 DocsCmd unit tests")
class DocsCmdTest {

    @TempDir
    File tempDir;

    private MockRTPServerAccessor accessor;
    private CoreRtpRoot root;
    private MockRTPPlayer player;

    @BeforeEach
    void setUp() throws Exception {
        accessor = RTPTestSetup.install(tempDir);
        root = new CoreRtpRoot();
        player = new MockRTPPlayer(UUID.randomUUID(), "DocsTester", null);
        accessor.addPlayer(player);

        // Populate DocsRegistry with test manuals
        Path wikiDir = tempDir.toPath().resolve("wiki");
        Files.createDirectories(wikiDir);
        Files.writeString(wikiDir.resolve("index.md"), "# Welcome to RTP Docs\nOverview of RTP commands.");
        Files.writeString(wikiDir.resolve("setup.md"), "# Setup Guide\nHow to install RTP.");

        DocsRegistry.getInstance().rebuild(wikiDir, DocsLoweringOptions.defaults());
    }

    @Test
    @DisplayName("Command tree resolution: /rtp docs is attached to CoreRtpRoot with subcommands")
    void testCommandTreeResolution() {
        CommandsAPICommand docsSub = root.getCommandLookup().get("DOCS");
        assertNotNull(docsSub, "DocsCmd should be registered as subcommand 'docs' on CoreRtpRoot");
        assertInstanceOf(DocsCmd.class, docsSub);

        DocsCmd docsCmd = (DocsCmd) docsSub;
        assertEquals("docs", docsCmd.name());
        assertEquals(DocsCmd.PERMISSION, docsCmd.permission());
        assertEquals("rtp.admin.docs", docsCmd.permission());

        CommandsAPICommand listSub = docsCmd.getCommandLookup().get("LIST");
        assertNotNull(listSub, "DocsListSubCmd should be registered under /rtp docs");
        assertInstanceOf(DocsListSubCmd.class, listSub);

        CommandsAPICommand exportSub = docsCmd.getCommandLookup().get("EXPORT");
        assertNotNull(exportSub, "DocsExportSubCmd should be registered under /rtp docs");
        assertInstanceOf(DocsExportSubCmd.class, exportSub);

        assertNotNull(docsCmd.getParameterLookup().get(DocsCmd.PARAM_TOPIC), "Topic parameter should be registered on DocsCmd");
    }

    @Test
    @DisplayName("DocsTopicParameter dynamically yields registered manual topics")
    void testDocsTopicParameterValues() {
        DocsTopicParameter param = new DocsTopicParameter("rtp.admin.docs", "desc");
        Set<String> values = param.values();
        assertTrue(values.contains("index"), "Should contain index topic");
        assertTrue(values.contains("setup"), "Should contain setup topic");
    }

    @Test
    @DisplayName("/rtp docs defaults to index page")
    void testDocsDefaultIndexDispatch() {
        DocsCmd docsCmd = (DocsCmd) root.getCommandLookup().get("DOCS");
        assertNotNull(docsCmd);

        // Player dispatch triggers renderer
        boolean result = docsCmd.onCommand(player.uuid(), Collections.emptyMap(), null);
        assertTrue(result);

        // Console dispatch triggers fallback text rendering
        MockRTPPlayer console = (MockRTPPlayer) accessor.getConsolePlayer();
        assertNotNull(console);
        console.sentMessages.clear();

        boolean consoleResult = docsCmd.onCommand(console.uuid(), Collections.emptyMap(), null);
        assertTrue(consoleResult);

        // Verify console received index content
        assertFalse(console.sentMessages.isEmpty(), "Console should receive message output");
        assertTrue(console.sentMessages.stream().anyMatch(msg -> msg.contains("Welcome to RTP Docs") || msg.contains("RTP Docs") || msg.contains("Documentation Index")),
                "Sent messages should contain index content");
    }

    @Test
    @DisplayName("/rtp docs <topic> renders specific topic")
    void testDocsSpecificTopicDispatch() {
        DocsCmd docsCmd = (DocsCmd) root.getCommandLookup().get("DOCS");
        assertNotNull(docsCmd);

        // Player dispatch succeeds
        boolean result = docsCmd.onCommand(player.uuid(), Map.of("topic", List.of("setup")), null);
        assertTrue(result);

        // Console dispatch fallback renders lines
        MockRTPPlayer console = (MockRTPPlayer) accessor.getConsolePlayer();
        assertNotNull(console);
        console.sentMessages.clear();

        boolean consoleResult = docsCmd.onCommand(console.uuid(), Map.of("topic", List.of("setup")), null);
        assertTrue(consoleResult);

        assertTrue(console.sentMessages.stream().anyMatch(msg -> msg.contains("Setup Guide") || msg.contains("Setup")),
                "Sent messages should contain setup guide content");
    }

    @Test
    @DisplayName("/rtp docs <nonexistent> sends notFound message without swallowing error (S-004, S-007)")
    void testDocsNotFoundHandling() {
        DocsCmd docsCmd = (DocsCmd) root.getCommandLookup().get("DOCS");
        assertNotNull(docsCmd);

        boolean result = docsCmd.onCommand(player.uuid(), Map.of("topic", List.of("nonexistent_manual_topic")), null);
        assertTrue(result);

        assertTrue(player.sentMessages.stream().anyMatch(msg -> msg.contains("not found") || msg.contains("nonexistent_manual_topic")),
                "Player should receive not-found notification");
    }

    @Test
    @DisplayName("/rtp docs list enumerates all available topics")
    void testDocsListDispatch() {
        DocsCmd docsCmd = (DocsCmd) root.getCommandLookup().get("DOCS");
        assertNotNull(docsCmd);

        CommandsAPICommand listSub = docsCmd.getCommandLookup().get("LIST");
        assertNotNull(listSub);

        boolean result = listSub.onCommand(player.uuid(), Collections.emptyMap(), null);
        assertTrue(result);

        assertTrue(player.sentMessages.stream().anyMatch(msg -> msg.contains("Available Documentation Topics")),
                "Player should receive topics header");
        assertTrue(player.sentMessages.stream().anyMatch(msg -> msg.contains("index")),
                "Topics list should list index");
        assertTrue(player.sentMessages.stream().anyMatch(msg -> msg.contains("setup")),
                "Topics list should list setup");
    }

    @Test
    @DisplayName("/rtp docs export executes asynchronously and outputs standalone bundle (S-005)")
    void testDocsExportDispatch() throws Exception {
        DocsCmd docsCmd = (DocsCmd) root.getCommandLookup().get("DOCS");
        assertNotNull(docsCmd);

        CommandsAPICommand exportSub = docsCmd.getCommandLookup().get("EXPORT");
        assertNotNull(exportSub);

        boolean result = exportSub.onCommand(player.uuid(), Collections.emptyMap(), null);
        assertTrue(result);

        // Verify async export completion
        File expectedBundle = new File(accessor.getPluginDirectory(), "docs-bundle.html");
        for (int i = 0; i < 50; i++) {
            if (expectedBundle.exists() && expectedBundle.length() > 0) break;
            Thread.sleep(100);
        }
        assertTrue(expectedBundle.exists(), "Exported HTML bundle must exist");
        assertTrue(expectedBundle.length() > 0, "Exported HTML bundle must not be empty");
    }
}
