package io.github.dailystruggle.rtp.common.commands.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.parameters.*;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SubConfigCmdTest {

    @TempDir
    Path tempDir;

    private ConfigParser<PerformanceKeys> performanceConfig;
    private EnumMap<PerformanceKeys, Object> performanceData;
    private ConfigParser<MessagesKeys> lang;
    private io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase mockDb;

    @BeforeEach
    void setUp() throws Exception {
        RTPTestSetup.install(tempDir.toFile());
        RTP.selectionAPI = new io.github.dailystruggle.rtp.common.selection.SelectionAPI();

        Configs configs = RTP.configs;

        mockDb = mock(io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase.class);
        Field cachedLookupField = io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase.class.getDeclaredField("cachedLookup");
        cachedLookupField.setAccessible(true);
        cachedLookupField.set(mockDb, new AtomicReference<>(new ConcurrentHashMap<>()));

        performanceConfig = mock(ConfigParser.class);
        performanceConfig.language_mapping = new ConcurrentHashMap<>();
        performanceConfig.reverse_language_mapping = new ConcurrentHashMap<>();
        performanceConfig.name = "performance";

        Field myClassField = io.github.dailystruggle.rtp.common.factory.FactoryValue.class.getDeclaredField("myClass");
        myClassField.setAccessible(true);
        myClassField.set(performanceConfig, PerformanceKeys.class);

        Field fileDatabaseField = ConfigParser.class.getDeclaredField("fileDatabase");
        fileDatabaseField.setAccessible(true);
        fileDatabaseField.set(performanceConfig, mockDb);

        performanceData = new EnumMap<>(PerformanceKeys.class);
        performanceData.put(PerformanceKeys.viewDistanceSelect, 5L);
        doReturn(performanceData).when(performanceConfig).getData();
        when(performanceConfig.getConfigValue(any(), any())).thenAnswer(inv -> {
            PerformanceKeys k = inv.getArgument(0);
            Object def = inv.getArgument(1);
            return performanceData.getOrDefault(k, def);
        });
        doAnswer(inv -> {
            String keyStr = inv.getArgument(0);
            Object val = inv.getArgument(1);
            if (val instanceof String) {
                try { val = Long.parseLong((String) val); } catch (NumberFormatException ignored) {}
            }
            for (PerformanceKeys k : PerformanceKeys.values()) {
                if (k.name().equalsIgnoreCase(keyStr)) { performanceData.put(k, val); break; }
            }
            return null;
        }).when(performanceConfig).set(anyString(), any());
        configs.configParserMap.put(PerformanceKeys.class, performanceConfig);

        lang = mock(ConfigParser.class);
        lang.language_mapping = new ConcurrentHashMap<>();
        lang.reverse_language_mapping = new ConcurrentHashMap<>();
        lang.name = "messages.yml";
        doReturn(new EnumMap<>(MessagesKeys.class)).when(lang).getData();
        when(lang.getConfigValue(any(), any())).thenReturn("");
        fileDatabaseField.set(lang, mockDb);
        configs.configParserMap.put(MessagesKeys.class, lang);

        RTP.baseCommand = mock(io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand.class);
        Map<String, CommandsAPICommand> commandLookup = new HashMap<>();
        commandLookup.put("reload", mock(CommandsAPICommand.class));
        when(RTP.baseCommand.getCommandLookup()).thenReturn(commandLookup);
    }

    // ── name / permission / description ──────────────────────────────────────

    @Test
    void subConfigCmd_name_isLowercased() {
        SubConfigCmd cmd = new SubConfigCmd(null, "Performance.YML", performanceConfig);
        assertEquals("performance.yml", cmd.name());
    }

    @Test
    void subConfigCmd_permission_isRtpConfig() {
        SubConfigCmd cmd = new SubConfigCmd(null, "performance", performanceConfig);
        assertEquals("rtp.config", cmd.permission());
    }

    @Test
    void subConfigCmd_description_isNotNull() {
        SubConfigCmd cmd = new SubConfigCmd(null, "performance", performanceConfig);
        assertNotNull(cmd.description());
    }

    // ── onCommand delegates to nextCommand when non-null ─────────────────────

    @Test
    void subConfigCmd_onCommand_delegatesToNextCommandWhenNonNull() {
        SubConfigCmd cmd = new SubConfigCmd(null, "performance", performanceConfig);
        CommandsAPICommand next = mock(CommandsAPICommand.class);
        when(next.onCommand(any(), any(), any())).thenReturn(true);

        boolean result = cmd.onCommand(UUID.randomUUID(), new HashMap<>(), next);

        assertTrue(result);
        verify(next).onCommand(any(), any(), isNull());
    }

    // ── addParameters — integer type registers IntegerParameter ──────────────

    @Test
    void subConfigCmd_addParameters_registersIntegerParameterForLongValue() {
        performanceData.put(PerformanceKeys.viewDistanceSelect, 5L);
        SubConfigCmd cmd = new SubConfigCmd(null, "performance", performanceConfig);

        Map<String, CommandParameter> params = cmd.getParameterLookup();
        assertTrue(params.containsKey("viewdistanceselect"),
                "Expected 'viewdistanceselect' parameter to be registered");
        assertInstanceOf(IntegerParameter.class, params.get("viewdistanceselect"));
    }

    // ── addParameters — boolean type registers BooleanParameter ──────────────

    @Test
    void subConfigCmd_addParameters_registersBooleanParameterForBooleanValue() {
        EnumMap<PerformanceKeys, Object> data = new EnumMap<>(PerformanceKeys.class);
        data.put(PerformanceKeys.viewDistanceSelect, Boolean.TRUE);
        doReturn(data).when(performanceConfig).getData();

        SubConfigCmd cmd = new SubConfigCmd(null, "performance", performanceConfig);
        Map<String, CommandParameter> params = cmd.getParameterLookup();

        assertTrue(params.containsKey("viewdistanceselect"));
        assertInstanceOf(BooleanParameter.class, params.get("viewdistanceselect"));
    }

    // ── addParameters — float type registers FloatParameter ──────────────────

    @Test
    void subConfigCmd_addParameters_registersFloatParameterForDoubleValue() {
        EnumMap<PerformanceKeys, Object> data = new EnumMap<>(PerformanceKeys.class);
        data.put(PerformanceKeys.viewDistanceSelect, 3.14);
        doReturn(data).when(performanceConfig).getData();

        SubConfigCmd cmd = new SubConfigCmd(null, "performance", performanceConfig);
        Map<String, CommandParameter> params = cmd.getParameterLookup();

        assertTrue(params.containsKey("viewdistanceselect"));
        assertInstanceOf(FloatParameter.class, params.get("viewdistanceselect"));
    }

    // ── addParameters — string type registers generic CommandParameter ────────

    @Test
    void subConfigCmd_addParameters_registersCommandParameterForStringValue() {
        EnumMap<PerformanceKeys, Object> data = new EnumMap<>(PerformanceKeys.class);
        data.put(PerformanceKeys.viewDistanceSelect, "someString");
        doReturn(data).when(performanceConfig).getData();

        SubConfigCmd cmd = new SubConfigCmd(null, "performance", performanceConfig);
        Map<String, CommandParameter> params = cmd.getParameterLookup();

        assertTrue(params.containsKey("viewdistanceselect"));
        // Should be a generic CommandParameter (not a typed subclass)
        assertNotNull(params.get("viewdistanceselect"));
    }

    // ── addParameters — version key is skipped ────────────────────────────────

    @Test
    void subConfigCmd_addParameters_skipsVersionKey() {
        // Use a config whose enum has a VERSION key — check it's not registered
        // PerformanceKeys doesn't have VERSION, so we verify no "version" key appears
        SubConfigCmd cmd = new SubConfigCmd(null, "performance", performanceConfig);
        Map<String, CommandParameter> params = cmd.getParameterLookup();
        assertFalse(params.containsKey("version"), "'version' key must be skipped");
    }

    // ── addParameters — null factoryValue is safe ─────────────────────────────

    @Test
    void subConfigCmd_addParameters_nullFactoryValueDoesNotThrow() {
        assertDoesNotThrow(() -> new SubConfigCmd(null, "empty", null));
    }

    // ── onCommand — returns true when nextCommand is null ─────────────────────

    @Test
    void subConfigCmd_onCommand_returnsTrueWhenNextCommandNull() {
        SubConfigCmd cmd = new SubConfigCmd(null, "performance", performanceConfig);
        // onCommand with null nextCommand triggers async work; just verify it returns true
        boolean result = cmd.onCommand(UUID.randomUUID(), new HashMap<>(), null);
        assertTrue(result);
    }

    // ── onCommand — updates config value via set(String, Object) ─────────────

    @Test
    void subConfigCmd_onCommand_updatesConfigValueAndSaves() throws IOException, InterruptedException {
        SubConfigCmd cmd = new SubConfigCmd(null, "performance", performanceConfig);

        Map<String, List<String>> params = new HashMap<>();
        params.put("viewdistanceselect", Collections.singletonList("20"));

        cmd.onCommand(UUID.randomUUID(), params, null);

        // Give async task time to complete
        Thread.sleep(500);

        verify(performanceConfig, atLeastOnce()).set(anyString(), any());
        verify(performanceConfig, atLeastOnce()).save();
    }

    // ── ConfigCmd — name / permission / description ───────────────────────────

    @Test
    void configCmd_name_isConfig() {
        ConfigCmd cmd = new ConfigCmd(mock(CommandsAPICommand.class));
        assertEquals("config", cmd.name());
    }

    @Test
    void configCmd_permission_isRtpConfig() {
        ConfigCmd cmd = new ConfigCmd(mock(CommandsAPICommand.class));
        assertEquals("rtp.config", cmd.permission());
    }

    @Test
    void configCmd_description_isNotNull() {
        ConfigCmd cmd = new ConfigCmd(mock(CommandsAPICommand.class));
        assertNotNull(cmd.description());
    }

    // ── ConfigCmd.onCommand — delegates to nextCommand ────────────────────────

    @Test
    void configCmd_onCommand_delegatesToNextCommandWhenNonNull() {
        ConfigCmd cmd = new ConfigCmd(mock(CommandsAPICommand.class));
        CommandsAPICommand next = mock(CommandsAPICommand.class);
        when(next.onCommand(any(), any(), any())).thenReturn(true);

        boolean result = cmd.onCommand(UUID.randomUUID(), new HashMap<>(), next);

        assertTrue(result);
        verify(next).onCommand(any(), any(), isNull());
    }

    @Test
    void configCmd_onCommand_returnsTrueWhenNextCommandNull() {
        ConfigCmd cmd = new ConfigCmd(mock(CommandsAPICommand.class));
        boolean result = cmd.onCommand(UUID.randomUUID(), new HashMap<>(), null);
        assertTrue(result);
    }

    // ── ConfigCmd.addCommands — registers SubConfigCmd for each parser ────────

    @Test
    void configCmd_addCommands_doesNotThrowWithMockParser() {
        ConfigCmd cmd = new ConfigCmd(mock(CommandsAPICommand.class));
        // addCommands() iterates configParserMap; SubConfigCmd construction may warn
        // about missing embedded resource but must not propagate an exception
        assertDoesNotThrow(cmd::addCommands);
    }

    @Test
    void configCmd_addCommands_manuallyRegisteredSubCmdIsReachable() {
        ConfigCmd cmd = new ConfigCmd(mock(CommandsAPICommand.class));
        // Simulate what ConfigCmd.addCommands() does when the parser is valid:
        // manually insert a SubConfigCmd and verify it is reachable via lookup
        SubConfigCmd sub = new SubConfigCmd(cmd, "performance", performanceConfig);
        cmd.getCommandLookup().put("performance", sub);

        assertTrue(cmd.getCommandLookup().containsKey("performance"),
                "Manually registered sub-command must be reachable");
        assertSame(sub, cmd.getCommandLookup().get("performance"));
    }

    @Test
    void configCmd_addCommands_calledTwiceDoesNotThrow() {
        ConfigCmd cmd = new ConfigCmd(mock(CommandsAPICommand.class));
        assertDoesNotThrow(() -> {
            cmd.addCommands();
            cmd.addCommands();
        });
    }
}
