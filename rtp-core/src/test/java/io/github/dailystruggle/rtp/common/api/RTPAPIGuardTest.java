package io.github.dailystruggle.rtp.common.api;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the write-once guard on {@link RTPAPI#setServerAccessor}.
 *
 * <p>These tests directly cover REQ-API-ARCH-003: the API must prevent a second
 * (different) accessor from silently replacing the one registered during
 * onEnable. (Custom-shape / vertical-adjustor registration moved to the typed
 * {@code RTP.addShape}/{@code RTP.addVerticalAdjustor} extension-tier entry
 * points under the two-tier API model, so the former {@code RTPAPI.addShape}
 * pre-init guards no longer apply here.)
 */
class RTPAPIGuardTest {

    @TempDir
    File tempDir;

    /**
     * Force {@link RTP} class initialisation before any test saves RTPAPI state,
     * so the suite stays order-independent regardless of which test first loads
     * the {@code RTP} static block.
     */
    @BeforeAll
    static void ensureRTPClassInitialised() {
        RTP.serverId.hashCode();
    }

    /** Saved state — restored after each test so the suite remains order-independent. */
    private io.github.dailystruggle.rtp.api.server.RTPServerAccessor savedAccessor;

    @BeforeEach
    void saveState() {
        savedAccessor = RTPAPI.serverAccessor;
    }

    @AfterEach
    void restoreState() {
        RTPAPI.serverAccessor = savedAccessor;
    }

    // -------------------------------------------------------------------------
    // setServerAccessor — write-once contract
    // -------------------------------------------------------------------------

    @Test
    void setServerAccessor_succeedsWhenFieldIsNull() {
        RTPAPI.serverAccessor = null;
        MockRTPServerAccessor mock = new MockRTPServerAccessor(tempDir);

        assertDoesNotThrow(() -> RTPAPI.setServerAccessor(mock),
                "first call with a non-null accessor must succeed");
        assertSame(mock, RTPAPI.serverAccessor);
    }

    @Test
    void setServerAccessor_isIdempotentForSameInstance() {
        RTPAPI.serverAccessor = null;
        MockRTPServerAccessor mock = new MockRTPServerAccessor(tempDir);
        RTPAPI.setServerAccessor(mock);

        assertDoesNotThrow(() -> RTPAPI.setServerAccessor(mock),
                "setting the same instance a second time must be a safe no-op");
    }

    @Test
    void setServerAccessor_throwsWhenDifferentInstanceAlreadyRegistered() {
        RTPAPI.serverAccessor = null;
        MockRTPServerAccessor first  = new MockRTPServerAccessor(tempDir);
        MockRTPServerAccessor second = new MockRTPServerAccessor(tempDir);

        RTPAPI.setServerAccessor(first);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> RTPAPI.setServerAccessor(second),
                "setting a different accessor after initialisation must throw");
        assertTrue(ex.getMessage().contains("already initialised"),
                "exception message must mention 'already initialised'");
    }

    @Test
    void setServerAccessor_throwsOnNullArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> RTPAPI.setServerAccessor(null),
                "null accessor must be rejected with IllegalArgumentException");
    }
}
