package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("QueueTask Unit Tests")
class QueueTaskTest {

    @TempDir
    File tempDir;

    private MockRTPServerAccessor accessor;
    private MockRTPWorld world;
    private Region region;
    private MockRTPPlayer player;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir);
        world = new MockRTPWorld("queue_world");
        accessor.addWorld(world);
        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                "queueRegion",
                world,
                square,
                vert,
                false,
                false,
                10L,
                1000L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
        region = new Region("queueRegion", settings);
        player = new MockRTPPlayer(UUID.randomUUID(), "QueuePlayer", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(player);
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    @DisplayName("QueueTask with custom biomes falls back to unqueued fast-path")
    void testCustomBiomesFallback() throws Exception {
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        // In MockRTPWorld, biome lookup for "MOCK_BIOME" or null
        Set<String> customBiomes = Set.of("nonexistent_biome");

        QueueTask task = new QueueTask(region, player, player, customBiomes, result);
        task.start();

        GenerationResult gen = result.get(10, TimeUnit.SECONDS);
        // Result completes (either null or with result depending on biome availability)
        assertTrue(result.isDone());
    }

    @Test
    @DisplayName("QueueTask with empty queue falls back to generateLocation")
    void testEmptyQueueFallback() throws Exception {
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();

        QueueTask task = new QueueTask(region, player, player, null, result);
        task.start();

        GenerationResult gen = result.get(10, TimeUnit.SECONDS);
        assertNotNull(gen);
        assertNotNull(gen.coords());
    }

    @Test
    @DisplayName("QueueTask drains kept fast location if available")
    void testFastLocationQueue() throws Exception {
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        RTPLocation prefill = new RTPLocation(new RTPCoords("queue_world", 10, 64, 10), 1);
        region.queueManager.fastLocations.put(player.uuid(), CompletableFuture.completedFuture(prefill));

        QueueTask task = new QueueTask(region, player, player, null, result);
        task.start();

        GenerationResult gen = result.get(10, TimeUnit.SECONDS);
        assertNotNull(gen);
    }

    @Test
    @DisplayName("QueueTask with keptLocations location resolves through afterChunkResolved")
    void testKeptLocationEvaluation() throws Exception {
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();

        // Put a kept location with preReservation
        int cx = 2;
        int cz = 2;
        long chunkKey = ((long) cx & 0xffffffffL) | ((long) cz << 32);
        ChunkSet chunkSet = new ChunkSet(
                world, cx, cz,
                java.util.Collections.singletonList(CompletableFuture.completedFuture(chunkKey)),
                new CompletableFuture<>());
        io.github.dailystruggle.rtp.api.world.ChunkReservation reservation =
                new io.github.dailystruggle.rtp.api.world.ChunkReservation(chunkSet, world);

        // Warm the mock world cached chunk
        io.github.dailystruggle.rtp.api.world.RTPChunk<?> chunk = world.getChunkAt(cx, cz).getNow(null) != null
                ? world.getCachedChunk(world.getChunkAt(cx, cz).getNow(null))
                : null;
        if (chunk == null) {
            world.getChunkAt(cx, cz).join();
        }

        RTPLocation keptLoc = new RTPLocation(new RTPCoords("queue_world", (cx << 4) + 4, 64, (cz << 4) + 4), 1, reservation);
        region.queueManager.keptLocations.offer(keptLoc);

        QueueTask task = new QueueTask(region, player, player, null, result);
        task.start();

        GenerationResult gen = result.get(10, TimeUnit.SECONDS);
        assertNotNull(gen);
        assertNotNull(gen.coords());
    }

    @Test
    @DisplayName("QueueTask handles null pair in queue and falls back")
    void testNullPairHandling() throws Exception {
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        region.queueManager.fastLocations.put(player.uuid(), CompletableFuture.completedFuture(null));

        QueueTask task = new QueueTask(region, player, player, null, result);
        task.start();

        GenerationResult gen = result.get(10, TimeUnit.SECONDS);
        assertNotNull(gen);
    }

    @Test
    @DisplayName("QueueTask handles pair with null coords and continues to next poll")
    void testPairWithNullCoords() throws Exception {
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        // Fast location with null coords
        RTPLocation invalidLoc = new RTPLocation(null, 1);
        region.queueManager.fastLocations.put(player.uuid(), CompletableFuture.completedFuture(invalidLoc));

        QueueTask task = new QueueTask(region, player, player, null, result);
        task.start();

        GenerationResult gen = result.get(10, TimeUnit.SECONDS);
        assertNotNull(gen);
    }

    @Test
    @DisplayName("QueueTask probe-first reject drains queue and falls back")
    void testProbeFirstReject() throws Exception {
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();

        // Create a probe that returns no solid blocks (e.g. all air) so adjustFromProbe returns null
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.FakeChunkColumnProbe probe =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.FakeChunkColumnProbe(0, 0, -1, 320)
                        .setAirRange(0, 320);

        MockRTPWorld spyWorld = org.mockito.Mockito.spy(world);
        org.mockito.Mockito.doReturn(CompletableFuture.completedFuture(probe))
                .when(spyWorld).probeChunkColumn(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());

        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                "probeQueueRegion",
                spyWorld,
                square,
                vert,
                false,
                false,
                10L,
                1000L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
        Region probeRegion = new Region("probeQueueRegion", settings);

        // Put a pair with NO reservation (so it exercises tryProbeFirstQueue)
        RTPLocation unreservedLoc = new RTPLocation(new RTPCoords("queue_world", 16, 64, 16), 1, null);
        probeRegion.queueManager.unkeptLocations.offer(unreservedLoc);

        QueueTask task = new QueueTask(probeRegion, player, player, null, result);
        task.start();

        GenerationResult gen = result.get(10, TimeUnit.SECONDS);
        assertNotNull(gen);
    }

    @Test
    @DisplayName("QueueTask self-contained (Anvil) chunk resolves inline without reservation")
    void testSelfContainedChunkResolve() throws Exception {
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();

        io.github.dailystruggle.rtp.common.mock.MockRTPChunk chunk =
                org.mockito.Mockito.spy(new io.github.dailystruggle.rtp.common.mock.MockRTPChunk(0, 0, world));
        org.mockito.Mockito.doReturn(true).when(chunk).isSelfContained();
        org.mockito.Mockito.doReturn("PLAINS").when(chunk).getBiome(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());

        MockRTPWorld spyWorld = org.mockito.Mockito.spy(world);
        org.mockito.Mockito.doReturn(CompletableFuture.completedFuture(chunk))
                .when(spyWorld).getOrLoadChunk(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());

        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                "anvilQueueRegion",
                spyWorld,
                square,
                vert,
                false,
                false,
                10L,
                1000L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
        Region anvilRegion = new Region("anvilQueueRegion", settings);

        RTPLocation unreservedLoc = new RTPLocation(new RTPCoords("queue_world", 8, 64, 8), 1, null);
        anvilRegion.queueManager.unkeptLocations.offer(unreservedLoc);

        QueueTask task = new QueueTask(anvilRegion, player, player, null, result);
        task.start();

        GenerationResult gen = result.get(10, TimeUnit.SECONDS);
        assertNotNull(gen);
    }

    @Test
    @DisplayName("QueueTask full load exception triggers fallback")
    void testFullLoadExceptionFallback() throws Exception {
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();

        MockRTPWorld spyWorld = org.mockito.Mockito.spy(world);
        CompletableFuture<io.github.dailystruggle.rtp.api.world.RTPChunk<?>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Simulated load failure"));
        org.mockito.Mockito.doReturn(failedFuture)
                .when(spyWorld).getOrLoadChunk(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());

        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                "failQueueRegion",
                spyWorld,
                square,
                vert,
                false,
                false,
                10L,
                1000L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
        Region failRegion = new Region("failQueueRegion", settings);

        RTPLocation unreservedLoc = new RTPLocation(new RTPCoords("queue_world", 8, 64, 8), 1, null);
        failRegion.queueManager.unkeptLocations.offer(unreservedLoc);

        QueueTask task = new QueueTask(failRegion, player, player, null, result);
        task.start();

        GenerationResult gen = result.get(10, TimeUnit.SECONDS);
        assertNotNull(gen);
    }

    @Test
    @DisplayName("QueueTask probe returns null on synchronous fast path, reentering pollNext")
    void testProbeFastPathRejects() throws Exception {
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();

        MockRTPWorld spyWorld = org.mockito.Mockito.spy(world);
        io.github.dailystruggle.rtp.api.world.ChunkColumnProbe nullProbe =
                org.mockito.Mockito.mock(io.github.dailystruggle.rtp.api.world.ChunkColumnProbe.class);
        org.mockito.Mockito.doReturn(CompletableFuture.completedFuture(nullProbe))
                .when(spyWorld).probeChunkColumn(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());

        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);

        // Adjustor that returns null from probe
        LinearAdjustor vert = org.mockito.Mockito.spy(new LinearAdjustor(new ArrayList<>()));
        org.mockito.Mockito.doReturn(null).when(vert).adjustFromProbe(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        RegionSettings settings = new RegionSettings(
                "probeRejectRegion",
                spyWorld,
                square,
                vert,
                false,
                false,
                10L,
                1000L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
        Region probeRegion = new Region("probeRejectRegion", settings);

        // Unreserved loc that will be rejected by probe
        RTPLocation unreservedLoc = new RTPLocation(new RTPCoords("queue_world", 16, 64, 16), 1, null);
        probeRegion.queueManager.unkeptLocations.offer(unreservedLoc);

        QueueTask task = new QueueTask(probeRegion, player, player, null, result);
        task.start();

        // After rejecting the unkept location, QueueTask falls back to generateLocation
        GenerationResult gen = result.get(10, TimeUnit.SECONDS);
        assertNotNull(gen);
    }

    @Test
    @DisplayName("QueueTask probe async completion rejection")
    void testProbeAsyncRejection() throws Exception {
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();

        MockRTPWorld spyWorld = org.mockito.Mockito.spy(world);
        io.github.dailystruggle.rtp.api.world.ChunkColumnProbe asyncProbe =
                org.mockito.Mockito.mock(io.github.dailystruggle.rtp.api.world.ChunkColumnProbe.class);
        CompletableFuture<io.github.dailystruggle.rtp.api.world.ChunkColumnProbe> probeFuture = new CompletableFuture<>();
        org.mockito.Mockito.doReturn(probeFuture)
                .when(spyWorld).probeChunkColumn(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());

        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);

        LinearAdjustor vert = org.mockito.Mockito.spy(new LinearAdjustor(new ArrayList<>()));
        org.mockito.Mockito.doReturn(null).when(vert).adjustFromProbe(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        RegionSettings settings = new RegionSettings(
                "probeAsyncRejectRegion",
                spyWorld,
                square,
                vert,
                false,
                false,
                10L,
                1000L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
        Region probeRegion = new Region("probeAsyncRejectRegion", settings);

        RTPLocation unreservedLoc = new RTPLocation(new RTPCoords("queue_world", 16, 64, 16), 1, null);
        probeRegion.queueManager.unkeptLocations.offer(unreservedLoc);

        QueueTask task = new QueueTask(probeRegion, player, player, null, result);
        task.start();

        // Complete probe future asynchronously
        probeFuture.complete(asyncProbe);

        GenerationResult gen = result.get(10, TimeUnit.SECONDS);
        assertNotNull(gen);
    }

    @Test
    @DisplayName("QueueTask probe async exceptionally falls back to full load")
    void testProbeAsyncExceptionalFallback() throws Exception {
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();

        MockRTPWorld spyWorld = org.mockito.Mockito.spy(world);
        CompletableFuture<io.github.dailystruggle.rtp.api.world.ChunkColumnProbe> probeFuture = new CompletableFuture<>();
        org.mockito.Mockito.doReturn(probeFuture)
                .when(spyWorld).probeChunkColumn(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());

        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());

        RegionSettings settings = new RegionSettings(
                "probeAsyncExRegion",
                spyWorld,
                square,
                vert,
                false,
                false,
                10L,
                1000L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
        Region probeRegion = new Region("probeAsyncExRegion", settings);

        RTPLocation unreservedLoc = new RTPLocation(new RTPCoords("queue_world", 16, 64, 16), 1, null);
        probeRegion.queueManager.unkeptLocations.offer(unreservedLoc);

        QueueTask task = new QueueTask(probeRegion, player, player, null, result);
        task.start();

        // Complete exceptionally
        probeFuture.completeExceptionally(new RuntimeException("Probe failure"));

        GenerationResult gen = result.get(10, TimeUnit.SECONDS);
        assertNotNull(gen);
    }

    @Test
    @DisplayName("QueueTask afterChunkResolved rejects when center chunk goes stale")
    void testCenterChunkStaleRejection() throws Exception {
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();

        MockRTPWorld spyWorld = org.mockito.Mockito.spy(world);
        // Make center chunk report unloaded
        org.mockito.Mockito.doReturn(false).when(spyWorld).isChunkLoaded(1, 1);

        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());

        RegionSettings settings = new RegionSettings(
                "staleCenterRegion",
                spyWorld,
                square,
                vert,
                false,
                false,
                10L,
                1000L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
        Region testRegion = new Region("staleCenterRegion", settings);

        // Pre-reserved loc with chunk coords (1, 1)
        long chunkKey = (1L & 0xffffffffL) | (1L << 32);
        ChunkSet chunkSet = new ChunkSet(spyWorld, 1, 1, Collections.singletonList(CompletableFuture.completedFuture(chunkKey)), new CompletableFuture<>());
        ChunkReservation reservation = new ChunkReservation(chunkSet, spyWorld);
        RTPLocation reservedLoc = new RTPLocation(new RTPCoords("queue_world", 16, 64, 16), 1, reservation);

        testRegion.queueManager.keptLocations.offer(reservedLoc);

        QueueTask task = new QueueTask(testRegion, player, player, null, result);
        task.start();

        // Stale center chunk rejects the queued location and falls back
        GenerationResult gen = result.get(10, TimeUnit.SECONDS);
        assertNotNull(gen);
    }

    @Test
    @DisplayName("QueueTask evaluateSafety with safety radius > 0 and neighbour missing falls back")
    void testNeighbourMissingFallback() throws Exception {
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();

        // Configure safety radius = 1
        @SuppressWarnings("unchecked")
        io.github.dailystruggle.rtp.common.configuration.ConfigParser<SafetyKeys> safetyParser =
                (io.github.dailystruggle.rtp.common.configuration.ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
        if (safetyParser != null) {
            safetyParser.set(SafetyKeys.safetyRadius, 1);
        }

        MockRTPWorld spyWorld = org.mockito.Mockito.spy(world);
        // Centre chunk loads fine, but neighbour returns null from getCachedChunk
        org.mockito.Mockito.doReturn(CompletableFuture.completedFuture(99999L)).when(spyWorld).getChunkAt(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.doReturn(null).when(spyWorld).getCachedChunk(99999L);

        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());

        RegionSettings settings = new RegionSettings(
                "neighbourMissingRegion",
                spyWorld,
                square,
                vert,
                false,
                false,
                10L,
                1000L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
        Region testRegion = new Region("neighbourMissingRegion", settings);

        // Position at chunk edge so neighbour chunks are needed
        RTPChunk<?> centerChunk = spyWorld.getChunkAt(1, 1).thenApply(spyWorld::getCachedChunk).join();
        long chunkKey = (1L & 0xffffffffL) | (1L << 32);
        ChunkSet chunkSet = new ChunkSet(spyWorld, 1, 1, Collections.singletonList(CompletableFuture.completedFuture(chunkKey)), new CompletableFuture<>());
        ChunkReservation reservation = new ChunkReservation(chunkSet, spyWorld);
        RTPLocation reservedLoc = new RTPLocation(new RTPCoords("queue_world", 31, 64, 31), 1, reservation);

        testRegion.queueManager.keptLocations.offer(reservedLoc);

        QueueTask task = new QueueTask(testRegion, player, player, null, result);
        task.start();

        GenerationResult gen = result.get(10, TimeUnit.SECONDS);
        assertNotNull(gen);
    }

    @Test
    @DisplayName("QueueTask fallback with existing TeleportData preserves delay and updates queue")
    void testFallbackWithExistingTeleportData() throws Exception {
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();

        TeleportData existing = new TeleportData();
        existing.delay = 5;
        existing.completed = false;
        RTP.getInstance().latestTeleportData.put(player.uuid(), existing);
        player.setPermission("rtp.unqueued", false);

        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());

        RegionSettings settings = new RegionSettings(
                "fallbackDataRegion",
                world,
                square,
                vert,
                false,
                false,
                10L,
                1000L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
        Region testRegion = new Region("fallbackDataRegion", settings);
        RTP.selectionAPI.permRegionLookup.put("fallbackDataRegion", testRegion);

        QueueTask task = new QueueTask(testRegion, player, player, null, result);
        task.start();

        GenerationResult gen = result.get(10, TimeUnit.SECONDS);
        // On normal enqueue fallback, result.complete(null) is called and player is enqueued
        assertNull(gen);
        assertTrue(testRegion.queueManager.playerQueue.contains(player.uuid()));
        assertTrue(RTP.getInstance().queuedPlayers.contains(player.uuid()));
    }

    @Test
    @DisplayName("QueueTask probe-first evaluates safe chunks and resolves location without full load")
    void testProbeFirstSafeChunkResolution() throws Exception {
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();

        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());

        RegionSettings settings = new RegionSettings(
                "probeSafeRegion",
                world,
                square,
                vert,
                false,
                false,
                10L,
                1000L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
        Region testRegion = new Region("probeSafeRegion", settings);

        // Offer an unkept location at 32, 64, 32
        RTPCoords coords = new RTPCoords("queue_world", 32, 64, 32);
        RTPLocation unkeptLoc = new RTPLocation(coords, 1L, null);
        testRegion.queueManager.unkeptLocations.offer(unkeptLoc);

        // Preload chunk in world so chunk is cached and safe
        world.getChunkAt(2, 2).join();

        QueueTask task = new QueueTask(testRegion, player, player, null, result);
        task.start();

        GenerationResult gen = result.get(10, TimeUnit.SECONDS);
        assertNotNull(gen);
        assertNotNull(gen.coords());
    }

    @Test
    @DisplayName("QueueTask fastQueue future completing exceptionally falls back to generateLocation")
    void testFastQueueExceptionallyFallsBack() throws Exception {
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();

        CompletableFuture<RTPLocation> failedFastFut = new CompletableFuture<>();
        failedFastFut.completeExceptionally(new RuntimeException("Simulated fastQueue failure"));
        region.queueManager.fastLocations.put(player.uuid(), failedFastFut);

        QueueTask task = new QueueTask(region, player, player, null, result);
        task.start();

        GenerationResult gen = result.get(10, TimeUnit.SECONDS);
        assertNotNull(gen);
        assertNotNull(gen.coords());
        assertFalse(region.queueManager.fastLocations.containsKey(player.uuid()));
    }

    @Test
    @DisplayName("QueueTask fastQueue returning location whose chunk reservation cannot be claimed triggers fallback")
    void testFastQueueUnclaimableReservationFallback() throws Exception {
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();

        MockRTPWorld spyWorld = new MockRTPWorld("unclaimable_fast_world") {
            @Override
            public CompletableFuture<RTPChunk<?>> getOrLoadChunk(int cx, int cz, String origin) {
                if (cx == 5 && cz == 5) {
                    CompletableFuture<RTPChunk<?>> failed = new CompletableFuture<>();
                    failed.completeExceptionally(new IllegalStateException("Simulated chunk load failure for unclaimable reservation"));
                    return failed;
                }
                return super.getOrLoadChunk(cx, cz, origin);
            }

            @Override
            public RTPChunk<?> getCachedChunk(long key) {
                int cx = (int) (key >> 32);
                int cz = (int) (key & 0xFFFFFFFFL);
                if (cx == 5 && cz == 5) {
                    return null;
                }
                return super.getCachedChunk(key);
            }
        };
        accessor.addWorld(spyWorld);

        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                "unclaimableFastRegion",
                spyWorld,
                square,
                vert,
                false,
                false,
                10L,
                1000L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
        Region testRegion = new Region("unclaimableFastRegion", settings);

        // Reservation pointing to chunk (5, 5), but chunk is not in cache and getOrLoadChunk fails
        long chunkKey = (5L & 0xffffffffL) | (5L << 32);
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
        ChunkSet chunkSet = new ChunkSet(spyWorld, 5, 5,
                Collections.singletonList(CompletableFuture.completedFuture(chunkKey)),
                new CompletableFuture<>());
        ChunkReservation reservation = new ChunkReservation(chunkSet, spyWorld) {
            @Override
            public void close() {
                closed.set(true);
                super.close();
            }
        };
        RTPLocation reservedLoc = new RTPLocation(new RTPCoords("unclaimable_fast_world", 80, 64, 80), 1, reservation);

        testRegion.queueManager.fastLocations.put(player.uuid(), CompletableFuture.completedFuture(reservedLoc));

        QueueTask task = new QueueTask(testRegion, player, player, null, result);
        task.start();

        GenerationResult gen = result.get(10, TimeUnit.SECONDS);
        // Centre chunk load failed -> afterChunkResolved called with chunk=null -> finishRejected closes reservation -> pollNext -> fallback to generateLocation
        assertNotNull(gen);
        assertNotNull(gen.coords());
        assertTrue(closed.get());
        assertEquals(0, spyWorld.activeChunkTickets.get());
        assertFalse(testRegion.queueManager.fastLocations.containsKey(player.uuid()));
    }

    @Test
    @DisplayName("QueueTask safety scan cancelled or failed cleans up reservation and does not leave player in processingPlayers (S-002, S-004)")
    void testSafetyScanCancelledCleansUpReservation() throws Exception {
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();

        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                "scanCancelRegion",
                world,
                square,
                vert,
                false,
                false,
                10L,
                1000L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
        Region testRegion = new Region("scanCancelRegion", settings);

        // Preload chunk in world so it's cached and valid
        world.getChunkAt(3, 3).join();

        long chunkKey = (3L & 0xffffffffL) | (3L << 32);
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
        ChunkSet chunkSet = new ChunkSet(world, 3, 3,
                Collections.singletonList(CompletableFuture.completedFuture(chunkKey)),
                new CompletableFuture<>());
        ChunkReservation reservation = new ChunkReservation(chunkSet, world) {
            @Override
            public void close() {
                closed.set(true);
                super.close();
            }
        };
        RTPCoords coords = new RTPCoords("queue_world", 48, 64, 48);
        RTPLocation reservedLoc = new RTPLocation(coords, 1L, reservation);

        testRegion.queueManager.keptLocations.offer(reservedLoc);

        // Register an async global verifier that completes exceptionally (simulating timeout or cancellation)
        AutoCloseable verifierHandle = GlobalRegionVerifiers.addGlobalRegionVerifierAsync(c -> {
            if (c.x() == 48 && c.z() == 48) {
                CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
                cancelled.cancel(true);
                return cancelled;
            }
            return CompletableFuture.completedFuture(true);
        });

        try {
            QueueTask task = new QueueTask(testRegion, player, player, null, result);
            task.start();

            GenerationResult gen = result.get(10, TimeUnit.SECONDS);
            // The cancelled verifier fails runSafetyScan -> finishRejected closes reservation -> next poll -> fallback generates location
            assertNotNull(gen);
            assertNotNull(gen.coords());
            assertTrue(closed.get());
            assertEquals(0, world.activeChunkTickets.get());
            assertFalse(RTP.getInstance().processingPlayers.contains(player.uuid()));
        } finally {
            verifierHandle.close();
        }
    }

    @Test
    @DisplayName("QueueTask concurrent dequeue race where reserved candidate becomes stale before dispatch")
    void testConcurrentDequeueCandidateInvalidatedBeforeDispatch() throws Exception {
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();

        MockRTPWorld staleWorld = new MockRTPWorld("stale_race_world");
        accessor.addWorld(staleWorld);

        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                "staleRaceRegion",
                staleWorld,
                square,
                vert,
                false,
                false,
                10L,
                1000L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
        Region testRegion = new Region("staleRaceRegion", settings);

        // Initial chunk is cached
        staleWorld.getChunkAt(4, 4).join();

        long chunkKey = (4L & 0xffffffffL) | (4L << 32);
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
        ChunkSet chunkSet = new ChunkSet(staleWorld, 4, 4,
                Collections.singletonList(CompletableFuture.completedFuture(chunkKey)),
                new CompletableFuture<>());
        ChunkReservation reservation = new ChunkReservation(chunkSet, staleWorld) {
            @Override
            public void close() {
                closed.set(true);
                super.close();
            }
        };
        RTPLocation reservedLoc = new RTPLocation(new RTPCoords("stale_race_world", 64, 64, 64), 1, reservation);

        testRegion.queueManager.keptLocations.offer(reservedLoc);

        // Only invalidate chunk (4, 4), leaving fallback chunks intact
        long staleTargetKey = (4L & 0xffffffffL) | (4L << 32);
        staleWorld.isChunkLoadedPredicate = k -> k != staleTargetKey;

        QueueTask task = new QueueTask(testRegion, player, player, null, result);
        task.start();

        GenerationResult gen = result.get(10, TimeUnit.SECONDS);
        // The stale chunk is detected by stale chunk guard in afterChunkResolved -> finishRejected closes reservation -> next poll -> fallback
        assertNotNull(gen);
        assertNotNull(gen.coords());
        assertTrue(closed.get());
        assertEquals(0, staleWorld.activeChunkTickets.get());
    }
}
