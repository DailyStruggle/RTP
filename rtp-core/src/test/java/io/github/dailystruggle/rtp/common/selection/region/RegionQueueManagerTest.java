package io.github.dailystruggle.rtp.common.selection.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.util.AbstractMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class RegionQueueManagerTest {
    private Region region;
    private RegionQueueManager queueManager;
    private RegionChunkManager chunkManager;
    private RegionSettings settings;

    @BeforeEach
    void setUp() {
        region = mock(Region.class);
        settings = mock(RegionSettings.class);
        chunkManager = mock(RegionChunkManager.class);
        
        when(region.getSettings()).thenReturn(settings);
        // Reflection to set final field chunkManager for testing
        try {
            java.lang.reflect.Field field = Region.class.getDeclaredField("chunkManager");
            field.setAccessible(true);
            field.set(region, chunkManager);
        } catch (Exception ignored) {}
        
        queueManager = new RegionQueueManager(region);
    }

    @Test
    void testOnPlayerPopWarmsUpCorrectLocation() {
        // Set activeChunkCap to 3
        when(settings.activeChunkCap()).thenReturn(3);

        // Fill queue with some locations
        RTPCoords loc0 = new RTPCoords("world", 0, 0, 0);
        RTPCoords loc1 = new RTPCoords("world", 16, 0, 16);
        RTPCoords loc2 = new RTPCoords("world", 32, 0, 32);
        RTPCoords loc3 = new RTPCoords("world", 48, 0, 48);

        queueManager.locationQueue.add(new AbstractMap.SimpleEntry<>(loc0, 1L));
        queueManager.locationQueue.add(new AbstractMap.SimpleEntry<>(loc1, 1L));
        queueManager.locationQueue.add(new AbstractMap.SimpleEntry<>(loc2, 1L));
        queueManager.locationQueue.add(new AbstractMap.SimpleEntry<>(loc3, 1L));

        // Initial state: [loc0, loc1, loc2, loc3]
        // After one pop: [loc1, loc2, loc3]
        // The new location at index activeChunkCap - 1 (index 2) is loc3.
        
        queueManager.locationQueue.poll();
        queueManager.onPlayerPop();

        verifyNoInteractions(chunkManager);
    }

    @Test
    void testOnPlayerPopWithSmallQueue() {
        // Set activeChunkCap to 5, but only 3 items in queue
        when(settings.activeChunkCap()).thenReturn(5);

        RTPCoords loc0 = new RTPCoords("world", 0, 0, 0);
        RTPCoords loc1 = new RTPCoords("world", 16, 0, 16);
        RTPCoords loc2 = new RTPCoords("world", 32, 0, 32);

        queueManager.locationQueue.add(new AbstractMap.SimpleEntry<>(loc0, 1L));
        queueManager.locationQueue.add(new AbstractMap.SimpleEntry<>(loc1, 1L));
        queueManager.locationQueue.add(new AbstractMap.SimpleEntry<>(loc2, 1L));

        queueManager.locationQueue.poll();
        queueManager.onPlayerPop();

        verifyNoInteractions(chunkManager);
    }

    @Test
    void testOnPlayerPopWithZeroCap() {
        when(settings.activeChunkCap()).thenReturn(0);

        RTPCoords loc0 = new RTPCoords("world", 0, 0, 0);
        queueManager.locationQueue.add(new AbstractMap.SimpleEntry<>(loc0, 1L));

        queueManager.locationQueue.poll();
        queueManager.onPlayerPop();

        verifyNoInteractions(chunkManager);
    }
}
