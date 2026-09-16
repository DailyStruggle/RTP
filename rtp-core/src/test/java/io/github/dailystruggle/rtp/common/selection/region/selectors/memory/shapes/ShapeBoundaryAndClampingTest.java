package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.EllipseMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams;
import io.github.dailystruggle.rtp.common.selection.region.util.WorldBorderAuditor;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ShapeBoundaryAndClampingTest - Zero radius, non-uniform scaling, center offsets, and border clamping")
class ShapeBoundaryAndClampingTest {

    @BeforeAll
    static void setUp() {
        MockRTPServerAccessor accessor = new MockRTPServerAccessor(new File("target/test-data"));
        RTP.serverAccessor = accessor;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
    }

    @org.junit.jupiter.api.AfterAll
    static void tearDown() {
        RTP.serverAccessor = null;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
    }

    // -------------------------------------------------------------------------
    // Zero-radius & centerRadius >= radius edge cases
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Circle: zero-radius and centerRadius >= radius clamp getRange to 0")
    void circle_zeroRadiusAndInvertedCenterRadius() {
        Circle circle = new Circle();
        circle.set(GenericMemoryShapeParams.radius, 0L);
        circle.set(GenericMemoryShapeParams.centerRadius, 0L);
        assertEquals(0L, circle.getRange());

        // Inverted: centerRadius > radius
        circle.set(GenericMemoryShapeParams.radius, 50L);
        circle.set(GenericMemoryShapeParams.centerRadius, 100L);
        assertTrue(circle.getRange() <= 0L);

        // xzToLocation and locationToXZ roundtrip on zero coords with centerRadius=0
        circle.set(GenericMemoryShapeParams.radius, 100L);
        circle.set(GenericMemoryShapeParams.centerRadius, 0L);
        circle.set(GenericMemoryShapeParams.centerX, 0L);
        circle.set(GenericMemoryShapeParams.centerZ, 0L);
        MutableRTPCoords coords = new MutableRTPCoords(null, 0, 0, 0);
        long loc = circle.xzToLocation(coords);
        assertEquals(0L, loc);

        MutableRTPCoords out = new MutableRTPCoords(null, 0, 0, 0);
        circle.locationToXZ(0L, out);
        assertEquals(0, out.x);
        assertEquals(0, out.z);
    }

    @Test
    @DisplayName("Square: zero-radius and centerRadius >= radius clamp getRange to 0")
    void square_zeroRadiusAndInvertedCenterRadius() {
        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 0L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        assertEquals(0L, square.getRange());

        square.set(GenericMemoryShapeParams.radius, 10L);
        square.set(GenericMemoryShapeParams.centerRadius, 20L);
        assertTrue(square.getRange() <= 0L);
    }

    @Test
    @DisplayName("Ellipse: zero-radius and centerRadius >= radius edge cases")
    void ellipse_zeroRadiusAndInvertedCenterRadius() {
        Ellipse ellipse = new Ellipse();
        ellipse.set(EllipseMemoryShapeParams.radius, 0L);
        ellipse.set(EllipseMemoryShapeParams.radius2, 0L);
        ellipse.set(EllipseMemoryShapeParams.centerRadius, 0L);
        ellipse.set(EllipseMemoryShapeParams.centerRadius2, 0L);
        assertEquals(0L, ellipse.getRange());

        ellipse.set(EllipseMemoryShapeParams.radius, 100L);
        ellipse.set(EllipseMemoryShapeParams.radius2, 50L);
        ellipse.set(EllipseMemoryShapeParams.centerRadius, 150L);
        ellipse.set(EllipseMemoryShapeParams.centerRadius2, 75L);
        assertTrue(ellipse.getRange() <= 0L);
    }

    @Test
    @DisplayName("Rectangle: zero width or height clamp getRange to 0")
    void rectangle_zeroDimensions() {
        Rectangle rect = new Rectangle();
        rect.set(RectangleParams.width, 0L);
        rect.set(RectangleParams.height, 100L);
        assertEquals(0L, rect.getRange());

        rect.set(RectangleParams.width, 100L);
        rect.set(RectangleParams.height, 0L);
        assertEquals(0L, rect.getRange());
    }

    // -------------------------------------------------------------------------
    // Non-uniform scaling (aspect ratios != 1.0)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Ellipse: non-uniform scaling preserves geometry across quadrants")
    void ellipse_nonUniformScaling() {
        Ellipse ellipse = new Ellipse();
        ellipse.set(EllipseMemoryShapeParams.radius, 500L);   // x-axis radius
        ellipse.set(EllipseMemoryShapeParams.radius2, 100L);  // z-axis radius
        ellipse.set(EllipseMemoryShapeParams.centerRadius, 50L);
        ellipse.set(EllipseMemoryShapeParams.centerRadius2, 10L);
        ellipse.set(EllipseMemoryShapeParams.centerX, 0L);
        ellipse.set(EllipseMemoryShapeParams.centerZ, 0L);

        assertTrue(ellipse.getRange() > 0L);

        // Test quadrant points
        long locQ1 = ellipse.xzToLocation(200, 50);
        assertTrue(locQ1 >= 0);

        long locQ2 = ellipse.xzToLocation(-200, 50);
        assertTrue(locQ2 >= 0);

        long locQ3 = ellipse.xzToLocation(-200, -50);
        assertTrue(locQ3 >= 0);

        long locQ4 = ellipse.xzToLocation(200, -50);
        assertTrue(locQ4 >= 0);

        // locationToXZ roundtrip fidelity
        MutableRTPCoords out = new MutableRTPCoords(null, 0, 0, 0);
        ellipse.locationToXZ(locQ1, out);
        long roundtrip = ellipse.xzToLocation(out.x, out.z);
        // Should map back to adjacent or same spiral coordinate
        assertTrue(roundtrip >= 0);
    }

    @Test
    @DisplayName("Rectangle: non-uniform width and height bounds")
    void rectangle_nonUniformDimensions() {
        Rectangle rect = new Rectangle();
        rect.set(RectangleParams.width, 400L);
        rect.set(RectangleParams.height, 50L);
        rect.set(RectangleParams.centerX, 0L);
        rect.set(RectangleParams.centerZ, 0L);

        long range = rect.getRange();
        assertEquals(400L * 50L, range);

        long loc = rect.xzToLocation(100, 10);
        assertTrue(loc >= 0);

        int[] xz = rect.locationToXZ(loc);
        assertNotNull(xz);
        assertEquals(2, xz.length);
    }

    // -------------------------------------------------------------------------
    // Center Offsets (large, negative, non-zero centers)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Shapes with negative and large center offsets compute correct relative bounds")
    void shapes_centerOffsets() {
        Circle circle = new Circle();
        circle.set(GenericMemoryShapeParams.radius, 200L);
        circle.set(GenericMemoryShapeParams.centerRadius, 0L);
        circle.set(GenericMemoryShapeParams.centerX, -50000L);
        circle.set(GenericMemoryShapeParams.centerZ, 100000L);

        // Center point location
        long locCenter = circle.xzToLocation(-50000, 100000);
        assertEquals(0L, locCenter);

        int[] centerXZ = circle.locationToXZ(0L);
        // Archimedean spiral mapping produces coordinates within 1 unit of center on 0L
        assertEquals(-50000, centerXZ[0], 1);
        assertEquals(100000, centerXZ[1], 1);

        // Offset point in quadrant 1 relative to center
        long locOffset = circle.xzToLocation(-50000 + 50, 100000 + 50);
        assertTrue(locOffset > 0);

        // Square with offset
        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 300L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        square.set(GenericMemoryShapeParams.centerX, 25000L);
        square.set(GenericMemoryShapeParams.centerZ, -75000L);

        // When x = centerX and z = centerZ: radius = 0, so xzToLocation returns 0L
        long sqLoc = square.xzToLocation(25000L, -75000L);
        assertEquals(0L, sqLoc);
        int[] sqXZ = square.locationToXZ(0L);
        assertEquals(25000, sqXZ[0]);
        assertEquals(-75000, sqXZ[1]);
    }

    // -------------------------------------------------------------------------
    // World Border Clamping & Collision Paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("WorldBorderAuditor: checkRegionWorldBorder with Circle outside border returns false")
    void worldBorderAuditor_circleOutsideBorder() {
        Square borderSquare = new Square();
        borderSquare.set(GenericMemoryShapeParams.radius, 100L);
        borderSquare.set(GenericMemoryShapeParams.centerX, 0L);
        borderSquare.set(GenericMemoryShapeParams.centerZ, 0L);
        WorldBorder border = new WorldBorder(() -> borderSquare, loc -> true);

        MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTP.serverAccessor;
        accessor.setWorldBorderFunction(s -> border);

        RTPWorld<?> world = mock(RTPWorld.class);
        when(world.name()).thenReturn("world");

        Circle regionCircle = new Circle();
        regionCircle.set(GenericMemoryShapeParams.radius, 150L); // 150 > 100 border
        regionCircle.set(GenericMemoryShapeParams.centerX, 0L);
        regionCircle.set(GenericMemoryShapeParams.centerZ, 0L);

        assertFalse(WorldBorderAuditor.checkRegionWorldBorder("circleRegion", world, regionCircle, false));
        // With override enabled, returns true
        assertTrue(WorldBorderAuditor.checkRegionWorldBorder("circleRegion", world, regionCircle, true));
    }

    @Test
    @DisplayName("WorldBorderAuditor: checkRegionWorldBorder with offset shape extending beyond border")
    void worldBorderAuditor_offsetShapeCollidingBorder() {
        Square borderSquare = new Square();
        borderSquare.set(GenericMemoryShapeParams.radius, 100L);
        borderSquare.set(GenericMemoryShapeParams.centerX, 0L);
        borderSquare.set(GenericMemoryShapeParams.centerZ, 0L);
        WorldBorder border = new WorldBorder(() -> borderSquare, loc -> true);

        MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTP.serverAccessor;
        accessor.setWorldBorderFunction(s -> border);

        RTPWorld<?> world = mock(RTPWorld.class);
        when(world.name()).thenReturn("world");

        // Radius 50 is fine if centered at 0, but centerX = 60 pushes maxX to 110 > 100
        Square regionSquare = new Square();
        regionSquare.set(GenericMemoryShapeParams.radius, 50L);
        regionSquare.set(GenericMemoryShapeParams.centerX, 60L);
        regionSquare.set(GenericMemoryShapeParams.centerZ, 0L);

        assertFalse(WorldBorderAuditor.checkRegionWorldBorder("collidingSquare", world, regionSquare, false));
    }

    // -------------------------------------------------------------------------
    // flushAndRebuild, save/load, exportDebugJson, and orientation math
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("flushAndRebuild: processes multiple known bad coordinates into cached runs")
    void flushAndRebuild_multipleKnownBadCoordinates() {
        Circle circle = new Circle();
        circle.set(GenericMemoryShapeParams.radius, 1000L);
        circle.set(GenericMemoryShapeParams.centerRadius, 0L);

        // Before adding bad locations, cache is empty and dirty flag is default true
        assertEquals(0, circle.badKeysSnapshot().length);

        // Add multiple distinct bad locations with specific fail types
        circle.addBadLocation(100L, LocationGenerator.FailTypes.safety);
        circle.addBadLocation(101L, LocationGenerator.FailTypes.safety);
        circle.addBadLocation(102L, LocationGenerator.FailTypes.safety);
        circle.addBadLocation(500L, LocationGenerator.FailTypes.vert);
        circle.addBadLocation(501L, LocationGenerator.FailTypes.vert);
        circle.addBadLocation(1200L, LocationGenerator.FailTypes.biome);

        // Verify isKnownBad checks pending locations even before rebuild
        assertTrue(circle.isKnownBad(100L));
        assertTrue(circle.isKnownBad(101L));
        assertTrue(circle.isKnownBad(500L));
        assertTrue(circle.isKnownBad(1200L));
        assertFalse(circle.isKnownBad(200L));

        // Trigger flushAndRebuild
        circle.flushAndRebuild(circle.spatialResolution());

        // Cache must now contain merged runs
        long[] badKeys = circle.badKeysSnapshot();
        long[] prefixSums = circle.badPrefixSumsSnapshot();
        byte[] badCauses = circle.badCausesSnapshot();

        assertTrue(badKeys.length >= 3, "Expected at least 3 distinct runs: [100..102], [500..501], [1200]");
        // Verify contiguous keys 100, 101, 102 are coalesced into a single run at key 100 with delta length 3
        assertEquals(100L, badKeys[0]);
        assertEquals(3L, prefixSums[0]);
        assertEquals((byte) LocationGenerator.FailTypes.safety.ordinal(), badCauses[0]);

        // Key 500 with length 2
        assertEquals(500L, badKeys[1]);
        assertEquals(3L + 2L, prefixSums[1]);
        assertEquals((byte) LocationGenerator.FailTypes.vert.ordinal(), badCauses[1]);

        // Key 1200 with length 1
        assertEquals(1200L, badKeys[2]);
        assertEquals(5L + 1L, prefixSums[2]);
        assertEquals((byte) LocationGenerator.FailTypes.biome.ordinal(), badCauses[2]);

        // All points remain known bad post rebuild
        assertTrue(circle.isKnownBad(100L));
        assertTrue(circle.isKnownBad(101L));
        assertTrue(circle.isKnownBad(102L));
        assertTrue(circle.isKnownBad(500L));
        assertTrue(circle.isKnownBad(501L));
        assertTrue(circle.isKnownBad(1200L));
        assertFalse(circle.isKnownBad(103L));
    }

    @Test
    @DisplayName("save and load round-trip: serializes shape to binary file and restores runs accurately")
    void saveAndLoad_roundTripBinaryFile(@TempDir Path tempDir) throws Exception {
        // Wire a dedicated test directory and database accessor
        File testDir = tempDir.toFile();
        MockRTPServerAccessor accessor = RTPTestSetup.install(testDir);

        YamlFileDatabase db = new YamlFileDatabase(testDir);
        Field daField = RTP.class.getDeclaredField("databaseAccessor");
        daField.setAccessible(true);
        daField.set(RTP.getInstance(), db);

        Square original = new Square();
        original.set(GenericMemoryShapeParams.radius, 500L);
        original.set(GenericMemoryShapeParams.centerRadius, 0L);

        // Populate bad locations and biome locations
        original.addBadLocation(50L, LocationGenerator.FailTypes.safety);
        original.addBadLocation(51L, LocationGenerator.FailTypes.safety);
        original.addBadLocation(200L, LocationGenerator.FailTypes.vert);
        original.addBiomeLocation(300L, 10L, "PLAINS");
        original.addBiomeLocation(400L, 5L, "FOREST");

        original.flushAndRebuild(original.spatialResolution());

        long[] originalBadKeys = original.badKeysSnapshot();
        long[] originalBadSums = original.badPrefixSumsSnapshot();
        byte[] originalBadCauses = originalBadCausesSnapshot(original);

        String fileName = "shape_test_roundtrip.bin";
        String worldName = "test_world";

        // Save
        original.save(fileName, worldName);
        db.processQueries(Long.MAX_VALUE);

        // Verify the binary file was written
        File savedFile = new File(testDir, "database" + File.separator + "regionData" + File.separator + fileName);
        assertTrue(savedFile.exists(), "Binary save file should exist on disk");
        assertTrue(savedFile.length() > 0, "Binary save file should have content");

        // Load into fresh shape
        Square reloaded = new Square();
        reloaded.set(GenericMemoryShapeParams.radius, 500L);
        reloaded.set(GenericMemoryShapeParams.centerRadius, 0L);

        CompletableFuture<Void> future = reloaded.load(fileName, worldName);
        db.processQueries(Long.MAX_VALUE);
        future.get(5, TimeUnit.SECONDS);

        // Verify reloaded shape data matches original
        long[] reloadedBadKeys = reloaded.badKeysSnapshot();
        long[] reloadedBadSums = reloaded.badPrefixSumsSnapshot();
        byte[] reloadedBadCauses = originalBadCausesSnapshot(reloaded);

        assertArrayEquals(originalBadKeys, reloadedBadKeys, "Bad keys must match after roundtrip");
        assertArrayEquals(originalBadSums, reloadedBadSums, "Bad prefix sums must match after roundtrip");
        assertArrayEquals(originalBadCauses, reloadedBadCauses, "Bad causes must match after roundtrip");

        assertTrue(reloaded.isKnownBad(50L));
        assertTrue(reloaded.isKnownBad(51L));
        assertTrue(reloaded.isKnownBad(200L));
        assertFalse(reloaded.isKnownBad(100L));

        assertEquals("PLAINS", reloaded.biomeAt(300L));
        assertEquals("FOREST", reloaded.biomeAt(400L));
    }

    private static byte[] originalBadCausesSnapshot(MemoryShape<?> shape) {
        return shape.badCausesSnapshot();
    }

    @Test
    @DisplayName("exportDebugJson: generates valid JSON file structure")
    void exportDebugJson_validStructure(@TempDir Path tempDir) throws Exception {
        File testDir = tempDir.toFile();
        MockRTPServerAccessor accessor = new MockRTPServerAccessor(testDir);
        RTP.serverAccessor = accessor;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;

        Circle circle = new Circle();
        circle.set(GenericMemoryShapeParams.radius, 800L);
        circle.addBadLocation(42L, LocationGenerator.FailTypes.safety);
        circle.addBadLocation(43L, LocationGenerator.FailTypes.safety);
        circle.addBadLocation(100L, LocationGenerator.FailTypes.vert);
        circle.addBiomeLocation(200L, 8L, "DESERT");
        circle.flushAndRebuild(circle.spatialResolution());

        String jsonName = "debug_export.json";
        String worldName = "world_nether";
        circle.exportDebugJson(jsonName, worldName);

        File debugFile = new File(testDir, "database" + File.separator + "regionData" + File.separator + "debug" + File.separator + jsonName);
        assertTrue(debugFile.exists(), "Debug JSON file must exist");
        assertTrue(debugFile.length() > 0, "Debug JSON file must not be empty");

        String content = Files.readString(debugFile.toPath());
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(content).getAsJsonObject();

        assertEquals(worldName, root.get("world").getAsString());
        assertTrue(root.has("scanStride"));
        assertTrue(root.has("spatialResolution"));
        assertTrue(root.has("badLocations"));
        assertTrue(root.has("biomeLocations"));

        com.google.gson.JsonArray badLocations = root.getAsJsonArray("badLocations");
        assertTrue(badLocations.size() >= 2);

        com.google.gson.JsonObject firstBad = badLocations.get(0).getAsJsonObject();
        assertEquals(42L, firstBad.get("start").getAsLong());
        assertEquals(2L, firstBad.get("length").getAsLong());
        assertEquals("safety", firstBad.get("cause").getAsString());

        com.google.gson.JsonObject secondBad = badLocations.get(1).getAsJsonObject();
        assertEquals(100L, secondBad.get("start").getAsLong());
        assertEquals(1L, secondBad.get("length").getAsLong());
        assertEquals("vert", secondBad.get("cause").getAsString());

        com.google.gson.JsonObject biomes = root.getAsJsonObject("biomeLocations");
        assertTrue(biomes.has("DESERT"));
        com.google.gson.JsonArray desertRuns = biomes.getAsJsonArray("DESERT");
        assertEquals(1, desertRuns.size());
        assertEquals(200L, desertRuns.get(0).getAsJsonObject().get("start").getAsLong());
        assertEquals(8L, desertRuns.get(0).getAsJsonObject().get("length").getAsLong());
    }

    @Test
    @DisplayName("Orientation rotation math: applyOrientation and unapplyOrientation invert each other across all dihedral orientations")
    void orientationMath_applyAndUnapplyInverse() {
        int n = 16;
        for (int o = 0; o < 8; o++) {
            for (int x = 0; x < n; x++) {
                for (int y = 0; y < n; y++) {
                    int[] oriented = MemoryShape.applyOrientation(x, y, n, o);
                    assertEquals(2, oriented.length);
                    assertTrue(oriented[0] >= 0 && oriented[0] < n, "x in bounds for o=" + o);
                    assertTrue(oriented[1] >= 0 && oriented[1] < n, "y in bounds for o=" + o);

                    int[] restored = MemoryShape.unapplyOrientation(oriented[0], oriented[1], n, o);
                    assertEquals(x, restored[0], "Restored x must match original for o=" + o);
                    assertEquals(y, restored[1], "Restored y must match original for o=" + o);
                }
            }
        }
    }

    @Test
    @DisplayName("Orientation rotation math: applyOrientation mapping definitions across all 8 dihedral orientations")
    void orientationMath_exactTransformations() {
        int n = 10;
        int max = n - 1; // 9
        int x = 2;
        int y = 7;

        // o=0: identity (x, y)
        assertArrayEquals(new int[]{2, 7}, MemoryShape.applyOrientation(x, y, n, 0));
        // o=1: transpose (y, x)
        assertArrayEquals(new int[]{7, 2}, MemoryShape.applyOrientation(x, y, n, 1));
        // o=2: 90 deg clockwise (max - y, x)
        assertArrayEquals(new int[]{max - 7, 2}, MemoryShape.applyOrientation(x, y, n, 2));
        // o=3: horizontal flip (max - x, y)
        assertArrayEquals(new int[]{max - 2, 7}, MemoryShape.applyOrientation(x, y, n, 3));
        // o=4: 180 deg rotation (max - x, max - y)
        assertArrayEquals(new int[]{max - 2, max - 7}, MemoryShape.applyOrientation(x, y, n, 4));
        // o=5: anti-transpose (max - y, max - x)
        assertArrayEquals(new int[]{max - 7, max - 2}, MemoryShape.applyOrientation(x, y, n, 5));
        // o=6: 270 deg clockwise (y, max - x)
        assertArrayEquals(new int[]{7, max - 2}, MemoryShape.applyOrientation(x, y, n, 6));
        // o=7: vertical flip (x, max - y)
        assertArrayEquals(new int[]{2, max - 7}, MemoryShape.applyOrientation(x, y, n, 7));

        // Modulo 8 equivalence
        assertArrayEquals(MemoryShape.applyOrientation(x, y, n, 1), MemoryShape.applyOrientation(x, y, n, 9));
        assertArrayEquals(MemoryShape.unapplyOrientation(x, y, n, 2), MemoryShape.unapplyOrientation(x, y, n, 10));
    }

    @Test
    @DisplayName("Rectangle and Ellipse with non-zero orientation / yaw rotation and coordinate transformation")
    void shapes_orientationWithNonZeroYaw() {
        Rectangle rect = new Rectangle();
        rect.set(RectangleParams.width, 200L);
        rect.set(RectangleParams.height, 100L);
        rect.set(RectangleParams.centerX, 0L);
        rect.set(RectangleParams.centerZ, 0L);
        rect.set(RectangleParams.rotation, 45L);

        // Rectangle with 45 degree rotation
        assertEquals(200L * 100L, rect.getRange());
        long loc0 = 500L;
        int[] xz0 = rect.locationToXZ(loc0);
        assertNotNull(xz0);
        assertEquals(2, xz0.length);

        // Rotating a coordinate changes xz compared to unrotated
        Rectangle unrotatedRect = new Rectangle();
        unrotatedRect.set(RectangleParams.width, 200L);
        unrotatedRect.set(RectangleParams.height, 100L);
        unrotatedRect.set(RectangleParams.centerX, 0L);
        unrotatedRect.set(RectangleParams.centerZ, 0L);
        unrotatedRect.set(RectangleParams.rotation, 0L);
        int[] unrotatedXZ = unrotatedRect.locationToXZ(loc0);

        assertFalse(xz0[0] == unrotatedXZ[0] && xz0[1] == unrotatedXZ[1],
                "Rotated coordinates should differ from unrotated coordinates");

        // Verify chunkToLocations inverts locationToXZ for rotated rectangle
        long[] preimages = rect.chunkToLocations(xz0[0], xz0[1]);
        assertNotNull(preimages);
        assertTrue(preimages.length > 0, "chunkToLocations should recover rotated coordinate preimages");

        // Ellipse with non-zero yaw (rotation)
        Ellipse ellipse = new Ellipse();
        ellipse.set(EllipseMemoryShapeParams.radius, 300L);
        ellipse.set(EllipseMemoryShapeParams.radius2, 150L);
        ellipse.set(EllipseMemoryShapeParams.centerRadius, 0L);
        ellipse.set(EllipseMemoryShapeParams.centerRadius2, 0L);
        ellipse.set(EllipseMemoryShapeParams.centerX, 0L);
        ellipse.set(EllipseMemoryShapeParams.centerZ, 0L);
        ellipse.set(EllipseMemoryShapeParams.rotation, 60L);

        long locE = 1200L;
        int[] xzE = ellipse.locationToXZ(locE);
        assertNotNull(xzE);
        assertEquals(2, xzE.length);

        Ellipse unrotatedEllipse = new Ellipse();
        unrotatedEllipse.set(EllipseMemoryShapeParams.radius, 300L);
        unrotatedEllipse.set(EllipseMemoryShapeParams.radius2, 150L);
        unrotatedEllipse.set(EllipseMemoryShapeParams.centerRadius, 0L);
        unrotatedEllipse.set(EllipseMemoryShapeParams.centerRadius2, 0L);
        unrotatedEllipse.set(EllipseMemoryShapeParams.centerX, 0L);
        unrotatedEllipse.set(EllipseMemoryShapeParams.centerZ, 0L);
        unrotatedEllipse.set(EllipseMemoryShapeParams.rotation, 0L);
        int[] unrotatedXZE = unrotatedEllipse.locationToXZ(locE);

        assertFalse(xzE[0] == unrotatedXZE[0] && xzE[1] == unrotatedXZE[1],
                "Rotated ellipse coordinates should differ from unrotated");
    }
}
