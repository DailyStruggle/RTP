package io.github.dailystruggle.rtp.common.selection.region.selectors;

import io.github.dailystruggle.rtp.common.configuration.enums.DatabaseKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.PolygonMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.enums.GenericShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.enums.RectangleParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Enum domain values test")
class EnumValuesCoverageTest {

    @Test
    void testDatabaseKeys() {
        for (DatabaseKeys key : DatabaseKeys.values()) {
            assertNotNull(key);
            assertEquals(key, DatabaseKeys.valueOf(key.name()));
        }
    }

    @Test
    void testGenericShapeParams() {
        for (GenericShapeParams param : GenericShapeParams.values()) {
            assertNotNull(param);
            assertEquals(param, GenericShapeParams.valueOf(param.name()));
        }
    }

    @Test
    void testPolygonMemoryShapeParams() {
        for (PolygonMemoryShapeParams param : PolygonMemoryShapeParams.values()) {
            assertNotNull(param);
            assertEquals(param, PolygonMemoryShapeParams.valueOf(param.name()));
        }
    }

    @Test
    void testRectangleParams() {
        for (RectangleParams param : RectangleParams.values()) {
            assertNotNull(param);
            assertEquals(param, RectangleParams.valueOf(param.name()));
        }
    }
}
