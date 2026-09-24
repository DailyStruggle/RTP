package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.group.GroupPlacementRequest;
import io.github.dailystruggle.rtp.api.group.GroupPlacementResult;
import io.github.dailystruggle.rtp.api.group.GroupProfileSpec;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("REQ-RTP-S-004 / S-005: GroupPlacementDispatcher Unit Tests")
class GroupPlacementDispatcherTest {

    @TempDir
    File tempDir;

    private GroupPlacementDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        dispatcher = new GroupPlacementDispatcher();
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = null;
        RTP.scheduler = null;
    }

    @Test
    @DisplayName("Returns failure when request is null")
    void testNullRequest() throws Exception {
        CompletableFuture<GroupPlacementResult> future = dispatcher.place(null);
        assertNotNull(future);
        GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);
        assertFalse(result.isSuccess());
        assertEquals(GroupPlacementResult.Reason.ERROR, result.reason());
    }

    @Test
    @DisplayName("Returns failure when region name does not exist")
    void testUnknownRegion() throws Exception {
        GroupProfileSpec spec = GroupProfileSpec.of("square", 16, 2, 5, 4);
        GroupPlacementRequest request = GroupPlacementRequest.of(
                "nonexistent_region",
                spec,
                new java.util.ArrayList<>(List.of(UUID.randomUUID()))
        );

        CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
        GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);
        assertFalse(result.isSuccess());
        assertEquals(GroupPlacementResult.Reason.INVALID_REGION, result.reason());
    }

    @Test
    @DisplayName("Returns failure when group size exceeds maxGroupSize")
    void testExceededMaxGroupSize() throws Exception {
        Region mockRegion = mock(Region.class);
        RTP.selectionAPI.permRegionLookup.put("capacity_test_region", mockRegion);

        try {
            GroupProfileSpec spec = GroupProfileSpec.of("square", 16, 2, 5, 2);
            GroupPlacementRequest request = GroupPlacementRequest.of(
                    "capacity_test_region",
                    spec,
                    new java.util.ArrayList<>(List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
            );

            CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
            GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);
            assertFalse(result.isSuccess());
            assertEquals(GroupPlacementResult.Reason.EXCEEDED_MAX_GROUP_SIZE, result.reason());
        } finally {
            RTP.selectionAPI.permRegionLookup.remove("capacity_test_region");
        }
    }

    @Test
    @DisplayName("Returns NO_ANCHOR when region returns null anchor result")
    void testNullAnchorResult() throws Exception {
        Region mockRegion = mock(Region.class);
        when(mockRegion.getLocation(anySet())).thenReturn(CompletableFuture.completedFuture(null));
        RTP.selectionAPI.permRegionLookup.put("mock_region", mockRegion);

        try {
            GroupProfileSpec spec = GroupProfileSpec.of("square", 16, 2, 5, 4);
            GroupPlacementRequest request = GroupPlacementRequest.of(
                    "mock_region",
                    spec,
                    new java.util.ArrayList<>(List.of(UUID.randomUUID()))
            );

            CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
            GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);
            assertFalse(result.isSuccess());
            assertEquals(GroupPlacementResult.Reason.NO_ANCHOR, result.reason());
        } finally {
            RTP.selectionAPI.permRegionLookup.remove("mock_region");
        }
    }

    @Test
    @DisplayName("Returns INVALID_REGION and releases reservation when region world is null")
    void testRegionWorldNull() throws Exception {
        Region mockRegion = mock(Region.class);
        ChunkReservation ticket = mock(ChunkReservation.class);
        GenerationResult genResult = new GenerationResult(
                new RTPCoords("world", 10, 64, 10),
                1,
                null,
                ticket
        );
        when(mockRegion.getLocation(anySet())).thenReturn(CompletableFuture.completedFuture(genResult));
        when(mockRegion.getWorld()).thenReturn(null);
        RTP.selectionAPI.permRegionLookup.put("no_world_region", mockRegion);

        try {
            GroupProfileSpec spec = GroupProfileSpec.of("square", 16, 2, 5, 4);
            GroupPlacementRequest request = GroupPlacementRequest.of(
                    "no_world_region",
                    spec,
                    new java.util.ArrayList<>(List.of(UUID.randomUUID()))
            );

            CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
            GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);
            assertFalse(result.isSuccess());
            assertEquals(GroupPlacementResult.Reason.INVALID_REGION, result.reason());
            verify(ticket).close();
        } finally {
            RTP.selectionAPI.permRegionLookup.remove("no_world_region");
        }
    }

    @Test
    @DisplayName("Returns CANCELLED when all participants are offline at dispatch")
    void testAllParticipantsOffline() throws Exception {
        UUID onlineUuid = UUID.randomUUID();
        Region mockRegion = mock(Region.class);
        ChunkReservation ticket = mock(ChunkReservation.class);
        GenerationResult genResult = new GenerationResult(
                new RTPCoords("world", 0, 64, 0),
                1,
                null,
                ticket
        );
        RTPWorld<?> world = RTP.serverAccessor.getRTPWorld("world");
        org.mockito.Mockito.doReturn(world).when(mockRegion).getWorld();
        when(mockRegion.getLocation(anySet())).thenReturn(CompletableFuture.completedFuture(genResult));
        when(mockRegion.candidateValidator()).thenReturn((x, z) -> new RTPLocation(new RTPCoords("world", x, 64, z), 1));
        RTP.selectionAPI.permRegionLookup.put("offline_test_region", mockRegion);

        try {
            GroupProfileSpec spec = GroupProfileSpec.of("square", 16, 2, 5, 4);
            GroupPlacementRequest request = GroupPlacementRequest.of(
                    "offline_test_region",
                    spec,
                    new java.util.ArrayList<>(List.of(onlineUuid))
            );

            CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
            GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);
            assertFalse(result.isSuccess());
            assertEquals(GroupPlacementResult.Reason.CANCELLED, result.reason());
        } finally {
            RTP.selectionAPI.permRegionLookup.remove("offline_test_region");
        }
    }

    @Test
    @DisplayName("Returns INSUFFICIENT_SAFE_SLOTS when subspace fails to allocate enough slots")
    void testSubspaceInsufficientSlots() throws Exception {
        UUID onlineUuid = UUID.randomUUID();
        Region mockRegion = mock(Region.class);
        ChunkReservation ticket = mock(ChunkReservation.class);
        GenerationResult genResult = new GenerationResult(
                new RTPCoords("world", 0, 64, 0),
                1,
                null,
                ticket
        );
        RTPWorld<?> world = RTP.serverAccessor.getRTPWorld("world");
        org.mockito.Mockito.doReturn(world).when(mockRegion).getWorld();
        when(mockRegion.getLocation(anySet())).thenReturn(CompletableFuture.completedFuture(genResult));
        // validator returns null everywhere -> 0 slots
        when(mockRegion.candidateValidator()).thenReturn((x, z) -> null);
        RTP.selectionAPI.permRegionLookup.put("insufficient_test_region", mockRegion);

        try {
            GroupProfileSpec spec = GroupProfileSpec.of("square", 16, 2, 5, 4);
            GroupPlacementRequest request = GroupPlacementRequest.of(
                    "insufficient_test_region",
                    spec,
                    new java.util.ArrayList<>(List.of(onlineUuid))
            );

            CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
            GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);
            assertFalse(result.isSuccess());
            assertEquals(GroupPlacementResult.Reason.INSUFFICIENT_SAFE_SLOTS, result.reason());
            verify(ticket).close();
        } finally {
            RTP.selectionAPI.permRegionLookup.remove("insufficient_test_region");
        }
    }

    @Test
    @DisplayName("Places online participants successfully and releases anchor ticket")
    void testSuccessfulGroupPlacement() throws Exception {
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                (io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) RTP.serverAccessor;
        RTPWorld<?> world = accessor.getRTPWorld("world");

        UUID p1Uuid = UUID.randomUUID();
        UUID p2Uuid = UUID.randomUUID();
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer p1 =
                new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(p1Uuid, "Player1", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer p2 =
                new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(p2Uuid, "Player2", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(p1);
        accessor.addPlayer(p2);

        Region mockRegion = mock(Region.class);
        ChunkReservation ticket = mock(ChunkReservation.class);
        GenerationResult genResult = new GenerationResult(
                new RTPCoords("world", 100, 64, 100),
                1,
                null,
                ticket
        );
        org.mockito.Mockito.doReturn(world).when(mockRegion).getWorld();
        when(mockRegion.getLocation(anySet())).thenReturn(CompletableFuture.completedFuture(genResult));
        when(mockRegion.candidateValidator()).thenReturn((x, z) -> new RTPLocation(new RTPCoords("world", x, 64, z), 1));
        RTP.selectionAPI.permRegionLookup.put("success_test_region", mockRegion);

        try {
            GroupProfileSpec spec = GroupProfileSpec.of("square", 32, 2, 5, 4);
            GroupPlacementRequest request = GroupPlacementRequest.of(
                    "success_test_region",
                    spec,
                    new java.util.ArrayList<>(List.of(p1Uuid, p2Uuid))
            );

            CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
            GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);
            assertNotNull(result);
            org.junit.jupiter.api.Assertions.assertTrue(result.isSuccess());
            assertEquals(2, result.placements().size());
            verify(ticket).close();
        } finally {
            RTP.selectionAPI.permRegionLookup.remove("success_test_region");
        }
    }

    @Test
    @DisplayName("Places online participants when some are offline, releasing offline reservation")
    void testPartialOfflineGroupPlacement() throws Exception {
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                (io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) RTP.serverAccessor;
        RTPWorld<?> world = accessor.getRTPWorld("world");

        UUID p1Uuid = UUID.randomUUID();
        UUID offlineUuid = UUID.randomUUID();
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer p1 =
                new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(p1Uuid, "Player1", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(p1);

        Region mockRegion = mock(Region.class);
        ChunkReservation ticket = mock(ChunkReservation.class);
        GenerationResult genResult = new GenerationResult(
                new RTPCoords("world", 100, 64, 100),
                1,
                null,
                ticket
        );
        org.mockito.Mockito.doReturn(world).when(mockRegion).getWorld();
        when(mockRegion.getLocation(anySet())).thenReturn(CompletableFuture.completedFuture(genResult));
        when(mockRegion.candidateValidator()).thenReturn((x, z) -> new RTPLocation(new RTPCoords("world", x, 64, z), 1));
        RTP.selectionAPI.permRegionLookup.put("partial_test_region", mockRegion);

        try {
            GroupProfileSpec spec = GroupProfileSpec.of("square", 32, 2, 5, 4);
            GroupPlacementRequest request = GroupPlacementRequest.of(
                    "partial_test_region",
                    spec,
                    new java.util.ArrayList<>(List.of(p1Uuid, offlineUuid))
            );

            CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
            GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);
            assertNotNull(result);
            org.junit.jupiter.api.Assertions.assertTrue(result.isSuccess());
            assertEquals(2, result.placements().size());
            verify(ticket).close();
        } finally {
            RTP.selectionAPI.permRegionLookup.remove("partial_test_region");
        }
    }

    @Test
    @DisplayName("Returns INVALID_REGION when region has no world")
    void testRegionHasNoWorld() throws Exception {
        Region mockRegion = mock(Region.class);
        ChunkReservation ticket = mock(ChunkReservation.class);
        GenerationResult genResult = new GenerationResult(
                new RTPCoords("world", 100, 64, 100),
                1,
                null,
                ticket
        );
        when(mockRegion.getLocation(anySet())).thenReturn(CompletableFuture.completedFuture(genResult));
        when(mockRegion.getWorld()).thenReturn(null);
        RTP.selectionAPI.permRegionLookup.put("no_world_region", mockRegion);

        try {
            GroupProfileSpec spec = GroupProfileSpec.of("square", 32, 2, 5, 4);
            GroupPlacementRequest request = GroupPlacementRequest.of(
                    "no_world_region",
                    spec,
                    new java.util.ArrayList<>(List.of(UUID.randomUUID()))
            );

            CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
            GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertFalse(result.isSuccess());
            assertEquals(GroupPlacementResult.Reason.INVALID_REGION, result.reason());
            verify(ticket).close();
        } finally {
            RTP.selectionAPI.permRegionLookup.remove("no_world_region");
        }
    }

    @Test
    @DisplayName("Returns INSUFFICIENT_SAFE_SLOTS when global verifier rejects a slot")
    void testGlobalVerifierRejectsSlot() throws Exception {
        RTPWorld<?> world = RTP.serverAccessor.getRTPWorld("world");
        Region mockRegion = mock(Region.class);
        ChunkReservation ticket = mock(ChunkReservation.class);
        GenerationResult genResult = new GenerationResult(
                new RTPCoords("world", 100, 64, 100),
                1,
                null,
                ticket
        );
        org.mockito.Mockito.doReturn(world).when(mockRegion).getWorld();
        when(mockRegion.getLocation(anySet())).thenReturn(CompletableFuture.completedFuture(genResult));
        when(mockRegion.candidateValidator()).thenReturn((x, z) -> new RTPLocation(new RTPCoords("world", x, 64, z), 1));
        RTP.selectionAPI.permRegionLookup.put("verifier_fail_region", mockRegion);

        // Add verifier that fails everything
        GlobalRegionVerifiers.addGlobalRegionVerifier(coords -> false);

        try {
            GroupProfileSpec spec = GroupProfileSpec.of("square", 32, 2, 5, 4);
            GroupPlacementRequest request = GroupPlacementRequest.of(
                    "verifier_fail_region",
                    spec,
                    new java.util.ArrayList<>(List.of(UUID.randomUUID()))
            );

            CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
            GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertFalse(result.isSuccess());
            assertEquals(GroupPlacementResult.Reason.INSUFFICIENT_SAFE_SLOTS, result.reason());
            verify(ticket).close();
        } finally {
            GlobalRegionVerifiers.clearGlobalRegionVerifiers();
            RTP.selectionAPI.permRegionLookup.remove("verifier_fail_region");
        }
    }

    @Test
    @DisplayName("Verifier failure records safetyExternal bad chunk to parent MemoryShape")
    void testGlobalVerifierFailureInheritsBadChunkToMemoryShape() throws Exception {
        RTPWorld<?> world = RTP.serverAccessor.getRTPWorld("world");
        Region mockRegion = mock(Region.class);
        io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape<?> mockMemShape =
                mock(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape.class);
        doReturn(mockMemShape).when(mockRegion).getShape();
        when(mockMemShape.contains(anyInt(), anyInt())).thenReturn(true);
        when(mockMemShape.isKnownBad(anyInt(), anyInt())).thenReturn(false);
        when(mockMemShape.xzToLocation(anyLong(), anyLong())).thenReturn(100L);

        ChunkReservation ticket = mock(ChunkReservation.class);
        GenerationResult genResult = new GenerationResult(
                new RTPCoords("world", 100, 64, 100),
                1,
                null,
                ticket
        );
        doReturn(world).when(mockRegion).getWorld();
        when(mockRegion.getLocation(anySet())).thenReturn(CompletableFuture.completedFuture(genResult));
        when(mockRegion.candidateValidator()).thenReturn((x, z) -> new RTPLocation(new RTPCoords("world", x, 64, z), 1));
        RTP.selectionAPI.permRegionLookup.put("verifier_bad_chunk_region", mockRegion);

        // Fail global verifier
        GlobalRegionVerifiers.addGlobalRegionVerifier(coords -> false);

        try {
            GroupProfileSpec spec = GroupProfileSpec.of("square", 32, 2, 5, 4);
            GroupPlacementRequest request = GroupPlacementRequest.of(
                    "verifier_bad_chunk_region",
                    spec,
                    new java.util.ArrayList<>(List.of(UUID.randomUUID()))
            );

            CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
            GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertFalse(result.isSuccess());
            verify(mockMemShape).addBadChunk(
                    anyLong(),
                    eq(LocationGenerator.FailTypes.safetyExternal)
            );
        } finally {
            GlobalRegionVerifiers.clearGlobalRegionVerifiers();
            RTP.selectionAPI.permRegionLookup.remove("verifier_bad_chunk_region");
        }
    }

    @Test
    @DisplayName("Returns INVALID_REGION when region lookup throws cyclic or lookup exception")
    void testCyclicOrThrowingRegionLookup() throws Exception {
        io.github.dailystruggle.rtp.common.selection.SelectionAPI originalSelectionAPI = RTP.selectionAPI;
        io.github.dailystruggle.rtp.common.selection.SelectionAPI mockSelectionAPI = mock(io.github.dailystruggle.rtp.common.selection.SelectionAPI.class);
        when(mockSelectionAPI.getRegion(org.mockito.ArgumentMatchers.eq("cyclic_region")))
                .thenThrow(new IllegalStateException("infinite override loop detected at region - cyclic_region"));
        RTP.selectionAPI = mockSelectionAPI;

        try {
            GroupProfileSpec spec = GroupProfileSpec.of("square", 32, 2, 5, 4);
            GroupPlacementRequest request = GroupPlacementRequest.of(
                    "cyclic_region",
                    spec,
                    new java.util.ArrayList<>(List.of(UUID.randomUUID()))
            );

            CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
            GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertFalse(result.isSuccess());
            assertEquals(GroupPlacementResult.Reason.INVALID_REGION, result.reason());
            assertTrue(result.message().contains("infinite override loop detected"));
        } finally {
            RTP.selectionAPI = originalSelectionAPI;
        }
    }

    @Test
    @DisplayName("Returns INVALID_REGION when region mapping is empty or not in lookup")
    void testEmptyOrMissingRegionMapping() throws Exception {
        GroupProfileSpec spec = GroupProfileSpec.of("square", 32, 2, 5, 4);
        GroupPlacementRequest request = GroupPlacementRequest.of(
                "non_existent_empty_region",
                spec,
                new java.util.ArrayList<>(List.of(UUID.randomUUID()))
        );

        CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
        GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals(GroupPlacementResult.Reason.INVALID_REGION, result.reason());
        assertTrue(result.message().contains("unknown region"));
    }

    @Test
    @DisplayName("Places nearplayer using EntityAnchorSource with single participant")
    void testNearPlayerPlacement() throws Exception {
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                (io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) RTP.serverAccessor;
        RTPWorld<?> world = accessor.getRTPWorld("world");
        Region mockRegion = mock(Region.class);
        org.mockito.Mockito.doReturn(world).when(mockRegion).getWorld();
        when(mockRegion.candidateValidator()).thenReturn((x, z) -> new RTPLocation(new RTPCoords("world", x, 64, z), 1));
        RTP.selectionAPI.permRegionLookup.put("nearplayer_region", mockRegion);

        UUID playerUuid = UUID.randomUUID();
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer player =
                new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(playerUuid, "TargetPlayer", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(player);

        try {
            GroupProfileSpec spec = GroupProfileSpec.of("square", 32, 2, 5, 1);
            RTPCoords targetPlayerPos = new RTPCoords("world", 500, 64, 500);

            GroupPlacementRequest request = GroupPlacementRequest.of(
                    "nearplayer_region",
                    spec,
                    new java.util.ArrayList<>(List.of(playerUuid)),
                    io.github.dailystruggle.rtp.api.group.AnchorSource.fixed(targetPlayerPos)
            );

            CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
            GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertTrue(result.isSuccess(), "Near-player placement should succeed: " + result.message());
            assertEquals(1, result.placements().size());
            io.github.dailystruggle.rtp.api.world.RTPLocation placedLoc = result.placements().get(playerUuid);
            assertNotNull(placedLoc);
            // Verify placement is within square profile bounds of target coordinates
            int dx = Math.abs(placedLoc.x() - targetPlayerPos.x());
            int dz = Math.abs(placedLoc.z() - targetPlayerPos.z());
            assertTrue(dx <= 32 && dz <= 32, "Placed spot must be within profile half-extent");
        } finally {
            RTP.selectionAPI.permRegionLookup.remove("nearplayer_region");
        }
    }

    @Test
    @DisplayName("Bounded retries: retries with a fresh anchor when initial placement fails (ADR-097)")
    void testBoundedPlacementRetriesOnInitialFailure() throws Exception {
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                (io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) RTP.serverAccessor;
        RTPWorld<?> world = accessor.getRTPWorld("world");
        Region mockRegion = mock(Region.class);
        doReturn(world).when(mockRegion).getWorld();

        UUID playerUuid = UUID.randomUUID();
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer player =
                new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(playerUuid, "RetryPlayer", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(player);

        // Sequence of anchors: try 1 at (100, 100), try 2 at (200, 200)
        GenerationResult anchor1 = new GenerationResult(new RTPCoords("world", 100, 64, 100), 1, null, null);
        GenerationResult anchor2 = new GenerationResult(new RTPCoords("world", 200, 64, 200), 1, null, null);
        java.util.concurrent.atomic.AtomicInteger drawCount = new java.util.concurrent.atomic.AtomicInteger(0);

        when(mockRegion.getLocation(anySet())).thenAnswer(inv -> {
            int count = drawCount.incrementAndGet();
            return CompletableFuture.completedFuture(count == 1 ? anchor1 : anchor2);
        });

        // Candidate validator rejects candidates around (100, 100) but accepts candidates around (200, 200)
        when(mockRegion.candidateValidator()).thenReturn((x, z) -> {
            if (Math.abs(x - 100) <= 32 && Math.abs(z - 100) <= 32) {
                return null; // rejected on attempt 1!
            }
            return new RTPLocation(new RTPCoords("world", x, 64, z), 1);
        });

        RTP.selectionAPI.permRegionLookup.put("retry_region", mockRegion);

        try {
            // Profile with retries = 3
            GroupProfileSpec spec = GroupProfileSpec.of("square", 16, 2, 5, 1, 3);
            GroupPlacementRequest request = GroupPlacementRequest.of(
                    "retry_region",
                    spec,
                    new java.util.ArrayList<>(List.of(playerUuid))
            );

            CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
            GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertTrue(result.isSuccess(), "Should succeed on second attempt after retry: " + result.message());
            assertEquals(2, drawCount.get(), "Must have executed 2 anchor draws");
            io.github.dailystruggle.rtp.api.world.RTPLocation placedLoc = result.placements().get(playerUuid);
            assertNotNull(placedLoc);
            // Verify it landed near anchor 2 (around 200, 200)
            assertTrue(Math.abs(placedLoc.x() - 200) <= 16);
            assertTrue(Math.abs(placedLoc.z() - 200) <= 16);
        } finally {
            RTP.selectionAPI.permRegionLookup.remove("retry_region");
        }
    }
}
