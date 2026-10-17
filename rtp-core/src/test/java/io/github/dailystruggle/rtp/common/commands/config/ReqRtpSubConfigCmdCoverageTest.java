package io.github.dailystruggle.rtp.common.commands.config;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("SubConfigCmd and ViewRawSubConfigCmd Interactive & Coverage Tests")
public class ReqRtpSubConfigCmdCoverageTest {

    private Path tempDir;
    private File pluginDir;
    private ConfigParser<PerformanceKeys> performanceConfig;
    private EnumMap<PerformanceKeys, Object> performanceData;
    private io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase mockDb;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("rtp-subconfig-cov-");
        pluginDir = tempDir.toFile();
        RTPTestSetup.install(pluginDir);
        RTP.selectionAPI = new io.github.dailystruggle.rtp.common.selection.SelectionAPI();

        mockDb = mock(io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase.class);
        Field cachedLookupField = io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase.class.getDeclaredField("cachedLookup");
        cachedLookupField.setAccessible(true);
        cachedLookupField.set(mockDb, new AtomicReference<>(new ConcurrentHashMap<>()));

        performanceConfig = mock(ConfigParser.class);
        performanceConfig.language_mapping = new ConcurrentHashMap<>();
        performanceConfig.reverse_language_mapping = new ConcurrentHashMap<>();
        performanceConfig.name = "performance.yml";
        performanceConfig.pluginDirectory = pluginDir;

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

        RTP.configs.configParserMap.put(PerformanceKeys.class, performanceConfig);

        RTP.baseCommand = mock(io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand.class);
        Map<String, CommandsAPICommand> commandLookup = new HashMap<>();
        commandLookup.put("reload", mock(CommandsAPICommand.class));
        when(RTP.baseCommand.getCommandLookup()).thenReturn(commandLookup);
    }

    @Test
    @DisplayName("ViewRawSubConfigCmd name, permission, description, and onCommand execution")
    void testViewRawSubConfigCmd() throws Exception {
        ViewRawSubConfigCmd viewRaw = new ViewRawSubConfigCmd(null, performanceConfig);
        assertEquals("viewraw", viewRaw.name());
        assertEquals("rtp.config", viewRaw.permission());
        assertNotNull(viewRaw.description());

        UUID callerId = UUID.randomUUID();

        // 1. NextCommand delegation when nextCommand != null
        CommandsAPICommand nextCmd = mock(CommandsAPICommand.class);
        viewRaw.onCommand(callerId, Map.of(), nextCmd);
        verify(nextCmd).onCommand(callerId, Map.of(), null);

        // 2. File not found path
        viewRaw.onCommand(callerId, Map.of(), null);

        // 3. File exists and streamed
        File targetFile = new File(pluginDir, "performance.yml");
        Files.writeString(targetFile.toPath(), "viewDistanceSelect: 5\nfoo: bar\n", StandardCharsets.UTF_8);

        viewRaw.onCommand(callerId, Map.of(), null);

        // 4. File truncation at MAX_LINES
        StringBuilder bigContent = new StringBuilder();
        for (int i = 0; i < ViewRawSubConfigCmd.MAX_LINES + 50; i++) {
            bigContent.append("line").append(i).append(": value\n");
        }
        Files.writeString(targetFile.toPath(), bigContent.toString(), StandardCharsets.UTF_8);
        viewRaw.onCommand(callerId, Map.of(), null);
    }

    @Test
    @DisplayName("SubConfigCmd onTabComplete invokes addParameters and delegates to super")
    void testSubConfigCmdTabComplete() {
        SubConfigCmd sub = new SubConfigCmd(null, "performance", performanceConfig);
        List<String> suggestions = sub.onTabComplete(UUID.randomUUID(), perm -> true, new String[]{"viewDistanceSelect"});
        assertNotNull(suggestions);
    }

    @Test
    @DisplayName("SubConfigCmd ownedTypeFactoryForKey tests")
    void testOwnedTypeFactoryForKey() {
        assertNull(SubConfigCmd.ownedTypeFactoryForKey(null));
        assertNull(SubConfigCmd.ownedTypeFactoryForKey("nonexistent_factory"));
        assertNull(SubConfigCmd.ownedTypeFactoryForKey("singleConfig"));
        assertNull(SubConfigCmd.ownedTypeFactoryForKey("multiConfig"));

        Factory<?> shapeFactory = SubConfigCmd.ownedTypeFactoryForKey("shape");
        assertNotNull(shapeFactory);
        Factory<?> vertFactory = SubConfigCmd.ownedTypeFactoryForKey("vert");
        assertNotNull(vertFactory);
    }

    @Test
    @DisplayName("SubConfigCmd canonicalizeDottedKey and descriptionFromComment")
    void testKeyResolutionHelpers() throws Exception {
        RtpYamlConfig yaml = RtpYamlConfig.parse("shape:\n  radius: 100\n  centerX: 0\n");
        String canonical = SubConfigCmd.canonicalizeDottedKey(performanceConfig, yaml, "shape.radius");
        assertEquals("shape.radius", canonical);

        // Case insensitivity
        String canonicalLower = SubConfigCmd.canonicalizeDottedKey(performanceConfig, yaml, "shape.centerx");
        assertEquals("shape.centerX", canonicalLower);

        // Non-existent key falls back to original
        String fallback = SubConfigCmd.canonicalizeDottedKey(performanceConfig, yaml, "shape.unknownKey");
        assertEquals("shape.unknownKey", fallback);

        // descriptionFromComment
        Method mDesc = SubConfigCmd.class.getDeclaredMethod("descriptionFromComment", RtpYamlSection.class, String.class);
        mDesc.setAccessible(true);
        assertEquals("", mDesc.invoke(null, null, "key"));
        assertEquals("", mDesc.invoke(null, yaml, ""));
    }

    @Test
    @DisplayName("SubConfigCmd Alias execution")
    void testSubConfigCmdAlias() {
        SubConfigCmd sub = new SubConfigCmd(null, "regions.yml", performanceConfig);
        SubConfigCmd.Alias alias = new SubConfigCmd.Alias(null, "regions", sub);
        assertEquals("regions", alias.name());
        assertEquals(sub.permission(), alias.permission());
        assertEquals(sub.description(), alias.description());

        UUID callerId = UUID.randomUUID();
        assertTrue(alias.onCommand(callerId, Map.of(), null));
    }

    @Test
    @DisplayName("SubConfigCmd addParameters with various parameter types and predicates")
    void testAddParametersCoverage() throws Exception {
        ConfigParser<RegionKeys> regionConfig = mock(ConfigParser.class);
        regionConfig.language_mapping = new ConcurrentHashMap<>();
        regionConfig.reverse_language_mapping = new ConcurrentHashMap<>();
        regionConfig.name = "default.yml";

        Field myClassField = io.github.dailystruggle.rtp.common.factory.FactoryValue.class.getDeclaredField("myClass");
        myClassField.setAccessible(true);
        myClassField.set(regionConfig, RegionKeys.class);

        Field fileDatabaseField = ConfigParser.class.getDeclaredField("fileDatabase");
        fileDatabaseField.setAccessible(true);
        fileDatabaseField.set(regionConfig, mockDb);

        EnumMap<RegionKeys, Object> data = new EnumMap<>(RegionKeys.class);
        data.put(RegionKeys.world, "world");
        data.put(RegionKeys.shape, "CIRCLE");
        data.put(RegionKeys.vert, "SURFACE");
        data.put(RegionKeys.cacheCap, 100L);
        data.put(RegionKeys.requirePermission, true);
        doReturn(data).when(regionConfig).getData();

        SubConfigCmd cmd = new SubConfigCmd(null, "default.yml", regionConfig);
        cmd.addParameters();

        // Exercise parameter predicates and values() methods
        for (Map.Entry<String, CommandParameter> entry : cmd.getParameterLookup().entrySet()) {
            CommandParameter p = entry.getValue();
            assertNotNull(p);
            // invoke predicate
            p.relevantValues(UUID.randomUUID());
            p.values();
        }
    }
}
