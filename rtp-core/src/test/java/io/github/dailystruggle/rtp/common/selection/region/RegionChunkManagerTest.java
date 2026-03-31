package io.github.dailystruggle.rtp.common.selection.region;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class RegionChunkManagerTest {
    private Region region;
    private RegionChunkManager chunkManager;
    private RTPScheduler scheduler;
    private RTPServerAccessor serverAccessor;
    private RTPChunkManager rtpChunkManager;
    private RTPWorld<?> world;

    @BeforeEach
    void setUp() {
        region = mock(Region.class);
        world = mock(RTPWorld.class);
        when(region.getWorld()).thenReturn((RTPWorld) world);
        
        chunkManager = new RegionChunkManager(region);
        
        scheduler = mock(RTPScheduler.class);
        RTP.scheduler = scheduler;
        
        serverAccessor = mock(RTPServerAccessor.class);
        rtpChunkManager = mock(RTPChunkManager.class);
        when(serverAccessor.getChunkManager()).thenReturn(rtpChunkManager);
        RTP.serverAccessor = serverAccessor;

        when(rtpChunkManager.getChunkAtAsync(any(), anyInt(), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(1L));
    }

    @Test
    void testChunkKeyGeneration() {
        // formula: ((long) cx & 0xFFFFFFFFL) | ((long) cz << 32)
        // Verify key generation with positive and negative coordinates
        
        assertEquals(0L, chunkManager.getChunkKey(0, 0));
        assertEquals(1L, chunkManager.getChunkKey(1, 0));
        assertEquals(1L << 32, chunkManager.getChunkKey(0, 1));
        
        // Negative coordinates
        // cx = -1 -> 0xFFFFFFFF
        // cz = -1 -> 0xFFFFFFFF
        // key should be 0xFFFFFFFFFFFFFFFFL
        assertEquals(-1L, chunkManager.getChunkKey(-1, -1));
        
        // cx = 1, cz = -1
        // (1 & FFFFFFFF) | (FFFFFFFF << 32) -> 0x00000001 | 0xFFFFFFFF00000000 -> 0xFFFFFFFF00000001
        assertEquals(0xFFFFFFFF00000001L, chunkManager.getChunkKey(1, -1));
    }

    @Test
    void testAddTicketChunkCoordinates() {
        chunkManager.addTicket(10, 20);
        long key = ((long) 10 & 0xFFFFFFFFL) | ((long) 20L << 32);
        
        assertTrue(chunkManager.locAssChunks.containsKey(key));
        assertEquals(1, chunkManager.ticketCounts.get(key));
        
        chunkManager.addTicket(10, 20);
        assertEquals(2, chunkManager.ticketCounts.get(key));
        
        verify(rtpChunkManager, times(1)).getChunkAtAsync(world, 10, 20);
    }

    @Test
    void testAddTicketFromLocation() {
        // RTPCoords(String world, int x, int y, int z)
        RTPCoords coords = new RTPCoords("world", 200, 64, -200);
        chunkManager.addTicket(coords);
        
        // 200 >> 4 = 12
        // -200 >> 4 = -13
        long key = ((long) 12 & 0xFFFFFFFFL) | ((long) -13L << 32);
        
        assertTrue(chunkManager.locAssChunks.containsKey(key));
        assertEquals(1, chunkManager.ticketCounts.get(key));
        verify(rtpChunkManager).getChunkAtAsync(world, 12, -13);
    }

    @Test
    void testRemoveTicket() {
        chunkManager.addTicket(5, 5);
        chunkManager.addTicket(5, 5);
        long key = ((long) 5 & 0xFFFFFFFFL) | ((long) 5L << 32);
        
        chunkManager.removeTicket(5, 5);
        assertEquals(1, chunkManager.ticketCounts.get(key));
        assertTrue(chunkManager.locAssChunks.containsKey(key));
        
        chunkManager.removeTicket(5, 5);
        assertNull(chunkManager.ticketCounts.get(key));
        assertFalse(chunkManager.locAssChunks.containsKey(key));
    }

    @Test
    void testFoliaRegionSchedulerAssignment() {
        Runnable task = mock(Runnable.class);
        chunkManager.runAt(30, 40, task);
        
        verify(scheduler).runTask(eq(world), eq(30), eq(40), eq(task));
        
        chunkManager.runAtFixedRate(50, 60, task, 10, 20);
        verify(scheduler).runTaskTimer(eq(world), eq(50), eq(60), eq(task), eq(10L), eq(20L));
    }
}
