package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.popcraft.chunky.Chunky;
import org.popcraft.chunky.ChunkyProvider;
import org.popcraft.chunky.shape.ShapeType;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ChunkyChecker} and {@link ChunkyRTPShape} (ENTERPRISE_READINESS item 19, {@code tools} package).
 */
public class ChunkyIntegrationTest {

    @TempDir
    File pluginDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(pluginDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Unregister ChunkyProvider and reset ChunkyChecker static state
        try {
            Method unregister = ChunkyProvider.class.getDeclaredMethod("unregister");
            unregister.setAccessible(true);
            unregister.invoke(null);
        } catch (Throwable ignored) {
        }

        Field chunkyField = ChunkyChecker.class.getDeclaredField("chunky");
        chunkyField.setAccessible(true);
        chunkyField.set(null, null);
    }

    @Test
    void chunkyCheckerPrivateConstructor() throws Exception {
        Constructor<ChunkyChecker> ctor = ChunkyChecker.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        ChunkyChecker instance = ctor.newInstance();
        assertNotNull(instance);
    }

    @Test
    void loadChunkyWhenProviderNotRegisteredIsNoOp() throws Exception {
        ChunkyChecker.loadChunky();
        @SuppressWarnings("unchecked")
        Factory<Shape<?>> shapeFactory =
                (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
        assertNotNull(shapeFactory);
        // Chunky shapes should not be registered when provider returns null / throws
        assertNull(shapeFactory.get("chunky_circle"));

        // Call private getChunky() to verify exception handling / setting chunky to null
        java.lang.reflect.Method getChunkyMethod = ChunkyChecker.class.getDeclaredMethod("getChunky");
        getChunkyMethod.setAccessible(true);
        assertDoesNotThrow(() -> getChunkyMethod.invoke(null));
    }

    @Test
    void loadChunkyWhenProviderRegisteredRegistersAllShapes() throws Exception {
        Chunky mockChunky = Mockito.mock(Chunky.class);
        Method register = ChunkyProvider.class.getDeclaredMethod("register", Chunky.class);
        register.setAccessible(true);
        register.invoke(null, mockChunky);

        ChunkyChecker.loadChunky();

        @SuppressWarnings("unchecked")
        Factory<Shape<?>> shapeFactory =
                (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
        assertNotNull(shapeFactory);

        for (Field field : ShapeType.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType().equals(String.class)) {
                String shapeName = (String) field.get(null);
                String expectedKey = "chunky_" + shapeName;
                assertTrue(shapeFactory.contains(expectedKey),
                        "shapeFactory must contain Chunky shape: " + expectedKey);
            }
        }

        // Idempotent: re-invoking loadChunky does not fail or duplicate
        ChunkyChecker.loadChunky();
        assertTrue(shapeFactory.contains("chunky_circle"));
    }

    @Test
    void chunkyRTPShapeConstructorRegistersShapeInRTP() {
        ChunkyRTPShape shape = new ChunkyRTPShape("square");
        assertEquals("chunky_square", shape.chunkyShapeName);

        @SuppressWarnings("unchecked")
        Factory<Shape<?>> shapeFactory =
                (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
        assertNotNull(shapeFactory);
        assertTrue(shapeFactory.contains("SQUARE"));
    }

    @Test
    void chunkyRTPShapeRandExecutesWithRegisteredProvider() throws Exception {
        Chunky mockChunky = Mockito.mock(Chunky.class);
        Method register = ChunkyProvider.class.getDeclaredMethod("register", Chunky.class);
        register.setAccessible(true);
        register.invoke(null, mockChunky);

        ChunkyRTPShape shape = new ChunkyRTPShape("square");
        long rand = shape.rand();
        int[] xz = shape.locationToXZ(rand);
        assertNotNull(xz);
        assertEquals(2, xz.length);
        assertTrue(rand >= 0L);

        // Test with custom center and dimensions
        shape.set(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams.centerX, 100);
        shape.set(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams.centerZ, -50);
        shape.set(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams.width, 500);
        shape.set(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams.height, 500);

        long boundedRand = shape.rand();
        assertTrue(boundedRand >= 0L);
    }

    @Test
    void chunkyRTPShapeRandWithFallbackWhenUnbounded() throws Exception {
        Chunky mockChunky = Mockito.mock(Chunky.class);
        Method register = ChunkyProvider.class.getDeclaredMethod("register", Chunky.class);
        register.setAccessible(true);
        register.invoke(null, mockChunky);

        ChunkyRTPShape shape = new ChunkyRTPShape("circle") {
            private int callCount = 0;
            @Override
            public long rand() {
                // Testing that when shape loop exceeds 10000, fallback returns -1 or badPrefixSumsCache[0]
                return super.rand();
            }
        };

        // When radius is 0 and centerX/Z are far away, Chunky shape.isBounding returns false for all candidate coords
        shape.set(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams.width, 0);
        shape.set(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams.height, 0);
        shape.set(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams.centerX, 1000000);
        shape.set(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams.centerZ, 1000000);

        long res = shape.rand();
        assertTrue(res == -1L || res >= 0L);
    }
}
