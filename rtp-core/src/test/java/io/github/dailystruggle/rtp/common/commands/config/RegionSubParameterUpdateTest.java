package io.github.dailystruggle.rtp.common.commands.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

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
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for {@link SubConfigCmd} verifying that user-supplied sub-parameters
 * (e.g. {@code /rtp config region default.yml shape:square radius:128}) update
 * the region config's nested shape/vert sub-map with the supplied values.
 *
 * <p>This pins V2 behaviour: when the user types a top-level enum-typed parameter
 * (shape or vert) plus one of its sub-parameters (radius, centerRadius, mode, ...),
 * {@link SubConfigCmd#onCommand} must rebuild the shape/vert section as a
 * {@code Map<String, Object>} with the user's overrides applied, then hand it
 * to {@link ConfigParser#set(String, Object)}.
 */
public class RegionSubParameterUpdateTest {

    @TempDir
    Path tempDir;

    private ConfigParser<RegionKeys> regionConfig;
    private EnumMap<RegionKeys, Object> regionData;
    private ConfigParser lang;
    private io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase mockDb;

    @BeforeEach
    void setUp() throws Exception {
        RTPTestSetup.install(tempDir.toFile());
        RTP.selectionAPI = new io.github.dailystruggle.rtp.common.selection.SelectionAPI();

        Configs configs = RTP.configs;

        mockDb = mock(io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase.class);
        Field cachedLookupField = io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase.class
                .getDeclaredField("cachedLookup");
        cachedLookupField.setAccessible(true);
        cachedLookupField.set(mockDb, new AtomicReference<>(new ConcurrentHashMap<>()));

        regionConfig = mock(ConfigParser.class);
        regionConfig.language_mapping = new ConcurrentHashMap<>();
        regionConfig.reverse_language_mapping = new ConcurrentHashMap<>();
        regionConfig.name = "default.yml";

        Field myClassField = io.github.dailystruggle.rtp.common.factory.FactoryValue.class
                .getDeclaredField("myClass");
        myClassField.setAccessible(true);
        myClassField.set(regionConfig, RegionKeys.class);

        Field fileDatabaseField = ConfigParser.class.getDeclaredField("fileDatabase");
        fileDatabaseField.setAccessible(true);
        fileDatabaseField.set(regionConfig, mockDb);

        // Region data with a Shape value under `shape` and a VerticalAdjustor under `vert`,
        // so addParameters() registers ShapeParameter / VertParameter for them.
        regionData = new EnumMap<>(RegionKeys.class);
        regionData.put(RegionKeys.shape, new Square());
        regionData.put(RegionKeys.vert, new JumpAdjustor(new java.util.ArrayList<>()));
        doReturn(regionData).when(regionConfig).getData();
        when(regionConfig.getConfigValue(any(), any())).thenAnswer(inv -> {
            RegionKeys k = inv.getArgument(0);
            Object def = inv.getArgument(1);
            return regionData.getOrDefault(k, def);
        });
        // record set() calls so tests can assert on the composite map
        doAnswer(inv -> {
            String keyStr = inv.getArgument(0);
            Object val = inv.getArgument(1);
            for (RegionKeys k : RegionKeys.values()) {
                if (k.name().equalsIgnoreCase(keyStr)) {
                    regionData.put(k, val);
                    break;
                }
            }
            return null;
        }).when(regionConfig).set(anyString(), any());
        configs.configParserMap.put(RegionKeys.class, regionConfig);

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

        // Populate the shape and vert factories with at least one entry each so
        // SubConfigCmd's `factory.get("SQUARE")` / `factory.get("JUMP")` paths resolve.
        Factory<Shape<?>> shapeFactory =
                (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
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
        vertFactory.add("JUMP", new JumpAdjustor(new java.util.ArrayList<>()));

        RTP.baseCommand = mock(io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand.class);
        Map<String, CommandsAPICommand> commandLookup = new HashMap<>();
        commandLookup.put("reload", mock(CommandsAPICommand.class));
        when(RTP.baseCommand.getCommandLookup()).thenReturn(commandLookup);
    }

    /**
     * Captures the {@code Map} passed to {@code regionConfig.set(key, ...)}
     * during the async {@code onCommand} run. Waits up to 2s for the call.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> waitForSet(String topKey) throws InterruptedException {
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                verify(regionConfig, atLeastOnce()).set(eq(topKey), valueCaptor.capture());
                Object captured = valueCaptor.getValue();
                if (captured instanceof Map) {
                    return (Map<String, Object>) captured;
                }
            } catch (AssertionError ignored) {
                // not yet — keep waiting
            }
            Thread.sleep(25);
        }
        fail("regionConfig.set(\"" + topKey + "\", <Map>) was never invoked");
        return null; // unreachable
    }

    // ── shape sub-parameter tests ───────────────────────────────────────────

    @Test
    void onCommand_shapeWithRadiusOverride_setsRadiusInSubMap() throws InterruptedException {
        SubConfigCmd cmd = new SubConfigCmd(null, "default.yml", regionConfig);

        Map<String, List<String>> params = new HashMap<>();
        params.put("shape", Collections.singletonList("SQUARE"));
        params.put("radius", Collections.singletonList("128"));

        cmd.onCommand(UUID.randomUUID(), params, null);

        Map<String, Object> shapeMap = waitForSet("shape");
        assertEquals("SQUARE", shapeMap.get("name"), "shape composite must record the shape name");
        assertEquals("128", shapeMap.get("radius"),
                "user-supplied radius:128 must be merged into the shape sub-map");
    }

    @Test
    void onCommand_shapeWithMultipleSubParams_appliesAllOverrides() throws InterruptedException {
        SubConfigCmd cmd = new SubConfigCmd(null, "default.yml", regionConfig);

        Map<String, List<String>> params = new HashMap<>();
        params.put("shape", Collections.singletonList("SQUARE"));
        params.put("radius", Collections.singletonList("512"));
        params.put("centerradius", Collections.singletonList("64"));
        params.put("centerx", Collections.singletonList("100"));
        params.put("centerz", Collections.singletonList("-200"));

        cmd.onCommand(UUID.randomUUID(), params, null);

        Map<String, Object> shapeMap = waitForSet("shape");
        assertEquals("512", shapeMap.get("radius"));
        assertEquals("64", shapeMap.get("centerRadius"));
        assertEquals("100", shapeMap.get("centerX"));
        assertEquals("-200", shapeMap.get("centerZ"));
    }

    @Test
    void onCommand_shapeWithoutSubParams_keepsDefaults() throws InterruptedException {
        SubConfigCmd cmd = new SubConfigCmd(null, "default.yml", regionConfig);

        Map<String, List<String>> params = new HashMap<>();
        params.put("shape", Collections.singletonList("SQUARE"));

        cmd.onCommand(UUID.randomUUID(), params, null);

        Map<String, Object> shapeMap = waitForSet("shape");
        assertEquals("SQUARE", shapeMap.get("name"));
        // Square's default radius is 256 (see Square.defaults). When the user supplies
        // no override, the sub-map's `radius` must be the default — not removed.
        assertEquals(256, shapeMap.get("radius"),
                "with no radius override the shape sub-map must carry the shape's default");
    }

    @Test
    void onCommand_unknownShape_doesNotInvokeSetForShape() throws InterruptedException {
        SubConfigCmd cmd = new SubConfigCmd(null, "default.yml", regionConfig);

        Map<String, List<String>> params = new HashMap<>();
        params.put("shape", Collections.singletonList("NOT_A_REAL_SHAPE"));
        params.put("radius", Collections.singletonList("128"));

        cmd.onCommand(UUID.randomUUID(), params, null);
        Thread.sleep(400);

        // For an unresolved shape SubConfigCmd reports msgBadParameter and skips the set.
        verify(regionConfig, never()).set(eq("shape"), any());
    }

    @Test
    void onCommand_shapeOverrideAtConfigScalar_materializesBlockInsteadOfPreservingToken()
            throws InterruptedException {
        // Region file ships `shape: "@config"` (ADR-073 inheritance token). The
        // operator explicitly picks SQUARE. Before the fix, ConfigParser.set saw
        // the on-disk `@config` token and preserved it verbatim, silently
        // discarding SQUARE so the value reverted to the global default (CIRCLE)
        // on the next reload. The explicit override must instead materialize the
        // chosen block onto disk (materialize-only-changed-keys).
        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig yaml =
                io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig.parse(
                        "shape: \"@config\"\n");
        mockDb.cachedLookup.get().put("default.yml", yaml);

        SubConfigCmd cmd = new SubConfigCmd(null, "default.yml", regionConfig);

        Map<String, List<String>> params = new HashMap<>();
        params.put("shape", Collections.singletonList("SQUARE"));

        cmd.onCommand(UUID.randomUUID(), params, null);

        // Poll for the async write to land on the YAML document.
        long deadline = System.currentTimeMillis() + 2_000;
        Object parent = null;
        while (System.currentTimeMillis() < deadline) {
            parent = yaml.get("shape");
            if (parent instanceof io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection) {
                break;
            }
            Thread.sleep(25);
        }

        assertTrue(parent instanceof io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection,
                "explicit shape override must materialize a nested section, not preserve the @config token");
        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection section =
                (io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection) parent;
        assertEquals("SQUARE", String.valueOf(section.get("name")).toUpperCase(),
                "the operator's chosen shape type must be saved, not reverted to the default");
    }

    @Test
    void onCommand_dottedLeafLowercasedByParser_updatesCanonicalKeyNotOrphan()
            throws InterruptedException {
        // The commands-api parser lower-cases every parameter name, so a menu
        // edit of `shape.centerZ` arrives as the dotted key `shape.centerz`.
        // YAML mappings are case-sensitive, so writing that verbatim would add
        // a NEW orphan leaf `centerz` alongside the real `centerZ` (which keeps
        // its old value), and the edit would appear to vanish on reload. The
        // write must instead restore the canonical leaf case and overwrite the
        // real `centerZ`.
        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig yaml =
                io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig.parse(
                        "shape: \"@config\"\n");
        mockDb.cachedLookup.get().put("default.yml", yaml);

        SubConfigCmd cmd = new SubConfigCmd(null, "default.yml", regionConfig);

        Map<String, List<String>> params = new HashMap<>();
        params.put("shape.centerz", Collections.singletonList("512"));

        cmd.onCommand(UUID.randomUUID(), params, null);

        long deadline = System.currentTimeMillis() + 2_000;
        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection section = null;
        while (System.currentTimeMillis() < deadline) {
            Object parent = yaml.get("shape");
            if (parent instanceof io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection) {
                section = (io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection) parent;
                if (section.getKeys(false).contains("centerZ")
                        && "512".equals(String.valueOf(section.get("centerZ")))) {
                    break;
                }
            }
            Thread.sleep(25);
        }

        assertNotNull(section, "shape block must be materialized as a nested section");
        assertEquals("512", String.valueOf(section.get("centerZ")),
                "the canonical centerZ leaf must carry the edited value");
        assertFalse(section.getKeys(false).contains("centerz"),
                "no lowercase orphan leaf may be created alongside the canonical centerZ");
    }

    // ── vert sub-parameter tests ────────────────────────────────────────────

    @Test
    void onCommand_vertWithSubParam_setsSubParamInVertMap() throws InterruptedException {
        SubConfigCmd cmd = new SubConfigCmd(null, "default.yml", regionConfig);

        // JumpAdjustor exposes maxY/minY as sub-parameters (see JumpAdjustor.subParameters).
        Map<String, List<String>> params = new HashMap<>();
        params.put("vert", Collections.singletonList("JUMP"));
        params.put("maxy", Collections.singletonList("200"));
        params.put("miny", Collections.singletonList("40"));

        cmd.onCommand(UUID.randomUUID(), params, null);

        Map<String, Object> vertMap = waitForSet("vert");
        assertEquals("JUMP", String.valueOf(vertMap.get("name")).toUpperCase(),
                "vert composite must record the adjustor name (case-insensitive)");
        assertEquals("200", vertMap.get("maxY"),
                "user-supplied maxy override must land in the vert sub-map");
        assertEquals("40", vertMap.get("minY"),
                "user-supplied miny override must land in the vert sub-map");
    }
}
