package io.github.dailystruggle.rtp.common.commands.config.list;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ListCmd} - the config-list mutation subcommand that appends /
 * removes entries from a YAML string-list key and persists the file.
 *
 * <p>The mock scheduler runs {@code runTaskAsynchronously} synchronously
 * (trampolined), so the mutation + save path completes within {@code onCommand}.
 */
class ListCmdTest {

    @TempDir
    File tempDir;

    private MockRTPServerAccessor accessor;
    private RtpYamlConfig file;
    private UUID caller;

    @BeforeEach
    void setUp() throws Exception {
        accessor = RTPTestSetup.install(tempDir);
        RTP.getInstance().miscAsyncTasks.clear();
        RTP.getInstance().miscAsyncTasks.start();

        file = new RtpYamlConfig(new File(tempDir, "list.yml"));
        file.createOrLoad();
        file.set("biomes", Arrays.asList("PLAINS", "DESERT"));
        file.save();

        caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "list-caller", null));
    }

    @AfterEach
    void tearDown() {
        RTPTestSetup.cleanUp();
    }

    private ListCmd newCmd() {
        return new ListCmd("biomes", null, () -> new HashSet<>(file.getStringList("biomes")), file, "biomes");
    }

    @Test
    @Timeout(5)
    void metadata_isStable() {
        ListCmd cmd = newCmd();
        assertEquals("biomes", cmd.name());
        assertEquals("rtp.config", cmd.permission());
        assertNotNull(cmd.description());
    }

    @Test
    @Timeout(5)
    void addCommands_registersAddAndRemoveParameters() {
        ListCmd cmd = newCmd();
        cmd.addCommands();
        assertNotNull(cmd.getParameterLookup().get("add"));
        assertNotNull(cmd.getParameterLookup().get("remove"));
    }

    @Test
    @Timeout(5)
    void onCommand_addsEntries_andPersists() {
        ListCmd cmd = newCmd();
        Map<String, List<String>> params = new HashMap<>();
        params.put("add", Arrays.asList("FOREST"));

        boolean result = cmd.onCommand(caller, params, null);
        assertTrue(result);

        List<String> saved = file.getStringList("biomes");
        assertTrue(saved.contains("FOREST"), "added value must be persisted");
        assertTrue(saved.contains("PLAINS"));
    }

    @Test
    @Timeout(5)
    void onCommand_removesEntries_andPersists() {
        ListCmd cmd = newCmd();
        Map<String, List<String>> params = new HashMap<>();
        params.put("remove", Arrays.asList("DESERT"));

        boolean result = cmd.onCommand(caller, params, null);
        assertTrue(result);

        List<String> saved = file.getStringList("biomes");
        assertFalse(saved.contains("DESERT"), "removed value must not remain");
        assertTrue(saved.contains("PLAINS"));
    }

    @Test
    @Timeout(5)
    void onCommand_withNoAddOrRemove_leavesListUnchanged() {
        ListCmd cmd = newCmd();
        boolean result = cmd.onCommand(caller, new HashMap<>(), null);
        assertTrue(result);

        List<String> saved = file.getStringList("biomes");
        assertEquals(2, saved.size());
    }

    @Test
    @Timeout(5)
    void onCommand_delegatesToNextCommand() throws Exception {
        // A non-null nextCommand short-circuits the parent's own mutation and
        // forwards to the delegate. Use a second ListCmd targeting a distinct
        // key and assert only the delegate's key was mutated.
        file.set("other", Arrays.asList("A"));
        file.save();
        ListCmd next = new ListCmd("other", null,
                () -> new HashSet<>(file.getStringList("other")), file, "other");

        Map<String, List<String>> params = new HashMap<>();
        params.put("add", Arrays.asList("B"));

        ListCmd cmd = newCmd();
        boolean result = cmd.onCommand(caller, params, next);
        assertTrue(result);

        assertTrue(file.getStringList("other").contains("B"),
                "delegate command must mutate its own key");
        assertFalse(file.getStringList("biomes").contains("B"),
                "parent must not apply the mutation when delegating");
    }
}
