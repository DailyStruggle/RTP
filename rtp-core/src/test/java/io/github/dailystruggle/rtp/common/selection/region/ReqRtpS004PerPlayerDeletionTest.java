package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class ReqRtpS004PerPlayerDeletionTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private MockRTPWorld world;
    private Region region;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        accessor.setLocationGenerator(new LocationGenerator());

        world = new MockRTPWorld("test_world");
        accessor.addWorld(world);

        RegionSettings settings = new RegionSettings(
                "test_region",
                world,
                new Circle(),
                new LinearAdjustor(new ArrayList<>()),
                false,
                false,
                10L,
                1000L,
                5,
                0.0,
                1L,
                "",
                false);
        region = new Region("test_region", settings);
    }

    @Test
    @DisplayName("REQ-RTP-S-004: Consumed private locations must be deleted from database")
    void execute_deletes_private_location_from_db() {
        UUID playerId = UUID.randomUUID();
        RTPCoords coords = new RTPCoords(world.name(), 16, 64, 16);

        // Setup chunk reservation mock
        ChunkReservation reservation = mock(ChunkReservation.class);
        ChunkSet chunkSet = mock(ChunkSet.class);
        CompletableFuture<Boolean> completeFuture = CompletableFuture.completedFuture(true);
        when(reservation.getChunkSet()).thenReturn(chunkSet);
        when(chunkSet.complete()).thenReturn(completeFuture);

        RTPLocation loc = new RTPLocation(coords, 1L, reservation);

        // Setup database mock
        DatabaseAccessor db = mock(DatabaseAccessor.class);
        RTP.getInstance().databaseAccessor = db;

        // Add to private queue
        region.queueManager.enqueuePlayerLocation(playerId, loc);

        // Add Mock Player
        io.github.dailystruggle.rtp.api.world.RTPLocation apiLoc =
            new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0);
        MockRTPPlayer player = new MockRTPPlayer(playerId, "TestPlayer", apiLoc);
        accessor.addPlayer(player);

        region.queueManager.queue(playerId);

        // Setup teleport data
        TeleportData data = new TeleportData();
        data.completed = false;
        data.sender = player;
        RTP.getInstance().latestTeleportData.put(playerId, data);
        RTP.getInstance().processingPlayers.add(playerId);

        // Run execute. It should poll and delete.
        region.execute(Long.MAX_VALUE);

        // Verify deletion was called for the private location
        verify(db, times(1)).deleteCachedLocation(eq(region.name), eq(loc));
    }

    @Test
    @DisplayName("REQ-RTP-S-004: Consumed public locations must be deleted from database")
    void execute_deletes_public_location_from_db() {
        UUID playerId = UUID.randomUUID();
        RTPCoords coords = new RTPCoords(world.name(), 32, 64, 32);

        // Setup chunk reservation mock
        ChunkReservation reservation = mock(ChunkReservation.class);
        ChunkSet chunkSet = mock(ChunkSet.class);
        CompletableFuture<Boolean> completeFuture = CompletableFuture.completedFuture(true);
        when(reservation.getChunkSet()).thenReturn(chunkSet);
        when(chunkSet.complete()).thenReturn(completeFuture);

        RTPLocation loc = new RTPLocation(coords, 1L, reservation);

        // Setup database mock
        DatabaseAccessor db = mock(DatabaseAccessor.class);
        RTP.getInstance().databaseAccessor = db;

        // Add to public queue
        region.queueManager.keptLocations.offer(loc);

        // Add Mock Player
        io.github.dailystruggle.rtp.api.world.RTPLocation apiLoc =
                new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0);
        MockRTPPlayer player = new MockRTPPlayer(playerId, "TestPlayer", apiLoc);
        accessor.addPlayer(player);

        region.queueManager.queue(playerId);

        // Setup teleport data
        TeleportData data = new TeleportData();
        data.completed = false;
        data.sender = player;
        RTP.getInstance().latestTeleportData.put(playerId, data);
        RTP.getInstance().processingPlayers.add(playerId);

        // Run execute. It should poll and delete.
        region.execute(Long.MAX_VALUE);

        // Verify deletion was called for the public location
        verify(db, atLeastOnce()).deleteCachedLocation(eq(region.name), eq(loc));
    }
}
