package io.github.dailystruggle.helpers.stresstestrtp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RunnerPhaseDispatchedTest {

    @Test
    @DisplayName("seqPhaseDispatched is AtomicInteger and satisfies count cap logic")
    void testSeqPhaseDispatchedFieldAndCap() throws Exception {
        Field dispatchedField = Runner.class.getDeclaredField("seqPhaseDispatched");
        dispatchedField.setAccessible(true);
        assertEquals(AtomicInteger.class, dispatchedField.getType(), "seqPhaseDispatched must be AtomicInteger");

        sun.misc.Unsafe unsafe;
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        unsafe = (sun.misc.Unsafe) f.get(null);

        Runner runner = (Runner) unsafe.allocateInstance(Runner.class);
        AtomicInteger seqDispatched = new AtomicInteger(0);
        dispatchedField.set(runner, seqDispatched);

        Field modeField = Runner.class.getDeclaredField("mode");
        modeField.setAccessible(true);
        modeField.set(runner, Runner.Mode.SEQUENCE);

        Field warmupField = Runner.class.getDeclaredField("warmupActive");
        warmupField.setAccessible(true);
        warmupField.set(runner, false);

        Field perTargetCountField = Runner.class.getDeclaredField("seqPerTargetCount");
        perTargetCountField.setAccessible(true);
        perTargetCountField.set(runner, 10L);

        Method capMethod = Runner.class.getDeclaredMethod("seqCountCapReached");
        capMethod.setAccessible(true);

        assertFalse((Boolean) capMethod.invoke(runner), "cap should not be reached at 0");

        seqDispatched.set(9);
        assertFalse((Boolean) capMethod.invoke(runner), "cap should not be reached at 9");

        seqDispatched.set(10);
        assertTrue((Boolean) capMethod.invoke(runner), "cap should be reached at 10");

        seqDispatched.set(15);
        assertTrue((Boolean) capMethod.invoke(runner), "cap should remain reached above 10");
    }
}
