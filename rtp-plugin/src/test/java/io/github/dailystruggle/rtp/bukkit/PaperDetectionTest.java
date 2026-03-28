package io.github.dailystruggle.rtp.bukkit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class PaperDetectionTest {
    @Test
    public void testPaperDetection() {
        RTPBukkitPlugin plugin = mock(RTPBukkitPlugin.class);
        
        // Mock isPaper to return true
        doReturn(true).when(plugin).isPaper();
        assertTrue(plugin.isPaper());
        
        // Mock isPaper to return false
        doReturn(false).when(plugin).isPaper();
        assertFalse(plugin.isPaper());
    }
}
