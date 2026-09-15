package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPCommandSender;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ForceQueueTest {

    @TempDir
    File tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        ForceQueue.preActions.clear();
        ForceQueue.postActions.clear();
    }

    @AfterEach
    void tearDown() {
        ForceQueue.preActions.clear();
        ForceQueue.postActions.clear();
    }

    @Test
    void defaultConstructor_usesServerSenderAndPermRegions() {
        ForceQueue forceQueue = new ForceQueue();
        assertNotNull(forceQueue.sender());
        assertEquals(CommandsAPI.serverId, forceQueue.sender().uuid());
        assertEquals(RTP.selectionAPI.permRegionLookup.values().size(), forceQueue.regions().size());
        assertEquals("rtp_force_queue", forceQueue.sparkFrameName());
    }

    @Test
    void constructor_withSender_usesSpecifiedSender() {
        UUID customId = UUID.randomUUID();
        RTPCommandSender sender = new MockRTPCommandSender(customId, "CustomSender");
        ForceQueue forceQueue = new ForceQueue(sender);
        assertSame(sender, forceQueue.sender());
        assertEquals(RTP.selectionAPI.permRegionLookup.values().size(), forceQueue.regions().size());
    }

    @Test
    void constructor_withSenderAndRegions_usesSpecifiedRegions() {
        UUID customId = UUID.randomUUID();
        RTPCommandSender sender = new MockRTPCommandSender(customId, "CustomSender");
        Region regionMock = mock(Region.class);
        List<Region> regions = Collections.singletonList(regionMock);

        ForceQueue forceQueue = new ForceQueue(sender, regions);
        assertSame(sender, forceQueue.sender());
        assertEquals(regions, forceQueue.regions());
    }

    @Test
    void constructor_withNullOrEmptyRegions_fallsBackToPermRegions() {
        UUID customId = UUID.randomUUID();
        RTPCommandSender sender = new MockRTPCommandSender(customId, "CustomSender");

        ForceQueue nullRegionsQueue = new ForceQueue(sender, null);
        assertEquals(RTP.selectionAPI.permRegionLookup.values().size(), nullRegionsQueue.regions().size());

        ForceQueue emptyRegionsQueue = new ForceQueue(sender, Collections.emptyList());
        assertEquals(RTP.selectionAPI.permRegionLookup.values().size(), emptyRegionsQueue.regions().size());
    }

    @Test
    void run_executesPreActionsRegionsAndPostActionsInOrder() {
        List<String> events = new ArrayList<>();
        ForceQueue.preActions.add(fq -> events.add("pre"));
        ForceQueue.postActions.add(fq -> events.add("post"));

        Region r1 = mock(Region.class);
        Region r2 = mock(Region.class);

        doAnswer(inv -> {
            events.add("r1");
            return null;
        }).when(r1).execute(anyLong());

        doAnswer(inv -> {
            events.add("r2");
            return null;
        }).when(r2).execute(anyLong());

        UUID customId = UUID.randomUUID();
        RTPCommandSender sender = new MockRTPCommandSender(customId, "CustomSender");
        ForceQueue forceQueue = new ForceQueue(sender, List.of(r1, r2));

        forceQueue.run();

        assertEquals(List.of("pre", "r1", "r2", "post"), events);
        verify(r1, times(1)).execute(anyLong());
        verify(r2, times(1)).execute(anyLong());
    }

    @Test
    void equalsAndHashCodeContract() {
        UUID id1 = UUID.randomUUID();
        RTPCommandSender sender1 = new MockRTPCommandSender(id1, "Sender1");
        RTPCommandSender sender2 = new MockRTPCommandSender(UUID.randomUUID(), "Sender2");

        Region r1 = mock(Region.class);
        Region r2 = mock(Region.class);

        ForceQueue fq1 = new ForceQueue(sender1, List.of(r1));
        ForceQueue fq1Same = new ForceQueue(sender1, List.of(r1));
        ForceQueue fq2 = new ForceQueue(sender2, List.of(r1));
        ForceQueue fq3 = new ForceQueue(sender1, List.of(r2));

        assertEquals(fq1, fq1);
        assertEquals(fq1, fq1Same);
        assertEquals(fq1.hashCode(), fq1Same.hashCode());

        assertNotEquals(fq1, null);
        assertNotEquals(fq1, "string");
        assertNotEquals(fq1, fq2);
        assertNotEquals(fq1, fq3);
    }

    @Test
    void toStringContainsSenderAndRegions() {
        UUID customId = UUID.randomUUID();
        RTPCommandSender sender = new MockRTPCommandSender(customId, "CustomSender");
        ForceQueue forceQueue = new ForceQueue(sender, Collections.emptyList());
        String str = forceQueue.toString();
        assertTrue(str.contains("sender="));
        assertTrue(str.contains("regions="));
    }
}
