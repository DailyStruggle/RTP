package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CommandTreeMenuBuilder options picker and nested flattening")
final class CommandTreeMenuBuilderPickerAndNestingTest {

    @TempDir
    Path tempDir;

    private UUID viewer;
    private CommandTreeMenuBuilder builder;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        viewer = UUID.randomUUID();
        builder = new CommandTreeMenuBuilder();
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = null;
        RTP.scheduler = null;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
    }

    @Test
    @DisplayName("buildOptionsPicker validates arguments and builds paginated options")
    void buildOptionsPicker_validationAndPagination() {
        assertThrows(NullPointerException.class, () -> builder.buildOptionsPicker(null, "config", "radius", "100", List.of("100", "200")));
        assertThrows(NullPointerException.class, () -> builder.buildOptionsPicker(viewer, null, "radius", "100", List.of("100", "200")));
        assertThrows(NullPointerException.class, () -> builder.buildOptionsPicker(viewer, "config", null, "100", List.of("100", "200")));
        assertThrows(NullPointerException.class, () -> builder.buildOptionsPicker(viewer, "config", "radius", "100", null));

        assertThrows(IllegalArgumentException.class, () -> builder.buildOptionsPicker(viewer, "", "radius", "100", List.of("100")));
        assertThrows(IllegalArgumentException.class, () -> builder.buildOptionsPicker(viewer, "config", "", "100", List.of("100")));
        assertThrows(IllegalArgumentException.class, () -> builder.buildOptionsPicker(viewer, "config", "radius", "100", Collections.emptyList()));

        // Normal options picker
        MenuModel model = builder.buildOptionsPicker(viewer, "config", "radius", "100", List.of("100", "200", "300"));
        assertNotNull(model);
        assertEquals("config:config:radius:options", model.title());
        assertFalse(model.pages().isEmpty());

        // Null/empty current value
        MenuModel modelUnset = builder.buildOptionsPicker(viewer, "config", "radius", null, List.of("100", "200"));
        assertNotNull(modelUnset);

        // Many options forcing pagination (> 14 visual lines)
        List<String> largeOptions = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            largeOptions.add("option_value_number_" + i);
        }
        MenuModel paginated = builder.buildOptionsPicker(viewer, "config", "radius", "option_value_number_0", largeOptions);
        assertTrue(paginated.pages().size() >= 2, "must paginate when options exceed visual lines per page");
    }

    @Test
    @DisplayName("flattenNestedConfigValue handles Map, FactoryValue, RtpYamlSection, and empty/scalar nodes")
    void flattenNestedConfigValue_types() throws Exception {
        Method mFlatten = CommandTreeMenuBuilder.class.getDeclaredMethod("flattenNestedConfigValue", Object.class);
        mFlatten.setAccessible(true);

        // Null and non-container objects return null
        assertNull(mFlatten.invoke(null, (Object) null));
        assertNull(mFlatten.invoke(null, 123));
        assertNull(mFlatten.invoke(null, "hello"));

        // 1. Map flattening
        Map<String, Object> simpleMap = new LinkedHashMap<>();
        simpleMap.put("a", 1);
        simpleMap.put("b", "test");
        simpleMap.put("emptyMap", Collections.emptyMap());
        simpleMap.put("nested", Map.of("c", 3, "d", Collections.emptyMap()));

        @SuppressWarnings("unchecked")
        List<String[]> mapResult = (List<String[]>) mFlatten.invoke(null, simpleMap);
        assertNotNull(mapResult);
        assertTrue(mapResult.stream().anyMatch(pair -> pair[0].equals("a") && pair[1].equals("1")));
        assertTrue(mapResult.stream().anyMatch(pair -> pair[0].equals("emptyMap") && pair[1].equals("&8(empty)")));
        assertTrue(mapResult.stream().anyMatch(pair -> pair[0].equals("nested.c") && pair[1].equals("3")));

        // Empty Map
        @SuppressWarnings("unchecked")
        List<String[]> emptyMapResult = (List<String[]>) mFlatten.invoke(null, Collections.emptyMap());
        assertNotNull(emptyMapResult);
        assertTrue(emptyMapResult.isEmpty());

        // 2. FactoryValue flattening
        Square square = new Square();
        square.set(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams.radius, 500L);

        @SuppressWarnings("unchecked")
        List<String[]> fvResult = (List<String[]>) mFlatten.invoke(null, square);
        assertNotNull(fvResult);
        assertTrue(fvResult.stream().anyMatch(pair -> pair[0].equals("name") && pair[1].equals("SQUARE")));
        assertTrue(fvResult.stream().anyMatch(pair -> pair[0].equals("radius") && pair[1].equals("500")));

        // Map with FactoryValue nested
        Map<String, Object> mapWithFv = Map.of("shape", square);
        @SuppressWarnings("unchecked")
        List<String[]> mapFvResult = (List<String[]>) mFlatten.invoke(null, mapWithFv);
        assertNotNull(mapFvResult);
        assertTrue(mapFvResult.stream().anyMatch(pair -> pair[0].equals("shape.name") && pair[1].equals("SQUARE")));

        // Map with empty nested Map
        Map<String, Object> mapWithEmptyNested = Map.of("sub", Collections.emptyMap());
        @SuppressWarnings("unchecked")
        List<String[]> mapEmptyNestedResult = (List<String[]>) mFlatten.invoke(null, mapWithEmptyNested);
        assertNotNull(mapEmptyNestedResult);
        assertTrue(mapEmptyNestedResult.stream().anyMatch(pair -> pair[0].equals("sub") && pair[1].equals("&8(empty)")));
    }
}
