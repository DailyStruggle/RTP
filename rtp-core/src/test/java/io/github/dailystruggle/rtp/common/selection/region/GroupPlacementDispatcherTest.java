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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
        when(mockRegion.candidateValidator()).thenReturn(new CandidateValidator() {
            @Override
            public RTPLocation validate(int x, int z) {
                return new RTPLocation(new RTPCoords("world", x, 64, z), 1);
            }

            @Override
            public CompletableFuture<RTPLocation> validateAsync(int x, int z) {
                return CompletableFuture.completedFuture(validate(x, z));
            }
        });
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
        RTPWorld<?> world = RTP.serverAccessor.getRTPWorld("world");
        ((io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) RTP.serverAccessor).addPlayer(
                new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
                        onlineUuid, "OnlinePlayer", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0)));

        Region mockRegion = mock(Region.class);
        ChunkReservation ticket = mock(ChunkReservation.class);
        GenerationResult genResult = new GenerationResult(
                new RTPCoords("world", 0, 64, 0),
                1,
                null,
                ticket
        );
        org.mockito.Mockito.doReturn(world).when(mockRegion).getWorld();
        when(mockRegion.getLocation(anySet())).thenReturn(CompletableFuture.completedFuture(genResult));
        // validator returns null everywhere -> 0 slots
        when(mockRegion.candidateValidator()).thenReturn(new CandidateValidator() {
            @Override
            public RTPLocation validate(int x, int z) {
                return null;
            }

            @Override
            public CompletableFuture<RTPLocation> validateAsync(int x, int z) {
                return CompletableFuture.completedFuture(null);
            }
        });
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
    @DisplayName("Footprint chunks are warmed asynchronously before slot selection")
    void testFootprintChunksWarmedBeforeSlotSelection() throws Exception {
        UUID onlineUuid = UUID.randomUUID();
        RTPWorld<?> world = RTP.serverAccessor.getRTPWorld("world");
        ((io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) RTP.serverAccessor).addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
                onlineUuid, "WarmedPlayer", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0)));

        Region mockRegion = mock(Region.class);
        ChunkReservation ticket = mock(ChunkReservation.class);
        GenerationResult genResult = new GenerationResult(
                new RTPCoords("world", 0, 64, 0),
                1,
                null,
                ticket
        );
        org.mockito.Mockito.doReturn(world).when(mockRegion).getWorld();
        when(mockRegion.getLocation(anySet())).thenReturn(CompletableFuture.completedFuture(genResult));
        when(mockRegion.candidateValidator()).thenReturn((x, z) -> new RTPLocation(new RTPCoords("world", x, 64, z), 1));
        RTP.selectionAPI.permRegionLookup.put("warm_test_region", mockRegion);

        try {
            GroupProfileSpec spec = GroupProfileSpec.of("square", 16, 2, 5, 4);
            GroupPlacementRequest request = GroupPlacementRequest.of(
                    "warm_test_region",
                    spec,
                    new java.util.ArrayList<>(List.of(onlineUuid))
            );

            CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
            GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);
            assertTrue(result.isSuccess(), "Placement should succeed when chunks are warmed");
            assertEquals(1, result.placements().size());
        } finally {
            RTP.selectionAPI.permRegionLookup.remove("warm_test_region");
        }
    }

    @Test
    @DisplayName("Non-square distribution resolves shape mask from factory")
    void testNonSquareDistributionShapeMask() throws Exception {
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
        when(mockRegion.candidateValidator()).thenReturn((x, z) -> new RTPLocation(new RTPCoords("world", x, 64, z), 1L));
        RTP.selectionAPI.permRegionLookup.put("circle_shape_region", mockRegion);

        try {
            GroupProfileSpec spec = GroupProfileSpec.of("circle", 32, 4, 5, 4);
            GroupPlacementRequest request = GroupPlacementRequest.of(
                    "circle_shape_region",
                    spec,
                    new java.util.ArrayList<>(List.of(onlineUuid))
            );

            CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
            GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);
            assertNotNull(result);
        } finally {
            RTP.selectionAPI.permRegionLookup.remove("circle_shape_region");
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
            verify(mockMemShape, atLeastOnce()).addBadChunk(
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

    @Test
    @DisplayName("preparePlacement handles null request, null world, and fallback validator branches")
    void testPreparePlacementCornerCases() throws Exception {
        // 1. null request
        GroupPlacementDispatcher.PreparedPlacement nullPrep = dispatcher.preparePlacement(null).get(5, TimeUnit.SECONDS);
        assertNotNull(nullPrep);
        assertFalse(nullPrep.isSuccess());
        assertEquals(GroupPlacementResult.Reason.ERROR, nullPrep.result().reason());

        // 2. region lookup throwing exception
        io.github.dailystruggle.rtp.common.selection.SelectionAPI realSel = RTP.selectionAPI;
        try {
            RTP.selectionAPI = new io.github.dailystruggle.rtp.common.selection.SelectionAPI() {
                @Override
                public Region getRegion(String name) {
                    if ("throw_region".equals(name)) {
                        throw new RuntimeException("Simulated lookup failure");
                    }
                    return null;
                }
            };
            GroupProfileSpec spec = GroupProfileSpec.of("square", 16, 2, 5, 1, 1);
            GroupPlacementRequest throwReq = GroupPlacementRequest.of(
                    "throw_region",
                    spec,
                    List.of(UUID.randomUUID())
            );
            GroupPlacementDispatcher.PreparedPlacement throwPrep = dispatcher.preparePlacement(throwReq).get(5, TimeUnit.SECONDS);
            assertNotNull(throwPrep);
            assertFalse(throwPrep.isSuccess());
            assertEquals(GroupPlacementResult.Reason.INVALID_REGION, throwPrep.result().reason());
        } finally {
            RTP.selectionAPI = realSel;
        }

        // 3. createWorldFallbackValidator branches
        RTPWorld<?> nullWorld = null;
        CandidateValidator fallbackValidator = GroupPlacementDispatcher.createWorldFallbackValidator(nullWorld);
        assertNull(fallbackValidator.validate(0, 0));
        assertNull(fallbackValidator.validateAsync(0, 0).get(5, TimeUnit.SECONDS));

        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                (io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) RTP.serverAccessor;
        RTPWorld<?> world = accessor.getRTPWorld("world");
        CandidateValidator realFallback = GroupPlacementDispatcher.createWorldFallbackValidator(world);
        RTPLocation val = realFallback.validate(0, 0);
        assertNotNull(val);
        RTPLocation valAsync = realFallback.validateAsync(0, 0).get(5, TimeUnit.SECONDS);
        assertNotNull(valAsync);

        // 4. allocate with null world coordinates
        GenerationResult nullCoordsGen = new GenerationResult(new RTPCoords(null, 0, 0, 0), 1, null, null);
        GroupProfileSpec spec = GroupProfileSpec.of("square", 16, 2, 5, 1, 1);
        GroupPlacementRequest nullWorldReq = GroupPlacementRequest.of(
                "dummy_region",
                spec,
                List.of(UUID.randomUUID()),
                ctx -> CompletableFuture.completedFuture(nullCoordsGen.coords())
        );
        GroupPlacementResult nullWorldRes = dispatcher.place(nullWorldReq).get(5, TimeUnit.SECONDS);
        assertNotNull(nullWorldRes);
        assertFalse(nullWorldRes.isSuccess());
    }

    @Test
    @DisplayName("2x candidate over-provisioning absorbs global verifier attrition and closes excess reservations (S-002)")
    void testOverProvisioningAbsorbsAttritionAndReleasesExcessReservations() throws Exception {
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                (io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) RTP.serverAccessor;
        RTPWorld<?> world = accessor.getRTPWorld("world");

        UUID p1Uuid = UUID.randomUUID();
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer p1 =
                new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(p1Uuid, "Player1", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(p1);

        Region mockRegion = mock(Region.class);
        ChunkReservation anchorTicket = mock(ChunkReservation.class);
        GenerationResult genResult = new GenerationResult(
                new RTPCoords("world", 100, 64, 100),
                1,
                null,
                anchorTicket
        );
        doReturn(world).when(mockRegion).getWorld();
        when(mockRegion.getLocation(anySet())).thenReturn(CompletableFuture.completedFuture(genResult));

        java.util.concurrent.atomic.AtomicInteger candidateCounter = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<ChunkReservation> candidateTickets = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<RTPCoords> candidateCoords = new java.util.concurrent.CopyOnWriteArrayList<>();

        // Validator supplies candidates with mock ChunkReservation tickets
        when(mockRegion.candidateValidator()).thenReturn(new CandidateValidator() {
            @Override
            public RTPLocation validate(int x, int z) {
                return validateAsync(x, z).join();
            }

            @Override
            public CompletableFuture<RTPLocation> validateAsync(int x, int z) {
                ChunkReservation res = mock(ChunkReservation.class);
                candidateTickets.add(res);
                RTPCoords coords = new RTPCoords("world", x, 64, z);
                candidateCoords.add(coords);
                candidateCounter.incrementAndGet();
                return CompletableFuture.completedFuture(new RTPLocation(coords, 1L, res));
            }
        });

        RTP.selectionAPI.permRegionLookup.put("overprov_region", mockRegion);

        // Global verifier rejects the 1st candidate, but accepts subsequent candidates
        GlobalRegionVerifiers.addGlobalRegionVerifier(coords -> {
            // Reject the first evaluated coordinate
            if (!candidateCoords.isEmpty() && coords.x() == candidateCoords.get(0).x() && coords.z() == candidateCoords.get(0).z()) {
                return false;
            }
            return true;
        });

        try {
            // 1 participant, so 2x over-provisioning requests 2 candidates
            GroupProfileSpec spec = GroupProfileSpec.of("square", 32, 2, 5, 4);
            GroupPlacementRequest request = GroupPlacementRequest.of(
                    "overprov_region",
                    spec,
                    new java.util.ArrayList<>(List.of(p1Uuid))
            );

            CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
            GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertTrue(result.isSuccess(), "Over-provisioning should absorb first candidate rejection: " + result.message());
            assertEquals(1, result.placements().size());

            // Check that at least 2 candidates were evaluated
            assertTrue(candidateTickets.size() >= 2, "Should have over-provisioned at least 2 candidates: " + candidateTickets.size());

            // The rejected first ticket must have been closed (S-002)
            verify(candidateTickets.get(0)).close();

            // The anchor ticket must have been closed
            verify(anchorTicket).close();
        } finally {
            GlobalRegionVerifiers.clearGlobalRegionVerifiers();
            RTP.selectionAPI.permRegionLookup.remove("overprov_region");
        }
    }

    @Test
    @DisplayName("Failed placement releases all candidate reservations (S-002)")
    void testFailedPlacementReleasesAllCandidateReservations() throws Exception {
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                (io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) RTP.serverAccessor;
        RTPWorld<?> world = accessor.getRTPWorld("world");

        UUID p1Uuid = UUID.randomUUID();
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer p1 =
                new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(p1Uuid, "Player1", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(p1);

        Region mockRegion = mock(Region.class);
        ChunkReservation anchorTicket = mock(ChunkReservation.class);
        GenerationResult genResult = new GenerationResult(
                new RTPCoords("world", 100, 64, 100),
                1,
                null,
                anchorTicket
        );
        doReturn(world).when(mockRegion).getWorld();
        when(mockRegion.getLocation(anySet())).thenReturn(CompletableFuture.completedFuture(genResult));

        java.util.List<ChunkReservation> candidateTickets = new java.util.concurrent.CopyOnWriteArrayList<>();

        when(mockRegion.candidateValidator()).thenReturn(new CandidateValidator() {
            @Override
            public RTPLocation validate(int x, int z) {
                return validateAsync(x, z).join();
            }

            @Override
            public CompletableFuture<RTPLocation> validateAsync(int x, int z) {
                ChunkReservation res = mock(ChunkReservation.class);
                candidateTickets.add(res);
                return CompletableFuture.completedFuture(new RTPLocation(new RTPCoords("world", x, 64, z), 1L, res));
            }
        });

        RTP.selectionAPI.permRegionLookup.put("fail_release_region", mockRegion);

        // Global verifier rejects everything
        GlobalRegionVerifiers.addGlobalRegionVerifier(coords -> false);

        try {
            GroupProfileSpec spec = GroupProfileSpec.of("square", 32, 2, 5, 4);
            GroupPlacementRequest request = GroupPlacementRequest.of(
                    "fail_release_region",
                    spec,
                    new java.util.ArrayList<>(List.of(p1Uuid))
            );

            CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
            GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertFalse(result.isSuccess());
            assertEquals(GroupPlacementResult.Reason.INSUFFICIENT_SAFE_SLOTS, result.reason());

            // All candidates must have had their reservations closed (S-002)
            assertFalse(candidateTickets.isEmpty());
            for (ChunkReservation ticket : candidateTickets) {
                verify(ticket).close();
            }
            verify(anchorTicket).close();
        } finally {
            GlobalRegionVerifiers.clearGlobalRegionVerifiers();
            RTP.selectionAPI.permRegionLookup.remove("fail_release_region");
        }
    }

    @Test
    @DisplayName("Phase 3: GroupPlacementDispatcher places multiple participants with guaranteed separation")
    void testGroupPlacementMultipleParticipantsWithSeparation() throws Exception {
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
        ChunkReservation anchorTicket = mock(ChunkReservation.class);
        GenerationResult genResult = new GenerationResult(
                new RTPCoords("world", 100, 64, 100),
                1,
                null,
                anchorTicket
        );
        doReturn(world).when(mockRegion).getWorld();
        when(mockRegion.getLocation(anySet())).thenReturn(CompletableFuture.completedFuture(genResult));

        when(mockRegion.candidateValidator()).thenReturn(new CandidateValidator() {
            @Override
            public RTPLocation validate(int x, int z) {
                return new RTPLocation(new RTPCoords("world", x, 64, z), 1L);
            }

            @Override
            public CompletableFuture<RTPLocation> validateAsync(int x, int z) {
                return CompletableFuture.completedFuture(new RTPLocation(new RTPCoords("world", x, 64, z), 1L));
            }
        });

        RTP.selectionAPI.permRegionLookup.put("multi_placement_region", mockRegion);

        try {
            GroupProfileSpec spec = GroupProfileSpec.of("square", 48, 2, 8, 4);
            GroupPlacementRequest request = GroupPlacementRequest.of(
                    "multi_placement_region",
                    spec,
                    new java.util.ArrayList<>(List.of(p1Uuid, p2Uuid))
            );

            CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
            GroupPlacementResult result = future.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertTrue(result.isSuccess(), "Placement should succeed for 2 participants");
            assertEquals(2, result.placements().size());

            io.github.dailystruggle.rtp.api.world.RTPLocation loc1 = result.placements().get(p1Uuid);
            io.github.dailystruggle.rtp.api.world.RTPLocation loc2 = result.placements().get(p2Uuid);
            long dx = (long) loc1.x() - loc2.x();
            long dz = (long) loc1.z() - loc2.z();
            assertTrue(dx * dx + dz * dz >= 8 * 8, "Slots must satisfy minSeparation: " + Math.sqrt(dx * dx + dz * dz));
            verify(anchorTicket).close();
        } finally {
            RTP.selectionAPI.permRegionLookup.remove("multi_placement_region");
        }
    }

    /**
     * A world whose warming path ({@link #getChunkAt}) resolves successfully but whose
     * {@link #getCachedChunk(long)} always returns {@code null} - i.e. chunks are "warmed" but never
     * become resident at validation time. This mirrors the production symptom where anvil views are
     * single-consumption / WeakReference-cached, so the real {@code RegionCandidateValidator} (which
     * reads only resident chunks and fails closed for non-resident columns) rejects every candidate.
     */
    private static final class WarmButNotResidentWorld
            extends io.github.dailystruggle.rtp.common.mock.MockRTPWorld {
        WarmButNotResidentWorld(String name) {
            super(name);
        }

        @Override
        public io.github.dailystruggle.rtp.api.world.RTPChunk<?> getCachedChunk(long key) {
            // Warming (getChunkAt) still completes, but the chunk is never resident on the cache path.
            return null;
        }
    }

    @Test
    @DisplayName("Repro: live INSUFFICIENT_SAFE_SLOTS - real validator + dual-layer parent, chunks warmed but not resident")
    void testLiveInsufficientSafeSlotsReproWithRealValidator() throws Exception {
        // Faithfully reproduces the observed production failure (121 futures warmed, "0 slots (required 2)"
        // on every retry -> INSUFFICIENT_SAFE_SLOTS with no teleport). The earlier group-placement tests
        // masked this by injecting permissive mock validators; here we drive the REAL
        // RegionCandidateValidator against a real SquareOptimizedDualLayer parent (so candidate
        // enumeration is NOT starved) with a world whose warming succeeds but whose chunks never become
        // resident, so every column fails closed - the "point C" the mocks previously hid.
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                (io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) RTP.serverAccessor;
        WarmButNotResidentWorld world = new WarmButNotResidentWorld("world");
        accessor.addWorld(world);

        UUID p1Uuid = UUID.randomUUID();
        UUID p2Uuid = UUID.randomUUID();
        accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
                p1Uuid, "leaf26", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0)));
        accessor.addPlayer(new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
                p2Uuid, "leaf_26", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0)));

        // Real dual-layer parent with fresh (unscanned) hazard memory: every in-domain chunk is
        // not-known-bad, so the footprint candidate pool is FULL (enumeration is not the failing stage).
        io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer
                dualLayer =
                        new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes
                                .SquareOptimizedDualLayer();
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("radius", 10000L);
        data.put("centerRadius", 0L);
        data.put("centerX", 0L);
        data.put("centerZ", 0L);
        dualLayer.setData(data);

        Region mockRegion = mock(Region.class);
        ChunkReservation anchorTicket = mock(ChunkReservation.class);
        GenerationResult genResult = new GenerationResult(
                new RTPCoords("world", 2000, 64, 2000),
                1,
                null,
                anchorTicket
        );
        doReturn(world).when(mockRegion).getWorld();
        doReturn(dualLayer).when(mockRegion).getShape();
        doReturn(new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear
                        .LinearAdjustor(new java.util.ArrayList<>()))
                .when(mockRegion).getVert();
        when(mockRegion.getLocation(anySet())).thenReturn(CompletableFuture.completedFuture(genResult));
        // The REAL production validator - reads only resident chunks, fails closed otherwise.
        when(mockRegion.candidateValidator()).thenReturn(new RegionCandidateValidator(mockRegion));

        RTP.selectionAPI.permRegionLookup.put("live_repro_region", mockRegion);

        try {
            GroupProfileSpec spec = GroupProfileSpec.of("square", 64, 24, 64, 2);
            GroupPlacementRequest request = GroupPlacementRequest.of(
                    "live_repro_region",
                    spec,
                    new java.util.ArrayList<>(List.of(p1Uuid, p2Uuid))
            );

            CompletableFuture<GroupPlacementResult> future = dispatcher.place(request);
            GroupPlacementResult result = future.get(10, TimeUnit.SECONDS);

            assertNotNull(result);
            assertFalse(result.isSuccess(), "Warmed-but-not-resident chunks must reproduce the live failure");
            assertEquals(GroupPlacementResult.Reason.INSUFFICIENT_SAFE_SLOTS, result.reason());
            assertTrue(result.placements() == null || result.placements().isEmpty(),
                    "No participant should be placed when every column fails closed");
        } finally {
            RTP.selectionAPI.permRegionLookup.remove("live_repro_region");
        }
    }
}
