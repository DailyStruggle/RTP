package io.github.dailystruggle.rtp.common.commands.config;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simpleyaml.configuration.file.YamlFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ConfigCmdTest {
    @TempDir
    Path tempDir;

    private ConfigCmd configCmd;
    private Configs configs;

    @BeforeEach
    void setUp() throws IOException {
        RTPServerAccessor serverAccessor = mock(RTPServerAccessor.class);
        io.github.dailystruggle.rtp.api.entity.RTPPlayer console = mock(io.github.dailystruggle.rtp.api.entity.RTPPlayer.class);
        when(console.uuid()).thenReturn(RTP.serverId);
        when(serverAccessor.getConsolePlayer()).thenReturn(console);
        RTPScheduler scheduler = mock(RTPScheduler.class);

        RTP.serverAccessor = serverAccessor;
        RTP.scheduler = scheduler;
        when(serverAccessor.getPluginDirectory()).thenReturn(tempDir.toFile());
        when(serverAccessor.createTaskPipe()).thenReturn(mock(RTPTaskPipe.class));
        when(serverAccessor.getSender(any())).thenReturn(mock(io.github.dailystruggle.rtp.api.entity.RTPCommandSender.class));

        RTP rtp = new RTP() {};
        try {
            java.lang.reflect.Field instanceField = RTP.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, rtp);
        } catch (Exception e) {}
        RTP.selectionAPI = new io.github.dailystruggle.rtp.common.selection.SelectionAPI();

        configs = new Configs(tempDir.toFile());
        RTP.configs = configs;
        configs.reload();

        configCmd = new ConfigCmd(mock(CommandsAPICommand.class));
        configCmd.addCommands();
    }

    @Test
    void testConfigCommandUpdatesRuntimeAndDisk() throws IOException {
        // 1. Identify the SubConfigCmd for "performance"
        SubConfigCmd subConfigCmd = (SubConfigCmd) configCmd.getCommandLookup().get("performance");

        // 2. Prepare parameter map to set viewDistanceSelect to 10
        Map<String, List<String>> parameterValues = new HashMap<>();
        parameterValues.put("argument", Collections.singletonList(PerformanceKeys.viewDistanceSelect.name()));
        parameterValues.put("value", Collections.singletonList("10"));

        // 3. Execute onCommand
        subConfigCmd.onCommand(UUID.randomUUID(), parameterValues, null);

        // 4. Verify runtime memory state
        ConfigParser<PerformanceKeys> performanceConfig = (ConfigParser<PerformanceKeys>) configs.getParser(PerformanceKeys.class);
        Object runtimeValue = performanceConfig.getConfigValue(PerformanceKeys.viewDistanceSelect, 0L);
        assertEquals(10L, ((Number) runtimeValue).longValue(), "Runtime memory state should be updated to 10");

        // 5. Explicitly invoke synchronous queue processing to flush writes to disk
        performanceConfig.fileDatabase.processQueries(Long.MAX_VALUE);

        // 6. Verify disk state
        File performanceFile = new File(tempDir.toFile(), "performance.yml");
        YamlFile yamlFile = new YamlFile(performanceFile);
        yamlFile.load();

        Object diskValue = yamlFile.get(PerformanceKeys.viewDistanceSelect.name());
        assertEquals(10, ((Number) diskValue).intValue(), "Disk storage should be updated to 10");
    }
}
