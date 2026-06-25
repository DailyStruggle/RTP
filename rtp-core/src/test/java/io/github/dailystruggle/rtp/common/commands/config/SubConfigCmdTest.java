package io.github.dailystruggle.rtp.common.commands.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.parameters.*;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;

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
    private ConfigParser lang;
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
        doReturn(new java.util.EnumMap<>(io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages.class)).when(lang).getData();
        when(lang.getConfigValue(any(), any())).thenReturn("");
        fileDatabaseField.set(lang, mockDb);
        configs.configParserMap.put(io.github.dailystruggle.rtp.api.configuration.enums.PlaceholderMessages.class, lang);
        configs.configParserMap.put(io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages.class, lang);
        configs.configParserMap.put(io.github.dailystruggle.rtp.api.configuration.enums.NetworkMessages.class, lang);
        configs.configParserMap.put(io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages.class, lang);
        configs.configParserMap.put(io.github.dailystruggle.rtp.api.configuration.enums.SystemMessages.class, lang);

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

    // ── onCommand does NOT run nextCommand (library handles sub-command dispatch) ──

    @Test
    void subConfigCmd_onCommand_returnsTrueAndDoesNotManuallyRunNextCommand() {
        // When this node is an intermediate in the command path, TreeCommand
        // runs it with the matched child as `nextCommand` AND separately
        // re-runs that child through its recursive walk. Running the child
        // here as well executed the update+reload twice (duplicate console /
        // chat output). The corrected contract mirrors ConfigCmd: return true
        // and let the recursive walk run the child exactly once.
        SubConfigCmd cmd = new SubConfigCmd(null, "performance", performanceConfig);
        CommandsAPICommand next = mock(CommandsAPICommand.class);

        boolean result = cmd.onCommand(UUID.randomUUID(), new HashMap<>(), next);

        assertTrue(result);
        verify(next, never()).onCommand(any(), any(), any());
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

    // ── addParameters — RtpYamlSection flattened into dotted-path parameters ──

    @Test
    void subConfigCmd_addParameters_flattensMemorySectionIntoDottedParameters() {
        RtpYamlSection section = mock(RtpYamlSection.class);
        LinkedHashSet<String> keys = new LinkedHashSet<>(Arrays.asList("host", "port", "enabled"));
        when(section.getKeys(false)).thenReturn(keys);
        when(section.get("host")).thenReturn("127.0.0.1");
        when(section.get("port")).thenReturn(3306);
        when(section.get("enabled")).thenReturn(false);

        EnumMap<PerformanceKeys, Object> data = new EnumMap<>(PerformanceKeys.class);
        data.put(PerformanceKeys.viewDistanceSelect, section);
        doReturn(data).when(performanceConfig).getData();

        SubConfigCmd cmd = new SubConfigCmd(null, "performance", performanceConfig);
        Map<String, CommandParameter> params = cmd.getParameterLookup();

        assertTrue(params.containsKey("viewdistanceselect.host"),
                "Expected dotted param 'viewdistanceselect.host'");
        assertTrue(params.containsKey("viewdistanceselect.port"),
                "Expected dotted param 'viewdistanceselect.port'");
        assertTrue(params.containsKey("viewdistanceselect.enabled"),
                "Expected dotted param 'viewdistanceselect.enabled'");
        assertInstanceOf(IntegerParameter.class, params.get("viewdistanceselect.port"));
        assertInstanceOf(BooleanParameter.class, params.get("viewdistanceselect.enabled"));
    }

    // ── addParameters — nested RtpYamlSection flattened recursively ────────────

    @Test
    void subConfigCmd_addParameters_flattensNestedMemorySectionRecursively() {
        RtpYamlSection inner = mock(RtpYamlSection.class);
        LinkedHashSet<String> innerKeys = new LinkedHashSet<>(Arrays.asList("host", "port"));
        when(inner.getKeys(false)).thenReturn(innerKeys);
        when(inner.get("host")).thenReturn("127.0.0.1");
        when(inner.get("port")).thenReturn(6379);

        RtpYamlSection outer = mock(RtpYamlSection.class);
        LinkedHashSet<String> outerKeys = new LinkedHashSet<>(Collections.singletonList("redis"));
        when(outer.getKeys(false)).thenReturn(outerKeys);
        when(outer.get("redis")).thenReturn(inner);

        EnumMap<PerformanceKeys, Object> data = new EnumMap<>(PerformanceKeys.class);
        data.put(PerformanceKeys.viewDistanceSelect, outer);
        doReturn(data).when(performanceConfig).getData();

        SubConfigCmd cmd = new SubConfigCmd(null, "performance", performanceConfig);
        Map<String, CommandParameter> params = cmd.getParameterLookup();

        assertTrue(params.containsKey("viewdistanceselect.redis.host"),
                "Expected 'viewdistanceselect.redis.host'");
        assertTrue(params.containsKey("viewdistanceselect.redis.port"),
                "Expected 'viewdistanceselect.redis.port'");
        assertInstanceOf(IntegerParameter.class, params.get("viewdistanceselect.redis.port"));
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

    // ── onCommand — reload fires exactly once per real update ─────────────────

    @Test
    void subConfigCmd_onCommand_reloadFiredExactlyOnceOnUpdate() throws InterruptedException {
        SubConfigCmd cmd = new SubConfigCmd(null, "performance", performanceConfig);

        CommandsAPICommand mockReload = mock(CommandsAPICommand.class);
        when(mockReload.onCommand(any(), any(), any())).thenReturn(true);
        RTP.baseCommand.getCommandLookup().put("reload", mockReload);

        Map<String, List<String>> params = new HashMap<>();
        params.put("viewdistanceselect", Collections.singletonList("10"));

        cmd.onCommand(UUID.randomUUID(), params, null);
        Thread.sleep(500);

        verify(mockReload, times(1)).onCommand(any(), any(), isNull());
    }

    // ── onCommand — reload NOT fired when acting as an intermediate node ──────

    @Test
    void subConfigCmd_onCommand_reloadNotFiredWhenNextCommandNonNull() throws InterruptedException {
        SubConfigCmd cmd = new SubConfigCmd(null, "performance", performanceConfig);

        CommandsAPICommand mockReload = mock(CommandsAPICommand.class);
        RTP.baseCommand.getCommandLookup().put("reload", mockReload);

        CommandsAPICommand mockChild = mock(CommandsAPICommand.class);

        cmd.onCommand(UUID.randomUUID(), new HashMap<>(), mockChild);
        Thread.sleep(200);

        // No update/save/reload work when this node is an intermediate, and the
        // child must not be invoked here (the recursive walk runs it once).
        verify(mockReload, never()).onCommand(any(), any(), any());
        verify(mockChild, never()).onCommand(any(), any(), any());
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

    // ── ConfigCmd.onCommand — always returns true, library handles sub-command dispatch ──

    @Test
    void configCmd_onCommand_returnsTrueAndDoesNotManuallyDelegateToNextCommand() {
        ConfigCmd cmd = new ConfigCmd(mock(CommandsAPICommand.class));
        CommandsAPICommand next = mock(CommandsAPICommand.class);

        boolean result = cmd.onCommand(UUID.randomUUID(), new HashMap<>(), next);

        assertTrue(result);
        verify(next, never()).onCommand(any(), any(), any());
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

    // ── dotted leaf edit over a "@config" scalar parent must keep the full block ──

    @Test
    void materializeSectionParent_expandsAtConfigScalarIntoFullBlock() {
        // Region file ships `shape: "@config"`; the menu emits a dotted edit
        // `shape.radius`. Without materialization, set("shape.radius", ...)
        // would clobber the scalar with a one-key mapping (the reported bug).
        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig yaml =
                io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig.parse(
                        "shape: \"@config\"\n");

        // Loaded, resolved shape value: a FactoryValue with name + sub-params.
        io.github.dailystruggle.rtp.common.factory.FactoryValue<?> shapeValue =
                mock(io.github.dailystruggle.rtp.common.factory.FactoryValue.class);
        shapeValue.name = "CIRCLE";
        EnumMap<PerformanceKeys, Object> shapeData = new EnumMap<>(PerformanceKeys.class);
        shapeData.put(PerformanceKeys.viewDistanceSelect, 256L);
        doReturn(shapeData).when(shapeValue).getData();

        ConfigParser regionConfig = mock(ConfigParser.class);
        EnumMap<io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys, Object> regionData =
                new EnumMap<>(io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys.class);
        regionData.put(io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys.shape, shapeValue);
        doReturn(regionData).when(regionConfig).getData();

        SubConfigCmd.materializeSectionParentIfScalar(regionConfig, yaml, "shape.radius");

        Object parent = yaml.get("shape");
        assertTrue(parent instanceof RtpYamlSection,
                "shape must be materialized into a nested section, not left as a scalar");
        RtpYamlSection section = (RtpYamlSection) parent;
        assertEquals("CIRCLE", String.valueOf(section.get("name")),
                "materialized block must keep the type discriminator 'name'");
        assertTrue(section.getKeys(false).contains("viewDistanceSelect"),
                "materialized block must keep sibling keys from the resolved value");
    }

    @Test
    void materializeSectionParent_noOpWhenParentAlreadySection() {
        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig yaml =
                io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig.parse(
                        "shape:\n  name: SQUARE\n  radius: 100\n");

        ConfigParser regionConfig = mock(ConfigParser.class);
        // getData must not be consulted for an already-nested parent.
        SubConfigCmd.materializeSectionParentIfScalar(regionConfig, yaml, "shape.radius");

        Object parent = yaml.get("shape");
        assertTrue(parent instanceof RtpYamlSection);
        assertEquals("SQUARE", String.valueOf(((RtpYamlSection) parent).get("name")));
        verify(regionConfig, never()).getData();
    }
}
