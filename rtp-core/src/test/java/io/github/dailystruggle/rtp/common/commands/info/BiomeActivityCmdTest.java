package io.github.dailystruggle.rtp.common.commands.info;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for {@code /rtp info biomes} ({@link BiomeActivityCmd}): the
 * empty-snapshot short-circuit, the ranked leaderboard render (header +
 * descending-count rows with token substitution), the {@link RTP#messageTap}
 * capture path, and {@code nextCommand} delegation. Templates are injected
 * explicitly via the {@link CommandMessages} parser so token substitution is
 * exercised deterministically regardless of the shipped locale defaults.
 */
class BiomeActivityCmdTest {

    @TempDir
    Path tempDir;

    private final List<String> captured = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        RTP.biomeActivity.reset();
        captured.clear();
        RTP.messageTap.set(captured::add);
        installTemplates(
                "no biomes sampled",
                "biomes total=[biomeTotalSamples] distinct=[biomeDistinctCount]",
                "[biomeRank]. [biomeName] [biomePercent]% ([biomeSamples]) [biomeBar]");
    }

    @AfterEach
    void tearDown() {
        RTP.messageTap.remove();
        RTP.biomeActivity.reset();
    }

    @SuppressWarnings("unchecked")
    private static void installTemplates(String empty, String header, String row) {
        ConfigParser<CommandMessages> lang =
                (ConfigParser<CommandMessages>) RTP.configs.getParser(CommandMessages.class);
        lang.setData(Map.of(
                "infoBiomeActivityEmpty", empty,
                "infoBiomeActivityHeader", header,
                "infoBiomeActivityRow", row));
    }

    private static boolean run(UUID caller) {
        return new BiomeActivityCmd(null).onCommand(caller, new HashMap<>(), null);
    }

    @Test
    @DisplayName("biomes metadata exposes the verb and rtp.info permission")
    void metadata() {
        BiomeActivityCmd cmd = new BiomeActivityCmd(null);
        assertEquals("biomes", cmd.name());
        assertEquals("rtp.info", cmd.permission());
    }

    @Test
    @DisplayName("no samples yet emits only the empty-line message")
    void emptySnapshot_emitsEmptyMessage() {
        boolean handled = run(RTPAPI.serverId);

        assertTrue(handled, "command reports handled");
        assertEquals(1, captured.size(), "exactly the empty line is emitted");
        assertEquals("no biomes sampled", captured.get(0));
    }

    @Test
    @DisplayName("populated snapshot renders header plus descending-count rows with substituted tokens")
    void populatedSnapshot_rendersRankedLeaderboard() {
        // 6 total: PLAINS(3) > FOREST(2) > DESERT(1)
        RTP.biomeActivity.record("PLAINS");
        RTP.biomeActivity.record("PLAINS");
        RTP.biomeActivity.record("PLAINS");
        RTP.biomeActivity.record("FOREST");
        RTP.biomeActivity.record("FOREST");
        RTP.biomeActivity.record("DESERT");

        boolean handled = run(RTPAPI.serverId);

        assertTrue(handled);
        assertEquals(4, captured.size(), "header + 3 rows");
        assertEquals("biomes total=6 distinct=3", captured.get(0), "header tokens substituted");
        assertTrue(captured.get(1).startsWith("1. PLAINS 50.0% (3) "),
                "highest-count biome ranked first: " + captured.get(1));
        assertTrue(captured.get(2).startsWith("2. FOREST 33.3% (2) "),
                "second biome by count: " + captured.get(2));
        assertTrue(captured.get(3).startsWith("3. DESERT 16.7% (1) "),
                "third biome by count: " + captured.get(3));
    }

    @Test
    @DisplayName("empty row template suppresses per-biome lines but keeps the header")
    void emptyRowTemplate_suppressesRows() {
        installTemplates("no biomes sampled",
                "biomes total=[biomeTotalSamples] distinct=[biomeDistinctCount]", "");
        RTP.biomeActivity.record("PLAINS");

        boolean handled = run(RTPAPI.serverId);

        assertTrue(handled);
        assertEquals(1, captured.size(), "only the header survives when the row template is blank");
        assertEquals("biomes total=1 distinct=1", captured.get(0));
    }

    @Test
    @DisplayName("nextCommand delegation short-circuits before any output")
    void delegatesToNextCommand() {
        RTP.biomeActivity.record("PLAINS");
        AtomicBoolean delegated = new AtomicBoolean(false);
        CommandsAPICommand next = new BiomeActivityCmd(null) {
            @Override
            public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues,
                                     CommandsAPICommand nextCommand) {
                delegated.set(true);
                return true;
            }
        };

        boolean handled = new BiomeActivityCmd(null)
                .onCommand(RTPAPI.serverId, new HashMap<>(), next);

        assertTrue(handled);
        assertTrue(delegated.get(), "nextCommand must be invoked");
        assertTrue(captured.isEmpty(), "delegation short-circuits before emitting any line");
    }

    @Test
    @DisplayName("blank empty-template suppresses output entirely when nothing sampled")
    void emptyTemplateBlank_suppressesEmptyLine() {
        installTemplates("",
                "biomes total=[biomeTotalSamples] distinct=[biomeDistinctCount]",
                "[biomeRank]. [biomeName]");

        boolean handled = run(RTPAPI.serverId);

        assertTrue(handled, "still reports handled");
        assertTrue(captured.isEmpty(), "blank empty-line template emits nothing");
        assertFalse(captured.contains(""), "no blank line leaks through");
        assertNotNull(captured, "capture list intact");
    }
}
