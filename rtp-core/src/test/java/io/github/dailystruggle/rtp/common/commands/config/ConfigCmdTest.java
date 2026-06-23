package io.github.dailystruggle.rtp.common.commands.config;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

public class ConfigCmdTest {
    @TempDir
    Path tempDir;

    private ConfigCmd configCmd;
    private Configs configs;
    private ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys> performanceConfig;
    private java.util.EnumMap<io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys, Object> performanceData;

    @BeforeEach
    void setUp() throws IOException {
        RTPTestSetup.install(tempDir.toFile());
        RTP.selectionAPI = new io.github.dailystruggle.rtp.common.selection.SelectionAPI();

        configs = new Configs(tempDir.toFile());
        RTP.configs = configs;

        performanceConfig = mock(io.github.dailystruggle.rtp.common.configuration.ConfigParser.class);
        performanceConfig.language_mapping = new java.util.concurrent.ConcurrentHashMap<>();
        performanceConfig.reverse_language_mapping = new java.util.concurrent.ConcurrentHashMap<>();
        performanceConfig.name = "performance";
        io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase mockDb = mock(io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase.class);
        try {
            java.lang.reflect.Field myClassField = io.github.dailystruggle.rtp.common.factory.FactoryValue.class.getDeclaredField("myClass");
            myClassField.setAccessible(true);
            myClassField.set(performanceConfig, io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.class);

            java.lang.reflect.Field cachedLookupField = io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase.class.getDeclaredField("cachedLookup");
            cachedLookupField.setAccessible(true);
            cachedLookupField.set(mockDb, new java.util.concurrent.atomic.AtomicReference<>(new java.util.concurrent.ConcurrentHashMap<>()));

            java.lang.reflect.Field fileDatabaseField = io.github.dailystruggle.rtp.common.configuration.ConfigParser.class.getDeclaredField("fileDatabase");
            fileDatabaseField.setAccessible(true);
            fileDatabaseField.set(performanceConfig, mockDb);
        } catch (Exception e) {}

        performanceData = new java.util.EnumMap<>(io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.class);
        performanceData.put(io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.viewDistanceSelect, 0L);
        doReturn(performanceData).when(performanceConfig).getData();
        when(performanceConfig.getConfigValue(any(), any())).thenAnswer(invocation -> {
            io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys key = invocation.getArgument(0);
            Object def = invocation.getArgument(1);
            Object res = performanceData.getOrDefault(key, def);
            return res;
        });
        doAnswer(invocation -> {
            io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys key = invocation.getArgument(0);
            Object val = invocation.getArgument(1);
            performanceData.put(key, val);
            return null;
        }).when(performanceConfig).set(any(io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.class), any());
        doAnswer(invocation -> {
            String keyStr = invocation.getArgument(0);
            Object val = invocation.getArgument(1);
            if (val instanceof String) {
                try {
                    val = Long.parseLong((String) val);
                } catch (NumberFormatException ignored) {}
            }
            io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys key = null;
            for (io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys k : io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.values()) {
                if (k.name().equalsIgnoreCase(keyStr)) {
                    key = k;
                    break;
                }
            }
            if (key != null) performanceData.put(key, val);
            return null;
        }).when(performanceConfig).set(anyString(), any());
        doAnswer(invocation -> {
            String keyStr = invocation.getArgument(0);
            Object val = invocation.getArgument(1);
            io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys key = null;
            for (io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys k : io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.values()) {
                if (k.name().equalsIgnoreCase(keyStr)) {
                    key = k;
                    break;
                }
            }
            if (key != null) performanceData.put(key, val);
            return null;
        }).when(performanceConfig).setConfigValue(anyString(), any());
        configs.configParserMap.put(PerformanceKeys.class, performanceConfig);

        ConfigParser lang = mock(io.github.dailystruggle.rtp.common.configuration.ConfigParser.class);
        lang.language_mapping = new java.util.concurrent.ConcurrentHashMap<>();
        lang.reverse_language_mapping = new java.util.concurrent.ConcurrentHashMap<>();
        doReturn(new java.util.EnumMap<>(io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages.class)).when(lang).getData();
        when(lang.getConfigValue(any(), any())).thenReturn("");
        lang.name = "messages";
        try {
            java.lang.reflect.Field fileDatabaseField = io.github.dailystruggle.rtp.common.configuration.ConfigParser.class.getDeclaredField("fileDatabase");
            fileDatabaseField.setAccessible(true);
            fileDatabaseField.set(lang, mockDb);
        } catch (Exception e) {}
        configs.configParserMap.put(io.github.dailystruggle.rtp.api.configuration.enums.PlaceholderMessages.class, lang);
        configs.configParserMap.put(io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages.class, lang);
        configs.configParserMap.put(io.github.dailystruggle.rtp.api.configuration.enums.NetworkMessages.class, lang);
        configs.configParserMap.put(io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages.class, lang);
        configs.configParserMap.put(io.github.dailystruggle.rtp.api.configuration.enums.SystemMessages.class, lang);

        RTP.baseCommand = mock(io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand.class);
        Map<String, io.github.dailystruggle.commandsapi.common.CommandsAPICommand> commandLookup = new HashMap<>();
        commandLookup.put("reload", mock(io.github.dailystruggle.commandsapi.common.CommandsAPICommand.class));
        when(RTP.baseCommand.getCommandLookup()).thenReturn(commandLookup);

        configCmd = new ConfigCmd(mock(CommandsAPICommand.class));
        configCmd.addCommands();
        if (configCmd.getCommandLookup().get("performance") == null) {
            configCmd.getCommandLookup().put("performance", new SubConfigCmd(configCmd, "performance", performanceConfig));
        }
    }

    @Test
    void testConfigCommandUpdatesRuntimeAndDisk() throws IOException {
        // 1. Identify the SubConfigCmd for "performance"
        SubConfigCmd subConfigCmd = (SubConfigCmd) configCmd.getCommandLookup().get("performance");

        // 2. Prepare parameter map to set viewDistanceSelect to 10
        Map<String, List<String>> parameterValues = new HashMap<>();
        parameterValues.put(PerformanceKeys.viewDistanceSelect.name(), Collections.singletonList("10"));

        // 3. Execute onCommand
        subConfigCmd.onCommand(UUID.randomUUID(), parameterValues, null);

        // 4. Verify runtime memory state
        ConfigParser<PerformanceKeys> performanceConfig = (ConfigParser<PerformanceKeys>) configs.getParser(PerformanceKeys.class);
        Object runtimeValue = performanceConfig.getConfigValue(PerformanceKeys.viewDistanceSelect, 0L);
        assertEquals(10L, ((Number) runtimeValue).longValue(), "Runtime memory state should be updated to 10");

        // 5. Verify disk state
        verify(performanceConfig).save();
    }
}
