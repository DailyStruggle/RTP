package io.github.dailystruggle.rtp.common.commands.config;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump.JumpAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test: regions with shape inheritance references (e.g. {@code @config})
 * must register dotted sub-parameters (such as {@code shape.centerZ}) and accept negative values.
 */
public class RegionCenterZReproTest {
    @TempDir
    Path tempDir;

    private ConfigParser<RegionKeys> regionConfig;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        RTPTestSetup.install(tempDir.toFile());
        RTP.selectionAPI = new io.github.dailystruggle.rtp.common.selection.SelectionAPI();
        Configs configs = RTP.configs;

        io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase mockDb =
                mock(io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase.class);
        Field cachedLookupField = io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase.class
                .getDeclaredField("cachedLookup");
        cachedLookupField.setAccessible(true);
        cachedLookupField.set(mockDb, new AtomicReference<>(new ConcurrentHashMap<>()));

        regionConfig = mock(ConfigParser.class);
        regionConfig.language_mapping = new ConcurrentHashMap<>();
        regionConfig.reverse_language_mapping = new ConcurrentHashMap<>();
        regionConfig.name = "north.yml";

        Field myClassField = io.github.dailystruggle.rtp.common.factory.FactoryValue.class
                .getDeclaredField("myClass");
        myClassField.setAccessible(true);
        myClassField.set(regionConfig, RegionKeys.class);

        Field fileDatabaseField = ConfigParser.class.getDeclaredField("fileDatabase");
        fileDatabaseField.setAccessible(true);
        fileDatabaseField.set(regionConfig, mockDb);

        // The reproduction: shape/vert stored as an inheritance-reference String.
        EnumMap<RegionKeys, Object> regionData = new EnumMap<>(RegionKeys.class);
        regionData.put(RegionKeys.shape, "@config");
        regionData.put(RegionKeys.vert, "@config");
        doReturn(regionData).when(regionConfig).getData();
        when(regionConfig.getConfigValue(any(), any())).thenAnswer(inv -> {
            RegionKeys k = inv.getArgument(0);
            Object def = inv.getArgument(1);
            return regionData.getOrDefault(k, def);
        });

        // messages parser
        ConfigParser lang = mock(ConfigParser.class);
        lang.language_mapping = new ConcurrentHashMap<>();
        lang.reverse_language_mapping = new ConcurrentHashMap<>();
        lang.name = "messages.yml";
        doReturn(new EnumMap<>(io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages.class))
                .when(lang).getData();
        when(lang.getConfigValue(any(), any())).thenReturn("");
        fileDatabaseField.set(lang, mockDb);
        configs.configParserMap.put(io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages.class, lang);
        configs.configParserMap.put(io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages.class, lang);
        configs.configParserMap.put(io.github.dailystruggle.rtp.api.configuration.enums.SystemMessages.class, lang);
        configs.configParserMap.put(io.github.dailystruggle.rtp.api.configuration.enums.NetworkMessages.class, lang);
        configs.configParserMap.put(io.github.dailystruggle.rtp.api.configuration.enums.PlaceholderMessages.class, lang);

        // Populate the shape/vert factories so getParameters() sub-knobs exist.
        Factory<Shape<?>> shapeFactory = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
        if (shapeFactory == null) {
            shapeFactory = new Factory<>();
            RTP.factoryMap.put(RTP.factoryNames.shape, shapeFactory);
        }
        shapeFactory.add("SQUARE", new Square());

        Factory<VerticalAdjustor<?>> vertFactory =
                (Factory<VerticalAdjustor<?>>) RTP.factoryMap.get(RTP.factoryNames.vert);
        if (vertFactory == null) {
            vertFactory = new Factory<>();
            RTP.factoryMap.put(RTP.factoryNames.vert, vertFactory);
        }
        vertFactory.add("JUMP", new JumpAdjustor(new ArrayList<>()));

        RTP.baseCommand = mock(io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand.class);
        Map<String, CommandsAPICommand> commandLookup = new java.util.HashMap<>();
        commandLookup.put("reload", mock(CommandsAPICommand.class));
        when(RTP.baseCommand.getCommandLookup()).thenReturn(commandLookup);
    }

    @Test
    void referencedShape_registersDottedCenterZ_andAcceptsNegative() throws Exception {
        SubConfigCmd cmd = new SubConfigCmd(null, "north.yml", regionConfig);

        assertTrue(cmd.getParameterLookup().containsKey("shape.centerz"),
                "shape.centerz must be registered for a shape stored as an @config reference");
        assertTrue(cmd.getParameterLookup().containsKey("shape.centerx"),
                "shape.centerx must be registered too");
        assertTrue(cmd.getParameterLookup().containsKey("shape.radius"),
                "shape.radius must be registered too");

        Predicate<String> perms = s -> true;
        List<String> negMsgs = new ArrayList<>();
        cmd.onCommand(UUID.randomUUID(), perms, negMsgs::add,
                new String[]{"shape.centerZ=-512"}, 0, null).get();
        assertTrue(negMsgs.isEmpty(),
                "negative centerZ must not be rejected, got: " + negMsgs);

        List<String> posMsgs = new ArrayList<>();
        cmd.onCommand(UUID.randomUUID(), perms, posMsgs::add,
                new String[]{"shape.centerZ=512"}, 0, null).get();
        assertTrue(posMsgs.isEmpty(),
                "positive centerZ must not be rejected, got: " + posMsgs);
    }
}
