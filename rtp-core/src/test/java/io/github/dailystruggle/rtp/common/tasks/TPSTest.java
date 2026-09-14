package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class TPSTest {
    private MockedStatic<RTP> rtpMockedStatic;
    private RTPServerAccessor mockAccessor;

    @BeforeEach
    void setUp() {
        rtpMockedStatic = mockStatic(RTP.class);
        mockAccessor = mock(RTPServerAccessor.class);
        RTP.serverAccessor = mockAccessor;
    }

    @AfterEach
    void tearDown() {
        rtpMockedStatic.close();
    }

    @Test
    void testGetTPSDelegatesToServerAccessor() {
        when(mockAccessor.getTPS(20)).thenReturn(19.5);
        when(mockAccessor.getTPS(100)).thenReturn(20.0);

        assertEquals(19.5, TPS.getTPS(20), 1e-6);
        assertEquals(20.0, TPS.getTPS(100), 1e-6);
        verify(mockAccessor).getTPS(20);
        verify(mockAccessor).getTPS(100);
    }

    @Test
    void testTimeSinceTickCalculation() {
        when(mockAccessor.getTPS(20)).thenReturn(20.0);
        // (1000.0 * 20 / 20.0) = 1000
        assertEquals(1000L, TPS.timeSinceTick(20));

        when(mockAccessor.getTPS(10)).thenReturn(10.0);
        // (1000.0 * 10 / 10.0) = 1000
        assertEquals(1000L, TPS.timeSinceTick(10));

        when(mockAccessor.getTPS(10)).thenReturn(20.0);
        // (1000.0 * 10 / 20.0) = 500
        assertEquals(500L, TPS.timeSinceTick(10));
    }

    @Test
    void testTPSConstructorForCoverage() {
        TPS tps = new TPS();
        org.junit.jupiter.api.Assertions.assertNotNull(tps);
    }
}
