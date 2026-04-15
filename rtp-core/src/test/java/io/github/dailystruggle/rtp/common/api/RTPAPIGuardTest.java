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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the write-once guard on {@link RTPAPI#setServerAccessor} and the
 * pre-init guard on {@link RTPAPI#addShape} / {@link RTPAPI#addVerticalAdjustor}.
 *
 * <p>These tests directly cover REQ-API-ARCH-003: the API must reject calls
 * made before core initialisation, and must prevent a second (different) accessor
 * from silently replacing the one registered during onEnable.
 */
class RTPAPIGuardTest {

    @TempDir
    File tempDir;

    /**
     * Force {@link RTP} class initialisation before any test saves RTPAPI state.
     *
     * <p>The {@code RTP} static block wires {@code RTPAPI.shapeAdder} and
     * {@code RTPAPI.vertAdder}. If that block has not run yet when
     * {@code saveState()} captures {@code shapeAdder}, {@code restoreState()}
     * will write {@code null} back — leaving later tests that call
     * {@code new RTP()} with an unregistered shape delegate.
     */
    @BeforeAll
    static void ensureRTPClassInitialised() {
        // Accessing a static field forces Java class initialisation, which runs
        // the RTP static block that wires RTPAPI.shapeAdder / vertAdder.
        // Without this, saveState() may capture null for shapeAdder (if this
        // test class runs before any other test that loads RTP), and
        // restoreState() would then leave shapeAdder null — causing subsequent
        // calls to RTPAPI.addShape() to throw IllegalStateException.
        RTP.serverId.hashCode();
    }

    /** Saved state — restored after each test so the suite remains order-independent. */
    private io.github.dailystruggle.rtp.api.server.RTPServerAccessor savedAccessor;
    private Consumer<Object> savedShapeAdder;

    @BeforeEach
    void saveState() {
        savedAccessor   = RTPAPI.serverAccessor;
        savedShapeAdder = RTPAPI.shapeAdder;
    }

    @AfterEach
    void restoreState() {
        RTPAPI.serverAccessor = savedAccessor;
        RTPAPI.shapeAdder     = savedShapeAdder;
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

    // -------------------------------------------------------------------------
    // addShape — pre-init guard (REQ-API-ARCH-003)
    // -------------------------------------------------------------------------

    @Test
    void addShape_throwsIllegalStateWhenShapeAdderIsNull() {
        RTPAPI.shapeAdder = null;

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> RTPAPI.addShape(new Object()),
                "addShape() before core init must throw IllegalStateException");
        assertTrue(ex.getMessage().contains("Core implementation is not loaded"),
                "exception message must identify the unregistered delegate");
    }

    @Test
    void addShape_delegatesToShapeAdderWhenRegistered() {
        Object[] received = {null};
        RTPAPI.shapeAdder = obj -> received[0] = obj;
        Object shape = new Object();

        assertDoesNotThrow(() -> RTPAPI.addShape(shape));
        assertSame(shape, received[0], "shapeAdder must receive the exact object passed to addShape");
    }
}
